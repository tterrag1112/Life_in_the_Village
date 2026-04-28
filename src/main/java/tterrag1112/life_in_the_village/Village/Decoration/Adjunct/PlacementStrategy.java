package tterrag1112.life_in_the_village.Village.Decoration.Adjunct;

/**
 * Doc 02 §"Two placement strategies" — how the materializer for an
 * {@link AdjunctPlotType} converts a placed plot into world content.
 *
 * <p>Phase 0c authors plot bounds only; nothing actually
 * materializes yet. Subsystems 07 (industry), 08 (gardens), and 11
 * (homesteading) will read this enum and route to the matching
 * realiser.</p>
 */
public enum PlacementStrategy {
    /** A single pre-authored NBT stamped at the plot origin.
     *  Default for industry yards and gardens. */
    NBT,
    /** Content assembled from primitives (FlowerBed, Hedgerow,
     *  Trellis, etc.) with per-plot variation. Reserved for
     *  homesteads and simple industry yards where variation
     *  matters more than polish. */
    PIECE_KIT
}
