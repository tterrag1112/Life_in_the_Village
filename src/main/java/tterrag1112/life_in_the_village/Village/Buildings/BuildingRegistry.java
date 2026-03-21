package tterrag1112.life_in_the_village.Village.Buildings;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;

import java.util.*;

public class BuildingRegistry {

    // -------------------------------------------------------------------------
    // Building definition
    // -------------------------------------------------------------------------

    public record BuildingDef(
            BuildingType type,
            VillageSizeTier minTier,          // minimum village tier to unlock
            int maxLevel,                      // highest upgrade level
            List<BuildingType> prerequisites, // must exist first
            Map<Item, Integer> constructionMaterials,  // items from stockpile
            int constructionCost,              // silver coins
            int populationRequirement,         // min NPCs to unlock
            boolean unique,                    // only one allowed per village
            String structurePath               // base path e.g. "blacksmith/level_1"
    ) {}

    // -------------------------------------------------------------------------
    // Registry
    // -------------------------------------------------------------------------

    private static final Map<BuildingType, BuildingDef> REGISTRY
            = new LinkedHashMap<>();

    static {
        // -------------------------------------------------------
        // Tier 1 — Hamlet
        // -------------------------------------------------------
        register(BuildingType.TOWN_HALL,
                VillageSizeTier.HAMLET, 3,
                List.of(),
                Map.of(Items.OAK_LOG, 32,
                        Items.COBBLESTONE, 64),
                5, 0, true,
                "town_hall/level_1");

        register(BuildingType.HOUSE,
                VillageSizeTier.HAMLET, 3,
                List.of(),
                Map.of(Items.OAK_LOG, 16,
                        Items.COBBLESTONE, 32),
                2, 0, false,
                "house/level_1");

        register(BuildingType.FARMHOUSE,
                VillageSizeTier.HAMLET, 3,
                List.of(),
                Map.of(Items.OAK_LOG, 16,
                        Items.COBBLESTONE, 16),
                2, 0, false,
                "farmhouse/level_1");

        register(BuildingType.STOCKPILE,
                VillageSizeTier.HAMLET, 3,
                List.of(),
                Map.of(Items.OAK_LOG, 24,
                        Items.COBBLESTONE, 32),
                3, 0, true,
                "stockpile/level_1");

        register(BuildingType.WELL,
                VillageSizeTier.HAMLET, 1,
                List.of(),
                Map.of(Items.COBBLESTONE, 16,
                        Items.BUCKET, 1),
                1, 0, true,
                "well/level_1");

        // -------------------------------------------------------
        // Tier 2 — Village
        // -------------------------------------------------------
        register(BuildingType.BLACKSMITH,
                VillageSizeTier.VILLAGE, 3,
                List.of(BuildingType.STOCKPILE),
                Map.of(Items.COBBLESTONE, 48,
                        Items.IRON_INGOT, 16,
                        Items.OAK_LOG, 16),
                8, 4, true,
                "blacksmith/level_1");

        register(BuildingType.MARKET,
                VillageSizeTier.VILLAGE, 2,
                List.of(BuildingType.STOCKPILE),
                Map.of(Items.OAK_LOG, 32,
                        Items.OAK_PLANKS, 32),
                6, 4, true,
                "market/level_1");

        register(BuildingType.INN,
                VillageSizeTier.VILLAGE, 3,
                List.of(BuildingType.HOUSE),
                Map.of(Items.OAK_LOG, 48,
                        Items.OAK_PLANKS, 32,
                        Items.COBBLESTONE, 24),
                10, 6, true,
                "inn/level_1");

        register(BuildingType.MINE,
                VillageSizeTier.VILLAGE, 3,
                List.of(BuildingType.BLACKSMITH),
                Map.of(Items.COBBLESTONE, 64,
                        Items.OAK_LOG, 24,
                        Items.IRON_PICKAXE, 2),
                8, 5, false,
                "mine/level_1");

        register(BuildingType.CARPENTRY,
                VillageSizeTier.VILLAGE, 3,
                List.of(BuildingType.STOCKPILE),
                Map.of(Items.OAK_LOG, 48,
                        Items.OAK_PLANKS, 32),
                6, 4, true,
                "carpentry/level_1");

        register(BuildingType.BAKERY,
                VillageSizeTier.VILLAGE, 2,
                List.of(BuildingType.FARMHOUSE),
                Map.of(Items.COBBLESTONE, 32,
                        Items.OAK_LOG, 16,
                        Items.WHEAT, 16),
                5, 4, true,
                "bakery/level_1");

        register(BuildingType.STABLE,
                VillageSizeTier.VILLAGE, 2,
                List.of(BuildingType.HOUSE),
                Map.of(Items.OAK_LOG, 32,
                        Items.HAY_BLOCK, 8),
                5, 5, true,
                "stable/level_1");

        register(BuildingType.MILLER,
                VillageSizeTier.VILLAGE, 2,
                List.of(BuildingType.FARMHOUSE),
                Map.of(Items.OAK_LOG, 24,
                        Items.COBBLESTONE, 32,
                        Items.STONE, 16),
                5, 4, true,
                "miller/level_1");

        register(BuildingType.WOODCUTTER,
                VillageSizeTier.VILLAGE, 2,
                List.of(BuildingType.STOCKPILE),
                Map.of(Items.OAK_LOG, 16,
                        Items.COBBLESTONE, 16),
                3, 3, false,
                "woodcutter/level_1");

        // Add in Tier 2 — Village section, after WOODCUTTER:
        register(BuildingType.GUARD_TOWER,
                VillageSizeTier.VILLAGE, 3,
                List.of(BuildingType.TOWN_HALL),
                Map.of(Items.COBBLESTONE,  64,
                        Items.OAK_LOG,      16,
                        Items.IRON_INGOT,    8),
                8, 5, false,
                "guard_tower/level_1");

        register(BuildingType.GUILD_HALL,
                VillageSizeTier.VILLAGE, 3,
                List.of(BuildingType.TOWN_HALL,
                        BuildingType.MARKET),
                Map.of(Items.COBBLESTONE, 64,
                        Items.OAK_LOG, 48,
                        Items.IRON_INGOT, 8),
                15, 6, true,
                "guild_hall/level_1");

        // -------------------------------------------------------
        // Tier 3 — Town
        // -------------------------------------------------------
        register(BuildingType.BARRACKS,
                VillageSizeTier.TOWN, 3,
                List.of(BuildingType.BLACKSMITH,
                        BuildingType.TOWN_HALL),
                Map.of(Items.COBBLESTONE, 96,
                        Items.IRON_INGOT, 32,
                        Items.OAK_LOG, 32),
                20, 10, true,
                "barracks/level_1");

        register(BuildingType.TEMPLE,
                VillageSizeTier.TOWN, 2,
                List.of(BuildingType.TOWN_HALL),
                Map.of(Items.COBBLESTONE, 128,
                        Items.STONE_BRICKS, 64,
                        Items.GOLD_INGOT, 4),
                25, 10, true,
                "temple/level_1");

        register(BuildingType.LIBRARY,
                VillageSizeTier.TOWN, 2,
                List.of(BuildingType.TOWN_HALL),
                Map.of(Items.OAK_LOG, 48,
                        Items.BOOKSHELF, 16,
                        Items.OAK_PLANKS, 32),
                15, 8, true,
                "library/level_1");

        register(BuildingType.APOTHECARY,
                VillageSizeTier.TOWN, 2,
                List.of(BuildingType.MARKET),
                Map.of(Items.OAK_LOG, 24,
                        Items.GLASS, 16,
                        Items.COBBLESTONE, 32),
                10, 8, true,
                "apothecary/level_1");

        register(BuildingType.WATCHTOWER,
                VillageSizeTier.TOWN, 3,
                List.of(BuildingType.BARRACKS),
                Map.of(Items.COBBLESTONE, 96,
                        Items.OAK_LOG, 16),
                12, 8, false,
                "watchtower/level_1");

        register(BuildingType.STONEMASON,
                VillageSizeTier.TOWN, 2,
                List.of(BuildingType.MINE),
                Map.of(Items.COBBLESTONE, 64,
                        Items.STONE, 32),
                8, 6, true,
                "stonemason/level_1");

        register(BuildingType.WEAVER,
                VillageSizeTier.TOWN, 2,
                List.of(BuildingType.FARMHOUSE),
                Map.of(Items.OAK_LOG, 24,
                        Items.WHITE_WOOL, 16),
                8, 6, true,
                "weaver/level_1");

        register(BuildingType.CANDLEMAKER,
                VillageSizeTier.TOWN, 2,
                List.of(BuildingType.MARKET),
                Map.of(Items.OAK_LOG, 16,
                        Items.HONEYCOMB, 8,
                        Items.STRING, 16),
                6, 6, true,
                "candlemaker/level_1");

        register(BuildingType.PRISON,
                VillageSizeTier.TOWN, 1,
                List.of(BuildingType.BARRACKS,
                        BuildingType.TOWN_HALL),
                Map.of(Items.IRON_BARS, 32,
                        Items.COBBLESTONE, 64,
                        Items.OAK_LOG, 16),
                15, 10, true,
                "prison/level_1");

        register(BuildingType.BELL_TOWER,
                VillageSizeTier.TOWN, 1,
                List.of(BuildingType.TEMPLE),
                Map.of(Items.COBBLESTONE, 64,
                        Items.STONE_BRICKS, 32,
                        Items.BELL, 1),
                20, 8, true,
                "bell_tower/level_1");

        // -------------------------------------------------------
        // Tier 4 — City
        // -------------------------------------------------------
        register(BuildingType.NOBLE_MANOR,
                VillageSizeTier.CITY, 3,
                List.of(BuildingType.TOWN_HALL,
                        BuildingType.MARKET),
                Map.of(Items.STONE_BRICKS, 128,
                        Items.OAK_LOG, 64,
                        Items.GLASS, 32,
                        Items.GOLD_INGOT, 8),
                50, 15, false,
                "noble_manor/level_1");

        register(BuildingType.WINERY,
                VillageSizeTier.CITY, 2,
                List.of(BuildingType.FARMHOUSE,
                        BuildingType.MARKET),
                Map.of(Items.OAK_LOG, 48,
                        Items.GLASS, 16,
                        Items.SWEET_BERRIES, 32),
                20, 10, false,
                "winery/level_1");

        register(BuildingType.ARMORER,
                VillageSizeTier.CITY, 2,
                List.of(BuildingType.BLACKSMITH,
                        BuildingType.BARRACKS),
                Map.of(Items.COBBLESTONE, 64,
                        Items.IRON_INGOT, 48,
                        Items.GOLD_INGOT, 8),
                25, 12, true,
                "armorer/level_1");

        register(BuildingType.TOOLSMITH,
                VillageSizeTier.CITY, 2,
                List.of(BuildingType.BLACKSMITH,
                        BuildingType.MINE),
                Map.of(Items.COBBLESTONE, 48,
                        Items.IRON_INGOT, 32,
                        Items.OAK_LOG, 24),
                20, 12, true,
                "toolsmith/level_1");

        register(BuildingType.ATELIER,
                VillageSizeTier.CITY, 2,
                List.of(BuildingType.LIBRARY,
                        BuildingType.WEAVER),
                Map.of(Items.OAK_LOG, 48,
                        Items.WHITE_WOOL, 24,
                        Items.GLASS, 16),
                20, 12, true,
                "atelier/level_1");

        register(BuildingType.DOCKS,
                VillageSizeTier.CITY, 2,
                List.of(BuildingType.MARKET,
                        BuildingType.STOCKPILE),
                Map.of(Items.OAK_LOG, 96,
                        Items.OAK_PLANKS, 64,
                        Items.IRON_INGOT, 16),
                30, 15, false,
                "docks/level_1");

        register(BuildingType.CASTLE,
                VillageSizeTier.CITY, 3,
                List.of(BuildingType.BARRACKS,
                        BuildingType.WATCHTOWER,
                        BuildingType.TOWN_HALL),
                Map.of(Items.STONE_BRICKS, 256,
                        Items.IRON_INGOT, 64,
                        Items.OAK_LOG, 64),
                100, 20, true,
                "castle/level_1");
    }

