package tterrag1112.life_in_the_village.Kingdom.Settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.GeneratorTerrainSource;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.TerrainSource;
import tterrag1112.life_in_the_village.Village.VillageTypeData;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;

import java.util.Optional;

/**
 * Track C1-b — the SURVEY stage anchor-pick for a chartered settlement.
 *
 * <p>Given a CHARTERED {@link SettlementCharter}, scans the committed atlas
 * cell (64x64 blocks, {@link AtlasCell#CELL_SIZE}) through the load-free
 * {@link GeneratorTerrainSource} to find (a) an exact block anchor and
 * (b) a footprint-fit suitability score for the charter's village type.
 *
 * <h3>Sampling path</h3>
 * The C1-b design names {@code GeneratorTerrainSource} as the survey
 * sampler (doc 15; {@code 08-C0-SAMPLING-SPIKE.md} section 8). This reuses
 * {@code DeepTerrainInspector}'s suitability <i>formula</i> and step-4
 * grid <i>shape</i> but drives it off {@code GeneratorTerrainSource}
 * (the V2 {@link TerrainSource} seam) rather than {@code AtlasSampler},
 * for two reasons:
 * <ul>
 *   <li>It is the load-free sampler the V2 seam is built around, keeping
 *       the survey on the canonical V2 path (and consistent with C1-c
 *       realization, which will scan the same source via
 *       {@code V2FeatureMap.scan}).</li>
 *   <li>{@link TerrainSource#height} returns an exact block Y, so the
 *       chosen anchor carries a real ground height -- the pre-survey
 *       cell-centre pin used a nominal Y=64.</li>
 * </ul>
 * This is the <b>anchor-pick tier</b> (C0 section 8.2): a coarse step-4
 * grid over one 64-block cell (~17x17 = 289 samples, r~48). Per C0
 * section 3 this costs tens of ms -- safe synchronously. The full
 * {@code V2FeatureMap.scan} (the 1-2s region-extraction tier) is
 * explicitly NOT run here.
 *
 * <h3>Suitability</h3>
 * Mirrors {@code DeepTerrainInspector.inspect}: a column is water (from
 * the generator water-detector via the source's synthesized WATER
 * surface), flat (|dy|&lt;=2 vs the cell-centre baseline), or steep. The
 * score is {@code flatRatio - 2*waterRatio - 0.3*steepRatio}. The
 * best anchor is the flattest non-water grid point nearest the cell
 * centre; the returned score is the whole-cell suitability (the
 * footprint-fit signal for {@code charter.surveyed(anchor, score)}).
 *
 * <h3>Threading</h3>
 * Synchronous, server-thread only (one {@code GeneratorTerrainSource} per
 * survey, scanned, discarded). C0 section 5/8.3 note the source is safe
 * off-thread; an off-thread survey scheduler is a later C1 slice and is
 * NOT built here -- see {@code CharterSurveySystem} for the plug-in point.
 */
public final class CharterSurvey {

    private CharterSurvey() {}

    /** Grid spacing -- matches {@code DeepTerrainInspector.SAMPLE_STEP}. */
    private static final int SAMPLE_STEP = 4;

    /** Flatness tolerance (blocks) vs the cell-centre baseline. */
    private static final int FLAT_TOLERANCE = 2;

    /** Suitability acceptance threshold -- matches DeepTerrainInspector. */
    private static final float SUITABILITY_THRESHOLD = 0.05f;

    /** Outcome of a survey: an exact anchor + footprint score, or empty
     *  when the cell has no viable (non-water, not-all-steep) anchor. */
    public record Result(Optional<BlockPos> anchor, float footprintScore,
                         float flatRatio, float waterRatio, float steepRatio) {
        public boolean viable() { return anchor.isPresent(); }
    }

    /**
     * Survey the charter's target cell. Builds one
     * {@link GeneratorTerrainSource} from {@code level}, scans the cell's
     * 64x64 block bounds, and returns the best anchor + suitability.
     *
     * @param level   the overworld (source of the generator + random state)
     * @param charter the CHARTERED charter to survey
     */
    public static Result survey(ServerLevel level, SettlementCharter charter) {
        TerrainSource source = new GeneratorTerrainSource(level);
        return survey(source, level.getSeaLevel(), charter);
    }

