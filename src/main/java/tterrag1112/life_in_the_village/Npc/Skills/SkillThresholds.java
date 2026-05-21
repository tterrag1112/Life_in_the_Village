package tterrag1112.life_in_the_village.Npc.Skills;

/**
 * Phase 6.3.2.b — single source of truth for the named skill-level
 * thresholds that gate mentorship, mastery, comprehension, milestones,
 * and promotion. Replaces the equivalent constants previously inlined
 * in MentorBehavior / RetirementHandler / ApprenticeshipMatcher /
 * ApprenticeshipContract / ApprenticeshipManager / LetterDelivery /
 * MerchantPromotion (each duplicating the same magic numbers).
 *
 * <p>No behavioral change: the migrated constants keep their existing
 * values. Consolidation is purely structural — future tuning happens
 * here in one place.
 */
public final class SkillThresholds {

    private SkillThresholds() {}

    // ── Mastery tiers ────────────────────────────────────────────────────
    /** Lowest level at which an NPC can be a mentor / elderly teacher. */
    public static final int MENTOR_SKILL_THRESHOLD     = 60;
    /** Master-tier threshold — apprenticeship eligibility, journeyman→master,
     *  ApprenticeUnderMe verb. */
    public static final int MASTER_SKILL_THRESHOLD     = 70;
    /** Minimum LITERACY for an NPC to read written content (letters /
     *  books) — gates confessions, letter comprehension. */
    public static final int READ_LITERACY_THRESHOLD    = 30;
    /** COMMERCE skill required for company-merchant promotion. */
    public static final int MERCHANT_PROMOTION         = 70;

    // ── Apprenticeship milestone ladder ──────────────────────────────────
    public static final int APPRENTICE_MILESTONE_FOUNDATION    = 20;
    public static final int APPRENTICE_MILESTONE_INTERMEDIATE  = 40;
    public static final int APPRENTICE_MILESTONE_ADVANCED      = 55;
    public static final int APPRENTICE_MILESTONE_MASTERPIECE   = 70;

    /** Same ladder, in array form, for the milestone-tick loops that index
     *  by current progress. Index = milestone tier (0..3). */
    public static final int[] APPRENTICE_MILESTONES = {
            APPRENTICE_MILESTONE_FOUNDATION,
            APPRENTICE_MILESTONE_INTERMEDIATE,
            APPRENTICE_MILESTONE_ADVANCED,
            APPRENTICE_MILESTONE_MASTERPIECE
    };
}
