package tterrag1112.life_in_the_village.Village.Farms.Complex.Render;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Random;

/**
 * Detour A Prompt B Stage E — code-generated tool shed
 * placeholder.
 *
 * <p>4×4 footprint. Floor + walls oak planks. Flat roof of dark
 * oak slabs. Door on the configurable side; chest + crafting
 * table inside; lantern on the wall above the door.
 *
 * <p>Placeholder pending NBT authoring. The hardcoded generator
 * is intentionally simple — when an authored NBT is available it
 * replaces this renderer's body, the call shape stays the same.
 */
public final class ToolShedRenderer {

    /** Inset of the shed's NW corner from {@code toolShedPosition}.
     *  The position is treated as the centre; the shed extends a
     *  half-side in each direction. */
    private static final int FOOTPRINT = 4;
    private static final int WALL_HEIGHT = 3;

    private static final BlockState PLANKS = Blocks.OAK_PLANKS.defaultBlockState();
    private static final BlockState ROOF   = Blocks.DARK_OAK_SLAB.defaultBlockState();
    private static final BlockState CHEST  = Blocks.CHEST.defaultBlockState();
    private static final BlockState BENCH  = Blocks.CRAFTING_TABLE.defaultBlockState();
    private static final BlockState LANTERN = Blocks.LANTERN.defaultBlockState();

    private ToolShedRenderer() {}

    /** Render the shed at {@code centre} (Y resolved per-column).
     *  Returns true iff at least the floor + walls landed.
     *  {@code doorFacing} controls which 4×1 wall hosts the door
     *  — Stage G picks the direction nearest the spine path. */
    public static boolean render(BlockPos centre, Direction doorFacing,
                                  ServerLevel level, Random rng) {
        if (centre == null) return false;
        int half = FOOTPRINT / 2;
        int minX = centre.getX() - half;
        int minZ = centre.getZ() - half;
        int maxX = minX + FOOTPRINT - 1;
        int maxZ = minZ + FOOTPRINT - 1;

        // Ground level: take the median heightmap value across the
        // four corners so the shed sits flat even on mildly sloped
        // ground.
        int gy = medianGroundY(level, minX, minZ, maxX, maxZ);
        if (gy <= 0) return false;
        int floorY = gy;        // floor sits at ground
        int ceilingY = floorY + WALL_HEIGHT;

        // Floor (plank platform).
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                level.setBlock(new BlockPos(x, floorY, z), PLANKS, 3);
            }
        }
        // Walls — perimeter only.
        for (int dy = 1; dy <= WALL_HEIGHT; dy++) {
            int y = floorY + dy;
            for (int x = minX; x <= maxX; x++) {
                place(level, new BlockPos(x, y, minZ), PLANKS);
                place(level, new BlockPos(x, y, maxZ), PLANKS);
            }
            for (int z = minZ; z <= maxZ; z++) {
                place(level, new BlockPos(minX, y, z), PLANKS);
                place(level, new BlockPos(maxX, y, z), PLANKS);
            }
        }
        // Flat roof.
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                place(level, new BlockPos(x, ceilingY + 1, z), ROOF);
            }
        }

        // Door — punch out two wall blocks on the doorFacing side
        // and stamp a door.
        Direction df = doorFacing == null ? Direction.SOUTH : doorFacing;
        int doorX, doorZ;
        switch (df) {
            case NORTH -> { doorX = (minX + maxX) / 2; doorZ = minZ; }
            case SOUTH -> { doorX = (minX + maxX) / 2; doorZ = maxZ; }
            case WEST  -> { doorX = minX;              doorZ = (minZ + maxZ) / 2; }
            case EAST  -> { doorX = maxX;              doorZ = (minZ + maxZ) / 2; }
            default    -> { doorX = (minX + maxX) / 2; doorZ = maxZ; }
        }
        BlockPos doorLower = new BlockPos(doorX, floorY + 1, doorZ);
        BlockPos doorUpper = new BlockPos(doorX, floorY + 2, doorZ);
        // Door block — facing pointed AWAY from interior (outward
        // is df since maxZ side faces df=SOUTH etc.).
        BlockState doorBlock = Blocks.OAK_DOOR.defaultBlockState();
        if (doorBlock.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            doorBlock = doorBlock.setValue(
                    BlockStateProperties.HORIZONTAL_FACING, df);
        }
        if (doorBlock.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            level.setBlock(doorLower, doorBlock.setValue(
                    BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER), 3);
            level.setBlock(doorUpper, doorBlock.setValue(
                    BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), 3);
        }
        // Lantern above the door, on the wall (one block toward
        // interior from the door, at ceiling + 1 — sits on roof).
        Direction inward = df.getOpposite();
        BlockPos lanternPos = doorUpper.relative(inward).above();
        place(level, lanternPos, LANTERN);

        // Furnishings — chest in one interior corner, crafting
        // table in another. Pick deterministically from doorFacing.
        BlockPos chestPos = pickInteriorCorner(minX, minZ, maxX, maxZ, floorY, df, 0);
        BlockPos benchPos = pickInteriorCorner(minX, minZ, maxX, maxZ, floorY, df, 1);
        place(level, chestPos, CHEST);
        place(level, benchPos, BENCH);

        return true;
    }

    private static int medianGroundY(ServerLevel level, int minX, int minZ,
                                       int maxX, int maxZ) {
        int[] ys = new int[]{
                level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, minX, minZ) - 1,
                level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, maxX, minZ) - 1,
                level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, minX, maxZ) - 1,
                level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, maxX, maxZ) - 1
        };
        java.util.Arrays.sort(ys);
        return ys[1];  // lower-median; biases toward the lower
                       // corners so the shed isn't suspended in air
    }

    private static BlockPos pickInteriorCorner(int minX, int minZ,
                                                 int maxX, int maxZ,
                                                 int floorY, Direction doorFacing,
                                                 int variant) {
        // Two opposite interior corners on the wall AWAY from the door.
        int ix = (variant == 0) ? minX + 1 : maxX - 1;
        int iz = (doorFacing == Direction.SOUTH || doorFacing == Direction.NORTH)
                ? (doorFacing == Direction.SOUTH ? minZ + 1 : maxZ - 1)
                : (variant == 0 ? minZ + 1 : maxZ - 1);
        return new BlockPos(ix, floorY + 1, iz);
    }

    private static void place(ServerLevel level, BlockPos pos, BlockState state) {
        BlockState cur = level.getBlockState(pos);
        if (cur.isAir() || cur.canBeReplaced()) {
            level.setBlock(pos, state, 3);
        }
    }
}
