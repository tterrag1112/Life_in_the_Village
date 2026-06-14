package tterrag1112.life_in_the_village.Village.Planning.V2.Harness;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import tterrag1112.life_in_the_village.Kingdom.Settlement.VillagePlacement;
import tterrag1112.life_in_the_village.Kingdom.Settlement.VillagePlacement.DigestSample;
import tterrag1112.life_in_the_village.Kingdom.Settlement.VillagePlacement.VillageCandidate;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Track V1 -- proves the deterministic placement primitive
 * {@link VillagePlacement}. Pure, headless, no Minecraft bootstrap needed:
 * the primitive's seam takes an injected digest sampler, so the math is
 * tested in isolation with synthetic digests (BlockPos / BiomeCategory /
 * VillageSizeTier are plain types -- no registry).
 *
 * <p>Determinism is the correctness property of the whole village-first
 * iteration (doc 16 sec.3), so these assertions are the proof the primitive
 * works while Garrett is away. Coverage:
 * <ul>
 *   <li><b>determinism</b> -- same (region, seed) gives an identical candidate
 *       across repeated and out-of-order calls;</li>
 *   <li><b>density</b> -- a region-area grid yields the expected count for the
 *       chosen spacing (lenient gate accepts buildable land);</li>
 *   <li><b>separation</b> -- no two candidates fall closer than SEPARATION
 *       chunks;</li>
 *   <li><b>lenient gate</b> -- ocean / void / no-land rejected, everything else
 *       (including steep, swamp, desert, coast) accepted.</li>
 * </ul>
 */
public class VillagePlacementTest {

    private static final long SEED = 0xC0FFEE_1234L;

    /** A digest sampler that reports buildable plains everywhere. */
    private static LongFunction<DigestSample> allLand() {
        return key -> new DigestSample(BiomeCategory.PLAINS, 4, 72, false, false);
    }

    /** A digest sampler that reports open ocean everywhere. */
    private static LongFunction<DigestSample> allOcean() {
        return key -> new DigestSample(BiomeCategory.OCEAN, 0, 40, false, false);
    }

    // =========================================================================
    // Determinism
    // =========================================================================

    @Test
    public void sameRegionSeedYieldsIdenticalCandidate() {
        LongFunction<DigestSample> land = allLand();
        for (int rx = -3; rx <= 3; rx++) {
            for (int rz = -3; rz <= 3; rz++) {
                Optional<VillageCandidate> a =
                        VillagePlacement.evaluateRegion(rx, rz, SEED, land);
                Optional<VillageCandidate> b =
                        VillagePlacement.evaluateRegion(rx, rz, SEED, land);
                assertEquals(a, b, "region (" + rx + "," + rz + ") not deterministic");
                assertTrue(a.isPresent(), "buildable land must yield a candidate");
                // Exact field equality (records use value equality, but assert
                // the load-bearing fields explicitly for a clearer failure).
                assertEquals(a.get().pos(), b.get().pos());
                assertEquals(a.get().cellKey(), b.get().cellKey());
                assertEquals(a.get().role(), b.get().role());
                assertEquals(a.get().sizeBand(), b.get().sizeBand());
            }
        }
    }

    @Test
    public void evaluationIsOrderIndependent() {
        LongFunction<DigestSample> land = allLand();
        // Forward sweep.
        Map<String, BlockPos> forward = new HashMap<>();
        for (int rx = 0; rx < 5; rx++)
            for (int rz = 0; rz < 5; rz++)
                VillagePlacement.evaluateRegion(rx, rz, SEED, land)
                        .ifPresent(c -> forward.put(rx(c), c.pos()));
        // Reverse sweep -- must match the forward result region for region.
        Map<String, BlockPos> reverse = new HashMap<>();
        for (int rx = 4; rx >= 0; rx--)
            for (int rz = 4; rz >= 0; rz--)
                VillagePlacement.evaluateRegion(rx, rz, SEED, land)
                        .ifPresent(c -> reverse.put(rx(c), c.pos()));
        assertEquals(forward, reverse, "placement must be order-independent");
    }

