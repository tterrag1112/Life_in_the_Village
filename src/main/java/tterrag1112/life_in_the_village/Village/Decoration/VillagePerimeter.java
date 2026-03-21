// src/main/java/tterrag1112/life_in_the_village/Village/Decoration/VillagePerimeter.java
package tterrag1112.life_in_the_village.Village.Decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.*;

/**
 * Places a perimeter boundary around a village.
 *
 * <h3>Tier behaviour</h3>
 * <ul>
 *   <li><b>HAMLET</b>: no perimeter</li>
 *   <li><b>VILLAGE</b>: simple wooden palisade (oak fence posts + planks),
 *       gaps at every path intersection</li>
 *   <li><b>TOWN</b>: stone wall (cobblestone wall blocks), gatehouse
 *       structure at primary street intersections</li>
 *   <li><b>CITY</b>: full stone wall with merlons (stone brick wall +
 *       stone brick slabs on top)</li>
 * </ul>
 *
 * <h3>Gap logic</h3>
 * A gap (no perimeter block) is placed at every XZ column that belongs
 * to a registered {@link VillagePath} or trade road, so that the existing
 * paths flow naturally through the perimeter.
 */
public class VillagePerimeter {

    /** Outward offset from outermost building footprint to perimeter line. */
    private static final int PERIMETER_OFFSET = 4;

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void place(ServerLevel level,
                             Village village,
                             VillageSavedData data,
                             VillageBiomeStyle style,
                             VillageSizeTier tier,
                             Set<Long> pathXZ) {
        if (tier == VillageSizeTier.HAMLET) return;

        List<BlockPos> perimeterLine = computePerimeterLine(
                village, data, PERIMETER_OFFSET);
        if (perimeterLine.isEmpty()) return;

        System.out.println("VillagePerimeter: placing "
                + tier.displayName + " perimeter");

        // Track gap positions for threshold features
        List<BlockPos> gapPositions = new ArrayList<>();

        for (BlockPos pos : perimeterLine) {
            long xzKey = xzKey(pos.getX(), pos.getZ());
            int surfY  = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    pos.getX(), pos.getZ());

            if (pathXZ.contains(xzKey)) {
                // This is a road/path crossing — record as a gate gap
                gapPositions.add(new BlockPos(pos.getX(), surfY, pos.getZ()));
                continue;
            }

            placePerimeterColumn(level, pos.getX(), pos.getZ(),
                    surfY, tier, style);
        }

