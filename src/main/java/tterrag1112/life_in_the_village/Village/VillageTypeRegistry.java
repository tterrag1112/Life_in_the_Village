// src/main/java/tterrag1112/life_in_the_village/Village/VillageTypeRegistry.java
package tterrag1112.life_in_the_village.Village;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStreamReader;
import java.util.*;

/**
 * Loads all village type definitions from
 * {@code data/<modid>/village_types/*.json}.
 *
 * <h3>JSON schema additions</h3>
 * <pre>
 * {
 *   "type": "royal_capital",
 *   "culture": "default",
 *   "shape_profile": { ... },          // optional
 *   "capital_profile": {               // optional — only for capitals (20+ buildings)
 *     "layout_type":      "ROUND",     // ROUND | SQUARE | ORGANIC  (default ROUND)
 *     "grid_spacing":     0,           // 0 = auto-calculate        (default 0)
 *     "buildings_per_face": 2,         // buildings along each face (default 2)
 *     "building_width":  12,           // footprint width in blocks (default 12)
 *     "building_depth":  10,           // footprint depth in blocks (default 10)
 *     "face_setback":     2,           // road kerb to building     (default 2)
 *     "alley_gap":        2,           // gap between buildings     (default 2)
 *     "spoke_count":      6,           // radial avenues for ROUND  (default 6)
 *     "gate_roads":    true            // roads stop at city gate   (default true)
 *   },
 *   "starter_buildings": [ ... ],
 *   "starter_npcs":      [ ... ],
 *   "starter_items":     [ ... ]
 * }
 * </pre>
 */
public class VillageTypeRegistry extends SimplePreparableReloadListener<Map<String, VillageTypeData>> {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(VillageTypeRegistry.class);
    private static final Gson GSON = new Gson();
    public static final String DEFAULT_TYPE = "default";

    public static final VillageTypeRegistry INSTANCE = new VillageTypeRegistry();
    private Map<String, VillageTypeData> types = new HashMap<>();

    private VillageTypeRegistry() {}

    // =========================================================================
    // Load
    // =========================================================================

