package tterrag1112.life_in_the_village.Village.Farms.Complex.Render;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Farms.Complex.PathSegment;

import java.util.List;

/**
 * Detour A Prompt B Stage C — paints {@link PathSegment} corridors
 * as dirt-path strips.
 *
 * <p>For each segment: walk the centerline cell-by-cell. At each
 * step paint a {@code width}-wide perpendicular strip of dirt-path
 * (clipped to a half-width on each side). Skip cells that aren't
 * over grass / dirt / similar replaceable ground.
 *
 * <p>The spine stub (the segment that starts outside the region)
 * paints too — appears as a short approach lane "from the road".
 *
 * <p>Pure block placement: no state, no per-segment RNG (the
 * texture variation is implicit in the underlying terrain). All
 * segments rendered identically; the only style distinction is
 * spine vs branch, which Stage G's orchestrator can hook into
 * later if path styling diverges.
 */
public final class PathRenderer {

    private static final BlockState DIRT_PATH = Blocks.DIRT_PATH.defaultBlockState();

    private PathRenderer() {}

    /** Render every segment. Idempotent — re-running re-stamps
     *  paths but doesn't accumulate or shift them. */
    public static void render(List<PathSegment> segments, ServerLevel level) {
        if (segments == null || segments.isEmpty()) return;
        for (PathSegment seg : segments) {
            renderSegment(seg, level);
        }
    }

    private static void renderSegment(PathSegment seg, ServerLevel level) {
        int x0 = seg.start().getX(), z0 = seg.start().getZ();
        int x1 = seg.end().getX(),   z1 = seg.end().getZ();
        int width = Math.max(1, seg.width());
        int half = width / 2;

        // Perpendicular direction for the strip. Compute once from
        // segment vector; for axis-aligned segments this lands on
        // a pure (±1, 0) or (0, ±1) perp.
        int dx = x1 - x0, dz = z1 - z0;
        int len = Math.max(1, (int) Math.round(Math.sqrt(dx * dx + dz * dz)));
        // Perp = rotate 90° CW: (dz, -dx) normalised.
        double pxu =  dz / (double) len;
        double pzu = -dx / (double) len;

        // Bresenham centerline walk.
        int ax = Math.abs(dx), az = Math.abs(dz);
        int sx = dx >= 0 ? 1 : -1;
        int sz = dz >= 0 ? 1 : -1;
        int err = ax - az;
        int cx = x0, cz = z0;
        int safety = 0;
        while (safety++ < 8192) {
            paintStrip(level, cx, cz, half, pxu, pzu);
            if (cx == x1 && cz == z1) break;
            int e2 = 2 * err;
            if (e2 > -az) { err -= az; cx += sx; }
            if (e2 <  ax) { err += ax; cz += sz; }
        }
    }

    /** Paint {@code 2*half + 1} cells perpendicular to the path
     *  direction at world XZ ({@code cx}, {@code cz}). */
    private static void paintStrip(ServerLevel level, int cx, int cz,
                                    int half, double pxu, double pzu) {
        for (int off = -half; off <= half; off++) {
            int wx = cx + (int) Math.round(pxu * off);
            int wz = cz + (int) Math.round(pzu * off);
            paintColumn(level, wx, wz);
        }
    }

    /** Convert the top surface block at (x, z) to dirt-path when
     *  safe to do so. Skip water, lava, leaves, anything solid
     *  that isn't grass/dirt-like. */
    private static void paintColumn(ServerLevel level, int wx, int wz) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz) - 1;
        if (y <= 0) return;
        BlockPos surf = new BlockPos(wx, y, wz);
        BlockState s = level.getBlockState(surf);
        if (!isPathable(s)) return;
        level.setBlock(surf, DIRT_PATH, 3);
        // Clear the block directly above so the path isn't choked
        // by tall grass / flowers / saplings.
        BlockPos above = surf.above();
        BlockState a = level.getBlockState(above);
        if (a.canBeReplaced() && !a.is(Blocks.WATER)) {
            level.setBlock(above, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static boolean isPathable(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK)
                || s.is(Blocks.DIRT)
                || s.is(Blocks.COARSE_DIRT)
                || s.is(Blocks.PODZOL)
                || s.is(Blocks.ROOTED_DIRT)
                || s.is(Blocks.MYCELIUM)
                || s.is(Blocks.MUD)
                || s.is(Blocks.DIRT_PATH);   // idempotent re-stamp
    }
}
