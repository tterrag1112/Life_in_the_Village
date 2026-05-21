package tterrag1112.life_in_the_village.Npc.Aging;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Events.EventDispatcher;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;
import tterrag1112.life_in_the_village.Npc.Memory.NpcMemory;
import tterrag1112.life_in_the_village.Npc.Relations.NpcRelationship;
import tterrag1112.life_in_the_village.Npc.Skills.ProfessionSkills;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bus listener that fires on TEEN/ADULT → ELDERLY transitions.
 *
 * <p>Spec line 158-219 — sets retirement decision (full retire vs.
 * mentor track), rolls unfinished business with weights driven by
 * Ambition + memory state, and primes the unfinished-business soft
 * goal via the existing LifeGoalSet.</p>
 */
public final class RetirementHandler implements EventDispatcher {

    /** Skill at or above which an elderly NPC qualifies as a mentor.
     *  Phase 6.3.2.b — sourced from {@link tterrag1112.life_in_the_village.Npc.Skills.SkillThresholds}. */
    public static final int MENTOR_SKILL_THRESHOLD =
            tterrag1112.life_in_the_village.Npc.Skills.SkillThresholds.MENTOR_SKILL_THRESHOLD;
    /** Base probability of having unfinished business at ELDERLY entry. */
    public static final float BASE_UNFINISHED_PROB = 0.35f;
    /** Ambition contribution to that probability (per +1 trait). */
    public static final float UNFINISHED_AMBITION_WEIGHT = 0.30f;

    @Override public String name() { return "retirement_handler"; }

    @Override
    public void onEvent(NpcLifeEvent event) {
        if (!(event instanceof NpcLifeEvent.LifeStageAdvanced lsa)) return;
        if (!"ELDERLY".equalsIgnoreCase(lsa.newStage())) return;
        TownspersonMob npc = lsa.subject();
        if (npc == null || !(npc.level() instanceof ServerLevel sl)) return;

        long now = sl.getGameTime();
        RetirementState state = npc.getRetirementState();
        if (state.retirementStartTick() != 0L) return; // already initialised
        state.setRetirementStartTick(now);

        // Retirement decision (spec line 162). Profession + Industry
        // trait combine.
        boolean fullyRetire = decideFullRetirement(npc);
        state.setFullyRetired(fullyRetire);

        // Mentor eligibility: skill ≥ 60 in profession's primary
        // skill. Sets actingAsMentor false initially — MentorGoal
        // will flip it true once a mentee is picked.
        Skill primary = ProfessionSkills.of(npc.getProfession())
                .map(ProfessionSkills::primary).orElse(null);
        if (!fullyRetire && primary != null
                && npc.getSkills().getLevel(primary) >= MENTOR_SKILL_THRESHOLD) {
            state.setActingAsMentor(false); // pending — MentorGoal sets target
        }

        // Unfinished-business roll.
        rollUnfinishedBusiness(npc, sl, state, now);
    }

    private static boolean decideFullRetirement(TownspersonMob npc) {
        // Spec line 168: physical professions retire; priest doesn't;
        // industry trait shifts the line.
        float industry = npc.getTraitVector().get(TraitAxis.INDUSTRY);
        Profession p = npc.getProfession();
        // Hard never-retire roles.
        if (p == Profession.PRIEST || p == Profession.SCHOLAR
                || p == Profession.LIBRARIAN || p == Profession.SCRIBE
                || p == Profession.VILLAGE_LEADER || p == Profession.KINGDOM_RULER
                || p == Profession.HERALD || p == Profession.CHANCELLOR) {
            return false;
        }
        // Physically demanding roles — full retire baseline.
        boolean physical = p == Profession.MINER || p == Profession.GUARD
                || p == Profession.FARMER
                || p == Profession.BUILDER || p == Profession.CARPENTER
                || p == Profession.STONEMASON;
        if (physical && industry < 0.5f) return true;
        return false;
    }

