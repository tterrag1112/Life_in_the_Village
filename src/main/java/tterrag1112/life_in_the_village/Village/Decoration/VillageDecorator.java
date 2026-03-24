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
 * <h3>Pass order</h3>
 * <ol>
 *   <li>Building foundations (stilts / retaining walls)</li>
 *   <li>Tight ground smoothing under each building</li>
 *   <li>Town square construction</li>
 *   <li>Hierarchical street network (PRIMARY → SECONDARY → TERTIARY)</li>
 *   <li>Hollow ground fill under all paths</li>
 *   <li>Village perimeter (palisade / wall)</li>
 *   <li>Building border transitions (low walls, planters, path edges)</li>
 *   <li>Per-building exterior decorations (profession-readable props)</li>
 *   <li>Street furniture (benches, notice boards at junctions)</li>
 *   <li>Market stalls (town+ tier only)</li>
 *   <li>Landmark (town+ tier only)</li>
 *   <li>Flower scatter</li>
 * </ol>
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

        BlockPos firstOrigin = buildings.get(0).getShape().getOrigin();
        VillageBiomeStyle style = VillageBiomeStyle.detect(level, firstOrigin);
        VillageSizeTier   tier  = VillageSizeTier
                .fromBuildingCount(buildings.size());

        System.out.println("VillageDecorator: decorating '"
                + village.getName() + "' as " + tier.displayName
                + " in " + style.name() + " style ("
                + buildings.size() + " buildings)");

        // ── Step 1: Foundations ───────────────────────────────────────────────
        buildings.stream()
                .filter(b -> b.getType() != BuildingType.TOWN_SQUARE)
                .forEach(b -> BuildingFoundation.place(level, b));

        // ── Step 2: Ground smoothing ──────────────────────────────────────────
        buildings.stream()
                .filter(b -> b.getType() != BuildingType.TOWN_SQUARE)
                .forEach(b -> smoothUnderBuilding(level, b));

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
        // Must happen AFTER streets so we know which faces are on a street
      /*  buildings.stream()
                .filter(b -> b.getType() != BuildingType.TOWN_SQUARE)
                .forEach(b -> placeBuildingTransitions(level, b, style, allPathXZ));
        // ── Step 7b: Approach gradient ────────────────────────────────────────
        placeApproachGradient(level, village, data, style, allPathXZ);

       */

        // ── Step 8: Per-building exterior decorations ─────────────────────────
        /*buildings.stream()
                .filter(b -> b.getType() != BuildingType.TOWN_SQUARE)
                .forEach(b -> placeExteriorDecorations(level, b, style, tier));

         */

        // ── Step 9: Street furniture ──────────────────────────────────────────
        //placeStreetFurniture(level, network, style, tier);

        // ── Step 9b: Proximity furniture ──────────────────────────────────────
       // placeProximityFurniture(level, buildings, style);

        // ── Step 10: Market stalls ────────────────────────────────────────────
        if (tier.hasMarket) {
            placeMarketStalls(level, squareCenter, style);
        }

        // ── Step 11: Landmark ─────────────────────────────────────────────────
        if (tier.hasLandmark) {
            findTownHall(buildings).ifPresent(th ->
                    placeLandmark(level, th, style, village));
        }

        // ── Step 12: Flowers ──────────────────────────────────────────────────
        if (tier.flowerAttempts > 0) {
            scatterFlowers(level, village, data, style, tier.flowerAttempts);
        }
        // ── Step 13: Weathering ───────────────────────────────────────────────
        VillageWeathering.weather(level, village, data, style);
        // ── Step 14: Guard patrol route ───────────────────────────────────────
        VillagePatrolRouteBuilder.build(level, village, data);
        // ── Step 15: Lighting pass ────────────────────────────────────────────
       // VillageLightingPass.light(level, village, data, style);
    }

    // =========================================================================
    // Street network
    // =========================================================================

    private static StreetNetwork buildStreetNetwork(
            ServerLevel level, List<Building> buildings,
            BlockPos squareCenter, VillageBiomeStyle style,
            VillageSizeTier tier, Set<Long> protectedXZ,
            VillageSavedData data, Village village, VillageLayout layout) {


        if (layout.hasCapitalStreetGraph()) {
            return buildCapitalStreetNetwork(
                    level, layout.getCapitalStreetGraph(),
                    buildings, squareCenter, style, data, village, protectedXZ);
        }

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

            int routerBudget = village.getBuildingIds().size() >= LayoutDensityProfile.CAPITAL_THRESHOLD
                    ? 800_000
                    : 500_000;
            List<BlockPos> route = RoadRouter.findRoad(level, squareCenter, entrance, routerBudget);

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
                    streetTier == StreetTier.PRIMARY
                            ? VillagePath.PathTier.COBBLESTONE
                            : streetTier == StreetTier.SECONDARY
                            ? VillagePath.PathTier.GRAVEL
                            : VillagePath.PathTier.DIRT));

            System.out.println("VillageDecorator: " + streetTier.name()
                    + " street to " + building.getType()
                    + " (" + placed.size() + " blocks)");
        }

        return network;
    }

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

        // Convert graph nodes to StreetNetwork nodes
        Map<UUID, StreetNetwork.Node> nodeMap = new HashMap<>();
        for (var gNode : graph.getNodes()) {
            int surfY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    gNode.x, gNode.z);
            BlockPos pos = new BlockPos(gNode.x, surfY, gNode.z);
            StreetTier tier = convertTier(gNode.tier);
            StreetNetwork.Node sNode = network.addNode(pos, tier, null);
            nodeMap.put(gNode.id, sNode);
        }

        // Place each street segment between its two nodes
        for (var seg : graph.getSegments()) {
            StreetNetwork.Node sA = nodeMap.get(seg.a.id);
            StreetNetwork.Node sB = nodeMap.get(seg.b.id);
            if (sA == null || sB == null) continue;

            StreetTier tier = convertTier(seg.tier);

            // Use RoadRouter to place actual blocks between node positions
            List<BlockPos> route = RoadRouter.findRoad(
                    level, sA.pos, sB.pos, 800_000);

            if (route.isEmpty()) continue;

            List<BlockPos> placed = placeStreetSegment(
                    level, route, style, tier, protectedXZ);
            if (placed.isEmpty()) continue;

            network.connect(sA, sB, tier, placed);
            placed.forEach(p -> protectedXZ.add(xzKey(p.getX(), p.getZ())));

            data.addVillagePath(new VillagePath(
                    UUID.randomUUID(), village.getId(), placed,
                    tier == StreetTier.PRIMARY
                            ? VillagePath.PathTier.COBBLESTONE
                            : tier == StreetTier.SECONDARY
                            ? VillagePath.PathTier.GRAVEL
                            : VillagePath.PathTier.DIRT));
        }

        // After streets are placed, connect building entrances to nearest street node
        // (short spur roads only — the street grid is already there)
        for (Building building : buildings) {
            if (building.getType() == BuildingType.TOWN_SQUARE) continue;
            BlockPos entrance = PathRouter.getBuildingEntrance(building);

            // Find nearest street node within 40 blocks
            StreetNetwork.Node nearest = network.nearestNodeOfTier(
                    entrance, StreetTier.TERTIARY);

            if (nearest == null || nearest.pos.distSqr(entrance) > 40 * 40) continue;
            if (nearest.pos.distSqr(entrance) < 4) continue; // already on street

            List<BlockPos> spur = RoadRouter.findRoad(
                    level, nearest.pos, entrance, 50_000);
            if (spur.isEmpty()) continue;

            ZoneRegistry.ZoneEntry zone = ZoneRegistry.get(building.getType());
            StreetTier spurTier = zone.preferredStreet();

            List<BlockPos> spurPlaced = placeStreetSegment(
                    level, spur, style, spurTier, protectedXZ);
            if (spurPlaced.isEmpty()) continue;

            StreetNetwork.Node entryNode = network.addNode(entrance, spurTier, building.getId());
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

                // Verify the block at surfY is actually solid (not air left
                // by a clearing pass that invalidated the heightmap cache).
                // Walk down until we find solid ground.
                BlockPos pathBlock = new BlockPos(wx, surfY, wz);
                for (int drop = 0; drop < 4; drop++) {
                    BlockState check = level.getBlockState(pathBlock);
                    if (!check.isAir() && !check.is(BlockTags.REPLACEABLE)
                            && !check.is(BlockTags.LEAVES)
                            && !check.is(BlockTags.LOGS)) {
                        break; // found solid ground
                    }
                    pathBlock = pathBlock.below();
                }

                if (isHardBlock(level.getBlockState(pathBlock))) continue;
                // Also skip if this column is protected (building or square)
                if (protectedXZ.contains(xzKey(wx, wz))) continue;

                level.setBlock(pathBlock, style.streetStateFor(tier), 3);

                // Clear vegetation on the block above the new path surface
                BlockPos above = pathBlock.above();
                BlockState aboveState = level.getBlockState(above);
                if (!aboveState.isAir()
                        && (aboveState.is(BlockTags.REPLACEABLE)
                        || aboveState.is(BlockTags.FLOWERS)
                        || aboveState.is(BlockTags.SMALL_FLOWERS)
                        || aboveState.is(BlockTags.LEAVES)
                        || aboveState.is(BlockTags.LOGS))) {
                    level.setBlock(above, Blocks.AIR.defaultBlockState(), 3);
                }

                // Kerb on primary street edges — slab sits ON the path block,
                // which is already at surface level, so kerb goes one above
                if (tier == StreetTier.PRIMARY
                        && Math.abs(offset) == tier.halfWidth) {
                    if (level.getBlockState(above).isAir()) {
                        level.setBlock(above, style.primaryStreetSlabState(), 3);
                    }
                }

                // Convention: placed list stores the AIR block above the path
                // surface so lamppost/slab-transition logic has a consistent
                // reference point (same as RoadRouter)
                placed.add(above);
            }
        }

        placeSlabTransitions(level, placed, style);
        placeLamppostsForTier(level, placed, style, tier);
        return placed;
    }

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
    // Step 7: Building border transitions
    // =========================================================================

    /**
     * Places the micro-detail elements at the seam between a building
     * and the surrounding terrain. Each face of the building gets:
     * <ul>
     *   <li><b>Street-facing faces</b>: stone slab kerb flush with the
     *       street level, potted plants or barrels flanking the entrance</li>
     *   <li><b>Garden-facing faces</b> (not on a street): low stone border
     *       wall (1 block tall) with flower/herb plantings in the gaps</li>
     *   <li><b>All faces</b>: clear any floating grass or vegetation that
     *       sits against the building wall</li>
     * </ul>
     */
    public static void placeBuildingTransitions(ServerLevel level,
                                                Building building,
                                                VillageBiomeStyle style,
                                                Set<Long> streetXZ) {
        BlockPos min    = building.getShape().getMin();
        BlockPos max    = building.getShape().getMax();
        int      floorY = building.getShape().getOrigin().getY();

        // North face (min Z)
        placeFaceBorder(level, min.getX(), max.getX(),
                min.getZ() - 1, floorY, style, streetXZ, true);
        // South face (max Z)
        placeFaceBorder(level, min.getX(), max.getX(),
                max.getZ() + 1, floorY, style, streetXZ, true);
        // West face (min X)
        placeFaceBorderZ(level, min.getZ(), max.getZ(),
                min.getX() - 1, floorY, style, streetXZ);
        // East face (max X)
        placeFaceBorderZ(level, min.getZ(), max.getZ(),
                max.getX() + 1, floorY, style, streetXZ);
    }

    /**
     * Places the border treatment for one X-axis face of a building
     * (north or south wall).
     */
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
                // Street-facing: place kerb slab at floor level if there's a step
                if (Math.abs(surfY - floorY) == 1) {
                    BlockPos kerbPos = new BlockPos(x, Math.min(surfY, floorY), fixedZ);
                    if (level.getBlockState(kerbPos).isAir()) {
                        level.setBlock(kerbPos, style.stoneSlab(), 3);
                    }
                }
            } else {
                // Garden-facing: low border wall with occasional planters
                BlockPos borderPos = new BlockPos(x, surfY, fixedZ);
                if (level.getBlockState(borderPos).isAir()
                        && level.getBlockState(borderPos.below()).isSolidRender()) {
                    boolean isPlanter = (x % 4 == 0); // every 4th block is a planter
                    if (isPlanter) {
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

    /** Z-axis face variant (east/west walls). */
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
                    BlockPos kerbPos = new BlockPos(fixedX,
                            Math.min(surfY, floorY), z);
                    if (level.getBlockState(kerbPos).isAir()) {
                        level.setBlock(kerbPos, style.stoneSlab(), 3);
                    }
                }
            } else {
                BlockPos borderPos = new BlockPos(fixedX, surfY, z);
                if (level.getBlockState(borderPos).isAir()
                        && level.getBlockState(borderPos.below()).isSolidRender()) {
                    boolean isPlanter = (z % 4 == 0);
                    if (isPlanter) {
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
    // Step 8: Per-building exterior decorations
    // =========================================================================

    /**
     * Places profession-readable props outside each building.
     * Every building type gets a distinct exterior that immediately
     * communicates its function — a player should be able to identify
     * the building from the outside before seeing a name tag.
     *
     * Convention: {@code entrance} is the air block directly in front
     * of the door. {@code entrance.east()} / {@code .west()} are flanking
     * positions. {@code entrance.south(n)} goes further from the building.
     * {@code entrance.above()} is above the door frame.
     */
    public static void placeExteriorDecorations(ServerLevel level,
                                                Building building,
                                                VillageBiomeStyle style,
                                                VillageSizeTier tier) {
        BlockPos entrance = PathRouter.getBuildingEntrance(building);

        switch (building.getType()) {

            // ── Civic ──────────────────────────────────────────────────────────

            case TOWN_HALL -> {
                // Flag posts flanking the entrance
                placePost(level, entrance.east(2), style);
                placePost(level, entrance.west(2), style);
                // Flower beds along the front
                placeIfClear(level, entrance.east(3),  style.flowerState());
                placeIfClear(level, entrance.west(3),  style.flowerState());
                placeIfClear(level, entrance.east(4),  style.flowerState());
                placeIfClear(level, entrance.west(4),  style.flowerState());
                // Notice board (lectern) to one side
                placeIfClear(level, entrance.east(3).above(),
                        Blocks.LECTERN.defaultBlockState());
            }

            case GUILD_HALL -> {
                // Armor stand display (symbolises the guild)
                placeIfClear(level, entrance.east(),
                        Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
                placeIfClear(level, entrance.west(),
                        Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
                // Barrels of supplies
                placeIfClear(level, entrance.east(2), Blocks.BARREL.defaultBlockState());
                placeIfClear(level, entrance.west(2), Blocks.BARREL.defaultBlockState());
                // Hanging lantern above entrance
                placeHangingLantern(level, entrance, style);
                // Notice board
                placeIfClear(level, entrance.east(3),
                        Blocks.LECTERN.defaultBlockState());
            }

            case INN -> {
                // Welcoming flower pots flanking the door
                placeIfClear(level, entrance.east(),  potOf(style.flower));
                placeIfClear(level, entrance.west(),  potOf(style.flower));
                // Bench for waiting
                placeIfClear(level, entrance.east(2), style.woodSlab());
                placeIfClear(level, entrance.east(3), style.woodSlab());
                // Barrel of ale/supplies beside the door
                placeIfClear(level, entrance.west(2), Blocks.BARREL.defaultBlockState());
                // Hanging sign post above entrance
                placeHangingLantern(level, entrance, style);
                // Carpet welcome mat
                placeIfClear(level, entrance,
                        Blocks.RED_CARPET.defaultBlockState());
            }

            case MARKET -> {
                // Market always has stalls extending outward
                placeMarketFront(level, entrance, style);
            }

            case TEMPLE -> {
                // Stone pillar columns flanking entrance
                placeColumn(level, entrance.east(2),  style);
                placeColumn(level, entrance.west(2),  style);
                // Candle offerings (soul sand + candle or just lanterns)
                placeIfClear(level, entrance.east(),  Blocks.CANDLE.defaultBlockState());
                placeIfClear(level, entrance.west(),  Blocks.CANDLE.defaultBlockState());
                // Flower offerings on the ground
                placeIfClear(level, entrance.east(3), style.flowerState());
                placeIfClear(level, entrance.west(3), style.flowerState());
                placeIfClear(level, entrance.east(4), style.flowerState());
                placeIfClear(level, entrance.west(4), style.flowerState());
            }

            case LIBRARY -> {
                // Bookshelves visible from outside (near windows — use as props)
                placeIfClear(level, entrance.east(),
                        Blocks.LECTERN.defaultBlockState());
                placeIfClear(level, entrance.west(),
                        Blocks.FLOWER_POT.defaultBlockState());
                // Small garden
                placeIfClear(level, entrance.east(2), style.flowerState());
                placeIfClear(level, entrance.west(2), style.flowerState());
                placeHangingLantern(level, entrance, style);
            }

            case BELL_TOWER -> {
                // Bell on a post in front
                placeIfClear(level, entrance.south(2), Blocks.BELL.defaultBlockState());
                placeIfClear(level, entrance.east(),   style.fenceState());
                placeIfClear(level, entrance.west(),   style.fenceState());
            }

            // ── Production ────────────────────────────────────────────────────

            case BLACKSMITH -> {
                // Working tools and materials visible outside
                placeIfClear(level, entrance.east(),
                        Blocks.ANVIL.defaultBlockState());
                placeIfClear(level, entrance.west(),
                        Blocks.BLAST_FURNACE.defaultBlockState());
                // Coal pile (coal blocks stacked 1 high)
                placeIfClear(level, entrance.east(2),
                        Blocks.COAL_BLOCK.defaultBlockState());
                // Water trough (cauldron)
                placeIfClear(level, entrance.west(2),
                        Blocks.CAULDRON.defaultBlockState());
                // Grindstone for sharpening
                placeIfClear(level, entrance.east(3),
                        Blocks.GRINDSTONE.defaultBlockState());
                // Smithing table outside for visual reference
                placeIfClear(level, entrance.west(3),
                        Blocks.SMITHING_TABLE.defaultBlockState());
            }

            case CARPENTRY -> {
                // Log pile outside (3 logs side by side)
                placeIfClear(level, entrance.east(),
                        Blocks.OAK_LOG.defaultBlockState());
                placeIfClear(level, entrance.east(2),
                        Blocks.OAK_LOG.defaultBlockState());
                placeIfClear(level, entrance.east(3),
                        Blocks.OAK_LOG.defaultBlockState());
                // Crafting table and sawdust (sand)
                placeIfClear(level, entrance.west(),
                        Blocks.CRAFTING_TABLE.defaultBlockState());
                placeIfClear(level, entrance.west(2),
                        Blocks.SAND.defaultBlockState()); // sawdust
                // Barrel of nails/tools
                placeIfClear(level, entrance.west(3),
                        Blocks.BARREL.defaultBlockState());
                // Stripped log for contrast
                placeIfClear(level, entrance.south(3),
                        Blocks.STRIPPED_OAK_LOG.defaultBlockState());
            }

            case BAKERY -> {
                // Smoker (oven) outside
                placeIfClear(level, entrance.east(),
                        Blocks.SMOKER.defaultBlockState());
                // Barrel of flour/grain
                placeIfClear(level, entrance.west(),
                        Blocks.BARREL.defaultBlockState());
                // Wheat display
                placeIfClear(level, entrance.east(2),
                        Blocks.HAY_BLOCK.defaultBlockState());
                // Flower pots (herbs)
                placeIfClear(level, entrance.west(2), potOf(Blocks.POPPY));
                placeIfClear(level, entrance.west(3), potOf(Blocks.DANDELION));
                // Welcome carpet
                placeIfClear(level, entrance, Blocks.WHITE_CARPET.defaultBlockState());
            }

            case MILLER -> {
                // Millstone representation (grindstone)
                placeIfClear(level, entrance.east(),
                        Blocks.GRINDSTONE.defaultBlockState());
                // Wheat sacks (hay bales)
                placeIfClear(level, entrance.west(),
                        Blocks.HAY_BLOCK.defaultBlockState());
                placeIfClear(level, entrance.west(2),
                        Blocks.HAY_BLOCK.defaultBlockState());
                // Barrel of flour
                placeIfClear(level, entrance.east(2),
                        Blocks.BARREL.defaultBlockState());
            }

            case STONEMASON -> {
                // Stone display
                placeIfClear(level, entrance.east(),
                        Blocks.STONE_BRICKS.defaultBlockState());
                placeIfClear(level, entrance.west(),
                        Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
                // Stonecutter
                placeIfClear(level, entrance.east(2),
                        Blocks.STONECUTTER.defaultBlockState());
                // Rubble pile (gravel + cobble)
                placeIfClear(level, entrance.west(2),
                        Blocks.GRAVEL.defaultBlockState());
                placeIfClear(level, entrance.west(3),
                        Blocks.COBBLESTONE.defaultBlockState());
            }

            case WEAVER -> {
                // Loom
                placeIfClear(level, entrance.east(),
                        Blocks.LOOM.defaultBlockState());
                // Coloured wool display
                placeIfClear(level, entrance.west(),
                        Blocks.WHITE_WOOL.defaultBlockState());
                placeIfClear(level, entrance.west(2),
                        Blocks.YELLOW_WOOL.defaultBlockState());
                // Barrel of dyes
                placeIfClear(level, entrance.east(2),
                        Blocks.BARREL.defaultBlockState());
                placeIfClear(level, entrance.east(), potOf(Blocks.BLUE_ORCHID));
            }

            case CANDLEMAKER -> {
                // Candles
                placeIfClear(level, entrance.east(),
                        Blocks.CANDLE.defaultBlockState());
                placeIfClear(level, entrance.west(),
                        Blocks.YELLOW_CANDLE.defaultBlockState());
                placeIfClear(level, entrance.east(2),
                        Blocks.RED_CANDLE.defaultBlockState());
                // Barrel of wax (honey)
                placeIfClear(level, entrance.west(2),
                        Blocks.BARREL.defaultBlockState());
                // Bee nest reference
                placeIfClear(level, entrance.south(3),
                        Blocks.BEE_NEST.defaultBlockState());
            }

            case APOTHECARY -> {
                // Brewing stand
                placeIfClear(level, entrance.east(),
                        Blocks.BREWING_STAND.defaultBlockState());
                // Herb pots
                placeIfClear(level, entrance.west(), potOf(Blocks.FERN));
                placeIfClear(level, entrance.west(2), potOf(Blocks.POPPY));
                placeIfClear(level, entrance.east(2),  potOf(Blocks.DANDELION));
                // Cauldron with ingredients
                placeIfClear(level, entrance.west(3),
                        Blocks.CAULDRON.defaultBlockState());
                placeHangingLantern(level, entrance, style);
            }

            case TOOLSMITH -> {
                placeIfClear(level, entrance.east(),
                        Blocks.SMITHING_TABLE.defaultBlockState());
                placeIfClear(level, entrance.west(),
                        Blocks.ANVIL.defaultBlockState());
                placeIfClear(level, entrance.east(2),
                        Blocks.IRON_BLOCK.defaultBlockState());
                placeIfClear(level, entrance.west(2),
                        Blocks.BARREL.defaultBlockState());
            }

            case ARMORER -> {
                placeIfClear(level, entrance.east(),
                        Blocks.BLAST_FURNACE.defaultBlockState());
                placeIfClear(level, entrance.west(),
                        Blocks.ANVIL.defaultBlockState());
                // Iron ingot display
                placeIfClear(level, entrance.east(2),
                        Blocks.IRON_BLOCK.defaultBlockState());
                placeIfClear(level, entrance.west(2),
                        Blocks.IRON_BLOCK.defaultBlockState());
                placeHangingLantern(level, entrance, style);
            }

            case WINERY -> {
                // Barrels of wine
                placeIfClear(level, entrance.east(),  Blocks.BARREL.defaultBlockState());
                placeIfClear(level, entrance.west(),  Blocks.BARREL.defaultBlockState());
                placeIfClear(level, entrance.east(2), Blocks.BARREL.defaultBlockState());
                // Vine display
                placeIfClear(level, entrance.west(2), potOf(Blocks.OAK_SAPLING));
                // Composter for grape waste
                placeIfClear(level, entrance.east(3), Blocks.COMPOSTER.defaultBlockState());
                placeIfClear(level, entrance, Blocks.PURPLE_CARPET.defaultBlockState());
            }

            case MINE -> {
                // Lantern post at entrance
                placePost(level, entrance.east(), style);
                placePost(level, entrance.west(), style);
                // Minecart (visual reference — just an iron block)
                placeIfClear(level, entrance.south(3),
                        Blocks.RAW_IRON_BLOCK.defaultBlockState());
                // Gravel pile (excavated material)
                placeIfClear(level, entrance.east(2),
                        Blocks.GRAVEL.defaultBlockState());
                placeIfClear(level, entrance.east(3),
                        Blocks.GRAVEL.defaultBlockState());
                // Ore reference on the ground
                placeIfClear(level, entrance.west(2),
                        Blocks.IRON_ORE.defaultBlockState());
            }

            case WOODCUTTER -> {
                // Log pile
                placeIfClear(level, entrance.east(),  Blocks.OAK_LOG.defaultBlockState());
                placeIfClear(level, entrance.east(2), Blocks.OAK_LOG.defaultBlockState());
                // Stripped log
                placeIfClear(level, entrance.west(),
                        Blocks.STRIPPED_OAK_LOG.defaultBlockState());
                // Wood planks (finished product)
                placeIfClear(level, entrance.west(2),
                        Blocks.OAK_PLANKS.defaultBlockState());
                // Composter for sawdust
                placeIfClear(level, entrance.south(3),
                        Blocks.COMPOSTER.defaultBlockState());
            }

            case ATELIER -> {
                // Artist's palette (colored terracotta)
                placeIfClear(level, entrance.east(),
                        Blocks.YELLOW_TERRACOTTA.defaultBlockState());
                placeIfClear(level, entrance.west(),
                        Blocks.RED_TERRACOTTA.defaultBlockState());
                // Flower pots for colour
                placeIfClear(level, entrance.east(2), potOf(Blocks.POPPY));
                placeIfClear(level, entrance.west(2), potOf(Blocks.BLUE_ORCHID));
                placeHangingLantern(level, entrance, style);
            }

            case DOCKS -> {
                // Barrels of goods
                for (int i = 0; i < 3; i++) {
                    placeIfClear(level, entrance.east(i + 1),
                            Blocks.BARREL.defaultBlockState());
                }
                // Rope/chain reference
                placeIfClear(level, entrance.west(),
                        Blocks.IRON_CHAIN.defaultBlockState());
                placeIfClear(level, entrance.west(2),
                        Blocks.IRON_CHAIN.defaultBlockState());
            }

            // ── Residential ───────────────────────────────────────────────────

            case HOUSE -> {
                // Flower box under front window (simulated with pot)
                placeIfClear(level, entrance.east(), potOf(style.flower));
                placeIfClear(level, entrance.west(), potOf(style.flower));
                // Bench outside
                placeIfClear(level, entrance.south(2), style.woodSlab());
                // Welcome carpet
                placeIfClear(level, entrance, Blocks.BROWN_CARPET.defaultBlockState());
            }

            case STABLE -> {
                // Hay bales for feed
                placeIfClear(level, entrance.east(),
                        Blocks.HAY_BLOCK.defaultBlockState());
                placeIfClear(level, entrance.east(2),
                        Blocks.HAY_BLOCK.defaultBlockState());
                // Water trough (cauldron)
                placeIfClear(level, entrance.west(),
                        Blocks.CAULDRON.defaultBlockState());
                // Fence posts (hitching posts)
                placeIfClear(level, entrance.west(2), style.fenceState());
                placeIfClear(level, entrance.west(3), style.fenceState());
                // Composter for manure
                placeIfClear(level, entrance.south(3),
                        Blocks.COMPOSTER.defaultBlockState());
            }

            // ── Agricultural ──────────────────────────────────────────────────

            case FARMHOUSE -> {
                // Composter outside — essential for a farm
                placeIfClear(level, entrance.east(),
                        Blocks.COMPOSTER.defaultBlockState());
                // Hay bales
                placeIfClear(level, entrance.west(),
                        Blocks.HAY_BLOCK.defaultBlockState());
                placeIfClear(level, entrance.west(2),
                        Blocks.HAY_BLOCK.defaultBlockState());
                // Seed barrel
                placeIfClear(level, entrance.east(2),
                        Blocks.BARREL.defaultBlockState());
                // Small kitchen garden strip in front
                placeGardenStrip(level, entrance, style);
            }

            case STOCKPILE -> {
                // Many barrels and chests — clearly a storage building
                placeIfClear(level, entrance.east(),   Blocks.BARREL.defaultBlockState());
                placeIfClear(level, entrance.east(2),  Blocks.BARREL.defaultBlockState());
                placeIfClear(level, entrance.east(3),  Blocks.BARREL.defaultBlockState());
                placeIfClear(level, entrance.west(),   Blocks.CHEST.defaultBlockState());
                placeIfClear(level, entrance.west(2),  Blocks.BARREL.defaultBlockState());
                placeIfClear(level, entrance.west(3),  Blocks.BARREL.defaultBlockState());
                // Cart representation (minecart on rails — use slab stack)
                placeIfClear(level, entrance.south(3), Blocks.OAK_SLAB.defaultBlockState());
            }

            // ── Defensive ─────────────────────────────────────────────────────

            case GUARD_TOWER -> {
                // Target dummy
                placeIfClear(level, entrance.south(3),
                        Blocks.HAY_BLOCK.defaultBlockState());
                // Weapon rack (fence with signs)
                placeIfClear(level, entrance.east(2), style.fenceState());
                placeIfClear(level, entrance.east(3), style.fenceState());
                // Armour display
                placeIfClear(level, entrance.west(2),
                        Blocks.IRON_BLOCK.defaultBlockState());
            }

            case WATCHTOWER -> {
                placePost(level, entrance.east(2), style);
                placePost(level, entrance.west(2), style);
                placeIfClear(level, entrance.south(3),
                        Blocks.HAY_BLOCK.defaultBlockState());
            }

            case BARRACKS -> {
                // Training grounds
                for (int i = -2; i <= 2; i++) {
                    placeIfClear(level, entrance.south(4).offset(i * 2, 0, 0),
                            Blocks.HAY_BLOCK.defaultBlockState());
                }
                // Weapon racks
                placeIfClear(level, entrance.east(2), style.fenceState());
                placeIfClear(level, entrance.east(3), style.fenceState());
                placeIfClear(level, entrance.west(2), style.fenceState());
                // Armor stands (iron blocks as stand-ins)
                placeIfClear(level, entrance.east(),
                        Blocks.IRON_BLOCK.defaultBlockState());
                placeIfClear(level, entrance.west(),
                        Blocks.IRON_BLOCK.defaultBlockState());
                // Campfire for morale
                placeIfClear(level, entrance.south(5),
                        Blocks.CAMPFIRE.defaultBlockState());
            }

            case PRISON -> {
                // Iron bars reference outside
                placeIfClear(level, entrance.east(),
                        Blocks.IRON_BARS.defaultBlockState());
                placeIfClear(level, entrance.west(),
                        Blocks.IRON_BARS.defaultBlockState());
                // Torch posts (oppressive lighting)
                placePost(level, entrance.east(3), style);
                placePost(level, entrance.west(3), style);
            }

            case NOBLE_MANOR -> {
                // Decorative columns
                placeColumn(level, entrance.east(3),  style);
                placeColumn(level, entrance.west(3),  style);
                // Flower beds
                for (int i = 1; i <= 4; i++) {
                    placeIfClear(level, entrance.east(i + 3), style.flowerState());
                    placeIfClear(level, entrance.west(i + 3), style.flowerState());
                }
                // Carpet path to door
                placeIfClear(level, entrance, Blocks.RED_CARPET.defaultBlockState());
                placeIfClear(level, entrance.south(2),
                        Blocks.RED_CARPET.defaultBlockState());
            }

            case CASTLE -> {
                // Large decorative columns
                placeColumn(level, entrance.east(4),  style);
                placeColumn(level, entrance.west(4),  style);
                // Torch posts
                placePost(level, entrance.east(3), style);
                placePost(level, entrance.west(3), style);
                placePost(level, entrance.east(6), style);
                placePost(level, entrance.west(6), style);
                // Flag-like pillars
                level.setBlock(entrance.east(4).above(2),
                        Blocks.RED_BANNER.defaultBlockState(), 3);
                level.setBlock(entrance.west(4).above(2),
                        Blocks.BLUE_BANNER.defaultBlockState(), 3);
            }

            default -> {
                // Any unhandled type: simple barrel + lantern
                placeIfClear(level, entrance.east(), Blocks.BARREL.defaultBlockState());
                placeHangingLantern(level, entrance, style);
            }
        }
    }

    // =========================================================================
    // Step 9: Street furniture
    // =========================================================================

    /**
     * Places benches, notice boards, and well surrounds at street junctions.
     * A junction is any network node where ≥2 edges meet that is NOT a
     * building entrance (i.e. an intersection node).
     *
     * <p>Also places a bench on each secondary/tertiary street segment at
     * the midpoint — giving NPCs somewhere to sit during idle time.
     */
    private static void placeStreetFurniture(ServerLevel level,
                                             StreetNetwork network,
                                             VillageBiomeStyle style,
                                             VillageSizeTier tier) {
        // Place furniture at each network node
        for (StreetNetwork.Node node : network.getAllNodes()) {
            if (node.buildingId != null) continue; // skip building entrance nodes

            int edgeCount = node.getEdges().size();
            if (edgeCount < 2) continue; // not a junction

            BlockPos pos = node.pos;
            int surfY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    pos.getX(), pos.getZ());
            BlockPos surface = new BlockPos(pos.getX(), surfY + 1, pos.getZ());

            if (edgeCount >= 3) {
                // Significant junction — place a notice board or well
                placeJunctionFeature(level, surface, style, tier, node);
            } else {
                // Simple bend — place a bench beside the path
                placeStreetBench(level, surface, style);
            }
        }

        // Midpoint benches on longer secondary/tertiary edges
        for (StreetNetwork.Edge edge : network.getAllEdges()) {
            if (edge.tier == StreetTier.PRIMARY) continue; // no benches on primary streets
            if (edge.blocks.size() < 20) continue; // too short

            int mid = edge.blocks.size() / 2;
            BlockPos midPos = edge.blocks.get(mid);
            // Offset perpendicular to place bench beside the path, not on it
            BlockPos benchBase = midPos.above().east();
            placeStreetBench(level, benchBase, style);
        }
    }

    private static void placeJunctionFeature(ServerLevel level,
                                             BlockPos surface,
                                             VillageBiomeStyle style,
                                             VillageSizeTier tier,
                                             StreetNetwork.Node node) {
        // Rotate through junction features based on node position hash
        int variant = (Math.abs(surface.getX() + surface.getZ())) % 3;

        switch (variant) {
            case 0 -> {
                // Notice board (lectern + fence post)
                placeIfClear(level, surface, style.fenceState());
                placeIfClear(level, surface.above(),
                        Blocks.LECTERN.defaultBlockState());
            }
            case 1 -> {
                // Planter (composter + flower)
                placeIfClear(level, surface, Blocks.COMPOSTER.defaultBlockState());
                placeIfClear(level, surface.above(), style.flowerState());
            }
            case 2 -> {
                // Stone bench (two slabs)
                placeIfClear(level, surface,        style.stoneSlab());
                placeIfClear(level, surface.east(), style.stoneSlab());
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
    // Market stalls
    // =========================================================================

    private static void placeMarketStalls(ServerLevel level,
                                          BlockPos squareCenter,
                                          VillageBiomeStyle style) {
        int stallDist = 13;
        int[][] positions = {
                {stallDist, 0}, {-stallDist, 0},
                {0, stallDist}, {0, -stallDist}
        };
        BlockState[] carpets = {
                Blocks.RED_CARPET.defaultBlockState(),
                Blocks.BLUE_CARPET.defaultBlockState(),
                Blocks.YELLOW_CARPET.defaultBlockState(),
                Blocks.WHITE_CARPET.defaultBlockState()
        };

        for (int i = 0; i < 4; i++) {
            BlockPos base = findSurface(level, new BlockPos(
                    squareCenter.getX() + positions[i][0],
                    squareCenter.getY(),
                    squareCenter.getZ() + positions[i][1]));

            // Carpet surface (3 blocks)
            placeIfClear(level, base,         carpets[i]);
            placeIfClear(level, base.north(),  carpets[i]);
            placeIfClear(level, base.south(),  carpets[i]);

            // Display goods on top
            placeIfClear(level, base.above(), Blocks.BARREL.defaultBlockState());

            // Canopy: fence post + slab overhead
            BlockPos post1 = base.north(2).above();
            BlockPos post2 = base.south(2).above();
            placeIfClear(level, post1, style.fenceState());
            placeIfClear(level, post2, style.fenceState());
            placeIfClear(level, post1.above(), style.stoneSlab());
            placeIfClear(level, post2.above(), style.stoneSlab());
            // Connect canopy with slab over the stall
            placeIfClear(level, base.above(2),       style.stoneSlab());
            placeIfClear(level, base.north().above(2), style.stoneSlab());
            placeIfClear(level, base.south().above(2), style.stoneSlab());
        }
    }

    // =========================================================================
    // Landmark
    // =========================================================================

    private static void placeLandmark(ServerLevel level,
                                      Building townHall,
                                      VillageBiomeStyle style,
                                      Village village) {
        BlockPos center = townHall.getShape().getOrigin().offset(
                townHall.getShape().getWidth() / 2, 0,
                -(townHall.getShape().getLength() / 2 + 6));
        BlockPos surface = findSurface(level, center);

        int type = Math.abs(village.getName().hashCode()) % 4;
        switch (type) {
            case 0 -> placeObelisk(level, surface, style);
            case 1 -> placeStatue(level, surface, style);
            case 2 -> placeNoticeBoard(level, surface, style);
            case 3 -> placeMemorialFountain(level, surface, style);
        }
    }

    private static void placeObelisk(ServerLevel level,
                                     BlockPos base, VillageBiomeStyle style) {
        // 5-block tapered obelisk
        level.setBlock(base,         style.stone.defaultBlockState(), 3);
        level.setBlock(base.above(), style.stone.defaultBlockState(), 3);
        level.setBlock(base.above(2),style.stone.defaultBlockState(), 3);
        level.setBlock(base.above(3),style.stoneSlab(),               3);
        level.setBlock(base.above(4),style.lanternState(),            3);
        // Decorative base ring (slabs)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                placeIfClear(level, base.offset(dx, 0, dz), style.stoneSlab());
            }
        }
    }

    private static void placeStatue(ServerLevel level,
                                    BlockPos base, VillageBiomeStyle style) {
        // 3-block pedestal + chiselled top
        level.setBlock(base,         style.stone.defaultBlockState(),         3);
        level.setBlock(base.above(), style.stoneStairs != null
                ? style.stoneStairs.defaultBlockState()
                : style.stone.defaultBlockState(),                            3);
        level.setBlock(base.above(2),
                Blocks.CHISELED_STONE_BRICKS.defaultBlockState(),             3);
        // Flower offerings
        placeIfClear(level, base.east(),  style.flowerState());
        placeIfClear(level, base.west(),  style.flowerState());
        placeIfClear(level, base.north(), style.flowerState());
        placeIfClear(level, base.south(), style.flowerState());
    }

    private static void placeNoticeBoard(ServerLevel level,
                                         BlockPos base, VillageBiomeStyle style) {
        // Fence post with lectern (notice board)
        level.setBlock(base,         style.fenceState(),              3);
        level.setBlock(base.above(), Blocks.LECTERN.defaultBlockState(), 3);
        // Flanking torches
        placeIfClear(level, base.east(2),  style.lanternState());
        placeIfClear(level, base.west(2),  style.lanternState());
    }

    private static void placeMemorialFountain(ServerLevel level,
                                              BlockPos base,
                                              VillageBiomeStyle style) {
        // 3×3 fountain with water centre
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos pos = base.offset(dx, 0, dz);
                if (dx == 0 && dz == 0) {
                    level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
                    level.setBlock(pos.above(), style.stoneWallState(), 3);
                } else if (Math.abs(dx) + Math.abs(dz) == 1) {
                    // Cardinal ring: wall blocks
                    level.setBlock(pos, style.stone.defaultBlockState(), 3);
                    level.setBlock(pos.above(), style.fenceState(), 3);
                } else {
                    // Diagonal corners: slab
                    level.setBlock(pos, style.stoneSlab(), 3);
                }
            }
        }
        level.setBlock(base.above(2), style.lanternState(), 3);
    }

    // =========================================================================
    // Flowers
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
                int surfY = level.getHeight(
                        Heightmap.Types.WORLD_SURFACE, x, z);

                if (surfY == targetSolid) continue;

                if (surfY < targetSolid) {
                    for (int y = surfY; y <= targetSolid; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState s = level.getBlockState(pos);
                        if (s.isAir() || s.is(BlockTags.REPLACEABLE) || s.liquid()) {
                            level.setBlock(pos, y == targetSolid
                                    ? Blocks.GRASS_BLOCK.defaultBlockState()
                                    : Blocks.DIRT.defaultBlockState(), 3);
                        }
                    }
                } else {
                    for (int y = surfY; y > targetSolid; y--) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (isNaturalTerrain(level.getBlockState(pos))) {
                            level.setBlock(pos,
                                    Blocks.AIR.defaultBlockState(), 3);
                        } else break;
                    }
                    BlockPos cap = new BlockPos(x, targetSolid, z);
                    BlockState capS = level.getBlockState(cap);
                    if (capS.is(Blocks.DIRT) || capS.is(Blocks.COARSE_DIRT)) {
                        level.setBlock(cap,
                                Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    private static boolean isNaturalTerrain(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT)
                || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.ROOTED_DIRT)
                || s.is(Blocks.SAND) || s.is(Blocks.RED_SAND)
                || s.is(Blocks.GRAVEL) || s.is(Blocks.SNOW_BLOCK)
                || s.is(Blocks.POWDER_SNOW)
                || s.is(BlockTags.REPLACEABLE) || s.isAir();
    }

    // =========================================================================
    // Slab transitions
    // =========================================================================

    private static void placeSlabTransitions(ServerLevel level,
                                             List<BlockPos> placed,
                                             VillageBiomeStyle style) {
        if (placed.size() < 2) return;

        for (int i = 1; i < placed.size(); i++) {
            BlockPos prev = placed.get(i - 1);
            BlockPos curr = placed.get(i);

            int dxz = Math.abs(curr.getX() - prev.getX())
                    + Math.abs(curr.getZ() - prev.getZ());
            if (dxz > 2) continue;

            int dy = curr.getY() - prev.getY();
            if (Math.abs(dy) != 1) continue;

            BlockPos lower   = dy > 0 ? prev : curr;
            BlockPos pathPos = lower.below();

            BlockState existing = level.getBlockState(pathPos);
            if (existing.is(Blocks.DIRT_PATH)
                    || existing.is(Blocks.GRAVEL)
                    || existing.is(Blocks.COBBLESTONE)
                    || existing.is(Blocks.DIRT)) {
                level.setBlock(pathPos, style.woodSlab(), 3);
            }
        }
    }

    // =========================================================================
    // Lamppost (public, called by TownSquarePlacer)
    // =========================================================================

    public static void placeLampposts(ServerLevel level,
                                      List<BlockPos> pathSurface,
                                      VillageBiomeStyle style,
                                      VillageSizeTier tier) {
        if (pathSurface.isEmpty()) return;
        int start = Math.max(0, tier.lampostSpacing / 2);
        for (int i = start; i < pathSurface.size(); i += tier.lampostSpacing) {
            BlockPos surface = pathSurface.get(i);
            BlockPos pole    = surface.above();
            BlockPos lamp    = surface.above(2);
            if (!level.getBlockState(pole).isAir()) continue;
            level.setBlock(pole, style.fenceState(),   3);
            level.setBlock(lamp, style.lanternState(), 3);
        }
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    private static BlockPos resolveSquareCenter(ServerLevel level,
                                                VillageLayout layout,
                                                List<Building> buildings) {
        if (layout != null && layout.getTownSquarePos() != null) {
            return layout.getTownSquarePos();
        }
        return deriveSquareCenterFromBuildings(level, buildings);
    }

    private static BlockPos deriveSquareCenterFromBuildings(
            ServerLevel level, List<Building> buildings) {
        Optional<Building> townHall = findTownHall(buildings);
        int sumX = 0, sumZ = 0;
        for (Building b : buildings) {
            BlockPos o = b.getShape().getOrigin();
            sumX += o.getX() + b.getShape().getWidth()  / 2;
            sumZ += o.getZ() + b.getShape().getLength() / 2;
        }
        int centX = sumX / buildings.size();
        int centZ = sumZ / buildings.size();
        if (townHall.isPresent()) {
            BlockPos th = townHall.get().getShape().getOrigin();
            centX = (centX + th.getX() + townHall.get().getShape().getWidth()  / 2) / 2;
            centZ = (centZ + th.getZ() + townHall.get().getShape().getLength() / 2) / 2;
        }
        return findSurface(level, new BlockPos(centX, 64, centZ));
    }

    private static Optional<Building> findTownHall(List<Building> buildings) {
        return buildings.stream()
                .filter(b -> b.getType() == BuildingType.TOWN_HALL)
                .findFirst();
    }

    static BlockPos findSurface(ServerLevel level, BlockPos pos) {
        int surfaceY = level.getHeight(
                Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
        BlockPos surface = new BlockPos(pos.getX(), surfaceY, pos.getZ());
        if (level.getBlockState(surface).liquid()
                || level.getBlockState(surface.below()).liquid()) {
            for (int y = surfaceY - 1; y > level.getMinY(); y--) {
                BlockPos check = new BlockPos(pos.getX(), y, pos.getZ());
                BlockState s = level.getBlockState(check);
                if (s.isSolidRender() && !s.liquid()) return check.above();
            }
        }
        return surface;
    }

    private static void placeIfClear(ServerLevel level,
                                     BlockPos pos, BlockState state) {
        if (level.getBlockState(pos.below()).isSolidRender()
                && level.getBlockState(pos).isAir()) {
            level.setBlock(pos, state, 3);
        }
    }

    private static boolean isHardBlock(BlockState s) {
        return s.is(BlockTags.BASE_STONE_OVERWORLD)
                || s.is(Blocks.BEDROCK)
                || s.is(Blocks.OBSIDIAN)
                || s.is(Blocks.WATER)
                || s.is(Blocks.LAVA)
                || s.is(BlockTags.LOGS)
                || s.is(BlockTags.PLANKS)
                || s.is(BlockTags.STONE_BRICKS)
                || s.is(Blocks.COBBLESTONE)
                || s.is(Blocks.STONE)
                || s.is(Blocks.DEEPSLATE)
                || s.is(Blocks.ANDESITE)
                || s.is(Blocks.DIORITE)
                || s.is(Blocks.GRANITE)
                // ── Village-placed protection ─────────────────────────────────
                || s.is(Blocks.DIRT_PATH)
                || s.is(Blocks.COBBLESTONE_SLAB)
                || s.is(Blocks.STONE_BRICK_SLAB)
                || s.is(BlockTags.WOOL_CARPETS)
                || s.is(Blocks.SMOOTH_SANDSTONE)
                || s.is(Blocks.PACKED_ICE)
                || s.is(Blocks.MUD);
    }

    private static void clearColumn(ServerLevel level,
                                    int x, int z, int nearY) {
        for (int y = nearY + 20; y >= nearY - 2; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(pos);
            if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES)
                    || s.is(BlockTags.REPLACEABLE)
                    || s.is(BlockTags.FLOWERS)
                    || s.is(BlockTags.SMALL_FLOWERS)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private static Set<Long> collectBuildingXZ(List<Building> buildings) {
        Set<Long> set = new HashSet<>();
        for (Building b : buildings) {
            BlockPos min = b.getShape().getMin();
            BlockPos max = b.getShape().getMax();
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    set.add(xzKey(x, z));
                }
            }
        }
        return set;
    }

    public static long xzKey(int x, int z) {
        return ((long)(x + 30_000_000)) << 32 | (z + 30_000_000);
    }

    // ── Decoration sub-helpers ────────────────────────────────────────────────

    /** Places a 2-tall fence-post lamppost at the given surface position. */
    private static void placePost(ServerLevel level,
                                  BlockPos surface, VillageBiomeStyle style) {
        if (!level.getBlockState(surface.below()).isSolidRender()) return;
        if (!level.getBlockState(surface).isAir()) return;
        level.setBlock(surface,         style.fenceState(),   3);
        level.setBlock(surface.above(), style.lanternState(), 3);
    }

    /** Places a 2-tall stone column (pillar) at the given surface position. */
    private static void placeColumn(ServerLevel level,
                                    BlockPos surface, VillageBiomeStyle style) {
        if (!level.getBlockState(surface.below()).isSolidRender()) return;
        level.setBlock(surface,         style.stone.defaultBlockState(), 3);
        level.setBlock(surface.above(), style.stone.defaultBlockState(), 3);
    }

    /** Places a lantern hanging above the entrance (on the block above the door). */
    private static void placeHangingLantern(ServerLevel level,
                                            BlockPos entrance,
                                            VillageBiomeStyle style) {
        BlockPos above = entrance.above();
        if (level.getBlockState(above).isAir()) {
            level.setBlock(above, style.lanternState(), 3);
        }
    }

    /**
     * Returns a potted-plant blockstate for the given flower block.
     * Falls back to a potted poppy if the flower doesn't have a pot form.
     */
    private static BlockState potOf(net.minecraft.world.level.block.Block flower) {
        // Map common flowers to their pot forms
        if (flower == Blocks.POPPY)        return Blocks.POTTED_POPPY.defaultBlockState();
        if (flower == Blocks.DANDELION)    return Blocks.POTTED_DANDELION.defaultBlockState();
        if (flower == Blocks.BLUE_ORCHID)  return Blocks.POTTED_BLUE_ORCHID.defaultBlockState();
        if (flower == Blocks.OXEYE_DAISY)  return Blocks.POTTED_OXEYE_DAISY.defaultBlockState();
        if (flower == Blocks.AZURE_BLUET)  return Blocks.POTTED_AZURE_BLUET.defaultBlockState();
        if (flower == Blocks.DEAD_BUSH)    return Blocks.POTTED_DEAD_BUSH.defaultBlockState();
        if (flower == Blocks.FERN)         return Blocks.POTTED_FERN.defaultBlockState();
        if (flower == Blocks.OAK_SAPLING)  return Blocks.POTTED_OAK_SAPLING.defaultBlockState();
        return Blocks.POTTED_POPPY.defaultBlockState(); // safe default
    }

    /** Small kitchen garden strip in front of a farmhouse. */
    private static void placeGardenStrip(ServerLevel level,
                                         BlockPos entrance,
                                         VillageBiomeStyle style) {
        // Two rows of farmland with wheat and carrots
        for (int i = -2; i <= 2; i++) {
            BlockPos groundPos = entrance.south(3).offset(i, -1, 0);
            BlockPos cropPos   = entrance.south(3).offset(i,  0, 0);
            if (level.getBlockState(groundPos).isSolidRender()
                    && level.getBlockState(cropPos).isAir()) {
                level.setBlock(groundPos,
                        Blocks.FARMLAND.defaultBlockState(), 3);
                level.setBlock(cropPos,
                        (i % 2 == 0)
                                ? Blocks.WHEAT.defaultBlockState()
                                : Blocks.CARROTS.defaultBlockState(), 3);
            }
        }
    }

    /** Extends the market building's front with a small covered stall. */
    private static void placeMarketFront(ServerLevel level,
                                         BlockPos entrance,
                                         VillageBiomeStyle style) {
        // Carpet display
        placeIfClear(level, entrance,        Blocks.YELLOW_CARPET.defaultBlockState());
        placeIfClear(level, entrance.east(),  Blocks.YELLOW_CARPET.defaultBlockState());
        placeIfClear(level, entrance.west(),  Blocks.YELLOW_CARPET.defaultBlockState());
        // Display goods
        placeIfClear(level, entrance.above(), Blocks.BARREL.defaultBlockState());
        // Canopy posts
        placeIfClear(level, entrance.east(2), style.fenceState());
        placeIfClear(level, entrance.west(2), style.fenceState());
        placeIfClear(level, entrance.east(2).above(), style.stoneSlab());
        placeIfClear(level, entrance.west(2).above(), style.stoneSlab());
        placeIfClear(level, entrance.east().above(),  style.stoneSlab());
        placeIfClear(level, entrance.west().above(),  style.stoneSlab());
        placeIfClear(level, entrance.above(2),        style.stoneSlab());
    }
    public static void decorateExpansionBuilding(ServerLevel level,
                                                 Building building,
                                                 Village village,
                                                 VillageSavedData data) {
        if (building.getType() == BuildingType.TOWN_SQUARE) return;

        VillageBiomeStyle style = VillageBiomeStyle.detect(
                level, building.getShape().getOrigin());
        VillageSizeTier tier = VillageSizeTier.fromBuildingCount(
                village.getBuildingIds().size());

        // Collect all path XZ for transition checks
        Set<Long> pathXZ = new HashSet<>();
        data.getPathsForVillage(village.getId()).forEach(p ->
                p.getBlocks().forEach(b ->
                        pathXZ.add(xzKey(b.getX(), b.getZ()))));

        // Foundation + ground smooth
        BuildingFoundation.place(level, building);
        smoothUnderBuilding(level, building);

        // Border transitions (requires knowing which faces are on streets)
        placeBuildingTransitions(level, building, style, pathXZ);

        // Exterior decorations
        placeExteriorDecorations(level, building, style, tier);

        // Light weathering (single building only)
        VillageBiomeStyle.PLAINS.equals(style);  // biome check handled inside
        VillageWeathering.weather(level, village, data, style);

        System.out.println("VillageDecorator: decorated expansion building "
                + building.getType() + " at "
                + building.getShape().getOrigin().toShortString());
    }
    // =========================================================================
    // Approach gradient
    // =========================================================================

    /**
     * Places a managed land zone between the wild terrain and the village
     * perimeter, giving the impression that the land around the village has
     * been cleared, farmed, and used over time.
     *
     * <h3>Effect</h3>
     * <ul>
     *   <li>Replaces grass with coarse dirt in a ring 4–12 blocks outside
     *       the outermost buildings — simulating worn ground from foot traffic</li>
     *   <li>Places occasional tree stumps (logs at ground level) at the
     *       cleared boundary — suggesting the forest was pushed back</li>
     *   <li>Scatters a few wildflowers and fern patches in the outer ring
     *       (8–16 blocks beyond buildings) — the managed fringe between
     *       cleared land and wild terrain</li>
     * </ul>
     */
    private static void placeApproachGradient(ServerLevel level,
                                              Village village,
                                              VillageSavedData data,
                                              VillageBiomeStyle style,
                                              Set<Long> pathXZ) {
        Optional<net.minecraft.world.phys.AABB> boundsOpt =
                village.getBounds(data);
        if (boundsOpt.isEmpty()) return;

        net.minecraft.world.phys.AABB bounds = boundsOpt.get();
        int cx = (int)((bounds.minX + bounds.maxX) / 2);
        int cz = (int)((bounds.minZ + bounds.maxZ) / 2);
        int outerRadius = (int)(Math.max(
                bounds.maxX - bounds.minX,
                bounds.maxZ - bounds.minZ) / 2);

        Random rng = new Random(village.getName().hashCode() * 17L);

        // Worn ground ring: 0–8 blocks outside village bounds
        int wornInner = outerRadius;
        int wornOuter = outerRadius + 8;

        // Managed fringe ring: 8–20 blocks outside
        int fringeInner = outerRadius + 8;
        int fringeOuter = outerRadius + 20;

        for (int dx = -fringeOuter; dx <= fringeOuter; dx += 2) {
            for (int dz = -fringeOuter; dz <= fringeOuter; dz += 2) {
                int x   = cx + dx;
                int z   = cz + dz;
                int dist = (int) Math.sqrt(dx * dx + dz * dz);

                if (pathXZ.contains(xzKey(x, z))) continue;

                int surfY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos pos = new BlockPos(x, surfY, z);
                BlockState s = level.getBlockState(pos);

                if (dist >= wornInner && dist <= wornOuter) {
                    // Worn ground: replace grass with coarse dirt
                    // Probability increases closer to village
                    float wornChance = 0.4f * (1.0f
                            - (float)(dist - wornInner) / (wornOuter - wornInner));
                    if (s.is(Blocks.GRASS_BLOCK) && rng.nextFloat() < wornChance) {
                        level.setBlock(pos,
                                Blocks.COARSE_DIRT.defaultBlockState(), 3);
                    }

                    // Tree stumps at the inner edge of the cleared zone
                    if (dist >= wornOuter - 4
                            && rng.nextFloat() < 0.03f
                            && s.is(Blocks.GRASS_BLOCK)) {
                        level.setBlock(pos.above(),
                                Blocks.OAK_LOG.defaultBlockState(), 3);
                    }

                } else if (dist >= fringeInner && dist <= fringeOuter) {
                    // Managed fringe: wildflowers and ferns
                    if (s.is(Blocks.GRASS_BLOCK)) {
                        BlockPos above = pos.above();
                        if (level.getBlockState(above).isAir()) {
                            float r = rng.nextFloat();
                            if (r < 0.04f) {
                                level.setBlock(above,
                                        style.flowerState(), 3);
                            } else if (r < 0.07f) {
                                level.setBlock(above,
                                        Blocks.FERN.defaultBlockState(), 3);
                            } else if (r < 0.085f) {
                                level.setBlock(above,
                                        Blocks.SHORT_GRASS.defaultBlockState(), 3);
                            }
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // Proximity-driven street furniture
    // =========================================================================

    /**
     * Places context-specific street furniture near buildings that
     * logically generate activity around them.
     *
     * <p>Unlike {@link #placeStreetFurniture} which operates on the
     * abstract street network, this method operates on building type
     * proximity — a horse trough belongs near the stable regardless of
     * where the nearest network node is.
     */
    private static void placeProximityFurniture(ServerLevel level,
                                                List<Building> buildings,
                                                VillageBiomeStyle style) {
        for (Building building : buildings) {
            BlockPos entrance = PathRouter.getBuildingEntrance(building);

            switch (building.getType()) {

                case STABLE -> {
                    // Horse trough (cauldron) on the south side of the stable,
                    // accessible from the path — animals drink here
                    BlockPos trough = findClearSurface(level, entrance.south(4));
                    placeIfClear(level, trough,
                            Blocks.CAULDRON.defaultBlockState());
                    // Hitching posts (fence) flanking trough
                    placeIfClear(level, trough.east(2), style.fenceState());
                    placeIfClear(level, trough.west(2), style.fenceState());
                }

                case PRISON -> {
                    // Stocks (two fences facing each other with a slab on top)
                    BlockPos stocksBase = findClearSurface(level, entrance.south(4));
                    placeIfClear(level, stocksBase,       style.fenceState());
                    placeIfClear(level, stocksBase.east(), style.fenceState());
                    placeIfClear(level, stocksBase.above(), style.woodSlab());
                    placeIfClear(level, stocksBase.east().above(), style.woodSlab());
                    // Sign post
                    placeIfClear(level, stocksBase.west(),
                            Blocks.OAK_SIGN.defaultBlockState());
                }

                case TEMPLE -> {
                    // Offering bowls (cauldrons) on either side of the path
                    // leading to the temple — ceremonial approach
                    for (int step = 2; step <= 6; step += 2) {
                        BlockPos left  = findClearSurface(level,
                                entrance.south(step).east(2));
                        BlockPos right = findClearSurface(level,
                                entrance.south(step).west(2));
                        if (step == 2) {
                            // Closest pair: cauldron offerings
                            placeIfClear(level, left,
                                    Blocks.CAULDRON.defaultBlockState());
                            placeIfClear(level, right,
                                    Blocks.CAULDRON.defaultBlockState());
                        } else {
                            // Further pairs: lantern posts
                            placeIfClear(level, left,  style.fenceState());
                            placeIfClear(level, right, style.fenceState());
                            placeIfClear(level, left.above(),
                                    style.lanternState());
                            placeIfClear(level, right.above(),
                                    style.lanternState());
                        }
                    }
                }

                case LIBRARY -> {
                    // Reading bench outside the library — facing east
                    // so it catches morning light
                    BlockPos bench = findClearSurface(level,
                            entrance.east(3).south(2));
                    placeIfClear(level, bench, style.woodSlab());
                    placeIfClear(level, bench.east(), style.woodSlab());
                    // Candle for evening reading
                    placeIfClear(level, bench.above(),
                            Blocks.CANDLE.defaultBlockState());
                }

                case BARRACKS -> {
                    // Weapon rack (two fence posts with slab top)
                    // visible from the street
                    BlockPos rack = findClearSurface(level, entrance.east(4));
                    placeIfClear(level, rack,          style.fenceState());
                    placeIfClear(level, rack.east(),   style.fenceState());
                    placeIfClear(level, rack.above(),  style.woodSlab());
                    // Target dummy (hay bale + carved pumpkin)
                    BlockPos dummy = findClearSurface(level, entrance.west(4));
                    placeIfClear(level, dummy,
                            Blocks.HAY_BLOCK.defaultBlockState());
                    placeIfClear(level, dummy.above(),
                            Blocks.CARVED_PUMPKIN.defaultBlockState());
                }

                case MARKET -> {
                    // Public notice board at the market approach
                    BlockPos board = findClearSurface(level, entrance.south(5));
                    placeIfClear(level, board, style.fenceState());
                    placeIfClear(level, board.above(),
                            Blocks.LECTERN.defaultBlockState());
                    // Planter box on either side of the board
                    placeIfClear(level, board.east(2),
                            Blocks.COMPOSTER.defaultBlockState());
                    placeIfClear(level, board.west(2),
                            Blocks.COMPOSTER.defaultBlockState());
                    placeIfClear(level, board.east(2).above(),
                            style.flowerState());
                    placeIfClear(level, board.west(2).above(),
                            style.flowerState());
                }

                case INN -> {
                    // Campfire in front of the inn — gathering spot
                    BlockPos fire = findClearSurface(level, entrance.south(4));
                    placeIfClear(level, fire,
                            Blocks.CAMPFIRE.defaultBlockState());
                    // Log benches around the fire
                    placeIfClear(level, fire.east(2), style.woodSlab());
                    placeIfClear(level, fire.west(2), style.woodSlab());
                    placeIfClear(level, fire.south(2), style.woodSlab());
                }

                case BLACKSMITH -> {
                    // Coal/iron storage pile next to the smithy
                    BlockPos pile = findClearSurface(level, entrance.east(4));
                    placeIfClear(level, pile,
                            Blocks.COAL_BLOCK.defaultBlockState());
                    placeIfClear(level, pile.east(),
                            Blocks.RAW_IRON_BLOCK.defaultBlockState());
                    placeIfClear(level, pile.above(),
                            Blocks.COAL_BLOCK.defaultBlockState());
                    // Grindstone outside on the path side
                    placeIfClear(level, findClearSurface(level, entrance.west(4)),
                            Blocks.GRINDSTONE.defaultBlockState());
                }

                case TOWN_HALL -> {
                    // Bell post in front of the town hall —
                    // used to call meetings (villagers path to bell)
                    BlockPos bellPost = findClearSurface(level,
                            entrance.south(5));
                    placeIfClear(level, bellPost, style.fenceState());
                    placeIfClear(level, bellPost.above(),
                            Blocks.BELL.defaultBlockState());
                    // Flower beds flanking the bell post
                    for (int i = 1; i <= 3; i++) {
                        placeIfClear(level,
                                findClearSurface(level,
                                        entrance.south(3 + i).east(3)),
                                style.flowerState());
                        placeIfClear(level,
                                findClearSurface(level,
                                        entrance.south(3 + i).west(3)),
                                style.flowerState());
                    }
                }

                default -> {} // handled by placeExteriorDecorations
            }
        }
    }

    /**
     * Returns the first non-air surface position at or above the given
     * position, accounting for the case where the position is already
     * at the right level. Avoids placing furniture underground.
     */
    private static BlockPos findClearSurface(ServerLevel level, BlockPos pos) {
        int surfY = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getX(), pos.getZ());
        return new BlockPos(pos.getX(), surfY + 1, pos.getZ());
    }
    private static StreetTier convertTier(CapitalStreetGraph.StreetTier t) {
        return switch (t) {
            case PRIMARY   -> StreetTier.PRIMARY;
            case SECONDARY -> StreetTier.SECONDARY;
            case TERTIARY  -> StreetTier.TERTIARY;
        };
    }
}