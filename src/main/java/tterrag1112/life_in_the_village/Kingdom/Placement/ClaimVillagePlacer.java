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
 * <h3>Strategy — three-pass selection</h3>
 * Villages are placed one at a time, capital (index 0) first.
 * For each type:
 * <ol>
 *   <li><b>Pass 1 — atlas filter (loose).</b> Walk claimed cells. Apply
 *       {@link VillageTypeMatcher} tag filter. Apply loose atlas-level thresholds
 *       (water &lt; 0.40, steep &lt; 0.40, buildable &gt; 0.25). Score with
 *       {@link VillagePlacementScorer#quickScore}. Keep the top
 *       {@value #PASS1_MAX_CANDIDATES} candidates.</li>
 *   <li><b>Pass 2 — deep terrain inspection.</b> Run
 *       {@link DeepTerrainInspector#inspect} (noise-only, no chunk loading) on
 *       each candidate. Reject those whose predicted suitability would fail
 *       {@code TerrainProfile.isSuitable()} (&le; 0.05). If all candidates fail
 *       deep inspection the pass-1 list is used as-is (safety fallback — the
 *       realiser has its own suitability gate).</li>
 *   <li><b>Pass 3 — full scoring.</b> Run {@link VillagePlacementScorer#score}
 *       on the surviving candidates and pick the best. If none pass the strict
 *       scorer, a relaxed-matching fallback mirrors the original behaviour.</li>
 * </ol>
 *
 * <h3>Co-location prevention</h3>
 * <ul>
 *   <li><b>Within-kingdom:</b> after each successful selection the winning cell
 *       and its neighbours within the village's footprint radius are added to a
 *       {@code reservedCells} set. Subsequent selections skip reserved cells in
 *       Pass 1.</li>
 *   <li><b>Cross-kingdom:</b> the caller supplies {@code existingVillagePositions}
 *       (positions of all already-planned or realised villages). These seed the
 *       {@code placedPositions} list, which the proximity-penalty logic in
 *       {@link VillagePlacementScorer} uses to avoid duplicate placement.</li>
 * </ul>
 */
public final class ClaimVillagePlacer {

    private ClaimVillagePlacer() {}

    /**
     * Maximum distance (blocks) from origin for the capital.
     */
    private static final int CAPITAL_SEARCH_RADIUS = 300;

    /**
     * Number of atlas-level candidates forwarded from Pass 1 to the full scorer.
     */
    private static final int PASS1_MAX_CANDIDATES = 15;

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

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Plans positions for each village type within the claim.
     *
     * @param compositionTypes         list of village type ids, index 0 = capital
     * @param existingVillagePositions anchor positions of already-planned/realised
     *                                 villages from other kingdoms — used to seed
     *                                 the proximity-penalty list
     */
    public static List<PlacementResult> plan(WorldAtlas atlas,
                                             KingdomClaim claim,
                                             BlockPos capitalOrigin,
                                             List<String> compositionTypes,
                                             List<BlockPos> existingVillagePositions) {
        List<PlacementResult> results = new ArrayList<>();
        // Seed with cross-kingdom positions so the proximity penalty prevents overlap.
        List<BlockPos> placedPositions = new ArrayList<>(existingVillagePositions);
        // Tracks cells (and their footprint neighbours) already assigned this run.
        Set<Long> reservedCells = new HashSet<>();

        // Resolve all claimed cells once.
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
                    atlas, type, claimedCells, capitalOrigin,
                    placedPositions, isCapital, typeId, reservedCells);

            results.add(best);
            if (best.placed()) {
                placedPositions.add(best.position());
                reserveFootprint(best.cell(), type, reservedCells);
            }
        }
        return results;
    }

    // =========================================================================
    // Two-pass selection (atlas-only — no chunk loading or noise sampling)
    // =========================================================================

    private static PlacementResult selectCell(
            WorldAtlas atlas, VillageTypeData type,
            List<AtlasCell> claimedCells, BlockPos capitalOrigin,
            List<BlockPos> placedPositions, boolean isCapital, String typeId,
            Set<Long> reservedCells) {

        // ── Pass 1: loose atlas filter — collect top-N candidates ─────────────
        List<Map.Entry<AtlasCell, Double>> pass1 = new ArrayList<>();

        for (AtlasCell cell : claimedCells) {
            if (reservedCells.contains(cell.key())) continue;
            if (!VillageTypeMatcher.cellMatchesType(cell, type)) continue;

            double s = VillagePlacementScorer.quickScore(
                    atlas, cell, type, capitalOrigin, placedPositions);
            if (s == Double.NEGATIVE_INFINITY) continue;

            if (isCapital) {
                double dist = distanceTo(cell, capitalOrigin);
                if (dist > CAPITAL_SEARCH_RADIUS) continue;
                s += Math.max(0, 3.0 - dist / 100.0);
            }

            pass1.add(Map.entry(cell, s));
        }

        pass1.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        if (pass1.size() > PASS1_MAX_CANDIDATES) pass1 = pass1.subList(0, PASS1_MAX_CANDIDATES);

        // ── Pass 2: full scorer — pick best candidate ─────────────────────────
        AtlasCell bestCell  = null;
        double    bestScore = Double.NEGATIVE_INFINITY;

        for (var e : pass1) {
            AtlasCell cell = e.getKey();
            double s = VillagePlacementScorer.score(
                    atlas, cell, type, capitalOrigin, placedPositions);
            if (s == Double.NEGATIVE_INFINITY) continue;

            if (isCapital) {
                double dist = distanceTo(cell, capitalOrigin);
                if (dist > CAPITAL_SEARCH_RADIUS) continue;
                s += Math.max(0, 3.0 - dist / 100.0);
            }

            if (s > bestScore) { bestScore = s; bestCell = cell; }
        }

        if (bestCell != null) return makeResult(typeId, bestCell, bestScore, false);

        // ── Relaxed fallback — drop category tags, keep physical gates ────────
        List<Map.Entry<AtlasCell, Double>> relaxedPass1 = new ArrayList<>();

        for (AtlasCell cell : claimedCells) {
            if (reservedCells.contains(cell.key())) continue;
            if (!VillageTypeMatcher.cellMatchesRelaxed(cell, type)) continue;

            double s = VillagePlacementScorer.quickScore(
                    atlas, cell, type, capitalOrigin, placedPositions);
            if (s == Double.NEGATIVE_INFINITY) continue;

            if (isCapital) {
                double dist = distanceTo(cell, capitalOrigin);
                if (dist > CAPITAL_SEARCH_RADIUS) continue;
                s += Math.max(0, 3.0 - dist / 100.0);
            }

            relaxedPass1.add(Map.entry(cell, s));
        }

        relaxedPass1.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        if (relaxedPass1.size() > PASS1_MAX_CANDIDATES)
            relaxedPass1 = relaxedPass1.subList(0, PASS1_MAX_CANDIDATES);

        for (var e : relaxedPass1) {
            AtlasCell cell = e.getKey();
            double s = VillagePlacementScorer.score(
                    atlas, cell, type, capitalOrigin, placedPositions);
            if (s == Double.NEGATIVE_INFINITY) continue;

            if (isCapital) {
                double dist = distanceTo(cell, capitalOrigin);
                if (dist > CAPITAL_SEARCH_RADIUS) continue;
            }

            if (s > bestScore) { bestScore = s; bestCell = cell; }
        }

        if (bestCell != null) return makeResult(typeId, bestCell, bestScore, true);
        return new PlacementResult(typeId, null, null, 0, false);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Marks the winning cell and its footprint neighbours as reserved. */
    private static void reserveFootprint(AtlasCell cell,
                                         VillageTypeData type,
                                         Set<Long> reservedCells) {
        int footprintCells =
                (DeepTerrainInspector.footprintRadius(type) >> AtlasCell.CELL_SHIFT) + 1;
        int cx = cell.cellX(), cz = cell.cellZ();
        for (int dx = -footprintCells; dx <= footprintCells; dx++) {
            for (int dz = -footprintCells; dz <= footprintCells; dz++) {
                reservedCells.add(AtlasCell.packKey(cx + dx, cz + dz));
            }
        }
    }

    private static double distanceTo(AtlasCell cell, BlockPos pos) {
        double dx = cell.blockCenterX() - pos.getX();
        double dz = cell.blockCenterZ() - pos.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static PlacementResult makeResult(String typeId, AtlasCell cell,
                                              double score, boolean relaxed) {
        BlockPos pos = new BlockPos(
                cell.blockCenterX(), cell.centerY(), cell.blockCenterZ());
        return new PlacementResult(typeId, pos, cell, score, relaxed);
    }
}
