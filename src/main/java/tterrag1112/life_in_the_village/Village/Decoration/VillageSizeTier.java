package tterrag1112.life_in_the_village.Village.Decoration;

import java.util.List;

public enum VillageSizeTier {

    HAMLET(
            1, 3,
            "Hamlet",
            false,  // no well
            false,  // no landmark
            false,  // no market
            4,      // lamppost spacing
            0       // flower attempts
    ),
    VILLAGE(
            4, 8,
            "Village",
            true,   // well
            false,  // no landmark
            false,  // no market
            8,      // lamppost spacing
            20      // flower attempts
    ),
    TOWN(
            9, 15,
            "Town",
            true,   // well
            true,   // landmark
            true,   // market
            6,      // closer lampposts
            40      // more flowers
    ),
    CITY(
            16, Integer.MAX_VALUE,
            "City",
            true,   // well
            true,   // landmark
            true,   // market
            4,      // dense lampposts
            60      // many flowers
    );

    public final int minBuildings;
    public final int maxBuildings;
    public final String displayName;
    public final boolean hasWell;
    public final boolean hasLandmark;
    public final boolean hasMarket;
    public final int lampostSpacing;
    public final int flowerAttempts;

    VillageSizeTier(int minBuildings, int maxBuildings,
                    String displayName, boolean hasWell,
                    boolean hasLandmark, boolean hasMarket,
                    int lampostSpacing, int flowerAttempts) {
        this.minBuildings   = minBuildings;
        this.maxBuildings   = maxBuildings;
        this.displayName    = displayName;
        this.hasWell        = hasWell;
        this.hasLandmark    = hasLandmark;
        this.hasMarket      = hasMarket;
        this.lampostSpacing = lampostSpacing;
        this.flowerAttempts = flowerAttempts;
    }

    public static VillageSizeTier fromBuildingCount(int count) {
        for (VillageSizeTier tier : values()) {
            if (count >= tier.minBuildings
                    && count <= tier.maxBuildings) {
                return tier;
            }
        }
        return HAMLET;
    }

}