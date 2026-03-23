// src/main/java/tterrag1112/life_in_the_village/Village/Planning/ZoneRegistry.java
package tterrag1112.life_in_the_village.Village.Planning;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.StreetTier;

import java.util.EnumMap;
import java.util.Map;

/**
 * Single source of truth mapping each {@link BuildingType} to its
 * {@link BuildingZone}, placement priority within that zone (lower =
 * placed first), and whether a new path connection is required when
 * the building is placed by the expansion system.
 */
public class ZoneRegistry {

    public record ZoneEntry(
            BuildingZone zone,
            int          priority,      // within zone: lower placed first
            boolean      needsPath,     // expansion: connect to street network
            StreetTier   preferredStreet // which tier of street to connect to
    ) {}

    private static final Map<BuildingType, ZoneEntry> REGISTRY
            = new EnumMap<>(BuildingType.class);

    static {
        // ── Civic ──────────────────────────────────────────────────────────────
        reg(BuildingType.TOWN_HALL,    BuildingZone.CIVIC,       0, false, StreetTier.PRIMARY);
        reg(BuildingType.CHANCELLERY,  BuildingZone.CIVIC,       1, false, StreetTier.PRIMARY);  // NEW
        reg(BuildingType.GUILD_HALL,   BuildingZone.CIVIC,       2, true,  StreetTier.PRIMARY);
        reg(BuildingType.INN,          BuildingZone.CIVIC,       3, true,  StreetTier.PRIMARY);
        reg(BuildingType.MARKET,       BuildingZone.CIVIC,       4, true,  StreetTier.PRIMARY);
        reg(BuildingType.BELL_TOWER,   BuildingZone.CIVIC,       5, true,  StreetTier.PRIMARY);
        reg(BuildingType.TEMPLE,       BuildingZone.CIVIC,       6, true,  StreetTier.PRIMARY);
        reg(BuildingType.LIBRARY,      BuildingZone.CIVIC,       7, true,  StreetTier.SECONDARY);
        reg(BuildingType.NOBLE_MANOR,  BuildingZone.CIVIC,       8, true,  StreetTier.PRIMARY);
        reg(BuildingType.WELL,         BuildingZone.CIVIC,       9, false, StreetTier.SECONDARY);

        // ── Production ────────────────────────────────────────────────────────
        reg(BuildingType.BLACKSMITH,   BuildingZone.PRODUCTION,  0, true,  StreetTier.SECONDARY);
        reg(BuildingType.CARPENTRY,    BuildingZone.PRODUCTION,  1, true,  StreetTier.SECONDARY);
        reg(BuildingType.MILLER,       BuildingZone.PRODUCTION,  2, true,  StreetTier.SECONDARY);
        reg(BuildingType.BAKERY,       BuildingZone.PRODUCTION,  3, true,  StreetTier.SECONDARY);
        reg(BuildingType.STONEMASON,   BuildingZone.PRODUCTION,  4, true,  StreetTier.SECONDARY);
        reg(BuildingType.WEAVER,       BuildingZone.PRODUCTION,  5, true,  StreetTier.TERTIARY);
        reg(BuildingType.CANDLEMAKER,  BuildingZone.PRODUCTION,  6, true,  StreetTier.TERTIARY);
        reg(BuildingType.WOODCUTTER,   BuildingZone.PRODUCTION,  7, true,  StreetTier.TERTIARY);
        reg(BuildingType.APOTHECARY,   BuildingZone.PRODUCTION,  8, true,  StreetTier.TERTIARY);
        reg(BuildingType.TOOLSMITH,    BuildingZone.PRODUCTION,  9, true,  StreetTier.SECONDARY);
        reg(BuildingType.ARMORER,      BuildingZone.PRODUCTION, 10, true,  StreetTier.SECONDARY);
        reg(BuildingType.WINERY,       BuildingZone.PRODUCTION, 11, true,  StreetTier.TERTIARY);
        reg(BuildingType.ATELIER,      BuildingZone.PRODUCTION, 12, true,  StreetTier.TERTIARY);
        reg(BuildingType.DOCKS,        BuildingZone.PRODUCTION, 13, true,  StreetTier.PRIMARY);
        reg(BuildingType.MINE,         BuildingZone.PRODUCTION, 14, true,  StreetTier.SECONDARY);

        // ── Residential ───────────────────────────────────────────────────────
        reg(BuildingType.HOUSE,        BuildingZone.RESIDENTIAL,  0, true,  StreetTier.TERTIARY);
        reg(BuildingType.STABLE,       BuildingZone.RESIDENTIAL,  1, true,  StreetTier.SECONDARY);

        // ── Agricultural ──────────────────────────────────────────────────────
        reg(BuildingType.FARMHOUSE,    BuildingZone.AGRICULTURAL, 0, true,  StreetTier.TERTIARY);
        reg(BuildingType.STOCKPILE,    BuildingZone.AGRICULTURAL, 1, true,  StreetTier.SECONDARY);

        // ── Defensive ─────────────────────────────────────────────────────────
        reg(BuildingType.GUARD_TOWER,  BuildingZone.DEFENSIVE,    0, true,  StreetTier.SECONDARY);
        reg(BuildingType.WATCHTOWER,   BuildingZone.DEFENSIVE,    1, true,  StreetTier.SECONDARY);
        reg(BuildingType.BARRACKS,     BuildingZone.DEFENSIVE,    2, true,  StreetTier.PRIMARY);
        reg(BuildingType.PRISON,       BuildingZone.DEFENSIVE,    3, true,  StreetTier.TERTIARY);
        reg(BuildingType.CASTLE,       BuildingZone.DEFENSIVE,    4, false, StreetTier.PRIMARY);
        reg(BuildingType.TREASURY,     BuildingZone.DEFENSIVE,    5, true,  StreetTier.PRIMARY);  // NEW
    }

    private static void reg(BuildingType type, BuildingZone zone,
                            int priority, boolean needsPath,
                            StreetTier street) {
        REGISTRY.put(type, new ZoneEntry(zone, priority, needsPath, street));
    }

    public static ZoneEntry get(BuildingType type) {
        return REGISTRY.getOrDefault(type,
                new ZoneEntry(BuildingZone.RESIDENTIAL, 99,
                        true, StreetTier.TERTIARY));
    }

    public static BuildingZone zoneOf(BuildingType type) {
        return get(type).zone();
    }
}