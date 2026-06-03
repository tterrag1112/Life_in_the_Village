package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.BlockCategory;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.Cell;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Layout Rework Stage 3a — terrain-warped, center-out land-use
 * partition over the {@link V2FeatureMap}.
 *
 * <p>Pipeline:
 * <ol>
 *   <li><b>Cost-distance field.</b> A weighted Dijkstra from the anchor
 *       cell over <i>buildable</i> cells only (non-buildable cells are
 *       blocked). Cost to enter a cell rises with local slope and with
 *       proximity to water/forest, so "distance from centre" flows
 *       <i>around</i> water and cliffs instead of through them — the
 *       source of the warp. The field is also written onto each
 *       {@link Cell#distToAnchor()} for inspection and later placement
 *       scoring.</li>
 *   <li><b>Center-out bands → zones.</b> The {@link NucleusKind} roles
 *       the inclination actually uses (read from
 *       {@link NucleusRules#forInclination}) are ordered center-out
 *       ({@code CIVIC} core → … → {@code RURAL}/{@code RESOURCE}
 *       fringe). Reachable buildable cells are sorted by cost-distance
 *       and sliced into contiguous bands, one per role, sized by a
 *       per-role weight (fringe roles get more land) with a minimum
 *       cell floor.</li>
 *   <li><b>Feature affinity (light).</b> Because non-buildable cells are
 *       blocked and the cost field penalises water/forest proximity, the
 *       fringe naturally biases toward flat-open ground; richer
 *       RESOURCE-edge biasing is a Stage 3c placement concern.</li>
 * </ol>
 *
 * <p>Deterministic from terrain + the (already seed-derived) anchor and
 * inclination. <b>Nothing consumes this in Stage 3a</b> — it is computed,
 * stored on {@link SiteContext}, and surfaced in the layout dump. Stage
 * 3c wires placement to it.
 *
 * <p>All threshold constants are starting baselines meant for in-world
 * tuning.
 */
public final class ZonePartition {

    /** Sentinel cost for blocked / unreachable cells. */
    public static final int UNREACHED = Integer.MAX_VALUE;

    // ── Buildable predicate (shared with the rest of the planner) ──────────────
    private static final int MAX_SLOPE = 3;

    // ── Cost metric (starting baselines — tune in-world) ───────────────────────
    /** Flat-open cost to step into a cell, in cost units (≈ cellSize so
     *  unobstructed cost-distance tracks world block distance). Also the
     *  minimum possible {@link #enterCost}, so an A* heuristic of
     *  {@code chebyshevSteps · BASE_STEP_COST} stays admissible. Public
     *  so Stage 3b's block-serving router reuses the same cost model. */
    public static final int BASE_STEP_COST = 2;
    /** Added cost per unit of {@code localSlope}. */
    private static final int SLOPE_WEIGHT = 2;
    /** Within this many cells of water, entering costs extra (steer
     *  around rivers/lakes). */
    private static final int WATER_NEAR_CELLS = 3;
    private static final int WATER_WEIGHT = 6;
    /** Within this many cells of forest, entering costs extra. */
    private static final int FOREST_NEAR_CELLS = 2;
    private static final int FOREST_WEIGHT = 3;

    // ── Band sizing (starting baselines) ───────────────────────────────────────
    /** Base minimum buildable cells a zone band is widened to, when the
     *  site has enough cells to honour it. Scaled up by tier so larger
     *  villages get larger minimum zones. */
    private static final int MIN_ZONE_CELLS_BASE = 8;

    /** Stage 3 fix-up #3 / fix-up #5 — the zoned region is bounded to
     *  {@code VillageExtent.radiusFor(tier) × zoneRadiusFactor(tier)} from
     *  the anchor (clamped to the scan grid). Cells beyond stay unzoned
     *  ({@code -1}): available for farm fields + outward expansion, but no
     *  buildings place there, so the settlement is compact and farm-field
     *  seeds land inside the scan grid. <b>The key compactness tuning knob.</b>
     *
     *  <p>TOWN/HAMLET/OUTPOST: 1.25× — a little room past the bare tier
     *  radius for the dense civic core while cutting the ~3× sprawl.
     *
     *  <p>Fix-up #5 — CITY: 0.8×. With the 1.25× factor, CITY (radius 80)
     *  capped at 100 → clamped to the ~96 scan grid → effectively
     *  UNCOMPACTED, so CITY still sprawled to the corners and farm seeds
     *  fell off-grid. 0.8× → cap 64 (< grid), so CITY farmhouses cluster and
     *  leave a ~32-block fringe for fields. (CITY 64 is still > TOWN 50, so
     *  CITY remains the larger tier.)
     *
     *  <p>Fix-up #7 — CITY: 1.0625× → cap {@code round(80 × 1.0625) = 85}
     *  (still &lt; the 100-block scan grid, so farm seeds stay on-grid). The
     *  0.8× cap (64) was starving CITY: the footprint-sized civic precinct
     *  (~98×78) ate most of a radius-64 extent, leaving the ~30 peripheral
     *  buildings (houses, stockpiles, a second blacksmith, peripheral farms)
     *  nowhere to go (22 drops). 85 gives the periphery room while keeping a
     *  ~15-block fringe inside the grid for fields. TOWN/HAMLET unchanged
     *  (1.25×). Baseline; raise toward the grid if CITY still drops. */
    private static double zoneRadiusFactor(ViabilityTier tier) {
        return tier == ViabilityTier.CITY ? 1.0625 : 1.25;
    }

    private final int gridSize;
    private final int cellSize;
    private final int anchorI;
    private final int anchorJ;
    private final int[][] costField;   // [i][j] cost-distance, UNREACHED if blocked/unreached
    private final int[][] zoneIdGrid;  // [i][j] zone id, -1 if unzoned
    private final List<Zone> zones;    // center-out order, id == index

    private ZonePartition(int gridSize, int cellSize, int anchorI, int anchorJ,
                          int[][] costField, int[][] zoneIdGrid, List<Zone> zones) {
        this.gridSize = gridSize;
        this.cellSize = cellSize;
        this.anchorI = anchorI;
        this.anchorJ = anchorJ;
        this.costField = costField;
        this.zoneIdGrid = zoneIdGrid;
        this.zones = List.copyOf(zones);
    }

    // =========================================================================
    // Compute
    // =========================================================================

    /**
     * Compute the partition. Writes the cost-distance field onto the
     * FeatureMap's cells as a side effect ({@link Cell#distToAnchor()}).
     *
     * @param fmap   the scanned terrain map
     * @param anchor the (adjusted) village anchor in world coords
     * @param tier   village size class — scales the per-zone cell floor
     *               (bigger villages get bigger minimum zones)
     * @param rules  the inclination's nucleus rules; its affinities'
     *               target kinds define which zone roles are present. The
     *               inclination itself isn't taken separately — it is
     *               already encoded in {@code rules} via
     *               {@link NucleusRules#forInclination}.
     */
    public static ZonePartition compute(V2FeatureMap fmap, BlockPos anchor,
                                        ViabilityTier tier, NucleusRules rules) {
        int g = fmap.gridSize();
        int cellSize = fmap.cellSize();
        int ai = fmap.worldXToCellI(anchor.getX());
        int aj = fmap.worldZToCellJ(anchor.getZ());

        int[][] cost = costDistance(fmap, g, ai, aj);
        fmap.setDistToAnchorField(cost);

        List<NucleusKind> rolesCenterOut = presentRolesCenterOut(rules);

        // Stage 3 fix-up #3 — bound the zoned region to the village radius
        // so the settlement is compact (not sprawled to the scan-grid
        // corners) and farm fields radiate into the still-open fringe.
        // Cap = VillageExtent.radiusFor(tier) × zoneRadiusFactor(tier),
        // clamped to the scan radius. Fix-up #5: the CITY factor (0.8) puts
        // the cap below the grid so CITY actually compacts.
        int radiusCap = Math.min(
                (int) Math.round(VillageExtent.radiusFor(tier) * zoneRadiusFactor(tier)),
                fmap.radius());
        long radiusCapSq = (long) radiusCap * radiusCap;

        // Reachable buildable cells within the village radius, ascending
        // cost-distance. Cells beyond the cap stay unzoned (zoneId = -1).
        List<int[]> ordered = new ArrayList<>();
        for (int i = 0; i < g; i++) {
            for (int j = 0; j < g; j++) {
                if (cost[i][j] == UNREACHED) continue;
                BlockPos wp = fmap.cellWorldPos(i, j);
                long dx = wp.getX() - anchor.getX();
                long dz = wp.getZ() - anchor.getZ();
                if (dx * dx + dz * dz > radiusCapSq) continue;   // outside village radius
                ordered.add(new int[]{i, j});
            }
        }
        ordered.sort(Comparator.comparingInt(c -> cost[c[0]][c[1]]));

        int[][] zoneIdGrid = new int[g][g];
        for (int[] row : zoneIdGrid) java.util.Arrays.fill(row, -1);

        List<Zone> zones = banding(fmap, cost, ordered, rolesCenterOut, tier, zoneIdGrid);
        return new ZonePartition(g, cellSize, ai, aj, cost, zoneIdGrid, zones);
    }

    // =========================================================================
    // Step 1 — weighted cost-distance (Dijkstra-lite)
    // =========================================================================

    private static int[][] costDistance(V2FeatureMap fmap, int g, int ai, int aj) {
        int[][] dist = new int[g][g];
        for (int[] row : dist) java.util.Arrays.fill(row, UNREACHED);

        // Seed the anchor cell at 0 even if it is itself marginal, so the
        // field still emanates; traversal only enters buildable cells.
        dist[ai][aj] = 0;
        PriorityQueue<int[]> pq = new PriorityQueue<>(Comparator.comparingInt(n -> n[2]));
        pq.add(new int[]{ai, aj, 0});

        while (!pq.isEmpty()) {
            int[] node = pq.poll();
            int i = node[0], j = node[1], d = node[2];
            if (d > dist[i][j]) continue;
            for (int di = -1; di <= 1; di++) {
                for (int dj = -1; dj <= 1; dj++) {
                    if (di == 0 && dj == 0) continue;
                    int ni = i + di, nj = j + dj;
                    if (ni < 0 || nj < 0 || ni >= g || nj >= g) continue;
                    Cell nc = fmap.cell(ni, nj);
                    if (!isBuildable(nc)) continue;          // blocked
                    int nd = d + enterCost(nc);
                    if (nd < dist[ni][nj]) {
                        dist[ni][nj] = nd;
                        pq.add(new int[]{ni, nj, nd});
                    }
                }
            }
        }

        // The anchor cell itself: keep a real cost only if it is buildable;
        // otherwise it was just a seed and should read UNREACHED so it
        // isn't banded as a (non-buildable) civic cell.
        if (!isBuildable(fmap.cell(ai, aj))) dist[ai][aj] = UNREACHED;
        return dist;
    }

    /** Whether a cell can host buildings / carry roads — the shared
     *  buildable predicate ({@code OPEN/SHORE && slope ≤ MAX_SLOPE}).
     *  Public so Stage 3b's router blocks the same cells. */
    public static boolean isBuildable(Cell c) {
        BlockCategory cat = c.category();
        return (cat == BlockCategory.OPEN || cat == BlockCategory.SHORE)
                && c.localSlope() <= MAX_SLOPE;
    }

    /** Cost to enter {@code c} (slope- and water/forest-proximity
     *  weighted). Caller must ensure {@code c} is buildable. Public so
     *  Stage 3b's router routes roads with the same valley-seeking,
     *  water-avoiding terrain logic the partition uses. */
    public static int enterCost(Cell c) {
        int s = BASE_STEP_COST + SLOPE_WEIGHT * c.localSlope();
        int dw = c.distToWater();
        if (dw < WATER_NEAR_CELLS) s += (WATER_NEAR_CELLS - dw) * WATER_WEIGHT;
        int df = c.distToForest();
        if (df < FOREST_NEAR_CELLS) s += (FOREST_NEAR_CELLS - df) * FOREST_WEIGHT;
        return s;
    }

    // =========================================================================
    // Step 2 — present roles, center-out
    // =========================================================================

    /**
     * The {@link NucleusKind} roles the inclination actually uses, in
     * center-out order. CIVIC is always present (the civic nucleus is
     * never null); the rest come from the affinities' target kinds
     * (including fallbacks) plus the explicit resource/sacred/rural
     * nucleus declarations.
     */
    private static List<NucleusKind> presentRolesCenterOut(NucleusRules rules) {
        Set<NucleusKind> present = new LinkedHashSet<>();
        present.add(NucleusKind.CIVIC);
        for (NucleusAffinity aff : rules.affinities().values()) {
            present.add(aff.preferred());
            if (aff.fallback() != null) present.add(aff.fallback().preferred());
        }
        if (rules.resourceNucleus() != null) present.add(NucleusKind.RESOURCE);
        if (rules.sacredNucleus() != null) present.add(NucleusKind.SACRED);
        if (!rules.ruralNucleusTypes().isEmpty()) present.add(NucleusKind.RURAL);

        List<NucleusKind> ordered = new ArrayList<>(present);
        ordered.sort(Comparator.comparingInt(ZonePartition::centerOutRank));
        return ordered;
    }

    /** Center-out rank: lower = closer to the anchor core. Exhaustive
     *  over {@link NucleusKind}. */
    private static int centerOutRank(NucleusKind kind) {
        return switch (kind) {
            case CIVIC    -> 0;
            case SACRED   -> 1;
            case GATEWAY  -> 2;
            case RESOURCE -> 3;
            case RURAL    -> 4;
        };
    }

    /** Tier-scaled minimum cells per zone band — larger villages get
     *  larger minimum zones. Exhaustive over {@link ViabilityTier}. */
    private static int tierZoneFloor(ViabilityTier tier) {
        return switch (tier) {
            case CITY     -> MIN_ZONE_CELLS_BASE * 2;
            case TOWN     -> (MIN_ZONE_CELLS_BASE * 3) / 2;
            case HAMLET   -> MIN_ZONE_CELLS_BASE;
            case OUTPOST, UNVIABLE -> MIN_ZONE_CELLS_BASE / 2;
        };
    }

    /** Center-out band-size weight: fringe roles claim more land. */
    private static double bandWeight(NucleusKind kind) {
        return switch (kind) {
            case CIVIC    -> 1.0;
            case SACRED   -> 1.0;
            case GATEWAY  -> 1.3;
            case RESOURCE -> 1.6;
            case RURAL    -> 2.0;
        };
    }

    // =========================================================================
    // Step 3 — banding
    // =========================================================================

    private static List<Zone> banding(V2FeatureMap fmap, int[][] cost,
                                      List<int[]> ordered, List<NucleusKind> roles,
                                      ViabilityTier tier, int[][] zoneIdGrid) {
        List<Zone> zones = new ArrayList<>();
        int n = ordered.size();
        int k = roles.size();
        if (n == 0 || k == 0) return zones;

        // Per-role cell budgets, weighted, floored at the tier-scaled
        // minimum when the site is large enough to afford it.
        int minCells = tierZoneFloor(tier);
        double wsum = 0;
        for (NucleusKind kind : roles) wsum += bandWeight(kind);
        int[] target = new int[k];
        long sumT = 0;
        for (int b = 0; b < k; b++) {
            int t = (int) Math.round(bandWeight(roles.get(b)) / wsum * n);
            t = Math.max(minCells, t);
            target[b] = t;
            sumT += t;
        }
        // Trim oversubscription (small sites) from the largest bands first,
        // never below 1, so every role keeps at least a toe-hold.
        while (sumT > n) {
            int largest = 0;
            for (int b = 1; b < k; b++) if (target[b] > target[largest]) largest = b;
            if (target[largest] <= 1) break;
            target[largest]--;
            sumT--;
        }

        // Assign contiguous ascending-cost slices; the outermost band
        // absorbs any remainder.
        int idx = 0;
        int zoneId = 0;
        for (int b = 0; b < k; b++) {
            int take = (b == k - 1) ? (n - idx) : Math.min(target[b], n - idx);
            if (take <= 0) continue;

            long si = 0, sj = 0;
            int minCost = Integer.MAX_VALUE, maxCost = Integer.MIN_VALUE;
            for (int t = 0; t < take; t++) {
                int[] c = ordered.get(idx + t);
                zoneIdGrid[c[0]][c[1]] = zoneId;
                si += c[0];
                sj += c[1];
                int cd = cost[c[0]][c[1]];
                if (cd < minCost) minCost = cd;
                if (cd > maxCost) maxCost = cd;
            }
            int ci = (int) (si / take);
            int cj = (int) (sj / take);
            BlockPos centroid = fmap.cellWorldPos(ci, cj);
            zones.add(new Zone(zoneId, roles.get(b), centroid, take, minCost, maxCost));
            zoneId++;
            idx += take;
        }
        return zones;
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    public int gridSize()            { return gridSize; }
    public int cellSize()            { return cellSize; }
    public int anchorI()             { return anchorI; }
    public int anchorJ()             { return anchorJ; }
    public List<Zone> zones()        { return zones; }

    /** Zone id at cell {@code (i, j)}, or {@code -1} if unzoned
     *  (non-buildable / unreachable). */
    public int zoneIdAt(int i, int j) { return zoneIdGrid[i][j]; }

    /** Cost-distance from the anchor at cell {@code (i, j)}, or
     *  {@link #UNREACHED}. */
    public int costAt(int i, int j) { return costField[i][j]; }
}
