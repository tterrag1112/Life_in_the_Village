package tterrag1112.life_in_the_village.Village.Decoration.Adjunct;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * Doc 02 — every concrete plot type. Each value carries the
 * planner's metadata: default footprint half-extents and slope
 * tolerance.
 *
 * <p>The Layer-3 reservation reads {@link #defaultHalfWidthX} /
 * {@link #defaultHalfLengthZ} when no per-variant manifest
 * preference overrides them, and reads {@link #slopeTolerance} as
 * the maximum admissible vertical drop across the plot's footprint
 * (sampled from {@code V2FeatureMap}).</p>
 *
 * <p>B2.1 removed the legacy {@code strategy} and {@code faceOrder}
 * fields. They served the old probe-and-place flow, which is now
 * a Layer-3 reservation; the rendering side ({@code AdjunctPlotPlacer},
 * stub) doesn't dispatch on strategy any more — content phases
 * (subsystems 07 / 08 / 11, B2.3+) wire their own renderers per
 * type.</p>
 *
 * <p>Codec stability per doc 02 §"Open decisions": new values
 * append to the end. The codec serialises by enum name via
 * {@link StringRepresentable}.</p>
 */
public enum AdjunctPlotType implements StringRepresentable {

    // ── Industry (subsystem 07) ──────────────────────────────────────────

    /** Blacksmith's outdoor work area: anvil, forge, log piles. */
    FORGE_YARD          (5, 5, 2, ActivityTag.METALWORK),
    /** Weaver / fishery drying racks. */
    DRYING_RACK_YARD    (5, 4, 1, ActivityTag.TANNING),
    /** Stonemason / candlemaker kiln yard. */
    KILN_YARD           (5, 5, 2, ActivityTag.POTTERY),
    /** Carpentry / woodcutter / miller log staging area. */
    LOG_YARD            (4, 6, 2, ActivityTag.LUMBER),
    /** Stable's outdoor enclosure. Larger footprint, looser slope. */
    PADDOCK             (8, 8, 4, ActivityTag.LIVESTOCK),
    /** Bakery's external oven shed. */
    OVEN_SHED           (4, 4, 1, ActivityTag.COOKING),

    // ── Gardens (subsystem 08) ───────────────────────────────────────────

    /** Apothecary herb garden — small, ordered rows. */
    HERB_GARDEN         (4, 4, 1, ActivityTag.CROP),
    /** Inn kitchen garden — vegetables for the kitchen. */
    KITCHEN_GARDEN      (4, 5, 1, ActivityTag.CROP),
    /** Temple meditation garden — quiet, contemplative. */
    MEDITATION_GARDEN   (5, 5, 1, ActivityTag.LEISURE),
    /** Noble manor formal garden — wider, hedgerow-bordered. */
    FORMAL_GARDEN       (7, 7, 2, ActivityTag.LEISURE),

    // ── Homesteads (subsystem 11) ────────────────────────────────────────

    /** Chicken coop attached to a house. */
    HOMESTEAD_COOP      (3, 3, 1, ActivityTag.POULTRY),
    /** Vegetable garden attached to a house. */
    HOMESTEAD_GARDEN    (4, 4, 1, ActivityTag.CROP),
    /** Pig / sheep pen attached to a house. */
    HOMESTEAD_PEN       (5, 5, 2, ActivityTag.LIVESTOCK),
    /** Beehives attached to a house. */
    HOMESTEAD_BEES      (3, 3, 1, ActivityTag.BEES),
    /** B2.6 — small workshop annex (workbench + storage; tinkerer's
     *  shed). Spouse runs idle craft animations here during WORK
     *  phases. */
    HOMESTEAD_WORKSHOP  (4, 4, 2, ActivityTag.WORKSHOP),
    /** B2.6 — household orchard: a few fruit trees, plot-scale. */
    HOMESTEAD_ORCHARD   (5, 5, 2, ActivityTag.ORCHARD),
    /** B2.6 — log-and-plank stockpile attached to a house. */
    HOMESTEAD_WOODSHED  (4, 4, 1, ActivityTag.LUMBER);

    private final int defaultHalfWidthX;
    private final int defaultHalfLengthZ;
    private final int slopeTolerance;
    private final ActivityTag activityTag;

    AdjunctPlotType(int defaultHalfWidthX, int defaultHalfLengthZ,
                    int slopeTolerance, ActivityTag activityTag) {
        this.defaultHalfWidthX  = defaultHalfWidthX;
        this.defaultHalfLengthZ = defaultHalfLengthZ;
        this.slopeTolerance     = slopeTolerance;
        this.activityTag        = activityTag;
    }

    public int defaultHalfWidthX()       { return defaultHalfWidthX; }
    public int defaultHalfLengthZ()      { return defaultHalfLengthZ; }
    public int slopeTolerance()          { return slopeTolerance; }

    /**
     * Phase 6.3.3.d — semantic activity tag. See {@link ActivityTag}.
     * Single-tag-per-type in v1; promote to {@code Set<ActivityTag>}
     * if multi-tag semantics emerge later (no API breakage required).
     */
    public ActivityTag activityTag()     { return activityTag; }

    public static final Codec<AdjunctPlotType> CODEC =
            StringRepresentable.fromEnum(AdjunctPlotType::values);

    @Override public String getSerializedName() { return name(); }
}
