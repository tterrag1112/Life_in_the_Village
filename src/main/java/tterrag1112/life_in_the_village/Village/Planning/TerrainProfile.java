// src/main/java/tterrag1112/life_in_the_village/Village/Planning/TerrainProfile.java
package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;

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
        List<TerrainAnalyzer.RidgeInfo> ridges
) {

    public int heightVariance() { return maxY - minY; }

    public boolean isSuitable() { return suitability > 0.05f; }

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