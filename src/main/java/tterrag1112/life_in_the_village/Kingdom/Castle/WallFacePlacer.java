package tterrag1112.life_in_the_village.Kingdom.Castle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;

import java.util.ArrayList;
import java.util.List;

import static tterrag1112.life_in_the_village.Kingdom.Castle.CastleConstants.*;

/**
 * Places and decorates rectangular RoomStack volumes.
 *
 * In TMA terms this is the layer that takes a room footprint and produces
 * the actual wall blocks, floor slabs, walkway hollow, windows, string
 * courses, and staircase access — all with floor-relative coordinates.
 *
 * One instance handles one stack completely.
 */
public class WallFacePlacer {

    private final LevelAccessor level;
    private final CastleStyle style;
    private final TerrainSampler terrain;

    public WallFacePlacer(LevelAccessor level, CastleStyle style,
                          TerrainSampler terrain) {
        this.level   = level;
        this.style   = style;
        this.terrain = terrain;
    }

    // =========================================================================
    // Entry point — place a complete rectangular stack
    // =========================================================================

    /**
     * Place the full volume of a rectangular RoomStack and return
     * a StackPlacementRecord for the detail layer to consume.
     */
    /**
     * Place the full volume of a rectangular RoomStack and return a
     * StackPlacementRecord for the detail layer to consume.
     *
     * For each floor in the stack:
     *   1. Fill foundation below ground
     *   2. Place floor slab at floor boundary (above ground floor)
     *   3. Build a FaceCanvas for each exposed face
     *   4. Apply the theme's WallDesign rules to the canvas
     *   5. Flush the canvas to world coordinates
     *   6. Clear the interior to air
     *   7. Place walkway floor tiles and staircase access
     *   8. Record top course and walkway positions
     */
    public StackPlacementRecord placeStack(RoomStack stack, RandomSource rng) {

        ArchitectTheme   theme   = style.theme();
        ArchitectPalette palette = style.palette();

        List<BlockPos> topCourse  = new ArrayList<>();
        List<BlockPos> walkway    = new ArrayList<>();
        int walkwayFloorY         = stack.origin().getY();

        // Step 1: Foundation — fill any air/water below the stack origin
        fillFoundation(stack, palette, rng);

        int currentY = stack.origin().getY();

        for (int floorIdx = 0; floorIdx < stack.floors().size(); floorIdx++) {
            RoomStack.FloorDef floor   = stack.floors().get(floorIdx);
            boolean isTopFloor         = (floorIdx == stack.floors().size() - 1);
            int floorTopY              = currentY + floor.height();
            int inset                  = floor.offsetX();

            // Step 2: Floor slab at each boundary above the ground floor
            if (floorIdx > 0) {
                placeFloorSlabs(stack, currentY, inset, palette, rng);
            }

            // Step 3 & 4: For each exposed face, build and apply a canvas
            for (Direction face : HORIZONTAL_FACES) {
                if (!isFaceExposed(stack, face)) continue;

                // Compute the world-space start position and length of this face
                FaceGeometry geo = computeFaceGeometry(stack, face, floorIdx, currentY);
                if (geo.length() <= 0) continue;

                // Pick the appropriate wall design for this stack role
                WallDesign design = selectDesign(theme, stack.role(), rng);

                // Build canvas — initialised entirely with fill block
                WallDesign.FaceCanvas canvas = new WallDesign.FaceCanvas(
                        geo.length(),
                        floor.height(),
                        face,
                        geo.worldOrigin(),
                        palette.pick("fill", rng));

                // Apply design rules — they write pattern, trim, windows into canvas
                for (WallDesign.DecoRule rule : design.rules()) {
                    rule.apply(canvas, palette, rng);
                }

                // Walkway hollow: clear interior of the face canvas at walkway height
                if (floor.hasWalkway()) {
                    applyWalkwayHollow(canvas, floor.height());
                    walkwayFloorY = currentY;
                }

                // Step 5: Write canvas to world
                canvas.flush(level);

                // Step 8a: Record top course from the outermost face only
                if (isTopFloor && face == stack.facing()) {
                    collectTopCourse(geo, currentY, floor.height(), topCourse);
                }
            }

            // Step 6: Clear interior air (rooms inside the shell)
            clearInterior(stack, floorIdx, currentY, floor.height(), inset);

            // Step 7a: Walkway floor tiles on inner surface
            if (floor.hasWalkway()) {
                placeWalkwayFloor(stack, currentY, inset, palette, rng, walkway);
                // Step 7b: Staircase from previous floor up to this walkway
                if (floorIdx > 0) {
                    placeWalkwayStairs(stack, currentY, inset, palette, rng);
                }
            }

            currentY = floorTopY;
        }

        return new StackPlacementRecord(
                topCourse, walkway, List.of(),
                stack.facing(), walkwayFloorY, stack);
    }

// =============================================================================
// Face geometry helper
// =============================================================================

