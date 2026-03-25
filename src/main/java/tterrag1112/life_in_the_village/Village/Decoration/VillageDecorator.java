// src/main/java/tterrag1112/life_in_the_village/Village/Decoration/VillageDecorator.java
package tterrag1112.life_in_the_village.Village.Decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Trade.RoadRouter;
import tterrag1112.life_in_the_village.Village.Planning.*;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

/**
 * Handles all post-placement decoration for a village.
 *
 * <h3>Pass order — normal villages</h3>
 * <ol>
 *   <li>Building foundations</li>
 *   <li>Ground smoothing under each building</li>
 *   <li>Town square construction</li>
 *   <li>Hierarchical street network (PRIMARY→SECONDARY→TERTIARY)</li>
 *   <li>Hollow-ground support under paths</li>
 *   <li>Village perimeter (palisade / wall)</li>
 *   <li>Building border transitions (kerb slabs, garden walls)</li>
 *   <li>Approach gradient (worn-dirt ring outside perimeter)</li>
 *   <li>Per-building exterior decorations</li>
 *   <li>Street furniture (benches, junction features)</li>
 *   <li>Proximity furniture (type-specific props)</li>
 *   <li>Market stalls (town+ tier)</li>
 *   <li>Landmark (town+ tier)</li>
 *   <li>Flower scatter</li>
 *   <li>Weathering</li>
 *   <li>Guard patrol route</li>
 * </ol>
 *
 * <h3>Capital override</h3>
 * When the layout has a {@link CapitalStreetGraph}, the decoration
 * pipeline changes:
 * <ul>
 *   <li>Streets are placed as strict straight-line runs (no pathfinding)
 *       for axis-aligned grid segments; diagonal spoke segments use
 *       RoadRouter.</li>
 *   <li>Building spurs only connect buildings that are NOT on a city
 *       block face (i.e. the town hall and emergency-placed buildings).</li>
 *   <li>Courtyard interiors of each city block are paved and furnished.</li>
 *   <li>Building transitions are enabled (kerb slabs work correctly
 *       because streets exist before this pass runs).</li>
 *   <li>Approach gradient is skipped — the perimeter wall serves
 *       the same visual purpose.</li>
 *   <li>Flowers, market stalls, landmark, proximity furniture, and
 *       street furniture are disabled until capital layout is stable.</li>
 * </ul>
 *
 * <h3>Pre-placement street pass</h3>
 * {@link #placeCapitalStreets} is called by VillageSpawner BEFORE
 * building placement so streets exist in the world before structure
 * templates are placed. This prevents roads from routing around buildings.
 */
public class VillageDecorator {

    // =========================================================================
    // Main entry point
    // =========================================================================

    public static void decorateVillage(ServerLevel level,
                                       Village village,
                                       VillageSavedData data,
                                       VillageLayout layout) {

        List<Building> buildings = village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        if (buildings.isEmpty()) return;

        boolean isCapital = layout != null && layout.hasCapitalStreetGraph();

        BlockPos firstOrigin = buildings.get(0).getShape().getOrigin();
        VillageBiomeStyle style = VillageBiomeStyle.detect(level, firstOrigin);
        VillageSizeTier   tier  = VillageSizeTier.fromBuildingCount(buildings.size());

        System.out.println("VillageDecorator: decorating '"
                + village.getName() + "' as " + tier.displayName
                + " in " + style.name() + " style ("
                + buildings.size() + " buildings)"
                + (isCapital ? " [CAPITAL]" : ""));

        // ── Step 1: Foundations ───────────────────────────────────────────────
        /*buildings.stream()
                .filter(b -> b.getType() != BuildingType.TOWN_SQUARE)
                .forEach(b -> BuildingFoundation.place(level, b));

        // ── Step 2: Ground smoothing ──────────────────────────────────────────
        buildings.stream()
                .filter(b -> b.getType() != BuildingType.TOWN_SQUARE)
                .forEach(b -> smoothUnderBuilding(level, b));

         */

        // ── Step 3: Town square ───────────────────────────────────────────────
        BlockPos squareCenter = resolveSquareCenter(level, layout, buildings);
        Set<BlockPos> squarePavement = TownSquarePlacer.place(
                level, squareCenter, style, tier, village, data);

        Set<Long> protectedXZ = collectBuildingXZ(buildings);
        for (BlockPos p : squarePavement) {
            protectedXZ.add(xzKey(p.getX(), p.getZ()));
        }



        // ── Step 4: Street network ────────────────────────────────────────────
        StreetNetwork network = buildStreetNetwork(
                level, buildings, squareCenter, style, tier,
                protectedXZ, data, village, layout);

        village.setPathHubPos(squareCenter);
        data.setDirty();

        Set<Long> allPathXZ = new HashSet<>(protectedXZ);
        for (BlockPos p : network.collectBlocks(StreetTier.TERTIARY)) {
            allPathXZ.add(xzKey(p.getX(), p.getZ()));
        }

        // ── Step 5: Support hollow ground ─────────────────────────────────────
        TerrainSmoother.supportPathBlocks(level,
                new ArrayList<>(network.collectBlocks(StreetTier.TERTIARY)));

        // ── Step 6: Perimeter ─────────────────────────────────────────────────
        VillagePerimeter.place(level, village, data, style, tier, allPathXZ);

        // ── Step 7: Building border transitions ───────────────────────────────
        // Enabled for all villages. For capitals, streets were pre-placed by
        // VillageSpawner so the kerb-slab logic works correctly.
      /*  buildings.stream()
                .filter(b -> b.getType() != BuildingType.TOWN_SQUARE)
                .forEach(b -> placeBuildingTransitions(level, b, style, allPathXZ));

       */

        // ── Step 7b: Approach gradient ────────────────────────────────────────
        // Skipped for capitals — the perimeter wall fills this role visually.
        if (!isCapital) {
            placeApproachGradient(level, village, data, style, allPathXZ);
        }



        // ── Step 8: Capital courtyard fill ───────────────────────────────────
        if (isCapital) {
            placeCapitalCourtyards(level, layout.getCapitalStreetGraph(), style);
        }

        // ── Steps 9–12: Standard decorations (disabled for capitals) ─────────
        if (!isCapital) {
            // Step 9: Per-building exterior decorations
            buildings.stream()
                    .filter(b -> b.getType() != BuildingType.TOWN_SQUARE)
                    .forEach(b -> placeExteriorDecorations(level, b, style, tier));
            // Step 10: Street furniture
            placeStreetFurniture(level, network, style, tier);
            // Step 10b: Proximity furniture
            placeProximityFurniture(level, buildings, style);
            // Step 11: Market stalls
            if (tier.hasMarket) {
                placeMarketStalls(level, squareCenter, style);
            }
            // Step 12: Landmark
            if (tier.hasLandmark) {
                findTownHall(buildings).ifPresent(th ->
                        placeLandmark(level, th, style, village));
            }
            // Step 13: Flowers
            if (tier.flowerAttempts > 0) {
                scatterFlowers(level, village, data, style, tier.flowerAttempts);
            }
        } else {
            System.out.println("VillageDecorator: capital mode — "
                    + "per-building decorations deferred until layout stable");
        }

        // ── Step 14: Weathering ───────────────────────────────────────────────
        VillageWeathering.weather(level, village, data, style);
        // ── Step 15: Guard patrol route ───────────────────────────────────────
        VillagePatrolRouteBuilder.build(level, village, data);
    }

