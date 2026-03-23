// src/main/java/tterrag1112/life_in_the_village/Village/Simulation/VillageSimEngine.java
package tterrag1112.life_in_the_village.Village.Simulation;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Needs.FoodValueHelper;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.SeasonTracker;

import java.util.List;
import java.util.Optional;

/**
 * Drives the village simulation system.
 *
 * <h3>Called from</h3>
 * {@code ServerTickDispatcher} in the once-per-day per-village block.
 * The engine is purely computational — no block placement, no entity
 * spawning. It is safe to call on any village regardless of whether
 * its chunks are loaded.
 *
 * <h3>Loaded vs unloaded villages</h3>
 * A village is considered "loaded" if any of its building bounding boxes
 * are within a loaded chunk. The engine detects this by checking whether
 * at least one entity query returns results — if the village centre chunk
 * is loaded, NPC queries work; if not, we fall back to sim data.
 */
public final class VillageSimEngine {

    // ── Tuning constants ──────────────────────────────────────────────────────
    /** Nutrition units produced per farmhouse per day (base, temperate season). */
    private static final float BASE_FOOD_PER_FARMHOUSE = 120f;
    /** Nutrition units consumed per NPC per day (base). */
    private static final float BASE_FOOD_PER_NPC = 20f;
    /** Material units produced per mine per day. */
    private static final float BASE_MATERIALS_PER_MINE = 64f;
    /** Material units consumed per building per day (maintenance). */
    private static final float BASE_MATERIALS_PER_BUILDING = 2f;

    private VillageSimEngine() {}

    // =========================================================================
    // Entry point — call once per in-game day per village
    // =========================================================================

    /**
     * Updates the simulation snapshot for the given village.
     *
     * <p>If the village centre is in a loaded chunk, real NPC and stockpile
     * data is measured and blended into the rolling average. Otherwise,
     * the existing sim rates are advanced using season multipliers only.</p>
     */
    public static void tick(ServerLevel level,
                            Village village,
                            VillageSavedData data,
                            long currentTick) {
        VillageSimData sim = data.getSimData(village.getId())
                .orElseGet(() -> buildBaseline(village, data, currentTick));

        if (isVillageLoaded(level, village, data)) {
            syncFromReal(level, village, data, sim, currentTick);
        } else {
            advanceSim(sim, currentTick);
        }

        data.putSimData(sim);
        data.markDirty();
    }

    // =========================================================================
    // Baseline — called at village spawn and for villages with no sim yet
    // =========================================================================

    /**
     * Builds a baseline sim snapshot from the village's building list.
     * No chunk loading or entity queries needed.
     */
    public static VillageSimData buildBaseline(Village village,
                                               VillageSavedData data,
                                               long tick) {
        int farmhouseCount = 0;
        int mineCount      = 0;
        int buildingCount  = 0;

        for (var bid : village.getBuildingIds()) {
            data.getBuildingById(bid).ifPresent(b -> {});  // warmup index
        }
        for (var bid : village.getBuildingIds()) {
            Optional<Building> bOpt = data.getBuildingById(bid);
            if (bOpt.isEmpty()) continue;
            buildingCount++;
            BuildingType t = bOpt.get().getType();
            if (t == BuildingType.FARMHOUSE) farmhouseCount++;
            if (t == BuildingType.MINE)      mineCount++;
        }

        // Estimate population: 2 NPCs per house/farmhouse
        int houseCount = 0;
        for (var bid : village.getBuildingIds()) {
            data.getBuildingById(bid).ifPresent(b -> {});
        }
        for (var bid : village.getBuildingIds()) {
            Optional<Building> bOpt = data.getBuildingById(bid);
            if (bOpt.isEmpty()) continue;
            BuildingType t = bOpt.get().getType();
            if (t == BuildingType.HOUSE || t == BuildingType.FARMHOUSE) houseCount++;
        }
        int estimatedPop = Math.max(1, houseCount * 2);

        float foodProd = farmhouseCount * BASE_FOOD_PER_FARMHOUSE;
        float foodCons = estimatedPop  * BASE_FOOD_PER_NPC;
        float matProd  = mineCount     * BASE_MATERIALS_PER_MINE;
        float matCons  = buildingCount * BASE_MATERIALS_PER_BUILDING;

        return new VillageSimData(
                village.getId(),
                foodProd, foodCons,
                matProd,  matCons,
                estimatedPop,
                farmhouseCount, mineCount,
                tick);
    }

