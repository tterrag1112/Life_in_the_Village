package tterrag1112.life_in_the_village.Gui.Map.Kingdom;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomClaim;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.*;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.*;

/**
 * Server-side gather for the PLAYER-CENTERED continent map. Samples
 * terrain in a fixed radius around the player and collects every kingdom
 * claim, village, and unrealized settlement charter inside that radius —
 * no kingdom seed, no flood-fill.
 *
 * <p>Supersedes the prior seed-kingdom flood-fill discovery (the {@code
 * fix-blank-maps} change): that picked one kingdom's claim cell as a
 * seed, flood-filled the connected landmass, and returned blank when the
 * player had no kingdom. The viewport is now a {@link
 * #VIEWPORT_RADIUS_BLOCKS} box around the player, pre-filled so terrain
 * is always present. {@code continentCells} now means simply "every
 * sampled cell inside the player-centered viewport" — the client uses it
 * only as an in-view membership test for villages/charters.
 */
public final class ContinentMapScope {

    private ContinentMapScope() {}

    /** Padding added around the viewport cell bounds for context. */
    public static final int BUFFER_PADDING_CELLS = 4;

    /**
     * Player-centered viewport radius in blocks for the continent map.
     * Larger than the kingdom map ({@code KingdomMapScope
     * .VIEWPORT_RADIUS_BLOCKS}) to show a wider area. 6000 blocks ≈ 94
     * cells radius (~35.7k cells); the pre-fill budget (1s) bounds the
     * cells actually sampled per open — re-open/refresh fills more if the
     * budget runs out on the first pass. Matches the prior continent
     * pre-fill radius so per-open sampling cost is no worse than before.
     */
    public static final int VIEWPORT_RADIUS_BLOCKS = 6_000;

    /** Synchronous time budget for pre-fill. */
    private static final long PREFILL_NANOS = 1_000_000_000L;

    public record Result(
            UUID seedKingdomId,
            List<AtlasCell> cells,
            List<Long> continentCells,
            List<ForeignClaimSnapshot> claims,
            List<MapRoadSnapshot> roads,
            List<MapSeaRouteSnapshot> seaRoutes,
            boolean prefillComplete
    ) {}

    /** Thin snapshot of a kingdom's in-view claim for client-side rendering. */
    public record ForeignClaimSnapshot(UUID kingdomId, List<Long> cellKeys) {}

    public static Result gather(ServerLevel level, int playerX, int playerZ) {
        VillageSavedData data = VillageSavedData.get(level);
        WorldAtlas atlas = WorldAtlas.get(level);

        // ── 1. Pre-fill atlas around the player ──────────────────────────────
        boolean prefillComplete = atlas.ensureRegionFilled(
                level, playerX, playerZ,
                VIEWPORT_RADIUS_BLOCKS, PREFILL_NANOS);

        // ── 2. Player-centered viewport (cell coords) ────────────────────────
        int rCells = (VIEWPORT_RADIUS_BLOCKS >> AtlasCell.CELL_SHIFT) + 1;
        int centreCX = playerX >> AtlasCell.CELL_SHIFT;
        int centreCZ = playerZ >> AtlasCell.CELL_SHIFT;
        int minCX = centreCX - rCells - BUFFER_PADDING_CELLS;
        int minCZ = centreCZ - rCells - BUFFER_PADDING_CELLS;
        int maxCX = centreCX + rCells + BUFFER_PADDING_CELLS;
        int maxCZ = centreCZ + rCells + BUFFER_PADDING_CELLS;

        // ── 3. Gather sampled atlas cells in viewport; the set of present
        //       cell keys doubles as the "continentCells" in-view membership
        //       test the client uses for villages/charters. ─────────────────
        List<AtlasCell> cells = new ArrayList<>();
        List<Long> viewCells = new ArrayList<>();
        Set<Long> viewCellSet = new HashSet<>();
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                AtlasCell c = atlas.getCellByCoord(cx, cz);
                if (c != null) {
                    cells.add(c);
                    long key = AtlasCell.packKey(cx, cz);
                    viewCells.add(key);
                    viewCellSet.add(key);
                }
            }
        }

        // ── 4. Every kingdom whose claim intersects the viewport ─────────────
        List<ForeignClaimSnapshot> claims = new ArrayList<>();
        Set<UUID> viewableVillages = new HashSet<>();
        for (Kingdom k : data.getAllKingdoms()) {
            KingdomClaim claim = k.getTerritorialClaim().orElse(null);
            if (claim == null) continue;
            List<Long> inView = new ArrayList<>();
            for (Long key : claim.claimedCellKeys()) {
                if (viewCellSet.contains(key)) inView.add(key);
            }
            if (inView.isEmpty()) continue;
            claims.add(new ForeignClaimSnapshot(k.getId(), inView));
            viewableVillages.addAll(k.getVillageIds());
        }

        // Also include any village whose anchor lies in the viewport, even if
        // its kingdom's claim doesn't intersect (or it has no kingdom).
        for (Village v : data.getAllVillages()) {
            var anchor = v.getAnchorPos();
            if (anchor == null) continue;
            long key = AtlasCell.packKey(
                    anchor.getX() >> AtlasCell.CELL_SHIFT,
                    anchor.getZ() >> AtlasCell.CELL_SHIFT);
            if (viewCellSet.contains(key)) viewableVillages.add(v.getId());
        }

        // ── 5. Roads + sea routes connecting viewable villages ───────────────
        var graph = tterrag1112.life_in_the_village.Networking
                .WorldRoadSavedData.get(level).getGraph();
        List<MapRoadSnapshot> roads = new ArrayList<>();
        // Track C3.3 — sea routes derived from SEA-tier RoadEdges.
        List<MapSeaRouteSnapshot> seaRoutes = new ArrayList<>();
        for (TradeRoute route : data.getAllTradeRoutes()) {
            if (!viewableVillages.contains(route.getVillageA())
                    && !viewableVillages.contains(route.getVillageB())) continue;
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

        // seedKingdomId echoes back the screen-identity UUID the request
        // carried (matched in the sync handler); it no longer scopes data.
        return new Result(null, cells,
                viewCells, claims,
                roads, seaRoutes, prefillComplete);
    }
}
