package tterrag1112.life_in_the_village.Village.Planning.V2.Culture;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;

import java.io.InputStreamReader;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * V2 culture registry. Loads JSON from
 * {@code data/<modid>/cultures/*.json} via the standard reload
 * pipeline (registered in {@code ModModEvents.onAddServerReloadListeners}).
 *
 * <p>Always provides a {@link #getDefault()} fallback even before
 * any reload has run, so Layer 2 callers never see {@code null}.
 * The hardcoded fallback matches what
 * {@code data/<modid>/cultures/default.json} declares; if the file
 * deviates, the file wins after reload.
 */
public final class CultureRegistry
        extends SimplePreparableReloadListener<Map<String, Culture>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(CultureRegistry.class);
    private static final Gson GSON = new Gson();
    public static final String DEFAULT_ID = "default";

    /** Hardcoded fallback so callers can run before resource reload.
     *  Declared before {@link #INSTANCE}: the instance constructor's
     *  field initializer references it via {@link Map#of}, which
     *  rejects null. Static field initializers run in declaration
     *  order, so this MUST stay above INSTANCE. */
    private static final Culture HARDCODED_DEFAULT = new Culture(
            DEFAULT_ID,
            "minecraft:dirt_path",
            Curvature.NATURAL,
            PlazaShape.IRREGULAR,
            uniformBias());

    public static final CultureRegistry INSTANCE = new CultureRegistry();

    private Map<String, Culture> cultures = Map.of(DEFAULT_ID, HARDCODED_DEFAULT);

    private CultureRegistry() {}

    @Override
    protected Map<String, Culture> prepare(ResourceManager manager,
                                           ProfilerFiller profiler) {
        Map<String, Culture> loaded = new HashMap<>();
        manager.listResources("cultures",
                path -> path.getNamespace().equals(Life_in_the_village.MODID)
                        && path.getPath().endsWith(".json")
        ).forEach((location, resource) -> {
            try {
                JsonObject json = GSON.fromJson(
                        new InputStreamReader(resource.open()), JsonObject.class);
                Culture c = parse(json);
                loaded.put(c.id(), c);
                LOGGER.info("Loaded culture '{}' from {}", c.id(), location);
            } catch (Exception e) {
                LOGGER.error("Failed to load culture from {}: {}",
                        location, e.getMessage());
            }
        });
        // Make sure default is always present.
        loaded.putIfAbsent(DEFAULT_ID, HARDCODED_DEFAULT);
        return loaded;
    }

    @Override
    protected void apply(Map<String, Culture> prepared,
                         ResourceManager manager, ProfilerFiller profiler) {
        this.cultures = new HashMap<>(prepared);
    }

    public Optional<Culture> byId(String id) {
        return Optional.ofNullable(cultures.get(id));
    }

    public Culture getDefault() {
        return cultures.getOrDefault(DEFAULT_ID, HARDCODED_DEFAULT);
    }

    public Culture byIdOrDefault(String id) {
        Culture c = cultures.get(id);
        return c != null ? c : getDefault();
    }

    private static Culture parse(JsonObject json) {
        String id = json.has("id") ? json.get("id").getAsString() : DEFAULT_ID;
        String roadMaterial = json.has("roadMaterial")
                ? json.get("roadMaterial").getAsString()
                : "minecraft:dirt_path";
        Curvature curvature = json.has("preferredCurvature")
                ? Curvature.valueOf(json.get("preferredCurvature").getAsString())
                : Curvature.NATURAL;
        PlazaShape plazaShape = json.has("preferredPlazaShape")
                ? PlazaShape.valueOf(json.get("preferredPlazaShape").getAsString())
                : PlazaShape.IRREGULAR;
        Map<Inclination, Double> bias = uniformBias();
        if (json.has("inclinationBias")) {
            JsonObject biasJson = json.getAsJsonObject("inclinationBias");
            for (Inclination inc : Inclination.values()) {
                if (biasJson.has(inc.name())) {
                    bias.put(inc, biasJson.get(inc.name()).getAsDouble());
                }
            }
        }
        return new Culture(id, roadMaterial, curvature, plazaShape, bias);
    }

    private static Map<Inclination, Double> uniformBias() {
        EnumMap<Inclination, Double> m = new EnumMap<>(Inclination.class);
        for (Inclination inc : Inclination.values()) m.put(inc, 1.0);
        return m;
    }
}
