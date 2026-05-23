package tterrag1112.life_in_the_village.Village.Farms.Complex.Render;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Farms.Complex.PathSegment;

import java.util.List;

/**
 * Detour A Prompt B Stage C — paints {@link PathSegment} corridors
 * as path-material strips.
 *
 * <p>For each segment: walk the centerline cell-by-cell. At each
 * step paint a {@code width}-wide perpendicular strip of the
 * configured path material (clipped to a half-width on each side).
 * Skip cells that aren't over grass / dirt / similar replaceable
 * ground.
 *
 * <p>Path material comes from the culture's
 * {@code planningBias.roadMaterial()} — same lookup the V2
 * {@code RoadPainter} uses, so farm paths visually match village
 * roads at the village/farm boundary.
 *
 * <p>Head clearance: 3 blocks of air above the path (matches
 * {@code RoadPainter.ROAD_HEAD_CLEARANCE}). Tall plants, saplings,
 * leftover branches all get cleared so the path is walkable.
 */
public final class PathRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(PathRenderer.class);

    /** Default air clearance — matches V2 RoadPainter so farm
     *  paths and village roads have identical above-path
     *  semantics. Callers that build their own PathPalette
     *  control this; the single-arg render overload uses it. */
    public static final int DEFAULT_HEAD_CLEARANCE = 3;

    private PathRenderer() {}

    /** Render every segment using {@code material} as the path
     *  surface block. Convenience wrapper that builds a single-
     *  entry palette internally — preserves the pre-palette
     *  call shape for any caller that doesn't yet have a
     *  PathPalette in hand. Idempotent — re-running re-stamps
     *  paths but doesn't accumulate or shift them. */
    public static void render(List<PathSegment> segments,
                               BlockState material,
                               ServerLevel level) {
        BlockState mat = material == null
                ? Blocks.DIRT_PATH.defaultBlockState() : material;
        render(segments,
                PathPalette.single(mat, DEFAULT_HEAD_CLEARANCE),
                level);
    }

    /** Render every segment via a {@link PathPalette}. The per-
     *  cell {@link PathPalette#selectBlock} call samples the
     *  weighted block mix; with a single-entry palette every
     *  cell receives the same block (identical to the pre-
     *  palette renderer). */
    public static void render(List<PathSegment> segments,
                               PathPalette palette,
                               ServerLevel level) {
        if (segments == null || segments.isEmpty()) return;
        if (palette == null) {
            palette = PathPalette.single(
                    Blocks.DIRT_PATH.defaultBlockState(),
                    DEFAULT_HEAD_CLEARANCE);
        }
        // Per-segment RNG seeded from segment endpoints — same
        // segment always produces the same per-cell block
        // selection (deterministic even with multi-entry
        // palettes).
        for (PathSegment seg : segments) {
            long segSeed = ((long) seg.start().getX() << 32)
                    ^ seg.start().getZ()
                    ^ ((long) seg.end().getX() << 16)
                    ^ seg.end().getZ();
            renderSegment(seg, palette, level, new java.util.Random(segSeed));
        }
    }

    /** Resolve a road-material id ({@code "minecraft:dirt_path"} or
     *  similar) to a BlockState. Matches
     *  {@code RoadPainter.resolveMaterial} so both renderers fall
     *  back identically on bad input. Falls back to dirt-path on
     *  null / empty / unknown / malformed. Logged once per failure
     *  for diagnostic clarity. */
    public static BlockState resolveRoadMaterial(String id) {
        if (id == null || id.isEmpty()) return Blocks.DIRT_PATH.defaultBlockState();
        try {
            Identifier rid = Identifier.parse(id);
            return BuiltInRegistries.BLOCK.getOptional(rid)
                    .map(Block::defaultBlockState)
                    .orElseGet(() -> {
                        LOGGER.warn("PathRenderer: unknown road material '{}', "
                                + "falling back to dirt path", id);
                        return Blocks.DIRT_PATH.defaultBlockState();
                    });
        } catch (Exception e) {
            LOGGER.warn("PathRenderer: malformed road material id '{}': {}",
                    id, e.getMessage());
            return Blocks.DIRT_PATH.defaultBlockState();
        }
    }

    private static void renderSegment(PathSegment seg, PathPalette palette,
                                       ServerLevel level,
                                       java.util.Random rng) {
        int x0 = seg.start().getX(), z0 = seg.start().getZ();
        int x1 = seg.end().getX(),   z1 = seg.end().getZ();
        int width = Math.max(1, seg.width());
        int half = width / 2;

        int dx = x1 - x0, dz = z1 - z0;
        int len = Math.max(1, (int) Math.round(Math.sqrt(dx * dx + dz * dz)));
        double pxu =  dz / (double) len;
        double pzu = -dx / (double) len;

        int ax = Math.abs(dx), az = Math.abs(dz);
        int sx = dx >= 0 ? 1 : -1;
        int sz = dz >= 0 ? 1 : -1;
        int err = ax - az;
        int cx = x0, cz = z0;
        int safety = 0;
        while (safety++ < 8192) {
            paintStrip(level, cx, cz, half, pxu, pzu, palette, rng);
            if (cx == x1 && cz == z1) break;
            int e2 = 2 * err;
            if (e2 > -az) { err -= az; cx += sx; }
            if (e2 <  ax) { err += ax; cz += sz; }
        }
    }

    /** Paint {@code 2*half + 1} cells perpendicular to the path
     *  direction at world XZ ({@code cx}, {@code cz}). */
    private static void paintStrip(ServerLevel level, int cx, int cz,
                                    int half, double pxu, double pzu,
                                    PathPalette palette,
                                    java.util.Random rng) {
        for (int off = -half; off <= half; off++) {
            int wx = cx + (int) Math.round(pxu * off);
            int wz = cz + (int) Math.round(pzu * off);
            paintColumn(level, wx, wz, palette, rng);
        }
    }

    /** Convert the top surface block at (x, z) to a palette-
     *  sampled path block when safe; clear the palette's
     *  headClearance blocks above. */
    private static void paintColumn(ServerLevel level, int wx, int wz,
                                     PathPalette palette,
                                     java.util.Random rng) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz) - 1;
        if (y <= 0) return;
        BlockPos surf = new BlockPos(wx, y, wz);
        BlockState s = level.getBlockState(surf);
        if (!isPathable(s)) return;
        BlockState pathBlock = palette.selectBlock(rng);
        level.setBlock(surf, pathBlock, 3);
        for (int dy = 1; dy <= palette.headClearance(); dy++) {
            BlockPos above = new BlockPos(wx, y + dy, wz);
            BlockState a = level.getBlockState(above);
            if (a.isAir() || a.is(Blocks.WATER)) continue;
            if (a.canBeReplaced() || isClearableAbovePath(a)) {
                level.setBlock(above, Blocks.AIR.defaultBlockState(), 3);
            }
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

    /** Above-path blocks safe to clear even if not canBeReplaced
     *  — leaves, saplings, grass, etc. Solid structure blocks
     *  (fences from adjacent borders, walls of adjoining
     *  buildings) stay. */
    private static boolean isClearableAbovePath(BlockState s) {
        return s.is(Blocks.OAK_LEAVES)
                || s.is(Blocks.BIRCH_LEAVES)
                || s.is(Blocks.SPRUCE_LEAVES)
                || s.is(Blocks.JUNGLE_LEAVES)
                || s.is(Blocks.DARK_OAK_LEAVES)
                || s.is(Blocks.ACACIA_LEAVES)
                || s.is(Blocks.OAK_SAPLING)
                || s.is(Blocks.SHORT_GRASS)
                || s.is(Blocks.TALL_GRASS)
                || s.is(Blocks.FERN)
                || s.is(Blocks.LARGE_FERN);
    }
}
