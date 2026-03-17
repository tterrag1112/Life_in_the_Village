package tterrag1112.life_in_the_village.Kingdom;

public enum DiplomaticRelation {
    ALLIANCE,   // full military and economic cooperation
    TRADE,      // economic cooperation only
    NEUTRAL,    // no special relations
    COLD_WAR,   // hostile but no active combat
    WAR;        // active conflict

    public boolean isHostile() {
        return this == COLD_WAR || this == WAR;
    }

    public boolean isCooperative() {
        return this == ALLIANCE || this == TRADE;
    }

    public boolean isAtWar() {
        return this == WAR;
    }
}