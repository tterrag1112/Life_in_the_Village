package tterrag1112.life_in_the_village.Npc.LifeGoal;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Events.EventDispatcher;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Npc.Traits.TraitVector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Goal-selection dispatcher. Subscribes to the bus and runs goal
 * selection when an NPC transitions to ADULT (or ELDERLY for late-life
 * goals). Also exposes a static {@link #refill} entry point used by the
 * daily evaluator when an NPC has free goal slots.
 *
 * <p>Spec: {@code docs/npc_redesign/07-life-goals.md} ("Goal selection").
 * Cultural pull is stubbed (Phase 5 wires culture); this implementation
 * uses neutral cultural weight (1.0) per the prompt's instruction.</p>
 */
public final class LifeGoalSelector implements EventDispatcher {

    /** Score floor for trait+skill alignment; below this the goal is excluded. */
    private static final float MIN_ELIGIBILITY_SCORE = 0.05f;
    /** Top-K candidates kept after scoring; weighted-random picks from this set. */
    private static final int CANDIDATE_TOP_K = 6;

    @Override public String name() { return "life_goal_selector"; }

    @Override
    public void onEvent(NpcLifeEvent event) {
        if (!(event instanceof NpcLifeEvent.LifeStageAdvanced lsa)) return;
        // Selection runs only when entering ADULT for the first time.
        // ELDERLY transition can re-evaluate via the daily evaluator.
        String to = lsa.newStage();
        if (!"ADULT".equalsIgnoreCase(to)) return;
        TownspersonMob npc = lsa.subject();
        if (npc == null) return;
        if (npc.getLifeGoals().activeCount() > 0) return; // already seeded
        if (!(npc.level() instanceof ServerLevel sl)) return;

        long now = sl.getGameTime();
        int desired = desiredInitialCount(npc.getTraitVector());
        List<LifeGoal> selected = selectGoalsFor(npc, desired, now);
        for (LifeGoal g : selected) npc.getLifeGoals().add(g);
    }

    /**
     * Goal-count formula at adulthood: 1 baseline, +1 if Ambition ≥ +0.4.
     * Capped at {@link LifeGoalSet#MAX_ACTIVE}. Documented in
     * {@code docs/npc_redesign/07-life-goals.md} Revision Notes — the spec
     * specifies "1–2 at adulthood" without a precise formula.
     */
    public static int desiredInitialCount(TraitVector traits) {
        int n = 1;
        if (traits.get(TraitAxis.AMBITION) >= 0.4f) n++;
        return Math.min(LifeGoalSet.MAX_ACTIVE, n);
    }

    /**
     * Refill an NPC's goal slate up to {@code targetCount} new goals,
     * never exceeding {@link LifeGoalSet#MAX_ACTIVE}. Called from the
     * daily evaluator when the slate has spare slots.
     */
    public static int refill(TownspersonMob npc, int targetCount, long now) {
        if (npc == null) return 0;
        LifeGoalSet set = npc.getLifeGoals();
        int slots = LifeGoalSet.MAX_ACTIVE - set.activeCount();
        int wanted = Math.min(targetCount, slots);
        if (wanted <= 0) return 0;
        List<LifeGoal> picks = selectGoalsFor(npc, wanted, now);
        int added = 0;
        for (LifeGoal g : picks) {
            if (set.add(g)) added++;
        }
        return added;
    }

    // ── Selection core ─────────────────────────────────────────────────────

    /**
     * Score every eligible {@link LifeGoalDefinition} for {@code npc},
     * keep the top-K, then weighted-random pick {@code count} of them.
     * Avoids duplicating types already in the active set.
     */
    private static List<LifeGoal> selectGoalsFor(TownspersonMob npc, int count, long now) {
        TraitVector traits = npc.getTraitVector();
        LifeGoalDefinition.Stage stage =
                LifeGoalDefinition.Stage.fromLifeStageName(npc.getLifeStage().name());

        List<Scored> scored = new ArrayList<>();
        for (LifeGoalDefinition def : LifeGoalRegistry.all()) {
            if (npc.getLifeGoals().has(def.type())) continue;
            if (!def.eligibleStages().contains(stage)) continue;
            if (!circumstanceEligible(npc, def)) continue;
            float score = scoreGoal(def, traits, npc);
            if (score < MIN_ELIGIBILITY_SCORE) continue;
            scored.add(new Scored(def, score));
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());
        List<Scored> topK = scored.subList(0, Math.min(CANDIDATE_TOP_K, scored.size()));
        return weightedRandomPick(topK, count, npc, now);
    }

    /**
     * Trait dot product + skill bonus + neutral cultural weight (Phase 5
     * stub). Negative trait alignment subtracts; missing axes contribute
     * 0. Skill bonus is small ({@code level / 100}) so high-skill NPCs
     * lean toward goals that build on their craft without dominating the
     * selection.
     */
    public static float scoreGoal(LifeGoalDefinition def, TraitVector traits, TownspersonMob npc) {
        float traitAlignment = 0f;
        for (Map.Entry<TraitAxis, Float> e : def.traitWeights().entrySet()) {
            traitAlignment += e.getValue() * traits.get(e.getKey());
        }
        float skillBonus = 0f;
        if (def.skillBonus() != null) {
            int level = npc.getSkills().getLevel(def.skillBonus());
            skillBonus = level / 100f;
        }
        float culturalWeight = 1f; // Phase 5 stub.
        // Combine: alignment is the dominant signal; skill bonus adds up to
        // ~+1.0 at level 100; cultural weight is multiplicative on the
        // base.
        return (traitAlignment + skillBonus + 0.10f) * culturalWeight;
    }

    /**
     * Coarse circumstance gate. Reject goals that need state the NPC
     * obviously lacks. Phase 1 only knows family/profession/age cleanly;
     * later phases (relationships, offices in detail) refine.
     */
    private static boolean circumstanceEligible(TownspersonMob npc, LifeGoalDefinition def) {
        return switch (def.type()) {
            // Children-related — need at least one child for completion-relevant goals.
            case SEE_CHILD_APPRENTICED, SEE_CHILD_MARRIED, ARRANGE_CHILD_MATCH ->
                    !npc.getFamily().getChildrenIds().isEmpty();
            // Marriage goals — need NOT to be married already.
            case MARRY_TARGET, MARRY_ANY -> npc.getFamily().getSpouseId().isEmpty();
            // Skill-bonus-required goals stay open even at low skill —
            // the NPC may aspire to grow into them.
            default -> true;
        };
    }

    private record Scored(LifeGoalDefinition def, float score) {}

    /**
     * Weighted random selection without replacement. Weights are the raw
     * scores (already shifted positive by the +0.10 floor in
     * {@link #scoreGoal}).
     */
    private static List<LifeGoal> weightedRandomPick(List<Scored> candidates, int count,
                                                     TownspersonMob npc, long now) {
        if (candidates.isEmpty()) {
            // Spec edge case: fall back to SAVE_AMOUNT if nothing scored.
            return List.of(buildGoal(npc, LifeGoalRegistry.get(LifeGoalType.SAVE_AMOUNT), now));
        }
        RandomSource rng = npc.level() instanceof ServerLevel sl
                ? sl.getRandom() : RandomSource.create();
        List<Scored> pool = new ArrayList<>(candidates);
        List<LifeGoal> out = new ArrayList<>();
        for (int picked = 0; picked < count && !pool.isEmpty(); picked++) {
            float total = 0f;
            for (Scored s : pool) total += Math.max(0.001f, s.score);
            float roll = rng.nextFloat() * total;
            float acc = 0f;
            int chosen = 0;
            for (int i = 0; i < pool.size(); i++) {
                acc += Math.max(0.001f, pool.get(i).score);
                if (roll <= acc) { chosen = i; break; }
            }
            out.add(buildGoal(npc, pool.get(chosen).def, now));
            pool.remove(chosen);
        }
        return out;
    }

    /**
     * Fills in narrative + targetCount + importance from the definition's
     * defaults plus simple per-NPC modifiers. Phase 5 content pass will
     * substitute richer narrative templates.
     */
    private static LifeGoal buildGoal(TownspersonMob npc, LifeGoalDefinition def, long now) {
        TraitVector traits = npc.getTraitVector();
        int importance = def.defaultImportance();
        // High Ambition + strong alignment bumps importance up to +2.
        if (traits.get(TraitAxis.AMBITION) >= 0.4f) importance++;
        if (def.traitWeights().getOrDefault(TraitAxis.AMBITION, 0f) >= 0.4f
                && traits.get(TraitAxis.AMBITION) >= 0.4f) {
            importance++;
        }
        importance = Math.max(1, Math.min(10, importance));

        // Scale targetCount for some count-based goals using importance.
        int targetCount = def.defaultTargetCount();
        if (def.type() == LifeGoalType.SAVE_AMOUNT) {
            targetCount = 200 + 100 * importance; // 300..1200 bronze.
        }

        return LifeGoal.newActive(
                def.type(),
                now,
                inferTargetParam(npc, def),
                targetCount,
                importance,
                def.narrativeTemplate());
    }

    private static String inferTargetParam(TownspersonMob npc, LifeGoalDefinition def) {
        return switch (def.type()) {
            // Skill-target goals: pick the NPC's primary skill.
            case REACH_SKILL_LEVEL -> npc.getSkills().primary().name();
            default -> "";
        };
    }
}
