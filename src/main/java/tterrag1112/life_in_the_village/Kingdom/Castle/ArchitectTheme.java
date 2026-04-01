package tterrag1112.life_in_the_village.Kingdom.Castle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.RandomSource;

import java.util.List;

/**
 * A named collection of wall designs and a default palette.
 *
 * In TMA terms, a theme defines the architectural vocabulary of a style:
 *   - Which wall designs are available (randomly selected per segment)
 *   - Which tower designs are available
 *   - The default palette to use when none is specified
 *
 * Multiple themes can share the same palette (same material, different pattern).
 * The same theme can use different palettes (different material, same pattern).
 */
public record ArchitectTheme(
        String id,

        // Wall designs — one is randomly selected per wall segment
        // More designs = more variety between wall panels
        List<String> wallDesignIds,

        // Tower wall design — applied to the faces of rectangular towers
        String towerDesignId,

        // Donjon design — applied to the keep faces
        String donjeonDesignId,

        // The built-in palette for this theme (can be overridden per style)
        String defaultPaletteId

) {

    // =========================================================================
    // Design resolution
    // =========================================================================

    /**
     * Pick a random wall design from this theme's pool.
     * More variety in the pool = more visual diversity between segments.
     */
    public WallDesign pickWallDesign(WallDesignRegistry registry, RandomSource rng) {
        if (wallDesignIds.isEmpty()) return WallDesign.PLAIN;
        String id = wallDesignIds.get(rng.nextInt(wallDesignIds.size()));
        return registry.get(id);
    }

    public WallDesign getTowerDesign(WallDesignRegistry registry) {
        return registry.get(towerDesignId);
    }

    public WallDesign getDonjeonDesign(WallDesignRegistry registry) {
        return registry.get(donjeonDesignId);
    }

    // =========================================================================
    // Built-in themes — one per castle style
    // =========================================================================

    public static final ArchitectTheme NORMAN = new ArchitectTheme(
            "norman",
            List.of("norman", "norman_heavy", "plain"),
            "norman_heavy",
            "norman_heavy",
            "stone_brick");

    public static final ArchitectTheme MOORISH = new ArchitectTheme(
            "moorish",
            List.of("moorish", "banded", "pilastered"),
            "banded",
            "pilastered",
            "sandstone");

    public static final ArchitectTheme FANTASY = new ArchitectTheme(
            "fantasy",
            List.of("pilastered", "dark", "banded"),
            "dark",
            "pilastered",
            "deepslate");

    public static final ArchitectTheme RUIN = new ArchitectTheme(
            "ruin",
            List.of("ruined", "plain"),
            "ruined",
            "ruined",
            "stone_brick");

    public static final ArchitectTheme JAPANESE = new ArchitectTheme(
            "japanese",
            List.of("japanese", "plain"),
            "japanese",
            "japanese",
            "quartz");

    public static final ArchitectTheme FRENCH_GOTHIC = new ArchitectTheme(
            "french_gothic",
            List.of("pilastered", "banded", "norman_heavy"),
            "pilastered",
            "pilastered",
            "stone_brick");

    public static final ArchitectTheme BYZANTINE = new ArchitectTheme(
            "byzantine",
            List.of("banded", "moorish", "pilastered"),
            "banded",
            "banded",
            "stone_brick");

    public static final ArchitectTheme EDWARDIAN = new ArchitectTheme(
            "edwardian",
            List.of("norman", "norman_heavy", "plain"),
            "norman_heavy",
            "norman_heavy",
            "stone_brick");

    public static final ArchitectTheme MUGHAL = new ArchitectTheme(
            "mughal",
            List.of("moorish", "banded", "pilastered"),
            "banded",
            "pilastered",
            "red_sandstone");

    public static final ArchitectTheme DARK_LORD = new ArchitectTheme(
            "dark_lord",
            List.of("dark", "plain", "dwarven"),
            "dark",
            "dark",
            "blackstone");

    public static final ArchitectTheme ELVISH = new ArchitectTheme(
            "elvish",
            List.of("elvish", "plain", "japanese"),
            "elvish",
            "elvish",
            "oak_wood");

    public static final ArchitectTheme DWARVEN = new ArchitectTheme(
            "dwarven",
            List.of("dwarven", "norman_heavy", "plain"),
            "dwarven",
            "dwarven",
            "deepslate");

    public static final ArchitectTheme SEA_FORT = new ArchitectTheme(
            "sea_fort",
            List.of("norman", "plain"),
            "norman",
            "norman",
            "prismarine");

    public static final ArchitectTheme ANCIENT_RUIN = new ArchitectTheme(
            "ancient_ruin",
            List.of("ruined"),
            "ruined",
            "ruined",
            "stone_brick");

    public static final ArchitectTheme UNDEAD = new ArchitectTheme(
            "undead",
            List.of("ruined", "dark"),
            "dark",
            "dark",
            "quartz");

    public static final ArchitectTheme CRYSTAL_SPIRE = new ArchitectTheme(
            "crystal_spire",
            List.of("pilastered", "elvish", "banded"),
            "pilastered",
            "pilastered",
            "quartz");
    /**
     * Cattingham — formal symmetrical palace with conical towers,
     * strong string courses, pilasters, and glass windows.
     */
    public static final ArchitectTheme CATTINGHAM = new ArchitectTheme(
            "cattingham",
            List.of("formal_palace", "grand_palace"),
            "formal_palace",
            "grand_palace",
            "formal_palace");

    /**
     * Grand Palace — more ornate than Cattingham, quartz accents,
     * very clean bright aesthetic. Suitable for royal capital.
     */
    public static final ArchitectTheme GRAND_PALACE = new ArchitectTheme(
            "grand_palace",
            List.of("grand_palace", "formal_palace"),
            "grand_palace",
            "grand_palace",
            "grand_palace");

    /**
     * Rustic Manor — smaller, warmer, less fortified.
     * Country estate rather than military castle.
     */
    public static final ArchitectTheme RUSTIC_MANOR = new ArchitectTheme(
            "rustic_manor",
            List.of("rustic_manor", "norman", "plain"),
            "rustic_manor",
            "rustic_manor",
            "rustic_manor");
}