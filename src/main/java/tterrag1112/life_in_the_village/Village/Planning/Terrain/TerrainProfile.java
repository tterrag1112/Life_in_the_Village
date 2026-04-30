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

    /**
     * Returns the side length (W and L assumed roughly equal) of the
     * largest contiguous patch of flat candidates whose Y range is at
     * most {@code maxYVariance}. Used by recipe pre-checks to reject
     * villages whose largest building physically can't sit anywhere on
     * the available terrain.
     *
     * <p>Implementation: bins {@link #flatCandidates} by Y, finds the
     * cluster with the largest XZ bounding box, returns
     * {@code min(boundingBoxW, boundingBoxL)}. If no candidates qualify,
     * returns 0.
     *
     * <p>Approximate by design — the goal is to cheaply detect "obviously
     * impossible" terrain for very large buildings, not to certify exact
     * fit. The terrain step (post-planning) is responsible for actually
     * grading the chosen plot.
     */
    public int largestFlatPatchAvailable(int maxYVariance) {
        if (flatCandidates.isEmpty()) return 0;

        // Bin candidates by floor(Y / (maxYVariance+1)) so two candidates
        // in the same bin are guaranteed to be within maxYVariance Y of
        // each other. Two-pass sweep: candidates whose Y is within bin or
        // (bin±1) are eligible; this catches cases where the bin boundary
        // splits a flat plateau.
        int bin = Math.max(1, maxYVariance + 1);
        java.util.Map<Integer, java.util.List<BlockPos>> bins = new java.util.HashMap<>();
        for (BlockPos p : flatCandidates) {
            bins.computeIfAbsent(p.getY() / bin, k -> new java.util.ArrayList<>()).add(p);
        }

        int bestSide = 0;
        for (var entry : bins.entrySet()) {
            int bucket = entry.getKey();
            // Combine this bucket with its neighbours so a near-bucket
            // boundary doesn't artificially split a plateau.
            java.util.List<BlockPos> patch = new java.util.ArrayList<>(entry.getValue());
            java.util.List<BlockPos> below = bins.get(bucket - 1);
            java.util.List<BlockPos> above = bins.get(bucket + 1);
            if (below != null) patch.addAll(below);
            if (above != null) patch.addAll(above);

            // Filter to actual Y-range ≤ maxYVariance for THIS centre.
            int centerY = entry.getValue().get(0).getY();
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos p : patch) {
                if (Math.abs(p.getY() - centerY) > maxYVariance) continue;
                if (p.getX() < minX) minX = p.getX();
                if (p.getX() > maxX) maxX = p.getX();
                if (p.getZ() < minZ) minZ = p.getZ();
                if (p.getZ() > maxZ) maxZ = p.getZ();
            }
            if (minX == Integer.MAX_VALUE) continue;
            int side = Math.min(maxX - minX, maxZ - minZ);
            if (side > bestSide) bestSide = side;
        }
        return bestSide;
    }
}