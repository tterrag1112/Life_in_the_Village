package tterrag1112.life_in_the_village.Gui.Map.Kingdom;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.*;
import tterrag1112.life_in_the_village.Village.Travel.TravellerSnapshot;
import tterrag1112.life_in_the_village.Village.Travel.TravellerType;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.*;

/**
 * Server-side companion to {@link KingdomMapDataBuilder}. PLAYER-CENTERED:
 * given the requesting player's block position, computes a fixed-radius
 * viewport around it and gathers the atlas cells, trade roads, and sea
 * routes needed to render the map. No kingdom seed — the map is usable
 * anywhere, the point being to visualise village placement around the
 * player while testing kingdoms.
 *
 * <p>Supersedes the prior claim-anchored viewport (the {@code fix-blank-maps}
 * change): that scoped the viewport to one kingdom's territorial claim
 * and returned blank when the player was not in/near a kingdom. The
 * viewport is now a {@link #VIEWPORT_RADIUS_BLOCKS} box around the player,
 * pre-filled so terrain is always present.
 *
 * <p>Uses the server-authoritative {@link VillageSavedData} rather than
 * the client caches — villages and roads are the real source here.
 */
public final class KingdomMapScope {

    private KingdomMapScope() {}

    /**
     * Player-centered viewport radius in blocks for the kingdom map.
     * Tighter than the continent map ({@code ContinentMapScope
     * .VIEWPORT_RADIUS_BLOCKS}) so village placement around the player is
     * legible. 2000 blocks ≈ 31 cells radius (~3900 cells), comfortably
     * inside the 1s fill budget. Must match
     * {@link KingdomMapDataBuilder#VIEWPORT_RADIUS_BLOCKS}.
     */
    public static final int VIEWPORT_RADIUS_BLOCKS =
            KingdomMapDataBuilder.VIEWPORT_RADIUS_BLOCKS;

    /** Synchronous atlas pre-fill budget on map open. Matches ContinentMapScope. */
    private static final long PREFILL_NANOS = 1_000_000_000L;

    public record Result(
            List<AtlasCell> cells,
            List<MapRoadSnapshot> roads,
            List<MapSeaRouteSnapshot> seaRoutes,
            List<TravellerSnapshot> travellers   // Phase 5e
    ) {}

    /**
     * Gather everything the client needs to render the kingdom map,
     * centred on the player block position {@code (playerX, playerZ)}.
     * Always returns terrain cells for the player-centered viewport, so
     * the client can render a non-blank map even when no kingdom is near.
     * Roads/sea routes/travellers are scoped to villages whose centre
     * falls within the viewport.
     */
    public static Result gather(ServerLevel level, int playerX, int playerZ) {
        VillageSavedData data = VillageSavedData.get(level);
        WorldAtlas atlas = WorldAtlas.get(level);

        // Player-centered viewport in cell coords.
        int rCells = (VIEWPORT_RADIUS_BLOCKS >> AtlasCell.CELL_SHIFT) + 1;
        int centreCX = playerX >> AtlasCell.CELL_SHIFT;
        int centreCZ = playerZ >> AtlasCell.CELL_SHIFT;
        int minCX = centreCX - rCells;
        int minCZ = centreCZ - rCells;
        int maxCX = centreCX + rCells;
        int maxCZ = centreCZ + rCells;

        // Pre-fill the atlas across the player-centered viewport before
        // reading. A cell never re-samples once present, so without this
        // the client renders ocean for any cell the worldgen pass did not
        // reach. Bounded synchronous budget — manually-opened screen.
        atlas.ensureRegionFilled(level, playerX, playerZ,
                VIEWPORT_RADIUS_BLOCKS, PREFILL_NANOS);

        // Atlas cells in viewport (now sampled above; any still-absent cell
        // means the fill budget ran out and the client renders it as ocean)
        List<AtlasCell> cells = new ArrayList<>();
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                AtlasCell c = atlas.getCellByCoord(cx, cz);
                if (c != null) cells.add(c);
            }
        }

        // Viewable villages: every village whose anchor cell falls within
        // the player-centered viewport, regardless of kingdom association.
        Set<UUID> viewable = new HashSet<>();
        for (Village v : data.getAllVillages()) {
            var anchor = v.getAnchorPos();
            if (anchor == null) continue;
            int vcx = anchor.getX() >> AtlasCell.CELL_SHIFT;
            int vcz = anchor.getZ() >> AtlasCell.CELL_SHIFT;
            if (vcx >= minCX && vcx <= maxCX && vcz >= minCZ && vcz <= maxCZ) {
                viewable.add(v.getId());
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

        // Religion R3e-3b — resident pilgrims (single-member land travellers).
        // Reuses the land route polyline for the {origin,dest} village pair
        // (destinations are route-connected), so no map-network change is needed.
        for (tterrag1112.life_in_the_village.Village.Travel.Pilgrimage pg
                : tterrag1112.life_in_the_village.Village.Travel.PilgrimageSavedData
                        .get(level).getAllPilgrimages()) {
            if (!viewable.contains(pg.getOriginVillageId())
                    && !viewable.contains(pg.getDestVillageId())) continue;
            out.add(new TravellerSnapshot(
                    pg.groupId(), TravellerType.PILGRIM.ordinal(),
                    pg.getOriginVillageId(), pg.getDestVillageId(),
                    false,
                    (float) pg.getProgress(), pg.isReversed(),
                    1.0f,
                    villageName(data, pg.getOriginVillageId()),
                    villageName(data, pg.getDestVillageId()),
                    ""));
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

}
