// src/main/java/tterrag1112/life_in_the_village/Village/Planning/VillagePlanner.java
package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.TownSquarePlacer;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.*;

/**
 * Produces a terrain-aware {@link VillageLayout} plan before any blocks
 * are placed. The plan is consumed by {@link
 * tterrag1112.life_in_the_village.Village.VillageSpawner}.
 *
 * <h3>Y-coordinate convention</h3>
 * All surface queries in this class use
 * {@code MOTION_BLOCKING_NO_LEAVES}, which returns the solid surface
 * block Y (same as {@link
 * tterrag1112.life_in_the_village.Village.Economy.Trade.RoadRouter}).
 * This ensures slot Y values are consistent with the placement Y used
 * in {@code VillageSpawner.Phase 1}.
 */
public class VillagePlanner {

    private static final int DEFAULT_BUILDING_RADIUS = 8;
    private static final int FARM_PLOT_RADIUS        = 10;
    private static final int DECORATION_RADIUS       = 3;
    private static final int MAX_ATTEMPTS            = 32;

    // =========================================================================
    // Entry point
    // =========================================================================

    public static Optional<VillageLayout> plan(
            ServerLevel level,
            BlockPos origin,
            VillageTypeData typeData,
            Random rng,
            int villageLevel) {

        // ── Terrain analysis ──────────────────────────────────────────────────
        TerrainProfile terrain = TerrainAnalyzer.analyze(level, origin);
        if (!terrain.isSuitable()) {
            System.out.println("VillagePlanner: unsuitable terrain at "
                    + origin + " (score="
                    + String.format("%.2f", terrain.suitability()) + ")");
            return Optional.empty();
        }

        LayoutDensityProfile density = LayoutDensityProfile.forLevel(villageLevel);
        VillageLayout layout = new VillageLayout(terrain, density);
        StructureSizeCache sizeCache = new StructureSizeCache(level);


        // ── Snap origin to flattest nearby point ──────────────────────────────
        BlockPos centre = snapToFlat(level, terrain, origin);
        layout.setCenter(centre);

        // ── Town hall at centre ───────────────────────────────────────────────
        placeTownHall(layout, level, typeData, centre, rng, sizeCache);

        // ── Town square south of town hall ────────────────────────────────────
        // Clearance ensures the square is beyond ring 1 and doesn't overlap
        // the town hall. Placed due south because buildings face south by
        // default (Rotation.NONE entrance faces south in VillageSpawner).
        int squareClearance = Math.max(
                density.getRing1Radius() + TownSquarePlacer.RADIUS + 4,
                DEFAULT_BUILDING_RADIUS + TownSquarePlacer.RADIUS + 8);

        BlockPos squareIdeal = centre.south(squareClearance);
        BlockPos squarePos   = resolveOnTerrain(level, layout, squareIdeal,
                4, TownSquarePlacer.RADIUS + 2, rng);
        if (squarePos == null) squarePos = solidSurface(level, squareIdeal);

        layout.tryAdd(new LayoutSlot(
                LayoutSlot.SlotType.DECORATION,
                squarePos,
                TownSquarePlacer.RADIUS + 2));
        layout.setTownSquarePos(squarePos);

        // ── Ring buildings ─────────────────────────────────────────────────────
        placeRingBuildings(layout, level, typeData, centre, density, rng, sizeCache);
        if (layout.getTerrain().hasWater()) {
            placeWaterEdgeBuildings(layout, level, typeData,
                    layout.getCenter(), layout.getTerrain(), density, rng);
        }

        // ── Cluster nearby buildings to similar Y ──────────────────────────────
        clusterBuildingYLevels(layout, level);

        // ── Farm plots ─────────────────────────────────────────────────────────
        placeFarmPlots(layout, level, terrain, density, rng);

        // ── Decoration clusters ────────────────────────────────────────────────
        placeDecorationClusters(layout, level, terrain, density, rng);

        System.out.println("VillagePlanner: planned — " + layout);
        return Optional.of(layout);
    }

    // =========================================================================
    // Town hall
    // =========================================================================

