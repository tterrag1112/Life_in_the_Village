package tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer;

import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;

public enum FarmRole implements tterrag1112.life_in_the_village.Profession.Roles.ProfessionRole {
    GENERALIST,
    CROP_SPECIALIST,
    ANIMAL_SPECIALIST,
    HARVESTER,
    PLANTER,
    MARKET_SELLER,
    ANIMAL_TENDER,
    FERTILIZER,
    /** Learner hired via an APPRENTICE job posting (FARMHAND profession was
     *  folded into FARMER+APPRENTICE tier in 6.3.3.f). Works at 60% speed. */
    APPRENTICE;

    // ── Self-register ─────────────────────────────────────────────────────────
    static {
        ProfessionRoleManager.register("FarmRole", FarmRole::valueOf);
    }

    @Override public boolean isGeneralist()   { return this == GENERALIST; }
    @Override public boolean isMarketSeller() { return this == MARKET_SELLER; }
    @Override public boolean splitsTime()     { return this == GENERALIST; }
    @Override public boolean isApprentice()   { return this == APPRENTICE; }

    /** Speed multiplier for production and task work relative to a GENERALIST. */
    public float productionSpeedMultiplier() {
        return switch (this) {
            case APPRENTICE    -> 0.60f;
            case MARKET_SELLER -> 0.0f;
            default            -> 1.0f;
        };
    }
}
