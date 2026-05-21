package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import com.google.common.collect.ImmutableMap;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Health.ActiveCondition;
import tterrag1112.life_in_the_village.Npc.Health.HealerInventory;
import tterrag1112.life_in_the_village.Npc.Health.HealthCondition;
import tterrag1112.life_in_the_village.Npc.Health.HealthSavedData;
import tterrag1112.life_in_the_village.Npc.Health.Plague;
import tterrag1112.life_in_the_village.Npc.Health.Remedy;
import tterrag1112.life_in_the_village.Npc.Health.RemedyType;
import tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spec line 123. Three phases:
 * <ol>
 *   <li>Look for a patient in the village with a treatable condition.</li>
 *   <li>If we have a remedy on hand, walk to them and treat (≈400t,
 *   plague triage = 200t).</li>
 *   <li>Otherwise return to the hut and produce remedies until the
 *   stash is full or a patient appears.</li>
 * </ol>
 *
 * <p>Diagnosis is auto-success for visible conditions. Misdiagnosis
 * (spec line 158) is a Phase 5 polish — v1 always picks the highest-
 * severity condition and a remedy that targets it.</p>
 */
public class HealerBehavior extends Behavior<TownspersonMob> {

    public static final int  TREATMENT_TICKS         = 400;
    public static final int  PLAGUE_TREATMENT_TICKS  = 200;
    public static final int  PRODUCE_TICKS           = 200;
    public static final int  XP_PER_TREATMENT        = 5;
    public static final int  XP_PER_REMEDY           = 1;
    public static final double ARRIVAL_SQ            = 4.0;
    public static final double PATIENT_SCAN_RADIUS   = 32.0;

    private enum Phase { IDLE, WALKING, TREATING, PRODUCING }

    private TownspersonMob entity;

