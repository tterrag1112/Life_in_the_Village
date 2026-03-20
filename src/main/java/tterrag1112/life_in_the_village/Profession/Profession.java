package tterrag1112.life_in_the_village.Profession;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

public enum Profession {
    NONE, CITIZEN, MERCHANT, FARMER, FARMHAND, BLACKSMITH, BUILDER, GUARD, STOCKPILE_KEEPER, INNKEEPER, MINER,
    VILLAGE_LEADER, KINGDOM_RULER, CARPENTER, GUILDMASTER, GUILDWORKER, ADVENTURER, COMPANY_WORKER;

    public String getDisplayName() {
        return name().charAt(0) + name().substring(1).toLowerCase();
    }

    public static Profession professionFor(BuildingType type) {
        return switch (type) {
            case BLACKSMITH      -> Profession.BLACKSMITH;
            case FARMHOUSE       -> Profession.FARMER;
            case MINE            -> Profession.MINER;
            case MARKET          -> Profession.MERCHANT;
            case INN             -> Profession.INNKEEPER;
            case STOCKPILE       -> Profession.STOCKPILE_KEEPER;
            case GUARD_TOWER     -> Profession.GUARD;
            case CARPENTRY       -> Profession.CARPENTER;
            case GUILD_HALL      -> Profession.GUILDWORKER;
            case TOWN_HALL       -> Profession.VILLAGE_LEADER;
            case HOUSE           -> Profession.NONE;
            default              -> Profession.NONE;
        };
    }
}
