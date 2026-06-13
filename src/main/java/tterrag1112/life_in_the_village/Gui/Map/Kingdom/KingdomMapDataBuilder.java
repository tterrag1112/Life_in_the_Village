package tterrag1112.life_in_the_village.Gui.Map.Kingdom;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomClaim;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Economy.Trade.*;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.ClientAtlasCache;

import java.util.*;

/**
 * Builds a {@link KingdomMapData} snapshot on GUI open from the
 * client-side caches. Independent of chunk loading.
 *
 * <p>PLAYER-CENTERED: the viewport is a fixed-radius box around the
 * player block position, NOT a kingdom claim. Every kingdom, village,
 * and unrealized settlement-charter pin whose cell falls within the box
 * is drawn, regardless of kingdom association — so the map is usable
 * (and never blank) even when the player stands far from any kingdom.
 *
 * <p>Supersedes the prior claim-anchored builder (the {@code
 * fix-blank-maps} change), which derived the viewport from one kingdom's
 * {@code KingdomBoundsProvider} claim and returned empty (blank) for an
 * unaffiliated player. The {@code KingdomBoundsProvider} indirection is
 * no longer used by this builder.
 */
public final class KingdomMapDataBuilder {

    /**
     * Player-centered viewport radius in blocks for the kingdom map.
     * Server-side {@code KingdomMapScope.VIEWPORT_RADIUS_BLOCKS} mirrors
     * this so the pre-filled terrain region matches the rendered box.
     * 2000 blocks keeps village placement around the player legible.
     */
    public static final int VIEWPORT_RADIUS_BLOCKS = 2000;

    private KingdomMapDataBuilder() {}

