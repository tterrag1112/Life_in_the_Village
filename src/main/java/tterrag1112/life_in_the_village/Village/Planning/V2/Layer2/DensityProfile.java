package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;

import java.util.Arrays;

/**
 * City-morphology step 2a — the density gradient as a REAL OBJECT (design
 * doc {@code 11-CITY-MORPHOLOGY-DESIGN.md} §1): one per-village profile
 * mapping the terrain-warped cost-distance field
 * ({@code Cell#distToAnchor()}, ZonePartition's Stage-3a weighted Dijkstra)
 * to concentric density zones. Everything that needs "how urban is it
 * here?" reads this — road formality ({@code RoadFormality}), core
 * rectilinear routing ({@code BlockServingRouter}), residential variant
 * selection ({@code PhasedPlanner.chooseVariant}).
 *
 * <p><b>NOT Euclidean radius</b> (Garrett's §1 ruling): real terrain makes
 * villages linear/irregular, so zone boundaries are <b>area budgets</b>
 * accumulated over the cost-distance field in nearest-first order — CORE is
 * the nearest reachable-buildable cells totaling {@code A(tier)} blocks² of
 * area, MIDTOWN the next {@code B(tier)}, OUTSKIRTS the next
 * {@code C(tier)}, RURAL everything beyond (including unreached / blocked /
 * out-of-scan cells). Circles on flat ground, bands along a valley, no
 * special cases. A tier with a zero budget simply has no such zone
 * (HAMLET/OUTPOST start at OUTSKIRTS); a site smaller than its budgets
 * truncates the outer zones honestly.
 *
 * <p><b>Replaces the v1 formality proxy.</b> Step 1's
 * {@code RoadFormality.FORMAL_MAX_COST}/{@code MIXED_MAX_COST} fixed
 * thresholds (&lt;50/&lt;100 cost units) are deleted; the CITY budgets
 * below are calibrated so the flat-ground boundaries land at the same
 * cost-distances (behavior-comparable on a flat CITY), while warped sites
 * now get capacity-true zones instead of cost-radius circles.
 *
 * <p>Deterministic and cheap (one sort of the reachable cells), so it is
 * computed independently where needed — {@code PhasedPlanner.State} for
 * planning, {@code V2VillageSpawnerAdapter} for the realizer — and the two
 * instances always agree. The profile holds the {@link V2FeatureMap} it
 * was built from for position lookups.
 */
public final class DensityProfile {

    private static final Logger LOGGER = LoggerFactory.getLogger(DensityProfile.class);

    /** The concentric density zones, centre-out. */
    public enum DensityZone { CORE, MIDTOWN, OUTSKIRTS, RURAL }

    // ── Area budgets per tier (blocks²) — TUNING BASELINES ────────────────────
    //
    // {CORE, MIDTOWN, OUTSKIRTS}; RURAL = everything beyond. CITY values are
    // calibrated to the v1 formality proxy on flat ground: cost-distance ≈
    // block distance when unobstructed, so
    //   CORE      ≈ π·50²          ≈  7 900 → boundary ≈ cost 50 (v1 FORMAL)
    //   MIDTOWN   ≈ π·(100²−50²)   ≈ 23 600 → boundary ≈ cost 100 (v1 MIXED)
    //   OUTSKIRTS ≈ π·(132²−100²)  ≈ 23 300 → boundary ≈ cost 132
    //                                          (= VillageExtent CITY radius)
    // TOWN gets a small core (~r25) + midtown (~r47); HAMLET and OUTPOST have
    // no CORE/MIDTOWN (they start at OUTSKIRTS, §1 tier truncation); UNVIABLE
    // is all RURAL. On real terrain the same budgets stretch along valleys —
    // that is the point.
    private static long[] areaBudgets(ViabilityTier tier) {
        return switch (tier) {
            case CITY     -> new long[]{7_900, 23_600, 23_300};
            case TOWN     -> new long[]{2_000,  5_000, 12_700};
            case HAMLET   -> new long[]{    0,      0,  5_000};
            case OUTPOST  -> new long[]{    0,      0,  2_000};
            case UNVIABLE -> new long[]{    0,      0,      0};
        };
    }

