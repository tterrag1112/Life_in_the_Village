package tterrag1112.life_in_the_village.Events;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Economy.EdgeTierManager;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;

/**
 * Periodic safety net that reconciles every maintained edge's tier with
 * the current effective tier of its maintainer villages.
 *
 * <p>Runs every 48 000 ticks (~2 in-game days). Under normal circumstances
 * the per-building hooks in {@code Village.checkAndFireTierChangeHook} keep
 * tiers up to date; this system catches any edge cases (command-driven
 * mutations, load-from-save drift, direct list mutations).
 *
 * <p>If this system frequently finds tier mismatches the per-building hooks
 * are not firing correctly — that is worth investigating.
 */
public final class TierReconciliationSystem {

    private TierReconciliationSystem() {}

    /**
     * Scans all villages and reconciles each one's maintained edges.
     * Returns the total number of edges that changed tier.
     */
    public static int runReconciliation(ServerLevel level, VillageSavedData villageData) {
        WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
        WorldRoadGraph graph = roadData.getGraph();

        int totalChanged = 0;
        for (Village village : villageData.getAllVillages()) {
            List<RoadEdge> changed = EdgeTierManager.reconcileEdgesForVillage(
                    village, graph, villageData);
            totalChanged += changed.size();
        }

        if (totalChanged > 0) {
            System.out.println("[TierReconciliation] Periodic scan changed " + totalChanged
                    + " edge tier(s). Check per-building hooks if this is frequent.");
            roadData.markDirty();
        }

        return totalChanged;
    }
}