    /**
     * Test seam -- survey through an arbitrary {@link TerrainSource}
     * (the harness's synthetic source, or a pre-built generator source).
     */
    public static Result survey(TerrainSource source, int seaLevel,
                                SettlementCharter charter) {
        long key = charter.targetCellKey();
        int cellX = AtlasCell.unpackX(key);
        int cellZ = AtlasCell.unpackZ(key);

        // Cell world bounds: [min, min + CELL_SIZE).
        int minX = cellX << AtlasCell.CELL_SHIFT;
        int minZ = cellZ << AtlasCell.CELL_SHIFT;
        int centreX = minX + AtlasCell.CELL_HALF;
        int centreZ = minZ + AtlasCell.CELL_HALF;
        int maxX = minX + AtlasCell.CELL_SIZE - 1;
        int maxZ = minZ + AtlasCell.CELL_SIZE - 1;

        // Baseline: the cell-centre surface height (DeepTerrainInspector
        // uses the centre column as the flatness reference).
        int baseY = source.height(centreX, centreZ);

        int samples = 0, flat = 0, water = 0, steep = 0;

        // Best anchor = flattest non-water point nearest the cell centre.
        BlockPos best = null;
        long bestCentreDistSq = Long.MAX_VALUE;
        int bestBucket = Integer.MAX_VALUE; // 0 = flat, 1 = gentle-steep

        for (int x = minX; x <= maxX; x += SAMPLE_STEP) {
            for (int z = minZ; z <= maxZ; z += SAMPLE_STEP) {
                int y = source.height(x, z);
                boolean isWater = isWaterColumn(source, x, y, z, seaLevel);

                int dy = Math.abs(y - baseY);
                samples++;
                if (isWater) {
                    water++;
                    continue; // never anchor on water
                }
                boolean isFlat = dy <= FLAT_TOLERANCE;
                if (isFlat) flat++;
                else        steep++;

                int bucket = isFlat ? 0 : 1;
                long dx = x - centreX, dz = z - centreZ;
                long distSq = dx * dx + dz * dz;
                // Prefer flat over steep; within a bucket prefer nearest centre.
                if (bucket < bestBucket
                        || (bucket == bestBucket && distSq < bestCentreDistSq)) {
                    bestBucket = bucket;
                    bestCentreDistSq = distSq;
                    best = new BlockPos(x, y, z);
                }
            }
        }

        if (samples == 0) {
            return new Result(Optional.empty(), -1f, 0f, 0f, 0f);
        }

        float flatR  = (float) flat  / samples;
        float waterR = (float) water / samples;
        float steepR = (float) steep / samples;
        float suit   = flatR - waterR * 2.0f - steepR * 0.3f;

        // No viable anchor if the cell is all water / no land point cleared,
        // or the suitability is below the acceptance threshold. Leaving the
        // anchor empty signals the caller to keep the charter CHARTERED.
        boolean viable = best != null && suit > SUITABILITY_THRESHOLD;
        return new Result(viable ? Optional.of(best) : Optional.empty(),
                suit, flatR, waterR, steepR);
    }

    /**
     * Water probe matching {@code V2FeatureMap}'s convention: the
     * synthesized source reports WATER as the top block at the surface Y,
     * so the surface block being a water fluid means a water column. Also
     * treats below-sea-level surfaces as water for robustness (mirrors
     * DeepTerrainInspector treating OCEAN/RIVER biomes as water).
     */
    private static boolean isWaterColumn(TerrainSource source, int x, int y,
                                         int z, int seaLevel) {
        var state = source.blockAt(x, y, z);
        if (!state.getFluidState().isEmpty()) return true;
        return y < seaLevel;
    }

    /**
     * Convenience footprint-radius accessor -- the cell is fixed at 64x64,
     * but the type's footprint radius (C0 section 8.2; {@code
     * DeepTerrainInspector.footprintRadius}) is recorded for the C1-c
     * realization slice which will widen the scan beyond the single cell
     * when the footprint spills.
     */
    public static int footprintRadius(VillageTypeData typeData) {
        return tterrag1112.life_in_the_village.Kingdom.Placement.DeepTerrainInspector
                .footprintRadius(typeData);
    }
}
