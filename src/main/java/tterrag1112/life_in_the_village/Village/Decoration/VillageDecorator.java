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
import tterrag1112.life_in_the_village.Village.Decoration.Roads.*;
import tterrag1112.life_in_the_village.Village.Economy.Trade.RoadRouter;
import tterrag1112.life_in_the_village.Village.Planning.*;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

public class VillageDecorator {

    // =========================================================================
    // Main entry point
    // =========================================================================

    /**
     * Full decoration pass for a village.
     *
     * @param level     server level
     * @param village   the village to decorate
     * @param data      saved data
     * @param layout    the layout used during spawn (may be null for expansion)
     * @param footprint building collision grid (built during Phase 1)
     */
    public static void decorateVillage(ServerLevel level,
                                       Village village,
                                       VillageSavedData data,
                                       VillageLayout layout,
                                       BuildingFootprint footprint) {
        List<Building> buildings = village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        if (buildings.isEmpty()) return;

        VillageBiomeStyle style = VillageBiomeStyle.detect(
                level, buildings.get(0).getShape().getOrigin());
        VillageSizeTier tier = VillageSizeTier.fromBuildingCount(
                buildings.size() * 2);


        System.out.println("VillageDecorator: decorating "
                + village.getName() + " (" + buildings.size()
                + " buildings, " + tier.displayName);

        // ── Step 1: Town square ───────────────────────────────────────────────
        BlockPos squareCenter = resolveSquareCenter(level, layout, buildings);
        // Phase 1 doc 04 — TownSquareComposer is the drop-in replacement
        // for the legacy TownSquarePlacer. Same signature, same return
        // type. The composer paves the plaza, registers a TOWN_SQUARE
        // building, sets the tier-scaled radius on the village, and
        // registers gathering points. Sub-feature NBTs are stamped
        // later by DecorationPass via DecorationProfile registrations.
        Set<BlockPos> squarePavement =
                tterrag1112.life_in_the_village.Village.Decoration
                        .TownSquare.TownSquareComposer.compose(
                                level, squareCenter, style, tier, village, data);
        int plazaRadius = village.getTownSquareRadius() > 0
                ? village.getTownSquareRadius()
                : tterrag1112.life_in_the_village.Village.Decoration
                        .TownSquare.TownSquareTier.plazaRadiusFor(tier);

        // Add square to footprint — sized to the tier-scaled radius.
        if (footprint != null) {
            footprint.occupyRect(
                    squareCenter.offset(-plazaRadius, 0, -plazaRadius),
                    plazaRadius * 2, plazaRadius * 2, 1);
        }

        // ── Step 2: Road network ──────────────────────────────────────────────
        Set<Long> allPathXZ;

            // Normal village: new organic road system
            if (footprint == null) {
                footprint = BuildingFootprint.fromVillage(village, data);
            }

            PathMaterial material = PathMaterial.forBiomeAndTier(
                    style, village.getPathTier());
            RoadShape.RoadTier roadTier = RoadShape.fromPathTier(
                    village.getPathTier());

        VillageRoadNetwork roads = new VillageRoadNetwork(squareCenter);
        allPathXZ = roads.buildInitialNetwork(
                level, village, data, layout, material, roadTier,
                footprint, level.getRandom());

            // Add square pavement
            for (BlockPos p : squarePavement) {
                allPathXZ.add(xzKey(p.getX(), p.getZ()));

        }
        System.out.println("VillageDecorator: road network produced "
                + allPathXZ.size() + " path XZ positions across "
                + data.getPathsForVillage(village.getId()).size() + " VillagePaths");
        int totalBlocks = 0;
        for (VillagePath path : data.getPathsForVillage(village.getId())) {
            totalBlocks += path.getBlocks().size();
        }
        System.out.println("VillageDecorator: total placed road blocks = " + totalBlocks);

        village.setPathHubPos(squareCenter);
        data.setDirty();

        // ── Step 3: Support hollow ground ─────────────────────────────────────
        List<BlockPos> allPathBlocks = new ArrayList<>();
        for (VillagePath path : data.getPathsForVillage(village.getId())) {
            allPathBlocks.addAll(path.getBlocks());
        }
        TerrainSmoother.supportPathBlocks(level, allPathBlocks);

        // ── Step 4: Perimeter ─────────────────────────────────────────────────
        //VillagePerimeter.place(level, village, data, style, tier, allPathXZ);

        // ── Step 5: Approach gradient ─────────────────────────────────────────

            //placeApproachGradient(level, village, data, style, allPathXZ);



            buildings.stream()
                    .filter(b -> b.getType() != BuildingType.TOWN_SQUARE)
                    .forEach(b -> placeExteriorDecorations(
                            level, b, style, tier));

            // Proximity furniture
            placeProximityFurniture(level, buildings, style);

            // Market stalls
            if (tier.hasMarket) {
                placeMarketStalls(level, squareCenter, style);
            }

            // Landmark
            if (tier.hasLandmark) {
                findTownHall(buildings).ifPresent(th ->
                        placeLandmark(level, th, style, village));
            }

            // Flowers
            if (tier.flowerAttempts > 0) {
                scatterFlowers(level, village, data, style,
                        tier.flowerAttempts);

        }

        // ── Step 12: Weathering ───────────────────────────────────────────────
        VillageWeathering.weather(level, village, data, style);

        // Diagnostic: check if roads survived weathering
        int survivingRoadBlocks = 0;
        for (VillagePath path : data.getPathsForVillage(village.getId())) {
            for (BlockPos pos : path.getBlocks()) {
                var state = level.getBlockState(pos.below());
                if (!state.is(Blocks.GRASS_BLOCK) && !state.is(Blocks.DIRT)) {
                    survivingRoadBlocks++;
                }
            }
        }
        System.out.println("VillageDecorator: " + survivingRoadBlocks
                + " road blocks still non-grass after decoration");

        // ── Step 13: Guard patrol route ────────────────────────────────────────
        VillagePatrolRouteBuilder.build(level, village, data);
    }

