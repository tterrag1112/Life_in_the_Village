package tterrag1112.life_in_the_village.Village.Decoration.Adjunct;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * Doc 02 — every concrete plot type. Each value carries the
 * placer's metadata: default footprint half-extents, materializer
 * strategy, slope tolerance, and probe-order override.
 *
 * <p>The placer's algorithm reads these fields. Subsystem 07
 * (industry adjuncts), subsystem 08 (gardens), and subsystem 11
 * (homesteading) plug their content into the matching strategy at
 * realisation time. Phase 0c authors plot bounds only; nothing
 * stamps NBTs or assembles piece-kits yet.</p>
 *
 * <p>Codec stability per doc 02 §"Open decisions": new values
 * append to the end. The codec serialises by enum name via
 * {@link StringRepresentable}.</p>
 */
public enum AdjunctPlotType implements StringRepresentable {

    // ── Industry (subsystem 07) ──────────────────────────────────────────

    /** Blacksmith's outdoor work area: anvil, forge, log piles. */
    FORGE_YARD          (5, 5, PlacementStrategy.NBT,       2, FaceProbeOrder.BACK_FIRST),
    /** Weaver / fishery drying racks. */
    DRYING_RACK_YARD    (5, 4, PlacementStrategy.NBT,       1, FaceProbeOrder.BACK_FIRST),
    /** Stonemason / candlemaker kiln yard. */
    KILN_YARD           (5, 5, PlacementStrategy.NBT,       2, FaceProbeOrder.BACK_FIRST),
    /** Carpentry / woodcutter / miller log staging area. */
    LOG_YARD            (4, 6, PlacementStrategy.NBT,       2, FaceProbeOrder.BACK_FIRST),
    /** Stable's outdoor enclosure. Larger footprint, looser slope. */
    PADDOCK             (8, 8, PlacementStrategy.PIECE_KIT, 4, FaceProbeOrder.BACK_FIRST),
    /** Bakery's external oven shed. */
    OVEN_SHED           (4, 4, PlacementStrategy.NBT,       1, FaceProbeOrder.BACK_FIRST),

    // ── Gardens (subsystem 08) ───────────────────────────────────────────

    /** Apothecary herb garden — small, ordered rows. */
    HERB_GARDEN         (4, 4, PlacementStrategy.NBT,       1, FaceProbeOrder.BACK_FIRST),
    /** Inn kitchen garden — vegetables for the kitchen. */
    KITCHEN_GARDEN      (4, 5, PlacementStrategy.NBT,       1, FaceProbeOrder.BACK_FIRST),
    /** Temple meditation garden — quiet, contemplative. */
    MEDITATION_GARDEN   (5, 5, PlacementStrategy.NBT,       1, FaceProbeOrder.BACK_FIRST),
    /** Noble manor formal garden — wider, hedgerow-bordered. */
    FORMAL_GARDEN       (7, 7, PlacementStrategy.NBT,       2, FaceProbeOrder.BACK_FIRST),

    // ── Homesteads (subsystem 11) ────────────────────────────────────────

    /** Chicken coop attached to a house. */
    HOMESTEAD_COOP      (3, 3, PlacementStrategy.PIECE_KIT, 1, FaceProbeOrder.BACK_FIRST),
    /** Vegetable garden attached to a house. */
    HOMESTEAD_GARDEN    (4, 4, PlacementStrategy.PIECE_KIT, 1, FaceProbeOrder.BACK_FIRST),
    /** Pig / sheep pen attached to a house. */
    HOMESTEAD_PEN       (5, 5, PlacementStrategy.PIECE_KIT, 2, FaceProbeOrder.BACK_FIRST),
    /** Beehives attached to a house. */
    HOMESTEAD_BEES      (3, 3, PlacementStrategy.PIECE_KIT, 1, FaceProbeOrder.SIDES_FIRST);

    private final int defaultHalfWidthX;
    private final int defaultHalfLengthZ;
    private final PlacementStrategy strategy;
    private final int slopeTolerance;
    private final FaceProbeOrder faceOrder;

    AdjunctPlotType(int defaultHalfWidthX, int defaultHalfLengthZ,
                    PlacementStrategy strategy,
                    int slopeTolerance,
                    FaceProbeOrder faceOrder) {
        this.defaultHalfWidthX  = defaultHalfWidthX;
        this.defaultHalfLengthZ = defaultHalfLengthZ;
        this.strategy           = strategy;
        this.slopeTolerance     = slopeTolerance;
        this.faceOrder          = faceOrder;
    }

    public int defaultHalfWidthX()       { return defaultHalfWidthX; }
    public int defaultHalfLengthZ()      { return defaultHalfLengthZ; }
    public PlacementStrategy strategy()  { return strategy; }
    public int slopeTolerance()          { return slopeTolerance; }
    public FaceProbeOrder faceOrder()    { return faceOrder; }

    public static final Codec<AdjunctPlotType> CODEC =
            StringRepresentable.fromEnum(AdjunctPlotType::values);

    @Override public String getSerializedName() { return name(); }
}
