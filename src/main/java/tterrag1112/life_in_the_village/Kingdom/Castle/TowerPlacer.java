package tterrag1112.life_in_the_village.Kingdom.Castle;


import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 2: Places a single tower (or donjon) at a given TowerNode.
 *
 * Handles four tower shapes:
 *   ROUND      — circular cross-section, filled solid outer shell + hollow interior
 *   SQUARE     — square cross-section
 *   D_SHAPED   — semicircular projection from a flat wall face
 *   POLYGONAL  — octagonal cross-section
 *
 * For every shape:
 *   - Anchors to terrain with a filled foundation
 *   - Places multiple interior floors with ladders between them
 *   - Adds arrow slits at floor-height intervals on each face
 *   - Leaves the top ring of blocks as a "cap reservation" for Layer 3
 *   - Records the cap ring and any interior floor positions for Layer 3
 */
public class TowerPlacer {

    private final LevelAccessor level;
    private final CastleStyle style;
    private final TerrainSampler terrain;

    // Height of each interior floor interval
    private static final int FLOOR_INTERVAL = 5;

    public TowerPlacer(LevelAccessor level, CastleStyle style, TerrainSampler terrain) {
        this.level   = level;
        this.style   = style;
        this.terrain = terrain;
    }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /**
     * Place a tower at the given node.
     *
     * @return PlacementRecord with cap positions for Layer 3.
     */
    public PlacementRecord place(CastleLayout.TowerNode node, RandomSource rng) {
        int towerTopY  = resolveTopY(node);
        int groundY    = terrain.maxGroundYInRadius(node.center(), node.radius());
        int bottomY    = groundY - 2; // sink slightly for foundation

        // Fill foundation down to min ground
        fillFoundation(node, bottomY, groundY, rng);

        // Place the tower shell upward
        List<BlockPos> capRing    = new ArrayList<>();
        List<BlockPos> floorPosns = new ArrayList<>();
        List<BlockPos> arrowSlits = new ArrayList<>();

        for (int y = groundY; y <= towerTopY; y++) {
            boolean isCap   = (y == towerTopY);
            boolean isFloor = isFloorLevel(y, groundY);

            placeTowerRing(node, y, isCap, rng, capRing, floorPosns, arrowSlits);

            // Place floor slab at floor intervals
            if (isFloor && !isCap) {
                placeTowerFloor(node, y, rng);
            }
        }

        // Place interior ladders between floors
        placeInteriorLadders(node, groundY, towerTopY);

        return new PlacementRecord(capRing, floorPosns, arrowSlits, node);
    }

    // -------------------------------------------------------------------------
    // Ring placement dispatch
    // -------------------------------------------------------------------------

    private void placeTowerRing(CastleLayout.TowerNode node, int y, boolean isCap,
                                RandomSource rng,
                                List<BlockPos> capRing,
                                List<BlockPos> floors,
                                List<BlockPos> arrowSlits) {
        switch (style.towerShape()) {
            case ROUND     -> placeRoundRing(node, y, isCap, rng, capRing, arrowSlits);
            case SQUARE    -> placeSquareRing(node, y, isCap, rng, capRing, arrowSlits);
            case D_SHAPED  -> placeDShapedRing(node, y, isCap, rng, capRing, arrowSlits);
            case POLYGONAL -> placeOctagonRing(node, y, isCap, rng, capRing, arrowSlits);
        }
    }

    // -------------------------------------------------------------------------
    // ROUND tower
    // -------------------------------------------------------------------------

    private void placeRoundRing(CastleLayout.TowerNode node, int y, boolean isCap,
                                RandomSource rng, List<BlockPos> capRing,
                                List<BlockPos> arrowSlits) {
        int cx = node.center().getX();
        int cz = node.center().getZ();
        int r  = node.radius();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > r) continue;

                boolean isShell = dist > r - style.wallThickness();
                BlockPos pos    = new BlockPos(cx + dx, y, cz + dz);

