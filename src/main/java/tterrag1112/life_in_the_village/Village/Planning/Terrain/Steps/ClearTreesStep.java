// src/main/java/tterrag1112/life_in_the_village/Village/Planning/Terrain/Steps/ClearTreesStep.java
package tterrag1112.life_in_the_village.Village.Planning.Terrain.Steps;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Planning.LayoutSlot;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainStep;
import tterrag1112.life_in_the_village.Village.Planning.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Planning.VillageLayout;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Clears trees and surface plants in a radius around the village
 * centre. Uses flood fill from every log/leaf block rather than a
 * blanket radius clear.
 *
 * <h3>Scope</h3>
 * This step uses the village's furthest building as the reference
 * for radius, so small villages don't clear enormous forests and
 * capitals get a wide enough area. Within that radius, flood-fill
 * removes connected tree structures without touching terrain.
 */
public class ClearTreesStep implements TerrainStep {

    @Override
    public String name() { return "clear_trees"; }

    @Override
    public void execute(ServerLevel level, VillageLayout layout,
                        TerrainProfile profile, VillageBiomeStyle style) {
        BlockPos centre = layout.getCenter();

        // Compute radius from the furthest building + margin
        int maxDistSq = 0;
        for (LayoutSlot slot : layout.buildings()) {
            int dx = slot.getPos().getX() - centre.getX();
            int dz = slot.getPos().getZ() - centre.getZ();
            int distSq = dx * dx + dz * dz;
            if (distSq > maxDistSq) maxDistSq = distSq;
        }
        int radius = Math.max(48, (int) Math.sqrt(maxDistSq) + 16);

        Set<BlockPos> toRemove = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        int rSq = radius * radius;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > rSq) continue;
                int x = centre.getX() + dx;
                int z = centre.getZ() + dz;
                int surfY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

                // Scan above surface for trees
                for (int y = surfY + 24; y >= surfY - 4; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(pos);
                    if (isTreeBlock(s) && !visited.contains(pos)) {
                        floodFill(level, pos, toRemove, visited);
                    }
                }

                // Surface plants
                BlockPos surfPos = new BlockPos(x, surfY, z);
                if (isSurfacePlant(level.getBlockState(surfPos))) {
                    toRemove.add(surfPos);
                }
                BlockPos above = new BlockPos(x, surfY + 1, z);
                if (isSurfacePlant(level.getBlockState(above))) {
                    toRemove.add(above);
                }
            }
        }

        for (BlockPos pos : toRemove) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
        }

        System.out.println("ClearTreesStep: removed "
                + toRemove.size() + " tree/plant blocks in radius " + radius);
    }

    private static void floodFill(ServerLevel level, BlockPos seed,
                                  Set<BlockPos> toRemove, Set<BlockPos> visited) {
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);
        visited.add(seed);
        int limit = 2048;
        int count = 0;

        while (!queue.isEmpty() && count < limit) {
            BlockPos cur = queue.poll();
            toRemove.add(cur);
            count++;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos nb = cur.offset(dx, dy, dz);
                        if (visited.contains(nb)) continue;
                        if (isTreeBlock(level.getBlockState(nb))) {
                            visited.add(nb);
                            queue.add(nb);
                        }
                    }
                }
            }
        }
    }

    private static boolean isTreeBlock(BlockState s) {
        return s.is(BlockTags.LOGS)
                || s.is(BlockTags.LEAVES)
                || s.is(BlockTags.WART_BLOCKS)
                || s.is(Blocks.SHROOMLIGHT)
                || s.is(Blocks.BEE_NEST)
                || s.is(Blocks.VINE)
                || s.is(Blocks.COCOA)
                || s.is(Blocks.BAMBOO)
                || s.is(Blocks.BAMBOO_SAPLING);
    }

    private static boolean isSurfacePlant(BlockState s) {
        return s.is(BlockTags.FLOWERS)
                || s.is(BlockTags.SMALL_FLOWERS)
                || s.is(BlockTags.REPLACEABLE)
                || s.is(Blocks.SUGAR_CANE)
                || s.is(Blocks.TALL_GRASS)
                || s.is(Blocks.SHORT_GRASS)
                || s.is(Blocks.DEAD_BUSH)
                || s.is(Blocks.FERN)
                || s.is(Blocks.LARGE_FERN);
    }
}