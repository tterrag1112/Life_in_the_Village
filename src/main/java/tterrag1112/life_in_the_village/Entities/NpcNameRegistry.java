package tterrag1112.life_in_the_village.Entities;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.util.*;

public class NpcNameRegistry extends SimplePreparableReloadListener<Map<String, NpcNameData>> {

private static final Logger LOGGER =
        LoggerFactory.getLogger(NpcNameRegistry.class);
private static final Gson GSON = new Gson();
private static final String DEFAULT_CULTURE = "default";
private static final String NAMES_PATH = "npc_names";

// Singleton instance
public static final NpcNameRegistry INSTANCE = new NpcNameRegistry();

private Map<String, NpcNameData> cultures = new HashMap<>();

private NpcNameRegistry() {}

// =========================================================================
// RELOAD LISTENER
// =========================================================================

@Override
protected Map<String, NpcNameData> prepare(ResourceManager manager,
                                           ProfilerFiller profiler) {
    Map<String, NpcNameData> loaded = new HashMap<>();

    // Find all first_names.json files under npc_names/
    manager.listResources(NAMES_PATH,
            path -> path.getPath().endsWith("/first_names.json")
    ).forEach((location, resource) -> {
        try {
            // Extract culture name from path
            // e.g. life_in_the_village:npc_names/default/first_names.json
            String path = location.getPath(); // npc_names/default/first_names.json
            String[] parts = path.split("/");
            if (parts.length < 3) return;
            String culture = parts[1]; // "default"

            // Parse first_names.json
            JsonObject firstNames = GSON.fromJson(
                    new InputStreamReader(resource.open()),
                    JsonObject.class
            );

            List<String> males = parseStringList(
                    firstNames.getAsJsonArray("male"));
            List<String> females = parseStringList(
                    firstNames.getAsJsonArray("female"));

            // Look for matching surnames.json
            Identifier surnameLocation = Identifier.fromNamespaceAndPath(
                    location.getNamespace(),
                    NAMES_PATH + "/" + culture + "/surnames.json"
            );

            List<String> surnames = new ArrayList<>();
            try {
                var surnameResource = manager.getResource(surnameLocation);
                if (surnameResource.isPresent()) {
                    JsonObject surnameJson = GSON.fromJson(
                            new InputStreamReader(
                                    surnameResource.get().open()),
                            JsonObject.class
                    );
                    surnames = parseStringList(
                            surnameJson.getAsJsonArray("surnames"));
                }
            } catch (Exception e) {
                LOGGER.warn("Could not load surnames for culture '{}': {}",
                        culture, e.getMessage());
            }

            loaded.put(culture, new NpcNameData(
                    culture, males, females, surnames));
            LOGGER.info("Loaded NPC names for culture '{}': {} male, "
                            + "{} female, {} surnames",
                    culture, males.size(), females.size(), surnames.size());

        } catch (Exception e) {
            LOGGER.error("Failed to load NPC names from {}: {}",
                    location, e.getMessage());
        }
    });

    return loaded;
}

@Override
protected void apply(Map<String, NpcNameData> prepared,
                     ResourceManager manager, ProfilerFiller profiler) {
    this.cultures = prepared;
    LOGGER.info("NPC name registry loaded {} cultures", cultures.size());
}

// =========================================================================
// API
// =========================================================================

public NpcNameData getCulture(String culture) {
    return cultures.getOrDefault(culture,
            cultures.getOrDefault(DEFAULT_CULTURE, fallback()));
}

public NpcNameData getDefaultCulture() {
    return getCulture(DEFAULT_CULTURE);
}

public Set<String> getAvailableCultures() {
    return Collections.unmodifiableSet(cultures.keySet());
}

/**
 * Generate a full name for an NPC.
 * Uses the default culture until the kingdom system is implemented.
 */
public String generateFirstName(boolean isMale, RandomSource random) {
    return getDefaultCulture().randomFirstName(isMale, random);
}

public String generateSurname(RandomSource random) {
    return getDefaultCulture().randomSurname(random);
}

public String generateFullName(boolean isMale, String surname,
                               RandomSource random) {
    return generateFirstName(isMale, random) + " " + surname;
}

// =========================================================================
// HELPERS
// =========================================================================

private List<String> parseStringList(JsonArray array) {
    if (array == null) return new ArrayList<>();
    List<String> result = new ArrayList<>();
    array.forEach(e -> result.add(e.getAsString()));
    return result;
}

/** Fallback if no culture files are loaded at all. */
private NpcNameData fallback() {
    return new NpcNameData("fallback",
            List.of("John", "William", "Thomas"),
            List.of("Jane", "Mary", "Alice"),
            List.of("Smith", "Miller", "Baker")
    );
}
}
