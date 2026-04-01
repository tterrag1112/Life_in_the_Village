package tterrag1112.life_in_the_village.Kingdom.Castle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.List;

/**
 * Carries all positions DetailApplicator needs after a RoomStack is placed.
 * Replaces WallSegmentPlacer.PlacementRecord in the new pipeline.
 */
public record StackPlacementRecord(
        List<BlockPos> topCoursePositions,   // battlement placement
        List<BlockPos> walkwayPositions,     // torch placement
        List<BlockPos> capRingPositions,     // tower roof placement (cylindrical only)
        Direction outwardFacing,             // battlement rotation
        int walkwayFloorY,                   // doorway carving reference
        RoomStack stack                      // full stack for context
) {
    public boolean isCylindrical() {
        return stack.shape() == RoomStack.StackShape.CYLINDRICAL;
    }

    public boolean isWall() {
        return stack.role() == RoomStack.StackRole.OUTER_WALL
                || stack.role() == RoomStack.StackRole.INNER_WALL;
    }

    public boolean isDonjon() {
        return stack.role() == RoomStack.StackRole.DONJON;
    }

    public boolean isGatehouse() {
        return stack.role() == RoomStack.StackRole.GATEHOUSE;
    }
}