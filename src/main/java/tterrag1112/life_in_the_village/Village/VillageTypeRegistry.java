// FILE: src/main/java/tterrag1112/life_in_the_village/Village/VillageTypeRegistry.java
package tterrag1112.life_in_the_village.Village;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Planning.Rules.ShapeRule;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainStrategy;

import java.io.InputStreamReader;
import java.util.*;

public class VillageTypeRegistry
        extends SimplePreparableReloadListener<Map<String, VillageTypeData>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(VillageTypeRegistry.class);
    private static final Gson GSON = new Gson();
    public static final String DEFAULT_TYPE = "default_village";

    public static final VillageTypeRegistry INSTANCE = new VillageTypeRegistry();
    private Map<String, VillageTypeData> types = new HashMap<>();

    private VillageTypeRegistry() {}

    @Override
    protected Map<String, VillageTypeData> prepare(ResourceManager manager,
                                                   ProfilerFiller profiler) {
        Map<String, VillageTypeData> loaded = new HashMap<>();

        manager.listResources("village_types",
                path -> path.getPath().endsWith(".json")
        ).forEach((location, resource) -> {
            try {
                JsonObject json = GSON.fromJson(
                        new InputStreamReader(resource.open()), JsonObject.class);

                String type = json.get("type").getAsString();
                String culture = json.has("culture")
                        ? json.get("culture").getAsString() : "default";

                // Starter buildings
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

                // Shape profile
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
                            LOGGER.warn("Unknown shape_type in {}", location);
                        }
                    }
                    boolean forcedAxis = sp.has("forced_axis") && sp.get("forced_axis").getAsBoolean();
                    int maxRings = sp.has("max_rings") ? sp.get("max_rings").getAsInt() : 2;
                    float streetDensity = sp.has("street_density")
                            ? sp.get("street_density").getAsFloat() : 1.0f;
                    boolean walledByDefault = sp.has("walled_by_default")
                            && sp.get("walled_by_default").getAsBoolean();
                    shapeProfile = new VillageTypeData.VillageShapeProfile(
                            shapeType, forcedAxis, maxRings, streetDensity, walledByDefault);
                }

                // Shape rules
                List<ShapeRule> shapeRules = new ArrayList<>();
                if (json.has("shape_rules")) {
                    shapeRules = new ArrayList<>();
                    for (var el : json.getAsJsonArray("shape_rules")) {
                        if (!el.isJsonObject()) continue;
                        ShapeRule rule = ShapeRule.Registry.parse(el.getAsJsonObject());
                        if (rule != null) shapeRules.add(rule);
                    }
                }

                // Terrain strategy
                String strategyName = json.has("terrain_strategy")
                        ? json.get("terrain_strategy").getAsString() : null;
                TerrainStrategy strategy = TerrainStrategy.fromName(strategyName);

                // Farm plot config
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
                        } catch (IllegalArgumentException ignored) {}
                    }
                    int minDistance = fp.has("min_distance") ? fp.get("min_distance").getAsInt() : 8;
                    int maxDistance = fp.has("max_distance") ? fp.get("max_distance").getAsInt() : 32;
                    boolean allowAnimalPens = !fp.has("allow_animal_pens")
                            || fp.get("allow_animal_pens").getAsBoolean();
                    int plotsPerFarmhouse = fp.has("plots_per_farmhouse")
                            ? fp.get("plots_per_farmhouse").getAsInt() : 1;
                    farmPlotConfig = new VillageTypeData.FarmPlotConfig(
                            placement, minDistance, maxDistance,
                            allowAnimalPens, plotsPerFarmhouse);
                }
                Set<VillageTag> manualTags = EnumSet.noneOf(VillageTag.class);
                if (json.has("tags")) {
                    for (var el : json.getAsJsonArray("tags")) {
                        if (!el.isJsonPrimitive()) continue;
                        VillageTag tag = VillageTag.fromName(el.getAsString());
                        if (tag != null) manualTags.add(tag);
                        else LOGGER.warn("Unknown village tag '{}' in {}", el.getAsString(), location);
                    }
                }

                int townSquareCapacity = json.has("town_square_capacity")
                        ? json.get("town_square_capacity").getAsInt() : 4;

                VillageTypeData typeData = new VillageTypeData(
                        type, culture, buildings,
                        shapeProfile, farmPlotConfig, strategy, townSquareCapacity,
                        manualTags);
                typeData.setShapeRules(shapeRules);

                // Phase 22: optional fallback_chain. JSON: an array of
                // ShapeType names. Empty / missing means no schema-declared
                // chain; cascade engine falls back to the per-recipe
                // fallbackShape() (Phase 16b path). The chain does NOT
                // include the primary shape — it's the list of shapes
                // tried after primary fails.
                if (json.has("fallback_chain")) {
                    java.util.List<VillageTypeData.ShapeType> chain =
                            new java.util.ArrayList<>();
                    for (var el : json.getAsJsonArray("fallback_chain")) {
                        if (!el.isJsonPrimitive()) continue;
                        try {
                            chain.add(VillageTypeData.ShapeType
                                    .valueOf(el.getAsString()));
                        } catch (IllegalArgumentException ex) {
                            LOGGER.warn("Unknown ShapeType '{}' in fallback_chain "
                                    + "for {} — skipping",
                                    el.getAsString(), location);
                        }
                    }
                    typeData.setFallbackChain(chain);
                }
                // ── Phase 21 kingdom-rework schema ─────────────────────
                // Schema-only — no consumer wired in this phase. Bad
                // values throw VillageTypeParseException and the
                // top-level catch below drops the type and logs.
                parseKingdomReworkSchema(json, typeData, type);

                if (json.has("style")) {
                    typeData.setStyle(json.get("style").getAsString());
                }
                // P0a-14: colorPalette accepts either a string id or
                // an inline {primary,accent,roof} object — both shapes
                // route through ColorPaletteRegistry.parse.
                if (json.has("colorPalette")) {
                    typeData.setColorPalette(
                            tterrag1112.life_in_the_village.Village.Decoration
                                    .Variants.ColorPaletteRegistry
                                    .parse(json.get("colorPalette")));
                }
                // P0a-12: signatureColor — DyeColor name, optional.
                if (json.has("signatureColor")
                        && json.get("signatureColor").isJsonPrimitive()) {
                    String name = json.get("signatureColor").getAsString();
                    try {
                        typeData.setSignatureColor(
                                net.minecraft.world.item.DyeColor.valueOf(
                                        name.toUpperCase(java.util.Locale.ROOT)));
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("Unknown signatureColor '{}' in {} — "
                                + "falling back to no override",
                                name, location);
                    }
                }

                loaded.put(type, typeData);
                LOGGER.info("Loaded village type '{}' ({} buildings, shape={}, strategy={}, tags={})",
                        type, buildings.size(), shapeProfile.shapeType(), strategy, typeData.getTags());

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
        return types.getOrDefault(type, types.getOrDefault(DEFAULT_TYPE, null));
    }

    public Set<String> getAvailableTypes() {
        return Collections.unmodifiableSet(types.keySet());
    }

    public void loadFromServer(ResourceManager manager) {
        Map<String, VillageTypeData> loaded = prepare(manager, null);
        apply(loaded, manager, null);
    }

    // ─── Phase 21: kingdom-rework schema parsing ──────────────────────────
    // Schema-only. Parsed values are stored on VillageTypeData; no
    // consumer is wired in this phase. Bad values throw
    // VillageTypeParseException, which the top-level catch in prepare()
    // turns into a logged error and a dropped village type.

    private static void parseKingdomReworkSchema(JsonObject json,
                                                 VillageTypeData typeData,
                                                 String name) {
        // settlement_tier — case-insensitive enum name; throws on bad.
        if (json.has("settlement_tier")) {
            String tierStr = json.get("settlement_tier").getAsString();
            SettlementTier tier = SettlementTier.fromName(tierStr);
            if (tier == null) {
                throw new VillageTypeParseException(
                        "Village type '" + name + "' has invalid "
                        + "settlement_tier='" + tierStr + "'. Valid: "
                        + java.util.Arrays.toString(SettlementTier.values()));
            }
            typeData.setSettlementTier(tier);
        }

        // biome_affinity — string array, defaults to empty.
        if (json.has("biome_affinity")) {
            typeData.setBiomeAffinity(parseStringSet(
                    json.getAsJsonArray("biome_affinity"),
                    name, "biome_affinity"));
        }

        // kingdom_roles — string array, defaults to empty.
        if (json.has("kingdom_roles")) {
            typeData.setKingdomRoles(parseStringSet(
                    json.getAsJsonArray("kingdom_roles"),
                    name, "kingdom_roles"));
        }

        // trade_priority — int 0..4, defaults to 1.
        if (json.has("trade_priority")) {
            typeData.setTradePriority(
                    json.get("trade_priority").getAsInt());
        }

        // can_be_capital — boolean, defaults to false.
        if (json.has("can_be_capital")) {
            typeData.setCanBeCapital(
                    json.get("can_be_capital").getAsBoolean());
        }

        // max_per_kingdom — int -1 or positive, defaults to -1.
        if (json.has("max_per_kingdom")) {
            typeData.setMaxPerKingdom(
                    json.get("max_per_kingdom").getAsInt());
        }

        validateKingdomFields(typeData, name);
    }

    private static java.util.Set<String> parseStringSet(
            com.google.gson.JsonArray array,
            String typeName, String fieldName) {
        java.util.Set<String> result = new java.util.HashSet<>();
        for (var el : array) {
            if (!el.isJsonPrimitive()) {
                throw new VillageTypeParseException(
                        "Village type '" + typeName + "' field '"
                        + fieldName + "' contains a non-string entry: "
                        + el);
            }
            String s = el.getAsString();
            if (s.isEmpty()) {
                throw new VillageTypeParseException(
                        "Village type '" + typeName + "' field '"
                        + fieldName + "' contains an empty string.");
            }
            result.add(s);
        }
        return result;
    }

    private static void validateKingdomFields(VillageTypeData data,
                                              String name) {
        if (data.canBeCapital()
                && data.getSettlementTier().ordinal()
                        < SettlementTier.TOWN.ordinal()) {
            throw new VillageTypeParseException(
                    "Village type '" + name + "' has can_be_capital=true "
                    + "but settlement_tier=" + data.getSettlementTier()
                    + ". Capitals must be TOWN or larger.");
        }
        if (data.getTradePriority() < 0 || data.getTradePriority() > 4) {
            throw new VillageTypeParseException(
                    "Village type '" + name + "' has trade_priority="
                    + data.getTradePriority() + ". Must be 0-4.");
        }
        if (data.getMaxPerKingdom() == 0
                || data.getMaxPerKingdom() < -1) {
            throw new VillageTypeParseException(
                    "Village type '" + name + "' has max_per_kingdom="
                    + data.getMaxPerKingdom()
                    + ". Must be -1 (unbounded) or positive.");
        }
        // Empty-string checks already happen in parseStringSet.
    }
}