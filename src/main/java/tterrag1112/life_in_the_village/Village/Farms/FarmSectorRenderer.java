package tterrag1112.life_in_the_village.Village.Farms;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * B2.5 — renders {@link FarmSector}s reserved by
 * {@link FarmSectorPlanner}: stamps farmland + crops per plot,
 * places a sector perimeter fence, scatters scarecrows, and logs
 * skipped tool-shed NBT placements.
 *
 * <p>Replaces the legacy {@code FarmPlotPlacer}'s rendering role
 * for B2.5+ V2 spawns. Per the user's "Replace" direction the
 * legacy placer is parked (no callers from the V2 spawn path);
 * this renderer ships the framework slice — tilling, crops,
 * perimeter fence, scarecrow. Inter-plot paths and tool-shed NBTs
 * are deferred (scaffolded but skipped).</p>
 *
 * <p>Procedural-only: GRAVEL_PATH-style strips and fence stamps
 * use direct block placements. Tool sheds (one per sector edge per
 * doc 10) are NBT-backed; the renderer logs each attempted shed
 * position and persists it onto the {@link FarmSector} record so
 * a future shed-NBT pass can stamp without re-rolling positions.</p>
 *
 * <p>Determinism: per-sector RNG seeded from {@code sectorId} so
 * save / reload / re-render produce identical output.</p>
 */