    private static void placeTownHall(VillageLayout layout,
                                      ServerLevel level,
                                      VillageTypeData typeData,
                                      BlockPos centre,
                                      Random rng,
                                      StructureSizeCache sizeCache) {
        String path = typeData.getStarterBuildings().stream()
                .filter(sb -> sb.type().equals(BuildingType.TOWN_HALL.name()))
                .findFirst()
                .map(VillageTypeData.StarterBuilding::structure)
                .orElse("town_hall/level_1");

        LayoutSlot slot = new LayoutSlot(
                centre, BuildingType.TOWN_HALL,
                path, StructureSizeCache.DEFAULT_RADIUS);

        // Set real footprint from template
        StructureSizeCache.FootprintInfo info =
                sizeCache.get(path, Rotation.NONE);
        slot.setFootprint(info.width(), info.length());

        layout.tryAdd(slot);
    }

    // =========================================================================
    // Ring buildings
    // =========================================================================

    private static void placeRingBuildings(VillageLayout layout,
                                           ServerLevel level,
                                           VillageTypeData typeData,
                                           BlockPos centre,
                                           LayoutDensityProfile density,
                                           Random rng, StructureSizeCache sizeCache) {
        List<VillageTypeData.StarterBuilding> remaining = new ArrayList<>(
                typeData.getStarterBuildings());
        remaining.removeIf(sb -> sb.type().equals(BuildingType.TOWN_HALL.name()));


        // Sort by zone priority so civic buildings fill ring 1 first,
        // then production, then residential, then defensive on the outer ring
        remaining.sort(Comparator.comparingInt(sb -> {
            try {
                BuildingType bt = BuildingType.valueOf(sb.type());
                ZoneRegistry.ZoneEntry ze = ZoneRegistry.get(bt);
                return ze.zone().preferredRing * 100 + ze.priority();
            } catch (IllegalArgumentException e) {
                return 999;
            }
        }));

        List<Integer> radii = new ArrayList<>();
        radii.add(density.getRing1Radius());
        if (density.isUseRing2()) radii.add(density.getRing2Radius());
        if (density.isUseRing3()) radii.add(density.getRing3Radius());

        // Compute the angle toward the town square (south by default)
        // so zone sectors are oriented correctly
        BlockPos squarePos = layout.getTownSquarePos();
        TerrainProfile terrain = layout.getTerrain();

        double squareAngle;
        if (terrain.hasWater() && terrain.waterFacingDir() != null) {
            squareAngle = switch (terrain.waterFacingDir()) {
                case NORTH -> 270.0;
                case SOUTH -> 90.0;
                case EAST  -> 0.0;
                case WEST  -> 180.0;
            };
            System.out.println("VillagePlanner: village faces water to the "
                    + terrain.waterFacingDir());
        } else {
            squareAngle = angleTo(centre, squarePos != null
                    ? squarePos : centre.south(1));
        }

        int buildingIdx = 0;

        for (int ring = 0; ring < radii.size() && buildingIdx < remaining.size(); ring++) {
            int    radius = radii.get(ring);
            int    slots  = Math.max(4, (int)(2 * Math.PI * radius
                    / density.getBuildingSpacing()));
            double angleOffset = rng.nextDouble() * (Math.PI / slots); // small random offset

            for (int i = 0; i < slots && buildingIdx < remaining.size(); i++) {
                // Compute the raw angle for this slot (0–360, clockwise from east)
                double rawAngle = angleOffset + (2 * Math.PI * i / slots);
                // Convert to degrees clockwise from south for zone matching
                double slotAngleDeg = ((Math.toDegrees(rawAngle)
                        - squareAngle + 360) % 360);

                VillageTypeData.StarterBuilding sb = remaining.get(buildingIdx);
                BuildingType btype;
                try {
                    btype = BuildingType.valueOf(sb.type());
                } catch (IllegalArgumentException e) {
                    buildingIdx++;
                    continue;
                }

                ZoneRegistry.ZoneEntry zone = ZoneRegistry.get(btype);

                // If this slot's angle is NOT in the zone's preferred sector,
                // skip this slot (the building will be tried at the next slot).
                // Only enforce zone sectors for ring 1 — outer rings are more flexible.
                if (ring == 0 && !zone.zone().containsAngle(slotAngleDeg)) {
                    // Don't increment buildingIdx — try the next angular slot
                    // for the same building. If all slots on ring 1 are tried,
                    // the building moves to ring 2 naturally.
                    continue;
                }

                int idealX = centre.getX() + (int)(Math.cos(rawAngle) * radius);
                int idealZ = centre.getZ() + (int)(Math.sin(rawAngle) * radius);

                boolean rejectWaterForSlot =
                        ZoneRegistry.zoneOf(btype) == BuildingZone.AGRICULTURAL;

                BlockPos placed = resolveOnTerrain(
                        level, layout,
                        new BlockPos(idealX, centre.getY(), idealZ),
                        density.getBuildingJitter(),
                        DEFAULT_BUILDING_RADIUS,
                        rng,
                        rejectWaterForSlot);

                if (placed != null) {
                    // Use the rotation that VillageSpawner will choose
                    // for this slot — approximate with NONE since we
                    // don't know exact rotation at plan time, but the
                    // footprint is symmetric enough for overlap purposes
                    StructureSizeCache.FootprintInfo info =
                            sizeCache.get(sb.structure(), Rotation.NONE);

                    LayoutSlot slot = new LayoutSlot(
                            placed, btype, sb.structure(),
                            StructureSizeCache.DEFAULT_RADIUS);
                    slot.setFootprint(info.width(), info.length());

                    layout.tryAdd(slot);
                    buildingIdx++;
                }
            }
        }

        // Second pass: place any buildings that couldn't fit their preferred zone
        // into any available remaining slot
        if (buildingIdx < remaining.size()) {
            placeRemainingBuildings(layout, level, remaining,
                    buildingIdx, centre, density, radii, rng);
        }
    }

