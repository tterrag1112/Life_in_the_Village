package tterrag1112.life_in_the_village.Village;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.BuildingVariant;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves building structure template paths based on culture and biome.
 *
 * <h3>Architecture separation</h3>
 * <ul>
 *   <li><b>Culture</b> = architectural form (floor plan, roof shape,
 *       proportions, window placement). Determined by village type data.
 *       Controls which .nbt template file is loaded.</li>
 *   <li><b>Biome style</b> = material palette (wood species, stone type,
 *       path blocks, lantern type). Determined by world biome at placement.
 *       Applied as a block-swap pass AFTER the template is placed.</li>
 * </ul>
 *
 * These two axes are orthogonal. A "nordic" culture blacksmith in a taiga
 * biome uses the nordic floor plan with spruce/stone-brick materials. The
 * same nordic blacksmith in a plains biome uses the same floor plan with
 * oak/cobblestone materials.
 *
 * <h3>Template path convention</h3>
 * <pre>
 *   structures/{culture}/{style}/{building_type}/{variant}/level_{n}.nbt
 *
 *   Examples:
 *     structures/default/rural/blacksmith/blacksmith/well_hamlet.nbt
 *     structures/default/rural/house/house/well_hamlet.nbt
 *     structures/default/rural/guard_tower/guard_tower/well_hamlet.nbt
 * </pre>
 *
 * <h3>Fallback chain (P0a-04)</h3>
 * For a lookup keyed by {@code (culture, style, type, variantId, level)}
 * the resolver walks doc 15's seven-step chain:
 * <ol>
 *   <li>{@code {culture}/{style}/{type}/{variantId}/level_{n}.nbt}</li>
 *   <li>{@code {culture}/{type}/{variantId}/level_{n}.nbt} — style-agnostic culture variant</li>
 *   <li>{@code default/{style}/{type}/{variantId}/level_{n}.nbt} — default culture, same style</li>
 *   <li>{@code default/{type}/{variantId}/level_{n}.nbt} — default culture, style-agnostic</li>
 *   <li>{@code default/{style}/{type}/{type}/level_{n}.nbt} — default-variant fallback</li>
 *   <li>{@code default/{type}/{type}/level_{n}.nbt} — default-variant, style-agnostic</li>
 *   <li>fail with logged error — no NBT found anywhere</li>
 * </ol>
 * Steps 5–6 ensure the matcher always has a placement candidate when
 * a culture omits a variant entirely.
 *
 * <h3>Registration</h3>
 * Cultures are discovered automatically from the village type JSON
 * ("culture" field). No manual registration needed — the resolver
 * builds paths dynamically and relies on the resource system to
 * check existence.
 *
 * <h3>Usage</h3>
 * <pre>
 * // In BuildingPlacer or VillageSpawner:
 * Identifier templateId = CultureResolver.resolve(
 *     "nordic", BuildingType.BLACKSMITH, 1, level);
 *
 * Optional&lt;StructureTemplate&gt; template =
 *     BuildingPlacer.loadTemplate(level, templateId);
 * </pre>
 */
