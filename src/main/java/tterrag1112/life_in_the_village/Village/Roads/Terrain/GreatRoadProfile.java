package tterrag1112.life_in_the_village.Village.Roads.Terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Roads.Graph.GreatRoadCharacter;

import java.util.ArrayList;
import java.util.List;

/**
 * Smoothed elevation profile and per-position classification for a GREAT_ROAD dense path.
 *
 * <h3>Profile computation</h3>
 * A Gaussian-weighted moving average over the terrain Y values of the dense centerline.
 * Window half-width depends on {@link GreatRoadCharacter.CharacterTag}:
 * <ul>
 *   <li>MOUNTAIN_HUGGING — ±8 (tight, follows terrain closely)</li>
 *   <li>PLAINS_STRAIGHT — ±25 (wide, long-horizon smoothing)</li>
 *   <li>all others — ±15</li>
 * </ul>
 *
 * <h3>Classification</h3>
 * Each position is compared against profile Y vs terrain Y:
 * <ul>
 *   <li>|delta| ≤ 1 → lateral slope check → SLOPED_LEFT / SLOPED_RIGHT / NORMAL</li>
 *   <li>profileY − terrainY > 1 → RAISED (support structure needed below)</li>
 *   <li>terrainY − profileY > 1 → LOWERED (excavation or cut needed)</li>
 * </ul>
 * Lateral slope uses perpendicular terrain Y at ±3 blocks from road centre; a
 * difference &gt; 2 blocks triggers the SLOPED classification.
 */
public final class GreatRoadProfile {

    private GreatRoadProfile() {}

    // =========================================================================
    // Classification
    // =========================================================================

    public enum PositionClassification {
        /** Minor discrepancy (≤1 block). Conservative smoother applies. */
        NORMAL,
        /** Profile is above terrain — fill or viaduct needed below. */
        RAISED,
        /** Terrain is above profile — excavation cut needed. */
        LOWERED,
        /** Left side (looking along road) is significantly higher — wall on left. */
        SLOPED_LEFT,
        /** Right side is significantly higher — wall on right. */
        SLOPED_RIGHT
    }

    // =========================================================================
    // Profile computation
    // =========================================================================

    /**
     * Computes a Gaussian-smoothed elevation profile for the dense centerline.
     * The Y values in {@code dense} are used directly (they are surface-snapped
     * heightmap results from densification).
     *
     * @param dense     block-dense centerline; Y values are terrain heights
     * @param character great-road character (may be {@code null} → default window)
     * @return int[] of smoothed profile Y, same length as {@code dense}
     */
    public static int[] computeProfile(List<BlockPos> dense,
                                        GreatRoadCharacter character) {
        int n = dense.size();
        int[] result = new int[n];
        if (n == 0) return result;

        int window = windowFor(character != null ? character.tag() : null);
        double sigma = Math.max(1.0, window / 2.0);
        double inv2s2 = 1.0 / (2.0 * sigma * sigma);

        int[] terrainY = new int[n];
        for (int i = 0; i < n; i++) terrainY[i] = dense.get(i).getY();

        for (int i = 0; i < n; i++) {
            int lo = Math.max(0, i - window);
            int hi = Math.min(n - 1, i + window);
            double weightSum = 0.0;
            double valueSum  = 0.0;
            for (int j = lo; j <= hi; j++) {
                double d = j - i;
                double w = Math.exp(-d * d * inv2s2);
                weightSum += w;
                valueSum  += w * terrainY[j];
            }
            result[i] = (int) Math.round(valueSum / weightSum);
        }

        return result;
    }

    // =========================================================================
    // Classification
    // =========================================================================

    /**
     * Classifies each position in the dense centerline.
     *
     * @param dense    block-dense centerline
     * @param profileY smoothed profile from {@link #computeProfile}
     * @param level    server level for lateral heightmap queries
     * @return list of classifications, same length as {@code dense}
     */
    public static List<PositionClassification> classify(List<BlockPos> dense,
                                                         int[] profileY,
                                                         ServerLevel level) {
        int n = dense.size();
        List<PositionClassification> result = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            BlockPos pos = dense.get(i);
            int terrain = pos.getY();
            int profile = profileY[i];
            int delta   = profile - terrain; // positive = road above terrain

            PositionClassification cls;
            if (delta > 1) {
                cls = PositionClassification.RAISED;
            } else if (delta < -1) {
                cls = PositionClassification.LOWERED;
            } else {
                int[] perp  = computePerp(dense, i);
                int leftY   = queryTerrainY(level, pos,  perp[0],  perp[1], 3);
                int rightY  = queryTerrainY(level, pos, -perp[0], -perp[1], 3);
                int lateral = leftY - rightY;

                if (lateral > 2) {
                    cls = PositionClassification.SLOPED_LEFT;
                } else if (lateral < -2) {
                    cls = PositionClassification.SLOPED_RIGHT;
                } else {
                    cls = PositionClassification.NORMAL;
                }
            }

            result.add(cls);
        }

        return result;
    }

    // =========================================================================
    // Helpers — package-visible for reuse in sibling builders
    // =========================================================================

    public static int windowFor(GreatRoadCharacter.CharacterTag tag) {
        if (tag == null) return 15;
        return switch (tag) {
            case MOUNTAIN_HUGGING -> 8;
            case PLAINS_STRAIGHT  -> 25;
            default               -> 15;
        };
    }

    private static int queryTerrainY(ServerLevel level, BlockPos center,
                                      int perpX, int perpZ, int dist) {
        int x = center.getX() + perpX * dist;
        int z = center.getZ() + perpZ * dist;
        if (!level.isLoaded(new BlockPos(x, center.getY(), z))) return center.getY();
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    /**
     * Computes a unit perpendicular vector (XZ plane) for position {@code index}
     * in the dense centerline. Identical algorithm to UnifiedRoadPlacer.computePerp.
     */
    static int[] computePerp(List<BlockPos> line, int index) {
        BlockPos prev = index > 0                  ? line.get(index - 1) : line.get(index);
        BlockPos next = index < line.size() - 1    ? line.get(index + 1) : line.get(index);
        int dx    = next.getX() - prev.getX();
        int dz    = next.getZ() - prev.getZ();
        int headX = Integer.signum(dx);
        int headZ = Integer.signum(dz);
        if (headX != 0 && headZ != 0) {
            return (index & 1) == 0 ? new int[]{0, 1} : new int[]{1, 0};
        }
        return new int[]{-headZ, headX};
    }
}
