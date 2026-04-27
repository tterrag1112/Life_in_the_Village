// src/main/java/tterrag1112/life_in_the_village/Village/Planning/Zoning/BuildingProfileRegistry.java
package tterrag1112.life_in_the_village.Village.Planning.Zoning;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.*;

import static tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag.*;

/**
 * Single source of truth mapping each {@link BuildingType} to its
 * {@link BuildingProfile}. Replaces the placement-intent half of
 * {@code ZoneRegistry} (ZoneRegistry stays alive until slice 10).
 *
 * <p>Profile design notes:
 * <ul>
 *   <li>Landmark civic buildings (market, inn, temple, guild hall,
 *       noble manor, bell tower) carry a 24-block same-type avoidance
 *       rule — two markets in a PLAZA should sit on opposite sides.</li>
 *   <li>Guard/watch towers carry a 32-block same-type avoidance so
 *       they spread around the perimeter.</li>
 *   <li>Water-dependent buildings (docks, miller, fishery, pier) use
 *       RIVER_BANK / SHORE as {@code required} on their top tier.
 *       If the layout doesn't emit those tags the building falls
 *       through to a production fallback or fails placement.</li>
 *   <li>Houses are the only truly generic FILLER. Wells, chapels,
 *       and shrines are FILLER but prefer civic-adjacent placement.</li>
 * </ul>
 */
public final class BuildingProfileRegistry {

    private static final Map<BuildingType, BuildingProfile> REG =
            new EnumMap<>(BuildingType.class);

    // Tier weights — higher earlier tiers so the matcher prefers them
    private static final int W_PRIMARY   = 100;
    private static final int W_SECONDARY = 70;
    private static final int W_TERTIARY  = 45;
    private static final int W_FALLBACK  = 25;
    private static final int W_BACKFILL  = 10;

