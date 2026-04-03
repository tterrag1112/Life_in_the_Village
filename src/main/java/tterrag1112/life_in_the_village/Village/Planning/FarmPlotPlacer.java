// src/main/java/tterrag1112/life_in_the_village/Village/Planning/FarmPlotPlacer.java
package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.PathRouter;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.VillagePath;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.VillageTypeData;
import tterrag1112.life_in_the_village.Village.VillageTypeRegistry;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Places farm plots with organic, naturally-shaped boundaries.
 *
 * <h3>Design goals</h3>
 * <ul>
 *   <li>Plots look like they were cleared from existing land, not stamped
 *       from a template. Shape variation comes from per-column random
 *       edge offsets and gentle terrain following.</li>
 *   <li>Each plot is divided into 2–3 strip sections with different crops,
 *       separated by narrow earth dividers — matching historical open-field
 *       strip farming.</li>
 *   <li>A wooden fence with occasional gate posts surrounds each plot
 *       instead of a cobblestone border.</li>
 *   <li>A narrow dirt-path footpath runs from the plot entrance to the
 *       assigned farmhouse entrance, branching from the nearest village
 *       path if one exists nearby.</li>
 *   <li>A scarecrow (fence post + carved pumpkin) and composter are placed
 *       at natural positions within the plot.</li>
 * </ul>
 *
 * <h3>Y convention</h3>
 * Uses {@code MOTION_BLOCKING_NO_LEAVES} throughout, consistent with
 * {@code RoadRouter} and {@code VillagePlanner}.
 */
public class FarmPlotPlacer {

    // Base plot half-dimensions — actual plot edges vary per column
    private static final int BASE_HALF_W = 9;
    private static final int BASE_HALF_L = 7;
    // Maximum random edge variation (blocks)
    private static final int EDGE_JITTER = 3;
    // Number of crop strips per plot
    private static final int STRIP_COUNT = 3;

    private static final BlockState[] CROP_OPTIONS = {
            Blocks.WHEAT.defaultBlockState(),
            Blocks.CARROTS.defaultBlockState(),
            Blocks.POTATOES.defaultBlockState(),
            Blocks.BEETROOTS.defaultBlockState(),
    };

    // =========================================================================
    // Entry point
    // =========================================================================

    // Major updates to FarmPlotPlacer.java

    public static void placeAll(ServerLevel level,
                                VillageLayout layout,
                                Village village,
                                VillageSavedData data,
                                Random rng) {
        VillageTypeData typeData = VillageTypeRegistry.INSTANCE.getType(village.getVillageType());
        VillageTypeData.FarmPlotConfig config = typeData.getFarmPlotConfig();

        // Check if farming is disabled for this village type
        if (config.placement() == VillageTypeData.FarmPlotPlacement.NONE) {
            System.out.println("FarmPlotPlacer: farming disabled for " + village.getVillageType());
            return;
        }

        // Count farmhouses
        List<Building> farmhouses = village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() == BuildingType.FARMHOUSE)
                .collect(Collectors.toList());

        if (farmhouses.isEmpty()) {
            System.out.println("FarmPlotPlacer: no farmhouses found");
            return;
        }

        int totalPlots = farmhouses.size() * config.plotsPerFarmhouse();
        int cropPlots = config.allowAnimalPens()
                ? (int)(totalPlots * 0.7)  // 70% crops, 30% animals
                : totalPlots;
        int animalPlots = totalPlots - cropPlots;

        System.out.println("FarmPlotPlacer: placing " + cropPlots + " crop plots and "
                + animalPlots + " animal pens for " + village.getName());

        VillageBiomeStyle style = VillageBiomeStyle.detect(level, layout.getCenter());

        // Place crop plots
        placeCropPlots(level, layout, village, data, farmhouses, cropPlots, config, style, rng);