    /**
     * Computes the world-space origin and length of a single face of a
     * RoomStack at a given floor index.
     *
     * The face origin is the block at the minimum lateral position on that face.
     * Length is measured along the face, not perpendicular to it.
     *
     * Stepped floors (inset > 0) reduce the face length accordingly.
     */
    private FaceGeometry computeFaceGeometry(RoomStack stack, Direction face,
                                             int floorIdx, int baseY) {
        RoomStack.FloorDef floor = stack.floors().get(floorIdx);
        int inset = floor.offsetX();
        int th    = stack.wallThickness();
        int ox    = stack.origin().getX() + inset;
        int oz    = stack.origin().getZ() + inset;
        int ow    = stack.outerWidthX() - inset * 2;
        int od    = stack.outerDepthZ() - inset * 2;

        // Face origin = the corner block of this face at baseY
        // Length = number of blocks along the face (excluding thickness)
        return switch (face) {
            case NORTH -> new FaceGeometry(
                    new BlockPos(ox, baseY, oz),
                    ow);
            case SOUTH -> new FaceGeometry(
                    new BlockPos(ox, baseY, oz + od - 1),
                    ow);
            case WEST -> new FaceGeometry(
                    new BlockPos(ox, baseY, oz),
                    od);
            case EAST -> new FaceGeometry(
                    new BlockPos(ox + ow - 1, baseY, oz),
                    od);
            default -> new FaceGeometry(new BlockPos(ox, baseY, oz), 0);
        };
    }

    /** Lightweight record carrying the computed face geometry. */
    private record FaceGeometry(BlockPos worldOrigin, int length) {}

// =============================================================================
// Design selection
// =============================================================================

    private WallDesign selectDesign(ArchitectTheme theme, RoomStack.StackRole role,
                                    RandomSource rng) {
        return switch (role) {
            case OUTER_WALL, INNER_WALL ->
                    theme.pickWallDesign(WallDesignRegistry.INSTANCE, rng);
            case TOWER, GATEHOUSE, BARBICAN ->
                    theme.getTowerDesign(WallDesignRegistry.INSTANCE);
            case DONJON ->
                    theme.getDonjeonDesign(WallDesignRegistry.INSTANCE);
            default ->
                    WallDesign.PLAIN;
        };
    }

// =============================================================================
// Face exposure check
// =============================================================================

    private boolean isFaceExposed(RoomStack stack, Direction face) {
        return switch (face) {
            case NORTH -> stack.faceNorthExposed();
            case SOUTH -> stack.faceSouthExposed();
            case EAST  -> stack.faceEastExposed();
            case WEST  -> stack.faceWestExposed();
            default    -> false;
        };
    }

// =============================================================================
// Walkway hollow applied to canvas before flush
// =============================================================================