public final class CultureResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(CultureResolver.class);
    private static final String MOD_ID = Life_in_the_village.MODID;
    private static final String DEFAULT_CULTURE = "default";

    /**
     * Cache of confirmed-existing template paths. Keyed by the
     * canonical lookup tuple so cross-culture / cross-style fallbacks
     * still cache. Cleared on resource reload via {@link #clearCache}.
     */
    private static final Map<String, Identifier> CACHE = new ConcurrentHashMap<>();

    /**
     * Tracks (type, variant) combinations that have already triggered
     * a default-of-default warning so the log doesn't spam once per
     * placement. Cleared when {@link #clearCache} runs.
     */
    private static final Set<String> WARNED_DEFAULT_FALLBACK =
            ConcurrentHashMap.newKeySet();

    private CultureResolver() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Resolves the best available structure template for the given
     * culture, building type, and level.
     *
     * @param culture  the culture id (e.g. "default", "nordic", "highland")
     * @param type     the building type
     * @param level    the building level (1, 2, 3...)
     * @param world    server level for resource existence checks
     * @return a ResourceLocation that can be passed to BuildingPlacer.loadTemplate
     */
    public static Identifier resolve(String culture,
                                     BuildingType type,
                                     int level,
                                     ServerLevel world) {
        // Thin wrapper around the variant-aware resolver. Callers that
        // don't yet pass a style/variant default to the type's RURAL
        // default-variant, matching the post-P0a-01 layout.
        return resolve(culture, Style.RURAL, type,
                BuildingVariant.defaultVariantId(type), level, world);
    }

    /**
     * Doc 15 seven-step variant-aware resolver.
     *
     * @param culture    requested culture id
     * @param style      requested folder style ({@link Style#RURAL} /
     *                   {@link Style#URBAN}); auto-derivation is
     *                   P0a-07, callers pass {@link Style#RURAL}
     *                   explicitly until then
     * @param type       building type
     * @param variantId  variant folder id (use {@link BuildingVariant
     *                   #defaultVariantId} for the default)
     * @param level      building level
     * @param world      server level for resource existence checks
     */
    public static Identifier resolve(String culture,
                                     Style style,
                                     BuildingType type,
                                     String variantId,
                                     int level,
                                     ServerLevel world) {
        String cacheKey = culture + "/" + style.folder() + "/"
                + type.name() + "/" + variantId + "/" + level;
        Identifier cached = CACHE.get(cacheKey);
        if (cached != null) return cached;

        Identifier resolved = resolveInternal(
                culture, style, type, variantId, level, world);
        if (resolved != null) CACHE.put(cacheKey, resolved);
        return resolved;
    }

    /**
     * Resolves using the culture from a village type's JSON data.
     * Convenience method for the common case.
     */
    public static Identifier resolve(VillageTypeData typeData,
                                     BuildingType type,
                                     int level,
                                     ServerLevel world) {
        String culture = typeData.getCulture() != null
                ? typeData.getCulture() : DEFAULT_CULTURE;
        return resolve(culture, type, level, world);
    }

    /**
     * Returns the structure path that a building's starter_buildings
     * JSON entry maps to, respecting the culture prefix.
     *
     * <p>Inputs are still in the legacy {@code {type}/level_{n}} shape
     * (BuildingRegistry / VillageTypeBuilder use that form). When the
     * input matches that shape, this method routes through the full
     * variant-aware seven-step resolver with style=RURAL and
     * variantId=type-default. Non-canonical paths (e.g. the market-
     * stall sub-piece) pass through with the previous culture / default
     * lookup so unrelated subsystems are unaffected.</p>
     *
     * @param culture       culture id
     * @param structurePath raw path from JSON (e.g. "blacksmith/level_1")
     * @param world         server level
     * @return resolved identifier
     */
    public static Identifier resolveFromPath(String culture,
                                             String structurePath,
                                             ServerLevel world) {
        LegacyTypeLevel parsed = parseLegacyTypeLevel(structurePath);
        if (parsed != null) {
            return resolve(culture, Style.RURAL, parsed.type,
                    BuildingVariant.defaultVariantId(parsed.type),
                    parsed.level, world);
        }

        // Non-canonical path: simple culture / default lookup.
        Identifier withCulture = buildId(culture + "/" + structurePath);
        if (templateExists(world, withCulture)) return withCulture;

        Identifier withDefault = buildId(DEFAULT_CULTURE + "/" + structurePath);
        if (templateExists(world, withDefault)) return withDefault;

        return withCulture;
    }

    /**
     * Parses a path of the form {@code {type}/level_{n}} into
     * {@code (BuildingType, level)}; returns {@code null} when the
     * path doesn't match (different segment count, unknown type, etc.).
     * Public so {@link tterrag1112.life_in_the_village.Village.Planning
     * .StructureSizeCache} can reuse the same parse for its bridging
     * overload.
     */
    public static LegacyTypeLevel parseLegacyTypeLevel(String legacyPath) {
        if (legacyPath == null || legacyPath.isEmpty()) return null;
        int slash = legacyPath.indexOf('/');
        if (slash <= 0) return null;
        String typeSeg = legacyPath.substring(0, slash);
        String rest = legacyPath.substring(slash + 1);
        if (rest.contains("/")) return null;
        if (!rest.startsWith("level_")) return null;
        int level;
        try {
            level = Integer.parseInt(rest.substring("level_".length()));
        } catch (NumberFormatException e) {
            return null;
        }
        BuildingType type;
        try {
            type = BuildingType.valueOf(typeSeg.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        return new LegacyTypeLevel(type, level);
    }

    /** Parsed result from {@link #parseLegacyTypeLevel}. */
    public record LegacyTypeLevel(BuildingType type, int level) {}

    /**
     * Clears the template cache and the dedupe set used by the
     * default-of-default warning. Call on resource reload or when
     * datapacks change.
     */
    public static void clearCache() {
        CACHE.clear();
        WARNED_DEFAULT_FALLBACK.clear();
    }

    // =========================================================================
    // Internal resolution with fallback chain
    // =========================================================================

    /**
     * Doc 15's seven-step fallback chain. Returns the first existing
     * NBT identifier; if step 7 is reached, logs a detailed error and
     * returns the step-1 identifier so callers see a single
     * deterministic "missing template" failure path. The step-5/6
     * default-of-default fallback emits a one-time warning per
     * (type, variant) combo.
     */
    private static Identifier resolveInternal(String culture,
                                              Style style,
                                              BuildingType type,
                                              String variantId,
                                              int level,
                                              ServerLevel world) {
        String typeFolder = type.name().toLowerCase();
        String defaultVariant = BuildingVariant.defaultVariantId(type);
        String levelTail = "/level_" + level;
        String styleSeg = style.folder();

        // Step 1: {culture}/{style}/{type}/{variant}/level_{n}
        Identifier step1 = buildId(culture + "/" + styleSeg + "/"
                + typeFolder + "/" + variantId + levelTail);
        if (templateExists(world, step1)) return step1;

        // Step 2: {culture}/{type}/{variant}/level_{n}  (style-agnostic)
        Identifier step2 = buildId(culture + "/"
                + typeFolder + "/" + variantId + levelTail);
        if (templateExists(world, step2)) return step2;

        // Step 3: default/{style}/{type}/{variant}/level_{n}
        Identifier step3 = null;
        if (!DEFAULT_CULTURE.equals(culture)) {
            step3 = buildId(DEFAULT_CULTURE + "/" + styleSeg + "/"
                    + typeFolder + "/" + variantId + levelTail);
            if (templateExists(world, step3)) return step3;
        }

        // Step 4: default/{type}/{variant}/level_{n}  (style-agnostic)
        Identifier step4 = null;
        if (!DEFAULT_CULTURE.equals(culture)) {
            step4 = buildId(DEFAULT_CULTURE + "/"
                    + typeFolder + "/" + variantId + levelTail);
            if (templateExists(world, step4)) return step4;
        }

        // Steps 5–6 fall through to the default-variant. The one-time
        // warning fires when one of them actually matches — that's the
        // useful diagnostic ("authored variant missing, used default").
        // If neither matches, step 7 logs the harder error instead.

        // Step 5: default/{style}/{type}/{type}/level_{n}
        Identifier step5 = buildId(DEFAULT_CULTURE + "/" + styleSeg + "/"
                + typeFolder + "/" + defaultVariant + levelTail);
        if (templateExists(world, step5)) {
            warnDefaultVariantFallback(type, variantId, culture, style, level);
            return step5;
        }

        // Step 6: default/{type}/{type}/level_{n}  (style-agnostic)
        Identifier step6 = buildId(DEFAULT_CULTURE + "/"
                + typeFolder + "/" + defaultVariant + levelTail);
        if (templateExists(world, step6)) {
            warnDefaultVariantFallback(type, variantId, culture, style, level);
            return step6;
        }

        // Step 7: hard fail
        LOGGER.error("CultureResolver: no NBT found for ({}, {}, {}, {}) "
                + "at level {}. Tried: {}, {}, {}, {}, {}, {}",
                culture, styleSeg, type.name(), variantId, level,
                step1.getPath(),
                step2.getPath(),
                step3 != null ? step3.getPath() : "(skipped: culture is default)",
                step4 != null ? step4.getPath() : "(skipped: culture is default)",
                step5.getPath(),
                step6.getPath());
        return step1;
    }

    private static void warnDefaultVariantFallback(BuildingType type,
                                                   String variantId,
                                                   String culture,
                                                   Style style,
                                                   int level) {
        String warnKey = type.name() + ":" + variantId;
        if (WARNED_DEFAULT_FALLBACK.add(warnKey)) {
            LOGGER.warn("CultureResolver: variant ({}, {}) not found for "
                    + "culture='{}' style={} level={}; falling back to "
                    + "default-variant. Authored content recommended to "
                    + "avoid silent placeholder placement.",
                    type.name(), variantId, culture, style.folder(), level);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Identifier buildId(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    /**
     * Checks if a structure template .nbt file exists in the resource system.
     */
    private static boolean templateExists(ServerLevel world, Identifier id) {
        try {
            var resource = world.getServer().getResourceManager()
                    .getResource(Identifier.fromNamespaceAndPath(
                            id.getNamespace(),
                            "structures/" + id.getPath() + ".nbt"));
            return resource.isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    // =========================================================================
    // Culture metadata (for UI/debug)
    // =========================================================================

    /**
     * Returns all cultures that have at least one template present.
     * Useful for the village type editor or debug commands.
     */
    public static List<String> discoverCultures(ServerLevel world) {
        // Check known culture directories
        String[] candidates = {
                "default", "nordic", "highland", "imperial",
                "desert", "jungle", "coastal"
        };

        List<String> found = new ArrayList<>();
        for (String culture : candidates) {
            // A culture "exists" if it has at least a default-variant
            // town_hall in the rural style.
            Identifier probe = buildId(culture + "/rural/town_hall/town_hall/level_1");
            if (templateExists(world, probe)) {
                found.add(culture);
            }
        }
        return found;
    }
}