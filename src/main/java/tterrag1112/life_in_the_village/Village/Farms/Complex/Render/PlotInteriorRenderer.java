package tterrag1112.life_in_the_village.Village.Farms.Complex.Render;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot.CropType;

import java.util.Random;

/**
 * Detour A Prompt B Stage D — paints the inside of one
 * {@link FarmPlot}'s polygon with terrain appropriate to its
 * {@link CropType}.
 *
 * <p>Per-cell algorithm:
 * <ol>
 *   <li>Walk the plot polygon's AABB at 1-block step.</li>
 *   <li>Skip cells outside the polygon (XZ point-in-poly).</li>
 *   <li>Resolve top-block Y via MOTION_BLOCKING_NO_LEAVES.</li>
 *   <li>Skip cells whose top is a path / fence / non-replaceable
 *       block (path-wins-conflict rule).</li>
 *   <li>Dispatch on {@link CropType} to a per-crop render.</li>
 * </ol>
 *
 * <p>Cultivated crops (WHEAT / CARROTS / POTATOES / BEETROOT /
 * MIXED / GRAIN / VEGETABLE): grass → farmland, place crop block
 * with weighted-random growth stage (70% stage 4-6, 20% stage 7,
 * 10% stage 0-2).
 *
 * <p>{@link CropType#PASTURE}: leave grass; place 1-3 hay bales
 * near centroid; scatter water-trough placeholders and tall grass.
 *
 * <p>{@link CropType#ORCHARD}: leave grass; place code-generated
 * mini-trees on a 4-cell grid; tall grass between.
 *
 * <p>Defensive throughout — null polygon, empty polygon, missing
 * crop, missing AGE property: silent skip rather than throw.
 */
public final class PlotInteriorRenderer {

    /** 4-cell step for orchard trees so they don't overlap. */
    private static final int ORCHARD_GRID = 4;

    private PlotInteriorRenderer() {}

