package tterrag1112.life_in_the_village.Village.Planning.Features;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.AtlasSampler;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

/**
 * Audit boundary for noise-only feature sampling. All planning-time
 * access to terrain data flows through this class so determinism can be
 * reasoned about in one place.
 *
 * <h3>Determinism contract</h3>
 * Every method here MUST be load-order-independent — same {@code (worldSeed, x, z)}
 * always yields the same answer regardless of which chunks are loaded.
 *
 * <p>Implementation note: this wraps {@link AtlasSampler} (whose static
 * methods are documented as stateless w.r.t. world state) and
 * {@link WorldAtlas#ensureCell} (which is a deterministic cache over
 * {@link AtlasSampler#sampleCell}). {@link tterrag1112.life_in_the_village
 * .Kingdom.Placement.DeepTerrainInspector} uses the same primitives in
 * its bulk inspection form; this class exposes the per-point primitives
 * that the planning-time {@code FeatureMap.buildPlanning} requires but
 * the inspector does not surface directly.
 *
 * <p>Do NOT call {@code level.getHeight(...)} from within
 * {@code buildPlanning} — that depends on chunk-load state. Add new
 * methods here when new noise-only primitives are needed.
 */
public final class FeatureSamplers {

    private FeatureSamplers() {}

    /**
     * Predicted surface Y at {@code (x, z)} from the noise generator,
     * independent of which chunks are loaded.
     */
    public static int predictedSurfaceY(ServerLevel level, int x, int z) {
        return AtlasSampler.sampleHeight(level, x, z);
    }

    /**
     * True if {@code (x, z)} is predicted to be water at planning time,
     * derived from the noise-only atlas biome category. Treats
     * {@link BiomeCategory#OCEAN} and {@link BiomeCategory#RIVER} as water.
     */
    public static boolean isPredictedWater(ServerLevel level, int x, int z) {
        AtlasCell cell = WorldAtlas.get(level).ensureCell(level, x, z);
        BiomeCategory cat = cell.category();
        return cat == BiomeCategory.OCEAN || cat == BiomeCategory.RIVER;
    }

    /**
     * Predicted Y delta between {@code (x, z)} and a reference point.
     * Sign convention: positive means {@code (x, z)} is higher than the
     * reference. Used by ridge detection to find cells that rise above
     * the village origin's predicted Y by more than a threshold.
     */
    public static int predictedYDelta(ServerLevel level,
                                      int refX, int refZ, int x, int z) {
        return predictedSurfaceY(level, x, z) - predictedSurfaceY(level, refX, refZ);
    }
}
