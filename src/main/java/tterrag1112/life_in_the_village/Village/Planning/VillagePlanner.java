package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.TownSquarePlacer;
import tterrag1112.life_in_the_village.Village.VillageTypeData;
import tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType;
import tterrag1112.life_in_the_village.Village.VillageTypeData.VillageShapeProfile;

import java.util.*;

/**
 * Produces a terrain-aware {@link VillageLayout} before any blocks are placed.
 *
 * <h3>Key fixes in this rewrite</h3>
 * <ul>
 *   <li><b>Building count expansion</b>: ALL shape planners call
 *       {@link #expandBuildingList} to respect min_count/max_count from
 *       the village type JSON. Previously only planRadial did this.</li>
 *   <li><b>Footprint-aware overlap</b>: ALL shape planners set footprint
 *       dimensions on every LayoutSlot via {@code slot.setFootprint(w, l)}.
 *       VillageLayout.tryAdd() then uses AABB overlap instead of radius
 *       circles, preventing buildings with large footprints from overlapping
 *       even when their centers are far apart.</li>
 *   <li><b>Angular retry</b>: {@link #resolveSlotOnRing} tries 12 angular
 *       offsets before giving up, instead of silently overlapping.</li>
 * </ul>
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
            ServerLevel level, BlockPos origin,
            VillageTypeData typeData, Random rng, int villageLevel) {

        TerrainProfile terrain = TerrainAnalyzer.analyze(level, origin);
        if (!terrain.isSuitable()) {
            System.out.println("VillagePlanner: unsuitable terrain at " + origin);
            return Optional.empty();
        }

        // Expand building list ONCE — all shapes use this
        List<VillageTypeData.StarterBuilding> expanded =
                expandBuildingList(typeData.getStarterBuildings(), rng);
        int buildingCount = expanded.size();

        boolean isCapital = buildingCount >= LayoutDensityProfile.CAPITAL_THRESHOLD;
        LayoutDensityProfile density = isCapital
                ? LayoutDensityProfile.forCapital(buildingCount)
                : LayoutDensityProfile.forLevel(villageLevel);

        StructureSizeCache sizeCache = new StructureSizeCache(level);
        VillageLayout layout = new VillageLayout(terrain, density);
        BlockPos centre = snapToFlat(level, terrain, origin);
        layout.setCenter(centre);

        if (isCapital) {
            VillageLayout capitalLayout = CapitalLayoutPlanner.plan(
                    level, centre, typeData, density, terrain, sizeCache, rng);
            if (capitalLayout != null) {
                return Optional.of(capitalLayout);
            }
            // ORGANIC capital falls through to radial
        }

        VillageShapeProfile profile = typeData.getShapeProfile();

        switch (profile.shapeType()) {
            case LINEAR    -> planLinear   (layout, level, expanded, centre, density, profile, rng, sizeCache);
            case CLUSTERED -> planClustered(layout, level, expanded, centre, density, profile, rng, sizeCache);
            case RIVERINE  -> planRiverine (layout, level, expanded, typeData, centre, density, profile, rng, sizeCache, terrain);
            case HILLTOP   -> planHilltop  (layout, level, expanded, centre, density, profile, rng, sizeCache, terrain);
            case PLAZA     -> planPlaza    (layout, level, expanded, centre, density, profile, rng, sizeCache);
            case COURTYARD -> planCourtyard(layout, level, expanded, centre, density, profile, rng, sizeCache);
            default        -> planRadial   (layout, level, expanded, centre, density, profile, rng, sizeCache);
        }

        if (terrain.hasWater()) {
            placeWaterEdgeBuildings(layout, level, typeData, centre, terrain, density, rng);
        }

        clusterBuildingYLevels(layout, level);
        placeFarmPlots(layout, level, terrain, density, typeData, rng);
        placeDecorationClusters(layout, level, terrain, density, rng);

        System.out.println("VillagePlanner: planned " + profile.shapeType()
                + " village — " + layout);
        return Optional.of(layout);
    }

    // =========================================================================
    // RADIAL
    // =========================================================================

    private static void planRadial(VillageLayout layout, ServerLevel level,
                                   List<VillageTypeData.StarterBuilding> buildings,
                                   BlockPos centre, LayoutDensityProfile density,
                                   VillageShapeProfile profile, Random rng,
                                   StructureSizeCache sizeCache) {
        int r1 = density.getRing1Radius();
        int r2 = density.getRing2Radius();
        int minGap = Math.max(4, density.getBuildingSpacing() / 2);
        List<LayoutSlot> placed = new ArrayList<>();

        // Sort: town hall first, then by zone priority
        List<VillageTypeData.StarterBuilding> sorted = sortBuildings(buildings);

        double angleStep = 360.0 / Math.max(1, sorted.size());
        double currentAngle = 0;

        for (VillageTypeData.StarterBuilding sb : sorted) {
            BuildingType btype = parseBuildingType(sb);
            if (btype == null) continue;

            if (btype == BuildingType.TOWN_HALL) {
                placed.add(placeTownHallSlot(layout, centre, sb, sizeCache));
                continue;
            }

            BuildingZone zone = ZoneRegistry.zoneOf(btype);
            int radius = zone.preferredRing <= 1 ? r1 : r2;
            double angle = currentAngle;
            currentAngle += angleStep;

            LayoutSlot slot = tryPlaceOnRings(level, centre, sb, btype,
                    angle, placed, sizeCache, minGap, r1, r2);

            if (slot != null) {
                layout.tryAdd(slot);
                placed.add(slot);
            } else {
                lastResort(layout, level, centre, sb, btype, placed,
                        sizeCache, minGap, r2, angle, rng);
            }
        }

        placeTownSquare(layout, level, centre, r1);
    }

    // =========================================================================
    // LINEAR
    // =========================================================================

    private static void planLinear(VillageLayout layout, ServerLevel level,
                                   List<VillageTypeData.StarterBuilding> buildings,
                                   BlockPos centre, LayoutDensityProfile density,
                                   VillageShapeProfile profile, Random rng,
                                   StructureSizeCache sizeCache) {
        int[] axis = profile.forcedAxis()
                ? flatDirVector(layout.getTerrain().bestFlatDir())
                : new int[]{1, 0};
        int perpX = axis[1], perpZ = -axis[0];

        List<VillageTypeData.StarterBuilding> sorted = sortBuildings(buildings);
        List<LayoutSlot> placed = new ArrayList<>();
        int spacing = (int)(density.getBuildingSpacing() * profile.streetDensity());
        int halfWidth = spacing / 2 + DEFAULT_BUILDING_RADIUS + 2;

        for (int i = 0; i < sorted.size(); i++) {
            VillageTypeData.StarterBuilding sb = sorted.get(i);
            BuildingType btype = parseBuildingType(sb);
            if (btype == null) continue;

            if (btype == BuildingType.TOWN_HALL) {
                placed.add(placeTownHallSlot(layout, centre, sb, sizeCache));
                continue;
            }

            int pairIdx = (i - 1) / 2; // skip town hall
            int streetDist = spacing * (pairIdx + 1);
            int side = (i % 2 == 0) ? 1 : -1;

            int idealX = centre.getX() + axis[0] * streetDist + perpX * halfWidth * side;
            int idealZ = centre.getZ() + axis[1] * streetDist + perpZ * halfWidth * side;

            LayoutSlot slot = placeWithFootprint(level, layout,
                    new BlockPos(idealX, centre.getY(), idealZ),
                    sb, btype, sizeCache, density, rng, placed);
            if (slot != null) placed.add(slot);
        }

        placeTownSquare(layout, level, centre, spacing);
    }

    // =========================================================================
    // CLUSTERED
    // =========================================================================

    private static void planClustered(VillageLayout layout, ServerLevel level,
                                      List<VillageTypeData.StarterBuilding> buildings,
                                      BlockPos centre, LayoutDensityProfile density,
                                      VillageShapeProfile profile, Random rng,
                                      StructureSizeCache sizeCache) {
        List<VillageTypeData.StarterBuilding> sorted = sortBuildings(buildings);
        List<LayoutSlot> placed = new ArrayList<>();

        // Place town hall at center
        for (var sb : sorted) {
            if (sb.type().equals(BuildingType.TOWN_HALL.name())) {
                placed.add(placeTownHallSlot(layout, centre, sb, sizeCache));
                break;
            }
        }

        // Remove town hall from remaining
        List<VillageTypeData.StarterBuilding> remaining = new ArrayList<>(sorted);
        remaining.removeIf(sb -> sb.type().equals(BuildingType.TOWN_HALL.name()));

        int clusterCount = Math.max(3, Math.min(5, remaining.size() / 3));
        int clusterRadius = density.getRing1Radius() + 8;
        int withinSpacing = (int)(density.getBuildingSpacing() * 0.5f);

        List<List<VillageTypeData.StarterBuilding>> clusters = new ArrayList<>();
        for (int c = 0; c < clusterCount; c++) clusters.add(new ArrayList<>());
        for (int i = 0; i < remaining.size(); i++) {
            clusters.get(i % clusterCount).add(remaining.get(i));
        }

        for (int c = 0; c < clusterCount; c++) {
            double angle = (2 * Math.PI * c / clusterCount)
                    + (rng.nextDouble() - 0.5) * (Math.PI / clusterCount);
            int ax = centre.getX() + (int)(Math.cos(angle) * clusterRadius);
            int az = centre.getZ() + (int)(Math.sin(angle) * clusterRadius);
            BlockPos anchor = solidSurface(level, new BlockPos(ax, centre.getY(), az));

            for (int b = 0; b < clusters.get(c).size(); b++) {
                VillageTypeData.StarterBuilding sb = clusters.get(c).get(b);
                BuildingType btype = parseBuildingType(sb);
                if (btype == null) continue;

                double localAngle = 2 * Math.PI * b / Math.max(1, clusters.get(c).size());
                int localDist = b == 0 ? 0 : withinSpacing;
                int bx = anchor.getX() + (int)(Math.cos(localAngle) * localDist);
                int bz = anchor.getZ() + (int)(Math.sin(localAngle) * localDist);

                LayoutSlot slot = placeWithFootprint(level, layout,
                        new BlockPos(bx, centre.getY(), bz),
                        sb, btype, sizeCache, density, rng, placed);
                if (slot != null) placed.add(slot);
            }
        }

        placeTownSquare(layout, level, centre, clusterRadius / 2);
    }

    // =========================================================================
    // RIVERINE
    // =========================================================================

    private static void planRiverine(VillageLayout layout, ServerLevel level,
                                     List<VillageTypeData.StarterBuilding> buildings,
                                     VillageTypeData typeData,
                                     BlockPos centre, LayoutDensityProfile density,
                                     VillageShapeProfile profile, Random rng,
                                     StructureSizeCache sizeCache,
                                     TerrainProfile terrain) {
        if (!terrain.hasWater()) {
            planRadial(layout, level, buildings, centre, density, profile, rng, sizeCache);
            return;
        }

        TerrainAnalyzer.WaterBodyInfo water = terrain.waterBody();
        BlockPos shore = water.nearestShore();
        int inlandX = Integer.signum(centre.getX() - shore.getX());
        int inlandZ = Integer.signum(centre.getZ() - shore.getZ());
        int shoreX = inlandZ, shoreZ = -inlandX;

        List<VillageTypeData.StarterBuilding> sorted = sortBuildings(buildings);
        List<LayoutSlot> placed = new ArrayList<>();
        int spacing = density.getBuildingSpacing();

        // Town hall inland from shore
        for (var sb : sorted) {
            if (sb.type().equals(BuildingType.TOWN_HALL.name())) {
                BlockPos thPos = solidSurface(level,
                        shore.offset(inlandX * 12, 0, inlandZ * 12));
                placed.add(placeTownHallSlot(layout, thPos, sb, sizeCache));
                break;
            }
        }

        List<VillageTypeData.StarterBuilding> remaining = new ArrayList<>(sorted);
        remaining.removeIf(sb -> sb.type().equals(BuildingType.TOWN_HALL.name()));

        for (int i = 0; i < remaining.size(); i++) {
            VillageTypeData.StarterBuilding sb = remaining.get(i);
            BuildingType btype = parseBuildingType(sb);
            if (btype == null) continue;

            BuildingZone zone = ZoneRegistry.zoneOf(btype);
            int inlandDist = switch (zone) {
                case CIVIC, PRODUCTION -> 8 + spacing / 2;
                case AGRICULTURAL      -> 28 + spacing;
                default                -> 16 + spacing / 2;
            };

            int shoreDist = ((i / 2) + 1) * spacing;
            int side = (i % 2 == 0) ? 1 : -1;

            int bx = shore.getX() + shoreX * shoreDist * side + inlandX * inlandDist;
            int bz = shore.getZ() + shoreZ * shoreDist * side + inlandZ * inlandDist;

            LayoutSlot slot = placeWithFootprint(level, layout,
                    new BlockPos(bx, centre.getY(), bz),
                    sb, btype, sizeCache, density, rng, placed);
            if (slot != null) placed.add(slot);
        }

        BlockPos sqPos = solidSurface(level,
                shore.offset(inlandX * 6, 0, inlandZ * 6));
        layout.addForced(new LayoutSlot(
                LayoutSlot.SlotType.DECORATION, sqPos, TownSquarePlacer.RADIUS + 2));
        layout.setTownSquarePos(sqPos);
    }

    // =========================================================================
    // HILLTOP
    // =========================================================================

    private static void planHilltop(VillageLayout layout, ServerLevel level,
                                    List<VillageTypeData.StarterBuilding> buildings,
                                    BlockPos centre, LayoutDensityProfile density,
                                    VillageShapeProfile profile, Random rng,
                                    StructureSizeCache sizeCache,
                                    TerrainProfile terrain) {
        BlockPos peak = findHighestPoint(level, centre, 48);
        layout.setCenter(peak);

        List<VillageTypeData.StarterBuilding> sorted = sortBuildings(buildings);
        List<LayoutSlot> placed = new ArrayList<>();

        for (var sb : sorted) {
            if (sb.type().equals(BuildingType.TOWN_HALL.name())) {
                placed.add(placeTownHallSlot(layout, peak, sb, sizeCache));
                break;
            }
        }

        List<VillageTypeData.StarterBuilding> remaining = new ArrayList<>(sorted);
        remaining.removeIf(sb -> sb.type().equals(BuildingType.TOWN_HALL.name()));

        // Sort: defensive first
        remaining.sort(Comparator.comparingInt(sb -> {
            try {
                return switch (ZoneRegistry.zoneOf(BuildingType.valueOf(sb.type()))) {
                    case DEFENSIVE -> 0; case CIVIC -> 1;
                    case PRODUCTION -> 2; default -> 3;
                };
            } catch (IllegalArgumentException e) { return 4; }
        }));

        int tightSpacing = (int)(density.getBuildingSpacing() * 0.6f);
        for (int i = 0; i < remaining.size(); i++) {
            VillageTypeData.StarterBuilding sb = remaining.get(i);
            BuildingType btype = parseBuildingType(sb);
            if (btype == null) continue;

            double angle = (2 * Math.PI * i / 5.0) + i * 0.4;
            int radius = tightSpacing + i * (tightSpacing / 3);
            int bx = peak.getX() + (int)(Math.cos(angle) * radius);
            int bz = peak.getZ() + (int)(Math.sin(angle) * radius);

            LayoutSlot slot = placeWithFootprint(level, layout,
                    solidSurface(level, new BlockPos(bx, peak.getY(), bz)),
                    sb, btype, sizeCache, density, rng, placed);
            if (slot != null) placed.add(slot);
        }

        placeTownSquare(layout, level, peak, tightSpacing);
    }

    // =========================================================================
    // PLAZA
    // =========================================================================

    private static void planPlaza(VillageLayout layout, ServerLevel level,
                                  List<VillageTypeData.StarterBuilding> buildings,
                                  BlockPos centre, LayoutDensityProfile density,
                                  VillageShapeProfile profile, Random rng,
                                  StructureSizeCache sizeCache) {
        final int PLAZA_RADIUS = 14;

        List<VillageTypeData.StarterBuilding> sorted = sortBuildings(buildings);
        List<LayoutSlot> placed = new ArrayList<>();

        // Town hall north of plaza
        for (var sb : sorted) {
            if (sb.type().equals(BuildingType.TOWN_HALL.name())) {
                BlockPos thPos = solidSurface(level,
                        new BlockPos(centre.getX(), centre.getY(),
                                centre.getZ() - PLAZA_RADIUS - DEFAULT_BUILDING_RADIUS));
                placed.add(placeTownHallSlot(layout, thPos, sb, sizeCache));
                break;
            }
        }

        // Plaza decoration
        BlockPos plazaPos = solidSurface(level, centre);
        layout.addForced(new LayoutSlot(
                LayoutSlot.SlotType.DECORATION, plazaPos, PLAZA_RADIUS));
        layout.setTownSquarePos(plazaPos);

        List<VillageTypeData.StarterBuilding> remaining = new ArrayList<>(sorted);
        remaining.removeIf(sb -> sb.type().equals(BuildingType.TOWN_HALL.name()));

        int perimeter = PLAZA_RADIUS + DEFAULT_BUILDING_RADIUS + 2;
        int outer = perimeter + density.getRing1Radius();

        for (int i = 0; i < remaining.size(); i++) {
            VillageTypeData.StarterBuilding sb = remaining.get(i);
            BuildingType btype = parseBuildingType(sb);
            if (btype == null) continue;

            BuildingZone zone = ZoneRegistry.zoneOf(btype);
            int r = (zone == BuildingZone.RESIDENTIAL || zone == BuildingZone.AGRICULTURAL)
                    ? outer : perimeter;

            double angle = 2 * Math.PI * i / Math.max(1, remaining.size());
            int idealX = centre.getX() + (int)(Math.cos(angle) * r);
            int idealZ = centre.getZ() + (int)(Math.sin(angle) * r);

            LayoutSlot slot = placeWithFootprint(level, layout,
                    new BlockPos(idealX, centre.getY(), idealZ),
                    sb, btype, sizeCache, density, rng, placed);
            if (slot != null) placed.add(slot);
        }
    }

    // =========================================================================
    // COURTYARD
    // =========================================================================

    private static void planCourtyard(VillageLayout layout, ServerLevel level,
                                      List<VillageTypeData.StarterBuilding> buildings,
                                      BlockPos centre, LayoutDensityProfile density,
                                      VillageShapeProfile profile, Random rng,
                                      StructureSizeCache sizeCache) {
        List<VillageTypeData.StarterBuilding> sorted = sortBuildings(buildings);
        List<LayoutSlot> placed = new ArrayList<>();

        for (var sb : sorted) {
            if (sb.type().equals(BuildingType.TOWN_HALL.name())) {
                placed.add(placeTownHallSlot(layout, centre, sb, sizeCache));
                break;
            }
        }

        List<VillageTypeData.StarterBuilding> remaining = new ArrayList<>(sorted);
        remaining.removeIf(sb -> sb.type().equals(BuildingType.TOWN_HALL.name()));

        List<VillageTypeData.StarterBuilding> inner = new ArrayList<>();
        List<VillageTypeData.StarterBuilding> outer = new ArrayList<>();

        for (var sb : remaining) {
            BuildingZone zone;
            try { zone = ZoneRegistry.zoneOf(BuildingType.valueOf(sb.type())); }
            catch (IllegalArgumentException e) { outer.add(sb); continue; }
            if (zone == BuildingZone.CIVIC || zone == BuildingZone.PRODUCTION) {
                inner.add(sb);
            } else {
                outer.add(sb);
            }
        }

        int innerR = (int)(density.getRing1Radius() * 0.65f);
        int outerR = density.getRing2Radius() + 8;
        int minGap = Math.max(4, density.getBuildingSpacing() / 2);

        placeOnRing(layout, level, inner, centre, innerR,
                sizeCache, minGap, rng, placed);
        placeOnRing(layout, level, outer, centre, outerR,
                sizeCache, minGap, rng, placed);

        placeTownSquare(layout, level, centre, innerR / 2);
    }

    // =========================================================================
    // Shared: place on ring (COURTYARD helper)
    // =========================================================================

    private static void placeOnRing(VillageLayout layout, ServerLevel level,
                                    List<VillageTypeData.StarterBuilding> buildings,
                                    BlockPos centre, int radius,
                                    StructureSizeCache sizeCache, int minGap,
                                    Random rng, List<LayoutSlot> placed) {
        if (buildings.isEmpty()) return;
        double angleStep = 360.0 / buildings.size();
        double angle = rng.nextDouble() * 360;

        for (var sb : buildings) {
            BuildingType btype = parseBuildingType(sb);
            if (btype == null) { angle += angleStep; continue; }

            LayoutSlot slot = resolveSlotOnRing(level, centre, radius,
                    angle, sb.structure(), btype, placed, sizeCache, minGap);
            if (slot != null) {
                layout.tryAdd(slot);
                placed.add(slot);
            }
            angle += angleStep;
        }
    }

    // =========================================================================
    // Shared: place with footprint (used by LINEAR, CLUSTERED, etc.)
    // =========================================================================

    /**
     * Resolves a building position near an ideal point using terrain
     * jitter, then creates a LayoutSlot with actual footprint dimensions.
     * Returns null if no valid position is found.
     */
    private static LayoutSlot placeWithFootprint(
            ServerLevel level, VillageLayout layout, BlockPos ideal,
            VillageTypeData.StarterBuilding sb, BuildingType btype,
            StructureSizeCache sizeCache, LayoutDensityProfile density,
            Random rng, List<LayoutSlot> placed) {

        StructureSizeCache.FootprintInfo info =
                sizeCache.get(sb.structure(), Rotation.NONE);
        int w = info != null ? info.width() : 12;
        int l = info != null ? info.length() : 12;
        int slotRadius = Math.max(w, l) / 2 + 1;

        BlockPos resolved = resolveOnTerrain(level, layout, ideal,
                density.getBuildingJitter(), slotRadius, rng,
                ZoneRegistry.zoneOf(btype) == BuildingZone.AGRICULTURAL);

        if (resolved == null) return null;

        // Check AABB overlap against placed list
        int minGap = VillageLayout.MIN_BUILDING_GAP;
        for (LayoutSlot existing : placed) {
            int eW = existing.getFootprintWidth();
            int eL = existing.getFootprintLength();
            if (eW > 0 && eL > 0) {
                if (VillageLayout.footprintOverlap(
                        resolved, w, l,
                        existing.getPos(), eW, eL, minGap)) {
                    return null; // overlaps
                }
            }
        }

        LayoutSlot slot = new LayoutSlot(resolved, btype, sb.structure(), slotRadius);
        slot.setFootprint(w, l);
        if (layout.tryAdd(slot)) return slot;
        return null;
    }

    // =========================================================================
    // Shared: town hall slot creation
    // =========================================================================

    private static LayoutSlot placeTownHallSlot(VillageLayout layout,
                                                BlockPos pos,
                                                VillageTypeData.StarterBuilding sb,
                                                StructureSizeCache sizeCache) {
        StructureSizeCache.FootprintInfo info =
                sizeCache.get(sb.structure(), Rotation.NONE);
        int w = info != null ? info.width() : 12;
        int l = info != null ? info.length() : 12;
        int slotR = Math.max(w, l) / 2 + 1;

        LayoutSlot slot = new LayoutSlot(pos, BuildingType.TOWN_HALL,
                sb.structure(), slotR);
        slot.setFootprint(w, l);
        layout.tryAdd(slot);
        return slot;
    }

    // =========================================================================
    // Shared: town square placement
    // =========================================================================

    private static void placeTownSquare(VillageLayout layout,
                                        ServerLevel level,
                                        BlockPos centre,
                                        int offset) {
        BlockPos sqPos = centre.offset(0, 0, Math.max(offset / 2, 6));
        int sqSurfY = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                sqPos.getX(), sqPos.getZ());
        sqPos = new BlockPos(sqPos.getX(), sqSurfY, sqPos.getZ());
        layout.addForced(new LayoutSlot(
                LayoutSlot.SlotType.DECORATION, sqPos,
                TownSquarePlacer.RADIUS + 2));
        layout.setTownSquarePos(sqPos);
    }

    // =========================================================================
    // Ring placement with angular retry
    // =========================================================================

    /**
     * Tries to place a building on a ring at the preferred angle,
     * then at 12 angular offsets. Uses AABB overlap against placed list.
     */
    public static LayoutSlot resolveSlotOnRing(
            ServerLevel level, BlockPos centre, int radius,
            double preferredAngle, String structurePath,
            BuildingType buildingType, List<LayoutSlot> existingSlots,
            StructureSizeCache sizeCache, int minGap) {

        StructureSizeCache.FootprintInfo info =
                sizeCache.get(structurePath, Rotation.NONE);
        int w = info != null ? info.width() : 12;
        int l = info != null ? info.length() : 12;
        int slotRadius = Math.max(w, l) / 2 + 1;

        double[] offsets = {0, 30, -30, 60, -60, 90, -90, 120, -120, 150, -150, 180};

        for (double offset : offsets) {
            double angle = Math.toRadians(preferredAngle + offset);
            int x = centre.getX() + (int)(Math.cos(angle) * radius);
            int z = centre.getZ() + (int)(Math.sin(angle) * radius);
            int y = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);

            BlockPos candidate = new BlockPos(x, y, z);

            // AABB overlap check against all placed slots
            boolean overlaps = false;
            for (LayoutSlot existing : existingSlots) {
                int eW = existing.getFootprintWidth();
                int eL = existing.getFootprintLength();
                if (eW > 0 && eL > 0) {
                    if (VillageLayout.footprintOverlap(
                            candidate, w, l,
                            existing.getPos(), eW, eL, minGap)) {
                        overlaps = true;
                        break;
                    }
                } else {
                    // Radius fallback
                    double minDist = slotRadius + existing.getRadius() + minGap;
                    if (candidate.distSqr(existing.getPos()) < minDist * minDist) {
                        overlaps = true;
                        break;
                    }
                }
            }

            if (!overlaps) {
                LayoutSlot slot = new LayoutSlot(candidate, buildingType,
                        structurePath, slotRadius);
                slot.setFootprint(w, l);
                return slot;
            }
        }

        return null;
    }

    // =========================================================================
    // Ring placement helpers for RADIAL
    // =========================================================================

    private static LayoutSlot tryPlaceOnRings(
            ServerLevel level, BlockPos centre,
            VillageTypeData.StarterBuilding sb, BuildingType btype,
            double angle, List<LayoutSlot> placed,
            StructureSizeCache sizeCache, int minGap,
            int r1, int r2) {

        BuildingZone zone = ZoneRegistry.zoneOf(btype);
        int radius = zone.preferredRing <= 1 ? r1 : r2;

        // Try preferred ring
        LayoutSlot slot = resolveSlotOnRing(level, centre, radius, angle,
                sb.structure(), btype, placed, sizeCache, minGap);
        if (slot != null) return slot;

        // Try other ring
        int altRadius = (radius == r1) ? r2 : r1;
        slot = resolveSlotOnRing(level, centre, altRadius, angle,
                sb.structure(), btype, placed, sizeCache, minGap);
        if (slot != null) return slot;

        // Try expansion ring
        int r3 = r2 + (r2 - r1);
        return resolveSlotOnRing(level, centre, r3, angle,
                sb.structure(), btype, placed, sizeCache, minGap);
    }

    private static void lastResort(VillageLayout layout, ServerLevel level,
                                   BlockPos centre,
                                   VillageTypeData.StarterBuilding sb,
                                   BuildingType btype,
                                   List<LayoutSlot> placed,
                                   StructureSizeCache sizeCache,
                                   int minGap, int r2,
                                   double angle, Random rng) {
        for (int extraR = r2 + 20; extraR <= r2 + 200; extraR += 15) {
            LayoutSlot slot = resolveSlotOnRing(level, centre, extraR,
                    angle + rng.nextInt(60), sb.structure(), btype,
                    placed, sizeCache, minGap);
            if (slot != null) {
                layout.addForced(slot);
                placed.add(slot);
                System.out.println("VillagePlanner: forced " + btype
                        + " at r=" + extraR);
                return;
            }
        }
        System.out.println("VillagePlanner: WARN — could not place " + btype);
    }

    // =========================================================================
    // Building list expansion
    // =========================================================================

    /**
     * Expands StarterBuilding entries with min_count/max_count into
     * individual entries. Each instance becomes a separate entry so
     * the planner creates a slot for each.
     */
    public static List<VillageTypeData.StarterBuilding> expandBuildingList(
            List<VillageTypeData.StarterBuilding> starters, Random rng) {
        List<VillageTypeData.StarterBuilding> expanded = new ArrayList<>();
        for (VillageTypeData.StarterBuilding sb : starters) {
            int min = Math.max(1, sb.minCount());
            int max = Math.max(min, sb.maxCount());
            int count = min == max ? min : min + rng.nextInt(max - min + 1);
            for (int i = 0; i < count; i++) {
                expanded.add(sb);
            }
        }
        return expanded;
    }

    // =========================================================================
    // Sorting
    // =========================================================================

    private static List<VillageTypeData.StarterBuilding> sortBuildings(
            List<VillageTypeData.StarterBuilding> buildings) {
        List<VillageTypeData.StarterBuilding> sorted = new ArrayList<>(buildings);
        sorted.sort(Comparator.comparingInt(sb -> {
            try {
                BuildingType bt = BuildingType.valueOf(sb.type());
                if (bt == BuildingType.TOWN_HALL) return -1000;
                ZoneRegistry.ZoneEntry entry = ZoneRegistry.get(bt);
                return entry.zone().ordinal() * 100 + entry.priority();
            } catch (IllegalArgumentException e) { return 999; }
        }));
        return sorted;
    }

    private static BuildingType parseBuildingType(
            VillageTypeData.StarterBuilding sb) {
        try { return BuildingType.valueOf(sb.type()); }
        catch (IllegalArgumentException e) { return null; }
    }

    // =========================================================================
    // Farm plots (FARMHOUSE-gated)
    // =========================================================================

    private static void placeFarmPlots(VillageLayout layout, ServerLevel level,
                                       TerrainProfile terrain,
                                       LayoutDensityProfile density,
                                       VillageTypeData typeData, Random rng) {
        long farmhouseCount = typeData.getStarterBuildings().stream()
                .filter(sb -> sb.type().equals(BuildingType.FARMHOUSE.name()))
                .mapToInt(sb -> Math.max(1, sb.minCount()))
                .sum();
        if (farmhouseCount == 0) return;

        VillageShapeProfile profile = typeData.getShapeProfile();
        if (profile.shapeType() == ShapeType.HILLTOP) return;

        int[] dir = flatDirVector(terrain.bestFlatDir());
        int perpX = dir[1], perpZ = -dir[0];
        int perimeterRadius = density.getRing2Radius() + 12;
        BlockPos centre = layout.getCenter();

        for (int i = 0; i < farmhouseCount; i++) {
            int sideOffset = (i - (int)farmhouseCount / 2)
                    * (FARM_PLOT_RADIUS * 2 + 4);
            int tx = centre.getX()
                    + dir[0] * Math.max(density.getFarmOffset(), perimeterRadius)
                    + perpX * sideOffset;
            int tz = centre.getZ()
                    + dir[1] * Math.max(density.getFarmOffset(), perimeterRadius)
                    + perpZ * sideOffset;

            BlockPos idealPos = terrain.bestFlatNear(
                    tx - terrain.origin().getX(),
                    tz - terrain.origin().getZ());

            BlockPos resolved = resolveOnTerrain(level, layout, idealPos,
                    4, FARM_PLOT_RADIUS, rng, true);
            if (resolved != null) {
                layout.tryAdd(new LayoutSlot(
                        LayoutSlot.SlotType.FARM_PLOT, resolved, FARM_PLOT_RADIUS));
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
        int clusters = density.getDecorationClusters();
        int gapRadius = (density.getRing1Radius() + density.getRing2Radius()) / 2;
        BlockPos centre = layout.getCenter();

        for (int i = 0; i < clusters; i++) {
            double angle = (2 * Math.PI * i / clusters) + rng.nextDouble() * 0.8;
            int dx = (int)(Math.cos(angle) * gapRadius);
            int dz = (int)(Math.sin(angle) * gapRadius);

            BlockPos ideal = terrain.bestFlatNear(dx, dz);
            BlockPos resolved = resolveOnTerrain(level, layout, ideal,
                    6, DECORATION_RADIUS, rng);
            if (resolved != null) {
                layout.addForced(new LayoutSlot(
                        LayoutSlot.SlotType.DECORATION, resolved, DECORATION_RADIUS));
            }
        }
    }

    // =========================================================================
    // Water-edge buildings
    // =========================================================================

    private static void placeWaterEdgeBuildings(VillageLayout layout,
                                                ServerLevel level,
                                                VillageTypeData typeData,
                                                BlockPos centre,
                                                TerrainProfile terrain,
                                                LayoutDensityProfile density,
                                                Random rng) {
        TerrainAnalyzer.WaterBodyInfo water = terrain.waterBody();
        if (water == null) return;
        BlockPos shore = water.nearestShore();

        BlockPos docksIdeal = shore.offset(
                Integer.signum(centre.getX() - shore.getX()) * 3, 0,
                Integer.signum(centre.getZ() - shore.getZ()) * 3);
        BlockPos docksPos = resolveOnTerrain(level, layout, docksIdeal,
                3, DEFAULT_BUILDING_RADIUS, rng);
        if (docksPos != null) {
            String path = findBuildingPath(typeData, BuildingType.DOCKS, "docks/level_1");
            layout.tryAdd(new LayoutSlot(docksPos, BuildingType.DOCKS,
                    path, DEFAULT_BUILDING_RADIUS));
        }

        int inlandDist = 8 + rng.nextInt(6);
        BlockPos millIdeal = shore.offset(
                Integer.signum(centre.getX() - shore.getX()) * inlandDist, 0,
                Integer.signum(centre.getZ() - shore.getZ()) * inlandDist);
        BlockPos millPos = resolveOnTerrain(level, layout, millIdeal,
                4, DEFAULT_BUILDING_RADIUS, rng);
        if (millPos != null) {
            String path = findBuildingPath(typeData, BuildingType.MILLER, "miller/level_1");
            layout.tryAdd(new LayoutSlot(millPos, BuildingType.MILLER,
                    path, DEFAULT_BUILDING_RADIUS));
        }
    }

    // =========================================================================
    // Y clustering
    // =========================================================================

    private static void clusterBuildingYLevels(VillageLayout layout,
                                               ServerLevel level) {
        List<LayoutSlot> buildings = layout.buildings();
        for (LayoutSlot slot : buildings) {
            List<Integer> nearbyY = new ArrayList<>();
            for (LayoutSlot other : buildings) {
                if (other == slot) continue;
                int dx = Math.abs(slot.getPos().getX() - other.getPos().getX());
                int dz = Math.abs(slot.getPos().getZ() - other.getPos().getZ());
                if (Math.max(dx, dz) <= layout.getDensity().getRing2Radius()) {
                    nearbyY.add(other.getPos().getY());
                }
            }
            if (nearbyY.isEmpty()) continue;
            Collections.sort(nearbyY);
            int medianY = nearbyY.get(nearbyY.size() / 2);
            int diff = Math.abs(slot.getPos().getY() - medianY);
            if (diff > 0 && diff <= 6) {
                int actual = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        slot.getPos().getX(), slot.getPos().getZ());
                if (Math.abs(actual - medianY) <= 4) slot.snapY(medianY);
            }
        }
    }

    // =========================================================================
    // Terrain resolution
    // =========================================================================

    private static BlockPos resolveOnTerrain(ServerLevel level, VillageLayout layout,
                                             BlockPos ideal, int jitterRange,
                                             int radius, Random rng) {
        return resolveOnTerrain(level, layout, ideal, jitterRange, radius, rng, false);
    }

    private static BlockPos resolveOnTerrain(ServerLevel level, VillageLayout layout,
                                             BlockPos ideal, int jitterRange,
                                             int radius, Random rng,
                                             boolean rejectWater) {
        TerrainProfile terrain = layout.getTerrain();

        BlockPos candidate = solidSurface(level, ideal);
        if (isValidTerrain(terrain, candidate, radius, rejectWater, level)) {
            return candidate;
        }

        for (int a = 0; a < MAX_ATTEMPTS / 2; a++) {
            int jx = rng.nextInt(jitterRange * 2 + 1) - jitterRange;
            int jz = rng.nextInt(jitterRange * 2 + 1) - jitterRange;
            candidate = solidSurface(level, ideal.offset(jx, 0, jz));
            if (isValidTerrain(terrain, candidate, radius, rejectWater, level)) {
                return candidate;
            }
        }

        BlockPos nearest = terrain.bestFlatNear(
                ideal.getX() - terrain.origin().getX(),
                ideal.getZ() - terrain.origin().getZ());
        candidate = solidSurface(level, nearest);
        if (isValidTerrain(terrain, candidate, radius, rejectWater, level)) {
            return candidate;
        }

        int step = Math.max(4, DEFAULT_BUILDING_RADIUS + 2);
        for (int ring = 1; ring <= MAX_ATTEMPTS / 2; ring++) {
            for (int[] off : new int[][]{
                    {ring, 0}, {-ring, 0}, {0, ring}, {0, -ring},
                    {ring, ring}, {-ring, ring}, {ring, -ring}, {-ring, -ring}}) {
                candidate = solidSurface(level, ideal.offset(off[0] * step, 0, off[1] * step));
                if (isValidTerrain(terrain, candidate, radius, rejectWater, level)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean isValidTerrain(TerrainProfile terrain, BlockPos pos,
                                          int radius, boolean rejectWater,
                                          ServerLevel level) {
        return !terrain.isOnRidge(pos, radius)
                && !(rejectWater && isWaterAdjacent(level, pos));
    }

    // =========================================================================
    // Utility helpers
    // =========================================================================

    private static boolean isWaterAdjacent(ServerLevel level, BlockPos pos) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (level.isWaterAt(pos.offset(dx, 0, dz))) return true;
                if (level.isWaterAt(pos.offset(dx, -1, dz))) return true;
            }
        }
        return false;
    }

    private static BlockPos solidSurface(ServerLevel level, BlockPos pos) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getX(), pos.getZ());
        return new BlockPos(pos.getX(), y, pos.getZ());
    }

    private static BlockPos snapToFlat(ServerLevel level, TerrainProfile terrain,
                                       BlockPos origin) {
        return terrain.flatCandidates().stream()
                .filter(p -> p.distSqr(origin) <= 16 * 16)
                .min(Comparator.comparingDouble(p -> p.distSqr(origin)))
                .orElseGet(() -> solidSurface(level, origin));
    }

    private static BlockPos findHighestPoint(ServerLevel level, BlockPos centre,
                                             int searchRadius) {
        BlockPos best = solidSurface(level, centre);
        int bestY = best.getY();
        for (int dx = -searchRadius; dx <= searchRadius; dx += 4) {
            for (int dz = -searchRadius; dz <= searchRadius; dz += 4) {
                BlockPos c = solidSurface(level, centre.offset(dx, 0, dz));
                if (c.getY() > bestY) { bestY = c.getY(); best = c; }
            }
        }
        return best;
    }

    private static int[] flatDirVector(TerrainAnalyzer.FlatDirection dir) {
        return switch (dir) {
            case NORTH -> new int[]{0, -1};
            case SOUTH -> new int[]{0, 1};
            case EAST  -> new int[]{1, 0};
            case WEST  -> new int[]{-1, 0};
        };
    }

    private static String findBuildingPath(VillageTypeData typeData,
                                           BuildingType type, String fallback) {
        return typeData.getStarterBuildings().stream()
                .filter(sb -> sb.type().equals(type.name()))
                .map(VillageTypeData.StarterBuilding::structure)
                .findFirst().orElse(fallback);
    }
}