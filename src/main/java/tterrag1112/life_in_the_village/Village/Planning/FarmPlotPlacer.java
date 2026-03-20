// src/main/java/tterrag1112/life_in_the_village/Village/Planning/FarmPlotPlacer.java
package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

/**
 * Places farm plots at positions provided by {@link VillageLayout} and
 * registers each one as a {@link FarmPlot} in {@link VillageSavedData}.
 *
 * <h3>New plot design</h3>
 * Each plot is a rectangular bed with:
 * <ul>
 *   <li>Farmland rows separated by a single water channel every 8 columns.</li>
 *   <li>Water sources are placed at the surface level, surrounded by
 *       farmland on all sides so they hydrate correctly and do NOT flow.</li>
 *   <li>A cobblestone border around the entire plot prevents water from
 *       flowing outward.</li>
 *   <li>A composter at one corner as a visual anchor.</li>
 * </ul>
 */
public class FarmPlotPlacer {

    private static final int PLOT_HALF_W = 9;   // half-width  (X)
    private static final int PLOT_HALF_L = 7;   // half-length (Z)

    private static final BlockState[] CROPS = {
            Blocks.WHEAT.defaultBlockState(),
            Blocks.CARROTS.defaultBlockState(),
            Blocks.POTATOES.defaultBlockState(),
            Blocks.BEETROOTS.defaultBlockState(),
    };

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /**
     * Places all farm plots defined in the layout and registers them with
     * the village saved data, assigning each to the nearest FARMHOUSE.
     */
    public static void placeAll(ServerLevel level,
                                VillageLayout layout,
                                Village village,
                                VillageSavedData data,
                                Random rng) {
        List<LayoutSlot> plots = layout.farmPlots();
        if (plots.isEmpty()) return;

        // Collect farmhouses for assignment
        List<Building> farmhouses = village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() == BuildingType.FARMHOUSE)
                .collect(java.util.stream.Collectors.toList());

        System.out.println("FarmPlotPlacer: placing " + plots.size()
                + " farm plot(s) for " + village.getName());

