package tterrag1112.life_in_the_village.Village.Planning.V2.Harness;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DropReason;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Track E1 — baseline read / write / diff.
 *
 * <p>Per-metric regression thresholds (asymmetric — improvements
 * never fail):
 *
 * <ul>
 *   <li><b>Per-type placement rate</b> — fail if any type drops by
 *       more than {@link #PER_TYPE_RATE_DELTA} absolute vs baseline.
 *       The HOUSE-bug canary.</li>
 *   <li><b>Overall placed rate</b> — fail if it drops more than
 *       {@link #PLACED_RATE_DELTA} absolute.</li>
 *   <li><b>network_components</b> — fail if it increases at all
 *       (any fragmentation is a regression).</li>
 *   <li><b>frac_buildings_on_main_component</b> — fail if it drops
 *       more than {@link #MAIN_COMPONENT_DELTA} absolute.</li>
 * </ul>
 *
 * <p>Non-gating (recorded, diffed, printed, never fail the build):
 * compactness, frontage utilization, road coverage, terrain
 * violence, clustering coherence, drop histogram. These move
 * intentionally as the system evolves; gating them would make every
 * legitimate change a fight with the harness.
 *
 * <p><b>Undefined-metric encoding.</b> Non-finite metric values
 * (NaN / Infinity from aborted runs, single-building bbox math,
 * single-instance clustering, etc.) serialise as
 * {@link #SENTINEL_UNDEFINED} and restore to {@link Double#NaN} on
 * read. A finite {@code 0.0} — the HOUSE-bug canary among others —
 * is never sentinel'd. Diff treats undefined↔undefined as no
 * change, defined↔defined as the normal delta, and a gating metric
 * going from defined to undefined as a regression in observability
 * (it should fail).
 */
public final class Baseline {

    public static final double PER_TYPE_RATE_DELTA  = 0.10;
    public static final double PLACED_RATE_DELTA    = 0.05;
    public static final double MAIN_COMPONENT_DELTA = 0.05;

    /**
     * Sentinel that represents a non-finite metric in JSON.
     *
     * <p>NaN and Infinity are common in the baseline — they're how
     * the harness honestly represents "metric undefined for this
     * run" (compactness with ≤1 building, clustering coherence with
     * a type count < 2, geometry on aborted runs). Gson cannot
     * round-trip NaN through standard JSON, so the serializer maps
     * non-finite → this sentinel and the reader maps it back to
     * {@link Double#NaN}.
     *
     * <p><b>Why {@code -1.0}.</b> Every metric in {@link RunMetrics}
     * is non-negative (placement rates in [0,1], counts ≥ 0,
     * distances and bbox ratios ≥ 0, clustering coherence ≥ 0). A
     * negative number is therefore unambiguous: it cannot be a real
     * value. {@code -1.0} round-trips exactly through Gson and
     * compares with strict equality on read-back.
     *
     * <p><b>What this is not.</b> The sentinel is for serialization
     * only. In-memory and at the table renderer, undefined stays
     * {@link Double#NaN} (so {@code Double.isNaN} checks keep
     * working). And — critically — a real finite {@code 0.0} is
     * never sentinel'd: the HOUSE-bug canary depends on a genuine
     * near-zero placement rate surviving to the baseline and the
     * table untouched. {@link #nanSafe(double)} fires only on
     * {@link Double#isNaN} / {@link Double#isInfinite}, never on a
     * finite value.
     */
    public static final double SENTINEL_UNDEFINED = -1.0;

    private Baseline() {}

    // =========================================================================
    // IO
    // =========================================================================

    /** Bumped to 2 when the district-era metrics block was added
     *  (2026-06 harness refresh). A v1 baseline (pre-district) reads
     *  back with empty district metrics — see {@link #readDistrict}. */
    public static final int SCHEMA_VERSION = 2;

    public static void write(Path path, List<RunMetrics> runs) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("recordedAt", System.currentTimeMillis());
        root.addProperty("runCount", runs.size());
        JsonArray arr = new JsonArray();
        for (RunMetrics r : runs) arr.add(toJson(r));
        root.add("runs", arr);
        Files.createDirectories(path.getParent());
        Gson g = new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(path, g.toJson(root));
    }

    public static List<RunMetrics> read(Path path) throws Exception {
        String body = Files.readString(path);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray arr = root.getAsJsonArray("runs");
        List<RunMetrics> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            out.add(fromJson(arr.get(i).getAsJsonObject()));
        }
        return out;
    }

    private static JsonObject toJson(RunMetrics r) {
        JsonObject o = new JsonObject();
        o.addProperty("terrain", r.terrain());
        o.addProperty("config", r.configLabel());
        o.addProperty("seed", r.seed());
        o.addProperty("aborted", r.aborted());
        o.addProperty("requested", r.requested());
        o.addProperty("placed", r.placed());
        o.addProperty("dropped", r.dropped());
        o.addProperty("placedRate", r.placedRate());
        o.add("requestedPerType", typeMap(r.requestedPerType()));
        o.add("placedPerType", typeMap(r.placedPerType()));
        o.add("placedRatePerType", typeDoubleMap(r.placedRatePerType()));
        o.addProperty("compactness", nanSafe(r.compactness()));
        o.addProperty("fracBuildingsOnNetwork", nanSafe(r.fracBuildingsOnNetwork()));
        o.addProperty("roadCoverage", nanSafe(r.roadCoverage()));
        o.addProperty("networkComponents", r.networkComponents());
        o.addProperty("fracBuildingsOnMainComponent", nanSafe(r.fracBuildingsOnMainComponent()));
        o.addProperty("terrainViolence", nanSafe(r.terrainViolence()));
        o.addProperty("vegetationPerPlaced", nanSafe(r.vegetationPerPlaced()));
        o.add("clusteringCoherence", typeDoubleMap(r.clusteringCoherence()));
        JsonObject dh = new JsonObject();
        for (Map.Entry<DropReason, Integer> e : r.dropHistogram().entrySet()) {
            dh.addProperty(e.getKey().name(), e.getValue());
        }
        o.add("dropHistogram", dh);
        o.add("district", districtJson(r.district()));
        // elapsedMs deliberately omitted from the JSON — wall time
        // varies run-to-run on different hardware and would create
        // noise in the diff.
        return o;
    }

    private static RunMetrics fromJson(JsonObject o) {
        Map<BuildingType, Integer> req = readTypeIntMap(o, "requestedPerType");
        Map<BuildingType, Integer> placed = readTypeIntMap(o, "placedPerType");
        Map<BuildingType, Double> rate = readTypeDoubleMap(o, "placedRatePerType");
        Map<BuildingType, Double> clust = readTypeDoubleMap(o, "clusteringCoherence");
        Map<DropReason, Integer> dh = new EnumMap<>(DropReason.class);
        if (o.has("dropHistogram")) {
            for (Map.Entry<String, com.google.gson.JsonElement> e
                    : o.getAsJsonObject("dropHistogram").entrySet()) {
                try {
                    dh.put(DropReason.valueOf(e.getKey()), e.getValue().getAsInt());
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return new RunMetrics(
                o.get("terrain").getAsString(),
                o.get("config").getAsString(),
                o.get("seed").getAsLong(),
                o.get("aborted").getAsBoolean(),
                o.get("requested").getAsInt(),
                o.get("placed").getAsInt(),
                o.get("dropped").getAsInt(),
                o.get("placedRate").getAsDouble(),
                req, placed, rate,
                fromNanSafe(o, "compactness"),
                fromNanSafe(o, "fracBuildingsOnNetwork"),
                fromNanSafe(o, "roadCoverage"),
                o.get("networkComponents").getAsInt(),
                fromNanSafe(o, "fracBuildingsOnMainComponent"),
                fromNanSafe(o, "terrainViolence"),
                fromNanSafe(o, "vegetationPerPlaced"),
                clust, dh,
                readDistrict(o),
                0L);
    }

    private static JsonObject districtJson(RunMetrics.DistrictMetrics d) {
        JsonObject o = new JsonObject();
        o.addProperty("civicReserved", d.civicReserved());
        o.addProperty("civicArea", d.civicArea());
        o.addProperty("marketSelected", d.marketSelected());
        o.addProperty("marketReserved", d.marketReserved());
        o.addProperty("marketArea", d.marketArea());
        o.addProperty("residentialHousesRequested", d.residentialHousesRequested());
        o.addProperty("residentialPrecinctsReserved", d.residentialPrecinctsReserved());
        o.addProperty("residentialHousesPlaced", d.residentialHousesPlaced());
        o.addProperty("residentialHousesDropped", d.residentialHousesDropped());
        o.addProperty("residentialBandActive", d.residentialBandActive());
        o.addProperty("workshopCraftsRequested", d.workshopCraftsRequested());
        o.addProperty("workshopSeating", d.workshopSeating());
        o.addProperty("workshopCraftsPlaced", d.workshopCraftsPlaced());
        o.addProperty("workshopCraftsDropped", d.workshopCraftsDropped());
        return o;
    }

    /** Read the district block; absent (v1 baseline) → empty metrics. */
    private static RunMetrics.DistrictMetrics readDistrict(JsonObject root) {
        if (!root.has("district") || root.get("district").isJsonNull()) {
            return RunMetrics.DistrictMetrics.empty();
        }
        JsonObject o = root.getAsJsonObject("district");
        return new RunMetrics.DistrictMetrics(
                bool(o, "civicReserved"),
                intOf(o, "civicArea"),
                bool(o, "marketSelected"),
                bool(o, "marketReserved"),
                intOf(o, "marketArea"),
                intOf(o, "residentialHousesRequested"),
                intOf(o, "residentialPrecinctsReserved"),
                intOf(o, "residentialHousesPlaced"),
                intOf(o, "residentialHousesDropped"),
                bool(o, "residentialBandActive"),
                intOf(o, "workshopCraftsRequested"),
                o.has("workshopSeating") ? o.get("workshopSeating").getAsString() : "NONE",
                intOf(o, "workshopCraftsPlaced"),
                intOf(o, "workshopCraftsDropped"));
    }

    private static boolean bool(JsonObject o, String f) {
        return o.has(f) && o.get(f).getAsBoolean();
    }

    private static int intOf(JsonObject o, String f) {
        return o.has(f) ? o.get(f).getAsInt() : 0;
    }

    private static JsonObject typeMap(Map<BuildingType, Integer> in) {
        JsonObject o = new JsonObject();
        for (Map.Entry<BuildingType, Integer> e : in.entrySet()) {
            o.addProperty(e.getKey().name(), e.getValue());
        }
        return o;
    }

    private static JsonObject typeDoubleMap(Map<BuildingType, Double> in) {
        JsonObject o = new JsonObject();
        for (Map.Entry<BuildingType, Double> e : in.entrySet()) {
            o.addProperty(e.getKey().name(), nanSafe(e.getValue()));
        }
        return o;
    }

    private static Map<BuildingType, Integer> readTypeIntMap(JsonObject root, String field) {
        Map<BuildingType, Integer> out = new EnumMap<>(BuildingType.class);
        if (!root.has(field)) return out;
        for (Map.Entry<String, com.google.gson.JsonElement> e
                : root.getAsJsonObject(field).entrySet()) {
            try { out.put(BuildingType.valueOf(e.getKey()), e.getValue().getAsInt()); }
            catch (IllegalArgumentException ignored) {}
        }
        return out;
    }

    private static Map<BuildingType, Double> readTypeDoubleMap(JsonObject root, String field) {
        Map<BuildingType, Double> out = new LinkedHashMap<>();
        if (!root.has(field)) return out;
        for (Map.Entry<String, com.google.gson.JsonElement> e
                : root.getAsJsonObject(field).entrySet()) {
            try {
                double v = e.getValue().isJsonNull()
                        ? Double.NaN : e.getValue().getAsDouble();
                if (v == SENTINEL_UNDEFINED) v = Double.NaN;
                out.put(BuildingType.valueOf(e.getKey()), v);
            }
            catch (IllegalArgumentException ignored) {}
        }
        return out;
    }

    /**
     * Maps a metric value to a {@link Number} fit for {@code
     * JsonObject.addProperty(String, Number)}. Non-finite values
     * (NaN, ±Infinity) become {@link #SENTINEL_UNDEFINED}; finite
     * values — including a real {@code 0.0} — pass through
     * unchanged.
     */
    private static Number nanSafe(double v) {
        return Double.isFinite(v) ? v : SENTINEL_UNDEFINED;
    }

    /**
     * Inverse of {@link #nanSafe(double)} for read-back. The
     * sentinel restores to {@link Double#NaN} so in-memory NaN
     * checks downstream (in {@link #diff} and in {@link Table})
     * keep working. A finite {@code 0.0} read from JSON stays
     * {@code 0.0}.
     *
     * <p>A legacy baseline written by the prior {@code null}-based
     * serializer also restores to NaN (the {@code isJsonNull}
     * branch) so a re-record after this fix isn't required just to
     * read older baselines, though re-recording is recommended for
     * clarity.
     */
    private static double fromNanSafe(JsonObject o, String field) {
        if (!o.has(field) || o.get(field).isJsonNull()) return Double.NaN;
        double v = o.get(field).getAsDouble();
        return v == SENTINEL_UNDEFINED ? Double.NaN : v;
    }

    // =========================================================================
    // Diff
    // =========================================================================

    public record Failure(String runLabel, String metric, double baseline,
                          double current, double delta) {}

    public record DiffResult(List<Failure> failures, boolean improvedOnly) {
        public boolean passed() { return failures.isEmpty(); }
    }

    public static DiffResult diff(List<RunMetrics> baseline, List<RunMetrics> current) {
        Map<String, RunMetrics> byKey = new LinkedHashMap<>();
        for (RunMetrics b : baseline) byKey.put(b.terrain() + "/" + b.configLabel(), b);
        List<Failure> failures = new ArrayList<>();

        for (RunMetrics cur : current) {
            String key = cur.terrain() + "/" + cur.configLabel();
            RunMetrics base = byKey.get(key);
            if (base == null) {
                // New run that wasn't in baseline — treat as a structural
                // change. Record but don't gate (the user added a row).
                continue;
            }
            // Gating metric 1: per-type placement rate.
            for (Map.Entry<BuildingType, Double> e : base.placedRatePerType().entrySet()) {
                double baseR = e.getValue();
                double curR  = cur.placedRatePerType().getOrDefault(e.getKey(), 0.0);
                double drop  = baseR - curR;
                if (drop > PER_TYPE_RATE_DELTA) {
                    failures.add(new Failure(key,
                            "placedRate[" + e.getKey().name() + "]",
                            baseR, curR, -drop));
                }
            }
            // Gating metric 2: overall placed rate.
            double dropOverall = base.placedRate() - cur.placedRate();
            if (dropOverall > PLACED_RATE_DELTA) {
                failures.add(new Failure(key, "placedRate",
                        base.placedRate(), cur.placedRate(), -dropOverall));
            }
            // Gating metric 3: network components must not increase.
            // Skip if either side aborted — going aborted→placed (or
            // vice-versa) reads as a 0→N change on this metric but
            // isn't a network-fragmentation regression. The placement
            // rate gate above already covers that direction.
            if (!base.aborted() && !cur.aborted()
                    && cur.networkComponents() > base.networkComponents()) {
                failures.add(new Failure(key, "networkComponents",
                        base.networkComponents(), cur.networkComponents(),
                        cur.networkComponents() - base.networkComponents()));
            }
            // Gating metric 4: frac_buildings_on_main_component.
            // Asymmetric treatment of the undefined boundary:
            //   defined → defined: normal delta check.
            //   defined → undefined: FAIL. A gating metric losing
            //       observability is a regression (per the harness
            //       contract: "a gating metric going undefined is a
            //       regression in observability").
            //   undefined → defined: improvement; don't fail.
            //   undefined → undefined: no change; don't fail.
            boolean baseDef = !Double.isNaN(base.fracBuildingsOnMainComponent());
            boolean curDef  = !Double.isNaN(cur.fracBuildingsOnMainComponent());
            if (baseDef && curDef) {
                double mainDrop = base.fracBuildingsOnMainComponent()
                        - cur.fracBuildingsOnMainComponent();
                if (mainDrop > MAIN_COMPONENT_DELTA) {
                    failures.add(new Failure(key, "fracBuildingsOnMainComponent",
                            base.fracBuildingsOnMainComponent(),
                            cur.fracBuildingsOnMainComponent(),
                            -mainDrop));
                }
            } else if (baseDef && !curDef) {
                failures.add(new Failure(key,
                        "fracBuildingsOnMainComponent(observability-lost)",
                        base.fracBuildingsOnMainComponent(), Double.NaN,
                        Double.NaN));
            }

            // Gating metrics 5-8: district-era reservations. Same
            // asymmetric philosophy — only the bad direction fails;
            // areas/counts moving UP, or districts newly reserving, are
            // improvements and never fail.
            diffDistrict(key, base.district(), cur.district(), failures);
        }
        return new DiffResult(failures, false);
    }

    /**
     * District-era gates (asymmetric). Fires a {@link Failure} on the
     * regression direction only:
     *
     * <ul>
     *   <li><b>Plaza paves-0</b> — a civic or market square whose
     *       baseline area was &gt; 0 collapsing to 0 in current. The
     *       paves-0 bug class.</li>
     *   <li><b>Market NO_REGION</b> — MARKET still selected, baseline
     *       reserved a sub-district, current didn't (the market that
     *       used to seed now finds no region).</li>
     *   <li><b>Residential reserve-rate</b> — houses still requested,
     *       precincts reserved dropped below baseline (districts that
     *       used to reserve now don't).</li>
     *   <li><b>Workshop row→fallback</b> — craft set still requested,
     *       seating regressed ROW → LOTS/NONE, or crafts placed dropped
     *       below baseline.</li>
     * </ul>
     *
     * Each gate is conditioned on the baseline having had the thing in
     * the first place, so a run that legitimately has no market / no
     * houses / no craft set never trips on absence.
     */
    private static void diffDistrict(String key, RunMetrics.DistrictMetrics b,
                                     RunMetrics.DistrictMetrics c,
                                     List<Failure> failures) {
        // Plaza paves-0 (civic).
        if (b.civicArea() > 0 && c.civicArea() == 0) {
            failures.add(new Failure(key, "district.civicArea(paves-0)",
                    b.civicArea(), c.civicArea(), c.civicArea() - b.civicArea()));
        }
        // Plaza paves-0 (market) — only when the market was reserved before.
        if (b.marketReserved() && b.marketArea() > 0 && c.marketArea() == 0) {
            failures.add(new Failure(key, "district.marketArea(paves-0)",
                    b.marketArea(), c.marketArea(), c.marketArea() - b.marketArea()));
        }
        // Market NO_REGION — selected both sides, reserved before, not now.
        if (b.marketSelected() && c.marketSelected()
                && b.marketReserved() && !c.marketReserved()) {
            failures.add(new Failure(key, "district.marketReserved(NO_REGION)",
                    1.0, 0.0, -1.0));
        }
        // Residential reserve-rate — houses still requested, fewer precincts.
        if (c.residentialHousesRequested() >= b.residentialHousesRequested()
                && b.residentialHousesRequested() > 0
                && c.residentialPrecinctsReserved() < b.residentialPrecinctsReserved()) {
            failures.add(new Failure(key, "district.residentialPrecinctsReserved",
                    b.residentialPrecinctsReserved(),
                    c.residentialPrecinctsReserved(),
                    c.residentialPrecinctsReserved()
                            - b.residentialPrecinctsReserved()));
        }
        // Workshop row→fallback regression — craft set still requested.
        if (b.workshopCraftsRequested() > 0
                && c.workshopCraftsRequested() >= b.workshopCraftsRequested()) {
            if ("ROW".equals(b.workshopSeating())
                    && !"ROW".equals(c.workshopSeating())) {
                failures.add(new Failure(key, "district.workshopSeating(ROW->fallback)",
                        Double.NaN, Double.NaN, Double.NaN));
            }
            if (c.workshopCraftsPlaced() < b.workshopCraftsPlaced()) {
                failures.add(new Failure(key, "district.workshopCraftsPlaced",
                        b.workshopCraftsPlaced(), c.workshopCraftsPlaced(),
                        c.workshopCraftsPlaced() - b.workshopCraftsPlaced()));
            }
        }
    }
}
