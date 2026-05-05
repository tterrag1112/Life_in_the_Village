// FILE: src/main/java/tterrag1112/life_in_the_village/Datagen/VillageTypeDatagen.java
package tterrag1112.life_in_the_village.Datagen;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainStrategy;
import tterrag1112.life_in_the_village.Village.SettlementTier;
import tterrag1112.life_in_the_village.Village.VillageTypeBuilder;
import tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Generates all village type JSON files.
 *
 * <h3>One village type per layout</h3>
 * Each of the 16 implemented {@link ShapeType} layouts has a
 * corresponding village type with appropriate buildings, terrain
 * strategy, and town square capacity. The kingdom spawning system
 * picks village types based on terrain suitability — types with
 * matching terrain strategies are preferred for the relevant biomes.
 *
 * <h3>What's gone</h3>
 * NPC and item starter arrays are no longer part of village types.
 * NPCs spawn from the building inhabitant system. Capitals were
 * removed pending reimplementation as a shape recipe.
 */
public class VillageTypeDatagen implements DataProvider {

    private final PackOutput output;

    public VillageTypeDatagen(PackOutput output) {
        this.output = output;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        List<CompletableFuture<?>> futures = new ArrayList<>();

        // ── Flat-terrain villages ──────────────────────────────────────────
        futures.add(saveVillageType(cache, buildDefaultVillage()));
        futures.add(saveVillageType(cache, buildFarmingHamlet()));
        futures.add(saveVillageType(cache, buildTradeCity()));
        futures.add(saveVillageType(cache, buildCrossroadsTown()));
        futures.add(saveVillageType(cache, buildTwinVillage()));
        futures.add(saveVillageType(cache, buildSparseHolding()));

        // ── Slope/forest villages ──────────────────────────────────────────
        futures.add(saveVillageType(cache, buildMiningCamp()));
        futures.add(saveVillageType(cache, buildSacredGrove()));
        futures.add(saveVillageType(cache, buildFrontierOutpost()));
        futures.add(saveVillageType(cache, buildBendHamlet()));
        futures.add(saveVillageType(cache, buildBorderKeep()));

        // ── Mountain villages ──────────────────────────────────────────────
        futures.add(saveVillageType(cache, buildMountainKeep()));
        futures.add(saveVillageType(cache, buildVineyardTerrace()));
        futures.add(saveVillageType(cache, buildCliffHamlet()));

        // ── Water villages ─────────────────────────────────────────────────
        futures.add(saveVillageType(cache, buildRiversideTown()));
        futures.add(saveVillageType(cache, buildPierVillage()));

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    // =========================================================================
    // FLAT-TERRAIN VILLAGES
    // =========================================================================

    /** RADIAL — the baseline village. Balanced civic + production + houses. */
    private JsonObject buildDefaultVillage() {
        return VillageTypeBuilder.create("default_village", "default")
                .shape(ShapeType.RADIAL)
                .terrainStrategy(TerrainStrategy.FLAT)
                .settlementTier(SettlementTier.VILLAGE)
                .townSquareCapacity(5)
                .building(BuildingType.TOWN_HALL,   "town_hall/level_1")
                .building(BuildingType.MARKET,      "market/level_1")
                .building(BuildingType.GUILD_HALL,  "guild_hall/level_1")
                .building(BuildingType.INN,         "inn/level_1")
                .building(BuildingType.STOCKPILE,   "stockpile/level_1")
                .building(BuildingType.BLACKSMITH,  "blacksmith/level_1")
                .building(BuildingType.CARPENTRY,   "carpentry/level_1")
                .building(BuildingType.STONEMASON,  "stonemason/level_1")
                .building(BuildingType.WEAVER,      "weaver/level_1")
                .building(BuildingType.GUARD_TOWER, "guard_tower/level_1")
                .building(BuildingType.FARMHOUSE,   "farmhouse/level_1")
                .buildingN(BuildingType.HOUSE,      "house/level_1", 4)
                .build();
    }

    /** LINEAR — small farming hamlet along a single road.
     *  Phase 22: falls back to RADIAL when LINEAR's recipe-authoring
     *  problems (microfix Fix 2 footprint-budget tightening dropped
     *  most of LINEAR's slot pool) reject the layout. */
    private JsonObject buildFarmingHamlet() {
        return VillageTypeBuilder.create("farming_hamlet", "default")
                .shape(ShapeType.LINEAR)
                .fallbackChain(ShapeType.RADIAL)
                .forcedAxis(true)
                .terrainStrategy(TerrainStrategy.FLAT)
                .townSquareCapacity(3)
                .building(BuildingType.TOWN_HALL, "town_hall/level_1")
                .building(BuildingType.STOCKPILE, "stockpile/level_1")
                .building(BuildingType.MARKET,    "market/level_1")
                .building(BuildingType.MILLER,    "miller/level_1")
                .building(BuildingType.BAKERY,    "bakery/level_1")
                .buildingRange(BuildingType.FARMHOUSE, "farmhouse/level_1", 2, 4)
                .buildingN(BuildingType.HOUSE, "house/level_1", 2)
                .build();
    }

    /** PLAZA — large urban trade city with civic ring and four spurs.
     *  Phase 22: falls back to RADIAL when PLAZA's recipe-authoring
     *  fails the validator (Section 6b — most superflat sites). */
    private JsonObject buildTradeCity() {
        return VillageTypeBuilder.create("trade_city", "default")
                .shape(ShapeType.PLAZA)
                .fallbackChain(ShapeType.RADIAL)
                .terrainStrategy(TerrainStrategy.FLAT)
                .settlementTier(SettlementTier.CITY)
                .canBeCapital(true)
                .kingdomRoles("trade_hub")
                .tradePriority(3)
                .maxPerKingdom(1)
                .townSquareCapacity(8)
                .streetDensity(1.3f)
                .building(BuildingType.TOWN_HALL,   "town_hall/level_1")
                .building(BuildingType.GUILD_HALL,  "guild_hall/level_1")
                .building(BuildingType.TEMPLE,      "temple/level_1")
                .buildingN(BuildingType.MARKET,     "market/level_1", 3)
                .buildingN(BuildingType.WAREHOUSE,  "stockpile/level_1", 2)
                .building(BuildingType.STOCKPILE,   "stockpile/level_1")
                .buildingRange(BuildingType.INN,    "inn/level_1", 2, 3)
                .building(BuildingType.STABLE,      "stable/level_1")
                .building(BuildingType.BLACKSMITH,  "blacksmith/level_1")
                .building(BuildingType.CARPENTRY,   "carpentry/level_1")
                .building(BuildingType.STONEMASON,  "blacksmith/level_1")
                .building(BuildingType.WEAVER,      "carpentry/level_1")
                .building(BuildingType.BAKERY,      "bakery/level_1")
                .buildingN(BuildingType.GUARD_TOWER, "guard_tower/level_1", 2)
                .buildingRange(BuildingType.HOUSE,  "house/level_1", 5, 8)
                .build();
    }

    /** CROSSROADS — market town at a road junction. */
    private JsonObject buildCrossroadsTown() {
        return VillageTypeBuilder.create("crossroads_town", "default")
                .shape(ShapeType.CROSSROADS)
                .terrainStrategy(TerrainStrategy.FLAT)
                .settlementTier(SettlementTier.TOWN)
                .canBeCapital(true)
                .kingdomRoles("trade_hub")
                .tradePriority(3)
                .townSquareCapacity(6)
                .building(BuildingType.TOWN_HALL,   "town_hall/level_1")
                .building(BuildingType.GUILD_HALL,  "guild_hall/level_1")
                .buildingN(BuildingType.MARKET,     "market/level_1", 2)
                .buildingN(BuildingType.INN,        "inn/level_1", 2)
                .building(BuildingType.STABLE,      "stable/level_1")
                .building(BuildingType.STOCKPILE,   "stockpile/level_1")
                .building(BuildingType.BLACKSMITH,  "blacksmith/level_1")
                .building(BuildingType.CARPENTRY,   "carpentry/level_1")
                .building(BuildingType.GUARD_TOWER, "guard_tower/level_1")
                .building(BuildingType.FARMHOUSE,   "farmhouse/level_1")
                .buildingRange(BuildingType.HOUSE,  "house/level_1", 3, 5)
                .build();
    }

    /** DUAL_PLAZA — twin village with two town squares.
     *  Phase 22: falls back to RADIAL (its plaza authoring inherits
     *  PLAZA's superflat issues). */
    private JsonObject buildTwinVillage() {
        return VillageTypeBuilder.create("twin_village", "default")
                .shape(ShapeType.DUAL_PLAZA)
                .fallbackChain(ShapeType.RADIAL)
                .terrainStrategy(TerrainStrategy.FLAT)
                .settlementTier(SettlementTier.TOWN)
                .canBeCapital(true)
                .townSquareCapacity(5)
                .building(BuildingType.TOWN_HALL,   "town_hall/level_1")
                .building(BuildingType.GUILD_HALL,  "guild_hall/level_1")
                .buildingN(BuildingType.MARKET,     "market/level_1", 2)
                .buildingN(BuildingType.INN,        "inn/level_1", 2)
                .building(BuildingType.STOCKPILE,   "stockpile/level_1")
                .building(BuildingType.BLACKSMITH,  "blacksmith/level_1")
                .building(BuildingType.CARPENTRY,   "carpentry/level_1")
                .building(BuildingType.STONEMASON,  "blacksmith/level_1")
                .building(BuildingType.BAKERY,      "bakery/level_1")
                .building(BuildingType.GUARD_TOWER, "guard_tower/level_1")
                .building(BuildingType.FARMHOUSE,   "farmhouse/level_1")
                .buildingRange(BuildingType.HOUSE,  "house/level_1", 4, 7)
                .build();
    }

    /** SPRAWL — sparse, low-density frontier village. */
    private JsonObject buildSparseHolding() {
        return VillageTypeBuilder.create("sparse_holding", "default")
                .shape(ShapeType.SPRAWL)
                .terrainStrategy(TerrainStrategy.FLAT)
                .settlementTier(SettlementTier.OUTPOST)
                .townSquareCapacity(2)
                .streetDensity(0.6f)
                .building(BuildingType.TOWN_HALL, "town_hall/level_1")
                .building(BuildingType.STOCKPILE, "stockpile/level_1")
                .buildingN(BuildingType.FARMHOUSE, "farmhouse/level_1", 2)
                .building(BuildingType.WELL, "stockpile/level_1")
                .buildingRange(BuildingType.HOUSE, "house/level_1", 3, 5)
                .build();
    }

    // =========================================================================
    // SLOPE / FOREST VILLAGES
    // =========================================================================

    /** CLUSTERED — mining camp with separate work/living/storage clumps. */
    private JsonObject buildMiningCamp() {
        return VillageTypeBuilder.create("mining_camp", "default")
                .shape(ShapeType.CLUSTERED)
                .terrainStrategy(TerrainStrategy.SLOPE_AWARE)
                .settlementTier(SettlementTier.OUTPOST)
                .kingdomRoles("industrial")
                .biomeAffinity("mountain")
                .townSquareCapacity(2)
                .streetDensity(0.7f)
                .building(BuildingType.TOWN_HALL,   "town_hall/level_1")
                .buildingN(BuildingType.MINE,       "mine/level_1", 2)
                .building(BuildingType.BLACKSMITH,  "blacksmith/level_1")
                .building(BuildingType.STONEMASON,  "blacksmith/level_1")
                .buildingN(BuildingType.STOCKPILE,  "stockpile/level_1", 2)
                .building(BuildingType.INN,         "inn/level_1")
                .building(BuildingType.BARRACKS,    "barracks/level_1")
                .building(BuildingType.WOODCUTTER,  "woodcutter/level_1")
                .buildingRange(BuildingType.HOUSE,  "house/level_1", 3, 5)
                .build();
    }

    /** GROVE — sacred-center village around an empty natural feature. */
    private JsonObject buildSacredGrove() {
        return VillageTypeBuilder.create("sacred_grove", "default")
                .shape(ShapeType.GROVE)
                .terrainStrategy(TerrainStrategy.SLOPE_AWARE)
                .settlementTier(SettlementTier.VILLAGE)
                .kingdomRoles("religious_center")
                .biomeAffinity("forest")
                .maxPerKingdom(1)
                .townSquareCapacity(5)
                .building(BuildingType.SHRINE,      "chapel/level_1")
                .building(BuildingType.TEMPLE,      "temple/level_1")
                .building(BuildingType.LIBRARY,     "library/level_1")
                .building(BuildingType.APOTHECARY,  "apothecary/level_1")
                .building(BuildingType.STOCKPILE,   "stockpile/level_1")
                .building(BuildingType.INN,         "inn/level_1")
                .building(BuildingType.BAKERY,      "bakery/level_1")
                .building(BuildingType.WEAVER,      "carpentry/level_1")
                .building(BuildingType.CANDLEMAKER, "carpentry/level_1")
                .buildingRange(BuildingType.HOUSE,  "house/level_1", 3, 5)
                .build();
    }

    /** OUTPOST — small fortified core with outlying production stations. */
    private JsonObject buildFrontierOutpost() {
        return VillageTypeBuilder.create("frontier_outpost", "default")
                .shape(ShapeType.OUTPOST)
                .terrainStrategy(TerrainStrategy.SLOPE_AWARE)
                .settlementTier(SettlementTier.OUTPOST)
                .kingdomRoles("frontier")
                .biomeAffinity("forest")
                .townSquareCapacity(3)
                .walled(true)
                .building(BuildingType.TOWN_HALL,    "town_hall/level_1")
                .buildingN(BuildingType.BARRACKS,    "barracks/level_1", 2)
                .buildingN(BuildingType.GUARD_TOWER, "guard_tower/level_1", 4)
                .building(BuildingType.WATCHTOWER,   "watchtower/level_1")
                .building(BuildingType.STOCKPILE,    "stockpile/level_1")
                .building(BuildingType.BLACKSMITH,   "blacksmith/level_1")
                .building(BuildingType.WOODCUTTER,   "woodcutter/level_1")
                .buildingN(BuildingType.HOUSE,       "house/level_1", 3)
                .build();
    }

    /** CHAIN — village strung along a curve following a terrain feature. */
    private JsonObject buildBendHamlet() {
        return VillageTypeBuilder.create("bend_hamlet", "default")
                .shape(ShapeType.CHAIN)
                .terrainStrategy(TerrainStrategy.SLOPE_AWARE)
                .biomeAffinity("forest")
                .townSquareCapacity(3)
                .building(BuildingType.TOWN_HALL,  "town_hall/level_1")
                .building(BuildingType.MARKET,     "market/level_1")
                .building(BuildingType.STOCKPILE,  "stockpile/level_1")
                .building(BuildingType.INN,        "inn/level_1")
                .building(BuildingType.BLACKSMITH, "blacksmith/level_1")
                .building(BuildingType.CARPENTRY,  "carpentry/level_1")
                .building(BuildingType.FARMHOUSE,  "farmhouse/level_1")
                .buildingRange(BuildingType.HOUSE, "house/level_1", 3, 5)
                .build();
    }

    /** ENCLAVE — fortified compound with buildings forming a wall. */
    private JsonObject buildBorderKeep() {
        return VillageTypeBuilder.create("border_keep", "default")
                .shape(ShapeType.ENCLAVE)
                .terrainStrategy(TerrainStrategy.SLOPE_AWARE)
                .settlementTier(SettlementTier.TOWN)
                .canBeCapital(true)
                .kingdomRoles("frontier", "military")
                .biomeAffinity("forest")
                .townSquareCapacity(4)
                .walled(true)
                .building(BuildingType.TOWN_HALL,    "town_hall/level_1")
                .building(BuildingType.GUILD_HALL,   "guild_hall/level_1")
                .building(BuildingType.STOCKPILE,    "stockpile/level_1")
                .buildingN(BuildingType.BARRACKS,    "barracks/level_1", 2)
                .buildingN(BuildingType.GUARD_TOWER, "guard_tower/level_1", 4)
                .building(BuildingType.WATCHTOWER,   "watchtower/level_1")
                .building(BuildingType.PRISON,       "prison/level_1")
                .building(BuildingType.BLACKSMITH,   "blacksmith/level_1")
                .building(BuildingType.ARMORER,      "armorer/level_1")
                .buildingRange(BuildingType.HOUSE,   "house/level_1", 4, 6)
                .build();
    }

    // =========================================================================
    // MOUNTAIN VILLAGES
    // =========================================================================

    /** HILLTOP — defensive citadel on a mountain peak. */
    private JsonObject buildMountainKeep() {
        return VillageTypeBuilder.create("mountain_keep", "default")
                .shape(ShapeType.HILLTOP)
                .terrainStrategy(TerrainStrategy.MOUNTAIN)
                .settlementTier(SettlementTier.TOWN)
                .canBeCapital(true)
                .kingdomRoles("military")
                .biomeAffinity("mountain")
                .townSquareCapacity(4)
                .walled(true)
                .building(BuildingType.TOWN_HALL,    "town_hall/level_1")
                .building(BuildingType.CASTLE,       "castle/level_1")
                .buildingN(BuildingType.BARRACKS,    "barracks/level_1", 2)
                .buildingN(BuildingType.GUARD_TOWER, "guard_tower/level_1", 4)
                .buildingN(BuildingType.WATCHTOWER,  "watchtower/level_1", 2)
                .building(BuildingType.PRISON,       "prison/level_1")
                .building(BuildingType.BLACKSMITH,   "blacksmith/level_1")
                .building(BuildingType.ARMORER,      "armorer/level_1")
                .building(BuildingType.STONEMASON,   "blacksmith/level_1")
                .building(BuildingType.STOCKPILE,    "stockpile/level_1")
                .building(BuildingType.TEMPLE,       "temple/level_1")
                .buildingRange(BuildingType.HOUSE,   "house/level_1", 3, 5)
                .build();
    }

    /** TERRACED — vineyard village stepped down a slope. */
    private JsonObject buildVineyardTerrace() {
        return VillageTypeBuilder.create("vineyard_terrace", "default")
                .shape(ShapeType.TERRACED)
                .terrainStrategy(TerrainStrategy.MOUNTAIN)
                .biomeAffinity("mountain")
                .townSquareCapacity(3)
                .building(BuildingType.TOWN_HALL,   "town_hall/level_1")
                .building(BuildingType.WINERY,      "winery/level_1")
                .buildingN(BuildingType.VINEYARD,   "winery/level_1", 3)
                .building(BuildingType.MARKET,      "market/level_1")
                .building(BuildingType.STOCKPILE,   "stockpile/level_1")
                .building(BuildingType.ATELIER,     "atelier/level_1")
                .building(BuildingType.BAKERY,      "bakery/level_1")
                .buildingRange(BuildingType.FARMHOUSE, "farmhouse/level_1", 2, 3)
                .buildingRange(BuildingType.HOUSE,  "house/level_1", 3, 5)
                .build();
    }

    /** ROADSIDE — hamlet built up against a cliff edge or other feature. */
    private JsonObject buildCliffHamlet() {
        return VillageTypeBuilder.create("cliff_hamlet", "default")
                .shape(ShapeType.ROADSIDE)
                .terrainStrategy(TerrainStrategy.MOUNTAIN)
                .settlementTier(SettlementTier.OUTPOST)
                .biomeAffinity("mountain")
                .townSquareCapacity(2)
                .building(BuildingType.TOWN_HALL,  "town_hall/level_1")
                .building(BuildingType.STOCKPILE,  "stockpile/level_1")
                .building(BuildingType.INN,        "inn/level_1")
                .building(BuildingType.MARKET,     "market/level_1")
                .building(BuildingType.WATCHTOWER, "watchtower/level_1")
                .building(BuildingType.BLACKSMITH, "blacksmith/level_1")
                .buildingRange(BuildingType.HOUSE, "house/level_1", 3, 5)
                .build();
    }

    // =========================================================================
    // WATER VILLAGES
    // =========================================================================

    /** RIVERINE — village paralleling a river or lake shore. */
    private JsonObject buildRiversideTown() {
        return VillageTypeBuilder.create("riverside_town", "default")
                .shape(ShapeType.RIVERINE)
                .forcedAxis(true)
                .terrainStrategy(TerrainStrategy.WATERFRONT)
                .settlementTier(SettlementTier.VILLAGE)
                .biomeAffinity("river")
                .townSquareCapacity(4)
                .building(BuildingType.TOWN_HALL,  "town_hall/level_1")
                .building(BuildingType.MARKET,     "market/level_1")
                .building(BuildingType.INN,        "inn/level_1")
                .building(BuildingType.STOCKPILE,  "stockpile/level_1")
                .building(BuildingType.FISHERY,    "farmhouse/level_1")
                .building(BuildingType.MILLER,     "miller/level_1")
                .building(BuildingType.BAKERY,     "bakery/level_1")
                .building(BuildingType.CARPENTRY,  "carpentry/level_1")
                .building(BuildingType.FARMHOUSE,  "farmhouse/level_1")
                .buildingRange(BuildingType.HOUSE, "house/level_1", 3, 5)
                .build();
    }

    /** DOCKSIDE — pier village running into the water. */
    private JsonObject buildPierVillage() {
        return VillageTypeBuilder.create("pier_village", "default")
                .shape(ShapeType.DOCKSIDE)
                .terrainStrategy(TerrainStrategy.WATERFRONT)
                .settlementTier(SettlementTier.VILLAGE)
                .kingdomRoles("trade_hub")
                .tradePriority(3)
                .biomeAffinity("coast")
                .townSquareCapacity(3)
                .building(BuildingType.TOWN_HALL,  "town_hall/level_1")
                .building(BuildingType.PIER,       "stockpile/level_1")
                .building(BuildingType.DOCKS,      "stockpile/level_1")
                .buildingN(BuildingType.FISHERY,   "farmhouse/level_1", 2)
                .building(BuildingType.WAREHOUSE,  "stockpile/level_1")
                .building(BuildingType.STOCKPILE,  "stockpile/level_1")
                .building(BuildingType.MARKET,     "market/level_1")
                .building(BuildingType.INN,        "inn/level_1")
                .building(BuildingType.CARPENTRY,  "carpentry/level_1")
                .buildingRange(BuildingType.HOUSE, "house/level_1", 3, 5)
                .build();
    }

    // =========================================================================
    // Save helper
    // =========================================================================

    private CompletableFuture<?> saveVillageType(CachedOutput cache, JsonObject json) {
        String type = json.get("type").getAsString();
        Path path = output.getOutputFolder()
                .resolve("data/" + Life_in_the_village.MODID
                        + "/village_types/" + type + ".json");
        return DataProvider.saveStable(cache,
                new GsonBuilder().setPrettyPrinting().create().toJsonTree(json),
                path);
    }

    @Override
    public String getName() {
        return "Life in the Village Village Types";
    }



}