// src/main/java/tterrag1112/life_in_the_village/Village/Planning/Terrain/Steps/FoundationStep.java
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
 * Fills the volume directly beneath each building's pad with a solid
 * foundation block down to natural ground.
 *
 * <h3>Why this exists</h3>
 * When a building's pad is higher than the natural terrain below it
 * — the downhill side of a sloped site — the space between the pad
 * and the ground is air. Left alone the building would appear to
 * float. This step fills that gap with the biome's stone block,
 * producing a visible foundation that reads as intentional.
 *
 * <h3>Material</h3>
 * Uses {@code VillageBiomeStyle.stone} (cobblestone, sandstone, stone
 * bricks, etc.) — the same block the biome uses for its primary
 * street surface. Feels consistent with the rest of the village.
 */
public class FoundationStep implements TerrainStep {

    /** Max foundation depth — beyond this the building is just floating. */
    private static final int MAX_FOUNDATION_DEPTH = 16;

    @Override
    public String name() { return "foundations"; }

    @Override
    public void execute(ServerLevel level, VillageLayout layout,
                        TerrainProfile profile, VillageBiomeStyle style) {
        int foundationBlocks = 0;

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

            BlockState foundationBlock = style.stone.defaultBlockState();

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int naturalY = level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    if (naturalY >= padY) continue; // no gap

                    int gap = padY - naturalY;
                    int depth = Math.min(gap, MAX_FOUNDATION_DEPTH);

                    for (int y = padY - 1; y >= padY - depth; y--) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState existing = level.getBlockState(pos);
                        if (existing.isAir() || existing.liquid()) {
                            level.setBlock(pos, foundationBlock, 18);
                            foundationBlocks++;
                        }
                    }
                }
            }
        }

        System.out.println("FoundationStep: placed "
                + foundationBlocks + " foundation blocks");
    }
}