        // Place threshold features at each gate gap
        for (BlockPos gap : gapPositions) {
            placeGateThreshold(level, gap, style, tier, village, data);
        }
    }

    /**
     * Places the threshold features at a perimeter gate crossing:
     *
     * <ul>
     *   <li><b>Outside</b>: worn-dirt apron (3×3 coarse dirt) showing
     *       foot traffic approaching the gate</li>
     *   <li><b>Gate posts</b>: two fence/wall posts flanking the gap,
     *       one block inside and one outside, with a lantern on top</li>
     *   <li><b>Inside</b>: a notice board (lectern on fence post) or
     *       small planter to mark the entry point</li>
     * </ul>
     */
    private static void placeGateThreshold(ServerLevel level,
                                           BlockPos gap,
                                           VillageBiomeStyle style,
                                           VillageSizeTier tier,
                                           Village village,
                                           VillageSavedData data) {
        // Determine which axis the road runs along by checking nearest path
        // blocks — we want to place posts perpendicular to the road
        // For simplicity: if a path block exists to the north/south of gap,
        // road runs N/S so posts go east/west, and vice versa
        BlockPos postEast = gap.east(2);
        BlockPos postWest = gap.west(2);

        int surfEast = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                postEast.getX(), postEast.getZ());
        int surfWest = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                postWest.getX(), postWest.getZ());

        // Gate posts (fence + lantern) flanking the gap
        BlockPos postE = new BlockPos(postEast.getX(), surfEast, postEast.getZ());
        BlockPos postW = new BlockPos(postWest.getX(), surfWest, postWest.getZ());

        if (level.getBlockState(postE).isAir()
                || isNaturalTerrain(level.getBlockState(postE))) {
            level.setBlock(postE, style.fenceState(), 3);
            level.setBlock(postE.above(), style.lanternState(), 3);
        }
        if (level.getBlockState(postW).isAir()
                || isNaturalTerrain(level.getBlockState(postW))) {
            level.setBlock(postW, style.fenceState(), 3);
            level.setBlock(postW.above(), style.lanternState(), 3);
        }

        // Worn dirt apron outside the gate (3 blocks outward from gap)
        for (int step = 1; step <= 3; step++) {
            for (int side = -2; side <= 2; side++) {
                // Try both N and S directions for "outside"
                for (int dir : new int[]{-1, 1}) {
                    BlockPos apron = gap.offset(side, 0, dir * step);
                    int apronY = level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            apron.getX(), apron.getZ());
                    BlockPos apronBlock = new BlockPos(
                            apron.getX(), apronY, apron.getZ());
                    BlockState apronState = level.getBlockState(apronBlock);
                    if (apronState.is(Blocks.GRASS_BLOCK)) {
                        // Probability decreases with distance from gate
                        float chance = 1.0f - (step * 0.25f);
                        if (Math.random() < chance) {
                            level.setBlock(apronBlock,
                                    Blocks.COARSE_DIRT.defaultBlockState(), 3);
                        }
                    }
                }
            }
        }

        // Inside feature — notice board on the inward side
        BlockPos insideFeature = gap.south(3); // assumes gate faces south
        int insideY = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                insideFeature.getX(), insideFeature.getZ());
        BlockPos insidePos = new BlockPos(
                insideFeature.getX(), insideY + 1, insideFeature.getZ());

        if (level.getBlockState(insidePos).isAir()
                && level.getBlockState(insidePos.below()).isSolidRender()) {
            // Alternate between notice board and planter per gate
            boolean noticeBoard = (gap.getX() + gap.getZ()) % 2 == 0;
            if (noticeBoard) {
                level.setBlock(insidePos, style.fenceState(), 3);
                level.setBlock(insidePos.above(),
                        Blocks.LECTERN.defaultBlockState(), 3);
            } else {
                level.setBlock(insidePos,
                        Blocks.COMPOSTER.defaultBlockState(), 3);
                level.setBlock(insidePos.above(), style.flowerState(), 3);
            }
        }
    }

    private static boolean isNaturalTerrain(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT)
                || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.SAND)
                || s.is(Blocks.GRAVEL) || s.is(BlockTags.REPLACEABLE)
                || s.isAir();
    }

    // =========================================================================
    // Perimeter block placement
    // =========================================================================

    private static void placePerimeterColumn(ServerLevel level,
                                             int x, int z,
                                             int surfY,
                                             VillageSizeTier tier,
                                             VillageBiomeStyle style) {
        BlockPos base = new BlockPos(x, surfY, z);
        BlockState belowState = level.getBlockState(base.below());
        if (!belowState.isSolidRender()) return; // no ground support

        switch (tier) {
            case VILLAGE -> {
                // Palisade: alternating fence posts and planks, 2 high
                boolean isPost = (x + z) % 3 == 0;
                level.setBlock(base,
                        isPost ? style.fenceState()
                                : Blocks.OAK_PLANKS.defaultBlockState(), 3);
                level.setBlock(base.above(),
                        isPost ? style.fenceState()
                                : Blocks.OAK_PLANKS.defaultBlockState(), 3);
            }
            case TOWN -> {
                // Stone wall, 3 high
                level.setBlock(base,
                        style.stoneWallState(), 3);
                level.setBlock(base.above(),
                        style.stoneWallState(), 3);
                level.setBlock(base.above(2),
                        style.stoneSlab(), 3);
            }
            case CITY -> {
                // Stone brick wall with merlons, 4 high
                level.setBlock(base,
                        Blocks.STONE_BRICK_WALL.defaultBlockState(), 3);
                level.setBlock(base.above(),
                        Blocks.STONE_BRICK_WALL.defaultBlockState(), 3);
                level.setBlock(base.above(2),
                        Blocks.STONE_BRICK_WALL.defaultBlockState(), 3);
                // Merlon pattern: every other block gets a top slab
                if ((x + z) % 2 == 0) {
                    level.setBlock(base.above(3),
                            Blocks.STONE_BRICK_SLAB.defaultBlockState(), 3);
                }
            }
            default -> {}
        }
    }

    // =========================================================================
    // Perimeter line computation
    // =========================================================================

    /**
     * Computes a simple perimeter polygon by finding the bounding positions
     * of all buildings, expanding outward by {@code offset} blocks, and
     * tracing the outline.
     */
    private static List<BlockPos> computePerimeterLine(Village village,
                                                       VillageSavedData data,
                                                       int offset) {
        List<Building> buildings = village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() != BuildingType.TOWN_SQUARE)
                .toList();

        if (buildings.isEmpty()) return List.of();

        // Find centroid
        int cx = 0, cz = 0;
        for (Building b : buildings) {
            cx += b.getShape().getOrigin().getX() + b.getShape().getWidth() / 2;
            cz += b.getShape().getOrigin().getZ() + b.getShape().getLength() / 2;
        }
        cx /= buildings.size();
        cz /= buildings.size();

        // Find max radius from centroid to any building corner + offset
        int maxRadius = 0;
        for (Building b : buildings) {
            int bCx = b.getShape().getOrigin().getX() + b.getShape().getWidth() / 2;
            int bCz = b.getShape().getOrigin().getZ() + b.getShape().getLength() / 2;
            int r   = (int) Math.sqrt(Math.pow(bCx - cx, 2)
                    + Math.pow(bCz - cz, 2))
                    + Math.max(b.getShape().getWidth(),
                    b.getShape().getLength()) / 2;
            if (r > maxRadius) maxRadius = r;
        }
        maxRadius += offset;

        // Trace a circle at that radius — dense enough that every block is covered
        List<BlockPos> line = new ArrayList<>();
        int circumference = (int)(2 * Math.PI * maxRadius);
        for (int i = 0; i < circumference; i++) {
            double angle = 2 * Math.PI * i / circumference;
            int px = cx + (int)(Math.cos(angle) * maxRadius);
            int pz = cz + (int)(Math.sin(angle) * maxRadius);
            BlockPos pos = new BlockPos(px, 64, pz);
            // Deduplicate adjacent identical XZ positions
            if (!line.isEmpty()) {
                BlockPos last = line.get(line.size() - 1);
                if (last.getX() == px && last.getZ() == pz) continue;
            }
            line.add(pos);
        }
        return line;
    }

    private static long xzKey(int x, int z) {
        return ((long)(x + 30_000_000)) << 32 | (z + 30_000_000);
    }
}