    /**
     * Clears the interior rows of a canvas at walkway height.
     * The outer column (x=0) and inner column (x=faceLength-1) remain solid.
     * Rows between Y=1 and Y=floorHeight-2 are set to air for walkway space.
     */
    private void applyWalkwayHollow(WallDesign.FaceCanvas canvas, int floorHeight) {
        int hollowStart = 1;
        int hollowEnd   = Math.max(1, floorHeight - 2);
        // Only hollow the inner portion — x=0 is the outer face, never hollowed
        // For a wall panel, the canvas represents only the face thickness (1 block)
        // so we just clear the upper-mid height band
        for (int y = hollowStart; y <= hollowEnd; y++) {
            for (int x = 1; x < canvas.faceLength() - 1; x++) {
                canvas.set(x, y, Blocks.AIR.defaultBlockState());
            }
        }
    }

// =============================================================================
// Top course collection
// =============================================================================

    private void collectTopCourse(FaceGeometry geo, int baseY, int floorHeight,
                                  List<BlockPos> topCourse) {
        int topY = baseY + floorHeight - 1;
        // The canvas flush method places blocks at worldOrigin + x along EAST/WEST
        // or along NORTH/SOUTH — use the face direction to reconstruct positions
        // We re-derive from the geometry rather than reading back from world
        for (int x = 0; x < geo.length(); x++) {
            topCourse.add(geo.worldOrigin().above(topY - geo.worldOrigin().getY()));
        }
    }

// =============================================================================
// Interior clearing
// =============================================================================

    /**
     * After faces are flushed, clear the interior volume of the stack to air.
     * This covers the space inside the shell walls on all four sides.
     * Stepped floors use the inset to correctly compute the interior bounds.
     */
    private void clearInterior(RoomStack stack, int floorIdx,
                               int baseY, int floorHeight, int inset) {
        int th = stack.wallThickness();
        int ox = stack.origin().getX() + th + inset;
        int oz = stack.origin().getZ() + th + inset;
        int wx = stack.widthX() - inset * 2;
        int wz = stack.depthZ() - inset * 2;

        if (wx <= 0 || wz <= 0) return;

        // Only clear interior for rooms, not for wall panels (which are solid)
        boolean isRoom = stack.role() == RoomStack.StackRole.DONJON
                || stack.role() == RoomStack.StackRole.SUBBUILDING;

        if (!isRoom) return;

        for (int dx = 0; dx < wx; dx++) {
            for (int dz = 0; dz < wz; dz++) {
                for (int dy = 0; dy < floorHeight - 1; dy++) {
                    // Leave the floor slab (dy=0 is handled by placeFloorSlabs)
                    int clearY = (floorIdx == 0) ? baseY + dy : baseY + 1 + dy;
                    if (clearY >= baseY + floorHeight) continue;
                    level.setBlock(
                            new BlockPos(ox + dx, clearY, oz + dz),
                            Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_CLIENTS);
                }
            }
        }
    }

// =============================================================================
// Floor slabs
// =============================================================================

    private void placeFloorSlabs(RoomStack stack, int y, int inset,
                                 ArchitectPalette palette, RandomSource rng) {
        int th = stack.wallThickness();
        int ox = stack.origin().getX() + th + inset;
        int oz = stack.origin().getZ() + th + inset;
        int wx = stack.widthX() - inset * 2;
        int wz = stack.depthZ() - inset * 2;

        if (wx <= 0 || wz <= 0) return;

        BlockState slab = palette.slab("slab", SlabType.BOTTOM);

        for (int dx = 0; dx < wx; dx++) {
            for (int dz = 0; dz < wz; dz++) {
                level.setBlock(
                        new BlockPos(ox + dx, y, oz + dz),
                        slab,
                        Block.UPDATE_CLIENTS);
            }
        }
    }

// =============================================================================
// Walkway floor
// =============================================================================

