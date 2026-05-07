package tterrag1112.life_in_the_village.Village.Planning.V2.Layer5;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.Footprint;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;

/**
 * V2 Layer 5 — execute terrain adaptation per building.
 *
 * <p>{@link TerrainAdapter.AdaptationMode#LEVEL}: cell-by-cell —
 * read the surface block once per column (don't bulldoze the
 * biome aesthetic), clear blocks above {@code targetY} to air,
 * fill from current ground up to {@code targetY} with that
 * column's surface block.
 *
 * <p>{@link TerrainAdapter.AdaptationMode#PLATFORM}: solid stone
 * pad. EVERY cell in the footprint is filled from
 * {@code surfaceY + 1} up through {@code targetY} with stone
 * bricks (matching the mod's great-roads palette — see
 * {@code RoadSupportBuilder.java:159} and
 * {@code RetainingWallBuilder.java:157} which fall back to
 * {@code Blocks.STONE_BRICKS}). Produces a solid pad with
 * full-column support down to natural ground — no floating
 * slabs.
 *
 * <p>{@link TerrainAdapter.AdaptationMode#DROP} is a no-op (the
 * spawner removes the building from the placement list before
 * calling this).
 */
public final class PadBuilder {

    /** Fill block for PLATFORM pads. Stone bricks match the mod's
     *  great-roads palette (RoadSupportBuilder, RetainingWallBuilder
     *  default fallbacks). */
    private static final BlockState PLATFORM_FILL =
            Blocks.STONE_BRICKS.defaultBlockState();

    private PadBuilder() {}

    public static void buildPad(ServerLevel level,
                                TerrainAdapter.AdaptationDecision decision) {
        switch (decision.mode()) {
            case LEVEL -> buildLevelPad(level, decision);
            case PLATFORM -> buildPlatformPad(level, decision);
            case DROP -> { /* no-op */ }
        }
    }

    private static void buildLevelPad(ServerLevel level,
                                      TerrainAdapter.AdaptationDecision d) {
        int[] aabb = footprintAabb(d.building());
        int targetY = d.targetY();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = aabb[0]; x <= aabb[2]; x++) {
            for (int z = aabb[1]; z <= aabb[3]; z++) {
                int surfY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                BlockState surfaceState = readSurfaceFill(level, cursor, x, surfY, z);

                // Clear blocks above targetY (down to first air or already-cleared).
                int top = Math.max(targetY, surfY) + 4;
                for (int y = top; y > targetY; y--) {
                    cursor.set(x, y, z);
                    BlockState bs = level.getBlockState(cursor);
                    if (!bs.isAir()) {
                        level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
                // Fill from surf+1 up to targetY with the surface block.
                for (int y = surfY + 1; y <= targetY; y++) {
                    cursor.set(x, y, z);
                    level.setBlock(cursor, surfaceState, 2);
                }
                // Drop terrain above targetY → already cleared above.
                // Surface block AT targetY (so the top is the natural
                // material rather than whatever was there).
                if (surfY > targetY) {
                    cursor.set(x, targetY, z);
                    level.setBlock(cursor, surfaceState, 2);
                }
            }
        }
    }

    private static void buildPlatformPad(ServerLevel level,
                                         TerrainAdapter.AdaptationDecision d) {
        int[] aabb = footprintAabb(d.building());
        int targetY = d.targetY();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minX = aabb[0], minZ = aabb[1], maxX = aabb[2], maxZ = aabb[3];

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // Solid stone pad: fill EVERY cell from one block above
                // natural ground all the way up to targetY (inclusive).
                // No floating slabs — full-column support.
                int surfY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                for (int y = surfY + 1; y <= targetY; y++) {
                    cursor.set(x, y, z);
                    level.setBlock(cursor, PLATFORM_FILL, 2);
                }

                // Clear any leftover blocks above the pad cap.
                for (int y = targetY + 1; y <= targetY + 4; y++) {
                    cursor.set(x, y, z);
                    BlockState bs = level.getBlockState(cursor);
                    if (!bs.isAir()) {
                        level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    /** Read the column's existing surface block state — used as the
     *  fill material for LEVEL pads so the pad blends with the local
     *  biome (grass on grass, sand on sand). */
    private static BlockState readSurfaceFill(ServerLevel level,
                                              BlockPos.MutableBlockPos cursor,
                                              int x, int surfY, int z) {
        cursor.set(x, surfY, z);
        BlockState bs = level.getBlockState(cursor);
        if (bs.isAir()) return Blocks.DIRT.defaultBlockState();
        return bs;
    }

    /** Returns {@code [minX, minZ, maxX, maxZ]} for the rotated footprint. */
    private static int[] footprintAabb(PlacedBuilding b) {
        Footprint fp = b.footprint();
        boolean swap = b.rotation() == Rotation.CLOCKWISE_90
                || b.rotation() == Rotation.COUNTERCLOCKWISE_90;
        int rotW = swap ? fp.length() : fp.width();
        int rotL = swap ? fp.width() : fp.length();
        int halfW = rotW / 2;
        int halfL = rotL / 2;
        BlockPos c = b.centre();
        return new int[]{c.getX() - halfW, c.getZ() - halfL,
                c.getX() + halfW, c.getZ() + halfL};
    }
}