    /**
     * @param screenKeyKingdomId screen-identity UUID (matches the sync
     *        reply to the requesting screen/book); NOT a viewport anchor.
     * @param playerX,playerZ    player block position — the viewport centre.
     */
    public static Optional<KingdomMapData> build(UUID screenKeyKingdomId,
                                                 int playerX, int playerZ) {
        // ── 1. Player-centered viewport bounds (cell coords) ─────────────────
        int rCells = (VIEWPORT_RADIUS_BLOCKS >> AtlasCell.CELL_SHIFT) + 1;
        int centreCX = playerX >> AtlasCell.CELL_SHIFT;
        int centreCZ = playerZ >> AtlasCell.CELL_SHIFT;
        int minCX = centreCX - rCells;
        int minCZ = centreCZ - rCells;
        int maxCX = centreCX + rCells;
        int maxCZ = centreCZ + rCells;

        // ── 2. Terrain grid in viewport ──────────────────────────────────────
        Map<Long, AtlasCell> terrain = new HashMap<>();
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                ClientAtlasCache.get(cx, cz).ifPresent(c -> terrain.put(c.key(), c));
            }
        }

        // ── 3. Every kingdom whose claim intersects the viewport ─────────────
        // All in-range kingdoms render as peers (the "foreign" channel of the
        // territory layer); there is no single focus kingdom in the
        // player-centered model. focusCells is left empty.
        List<KingdomMapData.ForeignKingdom> foreign = new ArrayList<>();
        Set<UUID> inRangeKingdoms = new HashSet<>();
        for (Kingdom k : Kingdom.ClientKingdomCache.getKingdoms()) {
            Set<Long> cells = k.getTerritorialClaim()
                    .map(KingdomClaim::claimedCellKeys)
                    .map(list -> (Set<Long>) new LinkedHashSet<>(list))
                    .orElse(Collections.emptySet());
            Set<Long> inView = new HashSet<>();
            for (long key : cells) {
                int cx = AtlasCell.unpackX(key), cz = AtlasCell.unpackZ(key);
                if (cx >= minCX && cx <= maxCX && cz >= minCZ && cz <= maxCZ) {
                    inView.add(key);
                }
            }
            if (!inView.isEmpty()) {
                foreign.add(new KingdomMapData.ForeignKingdom(
                        k.getId(), k.getName(), inView));
                inRangeKingdoms.add(k.getId());
            }
        }

        // ── 4. Villages whose centre falls in the viewport ───────────────────
        Map<UUID, UUID> villageToKingdom = new HashMap<>();
        for (Kingdom k : Kingdom.ClientKingdomCache.getKingdoms()) {
            for (UUID vid : k.getVillageIds()) villageToKingdom.put(vid, k.getId());
        }

        List<KingdomMapData.VillageMarker> villages = new ArrayList<>();
        Set<UUID> viewableVillageIds = new HashSet<>();
        for (Village v : Building.ClientBuildingCache.getVillages()) {
            BlockPos pos = approximateVillageCenter(v);
            if (pos == null) continue;
            int cx = pos.getX() >> AtlasCell.CELL_SHIFT;
            int cz = pos.getZ() >> AtlasCell.CELL_SHIFT;
            if (cx < minCX || cx > maxCX || cz < minCZ || cz > maxCZ) continue;
            viewableVillageIds.add(v.getId());
            villages.add(new KingdomMapData.VillageMarker(
                    v.getId(),
                    villageToKingdom.get(v.getId()),
                    v.getName(), pos,
                    false // capital detection TODO when kingdom capitals land
            ));
        }

        // ── 4b. Settlement-charter pins (Track C1-a) ──────────────────────────
        // Unrealized charters have no Village yet, so they have no anchor-pos
        // marker above. Render a pin at the target-cell centre for EVERY
        // kingdom (not just in-range claims) whose charter target falls inside
        // the viewport, so a chartered-but-unsited capital is visible. Once a
        // charter realizes (C1-c) the produced Village carries the marker via
        // the loop above and we skip the charter pin to avoid a double.
        for (Kingdom k : Kingdom.ClientKingdomCache.getKingdoms()) {
            for (var charter : k.getSettlementCharters()) {
                if (!charter.isUnrealized()) continue; // realized -> Village marker
                BlockPos pos = charter.targetCellCentre();
                int cx = pos.getX() >> AtlasCell.CELL_SHIFT;
                int cz = pos.getZ() >> AtlasCell.CELL_SHIFT;
                if (cx < minCX || cx > maxCX || cz < minCZ || cz > maxCZ) continue;
                villages.add(new KingdomMapData.VillageMarker(
                        charter.id(),
                        k.getId(),
                        charter.name(), pos,
                        charter.capital()));
            }
        }

        // ── 5. Routes (either endpoint in-view) ───────────────────────────────
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

        // ── 6. Provinces of every in-range kingdom (Track D3.3) ───────────────
        List<KingdomMapData.ProvinceMarker> provinces = new ArrayList<>();
        for (Kingdom k : Kingdom.ClientKingdomCache.getKingdoms()) {
            if (!inRangeKingdoms.contains(k.getId())) continue;
            for (var p : k.getProvinces()) {
                Set<Long> cells = new LinkedHashSet<>(p.cellKeys());
                BlockPos centroid = computeCentroid(cells);
                provinces.add(new KingdomMapData.ProvinceMarker(
                        p.id(), p.name(), p.governorUuid(),
                        cells, centroid, p.stability()));
            }
        }

        // Always render — the player-centered terrain box is never blank.
        // focusKingdomId/Name carry the screen-identity kingdom for chrome,
        // but the viewport no longer depends on it; "Region" name when the
        // screen key is not a known kingdom (e.g. unaffiliated player).
        String regionName = Kingdom.ClientKingdomCache.getById(screenKeyKingdomId)
                .map(Kingdom::getName).orElse("Surrounding region");

        return Optional.of(new KingdomMapData(
                screenKeyKingdomId, regionName,
                minCX, minCZ, maxCX, maxCZ,
                terrain, Collections.emptySet(), foreign, villages,
                landRoutes, seaRoutes,
                provinces,
                // Phase 5e — travellers from the last map sync.
                tterrag1112.life_in_the_village.Village.Travel
                        .ClientTravellerCache.getTravellers()));
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
