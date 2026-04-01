package tterrag1112.life_in_the_village.Kingdom.Castle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StringRepresentable;

/**
 * Visual detail configuration for a CastleStyle.
 * Drives all procedural fallback generation — the code that runs when
 * no NBT template is found. Getting this right means every style looks
 * architecturally distinct even with zero NBT files.
 *
 * Added as the 14th field on CastleStyle (still within 16-field codec limit).
 */
public record StyleDetailConfig(

        // --- Battlement pattern ---
        BattlementStyle battlementStyle,

        // --- Tower roof shape ---
        TowerRoofStyle towerRoofStyle,

        // --- Gate arch profile ---
        ArchStyle archStyle,

        // --- Window / arrow slit type ---
        WindowStyle windowStyle,

        // --- Wall surface decoration ---
        WallDecoStyle wallDecoration,

        // --- String course interval (0 = none) ---
        // A string course is a horizontal band of accent blocks
        // running around the wall at this interval in blocks of height
        int stringCourseInterval,

        // --- Tower base batter ---
        // If true, the tower base is 1 block wider per 3 blocks of height
        // for the bottom third of the tower, giving a tapered foot
        boolean hasBatteredBase,

        // --- Quoins ---
        // Accent blocks placed at tower corners for visual definition
        boolean hasQuoins,

        // --- Courtyard ground treatment ---
        CourtyardStyle courtyardStyle,

        // --- Merlon width (1 or 2 blocks wide) ---
        int merlonWidth,

        // --- Merlon height (1 or 2 blocks tall above wall top) ---
        int merlonHeight

) {

    // =========================================================================
    // Enums
    // =========================================================================

    public enum BattlementStyle implements StringRepresentable {
        /**
         * Standard alternating single-wide merlons and crenels.
         * Norman, Edwardian, Sea Fort.
         */
        CRENELLATED,

        /**
         * Merlons step up in a staircase profile.
         * Moorish, Mughal, Byzantine.
         */
        STEPPED,

        /**
         * Merlons taper to a point two blocks tall — gothic lancet shape.
         * French Gothic, Fantasy, Crystal Spire.
         */
        POINTED,

        /**
         * Two-block-wide merlons with one-block gaps.
         * Heavier, more fortified look. Dwarven, Dark Lord.
         */
        WIDE,

        /**
         * Solid parapet with no gaps — accent slab on top.
         * Japanese, Elvish.
         */
        PARAPET,

        /**
         * Irregular missing/broken merlons scaled by ruinationLevel.
         * Ruin, Ancient Ruin, Undead.
         */
        RUINED;

        public static final Codec<BattlementStyle> CODEC =
                StringRepresentable.fromEnum(BattlementStyle::values);

        @Override
        public String getSerializedName() { return name().toLowerCase(); }
    }

    public enum TowerRoofStyle implements StringRepresentable {
        /**
         * Flat slab ring around the cap. Low, defensive.
         * Norman, Edwardian, Sea Fort, Dwarven.
         */
        FLAT,

        /**
         * Four-sided pyramid of stair blocks tapering to a point.
         * Norman with donjon, Moorish, Byzantine.
         */
        PYRAMIDAL,

        /**
         * Narrow column tapering upward — very tall relative to radius.
         * Fantasy, Dark Lord, Crystal Spire.
         */
        SPIRE,

        /**
         * Conical ring of stair blocks — rounder than pyramidal.
         * French Gothic round towers.
         */
        CONICAL,

        /**
         * Multi-tier overhanging slabs stepping outward then back in.
         * Approximates a pagoda profile.
         * Japanese.
         */
        PAGODA,

        /**
         * Hemispherical dome approximated with layered circles.
         * Byzantine.
         */
        DOME,

        /**
         * Broken, irregular cap — some blocks missing.
         * Ruin, Ancient Ruin, Undead.
         */
        RUINED;

        public static final Codec<TowerRoofStyle> CODEC =
                StringRepresentable.fromEnum(TowerRoofStyle::values);

        @Override
        public String getSerializedName() { return name().toLowerCase(); }
    }

    public enum ArchStyle implements StringRepresentable {
        /**
         * Semicircular arch. Roman/Norman/Byzantine.
         */
        ROUND,

        /**
         * Two arcs meeting at a point. Gothic/Fantasy/French.
         */
        POINTED,

        /**
         * Arch widens below the apex before narrowing — Moorish keyhole.
         * Moorish, Mughal.
         */
        HORSESHOE,

        /**
         * Flat lintel — rectangular opening with no curve.
         * Dwarven, Japanese, Sea Fort.
         */
        FLAT,

        /**
         * Three-centred flat/Tudor arch — wide and only slightly curved.
         * Edwardian, Elvish.
         */
        TUDOR;

        public static final Codec<ArchStyle> CODEC =
                StringRepresentable.fromEnum(ArchStyle::values);

        @Override
        public String getSerializedName() { return name().toLowerCase(); }
    }

    public enum WindowStyle implements StringRepresentable {
        /**
         * Standard 1×2 vertical slit.
         */
        ARROW_SLIT,

        /**
         * Cross-shaped cutout — vertical slit with a horizontal one crossing it.
         */
        CROSS_SLIT,

        /**
         * 2×3 opening with a stair block above for the arch.
         * More light, more decorative.
         */
        WIDE_ARCH,

        /**
         * Single circular (2×2 diamond) opening.
         * Byzantine, Moorish.
         */
        CIRCULAR,

        /**
         * No windows cut into the wall — solid face.
         * Dwarven, Dark Lord.
         */
        NONE;

        public static final Codec<WindowStyle> CODEC =
                StringRepresentable.fromEnum(WindowStyle::values);

        @Override
        public String getSerializedName() { return name().toLowerCase(); }
    }

    public enum WallDecoStyle implements StringRepresentable {
        /**
         * No surface decoration — plain blocks.
         */
        NONE,

        /**
         * Horizontal bands of accent material at string course interval.
         */
        STRING_COURSE,

        /**
         * Accent blocks at tower corners (quoins).
         * Applied to tower ring at corners.
         */
        QUOINS,

        /**
         * Both string courses and quoins.
         */
        BANDED,

        /**
         * Vertical pilaster strips of accent material every N blocks.
         * Byzantine, Moorish.
         */
        PILASTERS;

        public static final Codec<WallDecoStyle> CODEC =
                StringRepresentable.fromEnum(WallDecoStyle::values);

        @Override
        public String getSerializedName() { return name().toLowerCase(); }
    }

    public enum CourtyardStyle implements StringRepresentable {
        /**
         * Bare dirt or stone — no treatment.
         */
        BARE,

        /**
         * Paved with floor material.
         */
        PAVED,

        /**
         * Grass + flowers + occasional tree.
         */
        GARDEN,

        /**
         * Paved with a central water feature (fountain approximated
         * by a 3×3 water pool with stone brick surround).
         */
        FOUNTAIN;

        public static final Codec<CourtyardStyle> CODEC =
                StringRepresentable.fromEnum(CourtyardStyle::values);

        @Override
        public String getSerializedName() { return name().toLowerCase(); }
    }

    // =========================================================================
    // Codec
    // =========================================================================

    public static final Codec<StyleDetailConfig> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    BattlementStyle.CODEC
                            .fieldOf("battlement_style").forGetter(StyleDetailConfig::battlementStyle),
                    TowerRoofStyle.CODEC
                            .fieldOf("tower_roof_style").forGetter(StyleDetailConfig::towerRoofStyle),
                    ArchStyle.CODEC
                            .fieldOf("arch_style").forGetter(StyleDetailConfig::archStyle),
                    WindowStyle.CODEC
                            .fieldOf("window_style").forGetter(StyleDetailConfig::windowStyle),
                    WallDecoStyle.CODEC
                            .fieldOf("wall_decoration").forGetter(StyleDetailConfig::wallDecoration),
                    Codec.INT.optionalFieldOf("string_course_interval", 0)
                            .forGetter(StyleDetailConfig::stringCourseInterval),
                    Codec.BOOL.optionalFieldOf("has_battered_base", false)
                            .forGetter(StyleDetailConfig::hasBatteredBase),
                    Codec.BOOL.optionalFieldOf("has_quoins", false)
                            .forGetter(StyleDetailConfig::hasQuoins),
                    CourtyardStyle.CODEC
                            .optionalFieldOf("courtyard_style", CourtyardStyle.BARE)
                            .forGetter(StyleDetailConfig::courtyardStyle),
                    Codec.INT.optionalFieldOf("merlon_width", 1)
                            .forGetter(StyleDetailConfig::merlonWidth),
                    Codec.INT.optionalFieldOf("merlon_height", 1)
                            .forGetter(StyleDetailConfig::merlonHeight)
            ).apply(instance, StyleDetailConfig::new)
    );

    // =========================================================================
    // Preset instances — one per style
    // =========================================================================

    public static final StyleDetailConfig NORMAN = new StyleDetailConfig(
            BattlementStyle.CRENELLATED, TowerRoofStyle.PYRAMIDAL, ArchStyle.ROUND,
            WindowStyle.ARROW_SLIT, WallDecoStyle.NONE, 0,
            true, false, CourtyardStyle.PAVED, 1, 1);

    public static final StyleDetailConfig MOORISH = new StyleDetailConfig(
            BattlementStyle.STEPPED, TowerRoofStyle.PYRAMIDAL, ArchStyle.HORSESHOE,
            WindowStyle.CIRCULAR, WallDecoStyle.BANDED, 4,
            false, true, CourtyardStyle.FOUNTAIN, 1, 2);

    public static final StyleDetailConfig FANTASY = new StyleDetailConfig(
            BattlementStyle.POINTED, TowerRoofStyle.SPIRE, ArchStyle.POINTED,
            WindowStyle.WIDE_ARCH, WallDecoStyle.STRING_COURSE, 5,
            false, false, CourtyardStyle.PAVED, 1, 2);

    public static final StyleDetailConfig RUIN = new StyleDetailConfig(
            BattlementStyle.RUINED, TowerRoofStyle.RUINED, ArchStyle.ROUND,
            WindowStyle.ARROW_SLIT, WallDecoStyle.NONE, 0,
            false, false, CourtyardStyle.BARE, 1, 1);

    public static final StyleDetailConfig JAPANESE = new StyleDetailConfig(
            BattlementStyle.PARAPET, TowerRoofStyle.PAGODA, ArchStyle.FLAT,
            WindowStyle.WIDE_ARCH, WallDecoStyle.STRING_COURSE, 3,
            false, false, CourtyardStyle.GARDEN, 2, 1);

    public static final StyleDetailConfig FRENCH_GOTHIC = new StyleDetailConfig(
            BattlementStyle.POINTED, TowerRoofStyle.SPIRE, ArchStyle.POINTED,
            WindowStyle.WIDE_ARCH, WallDecoStyle.PILASTERS, 4,
            false, true, CourtyardStyle.GARDEN, 1, 2);

    public static final StyleDetailConfig BYZANTINE = new StyleDetailConfig(
            BattlementStyle.STEPPED, TowerRoofStyle.DOME, ArchStyle.ROUND,
            WindowStyle.CIRCULAR, WallDecoStyle.BANDED, 3,
            false, true, CourtyardStyle.FOUNTAIN, 2, 1);

    public static final StyleDetailConfig EDWARDIAN = new StyleDetailConfig(
            BattlementStyle.CRENELLATED, TowerRoofStyle.FLAT, ArchStyle.ROUND,
            WindowStyle.ARROW_SLIT, WallDecoStyle.NONE, 0,
            true, false, CourtyardStyle.PAVED, 1, 1);

    public static final StyleDetailConfig MUGHAL = new StyleDetailConfig(
            BattlementStyle.STEPPED, TowerRoofStyle.DOME, ArchStyle.HORSESHOE,
            WindowStyle.CIRCULAR, WallDecoStyle.BANDED, 3,
            false, true, CourtyardStyle.FOUNTAIN, 2, 2);

    public static final StyleDetailConfig DARK_LORD = new StyleDetailConfig(
            BattlementStyle.WIDE, TowerRoofStyle.SPIRE, ArchStyle.POINTED,
            WindowStyle.NONE, WallDecoStyle.STRING_COURSE, 6,
            true, false, CourtyardStyle.PAVED, 2, 1);

    public static final StyleDetailConfig ELVISH = new StyleDetailConfig(
            BattlementStyle.PARAPET, TowerRoofStyle.CONICAL, ArchStyle.TUDOR,
            WindowStyle.WIDE_ARCH, WallDecoStyle.STRING_COURSE, 4,
            false, false, CourtyardStyle.GARDEN, 2, 1);

    public static final StyleDetailConfig DWARVEN = new StyleDetailConfig(
            BattlementStyle.WIDE, TowerRoofStyle.FLAT, ArchStyle.FLAT,
            WindowStyle.NONE, WallDecoStyle.QUOINS, 0,
            true, true, CourtyardStyle.PAVED, 2, 1);

    public static final StyleDetailConfig SEA_FORT = new StyleDetailConfig(
            BattlementStyle.CRENELLATED, TowerRoofStyle.FLAT, ArchStyle.FLAT,
            WindowStyle.CROSS_SLIT, WallDecoStyle.NONE, 0,
            true, false, CourtyardStyle.PAVED, 1, 1);

    public static final StyleDetailConfig ANCIENT_RUIN = new StyleDetailConfig(
            BattlementStyle.RUINED, TowerRoofStyle.RUINED, ArchStyle.ROUND,
            WindowStyle.ARROW_SLIT, WallDecoStyle.NONE, 0,
            false, false, CourtyardStyle.BARE, 1, 1);

    public static final StyleDetailConfig UNDEAD = new StyleDetailConfig(
            BattlementStyle.RUINED, TowerRoofStyle.RUINED, ArchStyle.POINTED,
            WindowStyle.CROSS_SLIT, WallDecoStyle.STRING_COURSE, 5,
            false, false, CourtyardStyle.BARE, 1, 1);

    public static final StyleDetailConfig CRYSTAL_SPIRE = new StyleDetailConfig(
            BattlementStyle.POINTED, TowerRoofStyle.SPIRE, ArchStyle.POINTED,
            WindowStyle.WIDE_ARCH, WallDecoStyle.PILASTERS, 3,
            false, true, CourtyardStyle.FOUNTAIN, 1, 2);
}