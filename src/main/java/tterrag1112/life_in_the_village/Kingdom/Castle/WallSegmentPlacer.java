package tterrag1112.life_in_the_village.Kingdom.Castle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 2: Places a curtain wall segment between two BlockPos endpoints.
 *
 * Responsibilities:
 *  - Walk a straight line path between the two endpoints
 *  - Anchor each column to terrain (no floating, no burial)
 *  - Hollow out the wall interior for walkway space
 *  - Insert arrow slit openings at regular intervals
 *  - Reserve the top row for Layer 3 battlement stamping
 *  - Record all top-course positions in a PlacementRecord for Layer 3
 *
 * Does NOT place battlements, windows, or decorative detail.
 */
public class WallSegmentPlacer {

    // How many blocks between arrow slits on each face
    private static final int ARROW_SLIT_INTERVAL = 5;
    // Minimum clear height inside the wall for the walkway gap
    private static final int WALKWAY_CLEAR_HEIGHT = 3;

    private final LevelAccessor level;
    private final CastleStyle style;
    private final TerrainSampler terrain;

    public WallSegmentPlacer(LevelAccessor level, CastleStyle style, TerrainSampler terrain) {
        this.level   = level;
        this.style   = style;
        this.terrain = terrain;
    }

    // -------------------------------------------------------------------------
    // Main placement
    // -------------------------------------------------------------------------

    /**
     * Place a wall segment from {@code from} to {@code to}.
     *
     * @param from       Center of the starting tower (wall begins here)
     * @param to         Center of the ending tower (wall ends here)
     * @param wallHeight Nominal height of the wall in blocks
     * @param rng        Random source for material variation and ruination
     * @return           PlacementRecord listing all top-course block positions
     *                   for the battlement applicator.
     */
    public PlacementRecord place(BlockPos from, BlockPos to, int wallHeight,
                                 RandomSource rng) {

        List<BlockPos> topCourse  = new ArrayList<>();
        List<BlockPos> walkway    = new ArrayList<>();

        // Rasterize the line between from and to using Bresenham-style stepping
        List<BlockPos> path = rasterizeLine(from, to);

        // Determine the dominant axis so we know which face gets arrow slits
        boolean xDominant = Math.abs(to.getX() - from.getX())
                >= Math.abs(to.getZ() - from.getZ());
        Direction inwardFace  = xDominant ? Direction.NORTH : Direction.WEST;
        Direction outwardFace = inwardFace.getOpposite();

        int thickness = style.wallThickness();

        // For each step along the path, place a wall column
        for (int stepIdx = 0; stepIdx < path.size(); stepIdx++) {
            BlockPos pathPos = path.get(stepIdx);

            // Resolve ground Y at this XZ position
            int groundY = terrain.groundY(pathPos.getX(), pathPos.getZ());
            int topY    = groundY + wallHeight;

            // Fill foundation if the ground dips (prevents floating walls on slopes)
            int minGroundY = terrain.minGroundY(
                    pathPos.getX() - thickness / 2, pathPos.getZ() - thickness / 2,
                    pathPos.getX() + thickness / 2, pathPos.getZ() + thickness / 2
            );
            fillFoundation(pathPos, minGroundY, groundY, rng);

            // Place wall columns across the thickness of the wall
            for (int t = 0; t < thickness; t++) {
                BlockPos columnBase = offsetIntoWall(pathPos, inwardFace, t);
                boolean isOuterFace = (t == 0);
                boolean isInnerFace = (t == thickness - 1);

                for (int y = groundY; y <= topY; y++) {
                    BlockPos blockPos = columnBase.atY(y);
                    boolean isWalkwayHeight = (y > topY - WALKWAY_CLEAR_HEIGHT - 1)
                            && (y < topY);
                    boolean isTopCourse     = (y == topY);

                    // Hollow out the interior columns at walkway height
                    if (isWalkwayHeight && !isOuterFace && !isInnerFace) {
                        placeBlock(blockPos, Blocks.AIR.defaultBlockState());
                        continue;
                    }

                    // Arrow slits: cut into the outer face at mid-wall height
                    if (isOuterFace && isArrowSlitPosition(y, groundY, topY, stepIdx)) {
                        placeBlock(blockPos, Blocks.AIR.defaultBlockState());
                        continue;
                    }

                    // Ruination: randomly remove blocks from upper portions
                    if (shouldRuin(y, groundY, topY, rng)) {
                        placeBlock(blockPos, Blocks.AIR.defaultBlockState());
                        if (isTopCourse) continue; // Skip recording ruined top course
                    } else {
                        placeBlock(blockPos, pickWallBlock(rng));
                    }

                    // Record the top course for battlement placement
                    if (isTopCourse && isOuterFace) {
                        topCourse.add(blockPos);
                    }
                }

                // Record walkway floor positions (top of the inner section)
                if (isInnerFace) {
                    walkway.add(columnBase.atY(topY - WALKWAY_CLEAR_HEIGHT - 1));
                }
            }
        }

        return new PlacementRecord(topCourse, walkway, from, to, wallHeight);
    }