    public HealerBehavior() {
        super(com.google.common.collect.ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), 24000);
    }
    private Phase phase = Phase.IDLE;
    private Building hut;
    private TownspersonMob patient;
    private HealthCondition targetCondition;
    private Remedy queuedRemedy;
    private int timer;

    

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        this.entity = entity;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (entity.getProfession() != Profession.HEALER) return false;
        if (!entity.isWorkTime() && !isPlagueOverride()) return false;
        if (!entity.getHealthComponent().canWork()) return false;

        hut = entity.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .orElse(null);
        if (hut == null) return false;

        // Prune expired remedies first so this run sees an accurate stash.
        entity.getHealerInventory().pruneExpired(level.getGameTime());

        // Pick a patient if there's a treatable one nearby. We check
        // remedy availability without removing it — the actual takeFor
        // happens in start() so a goal preempted between canUse() and
        // start() doesn't lose a remedy permanently.
        patient = findPatient(level);
        if (patient != null) {
            targetCondition = pickTarget(patient);
            return entity.getHealerInventory().hasRemedyFor(targetCondition);
        }
        // Otherwise, produce remedies if we have headroom in the stash.
        return !entity.getHealerInventory().isFull();
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        return switch (phase) {
            case IDLE -> false;
            case WALKING, TREATING -> patient != null && patient.isAlive()
                    && patient.getHealthComponent().hasCondition(targetCondition);
            case PRODUCING -> !entity.getHealerInventory().isFull()
                    && (entity.isWorkTime() || isPlagueOverride());
        };
    }

        @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        if (patient != null) {
            // Take the remedy now (canUse only checked availability).
            queuedRemedy = entity.getHealerInventory()
                    .takeFor(targetCondition).orElse(null);
            if (queuedRemedy == null) {
                // Stash drained between canUse() and start() — abort.
                phase = Phase.IDLE;
                patient = null;
                targetCondition = null;
                return;
            }
            phase = Phase.WALKING;
            entity.setCurrentActivity("Tending to " + patient.getNpcName());
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    patient.getX(), patient.getY(), patient.getZ(), 1.0));
        } else {
            phase = Phase.PRODUCING;
            entity.setCurrentActivity("Brewing remedies");
            BlockPos origin = hut.getShape().getOrigin();
            if (origin != null) {
                entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                        origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 1.0));
            }
        }
        timer = 0;
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        switch (phase) {
            case WALKING    -> tickWalking(level);
            case TREATING   -> tickTreating(level);
            case PRODUCING  -> tickProducing(level);
            case IDLE       -> {}
        }
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        // If we never delivered the queued remedy, return it to the stash.
        if (queuedRemedy != null && phase != Phase.TREATING) {
            entity.getHealerInventory().add(queuedRemedy);
        }
        phase = Phase.IDLE;
        patient = null;
        targetCondition = null;
        queuedRemedy = null;
        timer = 0;
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
    }

    // ── Phase ticks ────────────────────────────────────────────────────────

    private void tickWalking(ServerLevel level) {
        if (patient == null || !patient.isAlive()) { stop(); return; }
        double d2 = entity.distanceToSqr(patient);
        if (d2 <= ARRIVAL_SQ) {
            phase = Phase.TREATING;
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            timer = 0;
            entity.setCurrentActivity("Treating " + patient.getNpcName());
        } else {
            // Re-target the navigation (patient moves).
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    patient.getX(), patient.getY(), patient.getZ(), 1.0));
        }
    }

    private void tickTreating(ServerLevel level) {
        timer++;
        int needed = isPlagueOverride() ? PLAGUE_TREATMENT_TICKS : TREATMENT_TICKS;
        if (timer < needed) return;
        // Treatment complete.
        UUID healerId = entity.getUUID();
        long now = level.getGameTime();
        patient.getHealthComponent().markTreated(targetCondition, healerId, now);
        patient.getMood().applyWithRawMagnitude(MoodTrigger.HEALED,
                MoodTrigger.HEALED.defaultMagnitude(), now);
        entity.getSkills().addXp(Skill.MEDICINE, XP_PER_TREATMENT, now);
        // Remedy is consumed (queuedRemedy was already removed from
        // inventory in canUse()). Clear so stop() doesn't re-stash it.
        queuedRemedy = null;
        phase = Phase.IDLE;
    }

    private void tickProducing(ServerLevel level) {
        timer++;
        if (timer < PRODUCE_TICKS) return;
        if (entity.getHealerInventory().isFull()) { phase = Phase.IDLE; return; }
        // Pick the next remedy type cyclically by skill / season —
        // v1 just rotates through the enum so the stash diversifies.
        RemedyType next = pickRemedyType(level);
        int potency = potencyForSkill(entity.getSkills().getLevel(Skill.MEDICINE));
        Remedy r = Remedy.create(next, potency, level.getGameTime());
        entity.getHealerInventory().add(r);
        entity.getSkills().addXp(Skill.MEDICINE, XP_PER_REMEDY, level.getGameTime());
        timer = 0;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private boolean isPlagueOverride() {
        Village v = entity.getAssignedVillageName()
                .flatMap(n -> VillageSavedData.get(level).getVillageByName(n))
                .orElse(null);
        if (v == null) return false;
        return HealthSavedData.get(level).getActivePlague(v.getId())
                .map(Plague::isActive).orElse(false);
    }

    private TownspersonMob findPatient(ServerLevel level) {
        AABB box = new AABB(entity.blockPosition()).inflate(PATIENT_SCAN_RADIUS);
        List<TownspersonMob> nearby = level.getEntitiesOfClass(
                TownspersonMob.class, box,
                npc -> npc != entity && npc.isAlive()
                        && hasTreatableCondition(npc)
                        && entity.getHealerInventory()
                        .hasRemedyFor(highestSeverity(npc).type()));
        // Plague triage: prioritise PLAGUE_CARRIER patients.
        return nearby.stream()
                .max(Comparator.<TownspersonMob>comparingInt(npc ->
                        npc.getHealthComponent().hasCondition(HealthCondition.PLAGUE_CARRIER) ? 100 : 0)
                        .thenComparingInt(npc -> highestSeverity(npc).severity())
                        .thenComparingInt(npc -> npc.getHealthComponent().constitution()))
                .orElse(null);
    }

    private static boolean hasTreatableCondition(TownspersonMob npc) {
        for (ActiveCondition c : npc.getHealthComponent().active()) {
            if (c.type().requiresMedicine() && !c.treated()) return true;
        }
        return false;
    }

    private static ActiveCondition highestSeverity(TownspersonMob npc) {
        ActiveCondition best = null;
        for (ActiveCondition c : npc.getHealthComponent().active()) {
            if (!c.type().requiresMedicine() || c.treated()) continue;
            if (best == null || c.severity() > best.severity()) best = c;
        }
        return best;
    }

    private static HealthCondition pickTarget(TownspersonMob npc) {
        ActiveCondition c = highestSeverity(npc);
        return c == null ? HealthCondition.MINOR_WOUND : c.type();
    }

    private RemedyType pickRemedyType(ServerLevel level) {
        // Plague active → produce antidote first.
        if (isPlagueOverride()
                && entity.getHealerInventory().remedies().stream()
                .noneMatch(r -> r.type() == RemedyType.PLAGUE_ANTIDOTE)) {
            return RemedyType.PLAGUE_ANTIDOTE;
        }
        // Cycle through types so the stash diversifies.
        int dayIndex = (int) ((level.getGameTime() / 24000L)
                % RemedyType.values().length);
        return RemedyType.values()[dayIndex];
    }

    private static int potencyForSkill(int medicineLevel) {
        if (medicineLevel >= 60) return 3;
        if (medicineLevel >= 30) return 2;
        return 1;
    }

    /** Suppresses unused-import warning on Optional. */
    @SuppressWarnings("unused")
    private static final Optional<HealerInventory> DOC_HOOK = Optional.empty();

    /** Bridge helper — Goal-side used entity.getNavigation().moveTo(x,y,z,speed);
     *  Behavior-side writes WALK_TARGET memory and lets CORE MoveToTargetSink steer. */
    private static WalkTarget navWalkTarget(double x, double y, double z, double speed) {
        return new WalkTarget(net.minecraft.core.BlockPos.containing(x, y, z), (float) speed, 1);
    }

}
