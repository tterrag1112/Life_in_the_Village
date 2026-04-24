package tterrag1112.life_in_the_village.Village.Decoration.Roads;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import tterrag1112.life_in_the_village.Village.Roads.Lighting.RoadLightingProfile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Static registry of per-culture {@link CulturePalette}s used by Phase 7g.
 *
 * <p>Palettes are registered at class load. Cultures not in the map resolve to
 * the {@link #forCulture "default"} palette, so callers don't need to handle
 * null culture strings. The Old Realm palette is always accessible via
 * {@link #oldRealm()} for great-road defaults.
 *
 * <h3>Great-road resolution</h3>
 * {@link #greatRoadPalette(String)} returns the Old Realm palette unless the
 * nearest culture has {@link CulturePalette#isGreatRoadAlternate()} set, in
 * which case that culture's palette is used for great roads. Imperial is the
 * only built-in alternate — producing stone-brick royal roads.
 */
public final class PaletteRegistry {

    private PaletteRegistry() {}

    // =========================================================================
    // Registry state
    // =========================================================================

    private static final Map<String, CulturePalette> PALETTES = new LinkedHashMap<>();
    private static final CulturePalette OLD_REALM = buildOldRealm();

    static {
        register(buildDefault());
        register(buildImperial());
        register(buildHighland());
        register(buildNordic());
    }

    /** Registers a palette by its cultureId, overwriting any previous entry. */
    public static void register(CulturePalette palette) {
        PALETTES.put(palette.cultureId(), palette);
    }

    // =========================================================================
    // Lookup
    // =========================================================================

    /**
     * Returns the palette for the given culture, falling back to the default
     * palette for null, empty, or unregistered cultures.
     */
    public static CulturePalette forCulture(String cultureId) {
        if (cultureId == null || cultureId.isEmpty()) return PALETTES.get("default");
        CulturePalette p = PALETTES.get(cultureId);
        return p != null ? p : PALETTES.get("default");
    }

    /** The static Old Realm palette used for great roads by default. */
    public static CulturePalette oldRealm() {
        return OLD_REALM;
    }

    /**
     * Resolves the palette for a {@code GREAT_ROAD} edge near the given culture.
     * Returns {@link #oldRealm()} unless the culture's palette has
     * {@link CulturePalette#isGreatRoadAlternate()} set.
     */
    public static CulturePalette greatRoadPalette(String nearestCultureId) {
        CulturePalette c = forCulture(nearestCultureId);
        return c.isGreatRoadAlternate() ? c : OLD_REALM;
    }

    /** All registered non-Old-Realm palettes (for debug listing). */
    public static Map<String, CulturePalette> all() {
        return Collections.unmodifiableMap(PALETTES);
    }

    // =========================================================================
    // Built-in palettes
    // =========================================================================

    private static CulturePalette buildDefault() {
        return new CulturePalette(
                "default",
                List.of(Blocks.DIRT_PATH, Blocks.COARSE_DIRT, Blocks.DIRT),
                List.of(Blocks.COARSE_DIRT, Blocks.GRAVEL),
                List.of(Blocks.COBBLESTONE),
                List.of(Blocks.STONE, Blocks.COBBLESTONE),
                List.of(Blocks.COBBLESTONE, Blocks.GRAVEL),
                List.of(Blocks.GRAVEL),
                new RoadLightingProfile(
                        RoadLightingProfile.Frequency.SPARSE,
                        RoadLightingProfile.Strategy.ALTERNATING_SIDES),
                Blocks.LANTERN,
                Blocks.POLISHED_ANDESITE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false
        );
    }

    private static CulturePalette buildImperial() {
        return new CulturePalette(
                "imperial",
                List.of(Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS),
                List.of(Blocks.CHISELED_STONE_BRICKS, Blocks.POLISHED_ANDESITE),
                List.of(Blocks.CRACKED_STONE_BRICKS),
                List.of(Blocks.POLISHED_ANDESITE, Blocks.STONE_BRICKS),
                List.of(Blocks.MOSSY_STONE_BRICKS, Blocks.STONE),
                List.of(Blocks.CHISELED_STONE_BRICKS),
                new RoadLightingProfile(
                        RoadLightingProfile.Frequency.MODERATE,
                        RoadLightingProfile.Strategy.BOTH_SIDES),
                Blocks.SEA_LANTERN,
                Blocks.POLISHED_ANDESITE,
                Optional.of(Blocks.STONE_BRICK_SLAB),
                Optional.empty(),
                Optional.empty(),
                true
        );
    }

    private static CulturePalette buildHighland() {
        return new CulturePalette(
                "highland",
                List.of(Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE),
                List.of(Blocks.STONE),
                List.of(Blocks.GRAVEL),
                List.of(Blocks.MOSSY_COBBLESTONE, Blocks.COBBLESTONE),
                List.of(Blocks.STONE),
                List.of(Blocks.GRAVEL),
                new RoadLightingProfile(
                        RoadLightingProfile.Frequency.SPARSE,
                        RoadLightingProfile.Strategy.SINGLE_SIDE_RIGHT),
                Blocks.LANTERN,
                Blocks.COBBLESTONE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false
        );
    }

    private static CulturePalette buildNordic() {
        return new CulturePalette(
                "nordic",
                List.of(Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_LOG),
                List.of(Blocks.POLISHED_GRANITE),
                List.of(Blocks.COARSE_DIRT),
                List.of(Blocks.COBBLESTONE, Blocks.SPRUCE_LOG),
                List.of(Blocks.SPRUCE_PLANKS),
                List.of(Blocks.STONE),
                new RoadLightingProfile(
                        RoadLightingProfile.Frequency.SPARSE,
                        RoadLightingProfile.Strategy.FOREST_ONLY_BOTH),
                Blocks.LANTERN,
                Blocks.SPRUCE_FENCE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false
        );
    }

    /**
     * Old Realm palette applied to great roads by default. Darker retaining
     * wall palette for visual separation from the road-proper supports.
     */
    private static CulturePalette buildOldRealm() {
        return new CulturePalette(
                "old_realm",
                List.of(Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.COBBLESTONE),
                List.of(Blocks.MOSSY_STONE_BRICKS, Blocks.MOSSY_COBBLESTONE),
                List.of(Blocks.CRACKED_STONE_BRICKS),
                List.of(Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS),
                List.of(Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE),
                List.of(Blocks.CRACKED_STONE_BRICKS),
                new RoadLightingProfile(
                        RoadLightingProfile.Frequency.MODERATE,
                        RoadLightingProfile.Strategy.ALTERNATING_SIDES),
                Blocks.SOUL_LANTERN,
                Blocks.POLISHED_BLACKSTONE,
                Optional.empty(),
                Optional.of(List.of(Blocks.MOSSY_STONE_BRICKS, Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE)),
                Optional.of(List.of(Blocks.CRACKED_STONE_BRICKS)),
                false
        );
    }
}