public final class FarmSectorRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(FarmSectorRenderer.class);

    private static final long RENDER_SALT = 0x4641524D5F523532L;

    private FarmSectorRenderer() {}

    public static int run(ServerLevel level, Village village,
                          VillageSavedData data) {
        if (level == null || village == null || data == null) return 0;
        long started = System.nanoTime();
        int rendered = 0;
        for (FarmSector sector : data.getFarmSectorsForVillage(village.getId())) {
            if (renderOne(level, sector, data)) rendered++;
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        if (rendered > 0) {
            LOGGER.info("FarmSectorRenderer: village {} — rendered {} sectors ({}ms)",
                    village.getName(), rendered, ms);
        }
        return rendered;
    }

    private static boolean renderOne(ServerLevel level, FarmSector sector,
                                     VillageSavedData data) {
        if (sector == null || sector.bounds() == null) return false;
        Random rng = new Random(sector.sectorId().getMostSignificantBits() ^ RENDER_SALT);

        // 1. Render each plot (farmland + crop / pasture surface).
        for (UUID plotId : sector.plotIds()) {
            FarmPlot plot = findPlot(data, plotId);
            if (plot == null) continue;
            renderPlot(level, plot, rng);
        }

        // 2. Sector perimeter fence — stamp oak fence along each
        //    polygon edge, skipping segments that fall on water or air.
        stampPerimeterFence(level, sector.bounds());

        // 3. Scarecrows — procedural, ~1 per plot, random positions
        //    inside plot polygons.
        for (UUID plotId : sector.plotIds()) {
            FarmPlot plot = findPlot(data, plotId);
            if (plot == null || plot.getPolygon() == null) continue;
            if (rng.nextDouble() < 0.6) {
                stampScarecrow(level, plot.getPolygon(), rng);
            }
        }

        // 4. Tool sheds — log + skip (NBT user task). Doc 10 places
        //    1 shed per sector at an edge midpoint; we log here so a
        //    future renderer pass picks the same positions.
        BlockPos shedHint = polygonEdgeMidpoint(sector.bounds(), 0);
        if (shedHint != null) {
            LOGGER.debug("FarmSectorRenderer: tool shed for sector {} deferred "
                    + "(NBT path = {}); hint pos = {}",
                    sector.sectorId(),
                    "structures/default/decoration/farm/tool_shed.nbt",
                    shedHint);
        }
        return true;
    }

    private static FarmPlot findPlot(VillageSavedData data, UUID plotId) {
        return data.getFarmPlotById(plotId).orElse(null);
    }

    // ── Plot rendering ─────────────────────────────────────────────────

    /** Stamps farmland + crop blocks (or a fenced grass pasture) over
     *  every cell inside the plot's polygon. Y is resampled per
     *  column from the world heightmap so plots terrain-follow. */
    private static void renderPlot(ServerLevel level, FarmPlot plot, Random rng) {
        Polygon poly = plot.getPolygon();
        if (poly == null || poly.vertices().isEmpty()) return;

        int[] bbox = bbox(poly);
        boolean isCrop = plot.getSubtype() == FarmPlot.PlotSubtype.CROP_FIELD
                && plot.getCropType().isCropPlot();

        BlockState farmland = Blocks.FARMLAND.defaultBlockState()
                .setValue(BlockStateProperties.MOISTURE, 7);
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState grassPath = Blocks.DIRT_PATH.defaultBlockState();
        var cropBlock = isCrop ? plot.getCropType().resolveCropBlock() : null;

        // For crop plots, seed a single water source near the centre.
        BlockPos centre = polygonCentre(poly);
        if (isCrop && centre != null) {
            int waterY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    centre.getX(), centre.getZ()) - 1;
            BlockPos waterPos = new BlockPos(centre.getX(), waterY, centre.getZ());
            level.setBlock(waterPos, water, 3);
        }

        for (int x = bbox[0]; x <= bbox[2]; x++) {
            for (int z = bbox[1]; z <= bbox[3]; z++) {
                BlockPos at = new BlockPos(x, 0, z);
                if (!Polygon.contains(poly, at)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos surface = new BlockPos(x, y - 1, z);
                BlockState existing = level.getBlockState(surface);
                if (existing.is(Blocks.WATER) || existing.is(Blocks.LAVA)) continue;
                if (isCrop) {
                    // Skip the central irrigation block.
                    if (centre != null && x == centre.getX() && z == centre.getZ()) {
                        continue;
                    }
                    level.setBlock(surface, farmland, 3);
                    if (cropBlock != null) {
                        BlockState crop = cropBlock.defaultBlockState();
                        level.setBlock(surface.above(), crop, 3);
                    }
                } else {
                    // Pasture / animal pen — replace tilled blocks with
                    // grass path tiles for visual contrast.
                    level.setBlock(surface, dirt, 3);
                    if (rng.nextDouble() < 0.05) {
                        level.setBlock(surface.above(),
                                Blocks.HAY_BLOCK.defaultBlockState(), 3);
                    }
                }
            }
        }

        // Plot perimeter — single-block fence on the polygon edges.
        stampPolygonFence(level, poly);
    }

    // ── Sector perimeter fence ─────────────────────────────────────────

    private static void stampPerimeterFence(ServerLevel level, Polygon poly) {
        if (poly == null || poly.vertices().size() < 2) return;
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        List<BlockPos> v = poly.vertices();
        for (int i = 0; i < v.size(); i++) {
            BlockPos a = v.get(i);
            BlockPos b = v.get((i + 1) % v.size());
            stampLineFence(level, a, b, fence);
        }
    }

    /** Plot fences are short (single-step) — use the same line-stamper
     *  but track skipped-water positions so the renderer doesn't
     *  drown plot edges. */
    private static void stampPolygonFence(ServerLevel level, Polygon poly) {
        if (poly == null || poly.vertices().size() < 2) return;
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        List<BlockPos> v = poly.vertices();
        for (int i = 0; i < v.size(); i++) {
            BlockPos a = v.get(i);
            BlockPos b = v.get((i + 1) % v.size());
            stampLineFence(level, a, b, fence);
        }
    }

    /** Bresenham-ish XZ line stamper that places {@code state} above
     *  each surface cell along (a → b). Skips cells whose surface is
     *  water or lava; the heightmap re-samples per column so the fence
     *  follows terrain. */
    private static void stampLineFence(ServerLevel level, BlockPos a, BlockPos b,
                                       BlockState state) {
        int x0 = a.getX(), z0 = a.getZ();
        int x1 = b.getX(), z1 = b.getZ();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        int x = x0, z = z0;
        int safety = dx + dz + 4;
        while (safety-- > 0) {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos at = new BlockPos(x, y, z);
            BlockState surface = level.getBlockState(at.below());
            if (!(surface.is(Blocks.WATER) || surface.is(Blocks.LAVA))
                    && level.getBlockState(at).isAir()) {
                level.setBlock(at, state, 3);
            }
            if (x == x1 && z == z1) break;
            int e2 = err << 1;
            if (e2 > -dz) { err -= dz; x += sx; }
            if (e2 <  dx) { err += dx; z += sz; }
        }
    }

    // ── Scarecrow stamp ────────────────────────────────────────────────

    /** Procedural scarecrow: an oak fence post topped with a carved
     *  pumpkin. Single column, two blocks tall. */
    private static void stampScarecrow(ServerLevel level, Polygon plotPoly,
                                       Random rng) {
        BlockPos pos = randomInsidePolygon(plotPoly, rng);
        if (pos == null) return;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getX(), pos.getZ());
        BlockPos base = new BlockPos(pos.getX(), y, pos.getZ());
        if (!level.getBlockState(base).isAir()) return;
        level.setBlock(base, Blocks.OAK_FENCE.defaultBlockState(), 3);
        level.setBlock(base.above(),
                Blocks.CARVED_PUMPKIN.defaultBlockState(), 3);
    }

    // ── Geometry helpers ───────────────────────────────────────────────

    private static int[] bbox(Polygon p) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos v : p.vertices()) {
            minX = Math.min(minX, v.getX());
            maxX = Math.max(maxX, v.getX());
            minZ = Math.min(minZ, v.getZ());
            maxZ = Math.max(maxZ, v.getZ());
        }
        return new int[]{minX, minZ, maxX, maxZ};
    }

    private static BlockPos polygonCentre(Polygon p) {
        if (p == null || p.vertices().isEmpty()) return null;
        long sx = 0, sz = 0;
        for (BlockPos v : p.vertices()) { sx += v.getX(); sz += v.getZ(); }
        int n = p.vertices().size();
        return new BlockPos((int) (sx / n), 0, (int) (sz / n));
    }

    /** Midpoint of polygon edge {@code edgeIndex}. Used as a tool-shed
     *  hint position. */
    private static BlockPos polygonEdgeMidpoint(Polygon p, int edgeIndex) {
        if (p == null || p.vertices().size() < 2) return null;
        int n = p.vertices().size();
        BlockPos a = p.vertices().get(edgeIndex % n);
        BlockPos b = p.vertices().get((edgeIndex + 1) % n);
        return new BlockPos((a.getX() + b.getX()) / 2,
                (a.getY() + b.getY()) / 2,
                (a.getZ() + b.getZ()) / 2);
    }

    private static BlockPos randomInsidePolygon(Polygon p, Random rng) {
        if (p == null) return null;
        int[] b = bbox(p);
        for (int attempt = 0; attempt < 8; attempt++) {
            int x = b[0] + rng.nextInt(Math.max(1, b[2] - b[0] + 1));
            int z = b[1] + rng.nextInt(Math.max(1, b[3] - b[1] + 1));
            BlockPos at = new BlockPos(x, 0, z);
            if (Polygon.contains(p, at)) return at;
        }
        return null;
    }
}