    private static void register(BuildingType type,
                                 VillageSizeTier minTier,
                                 int maxLevel,
                                 List<BuildingType> prerequisites,
                                 Map<Item, Integer> materials,
                                 int coinCost,
                                 int populationReq,
                                 boolean unique,
                                 String structurePath) {
        REGISTRY.put(type, new BuildingDef(
                type, minTier, maxLevel,
                prerequisites, materials,
                coinCost, populationReq,
                unique, structurePath));
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    public static Optional<BuildingDef> get(
            BuildingType type) {
        return Optional.ofNullable(REGISTRY.get(type));
    }

    public static List<BuildingDef> getAllForTier(
            VillageSizeTier tier) {
        return REGISTRY.values().stream()
                .filter(d -> d.minTier().ordinal()
                        <= tier.ordinal())
                .toList();
    }

    /**
     * Returns true if a village can build this type given
     * its current buildings and tier.
     */
    public static boolean isUnlocked(
            BuildingType type,
            List<Building> existingBuildings,
            VillageSizeTier tier) {
        BuildingDef def = REGISTRY.get(type);
        if (def == null) return false;

        // Check tier
        if (tier.ordinal() < def.minTier().ordinal())
            return false;

        // Check unique
        if (def.unique()) {
            boolean alreadyExists = existingBuildings.stream()
                    .anyMatch(b -> b.getType() == type);
            if (alreadyExists) return false;
        }

        // Check prerequisites
        Set<BuildingType> existing = new HashSet<>();
        existingBuildings.forEach(b ->
                existing.add(b.getType()));
        return existing.containsAll(def.prerequisites());
    }

    /**
     * Returns true if a building can be upgraded further.
     */
    public static boolean canUpgrade(Building building) {
        BuildingDef def = REGISTRY.get(building.getType());
        if (def == null) return false;
        return building.getLevel() < def.maxLevel();
    }

    public static Map<BuildingType, BuildingDef>
    getAll() {
        return Collections.unmodifiableMap(REGISTRY);
    }


}
