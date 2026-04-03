// src/main/java/tterrag1112/life_in_the_village/Village/Simulation/VillageSimEngine.java
package tterrag1112.life_in_the_village.Village.Simulation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.VillageTreasury;
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


        boolean isLoaded = isVillageLoaded(level, village, data);

// Reconcile if transitioning from unloaded → loaded
        if (isLoaded && sim.wasUnloaded()) {
            reconcileOnLoad(level, village, data, currentTick);
            sim.setWasUnloaded(false);
        }

        if (isLoaded) {
            syncFromReal(level, village, data, sim, currentTick);
        } else {
            sim.setWasUnloaded(true);
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
    // Add to VillageSimEngine.java:

// =========================================================================
// Reconciliation — materialize simulated production on village load
// =========================================================================

    /**
     * Called once when a village transitions from unloaded to loaded.
     * Materializes the goods that were "produced" during the simulation
     * period into the village's physical storage.
     *
     * <p>This is approximate by design — the player wasn't there to see
     * the details, so perfect accuracy isn't needed. What matters is that
     * the stockpile reflects the passage of time.</p>
     */
    public static void reconcileOnLoad(ServerLevel level,
                                       Village village,
                                       VillageSavedData data,
                                       long currentTick) {
        VillageSimData sim = data.getSimData(village.getId()).orElse(null);
        if (sim == null) return;

        long ticksUnloaded = currentTick - sim.getLastSyncTick();
        if (ticksUnloaded < 24000L) return; // less than a day, skip

        float daysUnloaded = ticksUnloaded / 24000f;
        // Cap at 30 days to prevent absurd stockpiles after long absences
        daysUnloaded = Math.min(daysUnloaded, 30f);

        // ── Food reconciliation ──────────────────────────────────────────
        float netFood = sim.foodNetPerDay() * daysUnloaded;
        if (netFood > 0) {
            // Surplus — add food to stockpile
            materializeFood(level, village, data, (int) netFood);
        }
        // Deficit is handled passively — stockpile just won't have been
        // refilled, and needs calculator will flag CRITICAL on next tick.

        // ── Material reconciliation ──────────────────────────────────────
        float netMaterials = sim.materialNetPerDay() * daysUnloaded;
        if (netMaterials > 0) {
            materializeMaterials(level, village, data, (int) netMaterials);
        }

        // ── Treasury reconciliation ──────────────────────────────────────
        data.getTreasury(village.getId()).ifPresent(treasury -> {
            // Simulate tax income and wage drain
            long taxIncome = (long)(sim.getSimulatedPopulation()
                    * VillageTreasury.BASELINE_INCOME_PER_NPC * daysUnloaded);
            long wageDrain = (long)(Math.max(1, sim.getSimulatedPopulation() / 5)
                    * VillageTreasury.GUARD_WAGE * daysUnloaded);
            long propertyTax = (long)(sim.getFarmhouseCount()
                    * VillageTreasury.PROPERTY_TAX_PER_HOUSE * daysUnloaded);

            treasury.deposit(taxIncome + propertyTax);
            treasury.withdraw(wageDrain);
            data.putTreasury(treasury);
        });

        // Mark sim as synced at current tick
        sim.blendReal(
                sim.getFoodProductionPerDay(),
                sim.getFoodConsumptionPerDay(),
                sim.getMaterialProductionPerDay(),
                sim.getMaterialConsumptionPerDay(),
                sim.getSimulatedPopulation(),
                currentTick);
        data.putSimData(sim);

        System.out.printf("[SimReconcile] %s — %.1f days unloaded, " +
                        "food net=%+.0f, mat net=%+.0f%n",
                village.getName(), daysUnloaded, netFood, netMaterials);
    }

    private static void materializeFood(ServerLevel level, Village village,
                                        VillageSavedData data, int nutritionUnits) {
        // Convert nutrition units to bread (5 nutrition each)
        int breadCount = nutritionUnits / 5;
        if (breadCount <= 0) return;

        // Cap at 2 stacks per reconciliation
        breadCount = Math.min(breadCount, 128);

        Building stockpile = findStockpile(village, data);
        if (stockpile == null) return;

        BuildingStorageAccess.storeItem(level, stockpile,
                new ItemStack(Items.BREAD, breadCount));
    }

    private static void materializeMaterials(ServerLevel level, Village village,
                                             VillageSavedData data, int units) {
        // Split between logs and cobblestone
        int logs = units / 2;
        int cobble = units / 2;
        logs = Math.min(logs, 128);
        cobble = Math.min(cobble, 128);

        Building stockpile = findStockpile(village, data);
        if (stockpile == null) return;

        if (logs > 0) {
            BuildingStorageAccess.storeItem(level, stockpile,
                    new ItemStack(Items.OAK_LOG, logs));
        }
        if (cobble > 0) {
            BuildingStorageAccess.storeItem(level, stockpile,
                    new ItemStack(Items.COBBLESTONE, cobble));
        }
    }

    private static Building findStockpile(Village village, VillageSavedData data) {
        return village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() == BuildingType.STOCKPILE)
                .findFirst()
                .orElse(null);
    }
    // Add to VillageSimData:
    private boolean wasUnloaded = false;

    public boolean wasUnloaded() { return wasUnloaded; }
    public void setWasUnloaded(boolean val) { this.wasUnloaded = val; }
}