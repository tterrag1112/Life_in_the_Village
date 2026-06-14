package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;

/**
 * E-S1 — reusable multiplier math lifted from
 * {@link AbstractProductionBehavior} so that future context-based
 * production behaviors (HOME, MONASTERY, and upcoming skill-first
 * contexts) can scale tick durations and bonus-output rolls identically
 * to profession workshops, without inheriting from
 * {@code AbstractProductionBehavior}.
 *
 * <p>All three methods are pure functions of the NPC's skill/trait
 * state — no side effects, no mutable state, safe to call from any
 * thread that holds the server-tick lock.</p>
 *
 * <p>{@link AbstractProductionBehavior} keeps its existing protected
 * instance/static wrappers and delegates here; subclasses that call
 * {@code productionSpeedMultiplier()} or {@code craftingSpeedMultiplier(entity)}
 * continue to compile unchanged.</p>
 */
public final class WorkshopMultipliers {

    private WorkshopMultipliers() {}

    // ── Trait-driven tick scaling ──────────────────────────────────────────────

    /**
     * INDUSTRY-trait speed multiplier.  Apply as a factor on
     * {@code recipe.ticks()}: {@code ticks * industrySpeedMultiplier(npc)}.
     * Higher = slower (more ticks).
     *
     * <p>Industrious NPCs (INDUSTRY ≈ +1) scale to ≈0.85 (15 % faster);
     * lazy NPCs (INDUSTRY ≈ −1) scale to ≈1.15 (15 % slower). Replaces
     * the deleted {@code AppearanceComponent.getWorkSpeedModifier} path
     * (DILIGENT/LAZY) that had no callers.</p>
     */
    public static float industrySpeedMultiplier(TownspersonMob npc) {
        if (npc == null) return 1.0f;
        float industry = npc.getTraitVector().get(TraitAxis.INDUSTRY);
        return 1.0f - (industry * 0.15f);
    }

    // ── Crafting-skill tick scaling ────────────────────────────────────────────

    /**
     * CRAFTING-skill speed multiplier.  Divide {@code recipe.ticks()} by
     * this — higher = faster.  Mapped from the SkillComponent 4-tier scheme
     * (Amateur/Journeyman/Expert/Master at 0/30/60/85).
     */
    public static float craftingSpeedMultiplier(TownspersonMob npc) {
        int level = npc.getSkills().getLevel(Skill.CRAFTING);
        if (level >= 85) return 1.55f;
        if (level >= 60) return 1.35f;
        if (level >= 30) return 1.15f;
        return 1.0f;
    }

    // ── Crafting-skill bonus-output roll ──────────────────────────────────────

    /**
     * Probability (0–1) of producing one bonus output item per cycle.
     * Roll via {@code entity.getRandom().nextFloat() < craftingQualityChance(npc)}.
     */
    public static float craftingQualityChance(TownspersonMob npc) {
        int level = npc.getSkills().getLevel(Skill.CRAFTING);
        if (level >= 85) return 0.25f;
        if (level >= 60) return 0.10f;
        return 0.0f;
    }
}
