package tterrag1112.life_in_the_village.Village.Planning.V2.Layer1;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Track E1 — synthetic {@link TerrainSource} for the headless harness.
 *
 * <p>Generates a deterministic heightmap centred on the origin, with
 * column composition that V2FeatureMap's classifier sees as real
 * terrain: grass/stone on land, water in channels, air above ground.
 * No biome layer (always returns {@code null}); AnchorDetector
 * silently skips biome metadata when {@code biomeAt} returns null.
 *
 * <p>The 9 terrain shapes are pure functions of (x, z) plus the
 * configured {@link Shape}. No RNG inside the source — determinism is
 * a function of shape only. Seeds enter the planner via {@code
 * SiteContext.seed}, not through this source.
 *
 * <p>The world-Y origin is at {@link #BASE_Y} and the deepest carve
 * (CLIFF, BASIN inner) is no lower than {@link #FLOOR_Y}. All
 * generated heights fit comfortably inside Minecraft's normal Y
 * range; the harness never reads beyond what the classifier
 * actually queries.
 */
public final class SyntheticTerrainSource implements TerrainSource {

    /** Base sea-level Y for flat terrain. */
    public static final int BASE_Y = 64;
    /** Lowest Y any terrain produces. */
    public static final int FLOOR_Y = 50;
    /** Y for water surface (RIVER). One below BASE_Y so river beds are
     *  carved into ground that's flat at BASE_Y elsewhere. */
    public static final int WATER_Y = BASE_Y - 1;
    /** World ceiling reported via {@link #maxY()}. Way above any
     *  generated surface; matches the tree-column-scan clamp's
     *  intent. */
    public static final int CEILING_Y = 320;

    public enum Shape {
        /** Constant Y across the scan — the structural-bug isolator
         *  (this is the superflat equivalent that exposed the HOUSE
         *  bug). */
        FLAT,
        /** ~1 block per 8 horizontal. Fully adaptable; terrain should
         *  be a non-factor. */
        GENTLE_SLOPE,
        /** ~1 per 3. Stresses terrain adaptation limits. */
        STEEP_SLOPE,
        /** V cross-section, linear low corridor. Exercises
         *  valley_floor anchors / REIHENDORF. */
        VALLEY,
        /** Inverted V, linear high. Exercises ridge_line. */
        RIDGE,
        /** Flat plateau, sharp drop, flat lower bench. Exercises
         *  cliff_face / industrial_mining. */
        CLIFF,
        /** Flat ground with a water channel. Exercises water_edge /
         *  marschhufendorf. */
        RIVER,
        /** Flat enclosed by rising ground. Exercises natural
         *  enclosure / clearing. */
        BASIN,
        /** Composite — cliff west, ridge central, river east.
         *  Realistic stress test. */
        MIXED
    }

    private final Shape shape;
    private final BlockPos origin;

    public SyntheticTerrainSource(Shape shape, BlockPos origin) {
        this.shape = shape;
        this.origin = origin;
    }

    public Shape shape() { return shape; }
    public BlockPos origin() { return origin; }

    @Override
    public int height(int x, int z) {
        int dx = x - origin.getX();
        int dz = z - origin.getZ();
        return computeHeight(shape, dx, dz);
    }

    @Override
    public BlockState blockAt(int x, int y, int z) {
        int top = height(x, z);
        if (y > top) return Blocks.AIR.defaultBlockState();
        if (isWaterColumn(shape, x - origin.getX(), z - origin.getZ())) {
            // Water column: top block at WATER_Y is WATER, below is
            // stone (river bed).
            if (y == top && top == WATER_Y) return Blocks.WATER.defaultBlockState();
            return Blocks.STONE.defaultBlockState();
        }
        if (y == top) return Blocks.GRASS_BLOCK.defaultBlockState();
        return Blocks.STONE.defaultBlockState();
    }

    @Override
    public int maxY() { return CEILING_Y; }

    @Override
    public Holder<Biome> biomeAt(BlockPos pos) {
        // Harness has no biome registry; AnchorDetector tolerates null.
        return null;
    }

    // =========================================================================
    // Per-shape height functions
    // =========================================================================

    private static int computeHeight(Shape shape, int dx, int dz) {
        return switch (shape) {
            case FLAT          -> BASE_Y;
            case GENTLE_SLOPE  -> BASE_Y + dx / 8;
            case STEEP_SLOPE   -> BASE_Y + dx / 3;
            case VALLEY        -> valleyHeight(dx);
            case RIDGE         -> ridgeHeight(dx);
            case CLIFF         -> cliffHeight(dx);
            case RIVER         -> riverHeight(dz);
            case BASIN         -> basinHeight(dx, dz);
            case MIXED         -> mixedHeight(dx, dz);
        };
    }

    /** V cross-section along x: lowest at dx=0, rising 1 per 2
     *  horizontal out to a clamp at the lip. */
    private static int valleyHeight(int dx) {
        int depth = Math.min(20, Math.abs(dx) / 2);
        // Floor at BASE_Y - 10; rises back to BASE_Y over 20 cells.
        int floor = BASE_Y - 10;
        return floor + Math.min(10, depth);
    }

    /** Inverted V: peak at dx=0, dropping 1 per 2 horizontal. */
    private static int ridgeHeight(int dx) {
        int delta = Math.min(20, Math.abs(dx) / 2);
        int peak = BASE_Y + 10;
        return peak - Math.min(10, delta);
    }

    /** Two flat benches with a sharp 14-block drop at dx=0. */
    private static int cliffHeight(int dx) {
        return dx < 0 ? BASE_Y : FLOOR_Y;
    }

    /** RIVER: flat at BASE_Y everywhere except a 6-block-wide channel
     *  along dz=0 where the surface drops to WATER_Y (so MOTION_-
     *  BLOCKING_NO_LEAVES still returns WATER_Y because water is
     *  motion-blocking). */
    private static int riverHeight(int dz) {
        return Math.abs(dz) <= 3 ? WATER_Y : BASE_Y;
    }

    /** BASIN: flat at BASE_Y inside a 40-block radius from origin;
     *  rises 1 per 2 horizontal beyond, capped at +20. */
    private static int basinHeight(int dx, int dz) {
        int r = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
        if (r < 40) return BASE_Y;
        int over = (r - 40) / 2;
        return BASE_Y + Math.min(20, over);
    }

    /** MIXED: cliff in the west third, ridge in the centre, river in
     *  the east. Boundaries roughly +/-50 on x. */
    private static int mixedHeight(int dx, int dz) {
        if (dx < -50) return cliffHeight(dx + 50);     // cliff lip near dx=-50
        if (dx <  50) return ridgeHeight(dx);          // central ridge
        return riverHeight(dz);                        // eastern river
    }

    /** Whether the given world (x, z) lies in a water column. */
    private static boolean isWaterColumn(Shape shape, int dx, int dz) {
        return switch (shape) {
            case RIVER -> Math.abs(dz) <= 3;
            case MIXED -> dx >= 50 && Math.abs(dz) <= 3;
            default    -> false;
        };
    }
}
