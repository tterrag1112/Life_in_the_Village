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
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Planning.LayoutPlan;
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

    /**
     * Phase 17: thin realiser — consumes planned plot slots from
     * {@link VillageLayout#plotSlots()} and executes the realisation
     * (level pad, crops, animals, fence-with-gate, footpath, FarmPlot
     * registration) on the planner-validated positions.
     *
     * <p>The spiral search, {@code findPlotLocation}, and bound-aware
     * retry loop are gone — those are now the planner's job
     * ({@code VillagePlanner.runFarmPlotPass}).
     */
    public static void placeAll(ServerLevel level,
                                VillageLayout layout,
                                Village village,
                                VillageSavedData data,
                                Random rng) {
        VillageTypeData typeData = VillageTypeRegistry.INSTANCE
                .getType(village.getVillageType());
        VillageTypeData.FarmPlotConfig config = typeData.getFarmPlotConfig();

        if (config.placement() == VillageTypeData.FarmPlotPlacement.NONE) {
            System.out.println("FarmPlotPlacer: farming disabled for "
                    + village.getVillageType());
            return;
        }

        // Phase 19: read planned plots from the immutable LayoutPlan instead
        // of layout.plotSlots() / layout.getPlotSpec(). PlannedPlot carries
        // everything we need (centre, subtype, halfW/L, edgeJitterSeed,
        // ownerFarmhousePos) inline — no spec lookup, no parallel map.
        LayoutPlan plan = layout.getPlan();
        List<LayoutPlan.PlannedPlot> plots =
                plan != null ? plan.plotSlots() : List.of();
        if (plots.isEmpty()) {
            System.out.println("FarmPlotPlacer: no planned plot slots for "
                    + village.getName());
            return;
        }

        VillageBiomeStyle style = VillageBiomeStyle.detect(level, layout.getCenter());

        int realised = 0;
        int droppedNoOwner = 0;

        for (LayoutPlan.PlannedPlot pp : plots) {
            // Resolve owner UUID by looking up the farmhouse Building whose
            // origin is closest to the planned ownerFarmhousePos. The
            // planner stamped the LayoutSlot position; the realiser maps
            // it to the persisted Building UUID created by BuildingPlacer.
            Building owner = resolveOwner(pp.ownerFarmhousePos(), village, data);
            if (owner == null) {
                System.out.println("FarmPlotPlacer: planned plot at " + pp.centre()
                        + " — owner farmhouse at " + pp.ownerFarmhousePos()
                        + " did not materialise; skipping");
                droppedNoOwner++;
                continue;
            }

            // Regenerate the deterministic plot shape from the planning seed.
            Random shapeRng = new Random(pp.edgeJitterSeed());
            PlotShape shape = generateShape(shapeRng,
                    pp.halfW(), pp.halfL(), EDGE_JITTER);
            int targetY = medianFootprintY(level, pp.centre(), shape);
            BlockPos flatCentre = new BlockPos(
                    pp.centre().getX(), targetY, pp.centre().getZ());

            levelPad(level, flatCentre, shape, targetY);

            FarmPlot plot;
            String plotName;
            if (pp.subtype() == FarmPlot.PlotSubtype.CROP_FIELD) {
                BlockState[] crops = chooseCrops(rng, STRIP_COUNT);
                placeCropPlot(level, flatCentre, shape, targetY, crops, style, rng);
                FarmPlot.CropType cropType = chooseCropType(
                        style, realised, plots.size(), rng, village, data);
                plotName = village.getName() + "_farm_" + (realised + 1);
                plot = new FarmPlot(
                        UUID.randomUUID(), plotName, flatCentre,
                        Math.max(pp.halfW(), pp.halfL()),
                        cropType, FarmPlot.PlotSubtype.CROP_FIELD);
            } else {
                placeAnimalPen(level, flatCentre, shape, targetY, style, rng);
                plotName = village.getName() + "_pen_" + (realised + 1);
                plot = new FarmPlot(
                        UUID.randomUUID(), plotName, flatCentre,
                        Math.max(pp.halfW(), pp.halfL()),
                        FarmPlot.CropType.PASTURE,
                        FarmPlot.PlotSubtype.ANIMAL_PEN);
            }

            plot.setFarmhouseId(owner.getId());
            placeFootpath(level, flatCentre, shape, owner, data, village, style);
            data.addFarmPlot(plot);
            System.out.println("FarmPlotPlacer: realised "
                    + pp.subtype() + " '" + plotName + "' at " + flatCentre);
            realised++;
        }

        System.out.println("FarmPlotPlacer: planned=" + plots.size()
                + " realised=" + realised
                + " droppedNoOwner=" + droppedNoOwner);
    }

    /** Position-to-UUID lookup for plot ownership. The planner stamps the
     *  FARMHOUSE LayoutSlot's position into FarmPlotSpec.ownerFarmhousePos;
     *  the realiser finds the corresponding Building whose origin is
     *  closest to that position. Returns null if no farmhouse Building
     *  was actually persisted (e.g., structure load error during
     *  BuildingPlacer). */
    private static Building resolveOwner(BlockPos plannedPos,
                                         Village village,
                                         VillageSavedData data) {
        return village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() == BuildingType.FARMHOUSE)
                .min(Comparator.comparingDouble(
                        b -> b.getShape().getOrigin().distSqr(plannedPos)))
                .orElse(null);
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

        // Route from the plot gate to the branch point.
        // Phase 20: prefer the village's road graph (via village.getPlan())
        // so the footpath actually follows the road network instead of
        // cutting cross-country. RoadRouter.findRoad falls back when no
        // plan is available (old saves).
        LayoutPlan layoutPlan = village.getPlan();
        List<BlockPos> route;
        if (layoutPlan != null && !layoutPlan.roads().isEmpty()) {
            route = findFootpathOnGraph(plotGate, routeStart, layoutPlan);
        } else {
            route = tterrag1112.life_in_the_village.Village
                    .Economy.Trade.RoadRouter.findRoad(
                            level, plotGate, routeStart);
        }

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

    // =========================================================================
    // Phase 20: graph-aware footpath routing
    // =========================================================================

    /**
     * Builds a footpath route using the village's persisted road graph.
     * Picks the road edge whose centerline minimises {@code dist(plotGate
     * → nearest) + dist(farmhouseEntrance → nearest)} and slices that
     * edge between the two nearest points. The diagonal bits at start /
     * end (plotGate → firstCenterlinePoint, lastCenterlinePoint →
     * farmhouseEntrance) cover any off-road segments.
     *
     * <p>This is intentionally a single-edge slice rather than full
     * BFS-over-edges. For typical villages the plot's gate and its owning
     * farmhouse share an obvious feeding road; the multi-edge case (plot
     * across the village from its farmhouse) is rare and the diagonal
     * fallback at endpoints still produces a connected path.
     */
    private static List<BlockPos> findFootpathOnGraph(
            BlockPos plotGate, BlockPos farmhouseEntrance, LayoutPlan plan) {
        LayoutPlan.RoadEdge bestEdge = null;
        double bestScore = Double.MAX_VALUE;
        int bestPlotIdx = -1;
        int bestHouseIdx = -1;
        for (LayoutPlan.RoadEdge edge : plan.roads()) {
            if (edge.centerline().isEmpty()) continue;
            int plotIdx  = nearestIdxOn(edge.centerline(), plotGate);
            int houseIdx = nearestIdxOn(edge.centerline(), farmhouseEntrance);
            double plotD  = Math.sqrt(edge.centerline().get(plotIdx).distSqr(plotGate));
            double houseD = Math.sqrt(edge.centerline().get(houseIdx).distSqr(farmhouseEntrance));
            double score = plotD + houseD;
            if (score < bestScore) {
                bestScore = score;
                bestEdge = edge;
                bestPlotIdx = plotIdx;
                bestHouseIdx = houseIdx;
            }
        }
        if (bestEdge == null) {
            return List.of(plotGate, farmhouseEntrance);
        }
        List<BlockPos> route = new ArrayList<>();
        route.add(plotGate);
        List<BlockPos> cl = bestEdge.centerline();
        if (bestPlotIdx <= bestHouseIdx) {
            for (int i = bestPlotIdx; i <= bestHouseIdx; i++) route.add(cl.get(i));
        } else {
            for (int i = bestPlotIdx; i >= bestHouseIdx; i--) route.add(cl.get(i));
        }
        route.add(farmhouseEntrance);
        return route;
    }

    private static int nearestIdxOn(List<BlockPos> centerline, BlockPos target) {
        int best = 0;
        double bestDistSq = centerline.get(0).distSqr(target);
        for (int i = 1; i < centerline.size(); i++) {
            double d = centerline.get(i).distSqr(target);
            if (d < bestDistSq) { bestDistSq = d; best = i; }
        }
        return best;
    }
}