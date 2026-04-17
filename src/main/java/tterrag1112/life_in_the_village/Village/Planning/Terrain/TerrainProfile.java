// src/main/java/tterrag1112/life_in_the_village/Village/Planning/TerrainProfile.java
package tterrag1112.life_in_the_village.Village.Planning.Terrain;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Village.Planning.VillagePlanner;
import tterrag1112.life_in_the_village.Village.VillageTag;

import java.util.List;
import java.util.Set;

/**
 * Immutable snapshot of the terrain analysis around a village origin.
 * Produced by {@link TerrainAnalyzer} and consumed by
 * {@link VillagePlanner} to make layout decisions.
 */
public record TerrainProfile(
        BlockPos origin,
        int      baseY,
        int      minY,
        int      maxY,
        float    flatRatio,
        float    waterRatio,
        float    steepRatio,
        /** Fraction of sampled columns that have tree cover. */
        float    treeRatio,
        /** 0–1, higher = better site. */
        float    suitability,
        /** Flat surface positions — good for building placement. */
        List<BlockPos> flatCandidates,
        /**
         * Flat positions with no tree cover — preferred for initial
         * placement to minimise clearing work.
         */
        List<BlockPos> clearingCandidates,
        /** Cardinal direction with the most flat terrain. */
        TerrainAnalyzer.FlatDirection bestFlatDir,
        /**
         * Direction toward the nearest significant water body,
         * or null if no water body was detected.
         */
        @Nullable TerrainAnalyzer.FlatDirection waterFacingDir,
        /**
         * Information about the nearest significant water body,
         * or null if none detected.
         */
        @Nullable TerrainAnalyzer.WaterBodyInfo waterBody,
        /**
         * Ridge segments detected within the sample area.
         * The planner routes around these rather than flattening them.
         */
        List<TerrainAnalyzer.RidgeInfo> ridges,
        @javax.annotation.Nullable
        TerrainAnalyzer.FlatDirection slopeDir,
                int slopeMagnitude
) {

    public boolean hasSlope() {return slopeDir != null && slopeMagnitude >= 6;}

    public int heightVariance() { return maxY - minY; }

    public boolean isSuitable() { return suitability > 0.05f; }

    /**
     * Type-aware suitability formula.
     *
     * <ul>
     *   <li>RIVERSIDE / COASTAL: water proximity is the desired outcome —
     *       the water penalty is removed. Only sites that are &gt;60% submerged
     *       are rejected (penalised by the excess).</li>
     *   <li>MOUNTAIN / DEFENSIVE: steep terrain is expected for highland or
     *       fortress siting — the steep penalty is reduced to near-zero.</li>
     *   <li>All others: the standard formula applies.</li>
     * </ul>
     */
    public static float computeSuitability(float flatRatio, float waterRatio,
                                           float steepRatio, Set<VillageTag> tags) {
        if (tags.contains(VillageTag.RIVERSIDE) || tags.contains(VillageTag.COASTAL)) {
            float excessWater = Math.max(0f, waterRatio - 0.6f) * 5.0f;
            return flatRatio - excessWater - steepRatio * 0.5f;
        }
        if (tags.contains(VillageTag.MOUNTAIN) || tags.contains(VillageTag.DEFENSIVE)) {
            return flatRatio - waterRatio * 2.0f - steepRatio * 0.05f;
        }
        return flatRatio - waterRatio * 2.0f - steepRatio * 0.3f;
    }

    /** Type-aware suitability check — uses {@link #computeSuitability} for the village's tags. */
    public boolean isSuitableFor(Set<VillageTag> tags) {
        return computeSuitability(flatRatio, waterRatio, steepRatio, tags) > 0.05f;
    }

    /** True if a significant water body was detected near the site. */
    public boolean hasWater() { return waterBody != null; }

    /** True if any ridges were detected. */
    public boolean hasRidges() { return !ridges.isEmpty(); }

    /**
     * Selects the flat candidate closest to a given offset from origin.
     * Prefers candidates from {@link #clearingCandidates} (no tree cover)
     * when available, falling back to all flat candidates.
     * Falls back to origin-offset if no candidates exist.
     */
    public BlockPos bestFlatNear(int offsetX, int offsetZ) {
        BlockPos target = origin.offset(offsetX, 0, offsetZ);

        // Prefer clearing candidates first
        List<BlockPos> pool = clearingCandidates.isEmpty()
                ? flatCandidates
                : clearingCandidates;

        return pool.stream()
                .min((a, b) -> Double.compare(
                        a.distSqr(target), b.distSqr(target)))
                .orElse(new BlockPos(
                        target.getX(), baseY, target.getZ()));
    }

    /**
     * Returns true if the given XZ position falls within any detected
     * ridge's footprint with the given clearance radius.
     * Used by the planner to reject building slots on ridges.
     */
    public boolean isOnRidge(BlockPos pos, int clearance) {
        for (TerrainAnalyzer.RidgeInfo ridge : ridges) {
            if (ridge.overlapsCircle(pos, clearance)) return true;
        }
        return false;
    }

    /**
     * Returns the direction toward the nearest water body, or
     * {@link #bestFlatDir} if no water body was detected.
     * Used to orient civic buildings toward water when present.
     */
    public TerrainAnalyzer.FlatDirection primaryOrientationDir() {
        return waterFacingDir != null ? waterFacingDir : bestFlatDir;
    }
}