    private static String rx(VillageCandidate c) {
        return c.regionX() + ":" + c.regionZ();
    }

    @Test
    public void differentSeedsRelocate() {
        // A different world seed should (almost surely) move at least some
        // candidates -- confirms the seed actually drives the spread.
        LongFunction<DigestSample> land = allLand();
        int moved = 0;
        for (int rx = 0; rx < 8; rx++) {
            for (int rz = 0; rz < 8; rz++) {
                BlockPos a = VillagePlacement.evaluateRegion(rx, rz, SEED, land)
                        .get().pos();
                BlockPos b = VillagePlacement.evaluateRegion(rx, rz, SEED ^ 0xABCD, land)
                        .get().pos();
                if (!a.equals(b)) moved++;
            }
        }
        assertTrue(moved > 0, "a different seed must reshuffle the spread");
    }

    // =========================================================================
    // Density
    // =========================================================================

    @Test
    public void densityMatchesSpacingOverAClaimSizedArea() {
        // The lenient gate accepts all buildable land, and V1 places exactly
        // one candidate per region, so an all-land RxR region grid must yield
        // R*R candidates -- regardless of the chosen SPACING_CHUNKS. This is
        // the density invariant (one village per region on viable terrain).
        LongFunction<DigestSample> land = allLand();
        int regionsPerSide = 4;
        int count = 0;
        for (int rx = 0; rx < regionsPerSide; rx++)
            for (int rz = 0; rz < regionsPerSide; rz++)
                if (VillagePlacement.evaluateRegion(rx, rz, SEED, land).isPresent())
                    count++;
        int expected = regionsPerSide * regionsPerSide;
        assertEquals(expected, count,
                "all-land region grid must yield one candidate per region");
    }

    @Test
    public void oceanAreaYieldsNoCandidates() {
        LongFunction<DigestSample> ocean = allOcean();
        int count = 0;
        for (int rx = 0; rx < 5; rx++)
            for (int rz = 0; rz < 5; rz++)
                if (VillagePlacement.evaluateRegion(rx, rz, SEED, ocean).isPresent())
                    count++;
        assertEquals(0, count, "open-ocean area must place no villages");
    }

    // =========================================================================
    // Separation
    // =========================================================================

    @Test
    public void noTwoCandidatesCloserThanSeparation() {
        LongFunction<DigestSample> land = allLand();
        List<BlockPos> placed = new ArrayList<>();
        for (int rx = -6; rx <= 6; rx++)
            for (int rz = -6; rz <= 6; rz++)
                VillagePlacement.evaluateRegion(rx, rz, SEED, land)
                        .ifPresent(c -> placed.add(c.pos()));

        // Minimum allowed gap: SEPARATION_CHUNKS chunks along an axis. The
        // vanilla bound guarantees adjacent-region candidates differ by at
        // least SEPARATION chunks on whichever axis the regions are adjacent;
        // assert the Chebyshev (max-axis) distance never drops below it.
        int minBlocks = VillagePlacement.SEPARATION_CHUNKS * 16;
        for (int i = 0; i < placed.size(); i++) {
            for (int j = i + 1; j < placed.size(); j++) {
                BlockPos a = placed.get(i), b = placed.get(j);
                int dx = Math.abs(a.getX() - b.getX());
                int dz = Math.abs(a.getZ() - b.getZ());
                int cheb = Math.max(dx, dz);
                assertTrue(cheb >= minBlocks,
                        "candidates " + a.toShortString() + " and "
                                + b.toShortString() + " are " + cheb
                                + " blocks apart (axis), under the "
                                + minBlocks + "-block separation floor");
            }
        }
        assertFalse(placed.isEmpty(), "expected candidates on all-land");
    }

    // =========================================================================
    // Lenient gate
    // =========================================================================

