package tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop;

import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;

/**
 * Roles for workshop-based production buildings:
 * blacksmith, carpenter, miller, baker, weaver, etc.
 *
 * <h3>Thresholds</h3>
 * <pre>
 *   1 worker  → GENERALIST
 *   2 workers → PRODUCER + APPRENTICE
 *   3 workers → 2× PRODUCER + APPRENTICE
 *   4+ workers → PRODUCER(s) + APPRENTICE + MARKET_SELLER
 * </pre>
 */
public enum WorkshopRole implements tterrag1112.life_in_the_village.Profession.Roles.ProfessionRole {

    /** Does everything: produces, deposits, and sells during social time. */
    GENERALIST,

    /** Production-focused. Deposits to building storage; never mans stall. */
    PRODUCER,

    /**
     * Learner. Produces at 60% of normal speed. No stall involvement.
     * Eligible to graduate to PRODUCER when a new hire takes the APPRENTICE slot.
     */
    APPRENTICE,

    /**
     * Dedicated stall keeper. Never uses the workstation.
     * Stocks the building's market stall and minds it during work hours.
     */
    MARKET_SELLER;

    // ── Self-register with ProfessionRoleManager ──────────────────────────────
    static {
        ProfessionRoleManager.register("WorkshopRole", WorkshopRole::valueOf);
    }

    @Override public boolean isGeneralist()   { return this == GENERALIST; }
    @Override public boolean isMarketSeller() { return this == MARKET_SELLER; }
    @Override public boolean splitsTime()     { return this == GENERALIST; }

    /** Speed multiplier relative to a standard PRODUCER. */
    public float productionSpeedMultiplier() {
        return switch (this) {
            case GENERALIST  -> 1.0f;
            case PRODUCER    -> 1.0f;
            case APPRENTICE  -> 0.60f;
            case MARKET_SELLER -> 0.0f; // never produces
        };
    }
}