    /**
     * Legacy overload — builds footprint from village data.
     * Used by callers that don't have a pre-built footprint.
     */
    public static void decorateVillage(ServerLevel level,
                                       Village village,
                                       VillageSavedData data,
                                       VillageLayout layout) {
        BuildingFootprint footprint =
                BuildingFootprint.fromVillage(village, data);
        decorateVillage(level, village, data, layout, footprint);
    }

    // =========================================================================
    // Road upgrade — called from VillageDailyTickSystem
    // =========================================================================

    /**
     * Upgrades all road surfaces in the village to a new tier.
     * Preserves centerlines, changes surface blocks and width.
     */
    public static void upgradeStreets(ServerLevel level,
                                      Village village,
                                      VillageSavedData data,
                                      VillageBiomeStyle style,
                                      VillagePath.PathTier newTier) {
        PathMaterial material = PathMaterial.forBiomeAndTier(style, newTier);
        RoadShape.RoadTier roadTier = RoadShape.fromPathTier(newTier);
        BuildingFootprint footprint =
                BuildingFootprint.fromVillage(village, data);

        for (VillagePath path : data.getPathsForVillage(village.getId())) {
            if (path.isObsolete()) continue;

            OrganicRoadPlacer.PlacementResult result =
                    OrganicRoadPlacer.upgrade(level, path, material,
                            roadTier, footprint, level.getRandom());

            path.updatePlacedBlocks(result.placedBlocks());
            path.setTier(newTier);
        }

        data.setDirty();
        System.out.println("VillageDecorator: upgraded roads in "
                + village.getName() + " to " + newTier.name());
    }

    // =========================================================================
    // Expansion building decoration
    // =========================================================================

    /**
     * Decorates a newly placed expansion building: connects a road and
     * adds exterior decorations.
     */
    public static void decorateExpansionBuilding(ServerLevel level,
                                                 Building building,
                                                 Village village,
                                                 VillageSavedData data) {
        VillageBiomeStyle style = VillageBiomeStyle.detect(
                level, building.getShape().getOrigin());
        VillageSizeTier tier = VillageSizeTier.fromBuildingCount(
                village.getBuildingIds().size() * 2);

        BuildingFootprint footprint =
                BuildingFootprint.fromVillage(village, data);

        PathMaterial material = PathMaterial.forBiomeAndTier(
                style, village.getPathTier());
        RoadShape.RoadTier roadTier = RoadShape.fromPathTier(
                village.getPathTier());

        // Connect road
        BlockPos hub = village.getEffectivePathHub(data);
        VillageRoadNetwork roads = new VillageRoadNetwork(hub);
        roads.connectExpansionBuilding(level, building, village, data,
                material, roadTier, footprint, level.getRandom());

        // Exterior decorations
        placeExteriorDecorations(level, building, style, tier);
    }

    // =========================================================================
    // Capital street pre-placement (called by VillageSpawner BEFORE buildings)
    // =========================================================================


