package tterrag1112.life_in_the_village.Guild.Adventurers;

import tterrag1112.life_in_the_village.Guild.GuildRank;

public enum AdventurerReputationTier {

    HOSTILE   (-999, -75,  null,          0),
    WARY      (-74,  -40,  null,          0),
    UNPOPULAR (-39,  -10,  null,          0),
    NEUTRAL   (-9,    9,   null,          0),
    FRIENDLY  ( 10,   24,  GuildRank.BRONZE,   1),
    WELL_KNOWN( 25,   49,  GuildRank.SILVER,   1),
    RESPECTED ( 50,   74,  GuildRank.GOLD,     2),
    HONORED   ( 75,   89,  GuildRank.PLATINUM, 2),
    LEGEND    ( 90,  999,  GuildRank.DIAMOND,  2);

    // Village rep must be within this range
    private final int minVillageRep;
    private final int maxVillageRep;
    // Minimum guild rank required to reach this tier (null = no requirement)
    private final GuildRank minGuildRank;
    // How many tiers guild rank can raise above the village rep floor
    private final int maxGuildBoost;

    AdventurerReputationTier(int minVillageRep, int maxVillageRep,
                             GuildRank minGuildRank, int maxGuildBoost) {
        this.minVillageRep = minVillageRep;
        this.maxVillageRep = maxVillageRep;
        this.minGuildRank  = minGuildRank;
        this.maxGuildBoost = maxGuildBoost;
    }

    // -------------------------------------------------------------------------
    // Tier resolution
    // -------------------------------------------------------------------------

    /**
     * Resolves the final tier for a player based on their village reputation
     * and guild rank. Village rep sets the floor, guild rank can raise it
     * by up to 2 tiers.
     */
    public static AdventurerReputationTier resolve(int villageRep,
                                                   GuildRank guildRank) {
        // Step 1 — find the floor tier from village rep alone
        AdventurerReputationTier floor = fromVillageRep(villageRep);

        // Step 2 — try to raise by guild rank, up to 2 tiers max
        AdventurerReputationTier[] tiers = values();
        int floorIndex = floor.ordinal();

        AdventurerReputationTier best = floor;
        for (int i = floorIndex; i < Math.min(floorIndex + 3,
                tiers.length); i++) {
            AdventurerReputationTier candidate = tiers[i];
            int boost = i - floorIndex;
            if (boost > 2) break;

            // Check guild rank requirement
            if (candidate.minGuildRank == null
                    || guildRankSatisfies(guildRank,
                    candidate.minGuildRank)) {
                best = candidate;
            } else {
                break; // can't skip a rank requirement
            }
        }

        return best;
    }

    private static AdventurerReputationTier fromVillageRep(int rep) {
        for (AdventurerReputationTier tier : values()) {
            if (rep >= tier.minVillageRep
                    && rep <= tier.maxVillageRep) {
                return tier;
            }
        }
        return NEUTRAL;
    }

    private static boolean guildRankSatisfies(GuildRank playerRank,
                                              GuildRank required) {
        return playerRank.ordinal() >= required.ordinal();
    }

    // -------------------------------------------------------------------------
    // Behavior flags
    // -------------------------------------------------------------------------

    public boolean isHostile() {
        return this == HOSTILE;
    }

    public boolean willWarnVillages() {
        return this == HOSTILE;
    }

    public boolean willAssistInCombat() {
        return this == FRIENDLY || this == WELL_KNOWN
                || this == RESPECTED || this == HONORED
                || this == LEGEND;
    }

    public boolean willAssistProactively() {
        return this == WELL_KNOWN || this == RESPECTED
                || this == HONORED || this == LEGEND;
    }

    public boolean willShareBasicTips() {
        return this == FRIENDLY || this == WELL_KNOWN
                || this == RESPECTED || this == HONORED
                || this == LEGEND;
    }

    public boolean willShareFullTips() {
        return this == WELL_KNOWN || this == RESPECTED
                || this == HONORED || this == LEGEND;
    }

    public boolean willShareExclusiveTips() {
        return this == HONORED || this == LEGEND;
    }

    public boolean canJoinGroup() {
        return this == HONORED || this == LEGEND;
    }

    public boolean playerCanLead() {
        return this == LEGEND;
    }

    public boolean willBuffPlayer() {
        return this == RESPECTED || this == HONORED
                || this == LEGEND;
    }

    public boolean willIgnorePlayer() {
        return this == WARY || this == UNPOPULAR;
    }

    public String getDisplayName() {
        return name().charAt(0)
                + name().substring(1).toLowerCase()
                .replace("_", " ");
    }
}