        // Place animal pens (if allowed)
        if (config.allowAnimalPens()) {
            placeAnimalPens(level, layout, village, data, farmhouses, animalPlots, config, style, rng);
        }
    }

    private static void placeCropPlots(ServerLevel level, VillageLayout layout,
                                       Village village, VillageSavedData data,
                                       List<Building> farmhouses, int count,
                                       VillageTypeData.FarmPlotConfig config,
                                       VillageBiomeStyle style, Random rng) {
        BlockPos centre = layout.getCenter();
        Optional<AABB> villageBounds = village.getBounds(data);

        for (int i = 0; i < count; i++) {
            BlockPos plotPos = findPlotLocation(level, centre, villageBounds,
                    config, false, i, count, rng);

            if (plotPos == null) continue;

            // Generate plot shape
            PlotShape shape = generateShape(rng, BASE_HALF_W, BASE_HALF_L, EDGE_JITTER);
            int targetY = medianFootprintY(level, plotPos, shape);
            BlockPos flatCentre = new BlockPos(plotPos.getX(), targetY, plotPos.getZ());

            // Level and place
            levelPad(level, flatCentre, shape, targetY);
            BlockState[] crops = chooseCrops(rng, STRIP_COUNT);
            placeCropPlot(level, flatCentre, shape, targetY, crops, style, rng);

            // Register
            Building nearestFarmhouse = farmhouses.stream()
                    .min(Comparator.comparingDouble(f ->
                            f.getShape().getOrigin().distSqr(flatCentre)))
                    .orElse(null);

            String plotName = village.getName() + "_farm_" + (i + 1);
            FarmPlot.CropType cropType = chooseCropType(style, i, count, rng, village, data);
            FarmPlot farmPlot = new FarmPlot(
                    UUID.randomUUID(), plotName, flatCentre,
                    Math.max(BASE_HALF_W, BASE_HALF_L), cropType, FarmPlot.PlotSubtype.CROP_FIELD);

            if (nearestFarmhouse != null) {
                farmPlot.setFarmhouseId(nearestFarmhouse.getId());
                placeFootpath(level, flatCentre, shape, nearestFarmhouse, data, village, style);
            }

            data.addFarmPlot(farmPlot);
            System.out.println("FarmPlotPlacer: placed crop field '" + plotName + "' at " + flatCentre);
        }
    }

    private static void placeAnimalPens(ServerLevel level, VillageLayout layout,
                                        Village village, VillageSavedData data,
                                        List<Building> farmhouses, int count,
                                        VillageTypeData.FarmPlotConfig config,
                                        VillageBiomeStyle style, Random rng) {
        BlockPos centre = layout.getCenter();
        Optional<AABB> villageBounds = village.getBounds(data);

        for (int i = 0; i < count; i++) {
            // Animal pens ALWAYS go outside walls
            BlockPos plotPos = findPlotLocation(level, centre, villageBounds,
                    config, true, i, count, rng);

            if (plotPos == null) continue;

            // Larger pens for animals
            int penHalfW = BASE_HALF_W + 4;
            int penHalfL = BASE_HALF_L + 3;
            PlotShape shape = generateShape(rng, penHalfW, penHalfL, EDGE_JITTER);
            int targetY = medianFootprintY(level, plotPos, shape);
            BlockPos flatCentre = new BlockPos(plotPos.getX(), targetY, plotPos.getZ());

            // Level and place
            levelPad(level, flatCentre, shape, targetY);
            placeAnimalPen(level, flatCentre, shape, targetY, style, rng);

            // Register
            Building nearestFarmhouse = farmhouses.stream()
                    .min(Comparator.comparingDouble(f ->
                            f.getShape().getOrigin().distSqr(flatCentre)))
                    .orElse(null);

            String plotName = village.getName() + "_pen_" + (i + 1);
            FarmPlot farmPlot = new FarmPlot(
                    UUID.randomUUID(), plotName, flatCentre,
                    Math.max(penHalfW, penHalfL), FarmPlot.CropType.PASTURE, FarmPlot.PlotSubtype.ANIMAL_PEN);

            if (nearestFarmhouse != null) {
                farmPlot.setFarmhouseId(nearestFarmhouse.getId());
                placeFootpath(level, flatCentre, shape, nearestFarmhouse, data, village, style);
            }

            data.addFarmPlot(farmPlot);
            System.out.println("FarmPlotPlacer: placed animal pen '" + plotName + "' at " + flatCentre);
        }
    }

    private static BlockPos findPlotLocation(ServerLevel level, BlockPos centre,
                                             Optional<AABB> villageBounds, // FIX: Use AABB
                                             VillageTypeData.FarmPlotConfig config, boolean forceOutside,
                                             int index, int total, Random rng) {
        int attempts = 0;
        int maxAttempts = 20;

        while (attempts++ < maxAttempts) {
            // Calculate distance from center based on placement type
            int distance;
            if (forceOutside || config.placement() == VillageTypeData.FarmPlotPlacement.PERIMETER_OUTSIDE) {
                // Outside walls: use max distance from bounds edge
                int edgeDistance = villageBounds.map(b ->
                        (int)Math.max(b.getXsize(), b.getZsize()) / 2 + config.minDistance()
                ).orElse(config.minDistance());
                distance = edgeDistance + rng.nextInt(config.maxDistance() - config.minDistance() + 1);
            } else if (config.placement() == VillageTypeData.FarmPlotPlacement.DISTANT_FIELDS) {
                distance = config.minDistance() + rng.nextInt(config.maxDistance() - config.minDistance() + 1);
            } else {
                // INTEGRATED: within village bounds
                distance = rng.nextInt(config.maxDistance());
            }

            // Random angle
            double angle = 2 * Math.PI * (index + rng.nextDouble()) / total;
            int x = centre.getX() + (int)(Math.cos(angle) * distance);
            int z = centre.getZ() + (int)(Math.sin(angle) * distance);

            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);

            // Validate location
            if (isValidPlotLocation(level, candidate, villageBounds, config, forceOutside)) {
                return candidate;
            }
        }

        return null;
    }


    private static boolean isValidPlotLocation(ServerLevel level, BlockPos pos,
                                               Optional<AABB> villageBounds, // FIX: Use AABB
                                               VillageTypeData.FarmPlotConfig config, boolean forceOutside) {
        // Check if outside bounds when required
        if (forceOutside && villageBounds.isPresent()) {
            AABB bounds = villageBounds.get();
            // FIX: Use AABB.contains which takes a Vec3
            if (bounds.contains(pos.getCenter())) {
                return false;
            }
        }

        // Check terrain suitability (not too steep, not in water, not in structure)
        int slopeCheck = 0;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        pos.getX() + dx, pos.getZ() + dz);
                slopeCheck += Math.abs(y - pos.getY());
            }
        }

        return slopeCheck < 20; // Average slope less than ~0.4 blocks per unit
    }

    private static void placeCropPlot(ServerLevel level, BlockPos centre,
                                      PlotShape shape, int targetY,
                                      BlockState[] crops,
                                      VillageBiomeStyle style, Random rng) {
        // Place fence with gate
        placeFenceWithGate(level, centre, shape, targetY, style);

        // Place water source in center
        placeWaterSource(level, centre, targetY);

        // Place farmland and crops
        int scan = Math.max(shape.baseHalfW, shape.baseHalfL) + 2;
        for (int dx = -scan; dx <= scan; dx++) {
            for (int dz = -scan; dz <= scan; dz++) {
                if (!isInsidePlot(shape, dx, dz)) continue;
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) continue; // Skip water area

                BlockPos farmlandPos = new BlockPos(
                        centre.getX() + dx, targetY, centre.getZ() + dz);

                level.setBlock(farmlandPos, Blocks.FARMLAND.defaultBlockState()
                        .setValue(FarmBlock.MOISTURE, 7), 3);

                // Plant crop
                BlockState crop = crops[Math.abs((dx + dz)) % crops.length];
                level.setBlock(farmlandPos.above(), crop, 3);
            }
        }
    }

    private static void placeAnimalPen(ServerLevel level, BlockPos centre,
                                       PlotShape shape, int targetY,
                                       VillageBiomeStyle style, Random rng) {
        // Place fence with gate
        placeFenceWithGate(level, centre, shape, targetY, style);

        // Place water trough
        placeWaterTrough(level, centre, targetY);

        // Place grass
        int scan = Math.max(shape.baseHalfW, shape.baseHalfL) + 2;
        for (int dx = -scan; dx <= scan; dx++) {
            for (int dz = -scan; dz <= scan; dz++) {
                if (!isInsidePlot(shape, dx, dz)) continue;

                BlockPos grassPos = new BlockPos(
                        centre.getX() + dx, targetY, centre.getZ() + dz);
                level.setBlock(grassPos, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            }
        }

        // Spawn animals
        spawnPenAnimals(level, centre, shape, targetY, rng);
    }

    private static void placeFenceWithGate(ServerLevel level, BlockPos centre,
                                           PlotShape shape, int targetY,
                                           VillageBiomeStyle style) {
        Block fenceBlock = style.fence;
        int scan = Math.max(shape.baseHalfW, shape.baseHalfL) + 2;
        BlockPos gatePos = null;
        Direction gateDir = null;

        for (int dx = -scan; dx <= scan; dx++) {
            for (int dz = -scan; dz <= scan; dz++) {
                if (!isOnBoundary(shape, dx, dz)) continue;

                BlockPos fencePos = new BlockPos(
                        centre.getX() + dx, targetY + 1, centre.getZ() + dz);

                // Place gate on side closest to village center
                if (gatePos == null && Math.abs(dz) < 2 && dx < 0) {
                    gatePos = fencePos;
                    gateDir = Direction.SOUTH; // Adjust based on actual orientation
                    level.setBlock(fencePos, Blocks.OAK_FENCE_GATE.defaultBlockState()
                            .setValue(FenceGateBlock.FACING, gateDir), 3);
                } else {
                    level.setBlock(fencePos, fenceBlock.defaultBlockState(), 3);
                }
            }
        }
    }

    private static void placeWaterSource(ServerLevel level, BlockPos centre, int targetY) {
        // 2x2 water pool in center at ground level (not floating)
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                BlockPos waterPos = new BlockPos(centre.getX() + dx, targetY, centre.getZ() + dz);
                level.setBlock(waterPos, Blocks.WATER.defaultBlockState(), 3);
            }
        }
    }

    private static void placeWaterTrough(ServerLevel level, BlockPos centre, int targetY) {
        // Simple cauldron or water-filled basin
        BlockPos troughPos = centre.offset(2, 0, 2);
        level.setBlock(troughPos.below(), Blocks.STONE_BRICKS.defaultBlockState(), 3);
        level.setBlock(troughPos, Blocks.WATER_CAULDRON.defaultBlockState(), 3);
    }

    private static void spawnPenAnimals(ServerLevel level, BlockPos centre,
                                        PlotShape shape, int targetY, Random rng) {
        // Spawn 3-6 animals based on pen type
        int animalCount = 3 + rng.nextInt(4);
        EntityType<?>[] animalTypes = {
                EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN
        };
        EntityType<?> chosenType = animalTypes[rng.nextInt(animalTypes.length)];

        for (int i = 0; i < animalCount; i++) {
            int dx = rng.nextInt(shape.baseHalfL * 2) - shape.baseHalfW;
            int dz = rng.nextInt(shape.baseHalfL * 2) - shape.baseHalfL;
            if (!isInsidePlot(shape, dx, dz)) continue;

            BlockPos spawnPos = new BlockPos(
                    centre.getX() + dx, targetY + 1, centre.getZ() + dz);

            Entity animal = chosenType.create(level, EntitySpawnReason.MOB_SUMMONED);
            if (animal != null) {
                // FIX: Use setPos instead of moveTo
                animal.setPos(
                        spawnPos.getX() + 0.5,
                        spawnPos.getY(),
                        spawnPos.getZ() + 0.5
                );
                animal.setYRot(rng.nextFloat() * 360);
                animal.setXRot(0);
                level.addFreshEntity(animal);
            }
        }
    }

    // =========================================================================
    // Organic shape generation
    // =========================================================================

    /**
     * Generates a per-column edge offset table that gives the plot an
     * irregular, hand-cleared appearance.
     *
     * <p>For each column along each axis, a random offset in
     * [-jitter, +jitter] is stored. The actual plot boundary at column
     * {@code dx} is {@code baseHalfW + edgeOffset[dx + baseHalfW]}.
     * This means some columns extend further than the base and some are
     * shorter, producing a ragged but cohesive boundary.
     */
    private static PlotShape generateShape(Random rng,
                                           int baseHalfW, int baseHalfL,
                                           int jitter) {
        // Arrays must cover the full scan range: base + jitter + 2 buffer
        // Index = offset + halfDim, so max index = (halfDim + jitter + 2) + halfDim
        int wSize = (baseHalfW + jitter + 2) * 2 + 1;
        int lSize = (baseHalfL + jitter + 2) * 2 + 1;

        int[] westEdge  = new int[lSize];
        int[] eastEdge  = new int[lSize];
        int[] northEdge = new int[wSize];
        int[] southEdge = new int[wSize];

        int west = 0, east = 0, north = 0, south = 0;
        for (int i = 0; i < lSize; i++) {
            west  = clamp(west  + rng.nextInt(3) - 1, -jitter, jitter);
            east  = clamp(east  + rng.nextInt(3) - 1, -jitter, jitter);
            westEdge[i] = west;
            eastEdge[i] = east;
        }
        for (int i = 0; i < wSize; i++) {
            north = clamp(north + rng.nextInt(3) - 1, -jitter, jitter);
            south = clamp(south + rng.nextInt(3) - 1, -jitter, jitter);
            northEdge[i] = north;
            southEdge[i] = south;
        }

        return new PlotShape(baseHalfW, baseHalfL,
                baseHalfW + jitter + 2,   // centreOffsetW
                baseHalfL + jitter + 2,   // centreOffsetL
                westEdge, eastEdge, northEdge, southEdge);
    }

    /**
     * Returns true if the given offset (dx, dz) from plot centre is
     * inside the organic plot boundary.
     */
    private static boolean isInsidePlot(PlotShape shape, int dx, int dz) {
        // Arrays are sized (halfDim + EDGE_JITTER + 2) * 2 + 1
        // so centre index = halfDim + EDGE_JITTER + 2
        int centreOffsetW = shape.baseHalfW + EDGE_JITTER + 2;
        int centreOffsetL = shape.baseHalfL + EDGE_JITTER + 2;

        int lIdx = dx + shape.centreOffsetW;
        int wIdx = dz + shape.centreOffsetL;

        if (lIdx < 0 || lIdx >= shape.northEdge.length) return false;
        if (wIdx < 0 || wIdx >= shape.westEdge.length)  return false;

        int westBound  = -(shape.baseHalfW + shape.westEdge[wIdx]);
        int eastBound  =   shape.baseHalfW + shape.eastEdge[wIdx];
        int northBound = -(shape.baseHalfL + shape.northEdge[lIdx]);
        int southBound =   shape.baseHalfL + shape.southEdge[lIdx];

        return dx >= westBound && dx <= eastBound
                && dz >= northBound && dz <= southBound;
    }

    /**
     * Returns true if the position is on the plot boundary (inside but
     * adjacent to outside).
     */
    private static boolean isOnBoundary(PlotShape shape, int dx, int dz) {
        if (!isInsidePlot(shape, dx, dz)) return false;
        return !isInsidePlot(shape, dx + 1, dz)
                || !isInsidePlot(shape, dx - 1, dz)
                || !isInsidePlot(shape, dx, dz + 1)
                || !isInsidePlot(shape, dx, dz - 1);
    }

    // =========================================================================
    // Plot levelling
    // =========================================================================

    private static int medianFootprintY(ServerLevel level,
                                        BlockPos centre,
                                        PlotShape shape) {
        List<Integer> ys = new ArrayList<>();
        int scan = shape.baseHalfW + EDGE_JITTER + 2;
        for (int dx = -scan; dx <= scan; dx += 2) {
            for (int dz = -scan; dz <= scan; dz += 2) {
                if (!isInsidePlot(shape, dx, dz)) continue;

                int x = centre.getX() + dx;
                int z = centre.getZ() + dz;
                int y = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

                // Exclude water columns from the median calculation —
                // they would pull the target Y toward sea level and
                // cause the plot to generate submerged or floating
                BlockState below = level.getBlockState(
                        new BlockPos(x, y - 1, z));
                if (below.liquid()) continue;

                ys.add(y);
            }
        }

        // If all columns were water (shouldn't happen after resolveOnTerrain
        // rejects water, but guard anyway) fall back to centre Y
        if (ys.isEmpty()) return centre.getY();
        Collections.sort(ys);
        return ys.get(ys.size() / 2);
    }

    private static void levelPad(ServerLevel level, BlockPos centre,
                                 PlotShape shape, int targetY) {
        int scan = shape.baseHalfW + EDGE_JITTER + 2;
        for (int dx = -scan; dx <= scan; dx++) {
            for (int dz = -scan; dz <= scan; dz++) {
                if (!isInsidePlot(shape, dx, dz)) continue;
                int x = centre.getX() + dx;
                int z = centre.getZ() + dz;
                int surfY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

                // Never attempt to level a water column —
                // skip it and let the fence boundary handle the edge
                BlockState below = level.getBlockState(
                        new BlockPos(x, surfY - 1, z));
                if (below.liquid()) continue;

                if (surfY < targetY) {
                    for (int y = surfY + 1; y <= targetY; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState s = level.getBlockState(pos);
                        if (s.isAir() || s.is(BlockTags.REPLACEABLE)) {
                            level.setBlock(pos,
                                    Blocks.DIRT.defaultBlockState(), 3);
                        }
                    }
                } else if (surfY > targetY) {
                    for (int y = surfY; y > targetY; y--) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState s = level.getBlockState(pos);
                        if (isNatural(s)) {
                            level.setBlock(pos,
                                    Blocks.AIR.defaultBlockState(), 3);
                        } else break;
                    }
                }
            }
        }
    }

    // =========================================================================
    // Plot interior placement
    // =========================================================================

    private static void placePlot(ServerLevel level,
                                  BlockPos centre,
                                  PlotShape shape,
                                  int targetY,
                                  BlockState[] crops,
                                  VillageBiomeStyle style,
                                  Random rng) {
        int scan = shape.baseHalfW + EDGE_JITTER + 2;

        // Assign each X column to a crop strip
        // Strips run N/S, dividers are 1-block-wide earth rows
        // Strip boundaries at: baseHalfW * 2/3 and baseHalfW * -2/3
        int stripWidth = (shape.baseHalfW * 2) / STRIP_COUNT;

        for (int dx = -scan; dx <= scan; dx++) {
            for (int dz = -scan; dz <= scan; dz++) {
                boolean inside   = isInsidePlot(shape, dx, dz);
                boolean boundary = inside && isOnBoundary(shape, dx, dz);

                if (!inside) continue;

                int x = centre.getX() + dx;
                int z = centre.getZ() + dz;
                BlockPos surfaceBlock = new BlockPos(x, targetY, z);
                BlockPos above        = surfaceBlock.above();

                clearColumn(level, x, z, targetY);

                if (boundary) {
                    // ── Fence boundary ────────────────────────────────────────
                    // Place fence on top of a solid base block
                    level.setBlock(surfaceBlock,
                            Blocks.DIRT.defaultBlockState(), 3);
                    // Gate opening on south face (every 7 blocks of fence,
                    // at roughly the centre of each face)
                    boolean isGatePos = (Math.abs(dx) <= 1 && dz == scan - 1)
                            || (Math.abs(dz) <= 1 && dx == scan - 1);
                    if (isGatePos) {
                        level.setBlock(above,
                                style.fenceGateState(), 3);
                    } else {
                        level.setBlock(above,
                                style.fenceState(), 3);
                    }
                } else {
                    // ── Interior ──────────────────────────────────────────────
                    // Determine which strip this X column belongs to
                    int stripIndex = (dx + shape.baseHalfW) / Math.max(1, stripWidth);
                    stripIndex = clamp(stripIndex, 0, crops.length - 1);

                    // Narrow divider row between strips (coarse dirt, no crop)
                    boolean isDivider = (dx + shape.baseHalfW) % stripWidth == 0
                            && dx != -shape.baseHalfW;

                    // Water channel buried one below surface, every 5 rows
                    // within each strip — offset by strip so channels don't
                    // align across all strips
                    int channelOffset = stripIndex * 2;
                    boolean isWaterChannel = !isDivider
                            && ((dz + shape.baseHalfL + channelOffset) % 5 == 0);

                    if (isDivider) {
                        // Narrow earth divider between strips
                        level.setBlock(surfaceBlock,
                                Blocks.COARSE_DIRT.defaultBlockState(), 3);
                    } else if (isWaterChannel) {
                        // Buried irrigation channel
                        BlockPos waterPos = surfaceBlock;
                        level.setBlock(waterPos,
                                Blocks.WATER.defaultBlockState(), 3);
                        // Hydrated farmland on top
                        level.setBlock(surfaceBlock,
                                Blocks.FARMLAND.defaultBlockState()
                                        .setValue(FarmBlock.MOISTURE, 7), 3);
                        // No crop on water column — leave air
                    } else {
                        // Normal farmland with crop
                        level.setBlock(surfaceBlock,
                                Blocks.FARMLAND.defaultBlockState()
                                        .setValue(FarmBlock.MOISTURE, 7), 3);
                        if (level.getBlockState(above).isAir()) {
                            level.setBlock(above, crops[stripIndex], 3);
                        }
                    }
                }
            }
        }

        // ── Scarecrow ─────────────────────────────────────────────────────────
        // Place near the centre of the largest strip, offset slightly so it
        // doesn't sit exactly on the midpoint (more natural)
        int scarecrowDx = rng.nextInt(5) - 2;
        int scarecrowDz = rng.nextInt(5) - 2;
        if (isInsidePlot(shape, scarecrowDx, scarecrowDz)
                && !isOnBoundary(shape, scarecrowDx, scarecrowDz)) {
            BlockPos scarecrowBase = new BlockPos(
                    centre.getX() + scarecrowDx,
                    targetY + 1,  // on top of farmland
                    centre.getZ() + scarecrowDz);
            if (level.getBlockState(scarecrowBase).isAir()) {
                level.setBlock(scarecrowBase,
                        Blocks.OAK_FENCE.defaultBlockState(), 3);
                level.setBlock(scarecrowBase.above(),
                        Blocks.CARVED_PUMPKIN.defaultBlockState(), 3);
            }
        }

        // ── Composter ─────────────────────────────────────────────────────────
        // Place just inside a corner of the plot
        BlockPos composterPos = new BlockPos(
                centre.getX() + shape.baseHalfW - 1,
                targetY + 1,
                centre.getZ() - shape.baseHalfL + 1);
        if (level.getBlockState(composterPos).isAir()) {
            level.setBlock(composterPos,
                    Blocks.COMPOSTER.defaultBlockState(), 3);
        }

        // ── Hay bale ──────────────────────────────────────────────────────────
        // Near the fence, as if freshly cut and piled
        int hayDx = shape.baseHalfW - 1;
        int hayDz = shape.baseHalfL - 2;
        if (isInsidePlot(shape, hayDx, hayDz)) {
            BlockPos hayPos = new BlockPos(
                    centre.getX() + hayDx,
                    targetY + 1,
                    centre.getZ() + hayDz);
            if (level.getBlockState(hayPos).isAir()) {
                level.setBlock(hayPos,
                        Blocks.HAY_BLOCK.defaultBlockState(), 3);
            }
        }
    }

    // =========================================================================
    // Footpath to farmhouse
    // =========================================================================

    /**
     * Places a narrow dirt-path footpath from the plot's south gate
     * to the assigned farmhouse entrance.
     *
     * If a registered village path exists within 16 blocks of the route
     * midpoint, the footpath branches from that path rather than routing
     * all the way to the farmhouse — this avoids duplicate long paths
     * and creates the organic branching pattern of field tracks.
     */
    private static void placeFootpath(ServerLevel level,
                                      BlockPos centre,
                                      PlotShape shape,
                                      Building farmhouse,
                                      VillageSavedData data,
                                      Village village,
                                      VillageBiomeStyle style) {
        // Plot gate: south face centre, just outside the fence
        BlockPos plotGate = new BlockPos(
                centre.getX(),
                centre.getY(),
                centre.getZ() + shape.baseHalfL + EDGE_JITTER + 2);

        BlockPos farmhouseEntrance =
                PathRouter.getBuildingEntrance(farmhouse);

        // ── Find the best branch point ────────────────────────────────────────
        // Increase search radius to 48 blocks so farmhouses inside the
        // perimeter can still find the nearest path that crosses out to
        // the farm area.
        final int MAX_BRANCH_DIST = 48;

        // Check for a nearby path node near the PLOT GATE (not the midpoint)
        // since the gate is outside the perimeter and paths cross there
        Optional<BlockPos> nearGate = data.getNearestPathNode(
                plotGate, village.getId());
        Optional<BlockPos> nearFarmhouse = data.getNearestPathNode(
                farmhouseEntrance, village.getId());

        // Prefer the node nearest the plot gate — connecting outward
        // If neither is within range, route directly farmhouse → gate
        BlockPos routeStart;

        double gateNodeDist = nearGate
                .map(p -> Math.sqrt(p.distSqr(plotGate)))
                .orElse(Double.MAX_VALUE);
        double farmhouseNodeDist = nearFarmhouse
                .map(p -> Math.sqrt(p.distSqr(farmhouseEntrance)))
                .orElse(Double.MAX_VALUE);

        if (gateNodeDist <= MAX_BRANCH_DIST) {
            // A path exists near the plot gate — branch from it
            routeStart = nearGate.get();
        } else if (farmhouseNodeDist <= MAX_BRANCH_DIST) {
            // Path exists near the farmhouse — route from farmhouse
            routeStart = farmhouseEntrance;
        } else {
            // No nearby path — route directly gate → farmhouse entrance
            routeStart = farmhouseEntrance;
        }

        // Route from the plot gate to the branch point
        List<BlockPos> route = tterrag1112.life_in_the_village.Village
                .Economy.Trade.RoadRouter.findRoad(
                        level, plotGate, routeStart);

        if (route.isEmpty()) return;

        // Place single-block-wide dirt path
        for (BlockPos node : route) {
            int surfY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    node.getX(), node.getZ());
            BlockPos pathBlock = new BlockPos(node.getX(), surfY -1 , node.getZ());
            BlockState existing = level.getBlockState(pathBlock);

            if (!isNatural(existing)) continue;

            level.setBlock(pathBlock, style.pathState(), 3);

            BlockPos abv      = pathBlock.above();
            BlockState abvState = level.getBlockState(abv);
            if (!abvState.isAir()
                    && (abvState.is(BlockTags.REPLACEABLE)
                    || abvState.is(BlockTags.FLOWERS)
                    || abvState.is(BlockTags.LEAVES))) {
                level.setBlock(abv, Blocks.AIR.defaultBlockState(), 3);
            }
        }

        data.addVillagePath(new VillagePath(
                UUID.randomUUID(), village.getId(), route,
                VillagePath.PathTier.DIRT));
        data.setDirty();
    }

    // =========================================================================
    // Crop selection
    // =========================================================================

    /**
     * Chooses {@code count} distinct crops for the plot strips.
     * Always includes wheat as one option (most historically common).
     */
    private static BlockState[] chooseCrops(Random rng, int count) {
        List<BlockState> pool = new ArrayList<>(Arrays.asList(CROP_OPTIONS));
        Collections.shuffle(pool, rng);
        BlockState[] result = new BlockState[count];
        // Ensure wheat is always present
        result[0] = Blocks.WHEAT.defaultBlockState();
        for (int i = 1; i < count && i < pool.size(); i++) {
            result[i] = pool.get(i);
        }
        return result;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void clearColumn(ServerLevel level,
                                    int x, int z, int nearY) {
        for (int y = nearY + 10; y >= nearY - 1; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState s = level.getBlockState(pos);
            if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES)
                    || s.is(BlockTags.REPLACEABLE)
                    || s.is(BlockTags.FLOWERS)
                    || s.is(BlockTags.SMALL_FLOWERS)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }
    public static FarmPlot.CropType chooseCropType(
            VillageBiomeStyle style,
            int plotIndex,
            int totalPlots,
            Random rng,
            Village village,
            VillageSavedData data) {

        // Check if the village has a miller — if so, at least one plot is GRAIN
        boolean hasMiller = village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .anyMatch(b -> b.getType() == BuildingType.MILLER);

        // Biome-primary crop — drives the majority of plots
        FarmPlot.CropType biomePrimary = switch (style) {
            case SNOWY       -> FarmPlot.CropType.POTATOES;  // hardy cold-climate crop
            case DESERT      -> FarmPlot.CropType.CARROTS;   // oasis vegetable garden
            case JUNGLE      -> FarmPlot.CropType.ORCHARD;   // fruit-bearing jungle plots
            case SAVANNA     -> FarmPlot.CropType.PASTURE;   // grazing land
            case TAIGA       -> FarmPlot.CropType.VEGETABLE; // rich dark soil vegetables
            case SWAMP       -> FarmPlot.CropType.VEGETABLE; // waterlogged root veg
            default          -> FarmPlot.CropType.MIXED;     // temperate — standard rotation
        };

        // First plot: miller villages always start with GRAIN for bread supply
        if (plotIndex == 0 && hasMiller) {
            return FarmPlot.CropType.GRAIN;
        }

        // Single-plot village — use biome primary
        if (totalPlots == 1) return biomePrimary;

        // Multi-plot villages — distribute variety
        // Last plot: always VEGETABLE for nutritional diversity,
        //            unless biome dictates PASTURE
        if (plotIndex == totalPlots - 1) {
            return (biomePrimary == FarmPlot.CropType.PASTURE)
                    ? FarmPlot.CropType.PASTURE
                    : FarmPlot.CropType.VEGETABLE;
        }

        // Middle plots: biome primary with some random variety
        int roll = rng.nextInt(4);
        return switch (roll) {
            case 0  -> biomePrimary;
            case 1  -> hasMiller ? FarmPlot.CropType.GRAIN : FarmPlot.CropType.WHEAT;
            case 2  -> FarmPlot.CropType.MIXED;
            default -> biomePrimary;
        };
    }

    private static boolean isNatural(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT)
                || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.ROOTED_DIRT)
                || s.is(Blocks.SAND) || s.is(Blocks.RED_SAND)
                || s.is(Blocks.GRAVEL) || s.is(Blocks.SNOW_BLOCK)
                || s.is(BlockTags.REPLACEABLE) || s.isAir();
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    // =========================================================================
    // Shape data class
    // =========================================================================

    /**
     * Holds the organic edge offsets for a single plot.
     * All offsets are additive to the base half-dimensions.
     */
    private static class PlotShape {
        final int baseHalfW;
        final int baseHalfL;
        final int centreOffsetW; // = baseHalfW + EDGE_JITTER + 2
        final int centreOffsetL; // = baseHalfL + EDGE_JITTER + 2
        final int[] westEdge;
        final int[] eastEdge;
        final int[] northEdge;
        final int[] southEdge;

        PlotShape(int baseHalfW, int baseHalfL,
                  int centreOffsetW, int centreOffsetL,
                  int[] westEdge, int[] eastEdge,
                  int[] northEdge, int[] southEdge) {
            this.baseHalfW      = baseHalfW;
            this.baseHalfL      = baseHalfL;
            this.centreOffsetW  = centreOffsetW;
            this.centreOffsetL  = centreOffsetL;
            this.westEdge       = westEdge;
            this.eastEdge       = eastEdge;
            this.northEdge      = northEdge;
            this.southEdge      = southEdge;
        }
    }


}