// src/main/java/tterrag1112/life_in_the_village/Village/Planning/VillagePlanner.java
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
 * Produces a terrain-aware {@link VillageLayout} plan before any blocks are placed.
 *
 * <h3>Shape dispatch</h3>
 * The {@link VillageShapeProfile} from the village type JSON drives which
 * layout algorithm runs:
 * <ul>
 *   <li>{@link ShapeType#RADIAL}    — classic ring placement (original behaviour)</li>
 *   <li>{@link ShapeType#LINEAR}    — buildings flanking a main street axis</li>
 *   <li>{@link ShapeType#CLUSTERED} — loose organic micro-clusters</li>
 *   <li>{@link ShapeType#RIVERINE}  — waterfront-aligned (falls back to RADIAL)</li>
 *   <li>{@link ShapeType#HILLTOP}   — spiral descent from a high-point town hall</li>
 *   <li>{@link ShapeType#PLAZA}     — buildings arranged around a large central square</li>
 *   <li>{@link ShapeType#COURTYARD} — tight inner ward + looser outer ring</li>
 * </ul>
 *
 * <h3>Farm plot gating</h3>
 * Farm plots are only placed when the village type contains at least one
 * FARMHOUSE in its starter buildings. The plot count equals the number of
 * FARMHOUSE entries — one plot per farmhouse — rather than a fixed fraction
 * of village level.
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

        TerrainProfile terrain = TerrainAnalyzer.analyze(level, origin);
        if (!terrain.isSuitable()) {
            System.out.println("VillagePlanner: unsuitable terrain at "
                    + origin + " (score="
                    + String.format("%.2f", terrain.suitability()) + ")");
            return Optional.empty();
        }

        int buildingCount = typeData.getStarterBuildings().size();
// forCapital needs the *expanded* count (after resolving min/maxCount),
// not the count of unique building entries. Use sum of minCounts.
        int expandedBuildingCount = typeData.getStarterBuildings().stream()
                .mapToInt(VillageTypeData.StarterBuilding::minCount)
                .sum();
        buildingCount = Math.max(buildingCount, expandedBuildingCount);
        boolean isCapital = buildingCount >= LayoutDensityProfile.CAPITAL_THRESHOLD;

        LayoutDensityProfile density = isCapital
                ? LayoutDensityProfile.forCapital(buildingCount)
                : LayoutDensityProfile.forLevel(villageLevel);

        StructureSizeCache sizeCache = new StructureSizeCache(level);
        VillageLayout layout = new VillageLayout(terrain, density);
        BlockPos centre = snapToFlat(level, terrain, origin);
        layout.setCenter(centre);

        if (isCapital) {
            // ORGANIC layout type falls through to the ring planner below —
            // CapitalLayoutPlanner.plan() returns null as the signal.
            VillageLayout capitalLayout = CapitalLayoutPlanner.plan(
                    level, centre, typeData, density, terrain, sizeCache, rng);
            if (capitalLayout != null) {
                System.out.println("VillagePlanner: planned CAPITAL village — "
                        + capitalLayout);
                return Optional.of(capitalLayout);
            }
            System.out.println("VillagePlanner: ORGANIC capital — "
                    + "falling back to ring planner");
            // Fall through to normal shape dispatch below
        }

        VillageShapeProfile profile = typeData.getShapeProfile();

        // Dispatch to shape-specific layout algorithm
        switch (profile.shapeType()) {
            case LINEAR    -> planLinear   (layout, level, typeData, centre, density, profile, rng, sizeCache);
            case CLUSTERED -> planClustered(layout, level, typeData, centre, density, profile, rng, sizeCache);
            case RIVERINE  -> planRiverine (layout, level, typeData, centre, density, profile, rng, sizeCache, terrain);
            case HILLTOP   -> planHilltop  (layout, level, typeData, centre, density, profile, rng, sizeCache, terrain);
            case PLAZA     -> planPlaza    (layout, level, typeData, centre, density, profile, rng, sizeCache);
            case COURTYARD -> planCourtyard(layout, level, typeData, centre, density, profile, rng, sizeCache);
            default        -> planRadial   (layout, level, typeData, centre, density, profile, rng, sizeCache);
        }

        // Water-edge buildings (docks, mill) — all shapes benefit from these
        if (terrain.hasWater()) {
            placeWaterEdgeBuildings(layout, level, typeData,
                    centre, terrain, density, rng);
        }

        clusterBuildingYLevels(layout, level);
        placeFarmPlots(layout, level, terrain, density, typeData, rng);
        placeDecorationClusters(layout, level, terrain, density, rng);

        System.out.println("VillagePlanner: planned " + profile.shapeType()
                + " village — " + layout);
        return Optional.of(layout);
    }

    // =========================================================================
    // RADIAL — classic ring layout (original behaviour, polished)
    // =========================================================================

    /**
     * Town hall at the centre, buildings placed on 1–3 concentric rings.
     * Zone sectors control which buildings land where (civic south,
     * production east, defensive outermost).
     *
     * <p>This is the default and most common shape. It produces a compact,
     * balanced village that reads clearly from above.</p>
     */
    private static void planRadial(VillageLayout layout, ServerLevel level,
                                   VillageTypeData typeData, BlockPos centre,
                                   LayoutDensityProfile density,
                                   VillageShapeProfile profile,
                                   Random rng, StructureSizeCache sizeCache) {
        placeTownHall(layout, level, typeData, centre, rng, sizeCache);
        placeTownSquare(layout, level, centre, density, rng);
        placeRingBuildings(layout, level, typeData, centre, density, rng, sizeCache);
    }

    // =========================================================================
    // LINEAR — road village along a main street axis
    // =========================================================================

    /**
     * Buildings are placed in facing pairs flanking a central street.
     *
     * <pre>
     *  [CIVIC] [PROD] [RESI] [RESI] [RESI]
     *      ───────── MAIN STREET ─────────
     *  [CIVIC] [PROD] [RESI] [RESI] [AGRI]
     * </pre>
     *
     * <p>The street axis follows the terrain's flattest direction when
     * {@code profile.forcedAxis()} is true, otherwise defaults to east–west.
     * The town hall anchors the civic end; farm plots extend from the
     * agricultural end. This produces a classic road village or market town.</p>
     */
    private static void planLinear(VillageLayout layout, ServerLevel level,
                                   VillageTypeData typeData, BlockPos centre,
                                   LayoutDensityProfile density,
                                   VillageShapeProfile profile,
                                   Random rng, StructureSizeCache sizeCache) {
        // Determine street axis direction
        int[] axis = profile.forcedAxis()
                ? flatDirVector(layout.getTerrain().bestFlatDir())
                : new int[]{1, 0}; // default east–west
        // Perpendicular offset (buildings sit to the side of the street)
        int perpX =  axis[1];
        int perpZ = -axis[0];

        // ── Town hall: civic (western) end ───────────────────────────────────
        placeTownHall(layout, level, typeData, centre, rng, sizeCache);

        // ── Sort remaining buildings: civic first, agricultural last ─────────
        List<VillageTypeData.StarterBuilding> remaining = new ArrayList<>(
                typeData.getStarterBuildings());
        remaining.removeIf(sb -> sb.type().equals(BuildingType.TOWN_HALL.name()));

        remaining.sort(Comparator.comparingInt(sb -> {
            try {
                BuildingType bt = BuildingType.valueOf(sb.type());
                ZoneRegistry.ZoneEntry ze = ZoneRegistry.get(bt);
                return ze.zone().preferredRing * 100 + ze.priority();
            } catch (IllegalArgumentException e) { return 999; }
        }));

        // ── Place buildings along the street in pairs ─────────────────────
        int spacing   = (int)(density.getBuildingSpacing() * profile.streetDensity());
        int halfWidth = spacing / 2 + DEFAULT_BUILDING_RADIUS + 2;

        for (int i = 0; i < remaining.size(); i++) {
            VillageTypeData.StarterBuilding sb = remaining.get(i);
            BuildingType btype;
            try { btype = BuildingType.valueOf(sb.type()); }
            catch (IllegalArgumentException e) { continue; }

            // Advance along the street per building pair
            int pairIdx    = i / 2;
            int streetDist = spacing * (pairIdx + 1);
            // Alternate sides
            int side = (i % 2 == 0) ? 1 : -1;

            int idealX = centre.getX()
                    + axis[0] * streetDist
                    + perpX   * halfWidth * side;
            int idealZ = centre.getZ()
                    + axis[1] * streetDist
                    + perpZ   * halfWidth * side;

            BlockPos placed = resolveOnTerrain(level, layout,
                    new BlockPos(idealX, centre.getY(), idealZ),
                    density.getBuildingJitter(), DEFAULT_BUILDING_RADIUS, rng,
                    ZoneRegistry.zoneOf(btype) == BuildingZone.AGRICULTURAL);

            if (placed != null) {
                StructureSizeCache.FootprintInfo info =
                        sizeCache.get(sb.structure(), Rotation.NONE);
                LayoutSlot slot = new LayoutSlot(
                        placed, btype, sb.structure(),
                        StructureSizeCache.DEFAULT_RADIUS);
                slot.setFootprint(info.width(), info.length());
                layout.tryAdd(slot);
            }
        }

        // ── Town square midway along the street, one side ────────────────
        int sqDist = spacing;
        BlockPos sqIdeal = new BlockPos(
                centre.getX() + axis[0] * sqDist,
                centre.getY(),
                centre.getZ() + axis[1] * sqDist);
        BlockPos sqPos = resolveOnTerrain(level, layout, sqIdeal,
                4, TownSquarePlacer.RADIUS + 2, rng);
        if (sqPos == null) sqPos = solidSurface(level, sqIdeal);
        layout.addForced(new LayoutSlot(
                LayoutSlot.SlotType.DECORATION, sqPos, TownSquarePlacer.RADIUS + 2));
        layout.setTownSquarePos(sqPos);
    }

    // =========================================================================
    // CLUSTERED — organic hamlet of micro-clusters
    // =========================================================================

    /**
     * Buildings are grouped into 3–4 tight micro-clusters radiating from
     * the town hall. Each cluster has 2–4 buildings with very small gaps.
     * Clusters themselves are spaced apart, leaving deliberate open ground
     * between them.
     *
     * <p>This produces a settlement that looks like it grew incrementally —
     * a family farm here, a smithy and its outbuildings there — rather than
     * being planned all at once.</p>
     */
    private static void planClustered(VillageLayout layout, ServerLevel level,
                                      VillageTypeData typeData, BlockPos centre,
                                      LayoutDensityProfile density,
                                      VillageShapeProfile profile,
                                      Random rng, StructureSizeCache sizeCache) {
        placeTownHall(layout, level, typeData, centre, rng, sizeCache);

        List<VillageTypeData.StarterBuilding> remaining = new ArrayList<>(
                typeData.getStarterBuildings());
        remaining.removeIf(sb -> sb.type().equals(BuildingType.TOWN_HALL.name()));

        int clusterCount = Math.max(3, Math.min(5, remaining.size() / 3));
        int clusterRadius = density.getRing1Radius() + 8; // clusters orbit this far out
        int withinClusterSpacing = (int)(density.getBuildingSpacing() * 0.5f); // tight

        // Distribute buildings into clusters
        List<List<VillageTypeData.StarterBuilding>> clusters = new ArrayList<>();
        for (int c = 0; c < clusterCount; c++) clusters.add(new ArrayList<>());
        for (int i = 0; i < remaining.size(); i++) {
            clusters.get(i % clusterCount).add(remaining.get(i));
        }

        for (int c = 0; c < clusterCount; c++) {
            // Each cluster has an anchor position on a loose orbit around centre
            double angle     = (2 * Math.PI * c / clusterCount)
                    + (rng.nextDouble() - 0.5) * (Math.PI / clusterCount);
            int anchorX = centre.getX() + (int)(Math.cos(angle) * clusterRadius);
            int anchorZ = centre.getZ() + (int)(Math.sin(angle) * clusterRadius);
            BlockPos anchor = solidSurface(level, new BlockPos(anchorX, centre.getY(), anchorZ));

            List<VillageTypeData.StarterBuilding> clusterBuildings = clusters.get(c);
            for (int b = 0; b < clusterBuildings.size(); b++) {
                VillageTypeData.StarterBuilding sb = clusterBuildings.get(b);
                BuildingType btype;
                try { btype = BuildingType.valueOf(sb.type()); }
                catch (IllegalArgumentException e) { continue; }

                // Spread buildings tightly around the cluster anchor
                double localAngle = 2 * Math.PI * b / Math.max(1, clusterBuildings.size());
                int localDist     = b == 0 ? 0 : withinClusterSpacing;
                int bx = anchor.getX() + (int)(Math.cos(localAngle) * localDist);
                int bz = anchor.getZ() + (int)(Math.sin(localAngle) * localDist);

                BlockPos placed = resolveOnTerrain(level, layout,
                        new BlockPos(bx, centre.getY(), bz),
                        density.getBuildingJitter() / 2, // less jitter — tight clusters
                        DEFAULT_BUILDING_RADIUS, rng);

                if (placed != null) {
                    StructureSizeCache.FootprintInfo info =
                            sizeCache.get(sb.structure(), Rotation.NONE);
                    LayoutSlot slot = new LayoutSlot(
                            placed, btype, sb.structure(),
                            StructureSizeCache.DEFAULT_RADIUS);
                    slot.setFootprint(info.width(), info.length());
                    layout.tryAdd(slot);
                }
            }
        }

        // Town square near the largest cluster (cluster 0)
        if (!clusters.isEmpty() && !clusters.get(0).isEmpty()) {
            double angle0 = 0;
            BlockPos sqIdeal = new BlockPos(
                    centre.getX() + (int)(Math.cos(angle0) * (clusterRadius / 2)),
                    centre.getY(),
                    centre.getZ() + (int)(Math.sin(angle0) * (clusterRadius / 2)));
            BlockPos sqPos = resolveOnTerrain(level, layout, sqIdeal,
                    6, TownSquarePlacer.RADIUS + 2, rng);
            if (sqPos == null) sqPos = solidSurface(level, sqIdeal);
            layout.addForced(new LayoutSlot(
                    LayoutSlot.SlotType.DECORATION, sqPos, TownSquarePlacer.RADIUS + 2));
            layout.setTownSquarePos(sqPos);
        }
    }

    // =========================================================================
    // RIVERINE — waterfront village
    // =========================================================================

    /**
     * Buildings face the water, arranged in a strip parallel to the shore.
     * The town hall sits at the shore's midpoint but set back slightly inland.
     * Civic and market buildings cluster near the docks; residential fills
     * the inland side; farm plots go furthest inland.
     *
     * <p>Falls back to RADIAL if no water body is detected by the terrain
     * analyser.</p>
     */
    private static void planRiverine(VillageLayout layout, ServerLevel level,
                                     VillageTypeData typeData, BlockPos centre,
                                     LayoutDensityProfile density,
                                     VillageShapeProfile profile,
                                     Random rng, StructureSizeCache sizeCache,
                                     TerrainProfile terrain) {
        if (!terrain.hasWater()) {
            // No water — silently fall back to radial
            planRadial(layout, level, typeData, centre, density, profile, rng, sizeCache);
            return;
        }

        TerrainAnalyzer.WaterBodyInfo water = terrain.waterBody();
        BlockPos shore = water.nearestShore();

        // Inland direction = vector from shore toward centre
        int inlandX = Integer.signum(centre.getX() - shore.getX());
        int inlandZ = Integer.signum(centre.getZ() - shore.getZ());
        // Shore-parallel direction = perpendicular to inland
        int shoreX  =  inlandZ;
        int shoreZ  = -inlandX;

        // ── Town hall: inland from shore midpoint ─────────────────────────
        BlockPos thIdeal = shore.offset(inlandX * 12, 0, inlandZ * 12);
        thIdeal = solidSurface(level, thIdeal);
        placeTownHallAt(layout, level, typeData, thIdeal, rng, sizeCache);

        // ── Sort buildings by zone priority ───────────────────────────────
        List<VillageTypeData.StarterBuilding> remaining = new ArrayList<>(
                typeData.getStarterBuildings());
        remaining.removeIf(sb -> sb.type().equals(BuildingType.TOWN_HALL.name()));
        remaining.sort(Comparator.comparingInt(sb -> {
            try {
                BuildingType bt = BuildingType.valueOf(sb.type());
                return ZoneRegistry.get(bt).zone().preferredRing * 100
                        + ZoneRegistry.get(bt).priority();
            } catch (IllegalArgumentException e) { return 999; }
        }));

        // ── Place buildings: civic/market near shore, residential inland ──
        int spacing = density.getBuildingSpacing();
        for (int i = 0; i < remaining.size(); i++) {
            VillageTypeData.StarterBuilding sb = remaining.get(i);
            BuildingType btype;
            try { btype = BuildingType.valueOf(sb.type()); }
            catch (IllegalArgumentException e) { continue; }

            BuildingZone zone = ZoneRegistry.zoneOf(btype);
            // Civic/production: close to shore; residential/agricultural: inland
            int inlandDist = switch (zone) {
                case CIVIC, PRODUCTION -> 8 + spacing / 2;
                case AGRICULTURAL      -> 28 + spacing;
                default                -> 16 + spacing / 2;
            };

            // Alternate shore-parallel positions
            int shoreDist = ((i / 2) + 1) * spacing;
            int side      = (i % 2 == 0) ? 1 : -1;

            int bx = shore.getX() + shoreX * shoreDist * side + inlandX * inlandDist;
            int bz = shore.getZ() + shoreZ * shoreDist * side + inlandZ * inlandDist;

            BlockPos placed = resolveOnTerrain(level, layout,
                    new BlockPos(bx, centre.getY(), bz),
                    density.getBuildingJitter(), DEFAULT_BUILDING_RADIUS, rng,
                    zone == BuildingZone.AGRICULTURAL); // farm slots avoid water

            if (placed != null) {
                StructureSizeCache.FootprintInfo info =
                        sizeCache.get(sb.structure(), Rotation.NONE);
                LayoutSlot slot = new LayoutSlot(
                        placed, btype, sb.structure(), StructureSizeCache.DEFAULT_RADIUS);
                slot.setFootprint(info.width(), info.length());
                layout.tryAdd(slot);
            }
        }

        // Town square on the shore just inland from the docks zone
        BlockPos sqIdeal = shore.offset(inlandX * 6, 0, inlandZ * 6);
        sqIdeal = solidSurface(level, sqIdeal);
        BlockPos sqPos = resolveOnTerrain(level, layout, sqIdeal,
                4, TownSquarePlacer.RADIUS + 2, rng);
        if (sqPos == null) sqPos = solidSurface(level, sqIdeal);
        layout.addForced(new LayoutSlot(
                LayoutSlot.SlotType.DECORATION, sqPos, TownSquarePlacer.RADIUS + 2));
        layout.setTownSquarePos(sqPos);
    }

    // =========================================================================
    // HILLTOP — defensive settlement at elevation
    // =========================================================================

    /**
     * The town hall is placed at the highest terrain point within a search
     * radius. Buildings are placed in a descending spiral away from the peak,
     * each ring roughly 4–6 blocks lower than the previous.
     *
     * <p>Spacing is tighter than RADIAL to simulate cliff-hugging streets.
     * No farm plots — hilltop villages import food rather than growing it
     * (handled by the farm plot gating check in {@link #placeFarmPlots}).
     * Always walled.</p>
     */
    private static void planHilltop(VillageLayout layout, ServerLevel level,
                                    VillageTypeData typeData, BlockPos centre,
                                    LayoutDensityProfile density,
                                    VillageShapeProfile profile,
                                    Random rng, StructureSizeCache sizeCache,
                                    TerrainProfile terrain) {
        // Find highest surface candidate within 48 blocks
        BlockPos peak = findHighestPoint(level, centre, 48);

        placeTownHallAt(layout, level, typeData, peak, rng, sizeCache);
        layout.setCenter(peak); // recentre layout on peak

        List<VillageTypeData.StarterBuilding> remaining = new ArrayList<>(
                typeData.getStarterBuildings());
        remaining.removeIf(sb -> sb.type().equals(BuildingType.TOWN_HALL.name()));

        // Defensive buildings in the innermost ring; others spiral out
        remaining.sort(Comparator.comparingInt(sb -> {
            try {
                BuildingZone z = ZoneRegistry.zoneOf(BuildingType.valueOf(sb.type()));
                return switch (z) {
                    case DEFENSIVE   -> 0;
                    case CIVIC       -> 1;
                    case PRODUCTION  -> 2;
                    default          -> 3;
                };
            } catch (IllegalArgumentException e) { return 4; }
        }));

        // Spiral descent: angle increases, radius increases, Y decreases
        int tightSpacing = (int)(density.getBuildingSpacing() * 0.6f);
        for (int i = 0; i < remaining.size(); i++) {
            VillageTypeData.StarterBuilding sb = remaining.get(i);
            BuildingType btype;
            try { btype = BuildingType.valueOf(sb.type()); }
            catch (IllegalArgumentException e) { continue; }

            double angle  = (2 * Math.PI * i / 5.0) + i * 0.4;  // golden-angle spiral
            int    radius = tightSpacing + i * (tightSpacing / 3);
            int    bx     = peak.getX() + (int)(Math.cos(angle) * radius);
            int    bz     = peak.getZ() + (int)(Math.sin(angle) * radius);

            BlockPos ideal  = solidSurface(level, new BlockPos(bx, peak.getY(), bz));
            BlockPos placed = resolveOnTerrain(level, layout, ideal,
                    density.getBuildingJitter() / 2, DEFAULT_BUILDING_RADIUS, rng);

            if (placed != null) {
                StructureSizeCache.FootprintInfo info =
                        sizeCache.get(sb.structure(), Rotation.NONE);
                LayoutSlot slot = new LayoutSlot(
                        placed, btype, sb.structure(), StructureSizeCache.DEFAULT_RADIUS);
                slot.setFootprint(info.width(), info.length());
                layout.tryAdd(slot);
            }
        }

        // Town square just below the peak
        BlockPos sqIdeal = solidSurface(level,
                new BlockPos(peak.getX() + tightSpacing, peak.getY(), peak.getZ()));
        BlockPos sqPos = resolveOnTerrain(level, layout, sqIdeal,
                4, TownSquarePlacer.RADIUS + 2, rng);
        if (sqPos == null) sqPos = solidSurface(level, sqIdeal);
        layout.addForced(new LayoutSlot(
                LayoutSlot.SlotType.DECORATION, sqPos, TownSquarePlacer.RADIUS + 2));
        layout.setTownSquarePos(sqPos);
    }

    // =========================================================================
    // PLAZA — planned town around a central square
    // =========================================================================

    /**
     * A large central square dominates the layout. The town hall anchors the
     * north side. Civic and production buildings fill the square's perimeter.
     * Residential fills the outer ring. The square itself is much larger than
     * the default town square (radius 14 instead of the usual 8).
     *
     * <p>This produces a very organised, planned-looking settlement — a
     * market town, a noble's seat, or a trade hub.</p>
     */
    private static void planPlaza(VillageLayout layout, ServerLevel level,
                                  VillageTypeData typeData, BlockPos centre,
                                  LayoutDensityProfile density,
                                  VillageShapeProfile profile,
                                  Random rng, StructureSizeCache sizeCache) {
        final int PLAZA_RADIUS = 14;

        // ── Town hall: north side of plaza ───────────────────────────────
        BlockPos thPos = solidSurface(level,
                new BlockPos(centre.getX(), centre.getY(),
                        centre.getZ() - PLAZA_RADIUS - DEFAULT_BUILDING_RADIUS));
        placeTownHallAt(layout, level, typeData, thPos, rng, sizeCache);

        // ── Plaza as an oversized decoration slot ─────────────────────────
        BlockPos plazaPos = solidSurface(level, centre);
        layout.addForced(new LayoutSlot(
                LayoutSlot.SlotType.DECORATION, plazaPos, PLAZA_RADIUS));
        layout.setTownSquarePos(plazaPos);

        // ── Remaining buildings: perimeter of plaza, then outer ring ──────
        List<VillageTypeData.StarterBuilding> remaining = new ArrayList<>(
                typeData.getStarterBuildings());
        remaining.removeIf(sb -> sb.type().equals(BuildingType.TOWN_HALL.name()));

        remaining.sort(Comparator.comparingInt(sb -> {
            try {
                BuildingType bt = BuildingType.valueOf(sb.type());
                return ZoneRegistry.get(bt).zone().preferredRing * 100
                        + ZoneRegistry.get(bt).priority();
            } catch (IllegalArgumentException e) { return 999; }
        }));

        int perimeter = PLAZA_RADIUS + DEFAULT_BUILDING_RADIUS + 2;
        int outer     = perimeter + density.getRing1Radius();

        for (int i = 0; i < remaining.size(); i++) {
            VillageTypeData.StarterBuilding sb = remaining.get(i);
            BuildingType btype;
            try { btype = BuildingType.valueOf(sb.type()); }
            catch (IllegalArgumentException e) { continue; }

            BuildingZone zone = ZoneRegistry.zoneOf(btype);
            // Civic/production around plaza; residential on outer ring
            int r = (zone == BuildingZone.RESIDENTIAL || zone == BuildingZone.AGRICULTURAL)
                    ? outer : perimeter;

            double angle  = 2 * Math.PI * i / Math.max(1, remaining.size());
            int    idealX = centre.getX() + (int)(Math.cos(angle) * r);
            int    idealZ = centre.getZ() + (int)(Math.sin(angle) * r);

            BlockPos placed = resolveOnTerrain(level, layout,
                    new BlockPos(idealX, centre.getY(), idealZ),
                    density.getBuildingJitter(), DEFAULT_BUILDING_RADIUS, rng,
                    zone == BuildingZone.AGRICULTURAL);

            if (placed != null) {
                StructureSizeCache.FootprintInfo info =
                        sizeCache.get(sb.structure(), Rotation.NONE);
                LayoutSlot slot = new LayoutSlot(
                        placed, btype, sb.structure(), StructureSizeCache.DEFAULT_RADIUS);
                slot.setFootprint(info.width(), info.length());
                layout.tryAdd(slot);
            }
        }
    }

    // =========================================================================
    // COURTYARD — fortified inner ward + outer residential ring
    // =========================================================================

    /**
     * An inner ring of civic and production buildings forms a tight,
     * enclosed ward around the town hall. A looser outer ring holds
     * residential, agricultural, and defensive buildings.
     *
     * <p>The inner ring spacing is ~60% of normal. The outer ring uses
     * a larger radius than RADIAL ring2. This gives the settlement a
     * distinctive enclosed core that reads visually like a castle bailey
     * or a fortified manor courtyard.</p>
     */
    private static void planCourtyard(VillageLayout layout, ServerLevel level,
                                      VillageTypeData typeData, BlockPos centre,
                                      LayoutDensityProfile density,
                                      VillageShapeProfile profile,
                                      Random rng, StructureSizeCache sizeCache) {
        placeTownHall(layout, level, typeData, centre, rng, sizeCache);

        List<VillageTypeData.StarterBuilding> remaining = new ArrayList<>(
                typeData.getStarterBuildings());
        remaining.removeIf(sb -> sb.type().equals(BuildingType.TOWN_HALL.name()));

        // Partition: inner = civic/production; outer = residential/agricultural/defensive
        List<VillageTypeData.StarterBuilding> inner = new ArrayList<>();
        List<VillageTypeData.StarterBuilding> outer = new ArrayList<>();

        for (VillageTypeData.StarterBuilding sb : remaining) {
            BuildingZone zone;
            try { zone = ZoneRegistry.zoneOf(BuildingType.valueOf(sb.type())); }
            catch (IllegalArgumentException e) { outer.add(sb); continue; }
            if (zone == BuildingZone.CIVIC || zone == BuildingZone.PRODUCTION) {
                inner.add(sb);
            } else {
                outer.add(sb);
            }
        }

        int innerRadius = (int)(density.getRing1Radius() * 0.65f);
        int outerRadius = density.getRing2Radius() + 8;

        placeOnRing(layout, level, inner, centre, innerRadius,
                density, sizeCache, rng, false);
        placeOnRing(layout, level, outer, centre, outerRadius,
                density, sizeCache, rng, true);

        // Town square inside the courtyard, south of town hall
        BlockPos sqIdeal = solidSurface(level,
                new BlockPos(centre.getX(), centre.getY(),
                        centre.getZ() + innerRadius / 2));
        BlockPos sqPos = resolveOnTerrain(level, layout, sqIdeal,
                4, TownSquarePlacer.RADIUS + 2, rng);
        if (sqPos == null) sqPos = solidSurface(level, sqIdeal);
        layout.addForced(new LayoutSlot(
                LayoutSlot.SlotType.DECORATION, sqPos, TownSquarePlacer.RADIUS + 2));
        layout.setTownSquarePos(sqPos);
    }

    // =========================================================================
    // Shared ring placement helper
    // =========================================================================

    /**
     * Places a list of buildings on a single ring of the given radius.
     * Used by COURTYARD and as a building block for other shapes.
     */
    private static void placeOnRing(VillageLayout layout, ServerLevel level,
                                    List<VillageTypeData.StarterBuilding> buildings,
                                    BlockPos centre, int radius,
                                    LayoutDensityProfile density,
                                    StructureSizeCache sizeCache,
                                    Random rng, boolean rejectWaterForAgri) {
        if (buildings.isEmpty()) return;
        double angleOffset = rng.nextDouble() * (Math.PI / buildings.size());

        for (int i = 0; i < buildings.size(); i++) {
            VillageTypeData.StarterBuilding sb = buildings.get(i);
            BuildingType btype;
            try { btype = BuildingType.valueOf(sb.type()); }
            catch (IllegalArgumentException e) { continue; }

            double angle  = angleOffset + 2 * Math.PI * i / buildings.size();
            int    idealX = centre.getX() + (int)(Math.cos(angle) * radius);
            int    idealZ = centre.getZ() + (int)(Math.sin(angle) * radius);

            boolean rejectWater = rejectWaterForAgri
                    && ZoneRegistry.zoneOf(btype) == BuildingZone.AGRICULTURAL;

            BlockPos placed = resolveOnTerrain(level, layout,
                    new BlockPos(idealX, centre.getY(), idealZ),
                    density.getBuildingJitter(), DEFAULT_BUILDING_RADIUS, rng,
                    rejectWater);

            if (placed != null) {
                StructureSizeCache.FootprintInfo info =
                        sizeCache.get(sb.structure(), Rotation.NONE);
                LayoutSlot slot = new LayoutSlot(
                        placed, btype, sb.structure(), StructureSizeCache.DEFAULT_RADIUS);
                slot.setFootprint(info.width(), info.length());
                layout.tryAdd(slot);
            }
        }
    }

    // =========================================================================
    // Town hall placement helpers
    // =========================================================================

    private static void placeTownHall(VillageLayout layout, ServerLevel level,
                                      VillageTypeData typeData, BlockPos centre,
                                      Random rng, StructureSizeCache sizeCache) {
        placeTownHallAt(layout, level, typeData,
                solidSurface(level, centre), rng, sizeCache);
    }

    private static void placeTownHallAt(VillageLayout layout, ServerLevel level,
                                        VillageTypeData typeData, BlockPos pos,
                                        Random rng, StructureSizeCache sizeCache) {
        VillageTypeData.StarterBuilding thSb = typeData.getStarterBuildings().stream()
                .filter(sb -> sb.type().equals(BuildingType.TOWN_HALL.name()))
                .findFirst().orElse(null);
        if (thSb == null) return;

        StructureSizeCache.FootprintInfo info =
                sizeCache.get(thSb.structure(), Rotation.NONE);
        LayoutSlot slot = new LayoutSlot(
                pos, BuildingType.TOWN_HALL, thSb.structure(),
                StructureSizeCache.DEFAULT_RADIUS);
        slot.setFootprint(info.width(), info.length());
        layout.tryAdd(slot);
    }

    private static void placeTownSquare(VillageLayout layout, ServerLevel level,
                                        BlockPos centre, LayoutDensityProfile density,
                                        Random rng) {
        int squareClearance = Math.max(
                density.getRing1Radius() + TownSquarePlacer.RADIUS + 4,
                DEFAULT_BUILDING_RADIUS + TownSquarePlacer.RADIUS + 8);
        BlockPos sqIdeal = centre.south(squareClearance);
        BlockPos sqPos   = resolveOnTerrain(level, layout, sqIdeal,
                4, TownSquarePlacer.RADIUS + 2, rng);
        if (sqPos == null) sqPos = solidSurface(level, sqIdeal);
        layout.tryAdd(new LayoutSlot(
                LayoutSlot.SlotType.DECORATION, sqPos, TownSquarePlacer.RADIUS + 2));
        layout.setTownSquarePos(sqPos);
    }

    // =========================================================================
    // Ring building placement (RADIAL shared logic)
    // =========================================================================

    private static void placeRingBuildings(VillageLayout layout, ServerLevel level,
                                           VillageTypeData typeData, BlockPos centre,
                                           LayoutDensityProfile density,
                                           Random rng, StructureSizeCache sizeCache) {
        List<VillageTypeData.StarterBuilding> remaining = new ArrayList<>();
        for (VillageTypeData.StarterBuilding sb : typeData.getStarterBuildings()) {
            if (sb.type().equals(BuildingType.TOWN_HALL.name())) continue;
            int count = sb.resolveCount(rng);
            for (int k = 0; k < count; k++) {
                remaining.add(sb); // same entry repeated count times
            }
        }

        remaining.sort(Comparator.comparingInt(sb -> {
            try {
                BuildingType bt = BuildingType.valueOf(sb.type());
                ZoneRegistry.ZoneEntry ze = ZoneRegistry.get(bt);
                return ze.zone().preferredRing * 100 + ze.priority();
            } catch (IllegalArgumentException e) { return 999; }
        }));

        List<Integer> radii = new ArrayList<>();
        radii.add(density.getRing1Radius());
        if (density.isUseRing2()) radii.add(density.getRing2Radius());
        if (density.isUseRing3()) radii.add(density.getRing3Radius());

        BlockPos squarePos = layout.getTownSquarePos();
        double squareAngle = angleTo(centre, squarePos != null ? squarePos : centre.south(1));

        if (layout.getTerrain().hasWater() && layout.getTerrain().waterFacingDir() != null) {
            squareAngle = switch (layout.getTerrain().waterFacingDir()) {
                case NORTH -> 270.0; case SOUTH -> 90.0;
                case EAST  ->   0.0; case WEST  -> 180.0;
            };
        }

        int buildingIdx = 0;
        for (int ring = 0; ring < radii.size() && buildingIdx < remaining.size(); ring++) {
            int    radius = radii.get(ring);
            int    slots  = Math.max(4, (int)(2 * Math.PI * radius / density.getBuildingSpacing()));
            double angleOffset = rng.nextDouble() * (Math.PI / slots);

            for (int i = 0; i < slots && buildingIdx < remaining.size(); i++) {
                double rawAngle    = angleOffset + (2 * Math.PI * i / slots);
                double slotAngleDeg = ((Math.toDegrees(rawAngle) - squareAngle + 360) % 360);

                VillageTypeData.StarterBuilding sb = remaining.get(buildingIdx);
                BuildingType btype;
                try { btype = BuildingType.valueOf(sb.type()); }
                catch (IllegalArgumentException e) { buildingIdx++; continue; }

                ZoneRegistry.ZoneEntry zone = ZoneRegistry.get(btype);
                if (ring == 0 && !zone.zone().containsAngle(slotAngleDeg)) continue;

                int idealX = centre.getX() + (int)(Math.cos(rawAngle) * radius);
                int idealZ = centre.getZ() + (int)(Math.sin(rawAngle) * radius);

                BlockPos placed = resolveOnTerrain(level, layout,
                        new BlockPos(idealX, centre.getY(), idealZ),
                        density.getBuildingJitter(), DEFAULT_BUILDING_RADIUS, rng,
                        ZoneRegistry.zoneOf(btype) == BuildingZone.AGRICULTURAL);

                if (placed != null) {
                    StructureSizeCache.FootprintInfo info =
                            sizeCache.get(sb.structure(), Rotation.NONE);
                    LayoutSlot slot = new LayoutSlot(
                            placed, btype, sb.structure(), StructureSizeCache.DEFAULT_RADIUS);
                    slot.setFootprint(info.width(), info.length());
                    layout.tryAdd(slot);
                    buildingIdx++;
                }
            }
        }

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
            try { btype = BuildingType.valueOf(sb.type()); }
            catch (IllegalArgumentException e) { continue; }

            boolean placed = false;

            // ── Pass 1: try all existing rings ────────────────────────────────
            for (int ring = radii.size() - 1; ring >= 0 && !placed; ring--) {
                int    radius     = radii.get(ring);
                int    slots      = Math.max(8, (int)(2 * Math.PI * radius
                        / density.getBuildingSpacing()));
                double startAngle = rng.nextDouble() * 2 * Math.PI;

                for (int i = 0; i < slots && !placed; i++) {
                    double angle  = startAngle + (2 * Math.PI * i / slots);
                    int    idealX = centre.getX() + (int)(Math.cos(angle) * radius);
                    int    idealZ = centre.getZ() + (int)(Math.sin(angle) * radius);
                    BlockPos pos  = resolveOnTerrain(level, layout,
                            new BlockPos(idealX, centre.getY(), idealZ),
                            density.getBuildingJitter(), DEFAULT_BUILDING_RADIUS, rng);
                    if (pos != null) {
                        StructureSizeCache.FootprintInfo info =
                                new StructureSizeCache(level).get(sb.structure(), Rotation.NONE);
                        LayoutSlot slot = new LayoutSlot(pos, btype, sb.structure(),
                                StructureSizeCache.DEFAULT_RADIUS);
                        slot.setFootprint(info.width(), info.length());
                        layout.tryAdd(slot);
                        placed = true;
                    }
                }
            }

            if (placed) continue;

            // ── Pass 2: progressive expansion — try radii up to ring3 + 100 ──
            int outerRadius = (!radii.isEmpty() ? radii.get(radii.size() - 1) : density.getRing1Radius());
            for (int extraR = outerRadius + 16; extraR <= outerRadius + 160 && !placed; extraR += 16) {
                int    slots      = Math.max(8, (int)(2 * Math.PI * extraR
                        / density.getBuildingSpacing()));
                double startAngle = rng.nextDouble() * 2 * Math.PI;

                for (int i = 0; i < slots && !placed; i++) {
                    double angle  = startAngle + (2 * Math.PI * i / slots);
                    int    idealX = centre.getX() + (int)(Math.cos(angle) * extraR);
                    int    idealZ = centre.getZ() + (int)(Math.sin(angle) * extraR);
                    BlockPos pos  = resolveOnTerrain(level, layout,
                            new BlockPos(idealX, centre.getY(), idealZ),
                            density.getBuildingJitter() * 2,  // looser jitter outside rings
                            DEFAULT_BUILDING_RADIUS, rng);
                    if (pos != null) {
                        LayoutSlot slot = new LayoutSlot(pos, btype, sb.structure(),
                                DEFAULT_BUILDING_RADIUS);
                        layout.tryAdd(slot);
                        placed = true;
                        System.out.println("VillagePlanner: placed " + btype
                                + " in expansion ring at r=" + extraR);
                    }
                }
            }

            // ── Pass 3: last resort — ignore overlap, just find flat ground ───
            if (!placed) {
                placed = lastResortPlace(layout, level, sb, btype, centre, rng);
            }

            if (!placed) {
                System.out.println("VillagePlanner: WARN — could not place "
                        + btype + " after all passes");
            }
        }
    }
    private static boolean lastResortPlace(VillageLayout layout,
                                           ServerLevel level,
                                           VillageTypeData.StarterBuilding sb,
                                           BuildingType btype,
                                           BlockPos centre,
                                           Random rng) {
        for (int r = 20; r <= 256; r += 12) {
            for (int a = 0; a < 360; a += 15) {
                double rad = Math.toRadians(a + rng.nextInt(8));
                int    bx  = centre.getX() + (int)(Math.cos(rad) * r);
                int    bz  = centre.getZ() + (int)(Math.sin(rad) * r);
                BlockPos candidate = solidSurface(level, new BlockPos(bx, centre.getY(), bz));

                // Only basic checks: not on a ridge, not in water
                if (layout.getTerrain().isOnRidge(candidate, DEFAULT_BUILDING_RADIUS)) continue;
                if (isWaterAdjacent(level, candidate)) continue;

                // Skip overlap check — this IS the last resort
                LayoutSlot slot = new LayoutSlot(candidate, btype, sb.structure(),
                        DEFAULT_BUILDING_RADIUS);
                layout.addForced(slot);  // addForced bypasses overlap check
                System.out.println("VillagePlanner: last-resort placed "
                        + btype + " at r=" + r);
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // Farm plot placement — FARMHOUSE-gated
    // =========================================================================

    /**
     * Places farm plot slots in the layout.
     *
     * <p><b>Plot count = number of FARMHOUSE buildings in the starter list.</b>
     * If there are no farmhouses, no plots are placed at all. This prevents
     * orphaned farm plots spawning for village types (e.g. mining camps or
     * guard outposts) that have no farmer NPC to work them.</p>
     *
     * <p>For HILLTOP villages, even if farmhouses exist, plots are suppressed
     * because hilltop terrain is generally unsuitable for farming.</p>
     */
    private static void placeFarmPlots(VillageLayout layout, ServerLevel level,
                                       TerrainProfile terrain,
                                       LayoutDensityProfile density,
                                       VillageTypeData typeData,
                                       Random rng) {
        // ── Gate: only place plots if farmhouses are present ────────────────
        long farmhouseCount = typeData.getStarterBuildings().stream()
                .filter(sb -> sb.type().equals(BuildingType.FARMHOUSE.name()))
                .count();
        if (farmhouseCount == 0) return;

        // ── Gate: hilltop terrain is unsuitable for farming ─────────────────
        VillageShapeProfile profile = typeData.getShapeProfile();
        if (profile.shapeType() == ShapeType.HILLTOP) return;

        int plotCount = (int) farmhouseCount; // one plot per farmhouse

        int[] dir     = flatDirVector(terrain.bestFlatDir());
        int   perpX   =  dir[1];
        int   perpZ   = -dir[0];

        int ring2           = density.getRing2Radius();
        int perimeterRadius = ring2 + 12;
        BlockPos centre     = layout.getCenter();

        for (int i = 0; i < plotCount; i++) {
            int sideOffset = (i - plotCount / 2) * (FARM_PLOT_RADIUS * 2 + 4);

            int tx = centre.getX()
                    + dir[0] * Math.max(density.getFarmOffset(), perimeterRadius)
                    + perpX  * sideOffset;
            int tz = centre.getZ()
                    + dir[1] * Math.max(density.getFarmOffset(), perimeterRadius)
                    + perpZ  * sideOffset;

            BlockPos idealPos = terrain.bestFlatNear(
                    tx - terrain.origin().getX(),
                    tz - terrain.origin().getZ());

            BlockPos resolved = resolveOnTerrain(
                    level, layout, idealPos,
                    4, FARM_PLOT_RADIUS, rng, true); // rejectWater = true

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
        int gapRadius = (density.getRing1Radius() + density.getRing2Radius()) / 2;
        BlockPos centre = layout.getCenter();

        for (int i = 0; i < clusters; i++) {
            double angle   = (2 * Math.PI * i / clusters) + rng.nextDouble() * 0.8;
            int    dx      = (int)(Math.cos(angle) * gapRadius);
            int    dz      = (int)(Math.sin(angle) * gapRadius);

            BlockPos ideal    = terrain.bestFlatNear(dx, dz);
            BlockPos resolved = resolveOnTerrain(level, layout, ideal, 6, DECORATION_RADIUS, rng);

            if (resolved != null) {
                layout.addForced(new LayoutSlot(
                        LayoutSlot.SlotType.DECORATION, resolved, DECORATION_RADIUS));
            }
        }
    }

    // =========================================================================
    // Water-edge buildings
    // =========================================================================

    private static void placeWaterEdgeBuildings(VillageLayout layout, ServerLevel level,
                                                VillageTypeData typeData, BlockPos centre,
                                                TerrainProfile terrain,
                                                LayoutDensityProfile density, Random rng) {
        TerrainAnalyzer.WaterBodyInfo water = terrain.waterBody();
        if (water == null) return;
        BlockPos shore = water.nearestShore();

        // Docks
        BlockPos docksIdeal = shore.offset(
                Integer.signum(centre.getX() - shore.getX()) * 3, 0,
                Integer.signum(centre.getZ() - shore.getZ()) * 3);
        BlockPos docksPos = resolveOnTerrain(level, layout, docksIdeal,
                3, DEFAULT_BUILDING_RADIUS, rng);
        if (docksPos != null) {
            String docksPath = findBuildingPath(typeData, BuildingType.DOCKS, "docks/level_1");
            layout.tryAdd(new LayoutSlot(docksPos, BuildingType.DOCKS, docksPath, DEFAULT_BUILDING_RADIUS));
        }

        // Mill (inland from shore)
        int inlandDist  = 8 + rng.nextInt(6);
        BlockPos millIdeal = shore.offset(
                Integer.signum(centre.getX() - shore.getX()) * inlandDist, 0,
                Integer.signum(centre.getZ() - shore.getZ()) * inlandDist);
        BlockPos millPos = resolveOnTerrain(level, layout, millIdeal,
                4, DEFAULT_BUILDING_RADIUS, rng);
        if (millPos != null) {
            String millPath = findBuildingPath(typeData, BuildingType.MILLER, "miller/level_1");
            layout.tryAdd(new LayoutSlot(millPos, BuildingType.MILLER, millPath, DEFAULT_BUILDING_RADIUS));
        }

        // Waterfront houses
        if (water.radius() >= 8) {
            int hutCount = water.radius() >= 20 ? 2 : 1;
            for (int i = 0; i < hutCount; i++) {
                double shoreAngle = Math.atan2(
                        shore.getZ() - water.centre().getZ(),
                        shore.getX() - water.centre().getX());
                double offset = (i + 1) * (Math.PI / 4);
                int hx = water.centre().getX() + (int)(Math.cos(shoreAngle + offset) * (water.radius() + 2));
                int hz = water.centre().getZ() + (int)(Math.sin(shoreAngle + offset) * (water.radius() + 2));
                BlockPos hutIdeal = solidSurface(level, new BlockPos(hx, 64, hz));
                BlockPos hutPos   = resolveOnTerrain(level, layout, hutIdeal, 4, DEFAULT_BUILDING_RADIUS, rng);
                if (hutPos != null) {
                    String housePath = findBuildingPath(typeData, BuildingType.HOUSE, "house/level_1");
                    layout.tryAdd(new LayoutSlot(hutPos, BuildingType.HOUSE, housePath, DEFAULT_BUILDING_RADIUS));
                }
            }
        }
    }

    // =========================================================================
    // Y clustering
    // =========================================================================

    private static void clusterBuildingYLevels(VillageLayout layout, ServerLevel level) {
        List<LayoutSlot> buildings = layout.buildings();
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
                if (Math.max(dx, dz) <= searchRadius) nearbyY.add(other.getPos().getY());
            }
            if (nearbyY.isEmpty()) continue;
            Collections.sort(nearbyY);
            int medianY = nearbyY.get(nearbyY.size() / 2);
            int diff    = Math.abs(slot.getPos().getY() - medianY);
            if (diff > 0 && diff <= 6) {
                int actualSurface = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        slot.getPos().getX(), slot.getPos().getZ());
                if (Math.abs(actualSurface - medianY) <= 4) slot.snapY(medianY);
            }
        }
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    private static BlockPos resolveOnTerrain(ServerLevel level, VillageLayout layout,
                                             BlockPos ideal, int jitterRange,
                                             int radius, Random rng) {
        return resolveOnTerrain(level, layout, ideal, jitterRange, radius, rng, false);
    }

    private static BlockPos resolveOnTerrain(ServerLevel level, VillageLayout layout,
                                             BlockPos ideal, int jitterRange,
                                             int radius, Random rng, boolean rejectWater) {
        TerrainProfile terrain = layout.getTerrain();

        BlockPos candidate = solidSurface(level, ideal);
        if (!terrain.isOnRidge(candidate, radius)
                && !(rejectWater && isWaterAdjacent(level, candidate))
                && noOverlap(layout, candidate, radius)) return candidate;

        for (int a = 0; a < MAX_ATTEMPTS / 2; a++) {
            int jx = rng.nextInt(jitterRange * 2 + 1) - jitterRange;
            int jz = rng.nextInt(jitterRange * 2 + 1) - jitterRange;
            candidate = solidSurface(level, ideal.offset(jx, 0, jz));
            if (!terrain.isOnRidge(candidate, radius)
                    && !(rejectWater && isWaterAdjacent(level, candidate))
                    && noOverlap(layout, candidate, radius)) return candidate;
        }

        BlockPos nearestClearing = terrain.bestFlatNear(
                ideal.getX() - terrain.origin().getX(),
                ideal.getZ() - terrain.origin().getZ());
        candidate = solidSurface(level, nearestClearing);
        if (!terrain.isOnRidge(candidate, radius)
                && !(rejectWater && isWaterAdjacent(level, candidate))
                && noOverlap(layout, candidate, radius)) return candidate;

        int step = Math.max(4, DEFAULT_BUILDING_RADIUS + 2);
        for (int ring = 1; ring <= MAX_ATTEMPTS / 2; ring++) {
            for (int[] off : new int[][]{
                    {ring,0},{-ring,0},{0,ring},{0,-ring},
                    {ring,ring},{-ring,ring},{ring,-ring},{-ring,-ring}}) {
                candidate = solidSurface(level, ideal.offset(off[0]*step, 0, off[1]*step));
                if (!terrain.isOnRidge(candidate, radius)
                        && !(rejectWater && isWaterAdjacent(level, candidate))
                        && noOverlap(layout, candidate, radius)) return candidate;
            }
        }
        return null;
    }

    private static boolean noOverlap(VillageLayout layout, BlockPos pos, int radius) {
        LayoutSlot probe = new LayoutSlot(LayoutSlot.SlotType.PATH_NODE, pos, radius);
        for (LayoutSlot existing : layout.getAllSlots()) {
            if (existing.getSlotType() == LayoutSlot.SlotType.PATH_NODE) continue;
            if (probe.overlaps(existing)) return false;
        }
        return true;
    }

    private static boolean isWaterAdjacent(ServerLevel level, BlockPos pos) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (level.isWaterAt(pos.offset(dx, 0, dz))) return true;
                if (level.isWaterAt(pos.offset(dx,-1, dz))) return true;
            }
        }
        return false;
    }

    private static BlockPos solidSurface(ServerLevel level, BlockPos pos) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getX(), pos.getZ());
        return new BlockPos(pos.getX(), y, pos.getZ());
    }

    private static BlockPos snapToFlat(ServerLevel level, TerrainProfile terrain, BlockPos origin) {
        return terrain.flatCandidates().stream()
                .filter(p -> p.distSqr(origin) <= 16 * 16)
                .min(Comparator.comparingDouble(p -> p.distSqr(origin)))
                .orElseGet(() -> solidSurface(level, origin));
    }

    private static BlockPos findHighestPoint(ServerLevel level, BlockPos centre, int searchRadius) {
        BlockPos best  = solidSurface(level, centre);
        int      bestY = best.getY();
        for (int dx = -searchRadius; dx <= searchRadius; dx += 4) {
            for (int dz = -searchRadius; dz <= searchRadius; dz += 4) {
                BlockPos candidate = solidSurface(level, centre.offset(dx, 0, dz));
                if (candidate.getY() > bestY) { bestY = candidate.getY(); best = candidate; }
            }
        }
        return best;
    }

    private static int[] flatDirVector(TerrainAnalyzer.FlatDirection dir) {
        return switch (dir) {
            case NORTH -> new int[]{ 0, -1};
            case SOUTH -> new int[]{ 0,  1};
            case EAST  -> new int[]{ 1,  0};
            case WEST  -> new int[]{-1,  0};
        };
    }

    private static double angleTo(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return (Math.toDegrees(Math.atan2(dz, dx)) + 360) % 360;
    }

    private static String findBuildingPath(VillageTypeData typeData,
                                           BuildingType type, String fallback) {
        return typeData.getStarterBuildings().stream()
                .filter(sb -> sb.type().equals(type.name()))
                .map(VillageTypeData.StarterBuilding::structure)
                .findFirst()
                .orElse(fallback);
    }
}