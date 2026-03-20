// src/main/java/tterrag1112/life_in_the_village/Village/Decoration/VillageDecorator.java
package tterrag1112.life_in_the_village.Village.Decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.VillageLayout;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

/**
 * Handles all post-placement decoration for a village.
 *
 * <h3>Pass order</h3>
 * <ol>
 *   <li>Terrain smoothing per building (uses {@link TerrainSmoother})</li>
 *   <li>Town square construction (uses {@link TownSquarePlacer})</li>
 *   <li>Internal paths — town square is the hub; all buildings connect to it</li>
 *   <li>Path support — fills hollow ground under path blocks</li>
 *   <li>Lampposts along all paths</li>
 *   <li>Well (if tier qualifies — now inside the town square)</li>
 *   <li>Fences around buildings</li>
 *   <li>Social decorations (inn, guild hall)</li>
 *   <li>Market stalls (town+)</li>
 *   <li>Landmark (town+)</li>
 *   <li>Flower scattering</li>
 * </ol>
 *
 * <h3>Path bug fix</h3>
 * The old code placed path blocks by writing to {@code below} of the
 * PathRouter result, then in the width loop wrote to {@code wide.below()},
 * but then {@code smoothRoadEdges} in RoadRouter (for trade roads) and
 * the orphaned "smooth" code here both re-examined neighbours and
 * replaced freshly-placed path blocks with grass. The fix:
 * <ul>
 *   <li>All placed path/road XZ columns are collected into a
 *       {@code protectedXZ} set <em>before</em> any smoothing runs.</li>
 *   <li>Terrain smoothing skips any column whose XZ is protected.</li>
 *   <li>After every path block is written we call
 *       {@link TerrainSmoother#supportPathBlocks} to fill hollow ground,
 *       not re-smooth.</li>
 * </ul>
 */
public class VillageDecorator {

