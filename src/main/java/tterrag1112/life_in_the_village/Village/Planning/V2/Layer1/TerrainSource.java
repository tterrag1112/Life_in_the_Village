package tterrag1112.life_in_the_village.Village.Planning.V2.Layer1;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Track E1 — terrain seam for the headless harness.
 *
 * <p>The planner's read-side coupling to a live Minecraft world. All
 * world reads the planner performs (Layers 1, 2, 5-planner) funnel
 * through this interface. The live adapter
 * ({@link LiveTerrainSource}) is a 1:1 wrapper around {@code Level}
 * with no behavior change; a synthetic adapter generates deterministic
 * heightmaps for the harness.
 *
 * <p>Classification (WATER / FOREST / STONE_EXPOSED / STRUCTURE / OPEN
 * / SHORE) stays inside {@link V2FeatureMap}. The source returns raw
 * {@link BlockState} so the classifier runs unchanged on both the
 * live path and the harness path; the harness must exercise the real
 * classification code, not its own substitute.
 *
 * <p>Coordinate convention is world space.
 */
public interface TerrainSource {

    /**
     * The top-solid block Y at world (x, z). Equivalent to
     * {@code level.getHeight(MOTION_BLOCKING_NO_LEAVES, x, z) - 1};
     * the {@code -1} is performed inside the live adapter so callers
     * see "the Y of the highest solid block" directly.
     */
    int height(int x, int z);

    /** Block state at world (x, y, z). */
    BlockState blockAt(int x, int y, int z);

    /** World ceiling Y (inclusive). Used by tree-column scan as a clamp. */
    int maxY();

    /**
     * Biome holder at the given position. May be {@code null} when the
     * source has no biome layer (synthetic harness). Callers must
     * tolerate null — {@link tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.AnchorDetector}
     * already wraps biome lookups in a try/catch.
     */
    Holder<Biome> biomeAt(BlockPos pos);
}