        for (int i = 0; i < plots.size(); i++) {
            LayoutSlot slot = plots.get(i);
            BlockPos centre = slot.getPos();

            // Find the median Y across the plot footprint for levelling
            int targetY = medianGroundY(level, centre,
                    PLOT_HALF_W, PLOT_HALF_L);
            BlockPos flatCentre = new BlockPos(
                    centre.getX(), targetY, centre.getZ());

            // Level the pad (fills holes, carves bumps)
            levelPlotPad(level, flatCentre, PLOT_HALF_W, PLOT_HALF_L, targetY);

            // Place the plot blocks
            BlockState crop = CROPS[rng.nextInt(CROPS.length)];
            placePlot(level, flatCentre, targetY, crop);

            // Register as FarmPlot in saved data
            String plotName = village.getName() + "_farm_" + (i + 1);
            FarmPlot farmPlot = new FarmPlot(
                    UUID.randomUUID(),
                    plotName,
                    flatCentre,
                    Math.max(PLOT_HALF_W, PLOT_HALF_L),
                    FarmPlot.CropType.MIXED);

            // Assign to nearest farmhouse
            if (!farmhouses.isEmpty()) {
                Building nearest = farmhouses.stream()
                        .min(Comparator.comparingDouble(f ->
                                f.getShape().getOrigin().distSqr(flatCentre)))
                        .orElse(farmhouses.get(0));
                farmPlot.setFarmhouseId(nearest.getId());
            }

            data.addFarmPlot(farmPlot);
            System.out.println("FarmPlotPlacer: registered plot '"
                    + plotName + "' at " + flatCentre
                    + (farmPlot.isAssigned() ? " → assigned" : " → unassigned"));
        }
    }

    // -------------------------------------------------------------------------
    // Levelling
    // -------------------------------------------------------------------------

    private static void levelPlotPad(ServerLevel level, BlockPos centre,
                                     int halfW, int halfL, int targetY) {
        for (int dx = -(halfW + 1); dx <= halfW + 1; dx++) {
            for (int dz = -(halfL + 1); dz <= halfL + 1; dz++) {
                int x = centre.getX() + dx;
                int z = centre.getZ() + dz;
                int surfY = level.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);

                if (surfY < targetY) {
                    // Fill up
                    for (int y = surfY; y < targetY; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (level.getBlockState(pos).isAir()) {
                            level.setBlock(pos,
                                    Blocks.DIRT.defaultBlockState(), 3);
                        }
                    }
                } else if (surfY > targetY) {
                    // Carve down
                    for (int y = surfY - 1; y >= targetY; y--) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState s = level.getBlockState(pos);
                        if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT)
                                || s.is(Blocks.COARSE_DIRT)
                                || s.is(net.minecraft.tags.BlockTags.REPLACEABLE)
                                || s.isAir()) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                        } else break;
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Plot placement
    // -------------------------------------------------------------------------

    private static void placePlot(ServerLevel level, BlockPos centre,
                                  int targetY, BlockState crop) {
        // ── Step 1: cobblestone border ────────────────────────────────────────
        for (int dx = -(PLOT_HALF_W + 1); dx <= PLOT_HALF_W + 1; dx++) {
            for (int dz = -(PLOT_HALF_L + 1); dz <= PLOT_HALF_L + 1; dz++) {
                boolean onBorder = Math.abs(dx) == PLOT_HALF_W + 1
                        || Math.abs(dz) == PLOT_HALF_L + 1;
                if (!onBorder) continue;
                BlockPos borderPos = new BlockPos(
                        centre.getX() + dx, targetY, centre.getZ() + dz);
                level.setBlock(borderPos,
                        Blocks.COBBLESTONE.defaultBlockState(), 3);
                // Clear vegetation on top of border
                clearAbove(level, borderPos.above());
            }
        }

        // ── Step 2: interior — farmland + water channels ──────────────────────
        for (int dx = -PLOT_HALF_W; dx <= PLOT_HALF_W; dx++) {
            for (int dz = -PLOT_HALF_L; dz <= PLOT_HALF_L; dz++) {
                BlockPos pos = new BlockPos(
                        centre.getX() + dx, targetY, centre.getZ() + dz);
                clearAbove(level, pos.above());

                // Water channel every 8 columns (relative to plot centre)
                boolean isWaterCol = (dx % 8 == 0);

                if (isWaterCol) {
                    // Dig the water channel one block below farmland level
                    // so it is surrounded on all sides by solid blocks.
                    // This keeps it from flowing.
                    BlockPos waterPos = pos.below();
                    level.setBlock(waterPos,
                            Blocks.WATER.defaultBlockState(), 3);
                    // Cap with farmland at the surface level (pos)
                    // so water is buried and farmland sits on top
                    level.setBlock(pos,
                            Blocks.FARMLAND.defaultBlockState(), 3);
                } else {
                    level.setBlock(pos,
                            Blocks.FARMLAND.defaultBlockState(), 3);
                    // Plant crop in the air block above farmland
                    BlockPos cropPos = pos.above();
                    if (level.getBlockState(cropPos).isAir()) {
                        level.setBlock(cropPos, crop, 3);
                    }
                }
            }
        }

        // ── Step 3: ensure buried water is hydrated ───────────────────────────
        // Minecraft hydrates farmland within 4 blocks of water horizontally.
        // The buried water channels do this — but we need to tick the farmland.
        // We do a simple pass to ensure any farmland above a water channel
        // gets properly hydrated state.
        for (int dx = -PLOT_HALF_W; dx <= PLOT_HALF_W; dx++) {
            if (dx % 8 != 0) continue;
            for (int dz = -PLOT_HALF_L; dz <= PLOT_HALF_L; dz++) {
                BlockPos farmlandPos = new BlockPos(
                        centre.getX() + dx, targetY, centre.getZ() + dz);
                // Re-place hydrated farmland explicitly
                level.setBlock(farmlandPos,
                        Blocks.FARMLAND.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.FarmBlock.MOISTURE,
                                        7), 3);
            }
        }

        // ── Step 4: composter corner ──────────────────────────────────────────
        BlockPos composterPos = new BlockPos(
                centre.getX() + PLOT_HALF_W,
                targetY,
                centre.getZ() - PLOT_HALF_L);
        level.setBlock(composterPos,
                Blocks.COMPOSTER.defaultBlockState(), 3);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void clearAbove(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.is(net.minecraft.tags.BlockTags.REPLACEABLE)
                || s.is(net.minecraft.tags.BlockTags.LEAVES)
                || s.is(net.minecraft.tags.BlockTags.LOGS)
                || s.is(net.minecraft.tags.BlockTags.FLOWERS)
                || s.is(net.minecraft.tags.BlockTags.SMALL_FLOWERS)
                || !s.isAir() && !s.isSolidRender()) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static int medianGroundY(ServerLevel level, BlockPos centre,
                                     int halfW, int halfL) {
        List<Integer> ys = new ArrayList<>();
        for (int dx = -halfW; dx <= halfW; dx += 2) {
            for (int dz = -halfL; dz <= halfL; dz += 2) {
                ys.add(level.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                        centre.getX() + dx, centre.getZ() + dz));
            }
        }
        Collections.sort(ys);
        return ys.get(ys.size() / 2);
    }
}