package tterrag1112.life_in_the_village.Kingdom.Castle;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class CastleStyleLoader extends SimplePreparableReloadListener<Map<Identifier, JsonElement>> {

    public static final CastleStyleLoader INSTANCE = new CastleStyleLoader();

    private static final Logger LOGGER = LogManager.getLogger(CastleStyleLoader.class);

    private static final String FOLDER = "castle_styles";
    private static final String SUFFIX = ".json";

    // Starts empty — populated after the first reload fires
    private Map<Identifier, CastleStyle> loaded = Collections.emptyMap();

    // Tracks whether apply() has ever been called — lets us distinguish
    // "listener not registered" from "listener ran but found nothing"
    private boolean hasApplied = false;

    private CastleStyleLoader() {}

    // -------------------------------------------------------------------------
    // Off-thread: scan for files
    // -------------------------------------------------------------------------

    @Override
    protected Map<Identifier, JsonElement> prepare(ResourceManager manager,
                                                         ProfilerFiller profiler) {
        LOGGER.info("CastleStyleLoader prepare() fired — scanning for castle_styles/**/*.json");

        Map<Identifier, JsonElement> raw = new HashMap<>();

        Map<Identifier, Resource> resources = manager.listResources(
                FOLDER,
                path -> path.getPath().endsWith(SUFFIX)
        );

        LOGGER.info("CastleStyleLoader: listResources found {} file(s) under '{}'",
                resources.size(), FOLDER);

        for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
            Identifier fileId   = entry.getKey();
            Resource         resource = entry.getValue();

            LOGGER.info("CastleStyleLoader: reading file '{}'", fileId);

            try (Reader reader = resource.openAsReader()) {
                JsonElement      json    = JsonParser.parseReader(reader);
                Identifier styleId = fileLocationToStyleId(fileId);
                LOGGER.info("CastleStyleLoader: parsed '{}' as style id '{}'", fileId, styleId);
                raw.put(styleId, json);
            } catch (IOException e) {
                LOGGER.error("CastleStyleLoader: IOException reading '{}': {}", fileId, e.getMessage());
            } catch (Exception e) {
                LOGGER.error("CastleStyleLoader: unexpected error reading '{}': {}", fileId, e.getMessage());
            }
        }

        LOGGER.info("CastleStyleLoader: prepare() returning {} raw entries", raw.size());
        return raw;
    }

    // -------------------------------------------------------------------------
    // Main thread: codec parse
    // -------------------------------------------------------------------------

    @Override
    protected void apply(Map<Identifier, JsonElement> prepared,
                         ResourceManager manager,
                         ProfilerFiller profiler) {
        hasApplied = true;
        LOGGER.info("CastleStyleLoader apply() fired — parsing {} prepared entries", prepared.size());

        Map<Identifier, CastleStyle> result = new HashMap<>();

        for (Map.Entry<Identifier, JsonElement> entry : prepared.entrySet()) {
            Identifier id   = entry.getKey();
            JsonElement      json = entry.getValue();

            LOGGER.info("CastleStyleLoader: parsing style '{}'", id);

            CastleStyle.CODEC
                    .parse(JsonOps.INSTANCE, json)
                    .resultOrPartial(err -> LOGGER.error(
                            "CastleStyleLoader: codec error for '{}': {}", id, err))
                    .ifPresent(style -> {
                        LOGGER.info("CastleStyleLoader: successfully loaded style '{}'", id);
                        result.put(id, style);
                    });
        }

        this.loaded = Collections.unmodifiableMap(result);
        LOGGER.info("CastleStyleLoader: finished — {} style(s) loaded", result.size());

        if (result.isEmpty() && !prepared.isEmpty()) {
            LOGGER.error("CastleStyleLoader: {} file(s) were found but NONE parsed successfully. " +
                            "This is a codec mismatch — check that the JSON fields match CastleStyle.CODEC exactly.",
                    prepared.size());
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public Optional<CastleStyle> get(Identifier id) {
        warnIfNeverApplied();
        return Optional.ofNullable(loaded.get(id));
    }

    public Optional<CastleStyle> get(String namespacedId) {
        return get(Identifier.parse(namespacedId));
    }

    public Map<Identifier, CastleStyle> getAll() {
        warnIfNeverApplied();
        return loaded;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a file path Identifier to a logical style id.
     *
     *   "yourmod:castle_styles/norman.json"  ->  "yourmod:norman"
     *   "yourmod:castle_styles/ruins/keep.json"  ->  "yourmod:ruins/keep"
     */
    private static Identifier fileLocationToStyleId(Identifier file) {
        String path = file.getPath();

        if (path.startsWith(FOLDER + "/")) {
            path = path.substring(FOLDER.length() + 1);
        }
        if (path.endsWith(SUFFIX)) {
            path = path.substring(0, path.length() - SUFFIX.length());
        }

        return Identifier.fromNamespaceAndPath(file.getNamespace(), path);
    }

    /**
     * Logs a warning if getAll()/get() is called before apply() has ever fired.
     * This is the symptom of the listener not being registered.
     */
    private void warnIfNeverApplied() {
        if (!hasApplied) {
            LOGGER.warn("CastleStyleLoader.getAll() called but apply() has never fired. " +
                    "The loader is probably not registered. Make sure you have: " +
                    "event.addListener(CastleStyleLoader.INSTANCE) " +
                    "in an AddReloadListenerEvent handler on the FORGE event bus.");
        }
    }
}