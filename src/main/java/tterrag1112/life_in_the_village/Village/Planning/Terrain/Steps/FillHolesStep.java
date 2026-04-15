// src/main/java/tterrag1112/life_in_the_village/Village/Planning/Terrain/Steps/FillHolesStep.java
package tterrag1112.life_in_the_village.Village.Planning.Terrain.Steps;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Planning.LayoutSlot;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainStep;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Planning.VillageLayout;

/**
 * Fills small gaps (1-3 blocks) directly beneath the solid surface
 * within building footprints. Prevents buildings from generating
 * over hidden caves or drops.
 *
 * <h3>Scope</h3>
 * Unlike the previous site-preparer version, this step only touches
 * terrain under building footprints plus a small margin. The open
 * ground between buildings is left alone.
 */
public class FillHolesStep implements TerrainStep {

    private static final int MARGIN = 4;

    @Override
    public String name() { return "fill_holes"; }

    @Override
    public void execute(ServerLevel level, VillageLayout layout,
                        TerrainProfile profile, VillageBiomeStyle style) {
        int filled = 0;
        for (LayoutSlot slot : layout.buildings()) {
            int w = slot.getFootprintWidth();
            int l = slot.getFootprintLength();
            if (w == 0 || l == 0) continue;

            int minX = slot.getPos().getX() - w / 2 - MARGIN;
            int maxX = slot.getPos().getX() + w / 2 + MARGIN;
            int minZ = slot.getPos().getZ() - l / 2 - MARGIN;
            int maxZ = slot.getPos().getZ() + l / 2 + MARGIN;

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int surfY = level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    for (int depth = 1; depth <= 3; depth++) {
                        BlockPos below = new BlockPos(x, surfY - depth, z);
                        BlockState s = level.getBlockState(below);
                        if (s.isAir()) {
                            level.setBlock(below,
                                    Blocks.DIRT.defaultBlockState(), 18);
                            filled++;
                        } else {
                            break;
                        }
                    }
                }
            }
        }
        System.out.println("FillHolesStep: filled " + filled
                + " sub-surface holes under buildings");
    }
}