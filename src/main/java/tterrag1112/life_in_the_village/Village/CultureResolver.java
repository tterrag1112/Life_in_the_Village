package tterrag1112.life_in_the_village.Village;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;

import java.util.*;

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
 *     structures/default/rural/blacksmith/blacksmith/level_1.nbt
 *     structures/default/rural/house/house/level_1.nbt
 *     structures/default/rural/guard_tower/guard_tower/level_1.nbt
 * </pre>
 *
 * <p>This path layout is the post-P0a-01 migration form. The full
 * variant-aware fallback chain (separate {@code {culture}/{style}/
 * {type}/{variant}/} resolution with cross-style and cross-culture
 * fallbacks) is added in P0a-04. For now this resolver only constructs
 * the default-variant path of the form {@code
 * {culture}/rural/{type}/{type}/level_{n}}.</p>
 *
 * <h3>Fallback chain (interim — pre-P0a-04)</h3>
 * If a culture doesn't have a template for a specific building/level:
 * <ol>
 *   <li>Try {@code {culture}/rural/{type}/{type}/level_{n}.nbt}</li>
 *   <li>Try {@code {culture}/rural/{type}/{type}/level_1.nbt} (downgrade level)</li>
 *   <li>Try {@code default/rural/{type}/{type}/level_{n}.nbt} (fallback culture)</li>
 *   <li>Try {@code default/rural/{type}/{type}/level_1.nbt} (fallback both)</li>
 * </ol>
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

    private static final String MOD_ID = Life_in_the_village.MODID;
    private static final String DEFAULT_CULTURE = "default";

    /**
     * Cache of confirmed-existing template paths. Avoids repeated
     * resource manager lookups for the same culture/type/level combo.
     * Cleared on resource reload (if needed).
     */
    private static final Map<String, Identifier> CACHE = new HashMap<>();

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
        String cacheKey = culture + "/" + type.name() + "/" + level;
        Identifier cached = CACHE.get(cacheKey);
        if (cached != null) return cached;

        Identifier resolved = resolveInternal(culture, type, level, world);
        CACHE.put(cacheKey, resolved);
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
     * (BuildingRegistry / VillageTypeBuilder use that form). This
     * method translates them into the new variant-aware physical
     * layout {@code {culture}/rural/{type}/{type}/level_{n}} before
     * resolving. The richer variant-aware resolution happens in
     * P0a-04.</p>
     *
     * @param culture       culture id
     * @param structurePath raw path from JSON (e.g. "blacksmith/level_1")
     * @param world         server level
     * @return resolved identifier
     */
    public static Identifier resolveFromPath(String culture,
                                             String structurePath,
                                             ServerLevel world) {
        String variantAwarePath = toVariantAwarePath(structurePath);

        // Try requested culture
        Identifier withCulture = buildId(culture + "/" + variantAwarePath);
        if (templateExists(world, withCulture)) return withCulture;

        // Try default culture fallback
        Identifier withDefault = buildId(DEFAULT_CULTURE + "/" + variantAwarePath);
        if (templateExists(world, withDefault)) return withDefault;

        // Return the requested-culture path even if it doesn't exist —
        // BuildingPlacer will log the error
        return withCulture;
    }

    /**
     * Converts a legacy {@code {type}/level_{n}} path into the new
     * variant-aware {@code rural/{type}/{type}/level_{n}} layout. Paths
     * that don't match the legacy shape pass through unchanged so
     * already-rewritten or non-building paths (e.g. market sub-pieces)
     * keep working.
     *
     * <p>Public so {@link tterrag1112.life_in_the_village.Village.Planning
     * .StructureSizeCache} can reuse the same translation.</p>
     */
    public static String toVariantAwarePath(String legacyPath) {
        if (legacyPath == null || legacyPath.isEmpty()) return legacyPath;
        if (legacyPath.startsWith("rural/") || legacyPath.startsWith("urban/")) {
            return legacyPath;
        }
        int slash = legacyPath.indexOf('/');
        if (slash <= 0) return legacyPath;
        String firstSeg = legacyPath.substring(0, slash);
        // Only rewrite the canonical {type}/level_{n} shape; leave
        // anything more complex (e.g. market/stall/stall_1) untouched.
        String remainder = legacyPath.substring(slash + 1);
        if (remainder.contains("/")) return legacyPath;
        if (!remainder.startsWith("level_")) return legacyPath;
        return "rural/" + firstSeg + "/" + legacyPath;
    }

    /**
     * Clears the template cache. Call on resource reload or when
     * datapacks change.
     */
    public static void clearCache() {
        CACHE.clear();
    }

    // =========================================================================
    // Internal resolution with fallback chain
    // =========================================================================

    private static Identifier resolveInternal(String culture,
                                              BuildingType type,
                                              int level,
                                              ServerLevel world) {
        String typePath = type.name().toLowerCase();
        String variantTail = "rural/" + typePath + "/" + typePath;

        // 1. Exact: {culture}/rural/{type}/{type}/level_{n}
        Identifier exact = buildId(culture + "/" + variantTail + "/level_" + level);
        if (templateExists(world, exact)) return exact;

        // 2. Downgrade level: {culture}/rural/{type}/{type}/level_1
        if (level > 1) {
            Identifier downgrade = buildId(culture + "/" + variantTail + "/level_1");
            if (templateExists(world, downgrade)) return downgrade;
        }

        // 3. Fallback culture: default/rural/{type}/{type}/level_{n}
        if (!DEFAULT_CULTURE.equals(culture)) {
            Identifier fallback = buildId(DEFAULT_CULTURE + "/" + variantTail + "/level_" + level);
            if (templateExists(world, fallback)) return fallback;

            // 4. Fallback both: default/rural/{type}/{type}/level_1
            if (level > 1) {
                Identifier fallbackDown = buildId(DEFAULT_CULTURE + "/" + variantTail + "/level_1");
                if (templateExists(world, fallbackDown)) return fallbackDown;
            }
        }

        // Return the exact path even if it doesn't exist — let the
        // caller handle the missing template error
        return exact;
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