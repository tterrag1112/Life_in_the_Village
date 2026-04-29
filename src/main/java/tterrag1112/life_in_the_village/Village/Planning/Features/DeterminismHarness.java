package tterrag1112.life_in_the_village.Village.Planning.Features;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 4 regression harness: verifies that
 * {@link FeatureMap#buildPlanning} produces identical XZ-shape output
 * for identical inputs. Stays after Phase 4 ships — any future change
 * to {@code buildPlanning} or {@link FeatureSamplers} must keep this
 * green.
 *
 * <p>Equivalence is XZ-only: two FeatureMaps match iff their hull and
 * water/cliff polygons (in registration order) have the same XZ vertex
 * sequences after canonicalizing rotation to start at the lex-smallest
 * vertex. Y values are ignored — Phase 4's contract is "XZ
 * authoritative, Y advisory."
 */
public final class DeterminismHarness {

    private DeterminismHarness() {}

    public record HarnessResult(boolean deterministic,
                                int repetitions,
                                List<String> diffs) {}

    /**
     * Runs {@code repetitions} planning passes for the same {@code (origin,
     * worldSeed)} and asserts the results are identical. {@code
     * repetitions < 2} is trivially deterministic.
     */
    public static HarnessResult run(ServerLevel level, BlockPos origin,
                                    long worldSeed,
                                    List<VillageTypeData.StarterBuilding> remaining,
                                    int repetitions) {
        if (repetitions < 2) {
            return new HarnessResult(true, repetitions, List.of());
        }
        FeatureMap reference = FeatureMap.buildPlanning(origin, worldSeed, level, remaining);
        List<String> diffs = new ArrayList<>();
        boolean det = true;

        for (int i = 1; i < repetitions; i++) {
            FeatureMap candidate = FeatureMap.buildPlanning(origin, worldSeed, level, remaining);
            List<String> rep = compareFeatureMaps(reference, candidate, "rep" + i);
            if (!rep.isEmpty()) {
                det = false;
                for (String d : rep) {
                    if (diffs.size() >= 3) break;
                    diffs.add(d);
                }
            }
        }
        return new HarnessResult(det, repetitions, diffs);
    }

    private static List<String> compareFeatureMaps(FeatureMap a, FeatureMap b, String tag) {
        List<String> diffs = new ArrayList<>();
        if (!polygonsXZEquivalent(a.hull(), b.hull())) {
            diffs.add(tag + ": hull XZ mismatch");
        }
        if (a.waterFeatures().size() != b.waterFeatures().size()) {
            diffs.add(tag + ": water count " + a.waterFeatures().size()
                    + " != " + b.waterFeatures().size());
        } else {
            for (int i = 0; i < a.waterFeatures().size(); i++) {
                if (!polygonsXZEquivalent(a.waterFeatures().get(i).outline(),
                                          b.waterFeatures().get(i).outline())) {
                    diffs.add(tag + ": water[" + i + "] XZ mismatch");
                }
            }
        }
        if (a.cliffFeatures().size() != b.cliffFeatures().size()) {
            diffs.add(tag + ": cliff count " + a.cliffFeatures().size()
                    + " != " + b.cliffFeatures().size());
        } else {
            for (int i = 0; i < a.cliffFeatures().size(); i++) {
                if (!polygonsXZEquivalent(a.cliffFeatures().get(i).ridgeFootprint(),
                                          b.cliffFeatures().get(i).ridgeFootprint())) {
                    diffs.add(tag + ": cliff[" + i + "] XZ mismatch");
                }
            }
        }
        return diffs;
    }

    private static boolean polygonsXZEquivalent(PolygonXZ a, PolygonXZ b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        long[][] aXZ = canonicalXZSequence(a);
        long[][] bXZ = canonicalXZSequence(b);
        if (aXZ.length != bXZ.length) return false;
        for (int i = 0; i < aXZ.length; i++) {
            if (aXZ[i][0] != bXZ[i][0] || aXZ[i][1] != bXZ[i][1]) return false;
        }
        return true;
    }

    /**
     * Canonicalizes a polygon's XZ vertex sequence: rotates so the
     * lex-smallest (min X, then min Z) vertex appears first. Vertex
     * direction is preserved.
     */
    private static long[][] canonicalXZSequence(PolygonXZ p) {
        int n = p.vertices().size();
        long[][] xz = new long[n][2];
        for (int i = 0; i < n; i++) {
            BlockPos v = p.vertices().get(i);
            xz[i][0] = v.getX();
            xz[i][1] = v.getZ();
        }
        int min = 0;
        for (int i = 1; i < n; i++) {
            if (xz[i][0] < xz[min][0]
                    || (xz[i][0] == xz[min][0] && xz[i][1] < xz[min][1])) {
                min = i;
            }
        }
        long[][] out = new long[n][2];
        for (int i = 0; i < n; i++) {
            out[i] = xz[(min + i) % n];
        }
        return out;
    }
}
