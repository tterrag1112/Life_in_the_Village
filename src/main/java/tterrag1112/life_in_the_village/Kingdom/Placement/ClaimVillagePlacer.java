// src/main/java/tterrag1112/life_in_the_village/Kingdom/Placement/ClaimVillagePlacer.java
package tterrag1112.life_in_the_village.Kingdom.Placement;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Kingdom.KingdomClaim;
import tterrag1112.life_in_the_village.Village.VillageTypeData;
import tterrag1112.life_in_the_village.Village.VillageTypeRegistry;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.*;

/**
 * Given a {@link KingdomClaim} and a list of village types to place,
 * selects the best-scoring claimed cell for each type.
 *
 * <h3>Strategy</h3>
 * Villages are placed one at a time, largest-first-tagged (capital)
 * taking priority. For each type:
 * <ol>
 *   <li>Filter claimed cells by {@link VillageTypeMatcher}.</li>
 *   <li>Score each with {@link VillagePlacementScorer}, passing the
 *       list of already-chosen positions for proximity penalties.</li>
 *   <li>Pick the top. If none qualify, try relaxed matching.</li>
 *   <li>If still none, report unplaced.</li>
 * </ol>
 *
 * <p>The capital is placed first and anchored at (or very near) the
 * kingdom origin by treating it as a strict-match site but with a
 * distance-from-origin penalty that strongly prefers the origin cell.
 */
public final class ClaimVillagePlacer {

    private ClaimVillagePlacer() {}

    /**
     * Maximum distance (blocks) from origin for the capital. If the
     * origin cell itself doesn't match the capital's tags, we expand
     * the search outward within this radius.
     */
    private static final int CAPITAL_SEARCH_RADIUS = 300;

    public record PlacementResult(
            String villageType,
            /** Null when no cell matched and the type couldn't be placed. */
            BlockPos position,
            AtlasCell cell,
            double score,
            boolean relaxed
    ) {
        public boolean placed() { return position != null; }
    }

    /**
     * Plans positions for each village type within the claim.
     *
     * @param compositionTypes list of village type ids, index 0 = capital
     */
    public static List<PlacementResult> plan(WorldAtlas atlas,
                                             KingdomClaim claim,
                                             BlockPos capitalOrigin,
                                             List<String> compositionTypes) {
        List<PlacementResult> results = new ArrayList<>();
        List<BlockPos> placedPositions = new ArrayList<>();

        // Resolve cells once
        List<AtlasCell> claimedCells = new ArrayList<>(claim.claimedCellKeys().size());
        for (long key : claim.claimedCellKeys()) {
            AtlasCell c = atlas.getCellByCoord(AtlasCell.unpackX(key), AtlasCell.unpackZ(key));
            if (c != null) claimedCells.add(c);
        }

        for (int i = 0; i < compositionTypes.size(); i++) {
            String typeId = compositionTypes.get(i);
            VillageTypeData type = VillageTypeRegistry.INSTANCE.getType(typeId);
            if (type == null) {
                results.add(new PlacementResult(typeId, null, null, 0, false));
                continue;
            }

            boolean isCapital = (i == 0);
            PlacementResult best = selectCell(
                    atlas, type, claimedCells, capitalOrigin, placedPositions,
                    isCapital, typeId);

            results.add(best);
            if (best.placed()) placedPositions.add(best.position);
        }
        return results;
    }

    // =========================================================================
    // Selection
    // =========================================================================

    private static PlacementResult selectCell(
            WorldAtlas atlas, VillageTypeData type,
            List<AtlasCell> claimedCells, BlockPos capitalOrigin,
            List<BlockPos> placedPositions, boolean isCapital, String typeId) {

        // Strict pass
        AtlasCell bestCell = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (AtlasCell cell : claimedCells) {
            if (!VillageTypeMatcher.cellMatchesType(cell, type)) continue;

            double s = VillagePlacementScorer.score(
                    atlas, cell, type, capitalOrigin, placedPositions);
            if (s == Double.NEGATIVE_INFINITY) continue;

            // Capital-specific: huge bonus for cells close to the kingdom origin
            if (isCapital) {
                int dx = cell.blockCenterX() - capitalOrigin.getX();
                int dz = cell.blockCenterZ() - capitalOrigin.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > CAPITAL_SEARCH_RADIUS) continue;
                s += Math.max(0, 3.0 - dist / 100.0);
            }

            if (s > bestScore) { bestScore = s; bestCell = cell; }
        }

        if (bestCell != null) {
            return makeResult(typeId, bestCell, bestScore, false);
        }

        // Relaxed pass — drop category tags, keep physical gates
        for (AtlasCell cell : claimedCells) {
            if (!VillageTypeMatcher.cellMatchesRelaxed(cell, type)) continue;

            double s = VillagePlacementScorer.score(
                    atlas, cell, type, capitalOrigin, placedPositions);
            if (s == Double.NEGATIVE_INFINITY) continue;

            if (isCapital) {
                int dx = cell.blockCenterX() - capitalOrigin.getX();
                int dz = cell.blockCenterZ() - capitalOrigin.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > CAPITAL_SEARCH_RADIUS) continue;
            }

            if (s > bestScore) { bestScore = s; bestCell = cell; }
        }

        if (bestCell != null) {
            return makeResult(typeId, bestCell, bestScore, true);
        }
        return new PlacementResult(typeId, null, null, 0, false);
    }

    private static PlacementResult makeResult(String typeId, AtlasCell cell,
                                              double score, boolean relaxed) {
        BlockPos pos = new BlockPos(
                cell.blockCenterX(), cell.centerY(), cell.blockCenterZ());
        return new PlacementResult(typeId, pos, cell, score, relaxed);
    }
}