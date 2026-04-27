package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.io.Reader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reload listener for variant {@code manifest.json} files.
 *
 * <p>Scans the resource tree for files matching the variant-aware
 * folder layout introduced by P0a-01:</p>
 *
 * <pre>structures/{culture}/{style}/{type}/{variantId}/manifest.json</pre>
 *
 * <p>Manifests are parsed via {@link VariantManifest#parse} which
 * applies doc 15's "Missing manifest defaults" plus reasonable
 * fallbacks for fields the doc doesn't enumerate. A variant whose
 * folder exists without a {@code manifest.json} is not loaded by this
 * listener; consumers (P0a-03 {@code VariantRegistry}) layer the
 * "manifest missing → defaults" behaviour on top once they enumerate
 * variant folders.</p>
 *
 * <p>P0a-02 deliberately stops at "manifests indexed in memory". The
 * downstream {@code VariantRegistry}, scoring, and selection arrive
 * in P0a-03 / P0a-06.</p>
 */
public final class VariantManifestLoader
        extends SimplePreparableReloadListener<Map<VariantKey, JsonObject>> {

    public static final VariantManifestLoader INSTANCE = new VariantManifestLoader();

    private static final Logger LOGGER =
            LoggerFactory.getLogger(VariantManifestLoader.class);

    private static final String FOLDER = "structures";
    private static final String SUFFIX = "/manifest.json";

    private Map<VariantKey, VariantManifest> loaded = Collections.emptyMap();
    private boolean hasApplied = false;

    private VariantManifestLoader() {}

    // ── Off-thread: scan + parse JSON ─────────────────────────────────────

    @Override
    protected Map<VariantKey, JsonObject> prepare(ResourceManager manager,
                                                  ProfilerFiller profiler) {
        Map<VariantKey, JsonObject> raw = new HashMap<>();

        Map<Identifier, Resource> resources = manager.listResources(
                FOLDER,
                path -> path.getPath().endsWith(SUFFIX));

        for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
            Identifier fileId = entry.getKey();
            Resource resource = entry.getValue();

            Optional<VariantKey> keyOpt = keyFromPath(fileId);
            if (keyOpt.isEmpty()) {
                LOGGER.warn("VariantManifestLoader: skipping '{}' — "
                        + "path does not match "
                        + "structures/{{culture}}/{{style}}/{{type}}/"
                        + "{{variant}}/manifest.json", fileId);
                continue;
            }
            VariantKey key = keyOpt.get();

            try (Reader reader = resource.openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                if (!json.isJsonObject()) {
                    LOGGER.warn("VariantManifestLoader: '{}' is not a "
                            + "JSON object — skipping", fileId);
                    continue;
                }
                raw.put(key, json.getAsJsonObject());
            } catch (Exception e) {
                LOGGER.error("VariantManifestLoader: failed to read '{}': {}",
                        fileId, e.getMessage());
            }
        }

        return raw;
    }

    // ── Main thread: build defaults + index ───────────────────────────────

    @Override
    protected void apply(Map<VariantKey, JsonObject> prepared,
                         ResourceManager manager,
                         ProfilerFiller profiler) {
        Map<VariantKey, VariantManifest> result = new HashMap<>();

        for (Map.Entry<VariantKey, JsonObject> entry : prepared.entrySet()) {
            VariantKey key = entry.getKey();
            VariantManifest manifest =
                    VariantManifest.parse(entry.getValue(), key.variantId());
            result.put(key, manifest);
        }

        this.loaded = Collections.unmodifiableMap(result);
        this.hasApplied = true;

        LOGGER.info("VariantManifestLoader: loaded {} manifest(s)",
                result.size());
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Returns the parsed manifest for {@code key}, or empty if no
     * {@code manifest.json} exists at that variant path. Callers that
     * want default-on-missing behaviour should fall back to
     * {@link VariantManifest#defaults(String)} themselves — that
     * concern belongs to the variant registry (P0a-03), not the
     * loader.
     */
    public Optional<VariantManifest> get(VariantKey key) {
        return Optional.ofNullable(loaded.get(key));
    }

    /** Read-only snapshot of all currently loaded manifests. */
    public Map<VariantKey, VariantManifest> all() {
        return loaded;
    }

    public boolean hasApplied() {
        return hasApplied;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Parses a resource path of the form
     * {@code structures/{culture}/{style}/{type}/{variantId}/manifest.json}
     * into a {@link VariantKey}. Returns empty when the path does not
     * match (different segment count, unknown {@link BuildingType},
     * etc.) — those cases are logged by the caller.
     */
    static Optional<VariantKey> keyFromPath(Identifier fileId) {
        String path = fileId.getPath();
        if (!path.endsWith(SUFFIX)) return Optional.empty();
        if (!path.startsWith(FOLDER + "/")) return Optional.empty();

        String[] parts = path.split("/");
        // Expect exactly: structures, {culture}, {style}, {type},
        // {variantId}, manifest.json — six segments.
        if (parts.length != 6) return Optional.empty();
        if (!"manifest.json".equals(parts[5])) return Optional.empty();

        String culture = parts[1];
        String style = parts[2];
        String typeFolder = parts[3];
        String variantId = parts[4];

        BuildingType type;
        try {
            type = BuildingType.valueOf(typeFolder.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        return Optional.of(new VariantKey(culture, style, type, variantId));
    }
}