    // -------------------------------------------------------------------------
    // Line rasterization
    // -------------------------------------------------------------------------

    /**
     * Rasterize a 2D line from A to B in the XZ plane using integer steps.
     * Returns all integer (X, groundLevel, Z) positions along the segment,
     * excluding the endpoints themselves (which are handled by tower placers).
     */
    private List<BlockPos> rasterizeLine(BlockPos a, BlockPos b) {
        List<BlockPos> result = new ArrayList<>();

        int x0 = a.getX(), z0 = a.getZ();
        int x1 = b.getX(), z1 = b.getZ();
        int dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1, sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;

        // Skip first and last step (tower footprints)
        int totalSteps = Math.max(dx, dz);
        if (totalSteps < 2) return result;

        int x = x0, z = z0;
        for (int step = 0; step <= totalSteps; step++) {
            if (step > 0 && step < totalSteps) {
                result.add(new BlockPos(x, a.getY(), z));
            }
            int e2 = 2 * err;
            if (e2 > -dz) { err -= dz; x += sx; }
            if (e2 <  dx) { err += dx; z += sz; }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Block selection
    // -------------------------------------------------------------------------

    private BlockState pickWallBlock(RandomSource rng) {
        // Small chance to pick a cracked/mossy variant for visual noise
        if (rng.nextFloat() < 0.15f) {
            return style.primaryMaterial().pickCracked(rng);
        }
        return style.primaryMaterial().pickMain(rng);
    }

    // -------------------------------------------------------------------------
    // Arrow slits
    // -------------------------------------------------------------------------

    /**
     * Determines whether this (y, stepIdx) position should be an arrow slit opening.
     * Slits are placed at a fixed height band and at regular horizontal intervals.
     */
    private boolean isArrowSlitPosition(int y, int groundY, int topY, int stepIdx) {
        // Vertical position: place slits in the upper-middle band of the wall
        int midY   = groundY + (topY - groundY) * 2 / 3;
        boolean inBand = (y == midY || y == midY + 1);
        // Horizontal position: every N steps
        boolean inInterval = (stepIdx % ARROW_SLIT_INTERVAL == ARROW_SLIT_INTERVAL / 2);
        return inBand && inInterval;
    }

    // -------------------------------------------------------------------------
    // Ruination
    // -------------------------------------------------------------------------

    /**
     * Returns true if this block should be removed for ruination effect.
     * Higher blocks are more likely to be removed.
     */
    private boolean shouldRuin(int y, int groundY, int topY, RandomSource rng) {
        float ruin = style.ruinationLevel();
        if (ruin <= 0f) return false;

        // Linear increase toward the top
        float heightFraction = (float)(y - groundY) / Math.max(1, topY - groundY);
        float threshold = ruin * heightFraction;
        return rng.nextFloat() < threshold;
    }

    // -------------------------------------------------------------------------
    // Foundation
    // -------------------------------------------------------------------------

    private void fillFoundation(BlockPos topPos, int minY, int groundY, RandomSource rng) {
        for (int y = minY; y < groundY; y++) {
            for (int dx = 0; dx < style.wallThickness(); dx++) {
                BlockPos pos = offsetIntoWall(topPos, Direction.NORTH, dx).atY(y);
                BlockState current = level.getBlockState(pos);
                if (current.isAir() || current.is(Blocks.WATER)) {
                    placeBlock(pos, style.primaryMaterial().pickMain(rng));
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    /**
     * Offset a path-center position perpendicularly into the wall by t steps.
     * The "into wall" direction is determined by the inward face.
     */
    private BlockPos offsetIntoWall(BlockPos center, Direction inward, int steps) {
        return center.relative(inward, steps);
    }

    private void placeBlock(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    // -------------------------------------------------------------------------
    // PlacementRecord — passed to Layer 3
    // -------------------------------------------------------------------------

    /**
     * Records positions that Layer 3 (DetailApplicator) needs to decorate.
     */
    public record PlacementRecord(
            List<BlockPos> topCoursePositions,  // Where battlements go
            List<BlockPos> walkwayPositions,     // Floor of the wall walkway
            BlockPos wallFrom,
            BlockPos wallTo,
            int wallHeight
    ) {}
}