                if (isShell) {
                    if (isCap) {
                        placeBlock(pos, style.primaryMaterial().pickMain(rng));
                        capRing.add(pos);
                    } else if (isArrowSlitPosition(node, y, new BlockPos(cx + dx, y, cz + dz))) {
                        placeBlock(pos, Blocks.AIR.defaultBlockState());
                        arrowSlits.add(pos);
                    } else if (!shouldRuin(y, node, rng)) {
                        placeBlock(pos, pickTowerBlock(rng));
                    }
                } else {
                    // Interior: air for rooms
                    placeBlock(pos, Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // SQUARE tower
    // -------------------------------------------------------------------------

    private void placeSquareRing(CastleLayout.TowerNode node, int y, boolean isCap,
                                 RandomSource rng, List<BlockPos> capRing,
                                 List<BlockPos> arrowSlits) {
        int cx = node.center().getX();
        int cz = node.center().getZ();
        int r  = node.radius();
        int th = style.wallThickness();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                boolean isShell = Math.abs(dx) > r - th || Math.abs(dz) > r - th;
                BlockPos pos    = new BlockPos(cx + dx, y, cz + dz);

                if (isShell) {
                    if (isCap) {
                        placeBlock(pos, style.primaryMaterial().pickMain(rng));
                        capRing.add(pos);
                    } else if (isArrowSlitPosition(node, y, pos)) {
                        placeBlock(pos, Blocks.AIR.defaultBlockState());
                        arrowSlits.add(pos);
                    } else if (!shouldRuin(y, node, rng)) {
                        placeBlock(pos, pickTowerBlock(rng));
                    }
                } else {
                    placeBlock(pos, Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // D-SHAPED tower (semicircle projecting outward from the facing direction)
    // -------------------------------------------------------------------------

    private void placeDShapedRing(CastleLayout.TowerNode node, int y, boolean isCap,
                                  RandomSource rng, List<BlockPos> capRing,
                                  List<BlockPos> arrowSlits) {
        int cx = node.center().getX();
        int cz = node.center().getZ();
        int r  = node.radius();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                // Only the outward half
                if (!isInOutwardHalf(dx, dz, node.facing())) continue;

                double dist    = Math.sqrt(dx * dx + dz * dz);
                boolean inCirc = dist <= r;
                boolean isShell = dist > r - style.wallThickness();
                if (!inCirc) continue;

                BlockPos pos = new BlockPos(cx + dx, y, cz + dz);
                if (isShell) {
                    if (isCap) {
                        placeBlock(pos, style.primaryMaterial().pickMain(rng));
                        capRing.add(pos);
                    } else if (!shouldRuin(y, node, rng)) {
                        placeBlock(pos, pickTowerBlock(rng));
                    }
                } else {
                    placeBlock(pos, Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // POLYGONAL (octagon) tower
    // -------------------------------------------------------------------------

    private void placeOctagonRing(CastleLayout.TowerNode node, int y, boolean isCap,
                                  RandomSource rng, List<BlockPos> capRing,
                                  List<BlockPos> arrowSlits) {
        int cx = node.center().getX();
        int cz = node.center().getZ();
        int r  = node.radius();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                // Octagon: Chebyshev + L1 combined
                if (!isInsideOctagon(dx, dz, r)) continue;

                boolean isShell = isOctagonShell(dx, dz, r, style.wallThickness());
                BlockPos pos    = new BlockPos(cx + dx, y, cz + dz);

                if (isShell) {
                    if (isCap) {
                        placeBlock(pos, style.primaryMaterial().pickMain(rng));
                        capRing.add(pos);
                    } else if (!shouldRuin(y, node, rng)) {
                        placeBlock(pos, pickTowerBlock(rng));
                    }
                } else {
                    placeBlock(pos, Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Floor placement
    // -------------------------------------------------------------------------

    private void placeTowerFloor(CastleLayout.TowerNode node, int y, RandomSource rng) {
        int cx = node.center().getX();
        int cz = node.center().getZ();
        int r  = node.radius() - style.wallThickness(); // floor inside the shell

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!isInsideShape(node, dx, dz)) continue;
                BlockPos pos = new BlockPos(cx + dx, y, cz + dz);
                // Use floor material slab for the top floor level
                if (style.floorMaterial().hasSlab()) {
                    placeBlock(pos, style.floorMaterial().slabState(
                            net.minecraft.world.level.block.state.properties.SlabType.BOTTOM));
                } else {
                    placeBlock(pos, style.floorMaterial().pickMain(rng));
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Interior ladders
    // -------------------------------------------------------------------------

    private void placeInteriorLadders(CastleLayout.TowerNode node, int groundY, int topY) {
        // Place ladders on the inner north wall face at each floor
        BlockPos ladderBase = node.center().relative(Direction.SOUTH,
                node.radius() - style.wallThickness());
        for (int y = groundY + 1; y < topY; y++) {
            BlockPos ladderPos = ladderBase.atY(y);
            BlockState ladder  = Blocks.LADDER.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.LadderBlock.FACING, Direction.SOUTH);
            level.setBlock(ladderPos, ladder, Block.UPDATE_CLIENTS);
        }
    }

    // -------------------------------------------------------------------------
    // Foundation
    // -------------------------------------------------------------------------

    private void fillFoundation(CastleLayout.TowerNode node, int fromY, int toY, RandomSource rng) {
        int cx = node.center().getX();
        int cz = node.center().getZ();
        int r  = node.radius();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!isInsideShapeInclusive(node, dx, dz)) continue;
                for (int y = fromY; y <= toY; y++) {
                    BlockPos pos = new BlockPos(cx + dx, y, cz + dz);
                    BlockState current = level.getBlockState(pos);
                    if (current.isAir() || current.is(Blocks.WATER)) {
                        placeBlock(pos, style.primaryMaterial().pickMain(rng));
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Arrow slit logic
    // -------------------------------------------------------------------------

    private boolean isArrowSlitPosition(CastleLayout.TowerNode node, int y, BlockPos pos) {
        // Place arrow slits every FLOOR_INTERVAL height, at cardinal-facing positions
        int groundY  = terrain.maxGroundYInRadius(node.center(), node.radius());
        boolean inBand = ((y - groundY) % FLOOR_INTERVAL == FLOOR_INTERVAL / 2);
        if (!inBand) return false;

        // Only on cardinal-facing shell blocks
        int dx = pos.getX() - node.center().getX();
        int dz = pos.getZ() - node.center().getZ();
        int r  = node.radius() - 1;
        return (Math.abs(dx) == r && dz == 0) || (Math.abs(dz) == r && dx == 0);
    }

    // -------------------------------------------------------------------------
    // Shape helpers
    // -------------------------------------------------------------------------

    private boolean isInsideShape(CastleLayout.TowerNode node, int dx, int dz) {
        int interior = node.radius() - style.wallThickness();
        return switch (style.towerShape()) {
            case ROUND     -> dx * dx + dz * dz <= interior * interior;
            case SQUARE    -> Math.abs(dx) <= interior && Math.abs(dz) <= interior;
            case D_SHAPED  -> dx * dx + dz * dz <= interior * interior
                    && isInOutwardHalf(dx, dz, node.facing());
            case POLYGONAL -> isInsideOctagon(dx, dz, interior);
        };
    }

    private boolean isInsideShapeInclusive(CastleLayout.TowerNode node, int dx, int dz) {
        int r = node.radius();
        return switch (style.towerShape()) {
            case ROUND     -> dx * dx + dz * dz <= r * r;
            case SQUARE    -> Math.abs(dx) <= r && Math.abs(dz) <= r;
            case D_SHAPED  -> dx * dx + dz * dz <= r * r
                    && isInOutwardHalf(dx, dz, node.facing());
            case POLYGONAL -> isInsideOctagon(dx, dz, r);
        };
    }

    private boolean isInOutwardHalf(int dx, int dz, Direction facing) {
        return switch (facing) {
            case NORTH -> dz <= 0;
            case SOUTH -> dz >= 0;
            case EAST  -> dx >= 0;
            case WEST  -> dx <= 0;
            default    -> true;
        };
    }

    private boolean isInsideOctagon(int dx, int dz, int r) {
        return Math.abs(dx) + Math.abs(dz) <= r * 3 / 2
                && Math.abs(dx) <= r && Math.abs(dz) <= r;
    }

    private boolean isOctagonShell(int dx, int dz, int r, int thickness) {
        return isInsideOctagon(dx, dz, r)
                && !isInsideOctagon(dx, dz, r - thickness);
    }

    // -------------------------------------------------------------------------
    // Ruination
    // -------------------------------------------------------------------------

    private boolean shouldRuin(int y, CastleLayout.TowerNode node, RandomSource rng) {
        float ruin = style.ruinationLevel();
        if (ruin <= 0f) return false;
        int groundY = terrain.maxGroundYInRadius(node.center(), node.radius());
        int topY    = resolveTopY(node);
        float frac  = (float)(y - groundY) / Math.max(1, topY - groundY);
        return rng.nextFloat() < ruin * frac;
    }

    // -------------------------------------------------------------------------
    // Y calculation
    // -------------------------------------------------------------------------

    private int resolveTopY(CastleLayout.TowerNode node) {
        int groundY = terrain.maxGroundYInRadius(node.center(), node.radius());
        return groundY + node.wallHeight() + style.towerHeightBonus();
    }

    private boolean isFloorLevel(int y, int groundY) {
        return (y - groundY) % FLOOR_INTERVAL == 0 && y > groundY;
    }

    // -------------------------------------------------------------------------
    // Block helpers
    // -------------------------------------------------------------------------

    private BlockState pickTowerBlock(RandomSource rng) {
        if (rng.nextFloat() < 0.12f) return style.primaryMaterial().pickCracked(rng);
        return style.primaryMaterial().pickMain(rng);
    }

    private void placeBlock(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    // -------------------------------------------------------------------------
    // PlacementRecord
    // -------------------------------------------------------------------------

    public record PlacementRecord(
            List<BlockPos> capRingPositions,  // Top ring — roof/battlement template goes here
            List<BlockPos> floorPositions,    // Interior floor positions (for furniture etc.)
            List<BlockPos> arrowSlitPositions,
            CastleLayout.TowerNode node
    ) {}
}
