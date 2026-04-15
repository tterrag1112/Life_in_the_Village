package tterrag1112.life_in_the_village.Gui.Map.Kingdom;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Gui.BookScreenColors;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.ContinentMapSyncPacket;
import tterrag1112.life_in_the_village.Village.Economy.Trade.*;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.ClientAtlasCache;

import java.util.*;

/**
 * Builds a {@link ContinentMapData} snapshot from the continent cells
 * and claim snapshots delivered by {@link ContinentMapSyncPacket},
 * combined with the already-synced client caches for kingdoms,
 * villages, buildings, and trade routes.
 *
 * <p>Kingdom tints are chosen deterministically from the UUID hash so
 * colors stay stable across sessions.
 */
public final class ContinentMapDataBuilder {

    private ContinentMapDataBuilder() {}

    public static Optional<ContinentMapData> build(
            UUID seedKingdomId,
            Set<Long> continentCells,
            List<ContinentMapSyncPacket> unused_reserved,
            List<com.mojang.datafixers.util.Pair<UUID, List<Long>>> foreignClaims) {

        if (continentCells.isEmpty()) return Optional.empty();

        Kingdom seed = Kingdom.ClientKingdomCache.getById(seedKingdomId).orElse(null);
        if (seed == null) return Optional.empty();

        // Viewport: padded continent bounds
        int[] bounds = boundsOf(continentCells);
        int minCX = bounds[0] - ContinentMapScope.BUFFER_PADDING_CELLS;
        int minCZ = bounds[1] - ContinentMapScope.BUFFER_PADDING_CELLS;
        int maxCX = bounds[2] + ContinentMapScope.BUFFER_PADDING_CELLS;
        int maxCZ = bounds[3] + ContinentMapScope.BUFFER_PADDING_CELLS;

        // Terrain grid from the client atlas cache (populated by the packet)
        Map<Long, AtlasCell> terrain = new HashMap<>();
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                ClientAtlasCache.get(cx, cz).ifPresent(c -> terrain.put(c.key(), c));
            }
        }

        // Kingdom regions — seed plus any foreign claim we were sent
        List<ContinentMapData.KingdomRegion> regions = new ArrayList<>();
        for (var pair : foreignClaims) {
            UUID kid = pair.getFirst();
            Kingdom k = Kingdom.ClientKingdomCache.getById(kid).orElse(null);
            if (k == null) continue;
            boolean isSeed = kid.equals(seedKingdomId);
            int[] tints = tintFor(kid, isSeed);
            regions.add(new ContinentMapData.KingdomRegion(
                    kid, k.getName(),
                    new LinkedHashSet<>(pair.getSecond()),
                    tints[0], tints[1], isSeed));
        }

        // Villages — every village whose anchor lies in a continent cell
        Map<UUID, UUID> villageToKingdom = new HashMap<>();
        for (Kingdom k : Kingdom.ClientKingdomCache.getKingdoms()) {
            for (UUID vid : k.getVillageIds()) villageToKingdom.put(vid, k.getId());
        }

        List<KingdomMapData.VillageMarker> villages = new ArrayList<>();
        for (Village v : Building.ClientBuildingCache.getVillages()) {
            BlockPos anchor = v.getAnchorPos();
            if (anchor == null) continue;
            long key = AtlasCell.packKey(
                    anchor.getX() >> AtlasCell.CELL_SHIFT,
                    anchor.getZ() >> AtlasCell.CELL_SHIFT);
            if (!continentCells.contains(key)) continue;
            villages.add(new KingdomMapData.VillageMarker(
                    v.getId(), villageToKingdom.get(v.getId()),
                    v.getName(), anchor, false));
        }

        // Routes — reuse the kingdom map's sampling logic
        Set<UUID> viewableVillages = new HashSet<>();
        for (var vm : villages) viewableVillages.add(vm.id());

        List<KingdomMapData.RoutePath> landRoutes = new ArrayList<>();
        List<KingdomMapData.RoutePath> seaRoutes  = new ArrayList<>();
        for (TradeRoute route : TradeRoute.ClientRouteCache.getRoutes()) {
            if (!viewableVillages.contains(route.getVillageA())
                    && !viewableVillages.contains(route.getVillageB())) continue;
            UUID connId = route.getConnectionId();
            var road = ClientTradeConnectionCache.getRoad(connId);
            if (road.isPresent()) {
                List<BlockPos> wps = cellPathToBlockWaypoints(road.get().cellPath());
                if (!wps.isEmpty()) landRoutes.add(new KingdomMapData.RoutePath(
                        connId, route.getVillageA(), route.getVillageB(), wps, false));
                continue;
            }
            var sea = ClientTradeConnectionCache.getSeaRoute(connId);
            if (sea.isPresent()) {
                List<BlockPos> wps = cellPathToBlockWaypoints(sea.get().cellPath());
                if (!wps.isEmpty()) seaRoutes.add(new KingdomMapData.RoutePath(
                        connId, route.getVillageA(), route.getVillageB(), wps, true));
            }
        }

        return Optional.of(new ContinentMapData(
                seedKingdomId, seed.getName(),
                minCX, minCZ, maxCX, maxCZ,
                terrain, continentCells, regions,
                villages, landRoutes, seaRoutes));
    }

    /**
     * Deterministic tint from UUID hash. Seed kingdom gets a warm tint
     * (derived from {@code KINGDOM_FOCUS_TINT}); others get muted
     * variants cycling through hue.
     */
    private static int[] tintFor(UUID id, boolean isSeed) {
        if (isSeed) return new int[] {
                BookScreenColors.KINGDOM_FOCUS_TINT,
                BookScreenColors.KINGDOM_BORDER_FOCUS };
        // Hash UUID into a hue offset, apply to the muted foreign palette.
        int h = (int) (id.getMostSignificantBits() ^ id.getLeastSignificantBits());
        // Rotate RGB channels by hashing to produce 8 distinct muted tints
        int channel = Math.floorMod(h, 8);
        int[][] palette = {
                { 0x44A89060, 0xFF7A6040 },  // amber
                { 0x4488A06A, 0xFF5A6A40 },  // olive
                { 0x446A8890, 0xFF406470 },  // slate
                { 0x4490607A, 0xFF703B55 },  // mauve
                { 0x446A7A90, 0xFF404E70 },  // steel
                { 0x44907A60, 0xFF6B5540 },  // tan
                { 0x44609078, 0xFF407055 },  // sage
                { 0x44806878, 0xFF5A4A55 }   // plum
        };
        return palette[channel];
    }

    private static List<BlockPos> cellPathToBlockWaypoints(List<Long> cellPath) {
        List<BlockPos> out = new ArrayList<>(cellPath.size());
        for (long k : cellPath) {
            int cx = AtlasCell.unpackX(k), cz = AtlasCell.unpackZ(k);
            out.add(new BlockPos(
                    (cx << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF,
                    64,
                    (cz << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF));
        }
        return out;
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
}