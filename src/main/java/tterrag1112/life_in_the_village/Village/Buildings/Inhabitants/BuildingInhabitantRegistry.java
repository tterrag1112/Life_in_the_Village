package tterrag1112.life_in_the_village.Village.Buildings.Inhabitants;

import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingAdjacencySpec;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Maps each {@link BuildingType} to its {@link BuildingInhabitantSpec}.
 *
 * <h3>Population</h3>
 * Specs are registered in {@link #registerDefaults()}, called once at
 * mod setup. Addons can register custom specs (or replace built-in ones)
 * via {@link #register} at any time before village spawning happens.
 *
 * <h3>Default specs</h3>
 * The defaults reproduce the old village-type-level {@code starter_npcs}
 * spawning behaviour: a HOUSE produces a household, a BLACKSMITH produces
 * a blacksmith worker who needs housing, a TOWN_HALL produces a leader
 * and a builder, and so on. As the mod's content expands, these can
 * become richer (multiple worker tiers, apprentices, age progressions)
 * without touching the spawn pipeline.
 */
public final class BuildingInhabitantRegistry {

    private static final Map<BuildingType, BuildingInhabitantSpec> SPECS =
            new EnumMap<>(BuildingType.class);

    private static boolean registered = false;

    private BuildingInhabitantRegistry() {}

    // =========================================================================
    // Public API
    // =========================================================================

    public static BuildingInhabitantSpec get(BuildingType type) {
        return SPECS.getOrDefault(type, BuildingInhabitantSpec.EMPTY);
    }

    public static void register(BuildingType type, BuildingInhabitantSpec spec) {
        SPECS.put(type, spec);
    }
    // ── Adjacency specs ──────────────────────────────────────────────────────

    private static final java.util.Map<BuildingType, BuildingAdjacencySpec> ADJACENCY =
            new java.util.EnumMap<>(BuildingType.class);

    public static BuildingAdjacencySpec getAdjacency(BuildingType type) {
        return ADJACENCY.getOrDefault(type, BuildingAdjacencySpec.EMPTY);
    }

    public static void registerAdjacency(BuildingType type,
                                         BuildingAdjacencySpec spec) {
        ADJACENCY.put(type, spec);
    }

    // =========================================================================
    // Defaults
    // =========================================================================

    public static void registerDefaults() {
        if (registered) return;
        registered = true;

        // ── Residential ─────────────────────────────────────────────────────
        register(BuildingType.HOUSE, BuildingInhabitantSpec.builder()
                .household(0.7f, 3, 0.4f)
                .build());

        register(BuildingType.NOBLE_MANOR, BuildingInhabitantSpec.builder()
                .household(0.85f, 4, 0.5f)
                .build());

        // ── Civic ───────────────────────────────────────────────────────────
        register(BuildingType.TOWN_HALL, BuildingInhabitantSpec.builder()
                .resident(Profession.VILLAGE_LEADER)
                .worker(Profession.BUILDER)
                .build());

        register(BuildingType.CHANCELLERY, BuildingInhabitantSpec.builder()
                .resident(Profession.CHANCELLOR)
                .worker(Profession.HERALD)
                .build());

        register(BuildingType.TREASURY, BuildingInhabitantSpec.builder()
                .worker(Profession.GUARD)
                .build());

        register(BuildingType.GUILD_HALL, BuildingInhabitantSpec.builder()
                .resident(Profession.GUILDMASTER)
                .worker(Profession.GUILDWORKER)
                .build());

        register(BuildingType.LIBRARY, BuildingInhabitantSpec.builder()
                .worker(Profession.SCHOLAR)
                .build());

        register(BuildingType.TEMPLE, BuildingInhabitantSpec.builder()
                .worker(Profession.PRIEST)
                .build());

        // ── Production ──────────────────────────────────────────────────────
        register(BuildingType.FARMHOUSE, BuildingInhabitantSpec.builder()
                .workerHousehold(Profession.FARMER, 0.6f, 3, 0.4f)
                .build());

        register(BuildingType.BLACKSMITH, BuildingInhabitantSpec.builder()
                .worker(Profession.BLACKSMITH)
                .build());

        register(BuildingType.CARPENTRY, BuildingInhabitantSpec.builder()
                .worker(Profession.CARPENTER)
                .build());

        register(BuildingType.MILLER, BuildingInhabitantSpec.builder()
                .worker(Profession.MILLER)
                .build());

        register(BuildingType.BAKERY, BuildingInhabitantSpec.builder()
                .worker(Profession.BAKER)
                .build());

        register(BuildingType.STONEMASON, BuildingInhabitantSpec.builder()
                .worker(Profession.STONEMASON)
                .build());

        register(BuildingType.WEAVER, BuildingInhabitantSpec.builder()
                .worker(Profession.WEAVER)
                .build());

        register(BuildingType.CANDLEMAKER, BuildingInhabitantSpec.builder()
                .worker(Profession.CANDLEMAKER)
                .build());

        register(BuildingType.WOODCUTTER, BuildingInhabitantSpec.builder()
                .worker(Profession.CARPENTER)  // closest mapped profession
                .build());

        register(BuildingType.MINE, BuildingInhabitantSpec.builder()
                .worker(Profession.MINER)
                .build());

        // ── Storage and commerce ────────────────────────────────────────────
        register(BuildingType.STOCKPILE, BuildingInhabitantSpec.builder()
                .worker(Profession.STOCKPILE_KEEPER)
                .build());

        register(BuildingType.MARKET, BuildingInhabitantSpec.builder()
                .worker(Profession.MERCHANT)
                .worker(Profession.MERCHANT)
                .build());

        register(BuildingType.INN, BuildingInhabitantSpec.builder()
                .resident(Profession.INNKEEPER)
                .build());

        register(BuildingType.STABLE, BuildingInhabitantSpec.builder()
                // No specific stablehand profession; closest match
                .worker(Profession.CITIZEN)
                .build());

        // ── Defensive ───────────────────────────────────────────────────────
        register(BuildingType.BARRACKS, BuildingInhabitantSpec.builder()
                .worker(Profession.GUARD)
                .worker(Profession.GUARD)
                .worker(Profession.GUARD)
                .build());

        register(BuildingType.GUARD_TOWER, BuildingInhabitantSpec.builder()
                .worker(Profession.GUARD)
                .build());

        register(BuildingType.WATCHTOWER, BuildingInhabitantSpec.builder()
                .worker(Profession.GUARD)
                .build());

        // ── Capitals ────────────────────────────────────────────────────────
        register(BuildingType.CASTLE, BuildingInhabitantSpec.builder()
                .resident(Profession.KINGDOM_RULER)
                .worker(Profession.GUARD)
                .worker(Profession.GUARD)
                .build());



        // ── Building-intrinsic adjacency ────────────────────────────────────

        registerAdjacency(BuildingType.MILLER,
                BuildingAdjacencySpec.builder()
                        .requires("river", 24)
                        .build());

        // FISHERMAN doesn't have a building yet, but if/when it does:
        // registerAdjacency(BuildingType.FISHERMAN,
        //         BuildingAdjacencySpec.builder()
        //                 .requires("coast", 20)
        //                 .build());

        registerAdjacency(BuildingType.DOCKS,
                BuildingAdjacencySpec.builder()
                        .requires("water", 12)
                        .build());

        registerAdjacency(BuildingType.WOODCUTTER,
                BuildingAdjacencySpec.builder()
                        .prefers("forest", 48)
                        .build());

        // STONEMASON benefits from being near stone deposits, but the atlas
        // doesn't currently track stone — leave it as no-op for now and
        // revisit when biome-category gains a "rocky" feature.

        // Buildings with no inhabitants (well, town square, etc.) get the
        // default empty spec automatically.
    }
}