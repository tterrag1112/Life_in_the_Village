package tterrag1112.life_in_the_village.Kingdom.Castle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import java.util.List;

/**
 * A single rectangular or cylindrical room volume, potentially
 * with multiple floors stacked vertically.
 *
 * This is the TMA-inspired fundamental unit of castle generation.
 * Every structure in a castle — outer ward wall, inner ward wall,
 * donjon, tower, subbuilding — is a RoomStack.
 *
 * Walls are generated on the outer faces of a RoomStack.
 * Roofs are generated on top of a RoomStack.
 * Floors are generated at intervals within a RoomStack.
 *
 * Multiple RoomStacks can share faces — when two stacks are
 * adjacent, no wall is generated on that shared face.
 */
public record RoomStack(

        // Spatial definition
        BlockPos origin,        // bottom-left-front corner (min X, ground Y, min Z)
        int widthX,             // interior width in blocks (odd number for symmetry)
        int depthZ,             // interior depth in blocks
        int wallThickness,      // thickness of exterior walls

        // Vertical definition
        List<FloorDef> floors,  // one entry per floor, bottom to top

        // Shape
        StackShape shape,       // RECTANGULAR or CYLINDRICAL

        // Role — drives which variant pool is chosen for walls/roof
        StackRole role,

        // Facing — which direction is the "front" face
        Direction facing,

        // Which exterior faces are exposed (not shared with adjacent stacks)
        boolean faceNorthExposed,
        boolean faceSouthExposed,
        boolean faceEastExposed,
        boolean faceWestExposed

) {

    public enum StackShape {
        RECTANGULAR,
        CYLINDRICAL   // uses TowerPlacer logic, ignores widthX/depthZ in favor of radius
    }

    public enum StackRole {
        OUTER_WALL,     // curtain wall — primary perimeter
        INNER_WALL,     // inner ward wall
        TOWER,          // corner/flanking tower
        GATEHOUSE,      // gate complex
        DONJON,         // main keep
        BARBICAN,       // outer gate
        SUBBUILDING     // interior rooms
    }

    /**
     * Defines a single floor within the stack.
     * In TMA, individual floors can vary in size — we adopt this for
     * the donjon specifically, where upper floors can step inward.
     */
    public record FloorDef(
            int height,         // floor-to-ceiling height in blocks
            int offsetX,        // how much this floor is narrower than the base (each side)
            int offsetZ,        // same for Z axis
            boolean hasWalkway  // whether this floor has a walkway hollow
    ) {
        /** Standard floor — same size as base, full height. */
        public static FloorDef standard(int height) {
            return new FloorDef(height, 0, 0, false);
        }

        /** Walkway floor — hollow interior for wall-walk. */
        public static FloorDef walkway(int height) {
            return new FloorDef(height, 0, 0, true);
        }

        /** Stepped floor — narrower than the floor below. */
        public static FloorDef stepped(int height, int inset) {
            return new FloorDef(height, inset, inset, false);
        }
    }

    // ---- Convenience accessors ----

    /** Total height of the stack including all floors. */
    public int totalHeight() {
        return floors.stream().mapToInt(FloorDef::height).sum();
    }

    /** Y of the top surface of this stack. */
    public int topY() {
        return origin.getY() + totalHeight();
    }

    /** Center XZ of this stack. */
    public BlockPos center() {
        return new BlockPos(
                origin.getX() + (widthX + wallThickness * 2) / 2,
                origin.getY(),
                origin.getZ() + (depthZ + wallThickness * 2) / 2);
    }

    /** Outer width including walls. */
    public int outerWidthX() { return widthX + wallThickness * 2; }

    /** Outer depth including walls. */
    public int outerDepthZ() { return depthZ + wallThickness * 2; }
}