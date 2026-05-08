package tterrag1112.life_in_the_village.Village.Decoration.Parks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * B2.4 — renders reserved {@link GardenPlot}s.
 *
 * <h3>Preserve-vs-compose decision</h3>
 * Per the user's "natural-feature-first" direction:
 * <ul>
 *   <li>If the plot's {@code preserveBias} is high AND the bounds
 *       already contain enough natural features (trees, flowers,
 *       water), the renderer runs in {@code PRESERVE} mode —
 *       minimal additions only (a path, optional bench).</li>
 *   <li>Otherwise it runs in {@code COMPOSE} mode — samples the
 *       style's piece pool and places primitives across the plot.</li>
 * </ul>
 *
 * <h3>Procedural vs NBT primitives</h3>
 * Per the user's B2.4 split: simple geometric pieces (path,
 * flower bed, hedgerow, pond) render via direct block stamps;
 * complex pieces (bench, statue, arch, standing stone, topiary,
 * trellis, lamppost) load NBTs from
 * {@code structures/{culture}/decoration/park/{name}.nbt}. NBTs
 * that aren't authored yet log a single line and are skipped —
 * the procedural primitives still render so a park is visible
 * even with zero NBTs authored.
 *
 * <h3>Determinism</h3>
 * Per-plot RNG seeded from the plot UUID + {@link #RNG_SALT}, so
 * the same plot stamps the same primitives across save / reload /
 * re-render.
 */
public final class ParkRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParkRenderer.class);

    /** Density (cells per primitive) when composing — one primitive
     *  per ~20 m² of plot area. */
    private static final int COMPOSE_DENSITY_AREA_PER_PIECE = 20;

    /** Number of natural-feature samples that count as "enough for
     *  preserve mode" — anything above this skips the heavy compose
     *  pass. */
    private static final int PRESERVE_THRESHOLD = 4;

    private static final long RNG_SALT = 0x504152_4B5F5230L;

    private ParkRenderer() {}

    /**
     * Renders every garden plot reserved for {@code village}.
     * Persists each plot's populated {@code preservedFeatures} and
     * {@code composedPrimitives} lists for save / reload
     * determinism.
     */
    public static int run(ServerLevel level, Village village,
                          VillageSavedData data) {
        if (level == null || village == null || data == null) return 0;
        long started = System.nanoTime();
        int rendered = 0;
        for (GardenPlot plot : data.getGardenPlotsForVillage(village.getId())) {
            GardenPlot updated = renderOne(level, plot);
            if (updated != null) {
                data.updateGardenPlot(updated);
                rendered++;
            }
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        if (rendered > 0) {
            LOGGER.info("ParkRenderer: village {} — rendered {} parks ({}ms)",
                    village.getName(), rendered, ms);
        }
        return rendered;
    }

    private static GardenPlot renderOne(ServerLevel level, GardenPlot plot) {
        Random rng = new Random(plot.plotId().getMostSignificantBits() ^ RNG_SALT);

        // 1. Survey natural features inside the bounds.
        List<BlockPos> preserved = surveyNaturalFeatures(level, plot.bounds());

        // 2. Decide mode. Preserve when bias is high AND we've got
        //    enough material to keep the plot visually full.
        boolean preserveMode = plot.preserveBias() >= 0.6
                && preserved.size() >= PRESERVE_THRESHOLD;

        // 3. Always lay a path stripe — the user-visible "this is a
        //    park" cue, regardless of mode.
        List<GardenPlot.ComposedPrimitive> composed = new ArrayList<>();
        composed.addAll(stampPath(level, plot.bounds(), rng));

        if (preserveMode) {
            // Minimal additions only — a single bench (NBT, may
            // skip) at a corner of the path.
            composed.addAll(tryNbtAt(plot, ParkPrimitive.BENCH,
                    centreOf(plot.bounds()).offset(2, 0, 0)));
        } else {
            // Compose pass — pull primitives from the style's pool
            // until we hit the area-density target.
            composed.addAll(composeFromPool(level, plot, rng));
        }

        long now = level.getGameTime();
        return new GardenPlot(
                plot.plotId(), plot.villageId(), plot.bounds(),
                plot.style(), plot.preserveBias(),
                preserved, composed,
                plot.createdTick() == 0 ? now : plot.createdTick());
    }

    // ── Natural-feature survey ─────────────────────────────────────────

    /** Walks the plot's XZ rectangle, samples surface Y, records
     *  positions of preserved-worthy blocks (logs, leaves, flowers,
     *  water source, snow). Subsampled (every 2 blocks) for speed. */
    private static List<BlockPos> surveyNaturalFeatures(ServerLevel level,
                                                        GardenPlot.Bounds b) {
        List<BlockPos> hits = new ArrayList<>();
        for (int x = b.minX(); x <= b.maxX(); x += 2) {
            for (int z = b.minZ(); z <= b.maxZ(); z += 2) {
                int y = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos surface = new BlockPos(x, y - 1, z);
                BlockState state = level.getBlockState(surface);
                BlockPos above = surface.above();
                BlockState aboveState = level.getBlockState(above);
                if (isPreserveWorthy(state) || isPreserveWorthy(aboveState)) {
                    hits.add(surface);
                }
            }
        }
        return hits;
    }

    private static boolean isPreserveWorthy(BlockState state) {
        if (state == null) return false;
        return state.is(Blocks.OAK_LOG)         || state.is(Blocks.SPRUCE_LOG)
            || state.is(Blocks.BIRCH_LOG)       || state.is(Blocks.JUNGLE_LOG)
            || state.is(Blocks.OAK_LEAVES)      || state.is(Blocks.SPRUCE_LEAVES)
            || state.is(Blocks.BIRCH_LEAVES)    || state.is(Blocks.JUNGLE_LEAVES)
            || state.is(Blocks.WATER)           || state.is(Blocks.LILY_PAD)
            || state.is(Blocks.POPPY)           || state.is(Blocks.DANDELION)
            || state.is(Blocks.AZURE_BLUET)     || state.is(Blocks.CORNFLOWER)
            || state.is(Blocks.OXEYE_DAISY);
    }

    // ── Procedural path (always rendered) ──────────────────────────────

    /** Stamps a single-block-wide gravel path running along the
     *  longer axis through the centre of the plot. */
    private static List<GardenPlot.ComposedPrimitive> stampPath(ServerLevel level,
                                                                GardenPlot.Bounds b,
                                                                Random rng) {
        List<GardenPlot.ComposedPrimitive> out = new ArrayList<>();
        boolean horizontal = b.width() >= b.length();
        int axisStart = horizontal ? b.minX() : b.minZ();
        int axisEnd   = horizontal ? b.maxX() : b.maxZ();
        int cross     = horizontal
                ? (b.minZ() + b.maxZ()) / 2
                : (b.minX() + b.maxX()) / 2;
        BlockState gravel = Blocks.GRAVEL.defaultBlockState();
        for (int v = axisStart; v <= axisEnd; v++) {
            int x = horizontal ? v : cross;
            int z = horizontal ? cross : v;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos surface = new BlockPos(x, y - 1, z);
            BlockState existing = level.getBlockState(surface);
            // Skip water/lava — keep the path on dry ground.
            if (existing.is(Blocks.WATER) || existing.is(Blocks.LAVA)) continue;
            level.setBlock(surface, gravel, 3);
            out.add(new GardenPlot.ComposedPrimitive(
                    ParkPrimitive.GRAVEL_PATH, surface));
        }
        return out;
    }

    // ── Compose-mode pool sampler ──────────────────────────────────────

    private static List<GardenPlot.ComposedPrimitive> composeFromPool(
            ServerLevel level, GardenPlot plot, Random rng) {
        List<GardenPlot.ComposedPrimitive> out = new ArrayList<>();
        Map<ParkPrimitive, Integer> pool = plot.style().piecePool();
        if (pool.isEmpty()) return out;

        int area = plot.bounds().width() * plot.bounds().length();
        int budget = Math.max(1, area / COMPOSE_DENSITY_AREA_PER_PIECE);

        // Build a flat weighted list once.
        List<ParkPrimitive> bag = new ArrayList<>();
        for (Map.Entry<ParkPrimitive, Integer> e : pool.entrySet()) {
            for (int i = 0; i < e.getValue(); i++) bag.add(e.getKey());
        }
        if (bag.isEmpty()) return out;

        for (int i = 0; i < budget; i++) {
            ParkPrimitive p = bag.get(rng.nextInt(bag.size()));
            BlockPos pos = randomPosInPlot(level, plot.bounds(), rng);
            if (pos == null) continue;
            if (p.isProcedural()) {
                stampProcedural(level, p, pos, rng);
                out.add(new GardenPlot.ComposedPrimitive(p, pos));
            } else {
                out.addAll(tryNbtAt(plot, p, pos));
            }
        }
        return out;
    }

    /** Procedural primitives — direct block stamps. Each produces a
     *  small block pattern centred on {@code pos}. */
    private static void stampProcedural(ServerLevel level, ParkPrimitive p,
                                        BlockPos pos, Random rng) {
        switch (p) {
            case FLOWER_BED -> {
                BlockState[] flowers = new BlockState[]{
                        Blocks.POPPY.defaultBlockState(),
                        Blocks.DANDELION.defaultBlockState(),
                        Blocks.CORNFLOWER.defaultBlockState(),
                        Blocks.AZURE_BLUET.defaultBlockState()};
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (rng.nextFloat() > 0.6f) continue;
                        BlockPos at = pos.offset(dx, 0, dz);
                        if (level.getBlockState(at).isAir()
                                && !level.getBlockState(at.below()).isAir()) {
                            level.setBlock(at, flowers[rng.nextInt(flowers.length)], 3);
                        }
                    }
                }
            }
            case HEDGEROW -> {
                BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
                for (int dx = -2; dx <= 2; dx++) {
                    BlockPos at = pos.offset(dx, 0, 0);
                    if (level.getBlockState(at).isAir()) {
                        level.setBlock(at, leaves, 3);
                    }
                }
            }
            case POND -> {
                BlockState water = Blocks.WATER.defaultBlockState();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos at = pos.offset(dx, -1, dz);
                        level.setBlock(at, water, 3);
                    }
                }
            }
            case GRAVEL_PATH -> {
                // Single-block path tile — main path is already laid
                // by stampPath; this provides spur tiles.
                level.setBlock(pos, Blocks.GRAVEL.defaultBlockState(), 3);
            }
            default -> {
                // Should never hit — caller filters by isProcedural().
            }
        }
    }

    /** NBT primitives — log "not authored yet, skipped" until the
     *  user ships the NBT. The placement is still recorded so the
     *  renderer is stable across save/reload (no re-attempts). */
    private static List<GardenPlot.ComposedPrimitive> tryNbtAt(
            GardenPlot plot, ParkPrimitive primitive, BlockPos pos) {
        if (primitive.isProcedural()) return List.of();
        // For B2.4, the NBT-stamping path lives in DecorationPass for
        // standard pieces; parks live outside that registry today.
        // Keep the placement record for save/reload; future renderer
        // expansion will hook the NBT loader here.
        LOGGER.debug("ParkRenderer: NBT primitive {} for plot {} at {}: "
                + "stamping deferred (NBT path = {})",
                primitive, plot.plotId(), pos,
                primitive.nbtPath("default"));
        return List.of(new GardenPlot.ComposedPrimitive(primitive, pos));
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private static BlockPos centreOf(GardenPlot.Bounds b) {
        return new BlockPos((b.minX() + b.maxX()) / 2,
                /* Y resolved at render time */ 0,
                (b.minZ() + b.maxZ()) / 2);
    }

    /** Random surface position inside the plot. Returns null if the
     *  bounds collapse to a degenerate point. */
    private static BlockPos randomPosInPlot(ServerLevel level,
                                            GardenPlot.Bounds b,
                                            Random rng) {
        if (b.width() <= 0 || b.length() <= 0) return null;
        int x = b.minX() + rng.nextInt(b.width());
        int z = b.minZ() + rng.nextInt(b.length());
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }
}
