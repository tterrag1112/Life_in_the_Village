package tterrag1112.life_in_the_village.Gui.Map.Kingdom;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Economy.Trade.*;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.ClientAtlasCache;

import java.util.*;

/**
 * Builds a {@link KingdomMapData} snapshot on GUI open from the
 * client-side caches. Independent of chunk loading.
 */
public final class KingdomMapDataBuilder {

    /** Extra cells of padding around the focus kingdom for context. */
    public static final int BUFFER_PADDING_CELLS = 4;

    private KingdomMapDataBuilder() {}

    public static Optional<KingdomMapData> build(UUID focusKingdomId,
                                                 KingdomBoundsProvider boundsProvider) {
        Kingdom focus = Kingdom.ClientKingdomCache.getById(focusKingdomId).orElse(null);
        if (focus == null) return Optional.empty();

        // ── 1. Focus-owned cells + viewport bounds ───────────────────────────
        Set<Long> focusCells = boundsProvider.getOwnedCells(focus);
        if (focusCells.isEmpty()) {
            // Single-cell fallback so the map still renders something useful
            return Optional.empty();
        }

        int minCX = Integer.MAX_VALUE, minCZ = Integer.MAX_VALUE;
        int maxCX = Integer.MIN_VALUE, maxCZ = Integer.MIN_VALUE;
        for (long k : focusCells) {
            int cx = AtlasCell.unpackX(k), cz = AtlasCell.unpackZ(k);
            if (cx < minCX) minCX = cx; if (cz < minCZ) minCZ = cz;
            if (cx > maxCX) maxCX = cx; if (cz > maxCZ) maxCZ = cz;
        }
        minCX -= BUFFER_PADDING_CELLS; minCZ -= BUFFER_PADDING_CELLS;
        maxCX += BUFFER_PADDING_CELLS; maxCZ += BUFFER_PADDING_CELLS;

        // ── 2. Terrain grid in viewport ──────────────────────────────────────
        Map<Long, AtlasCell> terrain = new HashMap<>();
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                ClientAtlasCache.get(cx, cz).ifPresent(c -> terrain.put(c.key(), c));
            }
        }

        // ── 3. Foreign kingdoms overlapping viewport ─────────────────────────
        List<KingdomMapData.ForeignKingdom> foreign = new ArrayList<>();
        for (Kingdom other : Kingdom.ClientKingdomCache.getKingdoms()) {
            if (other.getId().equals(focusKingdomId)) continue;
            Set<Long> cells = boundsProvider.getOwnedCells(other);
            Set<Long> inView = new HashSet<>();
            for (long k : cells) {
                int cx = AtlasCell.unpackX(k), cz = AtlasCell.unpackZ(k);
                if (cx >= minCX && cx <= maxCX && cz >= minCZ && cz <= maxCZ) {
                    inView.add(k);
                }
            }
            if (!inView.isEmpty()) {
                foreign.add(new KingdomMapData.ForeignKingdom(
                        other.getId(), other.getName(), inView));
            }
        }

        // ── 4. Villages ──────────────────────────────────────────────────────
        Map<UUID, UUID> villageToKingdom = new HashMap<>();
        for (Kingdom k : Kingdom.ClientKingdomCache.getKingdoms()) {
            for (UUID vid : k.getVillageIds()) villageToKingdom.put(vid, k.getId());
        }

        Set<UUID> viewableVillageIds = new HashSet<>(focus.getVillageIds());
        for (KingdomMapData.ForeignKingdom fk : foreign) {
            Kingdom.ClientKingdomCache.getById(fk.id())
                    .ifPresent(k -> viewableVillageIds.addAll(k.getVillageIds()));
        }

        List<KingdomMapData.VillageMarker> villages = new ArrayList<>();
        for (Village v : Building.ClientBuildingCache.getVillages()) {
            if (!viewableVillageIds.contains(v.getId())) continue;
            BlockPos pos = approximateVillageCenter(v);
            if (pos == null) continue;
            int cx = pos.getX() >> AtlasCell.CELL_SHIFT;
            int cz = pos.getZ() >> AtlasCell.CELL_SHIFT;
            if (cx < minCX || cx > maxCX || cz < minCZ || cz > maxCZ) continue;
            villages.add(new KingdomMapData.VillageMarker(
                    v.getId(),
                    villageToKingdom.get(v.getId()),
                    v.getName(), pos,
                    false // capital detection TODO when kingdom capitals land
            ));
        }

        // ── 5. Routes ────────────────────────────────────────────────────────
        List<KingdomMapData.RoutePath> landRoutes = new ArrayList<>();
        List<KingdomMapData.RoutePath> seaRoutes  = new ArrayList<>();

        for (TradeRoute route : TradeRoute.ClientRouteCache.getRoutes()) {
            if (!viewableVillageIds.contains(route.getVillageA())
                    && !viewableVillageIds.contains(route.getVillageB())) continue;

            UUID connId = route.getConnectionId();
            var road = ClientTradeConnectionCache.getRoad(connId);
            if (road.isPresent()) {
                List<BlockPos> waypoints = cellPathToBlockWaypoints(road.get().cellPath());
                if (!waypoints.isEmpty()) {
                    landRoutes.add(new KingdomMapData.RoutePath(
                            connId, route.getVillageA(), route.getVillageB(),
                            waypoints, false));
                }
                continue;
            }
            var sea = ClientTradeConnectionCache.getSeaRoute(connId);
            if (sea.isPresent()) {
                List<BlockPos> waypoints = cellPathToBlockWaypoints(sea.get().cellPath());
                if (!waypoints.isEmpty()) {
                    seaRoutes.add(new KingdomMapData.RoutePath(
                            connId, route.getVillageA(), route.getVillageB(),
                            waypoints, true));
                }
            }
        }

        // Track D3.3 — province markers for the focus kingdom.
        List<KingdomMapData.ProvinceMarker> provinces = new ArrayList<>();
        for (var p : focus.getProvinces()) {
            Set<Long> cells = new LinkedHashSet<>(p.cellKeys());
            BlockPos centroid = computeCentroid(cells);
            provinces.add(new KingdomMapData.ProvinceMarker(
                    p.id(), p.name(), p.governorUuid(),
                    cells, centroid, p.stability()));
        }

        return Optional.of(new KingdomMapData(
                focusKingdomId, focus.getName(),
                minCX, minCZ, maxCX, maxCZ,
                terrain, focusCells, foreign, villages, landRoutes, seaRoutes,
                provinces));
    }

    /**
     * Cell-set centroid in block coordinates. Used to anchor the
     * governor label on the {@code ProvinceLayer}.
     */
    private static BlockPos computeCentroid(Set<Long> cells) {
        if (cells.isEmpty()) return new BlockPos(0, 64, 0);
        long sumX = 0L, sumZ = 0L;
        int n = 0;
        for (long key : cells) {
            sumX += AtlasCell.unpackX(key);
            sumZ += AtlasCell.unpackZ(key);
            n++;
        }
        int cx = (int) (sumX / n);
        int cz = (int) (sumZ / n);
        int blockX = (cx << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
        int blockZ = (cz << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
        return new BlockPos(blockX, 64, blockZ);
    }

    /**
     * Converts a cell-key path to block-center waypoints. Works for both
     * road snapshots and sea route snapshots since both now carry
     * cell-level paths.
     */
    private static List<BlockPos> cellPathToBlockWaypoints(List<Long> cellPath) {
        List<BlockPos> out = new ArrayList<>(cellPath.size());
        for (long k : cellPath) {
            int cx = AtlasCell.unpackX(k), cz = AtlasCell.unpackZ(k);
            int bx = (cx << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
            int bz = (cz << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
            out.add(new BlockPos(bx, 64, bz));
        }
        return out;
    }

    private static BlockPos approximateVillageCenter(Village village) {
        List<Building> buildings = Building.ClientBuildingCache.getBuildings();
        Set<UUID> ids = new HashSet<>(village.getBuildingIds());
        long sx = 0, sz = 0; int n = 0;
        for (Building b : buildings) {
            if (!ids.contains(b.getId())) continue;
            var pos = b.getShape().getOrigin();
            sx += pos.getX(); sz += pos.getZ(); n++;
        }
        if (n == 0) return null;
        return new BlockPos((int)(sx / n), 64, (int)(sz / n));
    }
}