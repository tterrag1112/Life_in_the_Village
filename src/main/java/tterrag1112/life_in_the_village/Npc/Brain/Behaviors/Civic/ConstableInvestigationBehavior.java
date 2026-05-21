package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Civic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import com.google.common.collect.ImmutableMap;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Memory.MemoryType;
import tterrag1112.life_in_the_village.Npc.Office.OfficePower;
import tterrag1112.life_in_the_village.Npc.Office.Powers.PowerGrant;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Constable's daily investigation. Spec line 172.
 *
 * <p>Eligible only when the NPC currently holds the {@link
 * OfficePower#INVESTIGATE_CRIME} power on its assigned village (i.e.
 * is the seated village_constable). Picks the highest-severity oldest
 * FILED report in the village; walks to the scene; corroborates
 * witness memories; if ≥2 corroborating witnesses OR a physical-item
 * note → marks {@link ReportStatus#TRIAL_SCHEDULED}, else
 * {@link ReportStatus#DISMISSED}.</p>
 *
 * <p>Goal priority: P_WORK_PRIMARY (8) — investigation is the
 * constable's main daytime task. Yields to combat / survival
 * naturally because they're at lower numeric priority.</p>
 */
public class ConstableInvestigationBehavior extends Behavior<TownspersonMob> {

    private static final int INTERACT_RANGE_SQ = 16;

    private TownspersonMob entity;

    public ConstableInvestigationBehavior() {
        super(com.google.common.collect.ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), 24000);
    }
    private CrimeReport target;
    private boolean arrived;

    

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        this.entity = entity;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (!entity.isWorkTime()) return false;
        if (!hasInvestigationPower(level)) return false;
        target = pickReport(level);
        return target != null;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        return target != null && entity.isAlive();
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        arrived = false;
        entity.setCurrentActivity("Investigating crime scene");
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        if (target == null) return;
        BlockPos scene = target.location();
        if (!arrived) {
            double distSq = entity.distanceToSqr(scene.getX(), scene.getY(), scene.getZ());
            if (distSq <= INTERACT_RANGE_SQ) {
                arrived = true;
                entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            } else {
                entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(scene.getX(), scene.getY(), scene.getZ(), 1.0));
                return;
            }
        }
        // At scene: gather evidence and forward.
        completeInvestigation(level);
        target = null;
    }

    @Override protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        target = null;
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private boolean hasInvestigationPower(ServerLevel level) {
        Village v = entity.getAssignedVillageName()
                .flatMap(VillageSavedData.get(level)::getVillageByName)
                .orElse(null);
        if (v == null) return false;
        return PowerGrant.hasPower(entity.getUUID(), OfficePower.INVESTIGATE_CRIME, v);
    }

    /** Highest-severity oldest FILED report in the constable's village. */
    private CrimeReport pickReport(ServerLevel level) {
        Village v = entity.getAssignedVillageName()
                .flatMap(VillageSavedData.get(level)::getVillageByName)
                .orElse(null);
        if (v == null) return null;
        CrimeSavedData crimes = CrimeSavedData.get(level);
        return crimes.reportsByStatus(v.getId(), ReportStatus.FILED).stream()
                .sorted((a, b) -> {
                    int sevDelta = b.type().severity().ordinal() - a.type().severity().ordinal();
                    if (sevDelta != 0) return sevDelta;
                    return Long.compare(a.reportedTick(), b.reportedTick());
                })
                .findFirst().orElse(null);
    }

    private void completeInvestigation(ServerLevel level) {
        CrimeSavedData crimes = CrimeSavedData.get(level);
        long now = level.getGameTime();

        // Build evidence list: validated witnesses + circumstantial.
        List<TrialEvidence> evidence = new ArrayList<>();
        int corroboratingWitnesses = 0;
        boolean physicalEvidence = false;

        for (UUID witnessId : target.witnessIds()) {
            TownspersonMob witness = TownspersonMob.findByUUID(level, witnessId).orElse(null);
            if (witness == null) continue;
            // Validate via memory: spec line 176 — corroborating
            // WITNESSED_CRIME_BY entry naming the perpetrator.
            UUID perpId = target.perpetratorId().orElse(null);
            if (perpId == null) continue;
            boolean corroborates = witness.getMemory().hasMemoryOf(
                    MemoryType.WITNESSED_CRIME_BY, perpId);
            if (!corroborates) continue;

            // Credibility weight per spec line 187.
            float weight = witnessWeight(witness, perpId);
            evidence.add(new TrialEvidence(EvidenceType.WITNESS_TESTIMONY,
                    witness.getNpcName() + " saw the act",
                    Optional.of(witnessId), weight));
            corroboratingWitnesses++;
        }

        // Physical evidence: stolen item heuristic — if the perpetrator
        // is an NPC and any THEFT note mentions a value, mark physical.
        // For player perpetrators we can't inspect inventory cleanly
        // here without a level-side reflection; documented for follow-up.
        for (String note : target.evidenceNotes()) {
            if (note.toLowerCase(java.util.Locale.ROOT).contains("bronze")) {
                evidence.add(new TrialEvidence(EvidenceType.PHYSICAL_ITEM,
                        note, Optional.empty(), 1.0f));
                physicalEvidence = true;
                break;
            }
        }

        boolean enoughEvidence = corroboratingWitnesses >= 2 || physicalEvidence;
        if (!enoughEvidence) {
            crimes.putReport(target.withStatus(ReportStatus.DISMISSED)
                    .withNote("Insufficient evidence at investigation"));
            return;
        }

        // Schedule trial 1-3 days out per spec line 206. v1 picks 1 day
        // for MINOR/MODERATE, 2 days for SERIOUS, 3 days for CAPITAL.
        long delayDays = switch (target.type().severity()) {
            case MINOR, MODERATE -> 1L;
            case SERIOUS -> 2L;
            case CAPITAL -> 3L;
        };
        long scheduledTick = now + delayDays * 24000L;

        Trial trial = new Trial(UUID.randomUUID(), target.reportId(),
                Optional.empty(), List.of(),
                evidence, List.of(),
                scheduledTick, 0L, TrialVerdict.PENDING, Optional.empty());
        crimes.putTrial(trial);
        crimes.putReport(target.withTrial(trial.trialId())
                .withInvestigator(entity.getUUID()));
    }

    /**
     * Spec line 215: {@code 0.5 + Honesty × 0.3 - abs(rel_to_accused × 0.003)}.
     * Combined with the v1 evidence-weight band (0.2 / 0.5 / 0.8), we
     * map honest+neutral to 0.8, honest+rival to 0.2, etc.
     */
    private static float witnessWeight(TownspersonMob witness, UUID accused) {
        float honesty = witness.getTraitVector().get(
                tterrag1112.life_in_the_village.Npc.Traits.TraitAxis.HONESTY);
        int rel = witness.getNpcRelationships().getScore(accused);
        float credibility = 0.5f + honesty * 0.3f - Math.abs(rel) * 0.003f;
        if (credibility >= 0.7f) return 0.8f;
        if (credibility <= 0.3f) return 0.2f;
        return 0.5f;
    }

    /** Bridge helper — Goal-side used entity.getNavigation().moveTo(x,y,z,speed);
     *  Behavior-side writes WALK_TARGET memory and lets CORE MoveToTargetSink steer. */
    private static WalkTarget navWalkTarget(double x, double y, double z, double speed) {
        return new WalkTarget(net.minecraft.core.BlockPos.containing(x, y, z), (float) speed, 1);
    }

}
