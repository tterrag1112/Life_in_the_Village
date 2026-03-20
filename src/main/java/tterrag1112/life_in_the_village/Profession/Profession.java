package tterrag1112.life_in_the_village.Profession;

public enum Profession {
    NONE, CITIZEN, MERCHANT, FARMER, FARMHAND, BLACKSMITH, BUILDER, GUARD, STOCKPILE_KEEPER, INNKEEPER, MINER,
    VILLAGE_LEADER, KINGDOM_RULER, CARPENTER, GUILDMASTER, GUILDWORKER, ADVENTURER, COMPANY_WORKER;

    public String getDisplayName() {
        return name().charAt(0) + name().substring(1).toLowerCase();
    }
}
