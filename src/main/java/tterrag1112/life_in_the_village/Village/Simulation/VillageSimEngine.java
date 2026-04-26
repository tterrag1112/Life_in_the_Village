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

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Drives the village simulation system.
 *
 * <h3>Phase 4 doc 25 refactor</h3>
 * The engine now operates on {@link ResourceCategory}-keyed maps. Each
 * building's contribution is looked up in
 * {@link BuildingResourceProfile#TABLE}; population-driven food
 * consumption is generalized as a FOOD entry on the consumption map.
 *
 * <h3>Loaded vs unloaded villages</h3>
 * A village is "loaded" iff its centre chunk is in
 * {@link ServerLevel#isLoaded}. Loaded → {@link #syncFromReal} samples
 * real production + blends. Unloaded → {@link #advanceSim} nudges by
 * season multipliers only.
 */
public final class VillageSimEngine {

    /** Nutrition units consumed per NPC per day (base, before season mult). */
    private static final float BASE_FOOD_PER_NPC = 20f;

    private VillageSimEngine() {}

    // =========================================================================
    // Entry point — call once per in-game day per village
    // =========================================================================

    public static void tick(ServerLevel level, Village village,
                            VillageSavedData data, long currentTick) {
        VillageSimData sim = data.getSimData(village.getId())
                .orElseGet(() -> buildBaseline(village, data, currentTick));

        boolean isLoaded = isVillageLoaded(level, village);

        if (isLoaded && sim.wasUnloaded()) {
            reconcileOnLoad(level, village, data, sim, currentTick);
            sim.setWasUnloaded(false);
        }

        if (isLoaded) {
            syncFromReal(level, village, data, sim, currentTick);
            sim.blendRealEconomy(
                    village.getTreasuryBronze(),
                    countWageNpcs(level, village, data) * 8f,
                    sim.getSimulatedPopulation() * 3f);
        } else {
            sim.setWasUnloaded(true);
            advanceSim(sim, currentTick);
        }

        data.putSimData(sim);
        data.markDirty();
    }

    // =========================================================================
    // Baseline — village spawn / sim never seen before
    // =========================================================================

    /**
     * Builds a baseline sim snapshot from the village's building list
     * by summing {@link BuildingResourceProfile} entries. Population
     * is estimated from house count (2 NPCs / house). No chunk loading.
     */
    public static VillageSimData buildBaseline(Village village,
                                               VillageSavedData data,
                                               long tick) {
        EnumMap<ResourceCategory, Float> prod = newCategoryMap();
        EnumMap<ResourceCategory, Float> cons = newCategoryMap();

        int farmhouseCount = 0;
        int mineCount      = 0;
        int houseCount     = 0;

        for (var bid : village.getBuildingIds()) {
            Optional<Building> bOpt = data.getBuildingById(bid);
            if (bOpt.isEmpty()) continue;
            BuildingType t = bOpt.get().getType();
            accumulateProfile(t, prod, cons);
            switch (t) {
                case FARMHOUSE -> { farmhouseCount++; houseCount++; }
                case HOUSE     -> houseCount++;
                case MINE      -> mineCount++;
                default        -> {}
            }
        }

        int estimatedPop = Math.max(1, houseCount * 2);
        cons.merge(ResourceCategory.FOOD, estimatedPop * BASE_FOOD_PER_NPC, Float::sum);

        return new VillageSimData(
                village.getId(),
                prod, cons,
                estimatedPop,
                farmhouseCount, mineCount,
                tick, 0f, 0f, 0f);
    }

    // =========================================================================
    // Sync from real data (village is loaded)
    // =========================================================================

    private static void syncFromReal(ServerLevel level,
                                     Village village,
                                     VillageSavedData data,
                                     VillageSimData sim,
                                     long tick) {
        int realPop = (int) level.getEntitiesOfClass(
                TownspersonMob.class,
                village.getBounds(data)
                        .map(b -> b.inflate(32))
                        .orElse(new net.minecraft.world.phys.AABB(0, 0, 0, 0, 0, 0)),
                mob -> mob.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false)
        ).size();

        EnumMap<ResourceCategory, Float> realProd = newCategoryMap();
        EnumMap<ResourceCategory, Float> realCons = newCategoryMap();

        // Population-driven food consumption.
        float seasonFoodMult = SeasonTracker.currentSeason(level).getFoodNeedMultiplier();
        realCons.merge(ResourceCategory.FOOD,
                realPop * BASE_FOOD_PER_NPC * seasonFoodMult, Float::sum);

        int farmhouses = 0, mines = 0;
        for (var bid : village.getBuildingIds()) {
            Optional<Building> bOpt = data.getBuildingById(bid);
            if (bOpt.isEmpty()) continue;
            BuildingType t = bOpt.get().getType();
            accumulateProfile(t, realProd, realCons);
            if (t == BuildingType.FARMHOUSE) farmhouses++;
            if (t == BuildingType.MINE)      mines++;

            // Stockpile actual nutrition counts as direct FOOD production
            // — measures real-world stock state on top of the per-building
            // profile estimate so a stockpile that's been raided this
            // tick won't keep claiming a high steady output.
            if (t == BuildingType.STOCKPILE) {
                float nutrition = sumStockpileNutrition(level, bOpt.get());
                realProd.merge(ResourceCategory.FOOD, nutrition, Float::sum);
            }
        }

        // COIN_INFLUX visitor flux placeholder — Phase 4 doc 29 wires
        // the real estimate. v1 returns 0 so the partner search has
        // nothing fabricated to chase.
        realProd.merge(ResourceCategory.COIN_INFLUX,
                estimateVisitorFlux(village, data), Float::sum);

        sim.blendReal(realProd, realCons, realPop, tick);
        sim.setFarmhouseCount(farmhouses);
        sim.setMineCount(mines);
    }

    // =========================================================================
    // Advance sim (village is not loaded)
    // =========================================================================

    private static void advanceSim(VillageSimData sim, long currentTick) {
        long daysSinceSync = (currentTick - sim.getLastSyncTick()) / 24000L;
        if (daysSinceSync < 1) return;
        float foodMult = SeasonTracker.currentSeason(currentTick).getFoodNeedMultiplier();
        sim.advanceSim(foodMult, 1.0f);
    }

    // =========================================================================
    // Reconciliation — materialize simulated production on village load
    // =========================================================================

    /**
     * Single canonical reconcile path. The previous file shipped two
     * overloaded versions (one taking {@code sim}, one looking it up);
     * this rewrite collapses to one taking {@code sim} explicitly so
     * the caller doesn't pay the lookup twice in
     * {@link #tick}.
     */
    public static void reconcileOnLoad(ServerLevel level,
                                       Village village,
                                       VillageSavedData data,
                                       VillageSimData sim,
                                       long currentTick) {
        long ticksUnloaded = currentTick - sim.getLastSyncTick();
        if (ticksUnloaded < 24000L) return;

        float daysUnloaded = Math.min(ticksUnloaded / 24000f, 30f);

        float netFood = sim.net(ResourceCategory.FOOD) * daysUnloaded;
        if (netFood > 0) {
            materializeFood(level, village, data, (int) netFood);
        }

        float netMaterials = sim.net(ResourceCategory.BUILDING_MATERIALS) * daysUnloaded;
        if (netMaterials > 0) {
            materializeMaterials(level, village, data, (int) netMaterials);
        }

        // Treasury reconciliation — same shape as before.
        float finalDays = daysUnloaded;
        data.getTreasury(village.getId()).ifPresent(treasury -> {
            long taxIncome = (long)(sim.getSimulatedPopulation()
                    * VillageTreasury.BASELINE_INCOME_PER_NPC * finalDays);
            long wageDrain = (long)(Math.max(1, sim.getSimulatedPopulation() / 5)
                    * VillageTreasury.GUARD_WAGE * finalDays);
            long propertyTax = (long)(sim.getFarmhouseCount()
                    * VillageTreasury.PROPERTY_TAX_PER_HOUSE * finalDays);
            propertyTax = Math.round(propertyTax
                    * tterrag1112.life_in_the_village.Npc.Laws.LawTaxHooks
                            .propertyTaxMultiplier(village));
            treasury.deposit(taxIncome + propertyTax);
            treasury.withdraw(wageDrain);
            data.putTreasury(treasury);
        });

        // Re-blend the existing rates against themselves so lastSyncTick
        // advances. Pop is unchanged (we have no fresh measurement here).
        // Build the EnumMap copies via putAll — the unmodifiable views
        // returned by sim.productionView() are not EnumMap instances, so
        // the EnumMap(Map) constructor would throw on an empty map (e.g.
        // a brand-new village in its first day).
        EnumMap<ResourceCategory, Float> prodCopy = newCategoryMap();
        prodCopy.putAll(sim.productionView());
        EnumMap<ResourceCategory, Float> consCopy = newCategoryMap();
        consCopy.putAll(sim.consumptionView());
        sim.blendReal(prodCopy, consCopy,
                sim.getSimulatedPopulation(), currentTick);
        data.putSimData(sim);

        System.out.printf("[SimReconcile] %s — %.1f days unloaded, " +
                        "food net=%+.0f, mat net=%+.0f%n",
                village.getName(), daysUnloaded, netFood, netMaterials);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static EnumMap<ResourceCategory, Float> newCategoryMap() {
        return new EnumMap<>(ResourceCategory.class);
    }

    private static void accumulateProfile(BuildingType t,
                                          EnumMap<ResourceCategory, Float> prod,
                                          EnumMap<ResourceCategory, Float> cons) {
        BuildingResourceProfile profile = BuildingResourceProfile.get(t);
        if (profile == null) return;
        for (Map.Entry<ResourceCategory, Float> e : profile.productionPerDay().entrySet()) {
            prod.merge(e.getKey(), e.getValue(), Float::sum);
        }
        for (Map.Entry<ResourceCategory, Float> e : profile.consumptionPerDay().entrySet()) {
            cons.merge(e.getKey(), e.getValue(), Float::sum);
        }
    }

    private static float sumStockpileNutrition(ServerLevel level, Building stockpile) {
        float total = 0f;
        for (var container : BuildingStorageAccess.findInventories(level, stockpile)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                var stack = container.getItem(i);
                if (!stack.isEmpty()) {
                    total += FoodValueHelper.getStackNutrition(stack);
                }
            }
        }
        return total;
    }

    /** Phase 4 doc 29 stub — returns 0 until visitor flux integration
     *  ships. The COIN_INFLUX category exists in the sim today so any
     *  caller that asks for it gets a coherent zero rather than a
     *  fabricated value. */
    private static float estimateVisitorFlux(Village village, VillageSavedData data) {
        return 0f;
    }

    private static boolean isVillageLoaded(ServerLevel level, Village village) {
        var centre = village.getVillageCentre();
        if (centre == null) return false;
        return level.isLoaded(centre);
    }

    private static void materializeFood(ServerLevel level, Village village,
                                        VillageSavedData data, int nutritionUnits) {
        int breadCount = nutritionUnits / 5;
        if (breadCount <= 0) return;
        breadCount = Math.min(breadCount, 128);
        Building stockpile = findStockpile(village, data);
        if (stockpile == null) return;
        BuildingStorageAccess.storeItem(level, stockpile,
                new ItemStack(Items.BREAD, breadCount));
    }

    private static void materializeMaterials(ServerLevel level, Village village,
                                             VillageSavedData data, int units) {
        int logs   = Math.min(units / 2, 128);
        int cobble = Math.min(units / 2, 128);
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
                .findFirst().orElse(null);
    }

    private static int countWageNpcs(ServerLevel level, Village village,
                                     VillageSavedData data) {
        var bounds = village.getBounds(data).orElse(null);
        if (bounds == null) return 0;
        return (int) level.getEntitiesOfClass(
                TownspersonMob.class, bounds.inflate(32),
                mob -> {
                    var p = mob.getProfession();
                    return p == tterrag1112.life_in_the_village.Profession.Profession.GUARD
                            || p == tterrag1112.life_in_the_village.Profession.Profession.STOCKPILE_KEEPER
                            || p == tterrag1112.life_in_the_village.Profession.Profession.INNKEEPER;
                }).size();
    }
}
