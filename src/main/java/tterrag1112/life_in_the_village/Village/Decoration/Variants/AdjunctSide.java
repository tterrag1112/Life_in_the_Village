package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import net.minecraft.core.Direction;

/**
 * B2.1 — preferred side of a parent building for an attached adjunct
 * plot, declared on a {@link VariantManifest} via
 * {@link AdjunctPreference}.
 *
 * <p>Values are relative to the parent's front-facing direction so a
 * variant author doesn't have to reason about world rotation.</p>
 *
 * <p>{@link #ANY} signals "the planner picks." The planner tries
 * BACK first (preserves the legacy {@code BACK_FIRST} behaviour),
 * then LEFT, then RIGHT.</p>
 */
public enum AdjunctSide {
    BACK, LEFT, RIGHT, ANY;

    /** Translates a side that's relative to the parent's front into a
     *  world {@link Direction}. {@link #ANY} is undefined; callers
     *  must resolve the choice themselves before calling. */
    public Direction toAbsolute(Direction parentFront) {
        if (parentFront == null) parentFront = Direction.SOUTH;
        return switch (this) {
            case BACK  -> parentFront.getOpposite();
            case LEFT  -> parentFront.getCounterClockWise();
            case RIGHT -> parentFront.getClockWise();
            case ANY   -> throw new IllegalStateException(
                    "AdjunctSide.ANY must be resolved by the planner before mapping to Direction");
        };
    }
}
