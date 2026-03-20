package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import java.util.List;

/**
 * Immutable snapshot of the terrain around a village origin.
 */
public record TerrainProfile(
        BlockPos origin,
        int baseY,
        int minY,
        int maxY,
        float flatRatio,
        float waterRatio,
        float steepRatio,
        /** 0‥1 — higher = better location */
        float suitability,
        List<BlockPos> flatCandidates,
        TerrainAnalyzer.FlatDirection bestFlatDir
) {

    /** Height variance across the sample area. */
    public int heightVariance() {
        return maxY - minY;
    }

    /** True when the terrain is suitable for a village at all. */
    public boolean isSuitable() {
        return suitability > 0.15f;
    }

    /**
     * Selects the flat candidate closest to a given offset direction.
     * Falls back to origin if no candidates exist.
     */
    public BlockPos bestFlatNear(int offsetX, int offsetZ) {
        BlockPos target = origin.offset(offsetX, 0, offsetZ);
        return flatCandidates.stream()
                .min((a, b) -> {
                    double da = a.distSqr(target);
                    double db = b.distSqr(target);
                    return Double.compare(da, db);
                })
                .orElse(new BlockPos(
                        target.getX(),
                        baseY,
                        target.getZ()));
    }
}