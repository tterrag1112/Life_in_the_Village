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
 * Server-side discovery of the continent a kingdom sits on, plus
 * everything drawable on it.
 *
 * <h3>Continent discovery</h3>
 * Flood-fill from the seed kingdom's cells through 4-neighbour land
 * cells (not ocean, not unsampled) until the fill closes or hits
 * {@link #MAX_CONTINENT_CELLS}. Ocean acts as a hard border;
 * unsampled cells also block traversal so the continent can't
 * silently leak through unfilled regions.
 *
 * <h3>Pre-fill</h3>
 * Before flood-fill, we call {@link WorldAtlas#ensureRegionFilled}
 * around the seed with a generous budget so the atlas has real data
 * to work with. If the budget is exhausted the continent is returned
 * partial — the player can re-open to fill more.
 */
public final class ContinentMapScope {

    private ContinentMapScope() {}

    /** Upper bound on continent cells — ~40M blocks of land coverage. */
    public static final int MAX_CONTINENT_CELLS = 10_000;

    /** Padding added around the continent's cell bounds for context. */
    public static final int BUFFER_PADDING_CELLS = 4;

    /** Radius around the seed kingdom to pre-fill before flood-filling. */
    private static final int PREFILL_RADIUS_BLOCKS = 6_000;

    /** Synchronous time budget for pre-fill. 100ms is noticeable but tolerable for a dev/inspection screen. */
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

    /** Thin snapshot of another kingdom's claim for client-side rendering. */
    public record ForeignClaimSnapshot(UUID kingdomId, List<Long> cellKeys) {}

    public static Result gather(ServerLevel level, UUID seedKingdomId) {
        VillageSavedData data = VillageSavedData.get(level);
        WorldAtlas atlas = WorldAtlas.get(level);

        Kingdom seed = data.getKingdomById(seedKingdomId).orElse(null);
        if (seed == null) return empty(seedKingdomId);

        // ── 1. Pick a seed block position for pre-fill ───────────────────────
        var seedPos = seed.getTerritorialClaim()
                .flatMap(c -> c.claimedCellKeys().stream().findFirst())
                .map(key -> new int[] {
                        (AtlasCell.unpackX(key) << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF,
                        (AtlasCell.unpackZ(key) << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF
                })
                .orElse(null);
        if (seedPos == null) return empty(seedKingdomId);

        // ── 2. Pre-fill atlas around the seed ────────────────────────────────
        boolean prefillComplete = atlas.ensureRegionFilled(
                level, seedPos[0], seedPos[1],
                PREFILL_RADIUS_BLOCKS, PREFILL_NANOS);

        // ── 3. Flood-fill the continent ──────────────────────────────────────
        Set<Long> continent = floodFillContinent(atlas, seed);
        if (continent.isEmpty()) return empty(seedKingdomId);

        int[] bounds = boundsOf(continent);
        int minCX = bounds[0] - BUFFER_PADDING_CELLS;
        int minCZ = bounds[1] - BUFFER_PADDING_CELLS;
        int maxCX = bounds[2] + BUFFER_PADDING_CELLS;
        int maxCZ = bounds[3] + BUFFER_PADDING_CELLS;

        // ── 4. Gather atlas cells in viewport ────────────────────────────────
        List<AtlasCell> cells = new ArrayList<>();
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                AtlasCell c = atlas.getCellByCoord(cx, cz);
                if (c != null) cells.add(c);
            }
        }

        // ── 5. Kingdoms whose claim intersects the continent ─────────────────
        List<ForeignClaimSnapshot> claims = new ArrayList<>();
        Set<UUID> viewableVillages = new HashSet<>();
        for (Kingdom k : data.getAllKingdoms()) {
            KingdomClaim claim = k.getTerritorialClaim().orElse(null);
            if (claim == null) continue;
            List<Long> keys = claim.claimedCellKeys();
            boolean overlaps = false;
            for (Long key : keys) {
                if (continent.contains(key)) { overlaps = true; break; }
            }
            if (!overlaps) continue;
            claims.add(new ForeignClaimSnapshot(k.getId(), new ArrayList<>(keys)));
            viewableVillages.addAll(k.getVillageIds());
        }

        // Also include any villages whose anchor lies on the continent, even if
        // their kingdom's claim doesn't technically overlap (edge case for
        // villages at the border between kingdom claim and continent land).
        for (Village v : data.getAllVillages()) {
            var anchor = v.getAnchorPos();
            if (anchor == null) continue;
            long key = AtlasCell.packKey(
                    anchor.getX() >> AtlasCell.CELL_SHIFT,
                    anchor.getZ() >> AtlasCell.CELL_SHIFT);
            if (continent.contains(key)) viewableVillages.add(v.getId());
        }

        // ── 6. Roads + sea routes connecting viewable villages ───────────────
        Set<UUID> roadIds = new HashSet<>();
        Set<UUID> seaIds  = new HashSet<>();
        for (TradeRoute route : data.getAllTradeRoutes()) {
            if (!viewableVillages.contains(route.getVillageA())
                    && !viewableVillages.contains(route.getVillageB())) continue;
            UUID connId = route.getConnectionId();
            if (data.getRoadById(connId).isPresent())       roadIds.add(connId);
            else if (data.getSeaRouteById(connId).isPresent()) seaIds.add(connId);
        }
        List<MapRoadSnapshot> roads = new ArrayList<>(roadIds.size());
        for (UUID id : roadIds)
            data.getRoadById(id).ifPresent(r -> roads.add(MapRoadSnapshot.fromRoad(r)));
        List<MapSeaRouteSnapshot> seaRoutes = new ArrayList<>(seaIds.size());
        for (UUID id : seaIds)
            data.getSeaRouteById(id).ifPresent(s -> seaRoutes.add(MapSeaRouteSnapshot.fromSeaRoute(s)));

        return new Result(seedKingdomId, cells,
                new ArrayList<>(continent), claims,
                roads, seaRoutes, prefillComplete);
    }

    // =========================================================================
    // Flood-fill
    // =========================================================================

    private static Set<Long> floodFillContinent(WorldAtlas atlas, Kingdom seed) {
        Set<Long> out = new HashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();

        // Seed queue with every claim cell the kingdom owns — if the claim
        // spans two joined landmasses we want both.
        seed.getTerritorialClaim().ifPresent(claim -> {
            for (Long key : claim.claimedCellKeys()) {
                if (isLandCell(atlas, key) && out.add(key)) queue.add(key);
            }
        });

        while (!queue.isEmpty() && out.size() < MAX_CONTINENT_CELLS) {
            long key = queue.poll();
            int cx = AtlasCell.unpackX(key);
            int cz = AtlasCell.unpackZ(key);

            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : dirs) {
                long nKey = AtlasCell.packKey(cx + d[0], cz + d[1]);
                if (out.contains(nKey)) continue;
                if (!isLandCell(atlas, nKey)) continue;
                out.add(nKey);
                queue.add(nKey);
                if (out.size() >= MAX_CONTINENT_CELLS) break;
            }
        }
        return out;
    }

    /** Land = sampled AND not ocean. Unsampled cells block flood-fill. */
    private static boolean isLandCell(WorldAtlas atlas, long key) {
        AtlasCell c = atlas.getCellByCoord(AtlasCell.unpackX(key), AtlasCell.unpackZ(key));
        return c != null && !c.has(AtlasCell.FLAG_HAS_OCEAN);
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

    private static Result empty(UUID seedId) {
        return new Result(seedId, List.of(), List.of(), List.of(),
                List.of(), List.of(), true);
    }
}