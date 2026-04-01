package tterrag1112.life_in_the_village.Kingdom.Castle.Placers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import tterrag1112.life_in_the_village.Kingdom.Castle.*;

import java.util.ArrayList;
import java.util.List;

import static tterrag1112.life_in_the_village.Kingdom.Castle.CastleConstants.*;

/**
 * Places a single cylindrical or polygonal tower at a given TowerNode.
 *
 * Handles four tower shapes:
 *   ROUND      — circular cross-section
 *   SQUARE     — square cross-section
 *   D_SHAPED   — semicircle projecting outward from the facing direction
 *   POLYGONAL  — octagonal cross-section
 *
 * All block choices go through ArchitectPalette slots:
 *   "fill"        — main shell blocks
 *   "fill_alt"    — occasional variation in shell
 *   "cracked"     — ruination swaps
 *   "floor"       — interior floor slabs
 *   "support"     — arrow slit surround / quoin accent
 *
 * Also handles:
 *   - Terrain foundation
 *   - Interior floor slabs at FLOOR_INTERVAL
 *   - Ladder on north inner wall
 *   - Arrow slits on cardinal faces
 *   - Battered base (if StyleDetailConfig.hasBatteredBase)
 *   - Quoins (if StyleDetailConfig.hasQuoins, square towers only)
 *   - Wall-walk doorway carving (called post-build by RoomStackBuilder)
 *   - Cap ring cleared of floating blocks
 */
public class TowerPlacer {

    private final LevelAccessor level;
    private final CastleStyle style;
    private final TerrainSampler terrain;
    private final ArchitectPalette palette;
    private final StyleDetailConfig detail;