    private final V2FeatureMap fmap;
    private final ViabilityTier tier;
    /** Exclusive upper cost-distance bound per zone: cost &lt; bound[0] is
     *  CORE, &lt; bound[1] MIDTOWN, &lt; bound[2] OUTSKIRTS, else RURAL.
     *  Monotone non-decreasing; equal adjacent bounds mean an empty zone. */
    private final int[] bounds;

    private DensityProfile(V2FeatureMap fmap, ViabilityTier tier, int[] bounds) {
        this.fmap = fmap;
        this.tier = tier;
        this.bounds = bounds;
    }

    /**
     * Build the profile from the populated cost-distance field. Requires
     * {@code ZonePartition.compute} to have run (it writes the field onto
     * the cells); an unpopulated field (all {@code UNREACHED}, e.g. a
     * harness map that skipped site analysis) degenerates to all-RURAL,
     * which downstream reads as today's organic look.
     */
    public static DensityProfile of(V2FeatureMap fmap, ViabilityTier tier) {
        long[] budgets = areaBudgets(tier);
        long cellArea = (long) fmap.cellSize() * fmap.cellSize();
        int g = fmap.gridSize();

        // Reachable cells' cost-distances, nearest-first.
        int[] costs = new int[g * g];
        int n = 0;
        for (int i = 0; i < g; i++) {
            for (int j = 0; j < g; j++) {
                int d = fmap.cell(i, j).distToAnchor();
                if (d != ZonePartition.UNREACHED) costs[n++] = d;
            }
        }
        Arrays.sort(costs, 0, n);

        // Accumulate area nearest-first; each zone's exclusive cost bound is
        // set where its cumulative budget fills. Zero budgets resolve before
        // the first cell (empty zone); budgets larger than the site resolve
        // after the last cell (the zone absorbs the remainder, outer zones
        // are empty — the honest small-site truncation).
        long[] cumBudget = {budgets[0], budgets[0] + budgets[1],
                budgets[0] + budgets[1] + budgets[2]};
        int[] bounds = new int[3];
        int b = 0;
        long cum = 0;
        while (b < 3 && cumBudget[b] == 0) bounds[b++] = 0;
        for (int k = 0; k < n && b < 3; k++) {
            cum += cellArea;
            while (b < 3 && cum >= cumBudget[b]) bounds[b++] = costs[k] + 1;
        }
        while (b < 3) bounds[b++] = n == 0 ? 0 : costs[n - 1] + 1;

        DensityProfile profile = new DensityProfile(fmap, tier, bounds);
        LOGGER.info("density profile ({}): budgets {}/{}/{} blocks² → cost bounds"
                + " core<{} midtown<{} outskirts<{} ({} reachable cells, {} blocks²)",
                tier, budgets[0], budgets[1], budgets[2],
                bounds[0], bounds[1], bounds[2], n, n * cellArea);
        return profile;
    }

    /** Zone for a cost-distance value ({@code Cell#distToAnchor()} units). */
    public DensityZone zoneAtDistance(int cost) {
        if (cost < bounds[0]) return DensityZone.CORE;
        if (cost < bounds[1]) return DensityZone.MIDTOWN;
        if (cost < bounds[2]) return DensityZone.OUTSKIRTS;
        return DensityZone.RURAL;
    }

    /** Zone at a world position — RURAL outside the scan grid and on
     *  blocked/unreached cells (water, cliffs): the conservative "not
     *  urban" answer every consumer wants for missing data. */
    public DensityZone zoneAt(int worldX, int worldZ) {
        if (!fmap.inBounds(worldX, worldZ)) return DensityZone.RURAL;
        int d = fmap.cellAt(worldX, worldZ).distToAnchor();
        if (d == ZonePartition.UNREACHED) return DensityZone.RURAL;
        return zoneAtDistance(d);
    }

    /** Zone at a world position. */
    public DensityZone zoneAt(BlockPos pos) {
        return zoneAt(pos.getX(), pos.getZ());
    }

    /** Exclusive cost-distance upper bound of CORE (consumers that
     *  threshold on the raw field). */
    public int coreMaxCost() { return bounds[0]; }

    /** Exclusive cost-distance upper bound of MIDTOWN. */
    public int midtownMaxCost() { return bounds[1]; }

    /** Exclusive cost-distance upper bound of OUTSKIRTS. */
    public int outskirtsMaxCost() { return bounds[2]; }

    public ViabilityTier tier() { return tier; }
}
