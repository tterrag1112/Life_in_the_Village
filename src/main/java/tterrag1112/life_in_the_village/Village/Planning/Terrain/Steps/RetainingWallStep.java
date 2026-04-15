// src/main/java/tterrag1112/life_in_the_village/Village/Planning/Terrain/Steps/RetainingWallStep.java
package tterrag1112.life_in_the_village.Village.Planning.Terrain.Steps;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Planning.LayoutSlot;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainStep;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Planning.VillageLayout;

/**
 * Places retaining walls on building edges where the surrounding
 * terrain is higher than the building's pad Y.
 *
 * <h3>Variable height</h3>
 * Wall height is computed per column by measuring the natural terrain
 * Y at that edge position and taking the delta from pad Y. A cliff-
 * edge building on a steep slope gets a tall wall; a mildly-elevated
 * neighbour gets a short curb. Same code, different visible outcome.
 *
 * <h3>Material</h3>
 * Uses {@code VillageBiomeStyle.stoneWall} — the existing stone wall
 * block for the biome. A future enhancement could use stone bricks
 * for tall walls and cobblestone walls for short ones.
 */
public class RetainingWallStep implements TerrainStep {

    /**
     * Walls are only placed where the natural terrain is at least this
     * many blocks above pad Y. Smaller differences are ignored because
     * they don't read as walls — they look like curbs or random blocks.
     */
    private static final int MIN_WALL_HEIGHT = 1;
    private static final int MAX_WALL_HEIGHT = 8;

    @Override
    public String name() { return "retaining_walls"; }

    @Override
    public void execute(ServerLevel level, VillageLayout layout,
                        TerrainProfile profile, VillageBiomeStyle style) {
        int wallsPlaced = 0;

        for (LayoutSlot slot : layout.buildings()) {
            int w = slot.getFootprintWidth();
            int l = slot.getFootprintLength();
            if (w == 0 || l == 0) continue;

            int padY = slot.getPadY();
            if (padY == 0) continue;

            int minX = slot.getPos().getX() - w / 2;
            int maxX = slot.getPos().getX() + w / 2;
            int minZ = slot.getPos().getZ() - l / 2;
            int maxZ = slot.getPos().getZ() + l / 2;

            BlockState wallBlock = style.stoneWallState();

            // Walk the four edges of the footprint
            // North edge (z = minZ - 1)
            for (int x = minX - 1; x <= maxX + 1; x++) {
                int edgeY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, minZ - 1);
                int height = edgeY - padY;
                if (height < MIN_WALL_HEIGHT) continue;
                height = Math.min(height, MAX_WALL_HEIGHT);
                for (int dy = 0; dy < height; dy++) {
                    BlockPos pos = new BlockPos(x, padY + dy, minZ - 1);
                    level.setBlock(pos, wallBlock, 18);
                    wallsPlaced++;
                }
            }
            // South edge (z = maxZ + 1)
            for (int x = minX - 1; x <= maxX + 1; x++) {
                int edgeY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, maxZ + 1);
                int height = edgeY - padY;
                if (height < MIN_WALL_HEIGHT) continue;
                height = Math.min(height, MAX_WALL_HEIGHT);
                for (int dy = 0; dy < height; dy++) {
                    BlockPos pos = new BlockPos(x, padY + dy, maxZ + 1);
                    level.setBlock(pos, wallBlock, 18);
                    wallsPlaced++;
                }
            }
            // West edge (x = minX - 1)
            for (int z = minZ; z <= maxZ; z++) {
                int edgeY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, minX - 1, z);
                int height = edgeY - padY;
                if (height < MIN_WALL_HEIGHT) continue;
                height = Math.min(height, MAX_WALL_HEIGHT);
                for (int dy = 0; dy < height; dy++) {
                    BlockPos pos = new BlockPos(minX - 1, padY + dy, z);
                    level.setBlock(pos, wallBlock, 18);
                    wallsPlaced++;
                }
            }
            // East edge (x = maxX + 1)
            for (int z = minZ; z <= maxZ; z++) {
                int edgeY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, maxX + 1, z);
                int height = edgeY - padY;
                if (height < MIN_WALL_HEIGHT) continue;
                height = Math.min(height, MAX_WALL_HEIGHT);
                for (int dy = 0; dy < height; dy++) {
                    BlockPos pos = new BlockPos(maxX + 1, padY + dy, z);
                    level.setBlock(pos, wallBlock, 18);
                    wallsPlaced++;
                }
            }
        }

        System.out.println("RetainingWallStep: placed "
                + wallsPlaced + " retaining wall blocks");
    }
}