    public TowerPlacer(LevelAccessor level, CastleStyle style, TerrainSampler terrain) {
        this.level   = level;
        this.style   = style;
        this.terrain = terrain;
        this.palette = style.palette();
        this.detail  = style.styleDetail();
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    public PlacementRecord place(CastleLayout.TowerNode node, RandomSource rng) {
        int groundY   = terrain.maxGroundYInRadius(node.center(), node.radius());
        int towerTopY = resolveTopY(node, groundY);
        int bottomY   = groundY - 2;

        fillFoundation(node, bottomY, groundY, rng);

        List<BlockPos> capRing    = new ArrayList<>();
        List<BlockPos> floorPosns = new ArrayList<>();
        List<BlockPos> arrowSlits = new ArrayList<>();

        for (int y = groundY; y <= towerTopY; y++) {
            boolean isCap   = (y == towerTopY);
            boolean isFloor = isFloorLevel(y, groundY) && !isCap;

            placeTowerRing(node, y, isCap, groundY, towerTopY, rng,
                    capRing, arrowSlits);

            if (isFloor) {
                placeTowerFloor(node, y, rng);
                floorPosns.add(node.center().atY(y));
            }
        }

        placeInteriorLadders(node, groundY, towerTopY);

        if (detail.hasBatteredBase()) {
            placeBatteredBase(node, groundY, towerTopY, rng);
        }
        if (detail.hasQuoins()
                && style.towers().towerShape() == CastleStyle.TowerShape.SQUARE) {
            placeQuoins(node, groundY, towerTopY, rng);
        }

        clearAboveCap(node, towerTopY);

        int wallWalkY = groundY + node.wallHeight() - WALKWAY_CLEAR_HEIGHT - 1;

        return new PlacementRecord(capRing, floorPosns, arrowSlits, node, wallWalkY);
    }

    // =========================================================================
    // Wall-walk doorway — called by RoomStackBuilder after all walls are placed
    // =========================================================================

    /**
     * Carves a doorway through the tower shell at wall-walk height in the
     * given direction so players can walk from a wall segment into the tower.
     */
    public void carveWallWalkDoorway(CastleLayout.TowerNode node,
                                     Direction wallDir,
                                     int walkFloorY,
                                     RandomSource rng) {
        int innerR = node.radius() - style.walls().wallThickness();

        for (int d = innerR; d <= node.radius() + 1; d++) {
            BlockPos column = node.center().relative(wallDir, d);
            for (int h = 0; h < WALKWAY_CLEAR_HEIGHT; h++) {
                level.setBlock(column.atY(walkFloorY + h),
                        Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }

        // Stone floor threshold at the doorway entrance
        BlockPos threshold = node.center()
                .relative(wallDir, innerR)
                .atY(walkFloorY - 1);
        if (level.getBlockState(threshold).isAir()) {
            placeBlock(threshold, palette.pick("floor", rng));
        }
    }

    /**
     * Post-pass called by RoomStackBuilder after all walls are placed.
     * Carves doorways into every tower for each wall segment that connects to it.
     */
    public void carveAllWallConnections(CastleLayout layout,
                                        List<StackPlacementRecord> wallRecords,
                                        RandomSource rng) {
        for (StackPlacementRecord wallRec : wallRecords) {
            if (wallRec.walkwayFloorY() <= wallRec.stack().origin().getY()) continue;

            BlockPos wallStart = wallRec.stack().origin();
            BlockPos wallEnd   = new BlockPos(
                    wallRec.stack().origin().getX() + wallRec.stack().outerWidthX(),
                    wallRec.stack().origin().getY(),
                    wallRec.stack().origin().getZ() + wallRec.stack().outerDepthZ());

            for (CastleLayout.TowerNode tower : layout.getOuterTowers()) {
                double ds = tower.center().distSqr(wallStart);
                double de = tower.center().distSqr(wallEnd);
                if (ds > 400 && de > 400) continue;

                Direction dir = ds < de
                        ? approxDir(tower.center(), wallStart)
                        : approxDir(tower.center(), wallEnd);

                carveWallWalkDoorway(tower, dir, wallRec.walkwayFloorY(), rng);
            }

            // Inner ward connections
            if (layout.hasInnerWard()) {
                for (CastleLayout.TowerNode tower : layout.getInnerWard().towers()) {
                    double ds = tower.center().distSqr(wallStart);
                    double de = tower.center().distSqr(wallEnd);
                    if (ds > 400 && de > 400) continue;

                    Direction dir = ds < de
                            ? approxDir(tower.center(), wallStart)
                            : approxDir(tower.center(), wallEnd);

                    carveWallWalkDoorway(tower, dir, wallRec.walkwayFloorY(), rng);
                }
            }
        }
    }

    // =========================================================================
    // Ring placement dispatch
    // =========================================================================

    private void placeTowerRing(CastleLayout.TowerNode node, int y, boolean isCap,
                                int groundY, int towerTopY, RandomSource rng,
                                List<BlockPos> capRing, List<BlockPos> arrowSlits) {
        switch (style.towers().towerShape()) {
            case ROUND     -> placeRoundRing(node, y, isCap, groundY, towerTopY,
                    rng, capRing, arrowSlits);
            case SQUARE    -> placeSquareRing(node, y, isCap, groundY, towerTopY,
                    rng, capRing, arrowSlits);
            case D_SHAPED  -> placeDShapedRing(node, y, isCap, groundY, towerTopY,
                    rng, capRing, arrowSlits);
            case POLYGONAL -> placeOctagonRing(node, y, isCap, groundY, towerTopY,
                    rng, capRing, arrowSlits);
        }
    }

    // =========================================================================
    // ROUND
    // =========================================================================

    private void placeRoundRing(CastleLayout.TowerNode node, int y, boolean isCap,
                                int groundY, int towerTopY, RandomSource rng,
                                List<BlockPos> capRing, List<BlockPos> arrowSlits) {
        int cx = node.center().getX();
        int cz = node.center().getZ();
        int r  = node.radius();
        int th = style.walls().wallThickness();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double dist    = Math.sqrt(dx * dx + dz * dz);
                if (dist > r) continue;

                boolean isShell = dist > r - th;
                BlockPos pos    = new BlockPos(cx + dx, y, cz + dz);

                if (isShell) {
                    if (shouldRuin(y, groundY, towerTopY, rng)) continue;
                    if (isCap) {
                        placeBlock(pos, palette.pick("fill", rng));
                        capRing.add(pos);
                    } else if (isArrowSlitPos(node, y, groundY, dx, dz)) {
                        placeBlock(pos, Blocks.AIR.defaultBlockState());
                        arrowSlits.add(pos);
                    } else {
                        placeBlock(pos, pickTowerBlock(rng));
                    }
                } else {
                    placeBlock(pos, Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    // =========================================================================
    // SQUARE
    // =========================================================================

    private void placeSquareRing(CastleLayout.TowerNode node, int y, boolean isCap,
                                 int groundY, int towerTopY, RandomSource rng,
                                 List<BlockPos> capRing, List<BlockPos> arrowSlits) {
        int cx = node.center().getX();
        int cz = node.center().getZ();
        int r  = node.radius();
        int th = style.walls().wallThickness();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                boolean isShell = Math.abs(dx) > r - th || Math.abs(dz) > r - th;
                BlockPos pos    = new BlockPos(cx + dx, y, cz + dz);

                if (isShell) {
                    if (shouldRuin(y, groundY, towerTopY, rng)) continue;
                    if (isCap) {
                        placeBlock(pos, palette.pick("fill", rng));
                        capRing.add(pos);
                    } else if (isArrowSlitPos(node, y, groundY, dx, dz)) {
                        placeBlock(pos, Blocks.AIR.defaultBlockState());
                        arrowSlits.add(pos);
                    } else {
                        placeBlock(pos, pickTowerBlock(rng));
                    }
                } else {
                    placeBlock(pos, Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    // =========================================================================
    // D-SHAPED
    // =========================================================================

    private void placeDShapedRing(CastleLayout.TowerNode node, int y, boolean isCap,
                                  int groundY, int towerTopY, RandomSource rng,
                                  List<BlockPos> capRing, List<BlockPos> arrowSlits) {
        int cx = node.center().getX();
        int cz = node.center().getZ();
        int r  = node.radius();
        int th = style.walls().wallThickness();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!isInOutwardHalf(dx, dz, node.facing())) continue;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > r) continue;

                boolean isShell = dist > r - th;
                BlockPos pos    = new BlockPos(cx + dx, y, cz + dz);

                if (isShell) {
                    if (shouldRuin(y, groundY, towerTopY, rng)) continue;
                    if (isCap) {
                        placeBlock(pos, palette.pick("fill", rng));
                        capRing.add(pos);
                    } else {
                        placeBlock(pos, pickTowerBlock(rng));
                    }
                } else {
                    placeBlock(pos, Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    // =========================================================================
    // POLYGONAL (octagon)
    // =========================================================================

    private void placeOctagonRing(CastleLayout.TowerNode node, int y, boolean isCap,
                                  int groundY, int towerTopY, RandomSource rng,
                                  List<BlockPos> capRing, List<BlockPos> arrowSlits) {
        int cx = node.center().getX();
        int cz = node.center().getZ();
        int r  = node.radius();
        int th = style.walls().wallThickness();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!isInsideOctagon(dx, dz, r)) continue;

                boolean isShell = !isInsideOctagon(dx, dz, r - th);
                BlockPos pos    = new BlockPos(cx + dx, y, cz + dz);

                if (isShell) {
                    if (shouldRuin(y, groundY, towerTopY, rng)) continue;
                    if (isCap) {
                        placeBlock(pos, palette.pick("fill", rng));
                        capRing.add(pos);
                    } else {
                        placeBlock(pos, pickTowerBlock(rng));
                    }
                } else {
                    placeBlock(pos, Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    // =========================================================================
    // Floor slabs
    // =========================================================================

    private void placeTowerFloor(CastleLayout.TowerNode node, int y, RandomSource rng) {
        int cx        = node.center().getX();
        int cz        = node.center().getZ();
        int interiorR = node.radius() - style.walls().wallThickness();

        for (int dx = -interiorR; dx <= interiorR; dx++) {
            for (int dz = -interiorR; dz <= interiorR; dz++) {
                if (!isInsideInterior(node, dx, dz)) continue;
                BlockPos pos = new BlockPos(cx + dx, y, cz + dz);

                BlockState floor = palette.slab("slab", SlabType.BOTTOM);
                placeBlock(pos, floor);
            }
        }
    }

    // =========================================================================
    // Interior ladders
    // =========================================================================

    /**
     * Ladder on the north inner wall face. FACING=SOUTH means the ladder
     * attaches to the block to its north and the player faces south while climbing.
     */
    private void placeInteriorLadders(CastleLayout.TowerNode node,
                                      int groundY, int topY) {
        int interiorR = node.radius() - style.walls().wallThickness();
        BlockPos base = node.center()
                .relative(Direction.NORTH, interiorR)
                .atY(groundY + 1);

        for (int y = groundY + 1; y < topY; y++) {
            BlockPos pos        = base.atY(y);
            BlockPos attachesTo = pos.north();
            if (level.getBlockState(attachesTo).isSolid()) {
                placeBlock(pos, Blocks.LADDER.defaultBlockState()
                        .setValue(LadderBlock.FACING, Direction.SOUTH));
            }
        }
    }

    // =========================================================================
    // Battered base
    // =========================================================================

    private void placeBatteredBase(CastleLayout.TowerNode node,
                                   int groundY, int topY, RandomSource rng) {
        int batterHeight = (topY - groundY) / 3;
        int cx = node.center().getX();
        int cz = node.center().getZ();

        for (int layer = 0; layer < batterHeight; layer++) {
            int y     = groundY + layer;
            int extra = (batterHeight - layer) / 3;
            if (extra == 0) continue;

            int r = node.radius() + extra;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (isInsideShapeInclusive(node, dx, dz)) continue;
                    if (Math.abs(dx) > r || Math.abs(dz) > r) continue;
                    BlockPos pos = new BlockPos(cx + dx, y, cz + dz);
                    if (level.getBlockState(pos).isAir()) {
                        placeBlock(pos, palette.pick("fill", rng));
                    }
                }
            }
        }
    }

    // =========================================================================
    // Quoins (square towers only)
    // =========================================================================

    private void placeQuoins(CastleLayout.TowerNode node,
                             int groundY, int topY, RandomSource rng) {
        int cx = node.center().getX();
        int cz = node.center().getZ();
        int r  = node.radius();

        int[][] corners = {{-r, -r}, {-r, r}, {r, -r}, {r, r}};
        for (int[] corner : corners) {
            for (int y = groundY; y <= topY; y++) {
                if (y % 2 == 0) {
                    placeBlock(new BlockPos(cx + corner[0], y, cz + corner[1]),
                            palette.pick("trim", rng));
                }
            }
        }
    }

    // =========================================================================
    // Clear above cap — prevents floating blocks from wall inflation
    // =========================================================================

    private void clearAboveCap(CastleLayout.TowerNode node, int towerTopY) {
        int cx = node.center().getX();
        int cz = node.center().getZ();
        int r  = node.radius() + 1;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int above = 1; above <= 3; above++) {
                    BlockPos pos = new BlockPos(cx + dx, towerTopY + above, cz + dz);
                    BlockState s = level.getBlockState(pos);
                    if (!s.isAir()
                            && !s.is(Blocks.OAK_FENCE)
                            && !s.is(Blocks.WHITE_WALL_BANNER)
                            && !s.is(Blocks.LADDER)
                            && !s.is(Blocks.TORCH)) {
                        placeBlock(pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    // =========================================================================
    // Foundation
    // =========================================================================

    private void fillFoundation(CastleLayout.TowerNode node,
                                int fromY, int toY, RandomSource rng) {
        int cx = node.center().getX();
        int cz = node.center().getZ();
        int r  = node.radius();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!isInsideShapeInclusive(node, dx, dz)) continue;
                for (int y = fromY; y <= toY; y++) {
                    BlockPos pos = new BlockPos(cx + dx, y, cz + dz);
                    BlockState cur = level.getBlockState(pos);
                    if (cur.isAir() || cur.is(Blocks.WATER)) {
                        placeBlock(pos, palette.pick("fill", rng));
                    }
                }
            }
        }
    }

    // =========================================================================
    // Arrow slit logic
    // =========================================================================

    private boolean isArrowSlitPos(CastleLayout.TowerNode node,
                                   int y, int groundY, int dx, int dz) {
        // Only on cardinal-facing shell positions
        int r = node.radius() - 1;
        if (!((Math.abs(dx) == r && dz == 0) || (Math.abs(dz) == r && dx == 0))) {
            return false;
        }
        // Window style NONE = no slits
        if (detail.windowStyle() == StyleDetailConfig.WindowStyle.NONE) return false;

        // Height band — upper third of each floor interval
        boolean inBand = ((y - groundY) % FLOOR_INTERVAL == FLOOR_INTERVAL / 2);
        return inBand;
    }

    // =========================================================================
    // Shape helpers
    // =========================================================================

    private boolean isInsideInterior(CastleLayout.TowerNode node, int dx, int dz) {
        int ir = node.radius() - style.walls().wallThickness();
        return switch (style.towers().towerShape()) {
            case ROUND     -> dx * dx + dz * dz <= ir * ir;
            case SQUARE    -> Math.abs(dx) <= ir && Math.abs(dz) <= ir;
            case D_SHAPED  -> dx * dx + dz * dz <= ir * ir
                    && isInOutwardHalf(dx, dz, node.facing());
            case POLYGONAL -> isInsideOctagon(dx, dz, ir - 1);
        };
    }

    private boolean isInsideShapeInclusive(CastleLayout.TowerNode node, int dx, int dz) {
        int r = node.radius();
        return switch (style.towers().towerShape()) {
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
        return Math.abs(dx) <= r
                && Math.abs(dz) <= r
                && Math.abs(dx) + Math.abs(dz) <= r * 3 / 2;
    }

    // =========================================================================
    // Ruination
    // =========================================================================

    private boolean shouldRuin(int y, int groundY, int topY, RandomSource rng) {
        float ruin = style.ruinationLevel();
        if (ruin <= 0f) return false;
        float frac = (float)(y - groundY) / Math.max(1, topY - groundY);
        return rng.nextFloat() < ruin * frac;
    }

    // =========================================================================
    // Y helpers
    // =========================================================================

    private int resolveTopY(CastleLayout.TowerNode node, int groundY) {
        return groundY + node.wallHeight() + style.towers().towerHeightBonus();
    }

    private boolean isFloorLevel(int y, int groundY) {
        return (y - groundY) % FLOOR_INTERVAL == 0 && y > groundY;
    }

    // =========================================================================
    // Direction helper
    // =========================================================================

    private Direction approxDir(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz))
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    // =========================================================================
    // Block helpers
    // =========================================================================

    private BlockState pickTowerBlock(RandomSource rng) {
        // Occasional fill_alt for natural variation
        if (rng.nextFloat() < 0.12f) return palette.pick("fill_alt", rng);
        return palette.pick("fill", rng);
    }

    private void placeBlock(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    // =========================================================================
    // PlacementRecord
    // =========================================================================

    public record PlacementRecord(
            List<BlockPos> capRingPositions,
            List<BlockPos> floorPositions,
            List<BlockPos> arrowSlitPositions,
            CastleLayout.TowerNode node,
            int wallWalkY
    ) {}
}