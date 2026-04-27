package tterrag1112.life_in_the_village.Village.Simulation;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRouteManager;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Evaluates kingdom-level economic health by aggregating
 * {@link VillageSimData} snapshots, then takes corrective action when
 * needs are unmet.
 *
 * <h3>Phase 4 doc 25 refactor</h3>
 * Generalised from food / material to an arbitrary
 * {@link ResourceCategory}. {@link #findExportPartner} now takes a
 * category parameter; {@link #evaluate} loops the categories the
 * spec calls out (FOOD, BUILDING_MATERIALS, plus any future expansion
 * with a positive {@link #defaultExportThreshold}).
 *
 * <h3>Performance contract</h3>
 * O(V) per call; runs once per in-game day from the dispatcher.
 */
public final class KingdomEconomyEngine {

    /** Per-category surplus thresholds that gate export-partner search.
     *  Values below these are treated as "not enough surplus to spare." */
    private static final Map<ResourceCategory, Float> EXPORT_THRESHOLDS = buildThresholds();
    /** Max distance to search for a trade partner (blocks). */
    private static final double MAX_PARTNER_DISTANCE = 6000.0;

    private KingdomEconomyEngine() {}

    public static void evaluate(ServerLevel level,
                                Kingdom kingdom,
                                VillageSavedData data) {
        List<VillageSimData> sims = collectSimData(kingdom, data);
        if (sims.isEmpty()) return;

        int totalPop = sims.stream().mapToInt(VillageSimData::getSimulatedPopulation).sum();

        StringBuilder log = new StringBuilder();
        log.append("[KingdomEconomy] ").append(kingdom.getName())
                .append(" — pop=").append(totalPop);

        for (Map.Entry<ResourceCategory, Float> e : EXPORT_THRESHOLDS.entrySet()) {
            ResourceCategory cat = e.getKey();
            float totalNet = (float) sims.stream()
                    .mapToDouble(s -> s.net(cat)).sum();
            log.append(", ").append(cat.name().toLowerCase())
                    .append("Net=").append(String.format("%.0f", totalNet));
            if (totalNet < 0f) {
                handleDeficit(level, kingdom, data, cat);
            }
        }
        System.out.println(log);
    }

    // =========================================================================
    // Deficit handling
    // =========================================================================

    private static void handleDeficit(ServerLevel level,
                                      Kingdom kingdom,
                                      VillageSavedData data,
                                      ResourceCategory category) {
        Village hub = findBestTradeHub(kingdom, data);
        if (hub == null) return;
        Village exportVillage = findExportPartner(kingdom, hub, data, category).orElse(null);
        if (exportVillage == null) {
            System.out.println("[KingdomEconomy] " + kingdom.getName()
                    + " " + category.name().toLowerCase()
                    + " deficit — no export partner within range");
            return;
        }
        if (data.getRouteBetween(hub.getId(), exportVillage.getId()).isEmpty()) {
            System.out.println("[KingdomEconomy] " + kingdom.getName()
                    + " requesting trade route to " + exportVillage.getName()
                    + " for " + category.name().toLowerCase());
            TradeRouteManager.establishRoutes(level, hub, data);
        }
    }

    // =========================================================================
    // Partner search
    // =========================================================================

    /**
     * Finds the closest non-kingdom village whose net per-day for
     * {@code category} exceeds the export threshold for that category.
     * Returns empty if no village qualifies within
     * {@link #MAX_PARTNER_DISTANCE}.
     */
    public static Optional<Village> findExportPartner(Kingdom seekingKingdom,
                                                      Village importer,
                                                      VillageSavedData data,
                                                      ResourceCategory category) {
        float threshold = EXPORT_THRESHOLDS.getOrDefault(category, defaultExportThreshold(category));
        return data.getAllVillages().stream()
                .filter(v -> !seekingKingdom.containsVillage(v.getId()))
                .filter(v -> {
                    Optional<VillageSimData> sim = data.getSimData(v.getId());
                    if (sim.isEmpty()) return false;
                    return sim.get().net(category) > threshold;
                })
                .filter(v -> distanceTo(importer, v, data) <= MAX_PARTNER_DISTANCE)
                .min(Comparator.comparingDouble(v -> distanceTo(importer, v, data)));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Map<ResourceCategory, Float> buildThresholds() {
        EnumMap<ResourceCategory, Float> m = new EnumMap<>(ResourceCategory.class);
        m.put(ResourceCategory.FOOD, 200f);
        m.put(ResourceCategory.BUILDING_MATERIALS, 32f);
        return Map.copyOf(m);
    }

    /** Phase 4 doc 26+ may grow this list. For categories the spec
     *  hasn't gated, use a generic fallback so the partner search is
     *  always callable. */
    private static float defaultExportThreshold(ResourceCategory category) {
        if (category == null) return Float.POSITIVE_INFINITY;
        if (!category.isPhysical()) return 5f; // COIN_INFLUX
        return 8f;
    }

    private static double distanceTo(Village a, Village b, VillageSavedData data) {
        var aB = a.getBounds(data);
        var bB = b.getBounds(data);
        if (aB.isEmpty() || bB.isEmpty()) return Double.MAX_VALUE;
        double dx = aB.get().getCenter().x - bB.get().getCenter().x;
        double dz = aB.get().getCenter().z - bB.get().getCenter().z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static List<VillageSimData> collectSimData(Kingdom kingdom,
                                                       VillageSavedData data) {
        return kingdom.getVillageIds().stream()
                .map(data::getSimData)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    /** Returns the village most central to the kingdom (closest to mean position). */
    private static Village findBestTradeHub(Kingdom kingdom, VillageSavedData data) {
        List<Village> villages = kingdom.getVillageIds().stream()
                .map(data::getVillageById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
        if (villages.isEmpty()) return null;

        double meanX = villages.stream()
                .mapToDouble(v -> v.getBounds(data).map(b -> b.getCenter().x).orElse(0.0))
                .average().orElse(0);
        double meanZ = villages.stream()
                .mapToDouble(v -> v.getBounds(data).map(b -> b.getCenter().z).orElse(0.0))
                .average().orElse(0);

        return villages.stream()
                .min(Comparator.comparingDouble(v -> {
                    var bounds = v.getBounds(data);
                    if (bounds.isEmpty()) return Double.MAX_VALUE;
                    double dx = bounds.get().getCenter().x - meanX;
                    double dz = bounds.get().getCenter().z - meanZ;
                    return dx * dx + dz * dz;
                }))
                .orElse(null);
    }
}