    public static void render(FarmPlot plot, ServerLevel level, Random rng) {
        if (plot == null) return;
        Polygon poly = plot.getPolygon();
        if (poly == null || poly.vertices().size() < 3) return;
        CropType crop = plot.getCropType();
        if (crop == null) return;

        Polygon.AABB bb = Polygon.boundingBox(poly);

        // E.bug.4 — APIARY plots use a dedicated stub: grass
        // interior + placeholder beehive at the centroid. Full
        // visual design is a Track E follow-up. Dispatched by
        // subtype before the per-cell-by-crop loop because the
        // CropType for an apiary is MIXED (placeholder) — we
        // can't distinguish via crop alone.
        // TODO Track E: apiary visual design (flower meadow,
        // multi-hive arrangement, beekeeper paths).
        if (plot.getSubtype() == FarmPlot.PlotSubtype.APIARY) {
            renderApiary(plot, poly, bb, level, rng);
            return;
        }

        // Pre-compute row axis for MIXED/GRAIN stripe direction.
        // Stripes align with the longer side; the index modulo 3
        // along that axis selects the crop in MIXED.
        boolean stripesAlongX = (bb.maxX() - bb.minX())
                                 >= (bb.maxZ() - bb.minZ());

        // Roll a per-plot base growth stage ONCE. Per-cell jitter
        // (±1) then varies the actual planted stage so the plot
        // reads as a single planting cohort rather than per-cell
        // noise. PASTURE / ORCHARD ignore baseStage (no crop block).
        int baseStage = rollPlotBaseStage(rng);

        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                if (!Polygon.contains(poly, x, z)) continue;
                renderCell(level, x, z, crop, rng, stripesAlongX, baseStage);
            }
        }

        // Per-crop post-pass content.
        if (crop == CropType.PASTURE) {
            renderPasturePropPass(plot, poly, bb, level, rng);
        }
        // Orchard trees are placed inline during the cell walk
        // (sparse-grid filter); no post-pass.
    }

    /** E.bug.4 stub — APIARY plot interior. Leaves grass; places a
     *  single bee_nest at the plot centroid plus a few short-grass
     *  decorations. Track E owns the real visual: clusters of
     *  beehives on stripped logs, flower meadow, beekeeper path. */
    private static void renderApiary(FarmPlot plot, Polygon poly,
                                       Polygon.AABB bb,
                                       ServerLevel level, Random rng) {
        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                if (!Polygon.contains(poly, x, z)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (y < level.getMinY()) continue;
                BlockPos surface = new BlockPos(x, y, z);
                BlockState top = level.getBlockState(surface);
                if (top.is(Blocks.DIRT_PATH)) continue;
                if (!top.is(Blocks.GRASS_BLOCK) && !top.is(Blocks.DIRT)
                        && !top.is(Blocks.COARSE_DIRT)) continue;
                if (top.is(Blocks.DIRT) || top.is(Blocks.COARSE_DIRT)) {
                    level.setBlock(surface, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                }
                BlockPos above = surface.above();
                BlockState aboveState = level.getBlockState(above);
                if (!aboveState.isAir() && !aboveState.canBeReplaced()) continue;
                if (rng.nextInt(6) == 0) {
                    level.setBlock(above, Blocks.SHORT_GRASS.defaultBlockState(), 3);
                }
            }
        }
        // Placeholder hive at the centroid.
        BlockPos centroid = plot.getOrigin();
        if (centroid != null) {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    centroid.getX(), centroid.getZ());
            BlockPos hivePos = new BlockPos(centroid.getX(), y, centroid.getZ());
            BlockState here = level.getBlockState(hivePos);
            if (here.isAir() || here.canBeReplaced()) {
                level.setBlock(hivePos, Blocks.BEE_NEST.defaultBlockState(), 3);
            }
        }
    }

    /** Roll a plot's base growth stage from the maturity-class
     *  distribution: 25% ready (7), 50% mid (5), 15% early (3),
     *  10% freshly-planted (1). Stages are on the wheat/carrot/
     *  potato 0-7 scale; BEETROOT scales them down inside
     *  cropStateFor. */
    private static int rollPlotBaseStage(Random rng) {
        int r = rng.nextInt(100);
        if (r < 25) return 7;   // ready-to-harvest
        if (r < 75) return 5;   // mid-growth
        if (r < 90) return 3;   // early-growth
        return 1;               // freshly-planted
    }

    // =========================================================================
    // Per-cell dispatch
    // =========================================================================

    private static void renderCell(ServerLevel level, int x, int z,
                                    CropType crop, Random rng,
                                    boolean stripesAlongX,
                                    int baseStage) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        // World floor is level.getMinY() in 1.21 (negative on
        // superflat). Skip only when the column is literally
        // empty to the floor.
        if (y < level.getMinY()) return;
        BlockPos surface = new BlockPos(x, y, z);
        BlockState top = level.getBlockState(surface);

        // Path-wins-conflict: never over-write dirt-path.
        if (top.is(Blocks.DIRT_PATH)) return;
        if (!top.is(Blocks.GRASS_BLOCK) && !top.is(Blocks.DIRT)
                && !top.is(Blocks.COARSE_DIRT)) return;

        BlockPos above = surface.above();
        BlockState aboveState = level.getBlockState(above);
        // Don't disturb existing solid blocks (a border fence, a
        // shed wall, etc).
        if (!aboveState.isAir() && !aboveState.canBeReplaced()) return;

        switch (crop) {
            case PASTURE -> {
                // Leave grass; ensure it IS grass (convert dirt → grass for visual consistency).
                if (top.is(Blocks.DIRT) || top.is(Blocks.COARSE_DIRT)) {
                    level.setBlock(surface, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                }
                if (rng.nextInt(8) == 0) {
                    level.setBlock(above, Blocks.SHORT_GRASS.defaultBlockState(), 3);
                }
            }
            case ORCHARD -> {
                if ((x & (ORCHARD_GRID - 1)) == 0 && (z & (ORCHARD_GRID - 1)) == 0
                        && rng.nextInt(4) != 0) {
                    placeMiniTree(level, surface, rng);
                } else if (rng.nextInt(6) == 0) {
                    level.setBlock(above, Blocks.SHORT_GRASS.defaultBlockState(), 3);
                }
            }
            default -> renderCultivatedCell(level, surface, above, crop,
                    rng, stripesAlongX, x, z, baseStage);
        }
    }

    // =========================================================================
    // Cultivated (farmland + crop)
    // =========================================================================

    private static void renderCultivatedCell(ServerLevel level,
                                              BlockPos surface, BlockPos above,
                                              CropType crop, Random rng,
                                              boolean stripesAlongX,
                                              int x, int z,
                                              int baseStage) {
        // Convert top to farmland (treat all cultivated cells as
        // moisture=0; the crop visual works either way).
        BlockState farmland = Blocks.FARMLAND.defaultBlockState();
        level.setBlock(surface, farmland, 3);

        CropType effective = resolveStripeCrop(crop, stripesAlongX, x, z, rng);
        BlockState cropState = cropStateFor(effective, rng, baseStage);
        if (cropState != null) {
            level.setBlock(above, cropState, 3);
        }
    }

    /** Resolves the actual block-state crop type for one cell.
     *  Most crops map 1:1; MIXED stripes WHEAT / CARROTS / POTATOES;
     *  VEGETABLE picks random; GRAIN aliases to WHEAT. */
    private static CropType resolveStripeCrop(CropType crop, boolean stripesAlongX,
                                                int x, int z, Random rng) {
        return switch (crop) {
            case MIXED -> {
                int idx = (stripesAlongX ? x : z) % 3;
                if (idx < 0) idx += 3;
                yield switch (idx) {
                    case 0 -> CropType.WHEAT;
                    case 1 -> CropType.CARROTS;
                    default -> CropType.POTATOES;
                };
            }
            case VEGETABLE -> switch (rng.nextInt(3)) {
                case 0 -> CropType.CARROTS;
                case 1 -> CropType.POTATOES;
                default -> CropType.BEETROOT;
            };
            case GRAIN -> CropType.WHEAT;
            default -> crop;
        };
    }

    /** Build a crop blockstate at a weighted-random growth stage.
     *  Returns null for crops that don't plant a block (PASTURE /
     *  ORCHARD — already filtered out by the dispatch). */
    private static BlockState cropStateFor(CropType crop, Random rng, int baseStage) {
        net.minecraft.world.level.block.Block block = switch (crop) {
            case WHEAT, MIXED, GRAIN -> Blocks.WHEAT;
            case CARROTS  -> Blocks.CARROTS;
            case POTATOES -> Blocks.POTATOES;
            case BEETROOT -> Blocks.BEETROOTS;
            case VEGETABLE, ORCHARD, PASTURE -> null;
        };
        if (block == null) return null;
        IntegerProperty ageProp = block == Blocks.BEETROOTS
                ? BlockStateProperties.AGE_3
                : BlockStateProperties.AGE_7;
        int maxAge = block == Blocks.BEETROOTS ? 3 : 7;
        int age = perCellStageJitter(maxAge, baseStage, rng);
        BlockState s = block.defaultBlockState();
        if (s.hasProperty(ageProp)) {
            s = s.setValue(ageProp, age);
        }
        return s;
    }

    /** Per-cell growth = clamp(scaledBase + uniform(-1, +1), 0, maxAge).
     *  Scales the 0-7 baseStage proportionally to the crop's actual
     *  max-age (BEETROOT max=3 → baseStage 7 maps to 3, etc.) so a
     *  "ready-to-harvest" wheat plot and a "ready-to-harvest"
     *  beetroot plot both read as fully grown. */
    private static int perCellStageJitter(int maxAge, int baseStage, Random rng) {
        int scaledBase = (int) Math.round(baseStage * (maxAge / 7.0));
        int stage = scaledBase + rng.nextInt(3) - 1;   // -1, 0, +1
        return Math.max(0, Math.min(maxAge, stage));
    }

    // =========================================================================
    // Orchard mini-tree
    // =========================================================================

    /** Place a tiny code-generated tree on the surface. Trunk 3-4
     *  blocks of oak log; canopy 3×3×2 oak leaves at the top. */
    private static void placeMiniTree(ServerLevel level, BlockPos surface, Random rng) {
        int height = 3 + rng.nextInt(2);  // 3 or 4 blocks
        BlockState log = Blocks.OAK_LOG.defaultBlockState();
        BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState();

        // Trunk
        for (int dy = 1; dy <= height; dy++) {
            placeIfAir(level, surface.offset(0, dy, 0), log);
        }
        // Canopy — 3×3 around the top + one cube above
        int topY = height;
        for (int dy = topY - 1; dy <= topY + 1; dy++) {
            int radius = (dy == topY + 1) ? 0 : 1;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0 && dy <= topY) continue; // trunk
                    placeIfAir(level, surface.offset(dx, dy, dz), leaf);
                }
            }
        }
    }

    private static void placeIfAir(ServerLevel level, BlockPos pos, BlockState s) {
        BlockState cur = level.getBlockState(pos);
        if (cur.isAir() || cur.canBeReplaced()) {
            level.setBlock(pos, s, 3);
        }
    }

    // =========================================================================
    // Pasture post-pass — hay bale stack + trough
    // =========================================================================

    /** Drops 1-3 hay bales near the plot centroid + a 2-block
     *  trough using composters as the trough placeholder. */
    private static void renderPasturePropPass(FarmPlot plot, Polygon poly,
                                                Polygon.AABB bb,
                                                ServerLevel level, Random rng) {
        BlockPos centroid = plot.getOrigin();
        if (centroid == null) return;
        int hayCount = 1 + rng.nextInt(3);
        int placed = 0;
        int safety = 0;
        while (placed < hayCount && safety++ < 24) {
            int dx = rng.nextInt(5) - 2;
            int dz = rng.nextInt(5) - 2;
            int wx = centroid.getX() + dx;
            int wz = centroid.getZ() + dz;
            if (!Polygon.contains(poly, wx, wz)) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz);
            BlockPos hay = new BlockPos(wx, y, wz);
            BlockState below = level.getBlockState(hay.below());
            if (!below.is(Blocks.GRASS_BLOCK) && !below.is(Blocks.DIRT)) continue;
            placeIfAir(level, hay, Blocks.HAY_BLOCK.defaultBlockState());
            if (placed > 0 && rng.nextInt(3) == 0) {
                placeIfAir(level, hay.above(), Blocks.HAY_BLOCK.defaultBlockState());
            }
            placed++;
        }
        // One trough — two composters in a row near the centroid.
        int tx = centroid.getX() + 3;
        int tz = centroid.getZ();
        if (Polygon.contains(poly, tx, tz)) {
            int ty = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, tx, tz);
            placeIfAir(level, new BlockPos(tx, ty, tz),
                    Blocks.COMPOSTER.defaultBlockState());
            placeIfAir(level, new BlockPos(tx, ty, tz + 1),
                    Blocks.COMPOSTER.defaultBlockState());
        }
    }
}