    static {
        // ── Civic core ───────────────────────────────────────────────────
        put(BuildingType.TOWN_HALL, AnchorPolicy.CORE, null,
                tier(W_PRIMARY, PRIME_CIVIC),
                tier(W_SECONDARY, SECONDARY_CIVIC));

        put(BuildingType.CHANCELLERY, AnchorPolicy.CORE, null,
                tier(W_PRIMARY, PRIME_CIVIC),
                tier(W_SECONDARY, SECONDARY_CIVIC));

        // Landmark civic — spread out
        landmarkCivic(BuildingType.GUILD_HALL);
        landmarkCivic(BuildingType.GUILD_HALL_CRAFTSMEN);
        landmarkCivic(BuildingType.GUILD_HALL_MERCHANTS);
        landmarkCivic(BuildingType.GUILD_HALL_AGRICULTURAL);
        landmarkCivic(BuildingType.GUILD_HALL_RELIGIOUS);
        landmarkCivic(BuildingType.GUILD_HALL_SCHOLARLY);
        landmarkCivic(BuildingType.INN);
        landmarkCivic(BuildingType.MARKET);
        landmarkCivic(BuildingType.TEMPLE);
        landmarkCivic(BuildingType.BELL_TOWER);
        landmarkCivic(BuildingType.NOBLE_MANOR);
        landmarkCivic(BuildingType.LIBRARY);

        // Scribal (Phase 2 task 17) — workshops cluster like production
        // but sit civic-adjacent so paperwork flows toward the town
        // hall; the retreat is a quiet landmark like the library.
        put(BuildingType.SCRIBE_WORKSHOP, AnchorPolicy.CORE, null,
                tier(W_PRIMARY, CIVIC_ADJACENT),
                tier(W_SECONDARY, PRODUCTION_CLUSTER),
                tier(W_TERTIARY, ROAD_ADJACENT));
        landmarkCivic(BuildingType.SCHOLARS_RETREAT);

        // ── Civic filler ─────────────────────────────────────────────────
        put(BuildingType.WELL, AnchorPolicy.FILLER, null,
                tier(W_PRIMARY, CIVIC_ADJACENT),
                tier(W_FALLBACK, ROAD_ADJACENT),
                tier(W_BACKFILL, BACKFILL));

        put(BuildingType.SHRINE, AnchorPolicy.FILLER, null,
                tier(W_PRIMARY, CIVIC_ADJACENT),
                tier(W_FALLBACK, ROAD_ADJACENT),
                tier(W_BACKFILL, BACKFILL));

        put(BuildingType.CHAPEL, AnchorPolicy.FILLER, null,
                tier(W_PRIMARY, CIVIC_ADJACENT),
                tier(W_FALLBACK, ROAD_ADJACENT),
                tier(W_BACKFILL, BACKFILL));

        // Bakery bridges civic and production
        put(BuildingType.BAKERY, AnchorPolicy.CORE, null,
                tier(W_PRIMARY, PRIME_CIVIC),
                tier(W_SECONDARY, SECONDARY_CIVIC),
                tier(W_TERTIARY, PRODUCTION_CLUSTER),
                tier(W_FALLBACK, ROAD_ADJACENT));

        // ── Production ───────────────────────────────────────────────────
        production(BuildingType.BLACKSMITH);
        production(BuildingType.TOOLSMITH);
        production(BuildingType.ARMORER);
        production(BuildingType.CARPENTRY);
        production(BuildingType.STONEMASON);
        production(BuildingType.WEAVER);
        production(BuildingType.CANDLEMAKER);
        production(BuildingType.APOTHECARY);
        production(BuildingType.HEALER_HUT);
        production(BuildingType.ATELIER);
        production(BuildingType.WINERY);
        production(BuildingType.WAREHOUSE);

        // Woodcutter prefers forest edge
        put(BuildingType.WOODCUTTER, AnchorPolicy.CORE, null,
                tier(W_PRIMARY, FOREST_EDGE),
                tier(W_SECONDARY, PRODUCTION_CLUSTER),
                tier(W_TERTIARY, PRODUCTION_SPUR_END),
                tier(W_FALLBACK, ROAD_ADJACENT));

        // Mine prefers high ground
        put(BuildingType.MINE, AnchorPolicy.CORE, null,
                tier(W_PRIMARY, HIGH_GROUND),
                tier(W_SECONDARY, PRODUCTION_CLUSTER),
                tier(W_FALLBACK, ROAD_ADJACENT));

        // Water-required production
        put(BuildingType.MILLER, AnchorPolicy.CORE, null,
                tier(W_PRIMARY, RIVER_BANK),
                tier(W_SECONDARY, SHORE),
                tier(W_TERTIARY, PRODUCTION_CLUSTER));

        put(BuildingType.DOCKS, AnchorPolicy.CORE, null,
                tier(W_PRIMARY, PIER_ADJACENT),
                tier(W_SECONDARY, SHORE),
                tier(W_TERTIARY, RIVER_BANK));

        put(BuildingType.FISHERY, AnchorPolicy.CORE, null,
                tier(W_PRIMARY, SHORE),
                tier(W_SECONDARY, PIER_ADJACENT),
                tier(W_TERTIARY, RIVER_BANK));

        put(BuildingType.PIER, AnchorPolicy.CORE, null,
                tier(W_PRIMARY, SHORE));

        // Vineyard wants terrace edge
        put(BuildingType.VINEYARD, AnchorPolicy.CORE, null,
                tier(W_PRIMARY, TERRACE_EDGE),
                tier(W_SECONDARY, FIELD_EDGE),
                tier(W_FALLBACK, PASTURE));

        // ── Residential ──────────────────────────────────────────────────
        put(BuildingType.HOUSE, AnchorPolicy.FILLER, null,
                tier(W_PRIMARY, RESIDENTIAL_CORE),
                tier(W_SECONDARY, RESIDENTIAL_INFILL),
                tier(W_TERTIARY, RESIDENTIAL_OUTER),
                tier(W_BACKFILL, BACKFILL));

        put(BuildingType.STABLE, AnchorPolicy.CORE, null,
                tier(W_PRIMARY, RESIDENTIAL_OUTER),
                tier(W_SECONDARY, ROAD_ADJACENT),
                tier(W_FALLBACK, BACKFILL));

        // ── Agricultural ─────────────────────────────────────────────────
        put(BuildingType.FARMHOUSE, AnchorPolicy.CORE, null,
                tier(W_PRIMARY, FIELD_EDGE),
                tier(W_SECONDARY, PASTURE),
                tier(W_TERTIARY, RESIDENTIAL_OUTER));

        put(BuildingType.STOCKPILE, AnchorPolicy.CORE, null,
                tier(W_PRIMARY, FIELD_EDGE),
                tier(W_SECONDARY, PRODUCTION_CLUSTER),
                tier(W_FALLBACK, ROAD_ADJACENT));

        // ── Defensive ────────────────────────────────────────────────────
        AvoidanceRule towerSpread = AvoidanceRule.sameTypeMin(32);
        put(BuildingType.GUARD_TOWER, AnchorPolicy.CORE, towerSpread,
                tier(W_PRIMARY, WALL_ADJACENT),
                tier(W_SECONDARY, GATE_ADJACENT),
                tier(W_TERTIARY, HIGH_GROUND),
                tier(W_FALLBACK, RESIDENTIAL_OUTER));

        put(BuildingType.WATCHTOWER, AnchorPolicy.CORE, towerSpread,
                tier(W_PRIMARY, HIGH_GROUND),
                tier(W_SECONDARY, WALL_ADJACENT),
                tier(W_FALLBACK, RESIDENTIAL_OUTER));

        put(BuildingType.BARRACKS, AnchorPolicy.CORE, null,
                tier(W_PRIMARY, GATE_ADJACENT),
                tier(W_SECONDARY, WALL_ADJACENT),
                tier(W_TERTIARY, CIVIC_ADJACENT));

        put(BuildingType.PRISON, AnchorPolicy.CORE, null,
                tier(W_PRIMARY, WALL_ADJACENT),
                tier(W_SECONDARY, RESIDENTIAL_OUTER),
                tier(W_FALLBACK, ROAD_ADJACENT));

        put(BuildingType.CASTLE, AnchorPolicy.CORE, null,
                tier(W_PRIMARY, HILLTOP_PEAK),
                tier(W_SECONDARY, HIGH_GROUND),
                tier(W_TERTIARY, PRIME_CIVIC));

        put(BuildingType.TREASURY, AnchorPolicy.CORE, null,
                tier(W_PRIMARY, PRIME_CIVIC),
                tier(W_SECONDARY, SECONDARY_CIVIC),
                tier(W_TERTIARY, WALL_ADJACENT));

        // TOWN_SQUARE is procedurally generated, not matcher-placed.
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static SlotPreference tier(int weight, SlotTag... required) {
        return SlotPreference.require(weight, required);
    }

    private static void put(BuildingType type, AnchorPolicy anchor,
                            AvoidanceRule avoid, SlotPreference... tiers) {
        REG.put(type, new BuildingProfile(type, Arrays.asList(tiers),
                anchor, avoid));
    }

    private static void landmarkCivic(BuildingType type) {
        put(type, AnchorPolicy.CORE, AvoidanceRule.sameTypeMin(24),
                tier(W_PRIMARY, PRIME_CIVIC),
                tier(W_SECONDARY, SECONDARY_CIVIC),
                tier(W_TERTIARY, CIVIC_ADJACENT));
    }

    private static void production(BuildingType type) {
        put(type, AnchorPolicy.CORE, null,
                tier(W_PRIMARY, PRODUCTION_CLUSTER),
                tier(W_SECONDARY, PRODUCTION_SPUR_END),
                tier(W_TERTIARY, PRODUCTION_INFILL),
                tier(W_FALLBACK, ROAD_ADJACENT));
    }

    // ── Public API ───────────────────────────────────────────────────────

    public static BuildingProfile get(BuildingType type) {
        BuildingProfile p = REG.get(type);
        if (p != null) return p;
        // Safe default: generic filler that'll take any road-adjacent slot
        return new BuildingProfile(type,
                List.of(SlotPreference.require(W_FALLBACK, ROAD_ADJACENT),
                        SlotPreference.require(W_BACKFILL, BACKFILL)),
                AnchorPolicy.FILLER, null);
    }

    public static boolean has(BuildingType type) {
        return REG.containsKey(type);
    }

    private BuildingProfileRegistry() {}
}