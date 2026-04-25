package tterrag1112.life_in_the_village.Npc.LifeGoal;

import com.mojang.serialization.Codec;

/**
 * Catalogue of life-goal archetypes per spec
 * {@code docs/npc_redesign/07-life-goals.md} (section "LifeGoalType").
 * 26 v1 entries grouped roughly by life domain.
 *
 * <p>Per-entry metadata (eligibility, trait alignment, completion
 * conditions) lives on the matching {@link LifeGoalDefinition} in
 * {@link LifeGoalRegistry}.</p>
 */
public enum LifeGoalType {
    // ── Wealth & property ────────────────────────────────────────────────
    SAVE_AMOUNT,
    BUY_HOUSE,
    BUY_BUSINESS,
    PAY_OFF_DEBT,

    // ── Career ───────────────────────────────────────────────────────────
    REACH_SKILL_LEVEL,
    REACH_PROFESSION_TIER,
    WIN_OFFICE,
    FOUND_BUSINESS,
    MASTERPIECE,

    // ── Family ───────────────────────────────────────────────────────────
    MARRY_TARGET,
    MARRY_ANY,
    HAVE_CHILD,
    SEE_CHILD_APPRENTICED,
    SEE_CHILD_MARRIED,
    ARRANGE_CHILD_MATCH,

    // ── Social ───────────────────────────────────────────────────────────
    BEFRIEND_TARGET,
    BEFRIEND_COUNT,
    AVENGE_WRONG,
    RESTORE_FAMILY_HONOR,

    // ── Spiritual / cultural ─────────────────────────────────────────────
    PILGRIMAGE,
    AUTHOR_BOOK,
    TEACH_APPRENTICE,

    // ── Location ─────────────────────────────────────────────────────────
    MOVE_TO_VILLAGE,
    VISIT_VILLAGES,

    // ── Survival / dark ──────────────────────────────────────────────────
    OUTLIVE_RIVAL,
    DIE_WITH_HONOR;

    public static final Codec<LifeGoalType> CODEC =
            Codec.STRING.xmap(LifeGoalType::valueOf, LifeGoalType::name);
}