    private static void placeRemainingBuildings(
            VillageLayout layout, ServerLevel level,
            List<VillageTypeData.StarterBuilding> remaining,
            int startIdx, BlockPos centre,
            LayoutDensityProfile density,
            List<Integer> radii, Random rng) {

        for (int idx = startIdx; idx < remaining.size(); idx++) {
            VillageTypeData.StarterBuilding sb = remaining.get(idx);
            BuildingType btype;
            try {
                btype = BuildingType.valueOf(sb.type());
            } catch (IllegalArgumentException e) { continue; }

            // Try each ring from outermost inward
            boolean placed = false;
            for (int ring = radii.size() - 1; ring >= 0 && !placed; ring--) {
                int    radius = radii.get(ring);
                int    slots  = Math.max(4, (int)(2 * Math.PI * radius
                        / density.getBuildingSpacing()));
                double startAngle = rng.nextDouble() * 2 * Math.PI;

                for (int i = 0; i < slots && !placed; i++) {
                    double angle  = startAngle + (2 * Math.PI * i / slots);
                    int    idealX = centre.getX() + (int)(Math.cos(angle) * radius);
                    int    idealZ = centre.getZ() + (int)(Math.sin(angle) * radius);

                    BlockPos pos = resolveOnTerrain(level, layout,
                            new BlockPos(idealX, centre.getY(), idealZ),
                            density.getBuildingJitter(),
                            DEFAULT_BUILDING_RADIUS, rng);

                    if (pos != null) {
                        layout.tryAdd(new LayoutSlot(
                                pos, btype, sb.structure(),
                                DEFAULT_BUILDING_RADIUS));
                        placed = true;
                    }
                }
            }

            if (!placed) {
                System.out.println("VillagePlanner: could not place "
                        + btype + " in any ring");
            }
        }
    }

    /** Angle in degrees clockwise from east (standard math convention → game). */
    private static double angleTo(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return (Math.toDegrees(Math.atan2(dz, dx)) + 360) % 360;
    }

    // =========================================================================
    // Y clustering
    // =========================================================================