    // =========================================================================
    // Pre-placement street pass (called by VillageSpawner before buildings)
    // =========================================================================

    /**
     * Places only the capital street grid as straight-line block runs.
     * Called by VillageSpawner BEFORE building placement so that road
     * blocks exist in the world before structure templates are placed.
     * This prevents RoadRouter from routing around buildings when the
     * full decoration pass runs later.
     *
     * @return the set of XZ keys of all placed road blocks, so VillageSpawner
     *         can add them to its protectedXZ set if needed
     */
    public static Set<Long> placeCapitalStreets(ServerLevel level,
                                                Village village,
                                                VillageSavedData data,
                                                VillageLayout layout) {
        if (!layout.hasCapitalStreetGraph()) return new HashSet<>();

        CapitalStreetGraph graph = layout.getCapitalStreetGraph();
        BlockPos centre          = layout.getCenter();
        VillageBiomeStyle style  = VillageBiomeStyle.detect(level, centre);
        Set<Long> placedXZ       = new HashSet<>();

        for (CapitalStreetGraph.StreetSegment seg : graph.getSegments()) {
            StreetTier tier = convertTier(seg.tier);

            int surfYA = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, seg.a.x, seg.a.z);
            int surfYB = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, seg.b.x, seg.b.z);
            BlockPos posA = new BlockPos(seg.a.x, surfYA, seg.a.z);
            BlockPos posB = new BlockPos(seg.b.x, surfYB, seg.b.z);

            // Axis-aligned segments → straight-line; diagonal spokes → RoadRouter
            List<BlockPos> route = seg.axisAligned
                    ? straightLineRoute(posA, posB, level)
                    : RoadRouter.findRoad(level, posA, posB, 200_000);

            if (route.isEmpty()) continue;

            List<BlockPos> placed = placeStreetSegment(
                    level, route, style, tier, placedXZ);
            placed.forEach(p -> placedXZ.add(xzKey(p.getX(), p.getZ())));