    @Test
    public void gateRejectsOnlyImpossibleTerrain() {
        // Accept: plains, steep mountain, swamp, desert, coast, river.
        assertAccepted(new DigestSample(BiomeCategory.PLAINS, 2, 70, false, false), "plains");
        assertAccepted(new DigestSample(BiomeCategory.MOUNTAIN, 40, 120, false, false), "steep mountain");
        assertAccepted(new DigestSample(BiomeCategory.SWAMP, 1, 63, true, false), "swamp");
        assertAccepted(new DigestSample(BiomeCategory.DESERT, 3, 75, false, false), "desert");
        assertAccepted(new DigestSample(BiomeCategory.BEACH, 2, 64, false, true), "coast/beach");
        assertAccepted(new DigestSample(BiomeCategory.RIVER, 1, 62, true, false), "river");

        // Reject: open ocean, void, and degenerate no-land columns.
        assertRejected(new DigestSample(BiomeCategory.OCEAN, 0, 40, false, false), "ocean");
        assertRejected(new DigestSample(BiomeCategory.VOID, 0, 0, false, false), "void");
        assertRejected(new DigestSample(BiomeCategory.PLAINS, 0, 0, false, false), "no-land (y<=0)");
    }

    private static void assertAccepted(DigestSample digest, String label) {
        Optional<VillageCandidate> c =
                VillagePlacement.evaluateRegion(0, 0, SEED, key -> digest);
        assertTrue(c.isPresent(), "lenient gate must ACCEPT " + label);
        assertNotNull(c.get().role());
        assertNotNull(c.get().sizeBand());
    }

    private static void assertRejected(DigestSample digest, String label) {
        Optional<VillageCandidate> c =
                VillagePlacement.evaluateRegion(0, 0, SEED, key -> digest);
        assertTrue(c.isEmpty(), "gate must REJECT " + label);
    }

    // =========================================================================
    // Candidate lands in the cell it reports (cellKey integrity)
    // =========================================================================

    @Test
    public void candidateCellKeyMatchesItsBlockPos() {
        LongFunction<DigestSample> land = allLand();
        for (int rx = -2; rx <= 2; rx++) {
            for (int rz = -2; rz <= 2; rz++) {
                VillageCandidate c =
                        VillagePlacement.evaluateRegion(rx, rz, SEED, land).get();
                long expected = AtlasCell.packKey(
                        c.pos().getX() >> AtlasCell.CELL_SHIFT,
                        c.pos().getZ() >> AtlasCell.CELL_SHIFT);
                assertEquals(expected, c.cellKey(),
                        "cellKey must match the candidate's block position");
            }
        }
    }

    // =========================================================================
    // Track V5 -- frontier placement (deterministic subset + absorption)
    // =========================================================================

    /**
     * The frontier subset rule is a pure function of (rx, rz) and the
     * compile-time multiplier: a region is frontier iff floorMod(rx,M)==0 &&
     * floorMod(rz,M)==0. Symmetric across the origin (floorMod, not %), so
     * negative regions follow the same rule -- order-independent everywhere.
     */
    @Test
    public void frontierSubsetIsTheEveryMthRegionRule() {
        int m = VillagePlacement.FRONTIER_SPACING_MULTIPLIER;
        org.junit.jupiter.api.Assertions.assertTrue(m >= 1, "multiplier must be >= 1");
        for (int rx = -2 * m; rx <= 2 * m; rx++) {
            for (int rz = -2 * m; rz <= 2 * m; rz++) {
                boolean expected = (m <= 1)
                        || (Math.floorMod(rx, m) == 0 && Math.floorMod(rz, m) == 0);
                assertEquals(expected, VillagePlacement.isFrontierRegion(rx, rz),
                        "frontier subset rule wrong at (" + rx + "," + rz + ")");
            }
        }
    }

