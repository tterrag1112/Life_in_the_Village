package tterrag1112.life_in_the_village.Npc.LifeGoal;

import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Static metadata for a {@link LifeGoalType}. Drives goal selection
 * (trait alignment + skill bonus + life-stage gating) and supplies
 * fallback narrative templates. Per-NPC progress data lives on the
 * concrete {@link LifeGoal} record.
 *
 * <p>Spec: {@code docs/npc_redesign/07-life-goals.md} sections
 * "Goal selection" and "Trait-goal alignment".</p>
 *
 * @param type           the enum entry this definition describes
 * @param displayLabel   short label shown in the profile snapshot
 * @param traitWeights   per-axis preference; positive entries reward
 *                       NPCs whose trait points the same way, negative
 *                       entries reward NPCs with the opposite pole
 * @param skillBonus     small additive bonus when this skill is high
 * @param eligibleStages life stages that may select this goal at all
 * @param defaultImportance baseline importance (1..10) before
 *                          situational modifiers
 * @param defaultTargetCount default {@link LifeGoal#targetCount} if the
 *                          selector doesn't override (used for
 *                          binary-completion goals where 1 means "done")
 * @param narrativeTemplate one-line default narrative; Phase 5 content
 *                          pass replaces with trait-flavoured variants
 */
public record LifeGoalDefinition(
        LifeGoalType type,
        String displayLabel,
        Map<TraitAxis, Float> traitWeights,
        Skill skillBonus,
        Set<Stage> eligibleStages,
        int defaultImportance,
        int defaultTargetCount,
        String narrativeTemplate
) {
    /**
     * Coarse life-stage gate. Mirrors the existing
     * {@code tterrag1112.life_in_the_village.Entities.LifeStage}
     * categories without import (avoid pulling the full enum here for
     * package-locality reasons; the registry maps stages by name).
     */
    public enum Stage {
        TEEN, ADULT, ELDERLY;

        public static Stage fromLifeStageName(String name) {
            if (name == null) return ADULT;
            return switch (name.toUpperCase(java.util.Locale.ROOT)) {
                case "CHILD" -> ADULT; // children don't pick goals; never queried.
                case "TEEN" -> TEEN;
                case "ELDERLY" -> ELDERLY;
                default -> ADULT;
            };
        }
    }

    public LifeGoalDefinition {
        traitWeights = Map.copyOf(traitWeights);
        eligibleStages = Collections.unmodifiableSet(EnumSet.copyOf(eligibleStages));
    }
}
