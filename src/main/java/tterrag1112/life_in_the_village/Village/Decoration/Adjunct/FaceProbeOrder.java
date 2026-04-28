package tterrag1112.life_in_the_village.Village.Decoration.Adjunct;

import net.minecraft.core.Direction;

import java.util.List;

/**
 * Doc 02 §"Open decisions" — face-probing order for
 * {@link AdjunctPlotPlacer}. The placer never probes the parent
 * building's front face (would block the door's view); the
 * remaining three faces are probed in the order this enum
 * declares.
 */
public enum FaceProbeOrder {
    /** Default — back, then left, then right. */
    BACK_FIRST,
    /** Sides first, then back. Used by plot types that read better
     *  flanking the building than behind it (currently none, but
     *  the metadata slot is authored so subsystems 07/08/11 can
     *  override per type). */
    SIDES_FIRST;

    /** Returns the list of face offsets relative to the parent's
     *  front direction. {@code 0 = front} (never returned),
     *  {@code 2 = back}, {@code -1 = left}, {@code 1 = right}. */
    public List<RelativeFace> sequence() {
        return switch (this) {
            case BACK_FIRST  -> List.of(RelativeFace.BACK,
                    RelativeFace.LEFT, RelativeFace.RIGHT);
            case SIDES_FIRST -> List.of(RelativeFace.LEFT,
                    RelativeFace.RIGHT, RelativeFace.BACK);
        };
    }

    /** Absolute direction obtained by combining the parent's front
     *  direction with a relative face offset. */
    public enum RelativeFace {
        BACK, LEFT, RIGHT;

        public Direction toAbsolute(Direction parentFront) {
            return switch (this) {
                case BACK  -> parentFront.getOpposite();
                case LEFT  -> parentFront.getCounterClockWise();
                case RIGHT -> parentFront.getClockWise();
            };
        }
    }
}
