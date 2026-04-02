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
 *   structures/{culture}/{building_type}/level_{n}.nbt
 *
 *   Examples:
 *     structures/default/blacksmith/level_1.nbt
 *     structures/default/house/level_1.nbt
 *     structures/nordic/blacksmith/level_1.nbt
 *     structures/highland/guard_tower/level_2.nbt
 * </pre>
 *
 * <h3>Fallback chain</h3>
 * If a culture doesn't have a template for a specific building/level:
 * <ol>
 *   <li>Try {@code {culture}/{type}/level_{n}.nbt}</li>
 *   <li>Try {@code {culture}/{type}/level_1.nbt} (downgrade level)</li>
 *   <li>Try {@code default/{type}/level_{n}.nbt} (fallback culture)</li>
 *   <li>Try {@code default/{type}/level_1.nbt} (fallback both)</li>
 *   <li>Try legacy path {@code {type}/level_{n}.nbt} (no culture prefix)</li>
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
     * @param culture       culture id
     * @param structurePath raw path from JSON (e.g. "blacksmith/level_1")
     * @param world         server level
     * @return resolved identifier
     */
    public static Identifier resolveFromPath(String culture,
                                             String structurePath,
                                             ServerLevel world) {
        // If path already has a culture prefix, use as-is
        if (structurePath.contains("/") && !structurePath.startsWith(culture + "/")) {
            // Try with culture prefix first
            Identifier withCulture = buildId(culture + "/" + structurePath);
            if (templateExists(world, withCulture)) return withCulture;
        }

        // Try direct path (legacy)
        Identifier direct = buildId(structurePath);
        if (templateExists(world, direct)) return direct;

        // Try with culture prefix
        Identifier withCulture = buildId(culture + "/" + structurePath);
        if (templateExists(world, withCulture)) return withCulture;

        // Try default culture
        Identifier withDefault = buildId(DEFAULT_CULTURE + "/" + structurePath);
        if (templateExists(world, withDefault)) return withDefault;

        // Fallback to direct path even if it doesn't exist —
        // BuildingPlacer will log the error
        return direct;
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

        // 1. Exact: {culture}/{type}/level_{n}
        Identifier exact = buildId(culture + "/" + typePath + "/level_" + level);
        if (templateExists(world, exact)) return exact;

        // 2. Downgrade level: {culture}/{type}/level_1
        if (level > 1) {
            Identifier downgrade = buildId(culture + "/" + typePath + "/level_1");
            if (templateExists(world, downgrade)) return downgrade;
        }

        // 3. Fallback culture: default/{type}/level_{n}
        if (!DEFAULT_CULTURE.equals(culture)) {
            Identifier fallback = buildId(DEFAULT_CULTURE + "/" + typePath + "/level_" + level);
            if (templateExists(world, fallback)) return fallback;

            // 4. Fallback both: default/{type}/level_1
            if (level > 1) {
                Identifier fallbackDown = buildId(DEFAULT_CULTURE + "/" + typePath + "/level_1");
                if (templateExists(world, fallbackDown)) return fallbackDown;
            }
        }

        // 5. Legacy path (no culture prefix): {type}/level_{n}
        Identifier legacy = buildId(typePath + "/level_" + level);
        if (templateExists(world, legacy)) return legacy;

        // 6. Legacy level 1
        if (level > 1) {
            Identifier legacyDown = buildId(typePath + "/level_1");
            if (templateExists(world, legacyDown)) return legacyDown;
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
            // A culture "exists" if it has at least a town_hall/level_1
            Identifier probe = buildId(culture + "/town_hall/level_1");
            if (templateExists(world, probe)) {
                found.add(culture);
            }
        }
        return found;
    }
}