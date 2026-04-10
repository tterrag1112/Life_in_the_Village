// src/main/java/tterrag1112/life_in_the_village/Village/Planning/Terrain/Steps/LevelBuildingPadsStep.java
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

/**
 * Levels the pad under each building to the slot's pre-computed
 * pad Y. Reads {@link LayoutSlot#getPadY()} — which is set by
 * {@code VillagePlanner.resolveAndPlace} during planning — and
 * carves or fills to match.
 *
 * <h3>Why pad Y is pre-computed</h3>
 * The planner needs to know each building's final Y during placement
 * so it can cluster nearby buildings at similar elevations. If pad Y
 * were computed here, the planner would see pre-prep terrain and
 * cluster against stale values. Instead, the planner computes pad Y
 * from the raw terrain and we honour that value here.
 *
 * <h3>Modes</h3>
 * <ul>
 *   <li>{@code withRetainingPrep=false} — pad extends slightly beyond
 *       the footprint and ramps back to natural terrain. Used by FLAT
 *       strategy where no retaining walls will be placed.</li>
 *   <li>{@code withRetainingPrep=true} — pad is cut exactly to the
 *       footprint. The subsequent {@link RetainingWallStep} places
 *       walls along the cut edges. Used by slope-aware strategies.</li>
 * </ul>
 */
public class LevelBuildingPadsStep implements TerrainStep {

    private static final int RAMP_MARGIN = 3;

    private final boolean withRetainingPrep;

    public LevelBuildingPadsStep(boolean withRetainingPrep) {
        this.withRetainingPrep = withRetainingPrep;
    }

    @Override
    public String name() {
        return withRetainingPrep ? "level_pads_retaining" : "level_pads_ramped";
    }

    @Override
    public void execute(ServerLevel level, VillageLayout layout,
                        TerrainProfile profile, VillageBiomeStyle style) {
        int levelledPads = 0;

        for (LayoutSlot slot : layout.buildings()) {
            int w = slot.getFootprintWidth();
            int l = slot.getFootprintLength();
            if (w == 0 || l == 0) continue;

            int padY = slot.getPadY();
            if (padY == 0) {
                // Planner didn't set it — fall back to slot's current Y
                padY = slot.getPos().getY();
                slot.setPadY(padY);
            }

            int minX = slot.getPos().getX() - w / 2;
            int maxX = slot.getPos().getX() + w / 2;
            int minZ = slot.getPos().getZ() - l / 2;
            int maxZ = slot.getPos().getZ() + l / 2;

            // Level the footprint itself
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int currentY = level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    adjustColumn(level, x, z, currentY, padY, style, true);
                }
            }

            // Ramp margin if we're not placing retaining walls
            if (!withRetainingPrep) {
                for (int x = minX - RAMP_MARGIN; x <= maxX + RAMP_MARGIN; x++) {
                    for (int z = minZ - RAMP_MARGIN; z <= maxZ + RAMP_MARGIN; z++) {
                        boolean insideFootprint = x >= minX && x <= maxX
                                && z >= minZ && z <= maxZ;
                        if (insideFootprint) continue;

                        int currentY = level.getHeight(
                                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

                        int distFromEdge = Math.max(0,
                                Math.max(Math.max(minX - x, x - maxX),
                                        Math.max(minZ - z, z - maxZ)));
                        float t = Math.min(1f,
                                (float) distFromEdge / RAMP_MARGIN);
                        int desiredY = (int)(padY + t * (currentY - padY));
                        adjustColumn(level, x, z, currentY, desiredY, style, false);
                    }
                }
            }

            // Commit the slot's final Y to the pad
            slot.snapY(padY);
            levelledPads++;
        }

        System.out.println("LevelBuildingPadsStep: levelled "
                + levelledPads + " building pads (retaining="
                + withRetainingPrep + ")");
    }

    /**
     * Carves or fills a single column to reach {@code targetY}, capping
     * with the biome-appropriate surface block.
     */
    private static void adjustColumn(ServerLevel level, int x, int z,
                                     int currentY, int targetY,
                                     VillageBiomeStyle style, boolean isPad) {
        if (currentY == targetY) return;

        if (targetY > currentY) {
            // Fill up
            BlockState fillBlock = Blocks.DIRT.defaultBlockState();
            BlockState capBlock = isPad
                    ? Blocks.DIRT.defaultBlockState() // pad caps get overwritten by building
                    : detectBiomeCap(level, x, z, currentY);

            for (int y = currentY + 1; y <= targetY; y++) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState existing = level.getBlockState(pos);
                if (existing.isAir() || existing.liquid()
                        || existing.is(BlockTags.REPLACEABLE)) {
                    boolean isTop = (y == targetY);
                    level.setBlock(pos,
                            isTop ? capBlock : fillBlock, 18);
                }
            }
        } else {
            // Carve down
            for (int y = currentY; y > targetY; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState s = level.getBlockState(pos);
                if (isNaturalTerrain(s)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
                } else {
                    break;
                }
            }
            // Re-cap at targetY
            BlockPos newTop = new BlockPos(x, targetY, z);
            BlockState top = level.getBlockState(newTop);
            if (top.is(Blocks.DIRT) || top.is(Blocks.COARSE_DIRT)) {
                BlockState cap = isPad
                        ? Blocks.DIRT.defaultBlockState()
                        : detectBiomeCap(level, x, z, targetY);
                level.setBlock(newTop, cap, 18);
            }
        }
    }

    private static BlockState detectBiomeCap(ServerLevel level, int x, int z, int nearY) {
        // Sample nearby natural surface to preserve biome type
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int sy = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz) - 1;
                BlockState s = level.getBlockState(new BlockPos(x + dx, sy, z + dz));
                if (s.is(Blocks.GRASS_BLOCK)) return s;
                if (s.is(Blocks.SAND)) return s;
                if (s.is(Blocks.RED_SAND)) return s;
                if (s.is(Blocks.PODZOL)) return s;
                if (s.is(Blocks.MYCELIUM)) return s;
            }
        }
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    private static boolean isNaturalTerrain(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK)
                || s.is(Blocks.DIRT)
                || s.is(Blocks.COARSE_DIRT)
                || s.is(Blocks.PODZOL)
                || s.is(Blocks.ROOTED_DIRT)
                || s.is(Blocks.SAND)
                || s.is(Blocks.RED_SAND)
                || s.is(Blocks.GRAVEL)
                || s.is(Blocks.SNOW_BLOCK)
                || s.is(BlockTags.REPLACEABLE)
                || s.is(BlockTags.FLOWERS);
    }
}