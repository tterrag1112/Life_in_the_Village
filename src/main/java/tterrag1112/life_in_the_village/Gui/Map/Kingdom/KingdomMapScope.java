package tterrag1112.life_in_the_village.Gui.Map.Kingdom;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.*;
import tterrag1112.life_in_the_village.Village.Travel.TravellerSnapshot;
import tterrag1112.life_in_the_village.Village.Travel.TravellerType;
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
            List<MapSeaRouteSnapshot> seaRoutes,
            List<TravellerSnapshot> travellers   // Phase 5e
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

        // Phase 5e — in-transit travellers (land + boat caravans) whose
        // origin or destination is a viewable village. Matched client-side to
        // a route by the village pair, so only ids/progress/names are sent.
        List<TravellerSnapshot> travellers = gatherTravellers(level, data, viewable);

        return new Result(cells, roads, seaRoutes, travellers);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Phase 5e — snapshots every land/boat caravan touching the viewport.
     * Generic intent: any future {@code TravellingGroup} source plugs in
     * here with its own {@link TravellerType} mapping.
     */
    private static List<TravellerSnapshot> gatherTravellers(
            ServerLevel level, VillageSavedData data, Set<UUID> viewable) {
        List<TravellerSnapshot> out = new ArrayList<>();

        for (Caravan c : CaravanSavedData.get(level).getAllCaravans()) {
            if (!viewable.contains(c.getOriginVillageId())
                    && !viewable.contains(c.getDestVillageId())) continue;
            TravellerType type = c.getKind() == Caravan.CaravanKind.PROCUREMENT
                    ? TravellerType.CARAVAN_PROCUREMENT : TravellerType.CARAVAN_EXPORT;
            // Procurement runs out empty and shop on arrival, so the
            // shopping list is the meaningful cargo there; goods otherwise.
            List<ItemStack> cargo = c.getKind() == Caravan.CaravanKind.PROCUREMENT
                    ? c.getShoppingList() : c.getGoods();
            out.add(new TravellerSnapshot(
                    c.groupId(), type.ordinal(),
                    c.getOriginVillageId(), c.getDestVillageId(),
                    false,
                    (float) c.getProgress(), c.isReversed(),
                    (float) c.getSpeedMultiplier(level, data),
                    villageName(data, c.getOriginVillageId()),
                    villageName(data, c.getDestVillageId()),
                    summarizeCargo(cargo)));
        }

        for (BoatCaravan b : BoatCaravanSavedData.get(level).getAllBoatCaravans()) {
            if (!viewable.contains(b.getOriginVillageId())
                    && !viewable.contains(b.getDestVillageId())) continue;
            out.add(new TravellerSnapshot(
                    b.groupId(), TravellerType.BOAT.ordinal(),
                    b.getOriginVillageId(), b.getDestVillageId(),
                    true,
                    (float) b.getProgress(), b.isReversed(),
                    (float) b.getSpeedMultiplier(level, data),
                    villageName(data, b.getOriginVillageId()),
                    villageName(data, b.getDestVillageId()),
                    summarizeCargo(b.getGoods())));
        }
        return out;
    }

    private static String villageName(VillageSavedData data, UUID villageId) {
        return data.getVillageById(villageId).map(Village::getName).orElse("Unknown");
    }

    /** Short cargo summary for the tooltip: top items + overflow count. */
    private static String summarizeCargo(List<ItemStack> goods) {
        if (goods == null || goods.isEmpty()) return "Empty";
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (ItemStack st : goods) {
            if (st == null || st.isEmpty()) continue;
            if (shown > 0) sb.append(", ");
            sb.append(st.getCount()).append("× ").append(st.getHoverName().getString());
            if (++shown >= 3) break;
        }
        if (shown == 0) return "Empty";
        int remaining = goods.size() - shown;
        if (remaining > 0) sb.append(" +").append(remaining);
        return sb.toString();
    }

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
        return new Result(List.of(), List.of(), List.of(), List.of());
    }
}