package tterrag1112.life_in_the_village.Kingdom.Castle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Post-generation pass that applies ruination effects to an already-built castle.
 *
 * Runs after all structural placement and NBT template stamping so it can
 * affect both procedural blocks and template-placed blocks uniformly.
 *
 * All block choices go through ArchitectPalette so the ruination material
 * matches the style (deepslate ruins use deepslate cracked variants, etc.).
 *
 * Consumes StackPlacementRecord — no dependency on deleted files.
 */
public class RuinationPass {

    private final LevelAccessor level;
    private final CastleStyle style;
    private final ArchitectPalette palette;

    public RuinationPass(LevelAccessor level, CastleStyle style) {
        this.level   = level;
        this.style   = style;
        this.palette = style.palette();
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    public void apply(CastleLayout layout,
                      List<StackPlacementRecord> wallRecords,
                      List<StackPlacementRecord> towerRecords,
                      StackPlacementRecord gateRecord,
                      RandomSource rng) {

        float ruin = style.ruinationLevel();
        if (ruin <= 0f) return;

        // ---- Wall ruination ----
        for (StackPlacementRecord rec : wallRecords) {
            ruinTopCourse(rec.topCoursePositions(), ruin, rng);
            if (style.features().addVines()) {
                growVines(rec.topCoursePositions(), rec.outwardFacing(), ruin, rng);
            }
            if (style.features().addRubble()) {
                scatterRubble(rec.stack(), layout, ruin, rng);
            }
        }

        // ---- Tower ruination ----
        for (StackPlacementRecord rec : towerRecords) {
            ruinTopCourse(rec.capRingPositions(), ruin, rng);
            swapCrackedBlocks(rec.capRingPositions(), ruin, rng);
            if (style.features().addVines()) {
                growVinesOnStack(rec.stack(), ruin, rng);
            }
        }

        // ---- Gatehouse ruination ----
        if (gateRecord != null) {
            ruinTopCourse(gateRecord.capRingPositions(), ruin, rng);
            swapCrackedBlocks(gateRecord.capRingPositions(), ruin, rng);
            if (style.features().addVines()) {
                growVinesOnStack(gateRecord.stack(), ruin, rng);
            }
        }

        // ---- Moss on walkway surfaces ----
        if (ruin > 0.3f) {
            for (StackPlacementRecord rec : wallRecords) {
                growMoss(rec.walkwayPositions(), ruin, rng);
            }
        }
    }

    // =========================================================================
    // Top course collapse
    // =========================================================================

    private void ruinTopCourse(List<BlockPos> topCourse, float ruin, RandomSource rng) {
        for (BlockPos pos : topCourse) {
            if (rng.nextFloat() < ruin * 0.8f) {
                placeBlock(pos.above(), Blocks.AIR.defaultBlockState());
            }
            if (rng.nextFloat() < ruin * 0.5f) {
                placeBlock(pos, Blocks.AIR.defaultBlockState());
                if (ruin > 0.7f && rng.nextFloat() < ruin * 0.4f) {
                    placeBlock(pos.below(), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    // =========================================================================
    // Block swapping — fill → cracked
    // =========================================================================

    private void swapCrackedBlocks(List<BlockPos> positions, float ruin,
                                   RandomSource rng) {
        for (BlockPos pos : positions) {
            if (level.getBlockState(pos).isAir()) continue;
            if (rng.nextFloat() < ruin * 0.6f) {
                placeBlock(pos, palette.pick("cracked", rng));
            }
        }
    }

    // =========================================================================
    // Vine growth
    // =========================================================================

    private void growVines(List<BlockPos> topCourse, Direction outwardFacing,
                           float ruin, RandomSource rng) {
        int maxLength = (int)(ruin * 8) + 1;
        for (BlockPos top : topCourse) {
            if (rng.nextFloat() > ruin * 0.6f) continue;
            int length = 1 + rng.nextInt(maxLength);
            for (int i = 1; i <= length; i++) {
                BlockPos vinePos = top.below(i);
                if (!level.getBlockState(vinePos).isAir()) break;
                placeBlock(vinePos, vineState(outwardFacing.getOpposite()));
            }
        }
    }

    private void growVinesOnStack(RoomStack stack, float ruin, RandomSource rng) {
        int maxLen = (int)(ruin * 6) + 1;
        int topY   = stack.topY();
        int ox     = stack.origin().getX();
        int oz     = stack.origin().getZ();
        int ow     = stack.outerWidthX();
        int od     = stack.outerDepthZ();

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            for (int i = 0; i < (dir == Direction.NORTH || dir == Direction.SOUTH ? ow : od); i++) {
                if (rng.nextFloat() > ruin * 0.4f) continue;
                BlockPos facePos = switch (dir) {
                    case NORTH -> new BlockPos(ox + i, topY, oz);
                    case SOUTH -> new BlockPos(ox + i, topY, oz + od - 1);
                    case WEST  -> new BlockPos(ox, topY, oz + i);
                    case EAST  -> new BlockPos(ox + ow - 1, topY, oz + i);
                    default    -> new BlockPos(ox, topY, oz);
                };
                int len = 1 + rng.nextInt(maxLen);
                for (int h = 0; h < len; h++) {
                    BlockPos vinePos = facePos.below(h);
                    if (!level.getBlockState(vinePos).isAir()) break;
                    placeBlock(vinePos, vineState(dir.getOpposite()));
                }
            }
        }
    }

    private BlockState vineState(Direction attachFace) {
        BlockState vine = Blocks.VINE.defaultBlockState();
        return switch (attachFace) {
            case NORTH -> vine.setValue(VineBlock.NORTH, true);
            case SOUTH -> vine.setValue(VineBlock.SOUTH, true);
            case EAST  -> vine.setValue(VineBlock.EAST,  true);
            case WEST  -> vine.setValue(VineBlock.WEST,  true);
            default    -> vine;
        };
    }

    // =========================================================================
    // Rubble scattering
    // =========================================================================

    private void scatterRubble(RoomStack stack, CastleLayout layout,
                               float ruin, RandomSource rng) {
        int ox = stack.origin().getX();
        int oz = stack.origin().getZ();
        int ow = stack.outerWidthX();
        int od = stack.outerDepthZ();

        // Sample perimeter positions only
        for (int i = 0; i < ow + od; i++) {
            if (rng.nextFloat() > ruin * 0.4f) continue;

            int rx, rz;
            if (i < ow) {
                rx = ox + i + rng.nextIntBetweenInclusive(-2, 2);
                rz = (rng.nextBoolean() ? oz - 2 : oz + od + 2)
                        + rng.nextIntBetweenInclusive(-1, 1);
            } else {
                rz = oz + (i - ow) + rng.nextIntBetweenInclusive(-2, 2);
                rx = (rng.nextBoolean() ? ox - 2 : ox + ow + 2)
                        + rng.nextIntBetweenInclusive(-1, 1);
            }

            int ry = naturalTerrainY(rx, rz);
            if (ry == Integer.MIN_VALUE) continue;

            BlockPos rubblePos = new BlockPos(rx, ry + 1, rz);
            if (!level.getBlockState(rubblePos).isAir()) continue;

            placeBlock(rubblePos, rng.nextFloat() < 0.6f
                    ? palette.pick("cracked", rng)
                    : Blocks.GRAVEL.defaultBlockState());
        }
    }

    // =========================================================================
    // Moss
    // =========================================================================

    private void growMoss(List<BlockPos> walkwayPositions, float ruin,
                          RandomSource rng) {
        float prob = (ruin - 0.3f) * 0.8f;
        for (BlockPos pos : walkwayPositions) {
            if (!level.getBlockState(pos.above()).isAir()) continue;
            if (rng.nextFloat() < prob) {
                placeBlock(pos.above(), Blocks.MOSS_CARPET.defaultBlockState());
            }
        }
    }

    // =========================================================================
    // Natural terrain scan
    // =========================================================================

    private int naturalTerrainY(int x, int z) {
        for (int y = level.getMaxY() - 1; y >= level.getMinY(); y--) {
            BlockState s = level.getBlockState(new BlockPos(x, y, z));
            if (isNaturalTerrain(s)) return y;
        }
        return Integer.MIN_VALUE;
    }

    private boolean isNaturalTerrain(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT)
                || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.PODZOL)
                || s.is(Blocks.STONE) || s.is(Blocks.DEEPSLATE)
                || s.is(Blocks.SAND) || s.is(Blocks.GRAVEL)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.SNOW_BLOCK)
                || s.is(Blocks.PACKED_ICE);
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private void placeBlock(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }
}