// src/main/java/tterrag1112/life_in_the_village/Kingdom/KingdomEconomyEngine.java
package tterrag1112.life_in_the_village.Village.Simulation;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRouteManager;
import tterrag1112.life_in_the_village.Village.Simulation.VillageSimData;
import tterrag1112.life_in_the_village.Village.Simulation.VillageSimEngine;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Evaluates the economic health of a kingdom by aggregating
 * {@link VillageSimData} snapshots, then takes corrective action when needs
 * are not met.
 *
 * <h3>What it does</h3>
 * <ol>
 *   <li>Aggregates food and material net rates across all villages.</li>
 *   <li>If the kingdom has a food deficit, requests inter-kingdom trade with
 *       the nearest kingdom that has a surplus (via {@link TradeRouteManager}).</li>
 *   <li>If the kingdom has a material deficit, attempts the same for
 *       materials.</li>
 *   <li>Logs a summary once per in-game day so operators can see the economy
 *       without needing in-game UI.</li>
 * </ol>
 *
 * <h3>Performance contract</h3>
 * This engine is called once per in-game day from {@code ServerTickDispatcher}.
 * It reads only from {@code VillageSavedData} (no chunk queries) and makes at
 * most two {@link TradeRouteManager} calls per kingdom per day. It is O(V) in
 * the number of villages and O(K) in the number of kingdoms.
 */
public final class KingdomEconomyEngine {

    /** Minimum food surplus (nutrition/day) before a kingdom offers food export. */
    private static final float EXPORT_THRESHOLD_FOOD  = 200f;
    /** Minimum material surplus before a kingdom offers material export. */
    private static final float EXPORT_THRESHOLD_MATS  = 32f;
    /** Max distance to search for a trade partner (blocks). */
    private static final double MAX_PARTNER_DISTANCE  = 6000.0;

    private KingdomEconomyEngine() {}

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Evaluates and acts on the kingdom's economic state.
     * Call once per in-game day from {@code ServerTickDispatcher}.
     */
    public static void evaluate(ServerLevel level,
                                Kingdom kingdom,
                                VillageSavedData data) {
        List<VillageSimData> simSnapshots = collectSimData(kingdom, data);
        if (simSnapshots.isEmpty()) return;

        float totalFoodNet  = (float) simSnapshots.stream()
                .mapToDouble(VillageSimData::foodNetPerDay).sum();
        float totalMatNet   = (float) simSnapshots.stream()
                .mapToDouble(VillageSimData::materialNetPerDay).sum();
        int   totalPop      = simSnapshots.stream()
                .mapToInt(VillageSimData::getSimulatedPopulation).sum();

        System.out.printf("[KingdomEconomy] %s — pop=%d, foodNet=%.0f/day, matNet=%.0f/day%n",
                kingdom.getName(), totalPop, totalFoodNet, totalMatNet);

        // ── Food deficit — seek import trade ─────────────────────────────────
        if (totalFoodNet < 0) {
            handleDeficit(level, kingdom, data, simSnapshots, true);
        }

        // ── Material deficit — seek import trade ──────────────────────────────
        if (totalMatNet < 0) {
            handleDeficit(level, kingdom, data, simSnapshots, false);
        }
    }

    // =========================================================================
    // Deficit handling
    // =========================================================================

    private static void handleDeficit(ServerLevel level,
                                      Kingdom kingdom,
                                      VillageSavedData data,
                                      List<VillageSimData> ownSims,
                                      boolean isFood) {
        // Find the kingdom's most central village to use as the trade hub
        Village hub = findBestTradeHub(kingdom, data);
        if (hub == null) return;

        // Find another kingdom or standalone village with a surplus
        Village exportVillage = findExportPartner(
                level, kingdom, hub, data, isFood);

        if (exportVillage == null) {
            System.out.println("[KingdomEconomy] " + kingdom.getName()
                    + (isFood ? " food" : " material")
                    + " deficit — no export partner found within range");
            return;
        }

        // Establish a trade route if one doesn't exist yet
        if (data.getRouteBetween(hub.getId(), exportVillage.getId()).isEmpty()) {
            System.out.println("[KingdomEconomy] " + kingdom.getName()
                    + " requesting trade route to " + exportVillage.getName()
                    + " for " + (isFood ? "food" : "materials"));
            TradeRouteManager.establishRoutes(level, hub, data);
        }
    }

    // =========================================================================
    // Partner search
    // =========================================================================

    private static Village findExportPartner(ServerLevel level,
                                             Kingdom seekingKingdom,
                                             Village hub,
                                             VillageSavedData data,
                                             boolean isFood) {
        // Candidate: any village NOT in this kingdom with a surplus
        return data.getAllVillages().stream()
                .filter(v -> !seekingKingdom.containsVillage(v.getId()))
                .filter(v -> {
                    Optional<VillageSimData> sim = data.getSimData(v.getId());
                    if (sim.isEmpty()) return false;
                    return isFood
                            ? sim.get().foodNetPerDay()     > EXPORT_THRESHOLD_FOOD
                            : sim.get().materialNetPerDay() > EXPORT_THRESHOLD_MATS;
                })
                .filter(v -> {
                    // Distance check using village centres
                    var hubBounds = hub.getBounds(data);
                    var vBounds   = v.getBounds(data);
                    if (hubBounds.isEmpty() || vBounds.isEmpty()) return false;
                    double dx = hubBounds.get().getCenter().x - vBounds.get().getCenter().x;
                    double dz = hubBounds.get().getCenter().z - vBounds.get().getCenter().z;
                    return Math.sqrt(dx*dx + dz*dz) <= MAX_PARTNER_DISTANCE;
                })
                // Prefer the closest surplus village
                .min(Comparator.comparingDouble(v -> {
                    var hubBounds = hub.getBounds(data);
                    var vBounds   = v.getBounds(data);
                    if (hubBounds.isEmpty() || vBounds.isEmpty()) return Double.MAX_VALUE;
                    double dx = hubBounds.get().getCenter().x - vBounds.get().getCenter().x;
                    double dz = hubBounds.get().getCenter().z - vBounds.get().getCenter().z;
                    return dx*dx + dz*dz;
                }))
                .orElse(null);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static List<VillageSimData> collectSimData(Kingdom kingdom,
                                                       VillageSavedData data) {
        return kingdom.getVillageIds().stream()
                .map(data::getSimData)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    /**
     * Finds the village most central to the kingdom (closest to mean position).
     */
    private static Village findBestTradeHub(Kingdom kingdom, VillageSavedData data) {
        List<Village> villages = kingdom.getVillageIds().stream()
                .map(data::getVillageById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
        if (villages.isEmpty()) return null;

        // Mean position
        double meanX = villages.stream()
                .mapToDouble(v -> v.getBounds(data)
                        .map(b -> b.getCenter().x).orElse(0.0))
                .average().orElse(0);
        double meanZ = villages.stream()
                .mapToDouble(v -> v.getBounds(data)
                        .map(b -> b.getCenter().z).orElse(0.0))
                .average().orElse(0);

        return villages.stream()
                .min(Comparator.comparingDouble(v -> {
                    var bounds = v.getBounds(data);
                    if (bounds.isEmpty()) return Double.MAX_VALUE;
                    double dx = bounds.get().getCenter().x - meanX;
                    double dz = bounds.get().getCenter().z - meanZ;
                    return dx*dx + dz*dz;
                }))
                .orElse(null);
    }
}