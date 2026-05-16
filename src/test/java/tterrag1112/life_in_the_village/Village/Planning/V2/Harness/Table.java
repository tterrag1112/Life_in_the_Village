package tterrag1112.life_in_the_village.Village.Planning.V2.Harness;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DropReason;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Track E1 — renders a tabular summary of one battery's metrics, with
 * an inline diff vs the committed baseline. Designed to be pasted into
 * chat verbatim; columns are fixed-width, deltas are signed and
 * decorated so regressions stand out.
 *
 * <p>One row per (terrain, config). A column-key line precedes the
 * table. Summary lines follow with overall means and the diff status.
 *
 * <p>Non-gating metrics print in every run (pass or fail) so the
 * non-regression deltas — compactness, frontage, terrain violence,
 * clustering coherence — stay visible turn over turn.
 */
public final class Table {

    private Table() {}

    public static String render(List<RunMetrics> current,
                                List<RunMetrics> baseline,
                                Baseline.DiffResult diff) {
        Map<String, RunMetrics> baseByKey = new LinkedHashMap<>();
        if (baseline != null) {
            for (RunMetrics b : baseline) {
                baseByKey.put(b.terrain() + "/" + b.configLabel(), b);
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("\n=== HEADLESS LAYOUT HARNESS — BATTERY RESULT ===\n");
        out.append(String.format("%-14s %-12s %-6s %-7s %-7s %-7s %-7s %-7s %-5s %-7s %-7s %s%n",
                "TERRAIN", "CONFIG", "seed", "plc/req", "rate", "HOUSE%", "cmpct",
                "frUtil", "comp", "onMain", "tViol", "topDrop"));

        for (RunMetrics r : current) {
            String key = r.terrain() + "/" + r.configLabel();
            RunMetrics b = baseByKey.get(key);
            out.append(String.format(
                    "%-14s %-12s %-6d %-7s %-7s %-7s %-7s %-7s %-5s %-7s %-7s %s%n",
                    r.terrain(),
                    r.configLabel(),
                    r.seed(),
                    intDelta(r.placed() + "/" + r.requested(),
                            b == null ? null : (b.placed() + "/" + b.requested())),
                    fmtDelta(r.placedRate(), b == null ? Double.NaN : b.placedRate(), 0.01),
                    fmtDelta(rate(r, BuildingType.HOUSE),
                            b == null ? Double.NaN : rate(b, BuildingType.HOUSE), 0.01),
                    fmt(r.compactness()),
                    fmt(r.fracBuildingsOnNetwork()),
                    intStr(r.networkComponents()),
                    fmt(r.fracBuildingsOnMainComponent()),
                    fmt(r.terrainViolence()),
                    topDrop(r.dropHistogram())));
        }

        // Summary line.
        double meanPlaced = 0.0;
        double meanHouse = 0.0;
        int nPlaced = 0, nHouse = 0;
        for (RunMetrics r : current) {
            if (!r.aborted()) { meanPlaced += r.placedRate(); nPlaced++; }
            Double h = r.placedRatePerType().get(BuildingType.HOUSE);
            if (h != null) { meanHouse += h; nHouse++; }
        }
        out.append(String.format("SUMMARY  mean placed-rate=%.3f  mean HOUSE%%=%.3f  runs=%d%n",
                nPlaced == 0 ? 0.0 : meanPlaced / nPlaced,
                nHouse == 0 ? 0.0 : meanHouse / nHouse,
                current.size()));

        // Diff status.
        if (baseline == null) {
            out.append("DIFF: NO BASELINE (record mode)\n");
        } else if (diff.passed()) {
            out.append("DIFF vs baseline: PASS (no gated regressions)\n");
        } else {
            out.append("DIFF vs baseline: FAIL — ").append(diff.failures().size())
                    .append(" gated regression(s)\n");
            for (Baseline.Failure f : diff.failures()) {
                out.append(String.format(
                        "  [%s] %s: %.4f -> %.4f (delta %+.4f)%n",
                        f.runLabel(), f.metric(), f.baseline(), f.current(), f.delta()));
            }
        }
        return out.toString();
    }

    private static double rate(RunMetrics r, BuildingType t) {
        return r.placedRatePerType().getOrDefault(t, Double.NaN);
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "-" : String.format("%.2f", v);
    }

    private static String intStr(int v) { return Integer.toString(v); }

    private static String fmtDelta(double cur, double base, double epsilon) {
        String curS = fmt(cur);
        if (Double.isNaN(base) || Double.isNaN(cur)) return curS;
        double d = cur - base;
        if (Math.abs(d) < epsilon) return curS;
        char marker = d < 0 ? '-' : '+';
        return curS + "(" + marker + String.format("%.2f", Math.abs(d)) + ")";
    }

    private static String intDelta(String cur, String base) {
        if (base == null || base.equals(cur)) return cur;
        return cur + "(was " + base + ")";
    }

    private static String topDrop(Map<DropReason, Integer> dh) {
        DropReason best = null;
        int bestN = 0;
        for (Map.Entry<DropReason, Integer> e : dh.entrySet()) {
            if (e.getValue() > bestN) { bestN = e.getValue(); best = e.getKey(); }
        }
        if (best == null) return "-";
        return abbr(best) + ":" + bestN;
    }

    private static String abbr(DropReason r) {
        return switch (r) {
            case NOT_SELECTED -> "notsel";
            case DEPENDENCY_MISSING -> "depmis";
            case NO_VIABLE_CANDIDATE -> "novia";
            case REQUIRED_FAILED -> "reqfail";
            case ISOLATED_AFTER_REASSESS -> "isol";
            case TERRAIN_UNADAPTABLE -> "terra";
        };
    }
}
