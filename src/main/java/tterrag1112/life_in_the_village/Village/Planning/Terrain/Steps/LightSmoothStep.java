// src/main/java/tterrag1112/life_in_the_village/Village/Planning/Terrain/Steps/LightSmoothStep.java
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
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Planning.VillageLayout;

/**
 * Mild cardinal-neighbour height averaging used only by the FLAT
 * strategy. One pass, maximum ±1 block change, only on natural terrain.
 *
 * <h3>Why so gentle</h3>
 * The FLAT strategy runs on terrain the site selector has already
 * judged buildable. Any lingering unevenness is minor. Aggressive
 * smoothing here would wipe out the subtle character that makes
 * plains villages look natural.
 */
public class LightSmoothStep implements TerrainStep {

    private static final int MAX_STEP = 1;

    @Override
    public String name() { return "light_smooth"; }

    @Override
    public void execute(ServerLevel level, VillageLayout layout,
                        TerrainProfile profile, VillageBiomeStyle style) {
        BlockPos centre = layout.getCenter();

        // Radius derived from village extent, like ClearTreesStep
        int maxDistSq = 0;
        for (LayoutSlot slot : layout.buildings()) {
            int dx = slot.getPos().getX() - centre.getX();
            int dz = slot.getPos().getZ() - centre.getZ();
            maxDistSq = Math.max(maxDistSq, dx * dx + dz * dz);
        }
        int radius = Math.max(32, (int) Math.sqrt(maxDistSq) + 8);

        int size = radius * 2 + 1;
        int[] heights = new int[size * size];

        // Sample
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int idx = (dx + radius) * size + (dz + radius);
                heights[idx] = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        centre.getX() + dx, centre.getZ() + dz);
            }
        }

        // Apply
        int rSq = radius * radius;
        int adjustments = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > rSq) continue;

                int cx = centre.getX() + dx;
                int cz = centre.getZ() + dz;
                int idx = (dx + radius) * size + (dz + radius);
                int currentY = heights[idx];

                int sum = currentY, count = 1;
                for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                    int ndx = dx + d[0];
                    int ndz = dz + d[1];
                    if (Math.abs(ndx) > radius || Math.abs(ndz) > radius) continue;
                    int ni = (ndx + radius) * size + (ndz + radius);
                    sum += heights[ni];
                    count++;
                }
                int avg = sum / count;
                int targetY = Math.max(currentY - MAX_STEP,
                        Math.min(currentY + MAX_STEP, avg));
                if (targetY == currentY) continue;

                adjustColumn(level, cx, cz, currentY, targetY);
                adjustments++;
            }
        }
        System.out.println("LightSmoothStep: adjusted "
                + adjustments + " columns");
    }

    private static void adjustColumn(ServerLevel level, int x, int z,
                                     int currentY, int targetY) {
        if (targetY > currentY) {
            BlockPos pos = new BlockPos(x, currentY + 1, z);
            BlockState s = level.getBlockState(pos);
            if (s.isAir() || s.is(BlockTags.REPLACEABLE)) {
                level.setBlock(pos, Blocks.GRASS_BLOCK.defaultBlockState(), 18);
            }
        } else {
            BlockPos pos = new BlockPos(x, currentY, z);
            BlockState s = level.getBlockState(pos);
            if (isNaturalTerrain(s)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
                BlockPos newTop = new BlockPos(x, targetY, z);
                BlockState top = level.getBlockState(newTop);
                if (top.is(Blocks.DIRT)) {
                    level.setBlock(newTop,
                            Blocks.GRASS_BLOCK.defaultBlockState(), 18);
                }
            }
        }
    }

    private static boolean isNaturalTerrain(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK)
                || s.is(Blocks.DIRT)
                || s.is(Blocks.COARSE_DIRT)
                || s.is(Blocks.PODZOL)
                || s.is(Blocks.SAND)
                || s.is(BlockTags.REPLACEABLE);
    }
}