            data.addVillagePath(new VillagePath(
                    UUID.randomUUID(), village.getId(), placed,
                    tier == StreetTier.PRIMARY   ? VillagePath.PathTier.COBBLESTONE
                            : tier == StreetTier.SECONDARY ? VillagePath.PathTier.GRAVEL
                            :                                VillagePath.PathTier.DIRT));
        }

        data.setDirty();
        System.out.println("VillageDecorator: pre-placed capital streets ("
                + placedXZ.size() + " blocks)");
        return placedXZ;
    }

    // ── Straight-line route for axis-aligned segments ─────────────────────────

    private static List<BlockPos> straightLineRoute(BlockPos a, BlockPos b,
                                                    ServerLevel level) {
        List<BlockPos> line = new ArrayList<>();
        int dx    = b.getX() - a.getX();
        int dz    = b.getZ() - a.getZ();
        int steps = Math.abs(dx) + Math.abs(dz);
        int stepX = dx == 0 ? 0 : (dx > 0 ? 1 : -1);
        int stepZ = dz == 0 ? 0 : (dz > 0 ? 1 : -1);
        for (int i = 0; i <= steps; i++) {
            int wx = a.getX() + stepX * i;
            int wz = a.getZ() + stepZ * i;
            int wy = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz);
            line.add(new BlockPos(wx, wy, wz));
        }
        return line;
    }

    // =========================================================================
    // Street network dispatch
    // =========================================================================

    private static StreetNetwork buildStreetNetwork(
            ServerLevel level, List<Building> buildings,
            BlockPos squareCenter, VillageBiomeStyle style,
            VillageSizeTier tier, Set<Long> protectedXZ,
            VillageSavedData data, Village village, VillageLayout layout) {

        if (layout != null && layout.hasCapitalStreetGraph()) {
            return buildCapitalStreetNetwork(
                    level, layout.getCapitalStreetGraph(),
                    buildings, squareCenter, style, data, village, protectedXZ);
        }

        // ── Normal village: hub-and-spoke with zone priority ──────────────────
        StreetNetwork network = new StreetNetwork();
        network.setHub(squareCenter);

        List<Building> sorted = buildings.stream()
                .filter(b -> b.getType() != BuildingType.TOWN_SQUARE)
                .sorted(Comparator.comparingInt(b -> {
                    ZoneRegistry.ZoneEntry ze = ZoneRegistry.get(b.getType());
                    return ze.zone().ordinal() * 100 + ze.priority();
                }))
                .toList();

        for (Building building : sorted) {
            BlockPos entrance = PathRouter.getBuildingEntrance(building);
            ZoneRegistry.ZoneEntry zone = ZoneRegistry.get(building.getType());
            StreetTier streetTier = zone.preferredStreet();

            StreetNetwork.Node branchFrom = streetTier == StreetTier.PRIMARY
                    ? network.getHub()
                    : network.nearestNodeOfTier(entrance,
                    StreetTier.values()[Math.max(0, streetTier.ordinal() - 1)]);

            int budget = village.getBuildingIds().size()
                    >= LayoutDensityProfile.CAPITAL_THRESHOLD ? 800_000 : 500_000;
            List<BlockPos> route = RoadRouter.findRoad(
                    level, squareCenter, entrance, budget);

            if (route.isEmpty()) {
                System.out.println("VillageDecorator: no route to "
                        + building.getType());
                continue;
            }

            List<BlockPos> placed = placeStreetSegment(
                    level, route, style, streetTier, protectedXZ);
            if (placed.isEmpty()) continue;

            StreetNetwork.Node endNode = network.addNode(
                    entrance, streetTier, building.getId());
            network.connect(branchFrom, endNode, streetTier, placed);
            placed.forEach(p -> protectedXZ.add(xzKey(p.getX(), p.getZ())));

            data.addVillagePath(new VillagePath(
                    UUID.randomUUID(), village.getId(), placed,
                    streetTier == StreetTier.PRIMARY   ? VillagePath.PathTier.COBBLESTONE
                            : streetTier == StreetTier.SECONDARY ? VillagePath.PathTier.GRAVEL
                            :                                      VillagePath.PathTier.DIRT));

            System.out.println("VillageDecorator: " + streetTier.name()
                    + " street to " + building.getType()
                    + " (" + placed.size() + " blocks)");
        }

        return network;
    }

    // =========================================================================
    // Capital street network
    // =========================================================================

    /**
     * Converts the abstract {@link CapitalStreetGraph} into placed road blocks.
     *
     * <p>If streets were already placed by {@link #placeCapitalStreets}, this
     * method detects the overlap via protectedXZ and skips re-placing those
     * blocks, but still registers them in the StreetNetwork for spur routing.
     *
     * <p>Short spur roads connect building entrances to the nearest street
     * node, but ONLY for buildings that are not on city block faces (i.e.
     * the town hall, castle, and emergency-placed buildings). City block
     * face buildings are already adjacent to streets by construction.
     */
    private static StreetNetwork buildCapitalStreetNetwork(
            ServerLevel level,
            CapitalStreetGraph graph,
            List<Building> buildings,
            BlockPos squareCenter,
            VillageBiomeStyle style,
            VillageSavedData data,
            Village village,
            Set<Long> protectedXZ) {

        StreetNetwork network = new StreetNetwork();
        network.setHub(squareCenter);

        // Build a set of all city-block face plot positions (these buildings
        // face the street already and don't need spurs)
        Set<Long> cityBlockPlotXZ = new HashSet<>();
        for (CapitalStreetGraph.CityBlock block : graph.getCityBlocks()) {
            for (CapitalStreetGraph.BlockFacePlot plot : block.plots) {
                if (plot.occupied) {
                    cityBlockPlotXZ.add(xzKey(plot.x, plot.z));
                }
            }
        }

        // Convert graph nodes to StreetNetwork nodes
        Map<UUID, StreetNetwork.Node> nodeMap = new HashMap<>();
        for (var gNode : graph.getNodes()) {
            int surfY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    gNode.x, gNode.z);
            BlockPos pos = new BlockPos(gNode.x, surfY, gNode.z);
            StreetTier tier = convertTier(gNode.tier);
            nodeMap.put(gNode.id, network.addNode(pos, tier, null));
        }

        // Place each street segment
        for (var seg : graph.getSegments()) {
            StreetNetwork.Node sA = nodeMap.get(seg.a.id);
            StreetNetwork.Node sB = nodeMap.get(seg.b.id);
            if (sA == null || sB == null) continue;

            StreetTier tier = convertTier(seg.tier);

            // Check if this segment was already placed by the pre-pass
            boolean alreadyPlaced =
                    protectedXZ.contains(xzKey(sA.pos.getX(), sA.pos.getZ()))
                            && protectedXZ.contains(xzKey(sB.pos.getX(), sB.pos.getZ()));

            List<BlockPos> placed;
            if (alreadyPlaced) {
                // Re-use the straight line for network registration only
                placed = straightLineRoute(sA.pos, sB.pos, level);
            } else {
                List<BlockPos> route = seg.axisAligned
                        ? straightLineRoute(sA.pos, sB.pos, level)
                        : RoadRouter.findRoad(level, sA.pos, sB.pos, 200_000);
                if (route.isEmpty()) continue;
                placed = placeStreetSegment(level, route, style, tier, protectedXZ);
                if (placed.isEmpty()) continue;
                placed.forEach(p -> protectedXZ.add(xzKey(p.getX(), p.getZ())));
                data.addVillagePath(new VillagePath(
                        UUID.randomUUID(), village.getId(), placed,
                        tier == StreetTier.PRIMARY   ? VillagePath.PathTier.COBBLESTONE
                                : tier == StreetTier.SECONDARY ? VillagePath.PathTier.GRAVEL
                                :                                VillagePath.PathTier.DIRT));
            }

            network.connect(sA, sB, tier, placed);
        }

        // Spur roads for non-face buildings (town hall, castle, emergency placements)
        for (Building building : buildings) {
            if (building.getType() == BuildingType.TOWN_SQUARE) continue;

            BlockPos entrance = PathRouter.getBuildingEntrance(building);
            long entranceKey  = xzKey(entrance.getX(), entrance.getZ());

            // Skip buildings on city block faces — they're already on the street
            if (cityBlockPlotXZ.contains(entranceKey)) continue;

            StreetNetwork.Node nearest = network.nearestNodeOfTier(
                    entrance, StreetTier.TERTIARY);
            if (nearest == null
                    || nearest.pos.distSqr(entrance) > 40 * 40
                    || nearest.pos.distSqr(entrance) < 4) continue;

            List<BlockPos> spur = RoadRouter.findRoad(
                    level, nearest.pos, entrance, 50_000);
            if (spur.isEmpty()) continue;

            ZoneRegistry.ZoneEntry zone = ZoneRegistry.get(building.getType());
            StreetTier spurTier = zone.preferredStreet();

            List<BlockPos> spurPlaced = placeStreetSegment(
                    level, spur, style, spurTier, protectedXZ);
            if (spurPlaced.isEmpty()) continue;

            StreetNetwork.Node entryNode = network.addNode(
                    entrance, spurTier, building.getId());
            network.connect(nearest, entryNode, spurTier, spurPlaced);
            spurPlaced.forEach(p -> protectedXZ.add(xzKey(p.getX(), p.getZ())));

            data.addVillagePath(new VillagePath(
                    UUID.randomUUID(), village.getId(), spurPlaced,
                    VillagePath.PathTier.DIRT));

            System.out.println("VillageDecorator: capital spur to "
                    + building.getType() + " (" + spurPlaced.size() + " blocks)");
        }

        return network;
    }

    // ── Tier conversion ───────────────────────────────────────────────────────

    private static StreetTier convertTier(CapitalStreetGraph.StreetTier t) {
        return switch (t) {
            case PRIMARY   -> StreetTier.PRIMARY;
            case SECONDARY -> StreetTier.SECONDARY;
            case TERTIARY  -> StreetTier.TERTIARY;
        };
    }

    // =========================================================================
    // Street segment placement
    // =========================================================================

    private static List<BlockPos> placeStreetSegment(
            ServerLevel level, List<BlockPos> route,
            VillageBiomeStyle style, StreetTier tier,
            Set<Long> protectedXZ) {

        List<BlockPos> placed = new ArrayList<>();

        for (int i = 0; i < route.size(); i++) {
            BlockPos node = route.get(i);
            BlockPos prev = i > 0              ? route.get(i - 1) : node;
            BlockPos next = i < route.size()-1 ? route.get(i + 1) : node;

            int tx    = next.getX() - prev.getX();
            int tz    = next.getZ() - prev.getZ();
            int perpX = Integer.signum(-tz);
            int perpZ = Integer.signum(tx);
            if (perpX == 0 && perpZ == 0) perpX = 1;

            for (int offset = -tier.halfWidth; offset <= tier.halfWidth; offset++) {
                int wx = node.getX() + perpX * offset;
                int wz = node.getZ() + perpZ * offset;

                if (protectedXZ.contains(xzKey(wx, wz))) continue;

                clearColumn(level, wx, wz, node.getY());

                int surfY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz);

                // Walk down to solid ground if heightmap is stale
                int probe = surfY;
                while (probe > level.getMinY() + 1
                        && level.getBlockState(new BlockPos(wx, probe, wz)).isAir()) {
                    probe--;
                }

                BlockPos surface = new BlockPos(wx, probe, wz);
                BlockPos above   = surface.above();

                if (!level.getBlockState(surface.below()).isSolidRender()) continue;

                // Suppress sound on bulk placement
                level.setBlock(surface,
                        style.streetStateFor(tier), 18);

                // Clear vegetation above
                BlockState aboveState = level.getBlockState(above);
                if (!aboveState.isAir() && (
                        aboveState.is(BlockTags.REPLACEABLE)
                                || aboveState.is(BlockTags.FLOWERS)
                                || aboveState.is(BlockTags.SMALL_FLOWERS)
                                || aboveState.is(BlockTags.LEAVES)
                                || aboveState.is(BlockTags.LOGS))) {
                    level.setBlock(above, Blocks.AIR.defaultBlockState(), 3);
                }

                // Kerb slab on outermost edge of primary streets
                if (tier == StreetTier.PRIMARY
                        && Math.abs(offset) == tier.halfWidth) {
                    if (level.getBlockState(above).isAir()) {
                        level.setBlock(above, style.primaryStreetSlabState(), 3);
                    }
                }

                placed.add(above);
            }
        }

        placeSlabTransitions(level, placed, style);
        placeLamppostsForTier(level, placed, style, tier);
        return placed;
    }

    // ── Slab transitions at height steps ─────────────────────────────────────

    private static void placeSlabTransitions(ServerLevel level,
                                             List<BlockPos> surface,
                                             VillageBiomeStyle style) {
        for (int i = 1; i < surface.size(); i++) {
            BlockPos prev = surface.get(i - 1);
            BlockPos cur  = surface.get(i);
            int dy = cur.getY() - prev.getY();
            if (Math.abs(dy) != 1) continue;
            BlockPos lower = dy > 0 ? prev : cur;
            BlockPos lowerRoad = lower.below();
            BlockState rs = level.getBlockState(lowerRoad);
            if (isRoadBlock(rs)) {
                level.setBlock(lowerRoad, style.streetStateFor(StreetTier.SECONDARY), 3);
            }
        }
    }

    private static boolean isRoadBlock(BlockState s) {
        return s.is(Blocks.COBBLESTONE) || s.is(Blocks.GRAVEL)
                || s.is(Blocks.DIRT_PATH)   || s.is(Blocks.COBBLESTONE_SLAB)
                || s.is(Blocks.STONE_BRICK_SLAB) || s.is(BlockTags.WOOL_CARPETS)
                || s.is(Blocks.SMOOTH_SANDSTONE) || s.is(Blocks.PACKED_ICE)
                || s.is(Blocks.MUD);
    }

    // ── Lampposts ─────────────────────────────────────────────────────────────

    private static void placeLamppostsForTier(ServerLevel level,
                                              List<BlockPos> surface,
                                              VillageBiomeStyle style,
                                              StreetTier tier) {
        if (surface.isEmpty()) return;
        int spacing = tier.lampostSpacing;
        for (int i = spacing / 2; i < surface.size(); i += spacing) {
            BlockPos s    = surface.get(i);
            BlockPos pole = s.above();
            BlockPos lamp = s.above(2);
            if (!level.getBlockState(pole).isAir()) continue;
            level.setBlock(pole, style.fenceState(),   3);
            level.setBlock(lamp, style.lanternState(), 3);
        }
    }

    // =========================================================================
    // Capital courtyard fill (Priority 6)
    // =========================================================================

    /**
     * Paves the interior courtyard of each city block and places a
     * district-appropriate central feature.
     *
     * <p>The courtyard is the enclosed space between the building rows on
     * the four faces of a city block. Its dimensions are computed from
     * the same road + setback + buildingDepth margins used by
     * {@link CapitalStreetGraph.CityBlock#generateFacePlots}, so the
     * paved area matches exactly the space between the buildings.
     */
    private static void placeCapitalCourtyards(ServerLevel level,
                                               CapitalStreetGraph graph,
                                               VillageBiomeStyle style) {
        if (graph == null) return;

        // Use the same margins as generateFacePlots
        final int ROAD_HW     = 2;  // SECONDARY halfWidth
        final int SETBACK     = 2;
        final int BLDG_DEPTH  = 10;

        Random rng = new Random(graph.centreX * 31L + graph.centreZ);

        for (CapitalStreetGraph.CityBlock block : graph.getCityBlocks()) {
            int cxMin = block.courtyardMinX(ROAD_HW, SETBACK, BLDG_DEPTH);
            int cxMax = block.courtyardMaxX(ROAD_HW, SETBACK, BLDG_DEPTH);
            int czMin = block.courtyardMinZ(ROAD_HW, SETBACK, BLDG_DEPTH);
            int czMax = block.courtyardMaxZ(ROAD_HW, SETBACK, BLDG_DEPTH);

            if (cxMax <= cxMin || czMax <= czMin) continue;
            if (cxMax - cxMin < 3 || czMax - czMin < 3) continue;

            // ── Pave courtyard floor ──────────────────────────────────────────
            for (int x = cxMin; x <= cxMax; x++) {
                for (int z = czMin; z <= czMax; z++) {
                    int surfY = level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos surface = new BlockPos(x, surfY, z);
                    BlockState existing = level.getBlockState(surface);
                    if (existing.is(Blocks.GRASS_BLOCK)
                            || existing.is(Blocks.DIRT)
                            || existing.is(Blocks.COARSE_DIRT)
                            || existing.is(Blocks.SAND)
                            || existing.is(BlockTags.REPLACEABLE)) {
                        boolean useSecondary = ((x + z) % 5 == 0);
                        level.setBlock(surface,
                                useSecondary ? style.secondaryStreetState()
                                        : style.pathState(), 18);
                    }
                }
            }

            // ── Central feature by district ───────────────────────────────────
            int midX = (cxMin + cxMax) / 2;
            int midZ = (czMin + czMax) / 2;
            int midY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, midX, midZ);
            BlockPos midSurface = new BlockPos(midX, midY, midZ);
            BlockPos midAbove   = midSurface.above();

            switch (block.district) {
                case PALACE_WARD, CIVIC_FORUM -> {
                    // Stone pillar well
                    if (level.getBlockState(midSurface).isAir()
                            || isNaturalTerrain(level.getBlockState(midSurface))) {
                        level.setBlock(midSurface,
                                Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), 18);
                    }
                    if (level.getBlockState(midAbove).isAir()) {
                        level.setBlock(midAbove, style.stoneWallState(), 18);
                        level.setBlock(midAbove.above(), style.lanternState(), 18);
                    }
                }
                case CRAFT_QUARTER -> {
                    // Work surface — barrel or crafting table
                    placeIfClear(level, midAbove,
                            ((midX + midZ) % 2 == 0)
                                    ? Blocks.BARREL.defaultBlockState()
                                    : Blocks.CRAFTING_TABLE.defaultBlockState());
                }
                case NOBLE_QUARTER -> {
                    // Garden with a flower centrepiece
                    placeIfClear(level, midAbove, style.flowerState());
                    placeIfClear(level, midAbove.north(), style.flowerState());
                    placeIfClear(level, midAbove.south(), style.flowerState());
                    placeIfClear(level, midAbove.east(),  style.flowerState());
                    placeIfClear(level, midAbove.west(),  style.flowerState());
                }
                default -> {
                    // Small oak tree or flower patch for residential blocks
                    if (isNaturalTerrain(level.getBlockState(midSurface))
                            || level.getBlockState(midSurface).isAir()) {
                        if ((midX + midZ) % 3 == 0) {
                            // Tiny oak tree
                            level.setBlock(midSurface,
                                    Blocks.OAK_LOG.defaultBlockState(), 18);
                            for (BlockPos leaf : List.of(
                                    midAbove, midAbove.above(),
                                    midAbove.north(), midAbove.south(),
                                    midAbove.east(),  midAbove.west())) {
                                if (level.getBlockState(leaf).isAir()) {
                                    level.setBlock(leaf,
                                            Blocks.OAK_LEAVES.defaultBlockState()
                                                    .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true),
                                            18);
                                }
                            }
                        } else {
                            placeIfClear(level, midAbove, style.flowerState());
                        }
                    }
                }
            }

            // ── Corner benches in larger courtyards ───────────────────────────
            if (cxMax - cxMin >= 8 && czMax - czMin >= 8) {
                for (int[] off : new int[][]{{2,2},{-2,2},{2,-2},{-2,-2}}) {
                    int bx = midX + off[0], bz = midZ + off[1];
                    int by = level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
                    placeIfClear(level, new BlockPos(bx, by + 1, bz),
                            style.woodSlab());
                }
            }
        }
    }

    // =========================================================================
    // Building border transitions (Priority 7)
    // =========================================================================

    /**
     * Places kerb slabs on street-facing building sides and low border
     * walls with planters on courtyard/garden-facing sides.
     */
    public static void placeBuildingTransitions(ServerLevel level,
                                                Building building,
                                                VillageBiomeStyle style,
                                                Set<Long> streetXZ) {
        BlockPos min    = building.getShape().getMin();
        BlockPos max    = building.getShape().getMax();
        int      floorY = building.getShape().getOrigin().getY();

        placeFaceBorder(level, min.getX(), max.getX(),
                min.getZ() - 1, floorY, style, streetXZ, true);
        placeFaceBorder(level, min.getX(), max.getX(),
                max.getZ() + 1, floorY, style, streetXZ, true);
        placeFaceBorderZ(level, min.getZ(), max.getZ(),
                min.getX() - 1, floorY, style, streetXZ);
        placeFaceBorderZ(level, min.getZ(), max.getZ(),
                max.getX() + 1, floorY, style, streetXZ);
    }

    private static void placeFaceBorder(ServerLevel level,
                                        int xMin, int xMax, int fixedZ,
                                        int floorY, VillageBiomeStyle style,
                                        Set<Long> streetXZ,
                                        boolean isNorthSouth) {
        for (int x = xMin; x <= xMax; x++) {
            long key = xzKey(x, fixedZ);
            int surfY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, fixedZ);
            if (streetXZ.contains(key)) {
                if (Math.abs(surfY - floorY) == 1) {
                    BlockPos kerbPos = new BlockPos(x, Math.min(surfY, floorY), fixedZ);
                    if (level.getBlockState(kerbPos).isAir()) {
                        level.setBlock(kerbPos, style.stoneSlab(), 3);
                    }
                }
            } else {
                BlockPos borderPos = new BlockPos(x, surfY, fixedZ);
                if (level.getBlockState(borderPos).isAir()
                        && level.getBlockState(borderPos.below()).isSolidRender()) {
                    if (x % 4 == 0) {
                        level.setBlock(borderPos,
                                Blocks.COMPOSTER.defaultBlockState(), 3);
                        BlockPos plantPos = borderPos.above();
                        if (level.getBlockState(plantPos).isAir()) {
                            level.setBlock(plantPos, style.flowerState(), 3);
                        }
                    } else {
                        level.setBlock(borderPos, style.stoneSlab(), 3);
                    }
                }
            }
        }
    }

    private static void placeFaceBorderZ(ServerLevel level,
                                         int zMin, int zMax, int fixedX,
                                         int floorY, VillageBiomeStyle style,
                                         Set<Long> streetXZ) {
        for (int z = zMin; z <= zMax; z++) {
            long key = xzKey(fixedX, z);
            int surfY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, fixedX, z);
            if (streetXZ.contains(key)) {
                if (Math.abs(surfY - floorY) == 1) {
                    BlockPos kerbPos = new BlockPos(fixedX, Math.min(surfY, floorY), z);
                    if (level.getBlockState(kerbPos).isAir()) {
                        level.setBlock(kerbPos, style.stoneSlab(), 3);
                    }
                }
            } else {
                BlockPos borderPos = new BlockPos(fixedX, surfY, z);
                if (level.getBlockState(borderPos).isAir()
                        && level.getBlockState(borderPos.below()).isSolidRender()) {
                    if (z % 4 == 0) {
                        level.setBlock(borderPos,
                                Blocks.COMPOSTER.defaultBlockState(), 3);
                        BlockPos plantPos = borderPos.above();
                        if (level.getBlockState(plantPos).isAir()) {
                            level.setBlock(plantPos, style.flowerState(), 3);
                        }
                    } else {
                        level.setBlock(borderPos, style.stoneSlab(), 3);
                    }
                }
            }
        }
    }

    // =========================================================================
    // Approach gradient
    // =========================================================================

    private static void placeApproachGradient(ServerLevel level,
                                              Village village,
                                              VillageSavedData data,
                                              VillageBiomeStyle style,
                                              Set<Long> pathXZ) {
        Optional<net.minecraft.world.phys.AABB> boundsOpt = village.getBounds(data);
        if (boundsOpt.isEmpty()) return;

        net.minecraft.world.phys.AABB bounds = boundsOpt.get();
        int cx = (int)((bounds.minX + bounds.maxX) / 2);
        int cz = (int)((bounds.minZ + bounds.maxZ) / 2);
        int outerRadius = (int)(Math.max(
                bounds.maxX - bounds.minX,
                bounds.maxZ - bounds.minZ) / 2);

        Random rng = new Random(village.getName().hashCode() * 17L);
        int wornInner = outerRadius;
        int wornOuter = outerRadius + 8;
        int wildInner = outerRadius + 8;
        int wildOuter = outerRadius + 16;

        for (int dx = -(wornOuter + 4); dx <= wornOuter + 4; dx++) {
            for (int dz = -(wornOuter + 4); dz <= wornOuter + 4; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist < wornInner || dist > wildOuter) continue;
                int wx = cx + dx, wz = cz + dz;
                if (pathXZ.contains(xzKey(wx, wz))) continue;

                int surfY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz);
                BlockPos pos = new BlockPos(wx, surfY, wz);
                BlockState state = level.getBlockState(pos);

                if (dist <= wornOuter && state.is(Blocks.GRASS_BLOCK)) {
                    float chance = (float)(1.0 - (dist - wornInner) / 8.0);
                    if (rng.nextFloat() < chance) {
                        level.setBlock(pos, Blocks.COARSE_DIRT.defaultBlockState(), 3);
                    }
                } else if (dist <= wornOuter && dist > wornInner - 4
                        && state.is(Blocks.GRASS_BLOCK)
                        && rng.nextFloat() < 0.04f) {
                    level.setBlock(pos, Blocks.OAK_LOG.defaultBlockState(), 3);
                } else if (dist > wildInner && dist <= wildOuter
                        && state.is(Blocks.GRASS_BLOCK)
                        && level.getBlockState(pos.above()).isAir()
                        && rng.nextFloat() < 0.15f) {
                    level.setBlock(pos.above(), style.flowerState(), 3);
                }
            }
        }
    }

    // =========================================================================
    // Per-building exterior decorations
    // =========================================================================

    private static void placeExteriorDecorations(ServerLevel level,
                                                 Building building,
                                                 VillageBiomeStyle style,
                                                 VillageSizeTier tier) {
        BlockPos entrance = PathRouter.getBuildingEntrance(building);

        switch (building.getType()) {
            case INN -> {
                placeIfClear(level, entrance.east(),  Blocks.BARREL.defaultBlockState());
                placeHangingLantern(level, entrance, style);
                placeIfClear(level, entrance, Blocks.RED_CARPET.defaultBlockState());
            }
            case MARKET -> placeMarketFront(level, entrance, style);
            case TEMPLE -> {
                placeColumn(level, entrance.east(2), style);
                placeColumn(level, entrance.west(2), style);
                placeIfClear(level, entrance.east(),  Blocks.CANDLE.defaultBlockState());
                placeIfClear(level, entrance.west(),  Blocks.CANDLE.defaultBlockState());
                placeIfClear(level, entrance.east(3), style.flowerState());
                placeIfClear(level, entrance.west(3), style.flowerState());
                placeIfClear(level, entrance.east(4), style.flowerState());
                placeIfClear(level, entrance.west(4), style.flowerState());
            }
            case LIBRARY -> {
                placeIfClear(level, entrance.east(), Blocks.LECTERN.defaultBlockState());
                placeIfClear(level, entrance.west(), Blocks.FLOWER_POT.defaultBlockState());
                placeIfClear(level, entrance.east(2), style.flowerState());
                placeIfClear(level, entrance.west(2), style.flowerState());
                placeHangingLantern(level, entrance, style);
            }
            case BELL_TOWER -> {
                placeIfClear(level, entrance.south(2), Blocks.BELL.defaultBlockState());
                placeIfClear(level, entrance.east(),   style.fenceState());
                placeIfClear(level, entrance.west(),   style.fenceState());
            }
            case BLACKSMITH -> {
                placeIfClear(level, entrance.east(), Blocks.ANVIL.defaultBlockState());
                placeIfClear(level, entrance.west(), Blocks.FURNACE.defaultBlockState());
                placeIfClear(level, entrance.east(2), Blocks.IRON_BLOCK.defaultBlockState());
                placeHangingLantern(level, entrance, style);
            }
            case BAKERY -> {
                placeIfClear(level, entrance.east(), Blocks.BARREL.defaultBlockState());
                placeIfClear(level, entrance.west(), Blocks.COMPOSTER.defaultBlockState());
                placeHangingLantern(level, entrance, style);
            }
            case CARPENTRY -> {
                placeIfClear(level, entrance.east(), Blocks.CRAFTING_TABLE.defaultBlockState());
                placeIfClear(level, entrance.west(), Blocks.BARREL.defaultBlockState());
                placeIfClear(level, entrance.east(2), Blocks.OAK_LOG.defaultBlockState());
            }
            case APOTHECARY -> {
                placeIfClear(level, entrance.east(), Blocks.BREWING_STAND.defaultBlockState());
                placeIfClear(level, entrance.west(), potOf(Blocks.FERN));
                placeIfClear(level, entrance.west(2), potOf(Blocks.POPPY));
                placeIfClear(level, entrance.east(2), potOf(Blocks.DANDELION));
                placeIfClear(level, entrance.west(3), Blocks.CAULDRON.defaultBlockState());
                placeHangingLantern(level, entrance, style);
            }
            case ARMORER -> {
                placeIfClear(level, entrance.east(), Blocks.BLAST_FURNACE.defaultBlockState());
                placeIfClear(level, entrance.west(), Blocks.ANVIL.defaultBlockState());
                placeIfClear(level, entrance.east(2), Blocks.IRON_BLOCK.defaultBlockState());
                placeIfClear(level, entrance.west(2), Blocks.IRON_BLOCK.defaultBlockState());
                placeHangingLantern(level, entrance, style);
            }
            case TOOLSMITH -> {
                placeIfClear(level, entrance.east(), Blocks.SMITHING_TABLE.defaultBlockState());
                placeIfClear(level, entrance.west(), Blocks.ANVIL.defaultBlockState());
                placeIfClear(level, entrance.east(2), Blocks.IRON_BLOCK.defaultBlockState());
                placeIfClear(level, entrance.west(2), Blocks.BARREL.defaultBlockState());
            }
            case WINERY -> {
                placeIfClear(level, entrance.east(),  Blocks.BARREL.defaultBlockState());
                placeIfClear(level, entrance.west(),  Blocks.BARREL.defaultBlockState());
                placeIfClear(level, entrance.east(2), Blocks.BARREL.defaultBlockState());
                placeIfClear(level, entrance.west(2), potOf(Blocks.OAK_SAPLING));
                placeIfClear(level, entrance.east(3), Blocks.COMPOSTER.defaultBlockState());
            }
            case STONEMASON -> {
                placeIfClear(level, entrance.east(), Blocks.STONE_BRICKS.defaultBlockState());
                placeIfClear(level, entrance.west(), Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
                placeIfClear(level, entrance.east(2), Blocks.STONECUTTER.defaultBlockState());
            }
            case STABLE -> {
                placeIfClear(level, entrance.east(2), style.fenceState());
                placeIfClear(level, entrance.west(2), style.fenceState());
            }
            case GUILD_HALL -> {
                placeColumn(level, entrance.east(3), style);
                placeColumn(level, entrance.west(3), style);
                placeIfClear(level, entrance.east(2), Blocks.LANTERN.defaultBlockState());
                placeIfClear(level, entrance.west(2), Blocks.LANTERN.defaultBlockState());
            }
            case NOBLE_MANOR -> {
                placeIfClear(level, entrance.east(3), style.flowerState());
                placeIfClear(level, entrance.west(3), style.flowerState());
                placeIfClear(level, entrance.east(4), style.flowerState());
                placeIfClear(level, entrance.west(4), style.flowerState());
                placeHangingLantern(level, entrance, style);
            }
            case BARRACKS -> {
                BlockPos rack = findClearSurface(level, entrance.east(4));
                placeIfClear(level, rack,        style.fenceState());
                placeIfClear(level, rack.east(), style.fenceState());
                placeIfClear(level, rack.above(), style.stoneSlab());
            }
            case GUARD_TOWER, WATCHTOWER -> {
                placePost(level, entrance.east(2).above(), style);
                placePost(level, entrance.west(2).above(), style);
            }
            case PRISON -> {
                BlockPos stocksBase = findClearSurface(level, entrance.south(4));
                placeIfClear(level, stocksBase,              style.fenceState());
                placeIfClear(level, stocksBase.east(),       style.fenceState());
                placeIfClear(level, stocksBase.above(),      style.woodSlab());
                placeIfClear(level, stocksBase.east().above(), style.woodSlab());
                placeIfClear(level, stocksBase.west(),       Blocks.OAK_SIGN.defaultBlockState());
            }
            default -> {}
        }
    }

    // =========================================================================
    // Street furniture
    // =========================================================================

    private static void placeStreetFurniture(ServerLevel level,
                                             StreetNetwork network,
                                             VillageBiomeStyle style,
                                             VillageSizeTier tier) {
        for (StreetNetwork.Node node : network.getAllNodes()) {
            if (node.buildingId != null) continue;
            int edgeCount = node.getEdges().size();
            if (edgeCount < 2) continue;

            int surfY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    node.pos.getX(), node.pos.getZ());
            BlockPos surface = new BlockPos(
                    node.pos.getX(), surfY + 1, node.pos.getZ());

            if (edgeCount >= 3) {
                placeJunctionFeature(level, surface, style, tier, node);
            } else {
                placeStreetBench(level, surface, style);
            }
        }

        for (StreetNetwork.Edge edge : network.getAllEdges()) {
            if (edge.tier == StreetTier.PRIMARY) continue;
            if (edge.blocks.size() < 20) continue;
            int mid = edge.blocks.size() / 2;
            placeStreetBench(level, edge.blocks.get(mid).above().east(), style);
        }
    }

    private static void placeJunctionFeature(ServerLevel level,
                                             BlockPos surface,
                                             VillageBiomeStyle style,
                                             VillageSizeTier tier,
                                             StreetNetwork.Node node) {
        int variant = (Math.abs(surface.getX() + surface.getZ())) % 3;
        switch (variant) {
            case 0 -> {
                placeIfClear(level, surface, style.fenceState());
                placeIfClear(level, surface.above(), Blocks.LECTERN.defaultBlockState());
            }
            case 1 -> {
                placeIfClear(level, surface, Blocks.COMPOSTER.defaultBlockState());
                placeIfClear(level, surface.above(), style.flowerState());
            }
            case 2 -> {
                placeIfClear(level, surface,         style.stoneSlab());
                placeIfClear(level, surface.east(),  style.stoneSlab());
            }
        }
    }

    private static void placeStreetBench(ServerLevel level,
                                         BlockPos surface,
                                         VillageBiomeStyle style) {
        if (!level.getBlockState(surface.below()).isSolidRender()) return;
        if (!level.getBlockState(surface).isAir()) return;
        level.setBlock(surface, style.woodSlab(), 3);
    }

    // =========================================================================
    // Proximity furniture (type-specific outdoor props)
    // =========================================================================

    private static void placeProximityFurniture(ServerLevel level,
                                                List<Building> buildings,
                                                VillageBiomeStyle style) {
        for (Building building : buildings) {
            BlockPos entrance = PathRouter.getBuildingEntrance(building);

            switch (building.getType()) {
                case STABLE -> {
                    BlockPos trough = findClearSurface(level, entrance.south(4));
                    placeIfClear(level, trough, Blocks.CAULDRON.defaultBlockState());
                    placeIfClear(level, trough.east(2), style.fenceState());
                    placeIfClear(level, trough.west(2), style.fenceState());
                }
                case PRISON -> {
                    BlockPos stocks = findClearSurface(level, entrance.south(4));
                    placeIfClear(level, stocks,              style.fenceState());
                    placeIfClear(level, stocks.east(),       style.fenceState());
                    placeIfClear(level, stocks.above(),      style.woodSlab());
                    placeIfClear(level, stocks.east().above(), style.woodSlab());
                    placeIfClear(level, stocks.west(), Blocks.OAK_SIGN.defaultBlockState());
                }
                case TEMPLE -> {
                    for (int step = 2; step <= 6; step += 2) {
                        BlockPos left  = findClearSurface(level, entrance.south(step).east(2));
                        BlockPos right = findClearSurface(level, entrance.south(step).west(2));
                        if (step == 2) {
                            placeIfClear(level, left,  Blocks.CAULDRON.defaultBlockState());
                            placeIfClear(level, right, Blocks.CAULDRON.defaultBlockState());
                        } else {
                            placeIfClear(level, left,  style.fenceState());
                            placeIfClear(level, right, style.fenceState());
                            placeIfClear(level, left.above(),  style.lanternState());
                            placeIfClear(level, right.above(), style.lanternState());
                        }
                    }
                }
                case LIBRARY -> {
                    BlockPos bench = findClearSurface(level, entrance.east(3).south(2));
                    placeIfClear(level, bench,       style.woodSlab());
                    placeIfClear(level, bench.east(), style.woodSlab());
                    placeIfClear(level, bench.above(), Blocks.CANDLE.defaultBlockState());
                }
                case BARRACKS -> {
                    BlockPos rack = findClearSurface(level, entrance.east(4));
                    placeIfClear(level, rack,        style.fenceState());
                    placeIfClear(level, rack.east(), style.fenceState());
                    placeIfClear(level, rack.above(), style.stoneSlab());
                }
                default -> {}
            }
        }
    }

    // =========================================================================
    // Market stalls
    // =========================================================================

    private static void placeMarketStalls(ServerLevel level,
                                          BlockPos squareCenter,
                                          VillageBiomeStyle style) {
        int stallDist = 13;
        int[][] positions = {{stallDist,0},{-stallDist,0},{0,stallDist},{0,-stallDist}};
        for (int[] off : positions) {
            BlockPos stallBase = findClearSurface(level,
                    squareCenter.offset(off[0], 0, off[1]));
            if (!level.getBlockState(stallBase.below()).isSolidRender()) continue;
            // Awning post
            level.setBlock(stallBase.above(),         style.fenceState(),   3);
            level.setBlock(stallBase.above(2),        style.stoneSlab(),    3);
            // Stall contents
            level.setBlock(stallBase,                 Blocks.BARREL.defaultBlockState(), 3);
            level.setBlock(stallBase.east(),          Blocks.BARREL.defaultBlockState(), 3);
            // Carpet display
            level.setBlock(stallBase.above().east(),  Blocks.YELLOW_CARPET.defaultBlockState(), 3);
            level.setBlock(stallBase.above().west(),  Blocks.YELLOW_CARPET.defaultBlockState(), 3);
        }
    }

    private static void placeMarketFront(ServerLevel level,
                                         BlockPos entrance,
                                         VillageBiomeStyle style) {
        placeIfClear(level, entrance,        Blocks.YELLOW_CARPET.defaultBlockState());
        placeIfClear(level, entrance.east(),  Blocks.YELLOW_CARPET.defaultBlockState());
        placeIfClear(level, entrance.west(),  Blocks.YELLOW_CARPET.defaultBlockState());
        placeIfClear(level, entrance.above(), Blocks.BARREL.defaultBlockState());
        placeIfClear(level, entrance.east(2), style.fenceState());
        placeIfClear(level, entrance.west(2), style.fenceState());
        placeIfClear(level, entrance.east(2).above(), style.stoneSlab());
        placeIfClear(level, entrance.west(2).above(), style.stoneSlab());
        placeIfClear(level, entrance.east().above(),  style.stoneSlab());
        placeIfClear(level, entrance.west().above(),  style.stoneSlab());
        placeIfClear(level, entrance.above(2),        style.stoneSlab());
    }

    // =========================================================================
    // Landmark
    // =========================================================================

    private static void placeLandmark(ServerLevel level,
                                      Building townHall,
                                      VillageBiomeStyle style,
                                      Village village) {
        BlockPos base = PathRouter.getBuildingEntrance(townHall).above(2);
        int surfY = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                base.getX(), base.getZ());
        BlockPos pole = new BlockPos(base.getX(), surfY + 1, base.getZ());
        if (!level.getBlockState(pole.below()).isSolidRender()) return;

        for (BlockPos ring : List.of(
                pole, pole.north(), pole.south(), pole.east(), pole.west())) {
            int dx = ring.getX() - pole.getX();
            int dz = ring.getZ() - pole.getZ();
            if (dx == 0 && dz == 0) {
                level.setBlock(ring, style.stoneWallState(), 3);
                level.setBlock(ring.above(), style.stoneWallState(), 3);
            } else if (Math.abs(dx) + Math.abs(dz) == 1) {
                level.setBlock(ring, style.stone.defaultBlockState(), 3);
                level.setBlock(ring.above(), style.fenceState(), 3);
            } else {
                level.setBlock(ring, style.stoneSlab(), 3);
            }
        }
        level.setBlock(base.above(2), style.lanternState(), 3);
    }

    // =========================================================================
    // Flower scatter
    // =========================================================================

    private static void scatterFlowers(ServerLevel level,
                                       Village village,
                                       VillageSavedData data,
                                       VillageBiomeStyle style,
                                       int attempts) {
        village.getBounds(data).ifPresent(bounds -> {
            Random rng = new Random(village.getName().hashCode());
            int cx = (int)((bounds.minX + bounds.maxX) / 2);
            int cz = (int)((bounds.minZ + bounds.maxZ) / 2);
            int rx = Math.max(1, (int)((bounds.maxX - bounds.minX) / 2));
            int rz = Math.max(1, (int)((bounds.maxZ - bounds.minZ) / 2));
            for (int i = 0; i < attempts; i++) {
                int fx = cx + rng.nextInt(rx * 2) - rx;
                int fz = cz + rng.nextInt(rz * 2) - rz;
                BlockPos pos = findSurface(level, new BlockPos(fx, 64, fz));
                if (level.getBlockState(pos.below()).is(Blocks.GRASS_BLOCK)
                        && level.getBlockState(pos).isAir()) {
                    level.setBlock(pos, style.flowerState(), 3);
                }
            }
        });
    }

    // =========================================================================
    // Ground smoothing
    // =========================================================================

    public static void smoothUnderBuilding(ServerLevel level, Building building) {
        BlockPos min         = building.getShape().getMin();
        BlockPos max         = building.getShape().getMax();
        int      targetSolid = building.getShape().getOrigin().getY() - 1;
        int      border      = 2;

        for (int x = min.getX() - border; x <= max.getX() + border; x++) {
            for (int z = min.getZ() - border; z <= max.getZ() + border; z++) {
                int surfY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                if (surfY == targetSolid) continue;
                if (surfY < targetSolid) {
                    for (int y = surfY; y <= targetSolid; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState s = level.getBlockState(pos);
                        if (s.isAir() || s.is(BlockTags.REPLACEABLE) || s.liquid()) {
                            level.setBlock(pos,
                                    y == targetSolid
                                            ? Blocks.GRASS_BLOCK.defaultBlockState()
                                            : Blocks.DIRT.defaultBlockState(), 3);
                        }
                    }
                } else {
                    for (int y = surfY; y > targetSolid; y--) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState s = level.getBlockState(pos);
                        if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT)
                                || s.is(Blocks.STONE) || s.is(Blocks.DEEPSLATE)
                                || s.is(BlockTags.REPLACEABLE)
                                || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
                        } else break;
                    }
                }
            }
        }
    }

    // =========================================================================
    // Expansion building decoration (called from BuilderGoal)
    // =========================================================================

    public static void decorateExpansionBuilding(ServerLevel level,
                                                 Building building,
                                                 Village village,
                                                 VillageSavedData data) {
        if (building.getType() == BuildingType.TOWN_SQUARE) return;
        VillageBiomeStyle style = VillageBiomeStyle.detect(
                level, building.getShape().getOrigin());
        VillageSizeTier tier = VillageSizeTier.fromBuildingCount(
                village.getBuildingIds().size());

        Set<Long> pathXZ = new HashSet<>();
        data.getPathsForVillage(village.getId()).forEach(p ->
                p.getBlocks().forEach(b ->
                        pathXZ.add(xzKey(b.getX(), b.getZ()))));

        BuildingFoundation.place(level, building);
        smoothUnderBuilding(level, building);
        placeBuildingTransitions(level, building, style, pathXZ);
        placeExteriorDecorations(level, building, style, tier);
        VillageWeathering.weather(level, village, data, style);
        System.out.println("VillageDecorator: decorated expansion building "
                + building.getType() + " at "
                + building.getShape().getOrigin().toShortString());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static BlockPos resolveSquareCenter(ServerLevel level,
                                                VillageLayout layout,
                                                List<Building> buildings) {
        if (layout != null && layout.getTownSquarePos() != null) {
            return layout.getTownSquarePos();
        }
        return deriveSquareCenterFromBuildings(level, buildings);
    }

    private static BlockPos deriveSquareCenterFromBuildings(ServerLevel level,
                                                            List<Building> buildings) {
        Optional<Building> townHall = findTownHall(buildings);
        int sumX = 0, sumZ = 0;
        for (Building b : buildings) {
            sumX += b.getShape().getOrigin().getX() + b.getShape().getWidth()  / 2;
            sumZ += b.getShape().getOrigin().getZ() + b.getShape().getLength() / 2;
        }
        int cx = sumX / buildings.size(), cz = sumZ / buildings.size();
        if (townHall.isPresent()) {
            BlockPos th = townHall.get().getShape().getOrigin();
            cx = (cx + th.getX() + townHall.get().getShape().getWidth()  / 2) / 2;
            cz = (cz + th.getZ() + townHall.get().getShape().getLength() / 2) / 2;
        }
        return findSurface(level, new BlockPos(cx, 64, cz));
    }

    private static Optional<Building> findTownHall(List<Building> buildings) {
        return buildings.stream()
                .filter(b -> b.getType() == BuildingType.TOWN_HALL)
                .findFirst();
    }

    static BlockPos findSurface(ServerLevel level, BlockPos pos) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                pos.getX(), pos.getZ());
        return new BlockPos(pos.getX(), y, pos.getZ());
    }

    private static BlockPos findClearSurface(ServerLevel level, BlockPos pos) {
        BlockPos surface = findSurface(level, pos);
        if (level.getBlockState(surface).isAir()) return surface;
        return surface.above();
    }

    private static Set<Long> collectBuildingXZ(List<Building> buildings) {
        Set<Long> set = new HashSet<>();
        for (Building b : buildings) {
            BlockPos min = b.getShape().getMin(), max = b.getShape().getMax();
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    set.add(xzKey(x, z));
                }
            }
        }
        return set;
    }

    static void placeIfClear(ServerLevel level, BlockPos pos, BlockState state) {
        if (level.getBlockState(pos.below()).isSolidRender()
                && level.getBlockState(pos).isAir()) {
            level.setBlock(pos, state, 3);
        }
    }

    private static void clearColumn(ServerLevel level, int x, int z, int nearY) {
        for (int y = nearY + 20; y >= nearY - 2; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(pos);
            if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES)
                    || s.is(BlockTags.REPLACEABLE) || s.is(BlockTags.FLOWERS)
                    || s.is(BlockTags.SMALL_FLOWERS)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private static boolean isNaturalTerrain(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT)
                || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.SAND)
                || s.is(Blocks.GRAVEL)       || s.is(BlockTags.REPLACEABLE)
                || s.isAir();
    }

    private static void placePost(ServerLevel level, BlockPos surface,
                                  VillageBiomeStyle style) {
        if (!level.getBlockState(surface.below()).isSolidRender()) return;
        if (!level.getBlockState(surface).isAir()) return;
        level.setBlock(surface,         style.fenceState(),   3);
        level.setBlock(surface.above(), style.lanternState(), 3);
    }

    private static void placeColumn(ServerLevel level, BlockPos surface,
                                    VillageBiomeStyle style) {
        if (!level.getBlockState(surface.below()).isSolidRender()) return;
        level.setBlock(surface,         style.stone.defaultBlockState(), 3);
        level.setBlock(surface.above(), style.stone.defaultBlockState(), 3);
    }

    private static void placeHangingLantern(ServerLevel level, BlockPos entrance,
                                            VillageBiomeStyle style) {
        BlockPos hangPos = entrance.above(2);
        if (level.getBlockState(hangPos).isAir()) {
            level.setBlock(hangPos, style.lanternState(), 3);
        }
    }

    private static BlockState potOf(net.minecraft.world.level.block.Block flower) {
        return Blocks.FLOWER_POT.defaultBlockState();
    }

    public static long xzKey(int x, int z) {
        return ((long)(x + 30_000_000)) << 32 | (z + 30_000_000);
    }
}