package tterrag1112.life_in_the_village.Gui.Map.Kingdom;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.*;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.*;

/**
 * Server-side companion to {@link KingdomMapDataBuilder}. Given a
 * kingdom UUID, computes the viewport and gathers the atlas cells,
 * trade roads, and sea routes needed to render the map.
 *
 * <p>Kept as a static helper so both the sync packet handler and any
 * future command / debug tool can share identical scoping logic.
 *
 * <p>Uses the server-authoritative {@link VillageSavedData} rather than
 * the client caches — villages and roads are the real source here.
 */
public final class KingdomMapScope {

    private KingdomMapScope() {}

    /** Must match {@link KingdomMapDataBuilder#BUFFER_PADDING_CELLS}. */
    public static final int BUFFER_PADDING_CELLS = KingdomMapDataBuilder.BUFFER_PADDING_CELLS;

    /** How far outside a village centre to extend kingdom influence. */
    private static final int INFLUENCE_RADIUS_CELLS = 3;

    public record Result(
            List<AtlasCell> cells,
            List<MapRoadSnapshot> roads,
            List<MapSeaRouteSnapshot> seaRoutes
    ) {}

    /**
     * Gather everything the client needs to render the kingdom map.
     * Returns empty collections if the kingdom is unknown or has no
     * villages.
     */
    public static Result gather(ServerLevel level, UUID kingdomId) {
        VillageSavedData data = VillageSavedData.get(level);
        WorldAtlas atlas = WorldAtlas.get(level);

        Kingdom focus = data.getKingdomById(kingdomId).orElse(null);
        if (focus == null) return empty();

        // Compute the focus kingdom's influence cells and the viewport
        Set<Long> focusCells = influenceCellsFor(focus, data);
        if (focusCells.isEmpty()) return empty();

        int[] bounds = boundsOf(focusCells);
        int minCX = bounds[0] - BUFFER_PADDING_CELLS;
        int minCZ = bounds[1] - BUFFER_PADDING_CELLS;
        int maxCX = bounds[2] + BUFFER_PADDING_CELLS;
        int maxCZ = bounds[3] + BUFFER_PADDING_CELLS;

        // Atlas cells in viewport (only those already sampled — unsampled
        // cells stay unknown on the client and render as ocean)
        List<AtlasCell> cells = new ArrayList<>();
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                AtlasCell c = atlas.getCellByCoord(cx, cz);
                if (c != null) cells.add(c);
            }
        }

        // Viewable villages: focus kingdom + any foreign kingdom overlapping the viewport
        Set<UUID> viewable = new HashSet<>(focus.getVillageIds());
        for (Kingdom other : data.getAllKingdoms()) {
            if (other.getId().equals(kingdomId)) continue;
            Set<Long> otherCells = influenceCellsFor(other, data);
            if (intersectsViewport(otherCells, minCX, minCZ, maxCX, maxCZ)) {
                viewable.addAll(other.getVillageIds());
            }
        }

        // Land routes (graph-backed) and sea routes referenced by any trade
        // route where either endpoint is a viewable village.
        var graph = tterrag1112.life_in_the_village.Networking
                .WorldRoadSavedData.get(level).getGraph();
        List<MapRoadSnapshot> roads = new ArrayList<>();
        // Track C3.3 — sea routes are now SEA-tier RoadEdges. Walk the
        // graph directly; pick edges whose maintainer set intersects a
        // viewable village.
        List<MapSeaRouteSnapshot> seaRoutes = new ArrayList<>();
        for (TradeRoute route : data.getAllTradeRoutes()) {
            if (!viewable.contains(route.getVillageA())
                    && !viewable.contains(route.getVillageB())) continue;
            if (route.hasGraphPath()) {
                var edge = graph.getEdge(route.getEdgeIds().get(0));
                if (edge != null
                        && edge.getTier() == tterrag1112.life_in_the_village
                                .Village.Roads.Graph.RoadEdge.EdgeTier.SEA) {
                    seaRoutes.add(MapSeaRouteSnapshot.fromEdge(edge));
                } else {
                    roads.add(MapRoadSnapshot.fromRoute(route, graph));
                }
            }
        }

        return new Result(cells, roads, seaRoutes);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Server-side version of the fallback bounds provider. Uses the
     * server-authoritative village AABBs via {@link Village#getBounds}.
     * When real kingdom border storage lands, swap this out (same single
     * call site as the client-side provider).
     */
    private static Set<Long> influenceCellsFor(Kingdom kingdom, VillageSavedData data) {
        Set<Long> owned = new HashSet<>();
        for (UUID vid : kingdom.getVillageIds()) {
            Village v = data.getVillageById(vid).orElse(null);
            if (v == null) continue;
            var bounds = v.getBounds(data).orElse(null);
            if (bounds == null) continue;
            int minCX = blockToCell((int) Math.floor(bounds.minX)) - INFLUENCE_RADIUS_CELLS;
            int maxCX = blockToCell((int) Math.ceil(bounds.maxX))  + INFLUENCE_RADIUS_CELLS;
            int minCZ = blockToCell((int) Math.floor(bounds.minZ)) - INFLUENCE_RADIUS_CELLS;
            int maxCZ = blockToCell((int) Math.ceil(bounds.maxZ))  + INFLUENCE_RADIUS_CELLS;
            for (int cx = minCX; cx <= maxCX; cx++) {
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    owned.add(AtlasCell.packKey(cx, cz));
                }
            }
        }
        return owned;
    }

    private static int[] boundsOf(Set<Long> cells) {
        int minCX = Integer.MAX_VALUE, minCZ = Integer.MAX_VALUE;
        int maxCX = Integer.MIN_VALUE, maxCZ = Integer.MIN_VALUE;
        for (long k : cells) {
            int cx = AtlasCell.unpackX(k), cz = AtlasCell.unpackZ(k);
            if (cx < minCX) minCX = cx; if (cz < minCZ) minCZ = cz;
            if (cx > maxCX) maxCX = cx; if (cz > maxCZ) maxCZ = cz;
        }
        return new int[] { minCX, minCZ, maxCX, maxCZ };
    }

    private static boolean intersectsViewport(Set<Long> cells,
                                              int minCX, int minCZ,
                                              int maxCX, int maxCZ) {
        for (long k : cells) {
            int cx = AtlasCell.unpackX(k), cz = AtlasCell.unpackZ(k);
            if (cx >= minCX && cx <= maxCX && cz >= minCZ && cz <= maxCZ) return true;
        }
        return false;
    }

    private static int blockToCell(int block) {
        return block >> AtlasCell.CELL_SHIFT;
    }

    private static Result empty() {
        return new Result(List.of(), List.of(), List.of());
    }
}