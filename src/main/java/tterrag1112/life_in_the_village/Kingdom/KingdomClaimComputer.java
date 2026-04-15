// src/main/java/tterrag1112/life_in_the_village/Kingdom/KingdomClaimComputer.java
package tterrag1112.life_in_the_village.Kingdom;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;
import tterrag1112.life_in_the_village.World.Atlas.Regions.AtlasRegion;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.*;

/**
 * Computes a {@link KingdomClaim} by Dijkstra expansion over atlas cells.
 *
 * <h3>Algorithm</h3>
 * Standard Dijkstra from the origin cell:
 * <ul>
 *   <li>Frontier is a priority queue ordered by cumulative cost to
 *       reach the cell.</li>
 *   <li>Each expansion visits the 4 orthogonal neighbours and computes
 *       {@code newCost = currentCost + totalCost(neighbour)}.</li>
 *   <li>Cells are claimed in order until total cost reaches
 *       {@code budget}.</li>
 *   <li>Impassable cells (ocean, void) are never claimed and block
 *       traversal — an island is a hard border.</li>
 * </ul>
 *
 * <h3>Budget</h3>
 * Budget is in the same units as {@link KingdomClaimCosts#totalCost}.
 * With base costs around 1–6, a budget of 180 claims roughly 30–180
 * cells depending on terrain — 30 in rough country, 180 in open plains.
 * The {@link #DEFAULT_BUDGET} value is tuned for a mid-sized kingdom;
 * per-culture scaling will adjust this in a later slice.
 *
 * <h3>Assumptions</h3>
 * The atlas should already be filled around the origin out to at least
 * {@code sqrt(budget) * 64 * 1.5} blocks before calling. Unfilled cells
 * are treated as moderate cost (5.0×) so expansion doesn't silently
 * stop at the atlas boundary, but the resulting claim shape will be
 * poor — callers should pre-fill generously.
 */
public final class KingdomClaimComputer {

    private KingdomClaimComputer() {}

    /** Default budget — claims roughly 60–120 cells on typical terrain. */
    public static final float DEFAULT_BUDGET = 1800f;

    /** Hard cap on cells to claim regardless of budget. Sanity bound. */
    private static final int MAX_CELLS = 6000;

    /** Hard cap on Dijkstra expansions. Guards against runaway costs. */
    private static final int MAX_EXPANSIONS = 4_000;

    public static KingdomClaim compute(WorldAtlas atlas, BlockPos originBlock, float budget) {
        int ocx = WorldAtlas.blockToCell(originBlock.getX());
        int ocz = WorldAtlas.blockToCell(originBlock.getZ());
        long originKey = AtlasCell.packKey(ocx, ocz);

        // Determine home category from the origin's region (fall back to cell category)
        BiomeCategory home = BiomeCategory.PLAINS;
        AtlasRegion originRegion = atlas.getRegionAt(ocx, ocz);
        if (originRegion != null) {
            home = originRegion.category();
        } else {
            AtlasCell originCell = atlas.getCellByCoord(ocx, ocz);
            if (originCell != null) home = originCell.category();
        }

        // Dijkstra
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.cost));
        Map<Long, Float> bestCost = new HashMap<>();
        LinkedHashSet<Long> claimed = new LinkedHashSet<>();

        open.add(new Node(originKey, 0f));
        bestCost.put(originKey, 0f);

        float spent = 0f;
        int expansions = 0;

        while (!open.isEmpty() && expansions < MAX_EXPANSIONS && claimed.size() < MAX_CELLS) {
            Node cur = open.poll();
            expansions++;

            // Stale entry — we found a cheaper route to this cell after enqueueing
            Float recorded = bestCost.get(cur.key);
            if (recorded == null || cur.cost > recorded + 1e-4) continue;

            // Claim this cell. Budget check is inclusive — if the cell's cost
            // would push us over, we stop rather than claim it.
            if (cur.cost > budget) break;
            if (claimed.add(cur.key)) {
                spent = Math.max(spent, cur.cost);
            }

            int cx = AtlasCell.unpackX(cur.key);
            int cz = AtlasCell.unpackZ(cur.key);

            // 4-neighbour expansion
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : dirs) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                long nKey = AtlasCell.packKey(nx, nz);

                AtlasCell nbCell = atlas.getCellByCoord(nx, nz);
                float stepCost = KingdomClaimCosts.totalCost(nbCell, home);
                if (Float.isInfinite(stepCost)) continue;

                float newCost = cur.cost + stepCost;
                if (newCost > budget) continue;

                Float existing = bestCost.get(nKey);
                if (existing == null || newCost < existing) {
                    bestCost.put(nKey, newCost);
                    open.add(new Node(nKey, newCost));
                }
            }
        }

        return new KingdomClaim(
                originKey,
                new ArrayList<>(claimed),
                spent,
                budget,
                home.name());
    }

    private record Node(long key, float cost) {}
}