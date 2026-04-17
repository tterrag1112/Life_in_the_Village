package tterrag1112.life_in_the_village.Kingdom.Placement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Village.VillageTag;
import tterrag1112.life_in_the_village.Village.VillageTypeData;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.World.Atlas.AtlasSampler;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;

import java.util.Set;

/**
 * Noise-only terrain quality predictor for atlas-level village planning.
 *
 * <p>Mirrors {@link tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer}'s
 * suitability formula but uses {@link AtlasSampler} (pure noise, no chunk loading)
 * instead of loaded blocks. This makes it safe to call during worldgen planning
 * on any thread.
 *
 * <h3>Design match</h3>
 * <ul>
 *   <li>Same sample step (4 blocks) as TerrainAnalyzer.</li>
 *   <li>Same suitability formula: {@code flatRatio - waterRatio * 2.0f - steepRatio * 0.3f}.</li>
 *   <li>Same acceptance threshold: {@code suitability > 0.05f}.</li>
 *   <li>Slightly conservative on steep: tree-covered 3–7 block slopes that
 *       TerrainAnalyzer would exclude from steepCount are counted as steep here
 *       (we don't know about trees at noise time). This means we may over-predict
 *       steepness on forested slopes, which is acceptable — we'd rather reject a
 *       marginal site at plan time than fail at realisation.</li>
 * </ul>
 */
public final class DeepTerrainInspector {

    private DeepTerrainInspector() {}

    /** Grid spacing — matches {@code TerrainAnalyzer.SAMPLE_STEP}. */
    private static final int SAMPLE_STEP = 4;

    // =========================================================================
    // Result type
    // =========================================================================

    public record InspectionResult(
            float flatRatio,
            float waterRatio,
            float steepRatio,
            float suitability
    ) {
        /**
         * Returns {@code true} if the predicted suitability would pass
         * {@code TerrainProfile.isSuitable()} (threshold: {@code > 0.05f}).
         */
        public boolean predictsSuitable() {
            return suitability > 0.05f;
        }

        /**
         * Type-aware variant — uses {@link TerrainProfile#computeSuitability} so
         * the prediction matches the same formula {@link VillagePlanner} uses at
         * realisation time. RIVERSIDE/COASTAL sites with high water ratios will
         * pass where the generic check would reject them.
         */
        public boolean predictsSuitableFor(Set<VillageTag> tags) {
            return TerrainProfile.computeSuitability(flatRatio, waterRatio, steepRatio, tags) > 0.05f;
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Inspects terrain quality at {@code centre} using noise-only sampling.
     *
     * <p>Safe to call from any thread — uses {@link AtlasSampler#sampleHeight}
     * and {@link AtlasSampler#sampleBiome} which never load chunks.
     *
     * @param centre        world-space centre of the inspection area
     * @param sampleRadius  radius in blocks; use {@link #footprintRadius(VillageTypeData)}
     *                      for the per-type estimate, or 48 for a quick check
     */
    public static InspectionResult inspect(ServerLevel level,
                                           BlockPos centre,
                                           int sampleRadius) {
        int seaLevel = level.getSeaLevel();
        int baseY    = AtlasSampler.sampleHeight(level, centre.getX(), centre.getZ());

        int samples = 0, flat = 0, water = 0, steep = 0;

        for (int dx = -sampleRadius; dx <= sampleRadius; dx += SAMPLE_STEP) {
            for (int dz = -sampleRadius; dz <= sampleRadius; dz += SAMPLE_STEP) {
                int bx = centre.getX() + dx;
                int bz = centre.getZ() + dz;
                int y  = AtlasSampler.sampleHeight(level, bx, bz);

                // Sample biome at or above sea level — same logic AtlasSampler.sampleCell()
                // uses to avoid resolving cave biomes under oceans.
                int biomeY = Math.max(y, seaLevel) + 4;
                var biomeHolder = AtlasSampler.sampleBiome(level, bx, biomeY, bz);
                String biomeKey = biomeHolder.unwrapKey()
                        .map(k -> k.identifier().toString())
                        .orElse("minecraft:plains");
                BiomeCategory cat = BiomeCategory.fromBiomeKey(biomeKey);
                boolean isWater = (cat == BiomeCategory.OCEAN || cat == BiomeCategory.RIVER);

                int dy = Math.abs(y - baseY);
                samples++;
                if (isWater)      water++;
                else if (dy <= 2) flat++;
                else              steep++;  // dy > 2, not water → counts as steep
            }
        }

        if (samples == 0) return new InspectionResult(0f, 0f, 0f, -1f);

        float flatR  = (float) flat  / samples;
        float waterR = (float) water / samples;
        float steepR = (float) steep / samples;
        float suit   = flatR - waterR * 2.0f - steepR * 0.3f;

        return new InspectionResult(flatR, waterR, steepR, suit);
    }

    /**
     * Returns the recommended inspection radius (blocks) for a village type,
     * derived from its shape profile. Larger layouts need a wider check so
     * terrain problems at the edges are caught before realisation.
     */
    public static int footprintRadius(VillageTypeData typeData) {
        var p = typeData.getShapeProfile();
        return switch (p.shapeType()) {
            case RADIAL, DUAL_PLAZA, PLAZA, CROSSROADS, SPRAWL ->
                    50 + p.maxRings() * 30;   // 2 rings → 110, 4 rings → 170
            case LINEAR, CHAIN, ROADSIDE ->
                    40 + p.maxRings() * 20;   // 2 rings → 80
            case CLUSTERED, ENCLAVE, GROVE ->
                    70;
            case OUTPOST, HILLTOP ->
                    60;
            default ->
                    80;
        };
    }
}
