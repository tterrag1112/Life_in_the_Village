// src/main/java/tterrag1112/life_in_the_village/Village/VillageTypeRegistry.java
package tterrag1112.life_in_the_village.Village;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Planning.Rules.LegacyShapeBridge;
import tterrag1112.life_in_the_village.Village.Planning.Rules.ShapeRule;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainStrategy;

import java.io.InputStreamReader;
import java.util.*;

/**
 * Loads all village type definitions from
 * {@code data/<modid>/village_types/*.json}.
 *
 * <h3>JSON schema</h3>
 * <pre>
 * {
 *   "type": "royal_capital",
 *   "culture": "default",
 *   "shape_profile":     { ... },      // optional — legacy enum-based shape
 *   "shape_rules":       [ ... ],      // optional — Phase 4a rule stack
 *   "capital_profile":   { ... },      // optional — only for capitals
 *   "farm_plot_config":  { ... },      // optional
 *   "starter_buildings": [ ... ],
 *   "starter_npcs":      [ ... ],
 *   "starter_items":     [ ... ]
 * }
 * </pre>
 *
 * <h3>Shape rules vs. shape profile</h3>
 * If a village type JSON contains an explicit {@code shape_rules} array, that
 * array is parsed into a rule stack. Otherwise, the planner's legacy
 * {@code shape_profile.shape_type} enum is translated into an equivalent
 * rule stack via
 * {@link tterrag1112.life_in_the_village.Village.Planning.Rules.LegacyShapeBridge}.
 * The {@code shape_profile} field is still parsed for the planner to consume
 * during Phase 4a — once Phase 4b lands, the enum becomes cosmetic and the
 * rule stack is authoritative.
 */
public class VillageTypeRegistry
        extends SimplePreparableReloadListener<Map<String, VillageTypeData>> {

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

                // ── Shape profile (legacy enum) ────────────────────────────────
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
                    boolean forcedAxis      = sp.has("forced_axis")
                            && sp.get("forced_axis").getAsBoolean();
                    int maxRings            = sp.has("max_rings")
                            ? sp.get("max_rings").getAsInt() : 2;
                    float streetDensity     = sp.has("street_density")
                            ? sp.get("street_density").getAsFloat() : 1.0f;
                    boolean walledByDefault = sp.has("walled_by_default")
                            && sp.get("walled_by_default").getAsBoolean();
                    shapeProfile = new VillageTypeData.VillageShapeProfile(
                            shapeType, forcedAxis, maxRings,
                            streetDensity, walledByDefault);
                }

                // ── Shape rules (Phase 4a) ─────────────────────────────────────
                // If the JSON has an explicit shape_rules array, parse it.
                // Otherwise synthesize a rule stack from the legacy
                // shape_profile.shape_type enum via the bridge.
                List<ShapeRule> shapeRules;
                if (json.has("shape_rules")) {
                    shapeRules = new ArrayList<>();
                    for (var el : json.getAsJsonArray("shape_rules")) {
                        if (!el.isJsonObject()) continue;
                        ShapeRule rule = ShapeRule.Registry.parse(el.getAsJsonObject());
                        if (rule != null) shapeRules.add(rule);
                    }
                    LOGGER.debug("Village type '{}' parsed {} explicit shape rules",
                            type, shapeRules.size());
                } else {
                    shapeRules = LegacyShapeBridge.ruleStackFor(shapeProfile.shapeType());
                    LOGGER.debug("Village type '{}' synthesized {} rules from legacy '{}'",
                            type, shapeRules.size(), shapeProfile.shapeType());
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
                String strategyName = json.has("terrain_strategy")
                        ? json.get("terrain_strategy").getAsString() : null;
                TerrainStrategy strategy = TerrainStrategy.fromName(strategyName);

                // ── Farm plot config ───────────────────────────────────────────
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

                // ── Construct the type data and attach the rule stack ─────────
                VillageTypeData typeData = new VillageTypeData(
                        type, culture, buildings, npcs, items,
                        shapeProfile, capitalProfile, farmPlotConfig);
                typeData.setShapeRules(shapeRules);

                loaded.put(type, typeData);
                LOGGER.info("Loaded village type '{}' ({} shape rules)",
                        type, shapeRules.size());

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

    // =========================================================================
    // Accessors
    // =========================================================================

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