    // =========================================================================
    // Sync from real data (village is loaded)
    // =========================================================================

    private static void syncFromReal(ServerLevel level,
                                     Village village,
                                     VillageSavedData data,
                                     VillageSimData sim,
                                     long tick) {
        // ── Real population ───────────────────────────────────────────────────
        int realPop = (int) level.getEntitiesOfClass(
                TownspersonMob.class,
                village.getBounds(data)
                        .map(b -> b.inflate(32))
                        .orElse(new net.minecraft.world.phys.AABB(0,0,0,0,0,0)),
                mob -> mob.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false)
        ).size();

        // ── Real food production — sum nutrition from all stockpiles ──────────
        float realFoodProd = 0f;
        for (var bid : village.getBuildingIds()) {
            Optional<Building> bOpt = data.getBuildingById(bid);
            if (bOpt.isEmpty()) continue;
            if (bOpt.get().getType() != BuildingType.STOCKPILE) continue;
            for (var container : BuildingStorageAccess.findInventories(level, bOpt.get())) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    var stack = container.getItem(i);
                    if (!stack.isEmpty()) {
                        realFoodProd += FoodValueHelper.getStackNutrition(stack);
                    }
                }
            }
        }
        // Treat current stockpile food as "one day's worth of production"
        // — crude but avoids needing to measure delta between ticks
        float seasonFoodMult = SeasonTracker.currentSeason(level).getFoodNeedMultiplier();
        float realFoodCons = realPop * BASE_FOOD_PER_NPC * seasonFoodMult;

        // ── Real material production — count building types ───────────────────
        int farmhouses = 0, mines = 0, buildings = 0;
        for (var bid : village.getBuildingIds()) {
            Optional<Building> bOpt = data.getBuildingById(bid);
            if (bOpt.isEmpty()) continue;
            buildings++;
            if (bOpt.get().getType() == BuildingType.FARMHOUSE) farmhouses++;
            if (bOpt.get().getType() == BuildingType.MINE)      mines++;
        }
        float realMatProd = mines     * BASE_MATERIALS_PER_MINE;
        float realMatCons = buildings * BASE_MATERIALS_PER_BUILDING;

        sim.blendReal(realFoodProd, realFoodCons,
                realMatProd, realMatCons,
                realPop, tick);

        sim.setFarmhouseCount(farmhouses);
        sim.setMineCount(mines);
    }

    // =========================================================================
    // Advance sim (village is not loaded)
    // =========================================================================

    private static void advanceSim(VillageSimData sim, long currentTick) {
        // Seasonally adjust consumption (production assumed stable —
        // unloaded farmhouses aren't actually growing anything, so we
        // don't inflate production either)
        long daysSinceSync = (currentTick - sim.getLastSyncTick()) / 24000L;
        if (daysSinceSync < 1) return; // nothing to advance

        // Just nudge the season multiplier into consumption
        // (full advance is unnecessary — kingdom just needs the direction)
        float foodMult = SeasonTracker.currentSeason(currentTick)
                .getFoodNeedMultiplier();
        sim.advanceSim(foodMult, 1.0f);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Returns true if the village centre chunk is loaded, meaning real
     * entity queries are valid.
     */
    private static boolean isVillageLoaded(ServerLevel level,
                                           Village village,
                                           VillageSavedData data) {
        var centre = village.getVillageCentre();
        if (centre == null) return false;
        return level.isLoaded(centre);
    }
}