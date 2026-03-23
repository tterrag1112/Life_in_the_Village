// src/main/java/tterrag1112/life_in_the_village/Village/VillageAgingManager.java
package tterrag1112.life_in_the_village.Village.Decoration;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingCondition;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;


public class VillageAgingManager {

    /** How often to check for level-up eligibility (ticks). */
    private static final long CHECK_INTERVAL   = 24000L * 3; // 3 in-game days
    /** Minimum ticks between consecutive level-ups. */
    private static final long LEVELUP_COOLDOWN = 24000L * 7; // 7 in-game days

    // Per-level thresholds [index = level - 1, so index 0 = level 1 → 2]
    private static final int[] POPULATION_THRESHOLDS = {
            2, 3, 5, 6, 8, 10, 12, 15, 18, Integer.MAX_VALUE
    };
    private static final long[] WEALTH_THRESHOLDS_BRONZE = {
            500L, 1000L, 2500L, 5000L, 10000L,
            20000L, 40000L, 75000L, 150000L, Long.MAX_VALUE
    };
    /** Required building tier (ordinal) to reach next level. */
    private static final int[] TIER_REQUIREMENTS = {
            0, 0, 1, 1, 2, 2, 3, 3, 3, 3
    };

    private static final long DEGRADE_INTERVAL = 24000L * 7;


    // =========================================================================
    // Tick
    // =========================================================================

    public static void tick(ServerLevel level,
                            VillageSavedData data,
                            long currentTick) {
        if (currentTick % CHECK_INTERVAL != 0) return;

        for (Village village : data.getAllVillages()) {
            evaluateAging(village, level, data, currentTick);
        }
    }

    // =========================================================================
    // Level-up evaluation
    // =========================================================================

    private static void evaluateAging(Village village,
                                      ServerLevel level,
                                      VillageSavedData data,
                                      long currentTick) {
        int currentLevel = village.getCurrentLevel();
        if (currentLevel >= 10) return; // already max

        // ── Cooldown check ────────────────────────────────────────────────────
        // lastNeedsUpdate is repurposed as a level-up timestamp here since
        // the needs system already updates it regularly — use a dedicated
        // field instead.
        long lastLevelUp = village.getLastLevelUpTick();
        if (currentTick - lastLevelUp < LEVELUP_COOLDOWN) return;

        tickBuildingDecay(village, data, currentTick);


        // ── Condition checks ──────────────────────────────────────────────────
        int  thresholdIdx  = Math.min(currentLevel - 1,
                POPULATION_THRESHOLDS.length - 1);
        int  popRequired   = POPULATION_THRESHOLDS[thresholdIdx];
        long wealthRequired= WEALTH_THRESHOLDS_BRONZE[thresholdIdx];
        int  tierRequired  = TIER_REQUIREMENTS[thresholdIdx];

        // Population
        int population = countPopulation(village, level, data);
        if (population < popRequired) return;

        // Wealth (village treasury + stockpile)
        long wealth = village.getTreasuryBronze()
                + getStockpileWealth(village, level, data);
        if (wealth < wealthRequired) return;

        // Building tier
        int highestTier = getHighestBuildingTier(village, data);
        if (highestTier < tierRequired) return;

        // ── Level up ──────────────────────────────────────────────────────────
        village.setCurrentLevel(currentLevel + 1);
        village.setLastLevelUpTick(currentTick);
        data.setDirty();

        System.out.println("VillageAgingManager: '"
                + village.getName() + "' levelled up to "
                + village.getCurrentLevel()
                + " (pop=" + population
                + ", wealth=" + wealth + ")");

        // Record in kingdom history
        /*data.getKingdomForVillage(village.getId()).ifPresent(k -> {
            k.getHistory().recordEvent(HistoryTextGenerator.villageLevelUp(
                                    village.getName(),
                                    village.getCurrentLevel(),
                                    currentTick),
                    k.getName(),
                    k.getRulerName(level));
            data.setDirty();
        });

         */
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static int countPopulation(Village village,
                                       ServerLevel level,
                                       VillageSavedData data) {
        return (int) level.getEntitiesOfClass(
                tterrag1112.life_in_the_village.Entities.custom
                        .TownspersonMob.class,
                village.getBounds(data)
                        .map(b -> b.inflate(32))
                        .orElse(new net.minecraft.world.phys
                                .AABB(0, 0, 0, 0, 0, 0)),
                npc -> npc.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false)
        ).size();
    }

    private static long getStockpileWealth(Village village,
                                           ServerLevel level,
                                           VillageSavedData data) {
        return village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() == BuildingType.STOCKPILE)
                .mapToLong(stockpile -> {
                    long total = 0;
                    for (var inv : BuildingStorageAccess
                            .findInventories(level, stockpile)) {
                        for (int i = 0; i < inv.getContainerSize(); i++) {
                            var stack = inv.getItem(i);
                            total += CurrencyValue.valuePerCoin(
                                    stack.getItem()) * stack.getCount();
                        }
                    }
                    return total;
                })
                .sum();
    }

    private static int getHighestBuildingTier(Village village,
                                              VillageSavedData data) {
        return village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .mapToInt(b -> {
                    var def = tterrag1112.life_in_the_village.Village
                            .Buildings.BuildingRegistry.get(b.getType());
                    return def.map(d -> d.minTier().ordinal()).orElse(0);
                })
                .max()
                .orElse(0);
    }
    private static void tickBuildingDecay(Village village,
                                          VillageSavedData data,
                                          long currentTick) {
        village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                // Never degrade NEW — VillageWeathering advances it to WEATHERED
                .filter(b -> b.getCondition() != BuildingCondition.NEW)
                // Only degrade if we haven't recently advanced it to MAINTAINED
                .filter(b -> b.getCondition() != BuildingCondition.MAINTAINED
                        || (currentTick % (DEGRADE_INTERVAL * 4) == 0)) // MAINTAINED lasts 4x longer
                .forEach(b -> {
                    if (currentTick % DEGRADE_INTERVAL == 0) {
                        BuildingCondition next = b.getCondition().degrade();
                        if (next != b.getCondition()) {
                            b.setCondition(next);
                            data.markDirty();
                        }
                    }
                });
    }
}