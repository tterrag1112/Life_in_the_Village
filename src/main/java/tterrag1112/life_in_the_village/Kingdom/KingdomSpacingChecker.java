// src/main/java/tterrag1112/life_in_the_village/Kingdom/KingdomSpacingChecker.java
package tterrag1112.life_in_the_village.Kingdom;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;

/**
 * Decides whether a candidate kingdom origin is far enough from
 * existing kingdoms that their territorial claims won't overlap.
 *
 * <h3>Rule</h3>
 * For each existing kingdom with a known claim, require:
 * <pre>
 *   distance(newOrigin, existingOrigin) &gt;
 *       existingEffectiveRadius + newProjectedRadius + BUFFER
 * </pre>
 * Existing kingdoms without computed claims (legacy worlds) fall
 * back to a conservative {@link #LEGACY_FALLBACK_DISTANCE} block
 * radius — enough to keep new kingdoms out of their general area
 * without making old saves unusable.
 *
 * <h3>Buffer</h3>
 * The {@link #SPACING_BUFFER_BLOCKS} value gives breathing room beyond
 * a strict tangent — claims that just kiss each other still feel
 * cramped, especially once each kingdom adds outlying villages near
 * its claim edge. 200 blocks is roughly two villages of breathing room.
 */
public final class KingdomSpacingChecker {

    private KingdomSpacingChecker() {}

    /** Min separation buffer between two claim edges. */
    public static final int SPACING_BUFFER_BLOCKS = 200;

    /** Distance assumed for legacy kingdoms with no claim record. */
    public static final int LEGACY_FALLBACK_DISTANCE = 1500;

    /**
     * Returns true if the proposed origin doesn't overlap any existing
     * kingdom's claim envelope.
     *
     * @param newProjectedRadiusBlocks estimate of how far the new
     *        kingdom's claim will reach. See
     *        {@link #estimateProjectedRadius(float)}.
     */
    public static boolean isSpacedClear(VillageSavedData data,
                                        BlockPos newOrigin,
                                        int newProjectedRadiusBlocks) {
        for (Kingdom k : data.getAllKingdoms()) {
            KingdomClaim claim = k.getTerritorialClaim().orElse(null);
            int existingRadius;
            BlockPos existingOrigin;

            if (claim != null) {
                existingRadius = claim.effectiveRadiusBlocks();
                existingOrigin = originOf(claim);
            } else {
                // Legacy kingdom without claim — use the first village pos
                // as origin and assume fallback radius
                BlockPos approx = approximateOrigin(data, k);
                if (approx == null) continue;
                existingOrigin = approx;
                existingRadius = LEGACY_FALLBACK_DISTANCE;
            }

            int required = existingRadius + newProjectedRadiusBlocks + SPACING_BUFFER_BLOCKS;
            long dx = newOrigin.getX() - existingOrigin.getX();
            long dz = newOrigin.getZ() - existingOrigin.getZ();
            long distSq = dx * dx + dz * dz;
            long requiredSq = (long) required * required;

            if (distSq < requiredSq) return false;
        }
        return true;
    }

    /**
     * Conservative projected-radius estimate from a budget value.
     * Assumes open terrain (1 cost unit per cell), then converts cell
     * count to a circular radius. Real terrain typically claims fewer
     * cells per budget unit, so this errs on the side of more spacing.
     */
    public static int estimateProjectedRadius(float budget) {
        double cells = budget; // 1 cost unit ~ 1 cell on flat land
        double radiusCells = Math.sqrt(cells / Math.PI);
        return (int) (radiusCells * AtlasCell.CELL_SIZE);
    }

    private static BlockPos originOf(KingdomClaim claim) {
        int cx = AtlasCell.unpackX(claim.originCellKey());
        int cz = AtlasCell.unpackZ(claim.originCellKey());
        return new BlockPos(
                (cx << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF,
                64,
                (cz << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF);
    }

    private static BlockPos approximateOrigin(VillageSavedData data, Kingdom k) {
        for (var vid : k.getVillageIds()) {
            var v = data.getVillageById(vid).orElse(null);
            if (v == null) continue;
            BlockPos anchor = v.getAnchorPos();
            if (anchor != null) return anchor;
        }
        return null;
    }
}