    /**
     * Frontier is SPARSER than the kingdom grid: over an all-land area, the
     * frontier candidate count is 1/M^2 of the dense (kingdom) count
     * (the subset density invariant Garrett wants).
     */
    @Test
    public void frontierIsSparserThanKingdomGrid() {
        LongFunction<DigestSample> land = allLand();
        int m = VillagePlacement.FRONTIER_SPACING_MULTIPLIER;
        // Use a span that's a clean multiple of M so the ratio is exact.
        int side = 6 * m;
        int dense = 0, frontier = 0;
        for (int rx = 0; rx < side; rx++) {
            for (int rz = 0; rz < side; rz++) {
                if (VillagePlacement.evaluateRegion(rx, rz, SEED, land).isPresent())
                    dense++;
                if (VillagePlacement.evaluateFrontierRegion(rx, rz, SEED, land).isPresent())
                    frontier++;
            }
        }
        assertEquals(side * side, dense, "dense grid: one per region on all-land");
        // Frontier regions on [0,side) with floorMod==0: indices 0,M,2M,... =>
        // ceil(side/M) per axis = side/M (side is a multiple of M).
        int perAxis = side / m;
        assertEquals(perAxis * perAxis, frontier,
                "frontier must be exactly 1/M^2 of the dense grid");
        assertTrue(frontier < dense, "frontier must be sparser than kingdoms");
    }

    /**
     * ABSORPTION property: the frontier candidate at a frontier region is the
     * IDENTICAL candidate the kingdom (dense) grid enumerates there -- same
     * pos, same cell, same role, same band. This is what makes a claimed-later
     * frontier village absorb with NO reposition and NO duplicate.
     */
    @Test
    public void frontierCandidateIsIdenticalToDenseCandidate() {
        LongFunction<DigestSample> land = allLand();
        int m = VillagePlacement.FRONTIER_SPACING_MULTIPLIER;
        int checked = 0;
        for (int rx = -m; rx <= 3 * m; rx += m) {
            for (int rz = -m; rz <= 3 * m; rz += m) {
                // (rx, rz) are multiples of m -> guaranteed frontier regions.
                assertTrue(VillagePlacement.isFrontierRegion(rx, rz));
                Optional<VillageCandidate> dense =
                        VillagePlacement.evaluateRegion(rx, rz, SEED, land);
                Optional<VillageCandidate> front =
                        VillagePlacement.evaluateFrontierRegion(rx, rz, SEED, land);
                assertEquals(dense, front,
                        "frontier candidate must equal the dense candidate at ("
                                + rx + "," + rz + ") -- absorption identity");
                checked++;
            }
        }
        assertTrue(checked > 0, "expected frontier regions to check");
    }

    /** Non-frontier regions place NO frontier village even on buildable land. */
    @Test
    public void nonFrontierRegionsPlaceNoFrontierVillage() {
        LongFunction<DigestSample> land = allLand();
        int m = VillagePlacement.FRONTIER_SPACING_MULTIPLIER;
        if (m <= 1) return; // every region is frontier; nothing to assert
        // (1, 0) is non-frontier for any M > 1.
        assertFalse(VillagePlacement.isFrontierRegion(1, 0));
        assertTrue(VillagePlacement.evaluateFrontierRegion(1, 0, SEED, land).isEmpty(),
                "non-frontier region must not place a frontier village");
        // ...but the dense grid still would place one there.
        assertTrue(VillagePlacement.evaluateRegion(1, 0, SEED, land).isPresent(),
                "dense grid still places at a non-frontier region");
    }

    /** Frontier placement is order-independent (forward == reverse sweep). */
    @Test
    public void frontierIsOrderIndependent() {
        LongFunction<DigestSample> land = allLand();
        Map<String, BlockPos> forward = new HashMap<>();
        for (int rx = -4; rx <= 4; rx++)
            for (int rz = -4; rz <= 4; rz++)
                VillagePlacement.evaluateFrontierRegion(rx, rz, SEED, land)
                        .ifPresent(c -> forward.put(rx(c), c.pos()));
        Map<String, BlockPos> reverse = new HashMap<>();
        for (int rx = 4; rx >= -4; rx--)
            for (int rz = 4; rz >= -4; rz--)
                VillagePlacement.evaluateFrontierRegion(rx, rz, SEED, land)
                        .ifPresent(c -> reverse.put(rx(c), c.pos()));
        assertEquals(forward, reverse, "frontier placement must be order-independent");
    }
}