    /**
     * Snaps building slot Y values toward the median of their neighbours.
     * Prevents staircase layouts on gentle slopes.
     * Uses the live heightmap to verify the snap won't bury or float
     * the building.
     */
    private static void clusterBuildingYLevels(VillageLayout layout,
                                               ServerLevel level) {
        List<LayoutSlot> buildings = layout.buildings();
        // Search across all rings — use the outermost radius
        int searchRadius = layout.getDensity().isUseRing3()
                ? layout.getDensity().getRing3Radius()
                : layout.getDensity().isUseRing2()
                ? layout.getDensity().getRing2Radius()
                : layout.getDensity().getRing1Radius();

        for (LayoutSlot slot : buildings) {
            List<Integer> nearbyY = new ArrayList<>();
            for (LayoutSlot other : buildings) {
                if (other == slot) continue;
                int dx = Math.abs(slot.getPos().getX() - other.getPos().getX());
                int dz = Math.abs(slot.getPos().getZ() - other.getPos().getZ());
                if (Math.max(dx, dz) <= searchRadius) {
                    nearbyY.add(other.getPos().getY());
                }
            }
            if (nearbyY.isEmpty()) continue;

            Collections.sort(nearbyY);
            int medianY = nearbyY.get(nearbyY.size() / 2);
            int diff    = Math.abs(slot.getPos().getY() - medianY);

            if (diff > 0 && diff <= 6) {
                // Re-query live surface after site prep
                int actualSurface = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        slot.getPos().getX(), slot.getPos().getZ());
                // Only snap if the actual terrain supports the target Y
                if (Math.abs(actualSurface - medianY) <= 4) {
                    slot.snapY(medianY);
                }
            }
        }
    }

    // =========================================================================
    // Farm plots
    // =========================================================================

    private static void placeFarmPlots(VillageLayout layout,
                                       ServerLevel level,
                                       TerrainProfile terrain,
                                       LayoutDensityProfile density,
                                       Random rng) {
        int plotCount = Math.max(1, density.getVillageLevel() / 2);
        int[] dir     = flatDirVector(terrain.bestFlatDir());
        int perpX     =  dir[1];
        int perpZ     = -dir[0];

        // Compute the approximate perimeter radius so plots start beyond it.
        // VillagePerimeter uses outermost building radius + PERIMETER_OFFSET (4).
        // We add a further buffer so plots are clearly outside the wall.
        int ring2     = density.getRing2Radius();
        int perimeterRadius = ring2 + 12; // rough perimeter + buffer

        for (int i = 0; i < plotCount; i++) {
            int sideOffset = (i - plotCount / 2) * (FARM_PLOT_RADIUS * 2 + 4);

            // Start the ideal position beyond the perimeter radius
            int tx = layout.getCenter().getX()
                    + dir[0] * Math.max(density.getFarmOffset(), perimeterRadius)
                    + perpX * sideOffset;
            int tz = layout.getCenter().getZ()
                    + dir[1] * Math.max(density.getFarmOffset(), perimeterRadius)
                    + perpZ * sideOffset;

            BlockPos idealPos = terrain.bestFlatNear(
                    tx - terrain.origin().getX(),
                    tz - terrain.origin().getZ());

            // rejectWater = true — farm plots must never touch water
            BlockPos resolved = resolveOnTerrain(
                    level, layout, idealPos,
                    4, FARM_PLOT_RADIUS, rng, true);

            if (resolved != null) {
                layout.tryAdd(new LayoutSlot(
                        LayoutSlot.SlotType.FARM_PLOT,
                        resolved, FARM_PLOT_RADIUS));
            }
        }
    }

    // =========================================================================
    // Decoration clusters
    // =========================================================================

    private static void placeDecorationClusters(VillageLayout layout,
                                                ServerLevel level,
                                                TerrainProfile terrain,
                                                LayoutDensityProfile density,
                                                Random rng) {
        int clusters  = density.getDecorationClusters();
        int gapRadius = (density.getRing1Radius()
                + density.getRing2Radius()) / 2;

        for (int i = 0; i < clusters; i++) {
            double angle = (2 * Math.PI * i / clusters) + rng.nextDouble() * 0.8;
            int dx = (int)(Math.cos(angle) * gapRadius);
            int dz = (int)(Math.sin(angle) * gapRadius);

            BlockPos ideal    = terrain.bestFlatNear(dx, dz);
            BlockPos resolved = resolveOnTerrain(
                    level, layout, ideal, 6, DECORATION_RADIUS, rng);

            if (resolved != null) {
                layout.addForced(new LayoutSlot(
                        LayoutSlot.SlotType.DECORATION,
                        resolved, DECORATION_RADIUS));
            }
        }
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    private static BlockPos resolveOnTerrain(ServerLevel level,
                                             VillageLayout layout,
                                             BlockPos ideal,
                                             int jitterRange,
                                             int radius,
                                             Random rng) {
        return resolveOnTerrain(level, layout, ideal,
                jitterRange, radius, rng, false);
    }

    private static BlockPos resolveOnTerrain(ServerLevel level,
                                             VillageLayout layout,
                                             BlockPos ideal,
                                             int jitterRange,
                                             int radius,
                                             Random rng,
                                             boolean rejectWater) {
        TerrainProfile terrain = layout.getTerrain();

        // 1. Exact ideal
        BlockPos candidate = solidSurface(level, ideal);
        if (!terrain.isOnRidge(candidate, radius)
                && !(rejectWater && isWaterAdjacent(level, candidate))
                && noOverlap(layout, candidate, radius)) {
            return candidate;
        }

        // 2. Jitter attempts
        for (int a = 0; a < MAX_ATTEMPTS / 2; a++) {
            int jx = rng.nextInt(jitterRange * 2 + 1) - jitterRange;
            int jz = rng.nextInt(jitterRange * 2 + 1) - jitterRange;
            candidate = solidSurface(level, ideal.offset(jx, 0, jz));
            if (!terrain.isOnRidge(candidate, radius)
                    && !(rejectWater && isWaterAdjacent(level, candidate))
                    && noOverlap(layout, candidate, radius)) {
                return candidate;
            }
        }

        // 3. Prefer clearing candidates near ideal
        BlockPos nearestClearing = terrain.bestFlatNear(
                ideal.getX() - terrain.origin().getX(),
                ideal.getZ() - terrain.origin().getZ());
        candidate = solidSurface(level, nearestClearing);
        if (!terrain.isOnRidge(candidate, radius)
                && !(rejectWater && isWaterAdjacent(level, candidate))
                && noOverlap(layout, candidate, radius)) {
            return candidate;
        }

        // 4. Spiral outward
        int step = Math.max(4, DEFAULT_BUILDING_RADIUS + 2);
        for (int ring = 1; ring <= MAX_ATTEMPTS / 2; ring++) {
            for (int[] off : new int[][]{
                    {ring, 0}, {-ring, 0}, {0, ring}, {0, -ring},
                    {ring, ring}, {-ring, ring},
                    {ring, -ring}, {-ring, -ring}}) {
                candidate = solidSurface(level,
                        ideal.offset(off[0] * step, 0, off[1] * step));
                if (!terrain.isOnRidge(candidate, radius)
                        && !(rejectWater && isWaterAdjacent(level, candidate))
                        && noOverlap(layout, candidate, radius)) {
                    return candidate;
                }
            }
        }

        return null;
    }

    /**
     * Returns true if a circle of {@code radius} at {@code pos} doesn't
     * overlap any BUILDING or FARM_PLOT slot in the layout.
     * PATH_NODE and DECORATION slots are intentionally excluded from
     * the check — they are allowed to be close together.
     */
    private static boolean noOverlap(VillageLayout layout,
                                     BlockPos pos, int radius) {
        LayoutSlot probe = new LayoutSlot(
                LayoutSlot.SlotType.PATH_NODE, pos, radius);
        for (LayoutSlot existing : layout.getAllSlots()) {
            if (existing.getSlotType() == LayoutSlot.SlotType.PATH_NODE) continue;
            if (probe.overlaps(existing)) return false;
        }
        return true;
    }

    /**
     * Returns the solid surface block Y at the given XZ using
     * {@code MOTION_BLOCKING_NO_LEAVES}. Consistent with RoadRouter,
     * VillageDecorator, and VillageSpawner placement Y queries.
     */
    private static BlockPos solidSurface(ServerLevel level, BlockPos pos) {
        int y = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getX(), pos.getZ());
        return new BlockPos(pos.getX(), y, pos.getZ());
    }

    /**
     * Snaps the origin to the nearest flat candidate within 16 blocks.
     * Falls back to the live heightmap at the origin if no candidate
     * is close enough.
     */
    private static BlockPos snapToFlat(ServerLevel level,
                                       TerrainProfile terrain,
                                       BlockPos origin) {
        return terrain.flatCandidates().stream()
                .filter(p -> p.distSqr(origin) <= 16 * 16)
                .min(Comparator.comparingDouble(p -> p.distSqr(origin)))
                .orElseGet(() -> solidSurface(level, origin));
    }

    private static int[] flatDirVector(TerrainAnalyzer.FlatDirection dir) {
        return switch (dir) {
            case NORTH -> new int[]{ 0, -1};
            case SOUTH -> new int[]{ 0,  1};
            case EAST  -> new int[]{ 1,  0};
            case WEST  -> new int[]{-1,  0};
        };
    }
    private static void placeWaterEdgeBuildings(
            VillageLayout layout,
            ServerLevel level,
            VillageTypeData typeData,
            BlockPos centre,
            TerrainProfile terrain,
            LayoutDensityProfile density,
            Random rng) {

        TerrainAnalyzer.WaterBodyInfo water = terrain.waterBody();
        if (water == null) return;

        BlockPos shore = water.nearestShore();

        // ── Docks ─────────────────────────────────────────────────────────────
        // Try to place docks on the dry land just adjacent to the shore
        BlockPos docksIdeal = shore.offset(
                Integer.signum(centre.getX() - shore.getX()) * 3,
                0,
                Integer.signum(centre.getZ() - shore.getZ()) * 3);

        BlockPos docksPos = resolveOnTerrain(level, layout, docksIdeal,
                3, DEFAULT_BUILDING_RADIUS, rng);

        if (docksPos != null) {
            String docksPath = findBuildingPath(typeData,
                    BuildingType.DOCKS, "docks/level_1");
            layout.tryAdd(new LayoutSlot(
                    docksPos, BuildingType.DOCKS,
                    docksPath, DEFAULT_BUILDING_RADIUS));
            System.out.println("VillagePlanner: placed DOCKS at shoreline "
                    + docksPos.toShortString());
        }

        // ── Mill ─────────────────────────────────────────────────────────────
        // Place 8–14 blocks inland from shore — close enough to use water,
        // far enough to stay dry
        int inlandDist = 8 + rng.nextInt(6);
        BlockPos millIdeal = shore.offset(
                Integer.signum(centre.getX() - shore.getX()) * inlandDist,
                0,
                Integer.signum(centre.getZ() - shore.getZ()) * inlandDist);

        BlockPos millPos = resolveOnTerrain(level, layout, millIdeal,
                4, DEFAULT_BUILDING_RADIUS, rng);

        if (millPos != null) {
            String millPath = findBuildingPath(typeData,
                    BuildingType.MILLER, "miller/level_1");
            layout.tryAdd(new LayoutSlot(
                    millPos, BuildingType.MILLER,
                    millPath, DEFAULT_BUILDING_RADIUS));
            System.out.println("VillagePlanner: placed MILLER near water at "
                    + millPos.toShortString());
        }

        // ── Shoreline fishing huts (houses near water) ─────────────────────
        // Place 1–2 houses along the shore at intervals if the water body
        // is large enough to make waterfront housing make sense
        if (water.radius() >= 8) {
            int hutCount = water.radius() >= 20 ? 2 : 1;
            for (int i = 0; i < hutCount; i++) {
                // Spread along the shore using an angle offset
                double shoreAngle = Math.atan2(
                        shore.getZ() - water.centre().getZ(),
                        shore.getX() - water.centre().getX());
                double offset = (i + 1) * (Math.PI / 4);
                int hx = water.centre().getX()
                        + (int)(Math.cos(shoreAngle + offset) * (water.radius() + 2));
                int hz = water.centre().getZ()
                        + (int)(Math.sin(shoreAngle + offset) * (water.radius() + 2));

                BlockPos hutIdeal = solidSurface(level, new BlockPos(hx, 64, hz));
                BlockPos hutPos   = resolveOnTerrain(level, layout, hutIdeal,
                        4, DEFAULT_BUILDING_RADIUS, rng);

                if (hutPos != null) {
                    String housePath = findBuildingPath(typeData,
                            BuildingType.HOUSE, "house/level_1");
                    layout.tryAdd(new LayoutSlot(
                            hutPos, BuildingType.HOUSE,
                            housePath, DEFAULT_BUILDING_RADIUS));
                }
            }
        }
    }

    /**
     * Finds the structure path for a building type in typeData,
     * falling back to the given default if not present.
     */
    private static String findBuildingPath(VillageTypeData typeData,
                                           BuildingType type,
                                           String fallback) {
        return typeData.getStarterBuildings().stream()
                .filter(sb -> {
                    try {
                        return BuildingType.valueOf(sb.type()) == type;
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                })
                .map(VillageTypeData.StarterBuilding::structure)
                .findFirst()
                .orElse(fallback);
    }
    private static boolean isWaterAdjacent(ServerLevel level, BlockPos pos) {
        // Check a 3×3 area so plots don't generate with one edge in water
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = pos.getX() + dx;
                int z = pos.getZ() + dz;
                int y = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockState below = level.getBlockState(
                        new BlockPos(x, y - 1, z));
                if (below.liquid()) return true;
            }
        }
        return false;
    }
}