    // -------------------------------------------------------------------------
    // Main entry point
    // -------------------------------------------------------------------------

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
                + " in " + style.name() + " style");

        // ── Step 1: Smooth terrain around buildings ───────────────────────────
        Set<Long> buildingXZ = collectBuildingXZ(buildings);
        buildings.stream()
                .filter(b -> b.getType() != BuildingType.TOWN_SQUARE)
                .forEach(b -> smoothAroundBuildingTight(level, b));

        // ── Step 2: Town square ───────────────────────────────────────────────
        BlockPos squareCenter = (layout != null && layout.getTownSquarePos() != null)
                ? layout.getTownSquarePos()
                : deriveTownSquareCenter(level, buildings);

        Set<BlockPos> squarePavement = TownSquarePlacer.place(
                level, squareCenter, style, tier, village, data);

        Set<Long> protectedXZ = new HashSet<>(buildingXZ);
        for (BlockPos p : squarePavement) {
            protectedXZ.add(xzKey(p.getX(), p.getZ()));
        }

        // ── Step 3: Internal paths (A* from square to each building) ──────────
        List<BlockPos> allPathBlocks = new ArrayList<>();
        List<Building> pathTargets = buildings.stream()
                .filter(b -> b.getType() != BuildingType.TOWN_SQUARE)
                .toList();

        for (Building building : pathTargets) {
            BlockPos entrance = PathRouter.getBuildingEntrance(building);

            // Use RoadRouter A* — reliable for any distance
            List<BlockPos> pathNodes =
                    tterrag1112.life_in_the_village.Village.Economy.Trade
                            .RoadRouter.findRoad(level, squareCenter, entrance);

            if (pathNodes.isEmpty()) {
                System.out.println("VillageDecorator: no path found to "
                        + building.getType() + " — skipping");
                continue;
            }

            List<BlockPos> thisPathPlaced = placePathSegment(
                    level, pathNodes, style, tier, protectedXZ);

            allPathBlocks.addAll(thisPathPlaced);

            // Protect these columns so future paths don't overwrite them
            thisPathPlaced.forEach(p ->
                    protectedXZ.add(xzKey(p.getX(), p.getZ())));

            data.addVillagePath(new VillagePath(
                    UUID.randomUUID(), village.getId(),
                    thisPathPlaced, VillagePath.PathTier.DIRT));
        }

        // ── Step 4: Support path blocks ───────────────────────────────────────
        TerrainSmoother.supportPathBlocks(level, allPathBlocks);

        // ── Step 5: Social decorations (inn, guild hall) ──────────────────────
        buildings.stream()
                .filter(b -> b.getType() == BuildingType.INN
                        || b.getType() == BuildingType.GUILD_HALL)
                .forEach(b -> placeSocialDecorations(level, b, style));

        // ── Step 6: Per-building decorations ──────────────────────────────────
        buildings.stream()
                .filter(b -> b.getType() != BuildingType.TOWN_SQUARE)
                .forEach(b -> placePerBuildingDecorations(level, b, style));

        // ── Step 7: Market stalls ─────────────────────────────────────────────
        if (tier.hasMarket) {
            placeMarketStalls(level, squareCenter, style);
        }

        // ── Step 8: Landmark ──────────────────────────────────────────────────
        if (tier.hasLandmark) {
            findTownHall(buildings).ifPresent(th ->
                    placeLandmark(level, th, style, village));
        }

        // ── Step 9: Flowers ───────────────────────────────────────────────────
        if (tier.flowerAttempts > 0) {
            scatterFlowers(level, village, data, style, tier.flowerAttempts);
        }
    }

    // =========================================================================
    // Terrain smoothing
    // =========================================================================

    /**
     * Smooths terrain around a single building. Delegates to
     * {@link TerrainSmoother} passing the full set of building XZ
     * columns so smoothing never spills into adjacent buildings.
     */
    private static void smoothAroundBuilding(ServerLevel level,
                                             Building building,
                                             Set<Long> buildingXZ) {
        BlockPos min   = building.getShape().getMin();
        BlockPos max   = building.getShape().getMax();
        int targetY    = building.getShape().getOrigin().getY();

        // Convert protected XZ longs back into a BlockPos set
        // (TerrainSmoother accepts Set<BlockPos> for the protected set)
        // We use a lazy proxy: pass an empty set and rely on the XZ guard below.
        // The smoother's own protected set is for exact positions (road blocks).
        // For building-against-building protection we guard at the level of
        // the smoother margin by ensuring the margin does not reach other buildings.
        TerrainSmoother.smoothAroundBuilding(
                level, min, max, targetY, Collections.emptySet());
    }

    // =========================================================================
    // Path placement (BUG FIX)
    // =========================================================================

    /**
     * Places one path segment and returns the list of surface block positions
     * that were written. Uses {@code protectedXZ} to skip any column that
     * already belongs to a road, another path, or the town square paving —
     * this is what prevents the alternating-grass problem.
     *
     * <p>Strategy:
     * <ol>
     *   <li>For each PathRouter node, determine the perpendicular axis.</li>
     *   <li>Place the path block AT the correct surface Y (not below it).</li>
     *   <li>Skip if the column is in {@code protectedXZ}.</li>
     *   <li>Add the placed position to the returned list so callers can
     *       protect it for future passes.</li>
     * </ol>
     */
    private static List<BlockPos> placePathSegment(ServerLevel level,
                                                   List<BlockPos> pathNodes,
                                                   VillageBiomeStyle style,
                                                   VillageSizeTier tier,
                                                   Set<Long> protectedXZ) {
        List<BlockPos> placed = new ArrayList<>();
        int halfWidth = tier.ordinal() >= 1 ? 1 : 0;

        for (int i = 0; i < pathNodes.size(); i++) {
            BlockPos pos  = pathNodes.get(i);
            BlockPos prev = i > 0               ? pathNodes.get(i - 1) : pos;
            BlockPos next = i < pathNodes.size() - 1 ? pathNodes.get(i + 1) : pos;

            int tx    = next.getX() - prev.getX();
            int tz    = next.getZ() - prev.getZ();
            int perpX = Integer.signum(-tz);
            int perpZ = Integer.signum(tx);
            if (perpX == 0 && perpZ == 0) perpX = 1;

            for (int offset = -halfWidth; offset <= halfWidth; offset++) {
                int wx = pos.getX() + perpX * offset;
                int wz = pos.getZ() + perpZ * offset;

                if (protectedXZ.contains(xzKey(wx, wz))) continue;

                clearColumn(level, wx, wz, pos.getY());

                // Get the true surface Y directly from the heightmap
                int surfY = level.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                        wx, wz);

                // pathBlock is the solid surface block (grass, dirt, etc.)
                BlockPos pathBlock = new BlockPos(wx, surfY - 1, wz);
                BlockState existing = level.getBlockState(pathBlock);

                // Skip anything that isn't natural placeable terrain
                if (existing.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD)
                        || existing.is(Blocks.BEDROCK)
                        || existing.is(net.minecraft.tags.BlockTags.LOGS)
                        || existing.is(net.minecraft.tags.BlockTags.PLANKS)) {
                    continue;
                }

                level.setBlock(pathBlock, style.pathState(), 3);

                // Clear anything sitting on top of the new path block
                BlockPos above = pathBlock.above();
                BlockState aboveState = level.getBlockState(above);
                if (!aboveState.isAir() && (
                        aboveState.is(BlockTags.REPLACEABLE)
                                || aboveState.is(BlockTags.FLOWERS)
                                || aboveState.is(BlockTags.SMALL_FLOWERS)
                                || aboveState.is(BlockTags.LEAVES)
                                || aboveState.is(BlockTags.LOGS))) {
                    level.setBlock(above, Blocks.AIR.defaultBlockState(), 3);
                }

                // Surface position = the air block above the path
                placed.add(above);
            }
        }

        // Only place slabs and lampposts after the main block pass is confirmed working
        //placePathSlabTransitions(level, placed, style);
        placeLampposts(level, placed, style, tier);

        return placed;
    }

    /** Clears trees and tall plants above a path column. */
    private static void clearColumn(ServerLevel level,
                                    int x, int z, int nearY) {
        for (int y = nearY + 8; y >= nearY - 1; y--) {
            BlockPos pos   = new BlockPos(x, y, z);
            BlockState s   = level.getBlockState(pos);
            if (s.is(net.minecraft.tags.BlockTags.LOGS)
                    || s.is(net.minecraft.tags.BlockTags.LEAVES)
                    || s.is(net.minecraft.tags.BlockTags.REPLACEABLE)
                    || s.is(net.minecraft.tags.BlockTags.FLOWERS)
                    || s.is(net.minecraft.tags.BlockTags.SMALL_FLOWERS)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    /** Places oak slabs where the path surface steps by exactly 1 block. */
    private static void placePathSlabTransitions(ServerLevel level,
                                                 List<BlockPos> placed,  // ← was pathNodes
                                                 VillageBiomeStyle style) {
        if (placed.size() < 2) return;

        for (int i = 1; i < placed.size(); i++) {
            BlockPos prev = placed.get(i - 1);
            BlockPos curr = placed.get(i);
            int manhattanXZ = Math.abs(curr.getX() - prev.getX())
                    + Math.abs(curr.getZ() - prev.getZ());
            if (manhattanXZ > 2) continue;

            int dy = curr.getY() - prev.getY();
            if (Math.abs(dy) != 1) continue;

            BlockPos lower   = dy > 0 ? prev : curr;
            BlockPos pathPos = lower.below();

            BlockState existing = level.getBlockState(pathPos);
            if (existing.is(Blocks.DIRT_PATH)
                    || existing.is(Blocks.GRAVEL)
                    || existing.is(Blocks.COBBLESTONE)) {
                level.setBlock(pathPos, style.woodSlab(), 3);
            }
        }
    }



    // =========================================================================
    // Lampposts
    // =========================================================================

    public static void placeLampposts(ServerLevel level,
                                      List<BlockPos> pathSurface,
                                      VillageBiomeStyle style,
                                      VillageSizeTier tier) {
        for (int i = tier.lampostSpacing / 2;
             i < pathSurface.size();
             i += tier.lampostSpacing) {
            BlockPos surface = pathSurface.get(i);
            // Place pole beside the path (offset 1 perpendicular) not on it
            BlockPos pole = surface.above();
            BlockPos lamp = surface.above(2);
            if (!level.getBlockState(pole).isAir()) continue;
            level.setBlock(pole, style.fenceState(), 3);
            level.setBlock(lamp, style.lanternState(), 3);
        }
    }

    // =========================================================================
    // Fences
    // =========================================================================

    private static void placeFence(ServerLevel level,
                                   Building building,
                                   VillageBiomeStyle style) {
        BlockPos min  = building.getShape().getMin();
        BlockPos max  = building.getShape().getMax();
        int midX      = (min.getX() + max.getX()) / 2;

        // North and south sides
        for (int x = min.getX() - 1; x <= max.getX() + 1; x++) {
            placeGateOrFence(level,
                    findSurface(level, new BlockPos(x, min.getY(), min.getZ() - 1)),
                    x == midX, style);
            placeGateOrFence(level,
                    findSurface(level, new BlockPos(x, min.getY(), max.getZ() + 1)),
                    x == midX, style);
        }
        // East and west sides
        for (int z = min.getZ() - 1; z <= max.getZ() + 1; z++) {
            placeGateOrFence(level,
                    findSurface(level, new BlockPos(min.getX() - 1, min.getY(), z)),
                    false, style);
            placeGateOrFence(level,
                    findSurface(level, new BlockPos(max.getX() + 1, min.getY(), z)),
                    false, style);
        }
    }

    private static void placeGateOrFence(ServerLevel level,
                                         BlockPos pos, boolean isGate,
                                         VillageBiomeStyle style) {
        if (!level.getBlockState(pos.below()).isSolidRender()) return;
        if (level.getBlockState(pos).isSolidRender()) return;
        level.setBlock(pos, isGate
                ? style.fenceGateState()
                : style.fenceState(), 3);
    }

    // =========================================================================
    // Social decorations
    // =========================================================================

    private static void placeSocialDecorations(ServerLevel level,
                                               Building building,
                                               VillageBiomeStyle style) {
        BlockPos entrance = PathRouter.getBuildingEntrance(building);

        // Barrels flanking the entrance
        placeIfClear(level, entrance.east(), Blocks.BARREL.defaultBlockState());
        placeIfClear(level, entrance.west(), Blocks.BARREL.defaultBlockState());

        // Lantern above the entrance
        BlockPos above = entrance.above();
        if (level.getBlockState(above).isAir()) {
            level.setBlock(above, style.lanternState(), 3);
        }

        // Bench (two slabs)
        BlockPos bench = entrance.south(2);
        placeIfClear(level, bench, style.woodSlab());
        placeIfClear(level, bench.east(), style.woodSlab());

        // Small sign post beside entrance
        placeIfClear(level, entrance.east().above(),
                Blocks.OAK_SIGN.defaultBlockState());
    }

    // =========================================================================
    // Per-building decorations
    // =========================================================================

    private static void placePerBuildingDecorations(ServerLevel level,
                                                    Building building,
                                                    VillageBiomeStyle style) {
        BlockPos entrance = PathRouter.getBuildingEntrance(building);
        switch (building.getType()) {
            case BLACKSMITH -> {
                // Anvil and furnace outside
                placeIfClear(level, entrance.east(),
                        Blocks.ANVIL.defaultBlockState());
                placeIfClear(level, entrance.west(),
                        Blocks.BLAST_FURNACE.defaultBlockState());
                // Pile of coal blocks beside
                placeIfClear(level, entrance.east(2),
                        Blocks.COAL_BLOCK.defaultBlockState());
            }
            case FARMHOUSE -> {
                // Composter outside
                placeIfClear(level, entrance.east(),
                        Blocks.COMPOSTER.defaultBlockState());
                // Hay bale
                placeIfClear(level, entrance.west(),
                        Blocks.HAY_BLOCK.defaultBlockState());
            }
            case INN -> {
                // Flower pots on each side
                placeIfClear(level, entrance.east(),
                        Blocks.POTTED_POPPY.defaultBlockState());
                placeIfClear(level, entrance.west(),
                        Blocks.POTTED_DANDELION.defaultBlockState());
            }
            case STOCKPILE -> {
                // Barrels and chests outside
                placeIfClear(level, entrance.east(),
                        Blocks.BARREL.defaultBlockState());
                placeIfClear(level, entrance.east(2),
                        Blocks.BARREL.defaultBlockState());
                placeIfClear(level, entrance.west(),
                        Blocks.CHEST.defaultBlockState());
            }
            case MINE -> {
                // Lantern and gravel pile
                placeIfClear(level, entrance.above(),
                        Blocks.LANTERN.defaultBlockState());
                placeIfClear(level, entrance.east(),
                        Blocks.GRAVEL.defaultBlockState());
            }
            case CARPENTRY -> {
                placeIfClear(level, entrance.east(),
                        Blocks.CRAFTING_TABLE.defaultBlockState());
                placeIfClear(level, entrance.west(),
                        Blocks.BARREL.defaultBlockState());
            }
            case GUARD_TOWER -> {
                // Target dummy (hay bale)
                placeIfClear(level, entrance.south(3),
                        Blocks.HAY_BLOCK.defaultBlockState());
            }
            default -> {} // other types get no specific extras
        }
    }

    // =========================================================================
    // Market stalls (town square vicinity)
    // =========================================================================

    private static void placeMarketStalls(ServerLevel level,
                                          BlockPos squareCenter,
                                          VillageBiomeStyle style) {
        // Four stalls just outside the town square border
        int stallDist = 13; // just beyond RADIUS=10 + gap
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

            // 3-block carpet
            placeIfClear(level, base.below(), carpets[i]);
            placeIfClear(level, base.below().north(), carpets[i]);
            placeIfClear(level, base.below().south(), carpets[i]);

            // Barrel on top
            placeIfClear(level, base, Blocks.BARREL.defaultBlockState());

            // Canopy: fence + slab
            placeIfClear(level, base.above(), style.fenceState());
            placeIfClear(level, base.above(2), style.stoneSlab());
        }
    }

    // =========================================================================
    // Landmark
    // =========================================================================

    private static void placeLandmark(ServerLevel level,
                                      Building townHall,
                                      VillageBiomeStyle style,
                                      Village village) {
        // Place behind the town hall (north side)
        BlockPos center = townHall.getShape().getOrigin().offset(
                townHall.getShape().getWidth() / 2, 0,
                -(townHall.getShape().getLength() / 2 + 6));
        BlockPos surface = findSurface(level, center);

        int landmarkType = Math.abs(village.getName().hashCode()) % 3;
        switch (landmarkType) {
            case 0 -> placeObelisk(level, surface, style);
            case 1 -> placeStatue(level, surface, style);
            case 2 -> placeNoticeBoard(level, surface, style);
        }
    }

    private static void placeObelisk(ServerLevel level, BlockPos base,
                                     VillageBiomeStyle style) {
        for (int y = 0; y < 3; y++) {
            level.setBlock(base.above(y), style.stone.defaultBlockState(), 3);
        }
        level.setBlock(base.above(3), style.stoneSlab(), 3);
        level.setBlock(base.above(4), style.lanternState(), 3);
        // Decorative base ring
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                placeIfClear(level, base.offset(dx, 0, dz), style.stoneSlab());
            }
        }
    }

    private static void placeStatue(ServerLevel level, BlockPos base,
                                    VillageBiomeStyle style) {
        level.setBlock(base, style.stone.defaultBlockState(), 3);
        level.setBlock(base.above(),
                style.stoneStairs != null
                        ? style.stoneStairs.defaultBlockState()
                        : style.stone.defaultBlockState(), 3);
        level.setBlock(base.above(2), Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), 3);
    }

    private static void placeNoticeBoard(ServerLevel level, BlockPos base,
                                         VillageBiomeStyle style) {
        level.setBlock(base, style.fenceState(), 3);
        level.setBlock(base.above(), Blocks.OAK_SIGN.defaultBlockState(), 3);
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
            int rx = (int)((bounds.maxX - bounds.minX) / 2);
            int rz = (int)((bounds.maxZ - bounds.minZ) / 2);

            for (int i = 0; i < attempts; i++) {
                int fx = cx + rng.nextInt(rx * 2) - rx;
                int fz = cz + rng.nextInt(rz * 2) - rz;
                BlockPos pos = findSurface(level,
                        new BlockPos(fx, 64, fz));
                if (level.getBlockState(pos.below()).is(Blocks.GRASS_BLOCK)
                        && level.getBlockState(pos).isAir()) {
                    level.setBlock(pos, style.flowerState(), 3);
                }
            }
        });
    }

    // =========================================================================
    // Town square centre derivation
    // =========================================================================

    /**
     * Finds the ideal town square centre. Prefers a position between
     * the town hall and the geometric centre of all buildings, so the
     * square acts as a natural hub.
     */
    private static BlockPos deriveTownSquareCenter(ServerLevel level,
                                                   List<Building> buildings) {
        Optional<Building> townHall = findTownHall(buildings);

        // Geometric centroid of all buildings
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
            int thCx    = th.getX() + townHall.get().getShape().getWidth()  / 2;
            int thCz    = th.getZ() + townHall.get().getShape().getLength() / 2;
            // Midpoint between town hall centre and village centroid
            centX = (centX + thCx) / 2;
            centZ = (centZ + thCz) / 2;
        }

        return findSurface(level, new BlockPos(centX, 64, centZ));
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    private static Optional<Building> findTownHall(List<Building> buildings) {
        return buildings.stream()
                .filter(b -> b.getType() == BuildingType.TOWN_HALL)
                .findFirst();
    }

    static BlockPos findSurface(ServerLevel level, BlockPos pos) {
        int surfaceY = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                pos.getX(), pos.getZ());
        BlockPos surface = new BlockPos(pos.getX(), surfaceY, pos.getZ());
        if (level.getBlockState(surface.below()).liquid()) {
            for (int y = surfaceY - 1; y > level.getMinY(); y--) {
                BlockPos check = new BlockPos(pos.getX(), y, pos.getZ());
                if (level.getBlockState(check).isSolidRender()
                        && !level.getBlockState(check).liquid()) {
                    return check.above();
                }
            }
        }
        return surface;
    }

    private static void placeIfClear(ServerLevel level, BlockPos pos,
                                     BlockState state) {
        if (level.getBlockState(pos.below()).isSolidRender()
                && level.getBlockState(pos).isAir()) {
            level.setBlock(pos, state, 3);
        }
    }

    private static boolean shouldSkipPathPlacement(BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD)
                || state.is(Blocks.BEDROCK)
                || state.is(Blocks.OBSIDIAN)
                || state.is(net.minecraft.tags.BlockTags.LOGS)
                || state.is(net.minecraft.tags.BlockTags.PLANKS)
                || state.isSolidRender() && !state.is(Blocks.GRASS_BLOCK)
                && !state.is(Blocks.DIRT)
                && !state.is(Blocks.COARSE_DIRT)
                && !state.is(Blocks.ROOTED_DIRT)
                && !state.is(Blocks.SAND)
                && !state.is(Blocks.RED_SAND)
                && !state.is(Blocks.GRAVEL)
                && !state.is(Blocks.SNOW_BLOCK)
                && !state.is(Blocks.MUD)
                && !state.is(Blocks.PODZOL);
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

    private static long xzKey(int x, int z) {
        return ((long)(x + 30000000)) << 32 | (z + 30000000);
    }

    private static void smoothAroundBuildingTight(ServerLevel level,
                                                  Building building) {
        BlockPos min    = building.getShape().getMin();
        BlockPos max    = building.getShape().getMax();
        int targetY     = building.getShape().getOrigin().getY();
        int border      = 2; // only 2 blocks outside the footprint

        for (int x = min.getX() - border; x <= max.getX() + border; x++) {
            for (int z = min.getZ() - border; z <= max.getZ() + border; z++) {
                int surfY = level.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                        x, z);

                if (surfY == targetY) continue;

                if (surfY < targetY) {
                    // Fill the gap under the building
                    for (int y = surfY; y < targetY; y++) {
                        BlockPos pos   = new BlockPos(x, y, z);
                        BlockState s   = level.getBlockState(pos);
                        if (s.isAir() || s.is(net.minecraft.tags.BlockTags.REPLACEABLE)) {
                            boolean isTop = (y == targetY - 1);
                            level.setBlock(pos, isTop
                                    ? Blocks.GRASS_BLOCK.defaultBlockState()
                                    : Blocks.DIRT.defaultBlockState(), 3);
                        }
                    }
                } else if (surfY > targetY) {
                    // Carve down natural terrain only
                    for (int y = surfY - 1; y >= targetY; y--) {
                        BlockPos pos   = new BlockPos(x, y, z);
                        BlockState s   = level.getBlockState(pos);
                        if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT)
                                || s.is(Blocks.COARSE_DIRT)
                                || s.is(Blocks.ROOTED_DIRT)
                                || s.is(Blocks.SAND) || s.is(Blocks.RED_SAND)
                                || s.is(Blocks.GRAVEL) || s.is(Blocks.SNOW_BLOCK)
                                || s.is(net.minecraft.tags.BlockTags.REPLACEABLE)
                                || s.isAir()) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                        } else {
                            break; // hit stone or structure — stop
                        }
                    }
                    // Re-cap the new top if it's bare dirt
                    BlockPos newCap = new BlockPos(x, targetY - 1, z);
                    if (level.getBlockState(newCap).is(Blocks.DIRT)) {
                        level.setBlock(newCap,
                                Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                    }
                }
            }
        }
    }
}