    @Override
    protected Map<String, VillageTypeData> prepare(ResourceManager manager,
                                                   ProfilerFiller profiler) {
        System.out.println("VillageTypeRegistry prepare() called");

        Map<String, VillageTypeData> loaded = new HashMap<>();

        manager.listResources("village_types",
                path -> path.getPath().endsWith(".json")
        ).forEach((location, resource) -> {
            try {
                JsonObject json = GSON.fromJson(
                        new InputStreamReader(resource.open()),
                        JsonObject.class);

                String type    = json.get("type").getAsString();
                String culture = json.has("culture")
                        ? json.get("culture").getAsString() : "default";

                // ── Starter buildings ──────────────────────────────────────────
                List<VillageTypeData.StarterBuilding> buildings = new ArrayList<>();
                for (var el : json.getAsJsonArray("starter_buildings")) {
                    JsonObject b = el.getAsJsonObject();
                    int minCount = b.has("min_count") ? b.get("min_count").getAsInt() : 1;
                    int maxCount = b.has("max_count") ? b.get("max_count").getAsInt() : minCount;
                    buildings.add(new VillageTypeData.StarterBuilding(
                            b.get("type").getAsString(),
                            b.get("structure").getAsString(),
                            minCount, maxCount));
                }

                // ── Starter NPCs ───────────────────────────────────────────────
                List<VillageTypeData.StarterNpc> npcs = new ArrayList<>();
                for (var el : json.getAsJsonArray("starter_npcs")) {
                    JsonObject n = el.getAsJsonObject();
                    npcs.add(new VillageTypeData.StarterNpc(
                            n.get("profession").getAsString(),
                            n.get("building_type").getAsString(),
                            n.get("family_role").getAsString()));
                }

                // ── Starter items ──────────────────────────────────────────────
                List<VillageTypeData.StarterItem> items = new ArrayList<>();
                if (json.has("starter_items")) {
                    for (var el : json.getAsJsonArray("starter_items")) {
                        JsonObject i = el.getAsJsonObject();
                        items.add(new VillageTypeData.StarterItem(
                                i.get("building_type").getAsString(),
                                i.get("item").getAsString(),
                                i.get("count").getAsInt()));
                    }
                }

                // ── Shape profile ──────────────────────────────────────────────
                VillageTypeData.VillageShapeProfile shapeProfile =
                        VillageTypeData.VillageShapeProfile.defaultProfile();
                if (json.has("shape_profile")) {
                    JsonObject sp = json.getAsJsonObject("shape_profile");
                    VillageTypeData.ShapeType shapeType = VillageTypeData.ShapeType.RADIAL;
                    if (sp.has("shape_type")) {
                        try {
                            shapeType = VillageTypeData.ShapeType.valueOf(
                                    sp.get("shape_type").getAsString().toUpperCase());
                        } catch (IllegalArgumentException ignored) {
                            LOGGER.warn("Unknown shape_type '{}' in {}",
                                    sp.get("shape_type").getAsString(), location);
                        }
                    }
                    boolean forcedAxis     = sp.has("forced_axis")
                            && sp.get("forced_axis").getAsBoolean();
                    int maxRings           = sp.has("max_rings")
                            ? sp.get("max_rings").getAsInt() : 2;
                    float streetDensity    = sp.has("street_density")
                            ? sp.get("street_density").getAsFloat() : 1.0f;
                    boolean walledByDefault = sp.has("walled_by_default")
                            && sp.get("walled_by_default").getAsBoolean();
                    shapeProfile = new VillageTypeData.VillageShapeProfile(
                            shapeType, forcedAxis, maxRings,
                            streetDensity, walledByDefault);
                }

                // ── Capital profile ────────────────────────────────────────────
                // Only relevant for capital types (20+ buildings), but parsed
                // unconditionally so the JSON can set values ahead of time.
                VillageTypeData.CapitalProfile capitalProfile =
                        VillageTypeData.CapitalProfile.defaultRound();
                if (json.has("capital_profile")) {
                    JsonObject cp = json.getAsJsonObject("capital_profile");

                    VillageTypeData.CapitalLayoutType layoutType =
                            VillageTypeData.CapitalLayoutType.ROUND;
                    if (cp.has("layout_type")) {
                        try {
                            layoutType = VillageTypeData.CapitalLayoutType.valueOf(
                                    cp.get("layout_type").getAsString().toUpperCase());
                        } catch (IllegalArgumentException ignored) {
                            LOGGER.warn("Unknown capital layout_type '{}' in {}",
                                    cp.get("layout_type").getAsString(), location);
                        }
                    }

                    int gridSpacing      = cp.has("grid_spacing")
                            ? cp.get("grid_spacing").getAsInt() : 0;
                    int buildingsPerFace = cp.has("buildings_per_face")
                            ? cp.get("buildings_per_face").getAsInt() : 2;
                    int buildingWidth    = cp.has("building_width")
                            ? cp.get("building_width").getAsInt() : 12;
                    int buildingDepth    = cp.has("building_depth")
                            ? cp.get("building_depth").getAsInt() : 10;
                    int faceSetback      = cp.has("face_setback")
                            ? cp.get("face_setback").getAsInt() : 2;
                    int alleyGap         = cp.has("alley_gap")
                            ? cp.get("alley_gap").getAsInt() : 2;
                    int spokeCount       = cp.has("spoke_count")
                            ? cp.get("spoke_count").getAsInt() : 6;
                    boolean gateRoads    = !cp.has("gate_roads")
                            || cp.get("gate_roads").getAsBoolean();

                    capitalProfile = new VillageTypeData.CapitalProfile(
                            layoutType, gridSpacing, buildingsPerFace,
                            buildingWidth, buildingDepth,
                            faceSetback, alleyGap, spokeCount, gateRoads);
                }
                VillageTypeData.FarmPlotConfig farmPlotConfig =
                        VillageTypeData.FarmPlotConfig.defaultConfig();
                if (json.has("farm_plot_config")) {
                    JsonObject fp = json.getAsJsonObject("farm_plot_config");

                    VillageTypeData.FarmPlotPlacement placement =
                            VillageTypeData.FarmPlotPlacement.PERIMETER_OUTSIDE;
                    if (fp.has("placement")) {
                        try {
                            placement = VillageTypeData.FarmPlotPlacement.valueOf(
                                    fp.get("placement").getAsString().toUpperCase());
                        } catch (IllegalArgumentException ignored) {
                            LOGGER.warn("Unknown farm_plot_placement '{}' in {}",
                                    fp.get("placement").getAsString(), location);
                        }
                    }

                    int minDistance = fp.has("min_distance")
                            ? fp.get("min_distance").getAsInt() : 8;
                    int maxDistance = fp.has("max_distance")
                            ? fp.get("max_distance").getAsInt() : 32;
                    boolean allowAnimalPens = !fp.has("allow_animal_pens")
                            || fp.get("allow_animal_pens").getAsBoolean();
                    int plotsPerFarmhouse = fp.has("plots_per_farmhouse")
                            ? fp.get("plots_per_farmhouse").getAsInt() : 1;

                    farmPlotConfig = new VillageTypeData.FarmPlotConfig(
                            placement, minDistance, maxDistance,
                            allowAnimalPens, plotsPerFarmhouse);
                }

                loaded.put(type, new VillageTypeData(
                        type, culture, buildings, npcs, items,
                        shapeProfile, capitalProfile, farmPlotConfig));
                LOGGER.info("Loaded village type '{}'", type);

            } catch (Exception e) {
                LOGGER.error("Failed to load village type from {}: {}",
                        location, e.getMessage());
            }
        });

        return loaded;
    }

    @Override
    protected void apply(Map<String, VillageTypeData> prepared,
                         ResourceManager manager, ProfilerFiller profiler) {
        this.types = new HashMap<>(prepared);
        LOGGER.info("Village type registry loaded {} types", types.size());
    }

    public VillageTypeData getType(String type) {
        return types.getOrDefault(type,
                types.getOrDefault(DEFAULT_TYPE, null));
    }

    public Set<String> getAvailableTypes() {
        return Collections.unmodifiableSet(types.keySet());
    }

    public void loadFromServer(ResourceManager manager) {
        Map<String, VillageTypeData> loaded = prepare(manager, null);
        apply(loaded, manager, null);
    }
}