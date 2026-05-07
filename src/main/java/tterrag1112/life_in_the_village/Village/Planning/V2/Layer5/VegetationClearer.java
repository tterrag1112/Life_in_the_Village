package tterrag1112.life_in_the_village.Village.Planning.V2.Layer5;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.Footprint;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadSegment;

/**
 * V2 Layer 5 — scoped vegetation removal.
 *
 * <p>Replaces foliage (logs, leaves, saplings, flowers, grass,
 * mushrooms, vines, sugar cane, bamboo) with air over a
 * narrowly-bounded region per building or road segment. Does NOT
 * touch terrain blocks (stone, dirt, grass, sand, etc.).
 *
 * <p>Crucially: outside the per-building / per-road regions,
 * trees stay where they are. The minimal V2 spawner does NOT
 * smooth, fill, or clear the broader area.
 *
 * <p>For each column in the bounded region, walks from surface y
 * upward, replacing any foliage block with air; stops at the
 * first non-foliage non-air block above to avoid drilling into
 * floating islands.
 */
public final class VegetationClearer {

    /** How far above the surface to scan for foliage. Tall trees
     *  reach ~30 blocks; bamboo can be taller, but 40 is enough for
     *  V1's broad-leaf forest case. */
    private static final int VERTICAL_SCAN = 40;

    private VegetationClearer() {}

    /** Clear foliage inside the building's footprint AABB plus
     *  {@code buffer} blocks on each side. Returns the count of
     *  blocks replaced. */
    public static int clearForBuilding(ServerLevel level, PlacedBuilding b, int buffer) {
        int[] aabb = footprintAabb(b);
        return clearAabb(level,
                aabb[0] - buffer, aabb[1] - buffer,
                aabb[2] + buffer, aabb[3] + buffer);
    }

    /** Clear foliage along the segment's corridor. Walks the
     *  centerline at 1-block resolution; at each step clears
     *  perpendicular cells out to {@code segment.width()/2 + buffer}.
     *  Returns the count of blocks replaced. */
    public static int clearForRoadSegment(ServerLevel level, RoadSegment segment, int buffer) {
        BlockPos a = segment.start();
        BlockPos b = segment.end();
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, (int) Math.round(len));
        int corridor = (segment.width() + 1) / 2 + buffer;

        // Approximate the corridor as the bounding rectangle of the
        // sampled cells. Walking the centerline + perpendicular at
        // 1-block resolution is exact enough for V1.
        int total = 0;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int cx = (int) Math.round(a.getX() + dx * t);
            int cz = (int) Math.round(a.getZ() + dz * t);
            for (int ox = -corridor; ox <= corridor; ox++) {
                for (int oz = -corridor; oz <= corridor; oz++) {
                    total += clearColumn(level, cx + ox, cz + oz);
                }
            }
        }
        return total;
    }

    private static int clearAabb(ServerLevel level, int minX, int minZ,
                                 int maxX, int maxZ) {
        int total = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                total += clearColumn(level, x, z);
            }
        }
        return total;
    }

    /** Walk upward from surface y replacing foliage with air; stop at
     *  the first non-foliage, non-air block. */
    private static int clearColumn(ServerLevel level, int x, int z) {
        int surfY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int top = Math.min(level.getMaxY(), surfY + VERTICAL_SCAN);
        int cleared = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = surfY; y <= top; y++) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (state.isAir()) {
                // Empty above — keep walking; trees often have small
                // air gaps inside the canopy.
                continue;
            }
            if (isFoliage(state)) {
                level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 2);
                cleared++;
            } else {
                // Non-foliage, non-air — stop. Avoids drilling into
                // floating islands or man-made overhead structures.
                break;
            }
        }
        return cleared;
    }

    private static boolean isFoliage(BlockState state) {
        if (state.is(BlockTags.LOGS)) return true;
        if (state.is(BlockTags.LEAVES)) return true;
        if (state.is(BlockTags.SAPLINGS)) return true;
        if (state.is(BlockTags.FLOWERS)) return true;
        if (state.is(BlockTags.SMALL_FLOWERS)) return true;
        if (state.is(BlockTags.FLOWERS)) return true;
        if (state.is(BlockTags.REPLACEABLE_BY_TREES)) return true;
        if (state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.VINE)
                || state.is(Blocks.SUGAR_CANE)
                || state.is(Blocks.BAMBOO)
                || state.is(Blocks.BAMBOO_SAPLING)
                || state.is(Blocks.RED_MUSHROOM)
                || state.is(Blocks.BROWN_MUSHROOM)
                || state.is(Blocks.LILY_PAD)
                || state.is(Blocks.DEAD_BUSH)) return true;
        return false;
    }

    /** Returns {@code [minX, minZ, maxX, maxZ]} for the rotated footprint. */
    private static int[] footprintAabb(PlacedBuilding b) {
        Footprint fp = b.footprint();
        boolean swap = b.rotation() == Rotation.CLOCKWISE_90
                || b.rotation() == Rotation.COUNTERCLOCKWISE_90;
        int rotW = swap ? fp.length() : fp.width();
        int rotL = swap ? fp.width() : fp.length();
        int halfW = rotW / 2;
        int halfL = rotL / 2;
        BlockPos c = b.centre();
        return new int[]{c.getX() - halfW, c.getZ() - halfL,
                c.getX() + halfW, c.getZ() + halfL};
    }
}
