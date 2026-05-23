package tterrag1112.life_in_the_village.Village.Farms.Complex.Render;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;

import java.util.List;
import java.util.Random;

/**
 * Detour A Prompt B Stage F — scatter "lived-in" props around a
 * farm complex.
 *
 * <p>5-10 props placed at sparse positions inside the region but
 * outside any plot polygon, away from paths, away from borders.
 * Each prop is small (1-3 blocks) and code-generated so no NBT
 * is required.
 *
 * <p>Palette: hay-bale stacks, hitching posts, compost heaps,
 * stacked crates (chest stacks), woodpiles, seed-bag chests.
 * Random weighted selection — heavier toward hay/woodpile (most
 * common visual filler on a working farm).
 *
 * <p>Defensive: if no plot-free position can be found inside the
 * region in a reasonable number of attempts, just emits fewer
 * props rather than blocking.
 */
public final class PropsScatterRenderer {

    private static final int PROBE_ATTEMPTS_PER_PROP = 16;

    private PropsScatterRenderer() {}

    /** Scatter 5-10 props inside {@code region}, outside every
     *  {@code plotPolygons[i]}.
     *
     *  @param region          complex region polygon
     *  @param plotPolygons    polygons to avoid (plot interiors)
     *  @param level           target level
     *  @param rng             deterministic RNG
     *  @return number of props actually placed (may be < target
     *          if probes failed) */
    public static int render(Polygon region,
                              List<Polygon> plotPolygons,
                              ServerLevel level,
                              Random rng) {
        if (region == null) return 0;
        int target = 5 + rng.nextInt(6);   // 5..10
        Polygon.AABB bb = Polygon.boundingBox(region);
        int placed = 0;
        for (int i = 0; i < target; i++) {
            BlockPos at = pickFreePosition(region, plotPolygons, bb, level, rng);
            if (at == null) continue;
            placeProp(level, at, rng);
            placed++;
        }
        return placed;
    }

    private static BlockPos pickFreePosition(Polygon region,
                                              List<Polygon> plotPolygons,
                                              Polygon.AABB bb,
                                              ServerLevel level,
                                              Random rng) {
        int width = bb.maxX() - bb.minX();
        int depth = bb.maxZ() - bb.minZ();
        if (width <= 1 || depth <= 1) return null;
        for (int attempt = 0; attempt < PROBE_ATTEMPTS_PER_PROP; attempt++) {
            int x = bb.minX() + rng.nextInt(width + 1);
            int z = bb.minZ() + rng.nextInt(depth + 1);
            if (!Polygon.contains(region, x, z)) continue;
            if (insideAnyPlot(x, z, plotPolygons)) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos at = new BlockPos(x, y, z);
            BlockState below = level.getBlockState(at.below());
            if (!below.is(Blocks.GRASS_BLOCK)
                    && !below.is(Blocks.DIRT)
                    && !below.is(Blocks.COARSE_DIRT)) continue;
            BlockState here = level.getBlockState(at);
            if (!here.isAir() && !here.canBeReplaced()) continue;
            return at;
        }
        return null;
    }

    private static boolean insideAnyPlot(int x, int z, List<Polygon> polygons) {
        if (polygons == null || polygons.isEmpty()) return false;
        for (Polygon p : polygons) {
            if (p == null) continue;
            if (Polygon.contains(p, x, z)) return true;
        }
        return false;
    }

    // =========================================================================
    // Prop palette
    // =========================================================================

    private static void placeProp(ServerLevel level, BlockPos at, Random rng) {
        int r = rng.nextInt(100);
        if (r < 30)        propHayBale(level, at, rng);
        else if (r < 55)   propWoodpile(level, at, rng);
        else if (r < 70)   propCompostHeap(level, at);
        else if (r < 82)   propHitchingPost(level, at);
        else if (r < 92)   propStackedCrates(level, at, rng);
        else               propSeedBagChest(level, at);
    }

    private static void propHayBale(ServerLevel level, BlockPos at, Random rng) {
        int n = 1 + rng.nextInt(3);
        for (int i = 0; i < n; i++) {
            place(level, at.above(i), Blocks.HAY_BLOCK.defaultBlockState());
        }
    }

    private static void propWoodpile(ServerLevel level, BlockPos at, Random rng) {
        int height = 1 + rng.nextInt(2);
        BlockState log = (rng.nextBoolean()
                ? Blocks.OAK_LOG : Blocks.BIRCH_LOG).defaultBlockState();
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 2; dz++) {
                for (int dy = 0; dy < height; dy++) {
                    place(level, at.offset(dx, dy, dz), log);
                }
            }
        }
    }

    private static void propCompostHeap(ServerLevel level, BlockPos at) {
        BlockState dirt = Blocks.COARSE_DIRT.defaultBlockState();
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                place(level, at.offset(dx, 0, dz), dirt);
            }
        }
        place(level, at.offset(0, 1, 0),
                Blocks.BROWN_MUSHROOM.defaultBlockState());
    }

    private static void propHitchingPost(ServerLevel level, BlockPos at) {
        place(level, at, Blocks.OAK_FENCE.defaultBlockState());
        place(level, at.above(),
                Blocks.STRIPPED_OAK_LOG.defaultBlockState());
    }

    private static void propStackedCrates(ServerLevel level, BlockPos at, Random rng) {
        int n = 2 + rng.nextInt(2);
        BlockState chest = Blocks.BARREL.defaultBlockState();
        for (int i = 0; i < n; i++) {
            place(level, at.above(i), chest);
        }
    }

    private static void propSeedBagChest(ServerLevel level, BlockPos at) {
        place(level, at, Blocks.CHEST.defaultBlockState());
    }

    private static void place(ServerLevel level, BlockPos pos, BlockState state) {
        BlockState cur = level.getBlockState(pos);
        if (cur.isAir() || cur.canBeReplaced()) {
            level.setBlock(pos, state, 3);
        }
    }
}