    /**
     * Spec line 200-220. Picks a business type weighted by trait +
     * memory state and seeds it on RetirementState. Doesn't add the
     * goal to LifeGoalSet directly because the goal-priority shift
     * happens at producer-side scoring (see Revision Notes).
     */
    private static void rollUnfinishedBusiness(TownspersonMob npc, ServerLevel level,
                                               RetirementState state, long now) {
        float ambition = npc.getTraitVector().get(TraitAxis.AMBITION);
        // Long-lived memory contribution: any unresolved promised /
        // negative entries about specific NPCs nudge the probability.
        long memorySpurs = npc.getMemory().all().stream()
                .filter(m -> isUnresolvedSpur(m))
                .count();
        float prob = BASE_UNFINISHED_PROB
                + Math.max(0f, ambition) * UNFINISHED_AMBITION_WEIGHT
                + Math.min(0.30f, memorySpurs * 0.10f);
        RandomSource rng = level.getRandom();
        if (rng.nextFloat() >= prob) return;

        // Pick a type, weighted by available targets in the world.
        UnfinishedBusinessType type = pickType(npc, rng);
        UUID relatedId = pickRelatedTarget(npc, type);
        String description = describe(type, npc, relatedId, level);
        state.setUnfinished(new UnfinishedBusiness(
                type, description,
                relatedId == null ? java.util.Optional.empty()
                        : java.util.Optional.of(relatedId),
                now, false));
    }

    private static boolean isUnresolvedSpur(NpcMemory m) {
        return switch (m.type()) {
            case PROMISED_BY,
                 INSULTED_BY,
                 VICTIM_OF_CRIME_BY,
                 WITNESSED_CRIME_BY -> true;
            default -> false;
        };
    }

    private static UnfinishedBusinessType pickType(TownspersonMob npc, RandomSource rng) {
        UnfinishedBusinessType[] options = UnfinishedBusinessType.values();
        // Prefer APPRENTICE_COMPLETION when the NPC has master-level
        // skill; prefer RECONCILIATION when they have any negative
        // relationship; else random uniform.
        var primary = ProfessionSkills.of(npc.getProfession())
                .map(ProfessionSkills::primary).orElse(null);
        if (primary != null && npc.getSkills().getLevel(primary) >= MENTOR_SKILL_THRESHOLD
                && rng.nextFloat() < 0.4f) {
            return UnfinishedBusinessType.APPRENTICE_COMPLETION;
        }
        if (!npc.getNpcRelationships().rivalsAndWorse().isEmpty()
                && rng.nextFloat() < 0.4f) {
            return UnfinishedBusinessType.RECONCILIATION;
        }
        return options[rng.nextInt(options.length)];
    }

    private static UUID pickRelatedTarget(TownspersonMob npc, UnfinishedBusinessType type) {
        return switch (type) {
            case RECONCILIATION -> {
                List<NpcRelationship> rivals = npc.getNpcRelationships().rivalsAndWorse();
                yield rivals.isEmpty() ? null : rivals.get(0).otherId();
            }
            case INHERITANCE_ARRANGEMENT -> npc.getFamily().getChildrenIds().stream()
                    .findFirst().orElse(null);
            case UNKEPT_PROMISE -> {
                Optional<NpcMemory> promise = npc.getMemory().all().stream()
                        .filter(m -> m.type() == tterrag1112.life_in_the_village.Npc.Memory.MemoryType.PROMISED_BY)
                        .findFirst();
                yield promise.map(m -> m.participantIds().isEmpty() ? null
                        : m.participantIds().get(0)).orElse(null);
            }
            default -> null;
        };
    }

    private static String describe(UnfinishedBusinessType type,
                                   TownspersonMob npc, UUID related,
                                   ServerLevel level) {
        String relName = related == null ? "?" :
                tterrag1112.life_in_the_village.Entities.custom.TownspersonMob
                        .findByUUID(level, related)
                        .map(tterrag1112.life_in_the_village.Entities.custom
                                .TownspersonMob::getNpcName)
                        .orElse("a stranger");
        return switch (type) {
            case RECONCILIATION         -> "Reconcile with " + relName;
            case LAST_PILGRIMAGE        -> "Make a final pilgrimage";
            case INHERITANCE_ARRANGEMENT-> "Arrange inheritance for " + relName;
            case APPRENTICE_COMPLETION  -> "Train one last apprentice";
            case UNKEPT_PROMISE         -> "Fulfill an unkept promise to " + relName;
        };
    }

    /**
     * Helper for debug commands — sets an explicit unfinished
     * business on an NPC without firing the bus event.
     */
    public static void setUnfinishedDirect(TownspersonMob npc,
                                           UnfinishedBusinessType type,
                                           UUID relatedId,
                                           ServerLevel level) {
        if (npc == null || type == null) return;
        npc.getRetirementState().setUnfinished(new UnfinishedBusiness(
                type,
                describe(type, npc, relatedId, level),
                relatedId == null ? Optional.empty() : Optional.of(relatedId),
                level.getGameTime(), false));
    }
}
