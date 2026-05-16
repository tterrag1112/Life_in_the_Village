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
 */
public final class Baseline {

    public static final double PER_TYPE_RATE_DELTA  = 0.10;
    public static final double PLACED_RATE_DELTA    = 0.05;
    public static final double MAIN_COMPONENT_DELTA = 0.05;

    private Baseline() {}

    // =========================================================================
    // IO
    // =========================================================================

    public static void write(Path path, List<RunMetrics> runs) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
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
                clust, dh, 0L);
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
            try { out.put(BuildingType.valueOf(e.getKey()),
                    e.getValue().isJsonNull() ? Double.NaN : e.getValue().getAsDouble()); }
            catch (IllegalArgumentException ignored) {}
        }
        return out;
    }

    private static Object nanSafe(double v) { return Double.isNaN(v) ? null : v; }

    private static double fromNanSafe(JsonObject o, String field) {
        if (!o.has(field) || o.get(field).isJsonNull()) return Double.NaN;
        return o.get(field).getAsDouble();
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
            if (!Double.isNaN(base.fracBuildingsOnMainComponent())
                    && !Double.isNaN(cur.fracBuildingsOnMainComponent())) {
                double mainDrop = base.fracBuildingsOnMainComponent()
                        - cur.fracBuildingsOnMainComponent();
                if (mainDrop > MAIN_COMPONENT_DELTA) {
                    failures.add(new Failure(key, "fracBuildingsOnMainComponent",
                            base.fracBuildingsOnMainComponent(),
                            cur.fracBuildingsOnMainComponent(),
                            -mainDrop));
                }
            }
        }
        return new DiffResult(failures, false);
    }
}