    // =========================================================================
    // Decoration methods (preserved from original)
    // =========================================================================

    static void placeExteriorDecorations(ServerLevel level,
                                         Building building,
                                         VillageBiomeStyle style,
                                         VillageSizeTier tier) {
        BlockPos min = building.getShape().getMin();
        BlockPos max = building.getShape().getMax();
        int floorY = building.getShape().getOrigin().getY();

        // Corner planters
        for (int[] corner : new int[][]{
                {min.getX() - 1, min.getZ() - 1},
                {max.getX() + 1, min.getZ() - 1},
                {min.getX() - 1, max.getZ() + 1},
                {max.getX() + 1, max.getZ() + 1}}) {
            int surfY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    corner[0], corner[1]);
            if (Math.abs(surfY - floorY) > 2) continue;
            BlockPos pos = new BlockPos(corner[0], surfY, corner[1]);
            if (level.getBlockState(pos).isAir()
                    && level.getBlockState(pos.below()).isSolidRender()) {
                level.setBlock(pos,
                        Blocks.COMPOSTER.defaultBlockState(), 3);
                BlockPos above = pos.above();
                if (level.getBlockState(above).isAir()) {
                    level.setBlock(above, style.flowerState(), 3);
                }
            }
        }
    }

    private static void placeProximityFurniture(ServerLevel level,
                                                List<Building> buildings,
                                                VillageBiomeStyle style) {
        for (Building b : buildings) {
            BlockPos entrance = PathRouter.getBuildingEntrance(b);
            int surfY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    entrance.getX() + 1, entrance.getZ());
            BlockPos benchPos = new BlockPos(
                    entrance.getX() + 1, surfY, entrance.getZ());
            if (level.getBlockState(benchPos).isAir()
                    && level.getBlockState(benchPos.below()).isSolidRender()) {
                level.setBlock(benchPos, style.woodSlab(), 3);
            }
        }
    }

    private static void placeMarketStalls(ServerLevel level,
                                          BlockPos squareCenter,
                                          VillageBiomeStyle style) {
        int[][] offsets = {{3, 3}, {-3, 3}, {3, -3}, {-3, -3}};
        for (int[] off : offsets) {
            int x = squareCenter.getX() + off[0];
            int z = squareCenter.getZ() + off[1];
            int surfY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos base = new BlockPos(x, surfY, z);
            if (!level.getBlockState(base).isAir()) continue;
            if (!level.getBlockState(base.below()).isSolidRender()) continue;

            level.setBlock(base, style.fenceState(), 3);
            level.setBlock(base.above(),
                    style.woodSlab(), 3);
        }
    }

    private static void placeLandmark(ServerLevel level, Building townHall,
                                      VillageBiomeStyle style,
                                      Village village) {
        BlockPos entrance = PathRouter.getBuildingEntrance(townHall);
        BlockPos pos = entrance.offset(0, 0, 2);
        int surfY = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getX(), pos.getZ());
        BlockPos base = new BlockPos(pos.getX(), surfY, pos.getZ());
        if (level.getBlockState(base).isAir()
                && level.getBlockState(base.below()).isSolidRender()) {
            level.setBlock(base, style.stoneWallState(), 3);
            level.setBlock(base.above(), style.stoneWallState(), 3);
            level.setBlock(base.above(2), style.lanternState(), 3);
        }
    }

    private static void scatterFlowers(ServerLevel level, Village village,
                                       VillageSavedData data,
                                       VillageBiomeStyle style,
                                       int attempts) {
        village.getBounds(data).ifPresent(bounds -> {
            for (int i = 0; i < attempts; i++) {
                int x = (int)(bounds.minX + level.getRandom().nextDouble()
                        * (bounds.maxX - bounds.minX));
                int z = (int)(bounds.minZ + level.getRandom().nextDouble()
                        * (bounds.maxZ - bounds.minZ));
                int surfY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos pos = new BlockPos(x, surfY, z);
                if (level.getBlockState(pos).isAir()
                        && level.getBlockState(pos.below()).is(
                        Blocks.GRASS_BLOCK)) {
                    level.setBlock(pos, style.flowerState(), 3);
                }
            }
        });
    }

    private static void placeApproachGradient(ServerLevel level,
                                              Village village,
                                              VillageSavedData data,
                                              VillageBiomeStyle style,
                                              Set<Long> pathXZ) {
        // Simplified: place coarse dirt ring outside building zone
        village.getBounds(data).ifPresent(bounds -> {
            int buffer = 6;
            for (int x = (int) bounds.minX - buffer;
                 x <= (int) bounds.maxX + buffer; x++) {
                for (int z = (int) bounds.minZ - buffer;
                     z <= (int) bounds.maxZ + buffer; z++) {
                    if (x >= bounds.minX && x <= bounds.maxX
                            && z >= bounds.minZ && z <= bounds.maxZ) continue;
                    if (pathXZ.contains(xzKey(x, z))) continue;
                    if (level.getRandom().nextFloat() > 0.3f) continue;

                    int surfY = level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos pos = new BlockPos(x, surfY - 1, z);
                    if (level.getBlockState(pos).is(Blocks.GRASS_BLOCK)) {
                        level.setBlock(pos,
                                Blocks.COARSE_DIRT.defaultBlockState(), 3);
                    }
                }
            }
        });
    }



    // =========================================================================
    // Street segment placement (used by capital streets only)
    // =========================================================================

    static List<BlockPos> placeStreetSegment(ServerLevel level,
                                             List<BlockPos> route,
                                             VillageBiomeStyle style,
                                             StreetTier tier,
                                             Set<Long> protectedXZ) {
        List<BlockPos> placed = new ArrayList<>();
        int halfWidth = tier == StreetTier.PRIMARY ? 2
                : tier == StreetTier.SECONDARY ? 1 : 0;

        for (BlockPos center : route) {
            for (int d = -halfWidth; d <= halfWidth; d++) {
                // Expand perpendicular — determine direction from route
                int wx, wz;
                if (route.size() > 1) {
                    int idx = route.indexOf(center);
                    BlockPos prev = idx > 0 ? route.get(idx - 1) : center;
                    BlockPos next = idx < route.size() - 1
                            ? route.get(idx + 1) : center;
                    int hx = Integer.signum(next.getX() - prev.getX());
                    int hz = Integer.signum(next.getZ() - prev.getZ());
                    wx = center.getX() + (-hz) * d;
                    wz = center.getZ() + hx * d;
                } else {
                    wx = center.getX() + d;
                    wz = center.getZ();
                }

                long key = xzKey(wx, wz);
                if (protectedXZ.contains(key)) continue;

                int surfY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz);
                BlockPos placePos = new BlockPos(wx, surfY - 1, wz);

                BlockState existing = level.getBlockState(placePos);
                if (existing.liquid()) continue;

                BlockState surface = style.streetStateFor(tier);
                level.setBlock(placePos, surface, 3);

                // Clear vegetation above
                BlockPos above = placePos.above();
                if (isVegetation(level.getBlockState(above))) {
                    level.setBlock(above,
                            Blocks.AIR.defaultBlockState(), 3);
                }

                placed.add(new BlockPos(wx, surfY, wz));
            }
        }
        return placed;
    }

    // =========================================================================
    // Utility methods
    // =========================================================================

    private static BlockPos resolveSquareCenter(ServerLevel level,
                                                VillageLayout layout,
                                                List<Building> buildings) {
        if (layout != null && layout.getTownSquarePos() != null) {
            return layout.getTownSquarePos();
        }
        if (layout != null && layout.getCenter() != null) {
            return layout.getCenter();
        }
        // Fallback: centroid of all buildings
        int cx = 0, cz = 0;
        for (Building b : buildings) {
            cx += b.getShape().getOrigin().getX()
                    + b.getShape().getWidth() / 2;
            cz += b.getShape().getOrigin().getZ()
                    + b.getShape().getLength() / 2;
        }
        cx /= Math.max(1, buildings.size());
        cz /= Math.max(1, buildings.size());
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

    static void placeIfClear(ServerLevel level, BlockPos pos,
                             BlockState state) {
        if (level.getBlockState(pos.below()).isSolidRender()
                && level.getBlockState(pos).isAir()) {
            level.setBlock(pos, state, 3);
        }
    }

    static long xzKey(int x, int z) {
        return ((long) x & 0xFFFFFFFFL)
                | (((long) z & 0xFFFFFFFFL) << 32);
    }

    private static List<BlockPos> straightLineRoute(BlockPos a, BlockPos b,
                                                    ServerLevel level) {
        List<BlockPos> line = new ArrayList<>();
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) { line.add(a); return line; }
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


    private static boolean isVegetation(BlockState state) {
        return state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(BlockTags.FLOWERS)
                || state.is(Blocks.DEAD_BUSH);
    }
}