    private void placeWalkwayFloor(RoomStack stack, int walkY, int inset,
                                   ArchitectPalette palette, RandomSource rng,
                                   List<BlockPos> walkwayOut) {
        int th  = stack.wallThickness();
        int ox  = stack.origin().getX() + th + inset;
        int oz  = stack.origin().getZ() + th + inset;
        int wx  = stack.widthX() - inset * 2;
        int wz  = stack.depthZ() - inset * 2;

        if (wx <= 0 || wz <= 0) return;

        BlockState floor = palette.pick("floor", rng);

        for (int dx = 0; dx < wx; dx++) {
            for (int dz = 0; dz < wz; dz++) {
                BlockPos pos = new BlockPos(ox + dx, walkY, oz + dz);
                if (level.getBlockState(pos).isAir()) {
                    level.setBlock(pos, floor, Block.UPDATE_CLIENTS);
                }
                walkwayOut.add(pos);
            }
        }
    }

// =============================================================================
// Walkway staircase
// =============================================================================

    private void placeWalkwayStairs(RoomStack stack, int walkY, int inset,
                                    ArchitectPalette palette, RandomSource rng) {
        Direction outward = stack.facing();
        Direction travel  = outward.getClockWise(); // direction along the wall
        int groundY       = stack.origin().getY();
        int stairCount    = walkY - groundY;
        if (stairCount <= 0 || stairCount > 24) return;

        int th = stack.wallThickness();
        BlockPos stairBase = switch (outward) {
            case NORTH -> new BlockPos(
                    stack.origin().getX() + inset, groundY,
                    stack.origin().getZ() + stack.outerDepthZ() - inset - 1);
            case SOUTH -> new BlockPos(
                    stack.origin().getX() + inset, groundY,
                    stack.origin().getZ() + inset);
            case EAST -> new BlockPos(
                    stack.origin().getX() + inset, groundY,
                    stack.origin().getZ() + inset);
            case WEST -> new BlockPos(
                    stack.origin().getX() + stack.outerWidthX() - inset - 1, groundY,
                    stack.origin().getZ() + inset);
            default -> stack.origin();
        };

        Block stairBlock = palette.block("stair");
        for (int i = 0; i < stairCount; i++) {
            BlockPos pos = stairBase.relative(travel, i).atY(groundY + i);

            level.setBlock(pos.above(),   Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(pos.above(2),  Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);

            // FACING = direction player walks TO GO UP the stair
            // Player walks in the travel direction going up, so facing = travel
            // (was travel.getOpposite() which made stairs face down-slope)
            level.setBlock(pos,
                    stairBlock.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.StairBlock.FACING,
                                    travel)
                            .setValue(net.minecraft.world.level.block.StairBlock.HALF,
                                    net.minecraft.world.level.block.state.properties.Half.BOTTOM)
                            .setValue(net.minecraft.world.level.block.StairBlock.SHAPE,
                                    net.minecraft.world.level.block.state.properties.StairsShape.STRAIGHT),
                    Block.UPDATE_CLIENTS);
        }

        // Landing at top
        BlockPos landing = stairBase.relative(travel, stairCount).atY(walkY);
        if (level.getBlockState(landing).isAir()) {
            level.setBlock(landing, palette.pick("floor", rng), Block.UPDATE_CLIENTS);
        }
    }

// =============================================================================
// Foundation
// =============================================================================

    private void fillFoundation(RoomStack stack, ArchitectPalette palette,
                                RandomSource rng) {
        int ox   = stack.origin().getX();
        int oz   = stack.origin().getZ();
        int ow   = stack.outerWidthX();
        int od   = stack.outerDepthZ();
        int base = stack.origin().getY();

        for (int dx = 0; dx < ow; dx++) {
            for (int dz = 0; dz < od; dz++) {
                int groundY = terrain.groundY(ox + dx, oz + dz);
                for (int y = groundY; y < base; y++) {
                    BlockPos pos = new BlockPos(ox + dx, y, oz + dz);
                    BlockState cur = level.getBlockState(pos);
                    if (cur.isAir() || cur.is(Blocks.WATER)) {
                        level.setBlock(pos, palette.pick("fill", rng),
                                Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

// =============================================================================
// Constants
// =============================================================================

    private static final Direction[] HORIZONTAL_FACES = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };


}