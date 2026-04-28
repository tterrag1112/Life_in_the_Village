package tterrag1112.life_in_the_village.Village.Decoration.Subbuilding;

import net.minecraft.core.BlockPos;

/**
 * Doc 03 §"Door target" — small helpers NPC goals call when a goal
 * targets a {@link SubBuilding} rather than a whole {@code Building}.
 *
 * <p>Today this just normalises the door-target / approach-target
 * pair: the NPC navigates to {@link #approachTarget(SubBuilding)}, which
 * is a position one block in front of the door (away from the
 * subbuilding interior) so the NPC stands outside the door before
 * opening it. {@link #pathTarget(SubBuilding)} returns the door block
 * itself for goals that prefer to drive their own approach offset.</p>
 *
 * <p>Subbuildings whose door target falls back to the anchor (no door
 * was found at scan time) are still navigable: both helpers return
 * the anchor position and {@link #hasRealDoor(SubBuilding)} returns
 * {@code false} so callers can skip "stand outside before opening"
 * behaviour for those.</p>
 */
public final class SubBuildingPathing {

    private SubBuildingPathing() {}

    /**
     * Returns the position of the registered door (or the anchor
     * fallback). NPCs that want to drive their own approach offset
     * should target this directly.
     */
    public static BlockPos pathTarget(SubBuilding sub) {
        return sub.doorTarget();
    }

    /**
     * Returns the position the NPC should walk to BEFORE opening the
     * door — one block outward from the door along {@code doorFacing}.
     * For subbuildings without a real door (door-target equals the
     * anchor), returns the anchor itself.
     */
    public static BlockPos approachTarget(SubBuilding sub) {
        if (!hasRealDoor(sub)) return sub.doorTarget();
        return sub.doorTarget().relative(sub.doorFacing());
    }

    /**
     * True iff the scanner found an actual door for this subbuilding
     * (i.e. {@code doorTarget != origin}). When false, the door target
     * is the anchor itself and there is no exterior approach to stand
     * at — the NPC just walks to the anchor.
     */
    public static boolean hasRealDoor(SubBuilding sub) {
        return !sub.doorTarget().equals(sub.origin());
    }

    /**
     * Returns true if the given world position is contained inside the
     * subbuilding's bounding box. NPC goals use this to detect
     * "I'm already inside, no need to path" cases.
     */
    public static boolean contains(SubBuilding sub, BlockPos pos) {
        BlockPos min = sub.min();
        BlockPos max = sub.max();
        return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }
}
