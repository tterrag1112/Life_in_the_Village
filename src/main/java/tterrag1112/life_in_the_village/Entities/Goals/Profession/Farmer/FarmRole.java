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
    FERTILIZER;

    // ── Self-register ─────────────────────────────────────────────────────────
    static {
        ProfessionRoleManager.register("FarmRole", FarmRole::valueOf);
    }

    @Override public boolean isGeneralist()   { return this == GENERALIST; }
    @Override public boolean isMarketSeller() { return this == MARKET_SELLER; }
    @Override public boolean splitsTime()     { return this == GENERALIST; }
}
