// src/main/java/tterrag1112/life_in_the_village/Village/Planning/CapitalLayoutPlanner.java
package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.TownSquarePlacer;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.*;

/**
 * Street-first layout engine for large capitals (20+ buildings).
 *
 * <h3>Pipeline</h3>
 * <ol>
 *   <li>Build abstract radial/ring street graph (wagon-wheel).</li>
 *   <li>Overlay an axis-aligned grid to form rectangular city blocks.</li>
 *   <li>Detect all rectangular city blocks from grid intersections.</li>
 *   <li>Generate face plots on each city block perimeter — buildings will
 *       pack wall-to-wall along each face, facing the road.</li>
 *   <li>Assign buildings to face plots by zone/distance priority.</li>
 *   <li>Commit assigned plots to {@link VillageLayout} slots with correct
 *       orientations derived from the face direction, not the town square.</li>
 *   <li>Place town hall at centre, town square nearby, farm plots outside
 *       the perimeter.</li>
 * </ol>
 *
 * <h3>Dense city blocks</h3>
 * The key difference from the ring planner: buildings are assigned to
 * {@link CapitalStreetGraph.BlockFacePlot} slots, not per-segment plots.
 * Each face of a city block holds multiple buildings packed at 1-block
 * gaps. A 40×40 block with a 12-block setback and 10-block building width
 * fits 3 buildings per face = 12 per block. This produces the terraced
 * row-house density of a medieval city rather than isolated buildings.
 *
 * <h3>Building orientation</h3>
 * Every building faces the street — its rotation is set by which face of
 * the city block it occupies (north/south/east/west), not by which direction
 * the town square lies. A house on the south face of a block faces south;
 * a house on the east face faces east. VillageSpawner honours the stored
 * rotation from the LayoutSlot.
 */
public class CapitalLayoutPlanner {

    // ── Wagon-wheel parameters ─────────────────────────────────────────────
    private static final int SPOKE_COUNT        = 6;

    // ── Grid overlay parameters ────────────────────────────────────────────
    // GRID_SPACING controls how large each city block is.
    // Smaller = denser. Default ~1/2 of ring1Radius.
    private static final double GRID_SPACING_FACTOR = 0.45;
    private static final int    MIN_GRID_SPACING    = 22;
    private static final int    MAX_GRID_SPACING    = 40;

    // ── City block face packing ────────────────────────────────────────────
    // Buildings are packed along block faces at these parameters.
    // BUILDING_WIDTH: typical building footprint along the face direction.
    // BUILDING_DEPTH: typical footprint perpendicular to face (into the block).
    // ALLEY_GAP: 1-block gap between adjacent buildings on the same face.
    // SETBACK: distance from road edge to building front face.
    private static final int BUILDING_WIDTH  = 10;
    private static final int BUILDING_DEPTH  = 10;
    private static final int ALLEY_GAP       = 1;
    private static final int FACE_SETBACK    = 1;  // was 4, reduced for tighter packing

    // ── Block filtering ────────────────────────────────────────────────────
    // Only register blocks within these size limits.
    private static final int MIN_BLOCK_SIZE  = 18;
    private static final int MAX_BLOCK_SIZE  = 80;


    // =========================================================================
    // Entry point
    // =========================================================================

    public static VillageLayout plan(ServerLevel level,
                                     BlockPos centre,
                                     VillageTypeData typeData,
                                     LayoutDensityProfile density,
                                     TerrainProfile terrain,
                                     StructureSizeCache sizeCache,
                                     Random rng) {
        VillageLayout layout = new VillageLayout(terrain, density);
        layout.setCenter(centre);
        int cx = centre.getX(), cz = centre.getZ();
        boolean tightGrid = typeData.getShapeProfile() != null
                && typeData.getShapeProfile().shapeType()
                == VillageTypeData.ShapeType.COURTYARD;

        // ── Step 1: Town hall at centre ───────────────────────────────────────
        placeTownHall(layout, typeData, centre, sizeCache);

        // ── Step 2: Build wagon-wheel street graph ────────────────────────────────
        CapitalStreetGraph graph = buildWagonWheel(cx, cz, density, rng);
        System.out.println("CapitalLayoutPlanner: wagon wheel — "
                + graph.getNodes().size() + " nodes, "
                + graph.getSegments().size() + " segments");

// ── Step 3: Grid overlay — returns the map of grid nodes ─────────────────
        Map<Long, CapitalStreetGraph.StreetNode> gridNodes =
                addGridOverlay(graph, cx, cz, density, rng, tightGrid);
        System.out.println("CapitalLayoutPlanner: grid overlay — "
                + gridNodes.size() + " grid nodes");

// ── Step 4: Detect city blocks from the grid node map ────────────────────


        // ── Step 5: Generate face plots on every city block ───────────────────
        for (CapitalStreetGraph.CityBlock block : graph.getCityBlocks()) {
            block.generateFacePlots(BUILDING_WIDTH, BUILDING_DEPTH,
                    ALLEY_GAP, FACE_SETBACK);
        }
        int totalPlots = graph.getCityBlocks().stream()
                .mapToInt(b -> b.plots.size()).sum();
        System.out.println("CapitalLayoutPlanner: total face plots: " + totalPlots);


        // ── Step 6: Assign buildings to face plots ────────────────────────────
        assignBuildingsToCityBlocks(graph, typeData, sizeCache, rng);
        long assigned = graph.getCityBlocks().stream()
                .flatMap(b -> b.plots.stream())
                .filter(p -> p.occupied).count();
        System.out.println("CapitalLayoutPlanner: assigned buildings: " + assigned);

        // ── Step 7: Commit plots to VillageLayout ─────────────────────────────
        commitToLayout(layout, graph, level, rng);

        // ── Step 8: Street graph stored for decorator ─────────────────────────
        layout.setCapitalStreetGraph(graph);

        // ── Step 9: Town square south of centre ───────────────────────────────
        placeTownSquare(layout, centre, density, level, rng);

        // ── Step 10: Farm plots (honoured if type defines them) ───────────────
        placeFarmPlots(layout, level, typeData, terrain, density, rng);

        System.out.println("CapitalLayoutPlanner: planned capital — " + layout);
        return layout;
    }

    // =========================================================================
    // Step 1 — Town hall
    // =========================================================================

    private static void placeTownHall(VillageLayout layout,
                                      VillageTypeData typeData,
                                      BlockPos centre,
                                      StructureSizeCache sizeCache) {
        String path = typeData.getStarterBuildings().stream()
                .filter(sb -> sb.type().equals(BuildingType.TOWN_HALL.name()))
                .findFirst().map(VillageTypeData.StarterBuilding::structure)
                .orElse("town_hall/level_1");

        LayoutSlot slot = new LayoutSlot(centre, BuildingType.TOWN_HALL,
                path, StructureSizeCache.DEFAULT_RADIUS);
        StructureSizeCache.FootprintInfo info = sizeCache.get(path, Rotation.NONE);
        slot.setFootprint(info.width(), info.length());
        layout.tryAdd(slot);
    }

    // =========================================================================
    // Step 2 — Wagon-wheel street graph
    // =========================================================================

    private static CapitalStreetGraph buildWagonWheel(int cx, int cz,
                                                      LayoutDensityProfile density,
                                                      Random rng) {
        CapitalStreetGraph graph = new CapitalStreetGraph(cx, cz);

        int r1 = density.getRing1Radius();
        int r2 = density.getRing2Radius();
        int r3 = density.getRing3Radius();
        int r4 = r3 + (r3 - r2); // outer defensive ring

        CapitalStreetGraph.StreetNode centre =
                graph.addNode(cx, cz, CapitalStreetGraph.StreetTier.PRIMARY);

        double baseAngle = rng.nextDouble() * (Math.PI / SPOKE_COUNT);

        CapitalStreetGraph.StreetNode[] n1 = new CapitalStreetGraph.StreetNode[SPOKE_COUNT];
        CapitalStreetGraph.StreetNode[] n2 = new CapitalStreetGraph.StreetNode[SPOKE_COUNT];
        CapitalStreetGraph.StreetNode[] n3 = new CapitalStreetGraph.StreetNode[SPOKE_COUNT];
        CapitalStreetGraph.StreetNode[] n4 = new CapitalStreetGraph.StreetNode[SPOKE_COUNT];

        for (int s = 0; s < SPOKE_COUNT; s++) {
            double a = baseAngle + 2 * Math.PI * s / SPOKE_COUNT;
            double cos = Math.cos(a), sin = Math.sin(a);
            n1[s] = graph.addNode(cx + (int)(cos*r1), cz + (int)(sin*r1), CapitalStreetGraph.StreetTier.PRIMARY);
            n2[s] = graph.addNode(cx + (int)(cos*r2), cz + (int)(sin*r2), CapitalStreetGraph.StreetTier.PRIMARY);
            n3[s] = graph.addNode(cx + (int)(cos*r3), cz + (int)(sin*r3), CapitalStreetGraph.StreetTier.SECONDARY);
            n4[s] = graph.addNode(cx + (int)(cos*r4), cz + (int)(sin*r4), CapitalStreetGraph.StreetTier.SECONDARY);
            // Spokes
            graph.connect(centre, n1[s], CapitalStreetGraph.StreetTier.PRIMARY);
            graph.connect(n1[s],  n2[s], CapitalStreetGraph.StreetTier.PRIMARY);
            graph.connect(n2[s],  n3[s], CapitalStreetGraph.StreetTier.SECONDARY);
            graph.connect(n3[s],  n4[s], CapitalStreetGraph.StreetTier.SECONDARY);
        }

        for (int s = 0; s < SPOKE_COUNT; s++) {
            int next = (s + 1) % SPOKE_COUNT;
            graph.connect(n1[s], n1[next], CapitalStreetGraph.StreetTier.PRIMARY);
            graph.connect(n2[s], n2[next], CapitalStreetGraph.StreetTier.SECONDARY);
            graph.connect(n3[s], n3[next], CapitalStreetGraph.StreetTier.TERTIARY);
            graph.connect(n4[s], n4[next], CapitalStreetGraph.StreetTier.TERTIARY);
        }

        return graph;
    }

    // =========================================================================
    // Step 3 — Grid overlay
    // =========================================================================

    /**
     * Adds an axis-aligned street grid over the radial network.
     * Grid intersections snap to nearby radial nodes to avoid near-duplicates.
     * After this method, the graph has both radial spokes and grid streets.
     * The decorator places both as roads.
     */
    private static Map<Long, CapitalStreetGraph.StreetNode> addGridOverlay(
            CapitalStreetGraph graph, int cx, int cz,
            LayoutDensityProfile density, Random rng, boolean tight) {
        int r2 = density.getRing2Radius();

        // Grid spacing: tighter for imperial capitals, standard otherwise
        int gridSpacing = tight
                ? Math.max(MIN_GRID_SPACING, (int)(density.getRing1Radius() * 0.35))
                : clamp((int)(density.getRing1Radius() * GRID_SPACING_FACTOR),
                MIN_GRID_SPACING, MAX_GRID_SPACING);

        // Snap threshold: if a radial node is within this distance of a grid
        // intersection, reuse the radial node instead of creating a new one
        double snapDist = gridSpacing * 0.35;

        Map<Long, CapitalStreetGraph.StreetNode> gridNodeByXZ = new LinkedHashMap<>();

        // Create grid nodes within ring2 circle
        for (int gx = cx - r2; gx <= cx + r2 + gridSpacing; gx += gridSpacing) {
            // Round to nearest grid line
            int nx = cx + Math.round((float)(gx - cx) / gridSpacing) * gridSpacing;
            for (int gz = cz - r2; gz <= cz + r2 + gridSpacing; gz += gridSpacing) {
                int nz = cz + Math.round((float)(gz - cz) / gridSpacing) * gridSpacing;
                double dx = nx - cx, dz = nz - cz;
                if (Math.sqrt(dx*dx + dz*dz) > r2 + gridSpacing * 0.5) continue;

                long key = gridKey(nx, nz);
                if (gridNodeByXZ.containsKey(key)) continue;

                // Snap to existing radial node if close enough
                CapitalStreetGraph.StreetNode existing =
                        graph.nearestNode(nx, nz, snapDist);
                if (existing != null) {
                    gridNodeByXZ.put(key, existing);
                } else {
                    double dist = Math.sqrt(dx*dx + dz*dz);
                    CapitalStreetGraph.StreetTier tier =
                            dist < density.getRing1Radius()
                                    ? CapitalStreetGraph.StreetTier.SECONDARY
                                    : CapitalStreetGraph.StreetTier.TERTIARY;
                    gridNodeByXZ.put(key, graph.addNode(nx, nz, tier));
                }
            }
        }

        // Connect horizontal grid lines
        List<Integer> gxCoords = new ArrayList<>(), gzCoords = new ArrayList<>();
        gridNodeByXZ.keySet().forEach(k -> {
            int gx = (int)((k >> 32) - 32768);
            int gz = (int)((k & 0xFFFFFFFFL) - 32768);
            if (!gxCoords.contains(gx)) gxCoords.add(gx);
            if (!gzCoords.contains(gz)) gzCoords.add(gz);
        });
        Collections.sort(gxCoords);
        Collections.sort(gzCoords);

        for (int gz : gzCoords) {
            for (int i = 0; i < gxCoords.size() - 1; i++) {
                int gx0 = gxCoords.get(i), gx1 = gxCoords.get(i + 1);
                CapitalStreetGraph.StreetNode a = gridNodeByXZ.get(gridKey(gx0, gz));
                CapitalStreetGraph.StreetNode b = gridNodeByXZ.get(gridKey(gx1, gz));
                if (a == null || b == null || a == b) continue;
                if (segmentExists(graph, a, b)) continue;
                double dist = Math.sqrt(Math.pow(a.x-cx,2)+Math.pow(a.z-cz,2));
                graph.connect(a, b, dist < density.getRing1Radius()
                        ? CapitalStreetGraph.StreetTier.SECONDARY
                        : CapitalStreetGraph.StreetTier.TERTIARY);
            }
        }

        // Connect vertical grid lines
        for (int gx : gxCoords) {
            for (int i = 0; i < gzCoords.size() - 1; i++) {
                int gz0 = gzCoords.get(i), gz1 = gzCoords.get(i + 1);
                CapitalStreetGraph.StreetNode a = gridNodeByXZ.get(gridKey(gx, gz0));
                CapitalStreetGraph.StreetNode b = gridNodeByXZ.get(gridKey(gx, gz1));
                if (a == null || b == null || a == b) continue;
                if (segmentExists(graph, a, b)) continue;
                double dist = Math.sqrt(Math.pow(a.x-cx,2)+Math.pow(a.z-cz,2));
                graph.connect(a, b, dist < density.getRing1Radius()
                        ? CapitalStreetGraph.StreetTier.SECONDARY
                        : CapitalStreetGraph.StreetTier.TERTIARY);
            }
        }
        System.out.println("CapitalLayoutPlanner: grid overlay — "
                + gridNodeByXZ.size() + " grid nodes, spacing=" + gridSpacing);

        for (int i = 0; i < gxCoords.size() - 1; i++) {
            for (int j = 0; j < gzCoords.size() - 1; j++) {
                int gx0 = gxCoords.get(i),     gx1 = gxCoords.get(i + 1);
                int gz0 = gzCoords.get(j),     gz1 = gzCoords.get(j + 1);
                int w   = gx1 - gx0,           d   = gz1 - gz0;

                if (w < MIN_BLOCK_SIZE || d < MIN_BLOCK_SIZE) continue;
                if (w > MAX_BLOCK_SIZE || d > MAX_BLOCK_SIZE) continue;

                CapitalStreetGraph.StreetNode sw = gridNodeByXZ.get(gridKey(gx0, gz0));
                CapitalStreetGraph.StreetNode se = gridNodeByXZ.get(gridKey(gx1, gz0));
                CapitalStreetGraph.StreetNode nw = gridNodeByXZ.get(gridKey(gx0, gz1));
                CapitalStreetGraph.StreetNode ne = gridNodeByXZ.get(gridKey(gx1, gz1));

                if (sw == null || se == null || nw == null || ne == null) continue;
                if (!segmentExists(graph, sw, se)) continue;
                if (!segmentExists(graph, nw, ne)) continue;
                if (!segmentExists(graph, sw, nw)) continue;
                if (!segmentExists(graph, se, ne)) continue;

                graph.registerCityBlock(new CapitalStreetGraph.CityBlock(gx0, gz0, gx1, gz1));
            }
        }
        System.out.println("CapitalLayoutPlanner: city blocks created: "
                + graph.getCityBlocks().size());

        return gridNodeByXZ;  //

    }

    private static boolean segmentExists(CapitalStreetGraph graph,
                                         CapitalStreetGraph.StreetNode a,
                                         CapitalStreetGraph.StreetNode b) {
        return graph.getSegments().stream().anyMatch(
                s -> (s.a == a && s.b == b) || (s.a == b && s.b == a));
    }

    // =========================================================================
    // Step 6 — Assign buildings to city block face plots
    // =========================================================================

    /**
     * Assigns buildings from the type definition to city block face plots.
     *
     * <h3>Assignment order</h3>
     * Buildings are sorted by zone priority (civic first, defensive last).
     * Each building is matched to the nearest unoccupied face plot whose
     * distance from the centre falls in the zone's preferred band:
     * <ul>
     *   <li>CIVIC → innermost blocks (core ring)</li>
     *   <li>PRODUCTION → mid-ring blocks</li>
     *   <li>RESIDENTIAL → outer-ring blocks</li>
     *   <li>DEFENSIVE → perimeter blocks</li>
     * </ul>
     *
     * The building list is pre-expanded using min/maxCount so that
     * {@code buildingN("HOUSE", ..., 8)} produces 8 separate assignments.
     */
    private static void assignBuildingsToCityBlocks(CapitalStreetGraph graph,
                                                    VillageTypeData typeData,
                                                    StructureSizeCache sizeCache,
                                                    Random rng) {
        int cx = graph.centreX, cz = graph.centreZ;

        // Expand building list by count
        List<VillageTypeData.StarterBuilding> buildings = new ArrayList<>();
        for (VillageTypeData.StarterBuilding sb : typeData.getStarterBuildings()) {
            if (sb.type().equals(BuildingType.TOWN_HALL.name())) continue;
            int count = sb.resolveCount(rng);
            for (int k = 0; k < count; k++) buildings.add(sb);
        }

        // Sort by zone priority
        buildings.sort(Comparator.comparingInt(sb -> {
            try {
                BuildingType bt = BuildingType.valueOf(sb.type());
                ZoneRegistry.ZoneEntry ze = ZoneRegistry.get(bt);
                return ze.zone().preferredRing * 100 + ze.priority();
            } catch (IllegalArgumentException e) { return 999; }
        }));

        // Pre-compute ring radii estimates for zone banding
        double r1 = estimateRing(graph, cx, cz, 1);
        double r2 = estimateRing(graph, cx, cz, 2);
        double r3 = estimateRing(graph, cx, cz, 3);

        for (VillageTypeData.StarterBuilding sb : buildings) {
            BuildingType btype;
            try { btype = BuildingType.valueOf(sb.type()); }
            catch (IllegalArgumentException e) { continue; }

            ZoneRegistry.ZoneEntry zone = ZoneRegistry.get(btype);
            double minD = zoneMin(zone.zone(), r1, r2, r3);
            double maxD = zoneMax(zone.zone(), r1, r2, r3);

            // Needed footprint size — prefer plots where buildings fit well
            StructureSizeCache.FootprintInfo info =
                    sizeCache.get(sb.structure(), Rotation.NONE);
            int neededW = Math.max(info.width(), info.length());

            // Find the best available plot in the zone's distance band
            CapitalStreetGraph.BlockFacePlot best = null;
            for (CapitalStreetGraph.CityBlock block : graph.getCityBlocks()) {
                double dist = Math.sqrt(Math.pow(block.centreX() - cx, 2)
                        + Math.pow(block.centreZ() - cz, 2));
                if (dist < minD || dist > maxD) continue;

                for (CapitalStreetGraph.BlockFacePlot plot : block.plots) {
                    if (plot.occupied) continue;
                    if (plot.plotW < neededW - 4) continue; // plot too narrow
                    best = plot;
                    break;
                }
                if (best != null) break;
            }

            // Fallback: any unoccupied plot anywhere
            if (best == null) {
                for (CapitalStreetGraph.BlockFacePlot p
                        : graph.getUnoccupiedPlotsByDistance()) {
                    if (!p.occupied) { best = p; break; }
                }
            }

            if (best != null) {
                best.assignedType  = btype;
                best.structurePath = sb.structure();
                best.occupied      = true;
            }
        }


    }

    private static double zoneMin(BuildingZone zone,
                                  double r1, double r2, double r3) {
        return switch (zone) {
            case CIVIC        -> 0;
            case PRODUCTION   -> r1 * 0.7;
            case RESIDENTIAL  -> r2 * 0.6;
            case AGRICULTURAL -> r3 * 0.8;
            case DEFENSIVE    -> r3 * 0.9;
        };
    }

    private static double zoneMax(BuildingZone zone,
                                  double r1, double r2, double r3) {
        double r4 = r3 + (r3 - r2);
        return switch (zone) {
            case CIVIC        -> r1 * 1.3;
            case PRODUCTION   -> r2 * 1.1;
            case RESIDENTIAL  -> r3 * 1.1;
            case AGRICULTURAL -> r4 * 1.0;
            case DEFENSIVE    -> r4 * 1.4;
        };
    }

    private static double estimateRing(CapitalStreetGraph graph,
                                       int cx, int cz, int ringIdx) {
        List<Double> dists = new ArrayList<>();
        for (CapitalStreetGraph.StreetNode n : graph.getNodes()) {
            double dx = n.x - cx, dz = n.z - cz, d = Math.sqrt(dx*dx + dz*dz);
            if (d > 1) dists.add(d);
        }
        if (dists.isEmpty()) return ringIdx * 40.0;
        Collections.sort(dists);
        int idx = Math.min(dists.size()-1, (int)(dists.size() * ringIdx * 0.25));
        return dists.get(idx);
    }

    // =========================================================================
    // Step 7 — Commit to VillageLayout
    // =========================================================================

    /**
     * Converts assigned BlockFacePlots into LayoutSlots.
     * The slot's position is the plot centre snapped to live heightmap Y.
     * The slot stores the facing rotation derived from the face direction
     * so VillageSpawner uses it directly instead of recomputing toward the
     * town square.
     */
    private static void commitToLayout(VillageLayout layout,
                                       CapitalStreetGraph graph,
                                       ServerLevel level,
                                       Random rng) {
        for (CapitalStreetGraph.CityBlock block : graph.getCityBlocks()) {
            for (CapitalStreetGraph.BlockFacePlot plot : block.plots) {
                if (!plot.occupied || plot.assignedType == null) continue;

                int surfY = level.getHeight(
                        Heightmap.Types.WORLD_SURFACE, plot.x, plot.z);
                if (surfY <= level.getMinY() + 2) surfY = layout.getCenter().getY();

                BlockPos pos = new BlockPos(plot.x, surfY, plot.z);

                LayoutSlot.LayoutSlotWithRotation slot = new LayoutSlot.LayoutSlotWithRotation(
                        pos, plot.assignedType, plot.structurePath,
                        StructureSizeCache.DEFAULT_RADIUS,
                        plot.facingRotation());

                // Face plots are geometrically non-overlapping by construction.
                // The overlap check is for the ring planner — skip it here.
                layout.addForced(slot);
            }
        }
    }

    // =========================================================================
    // Steps 9–10 — Town square + farm plots
    // =========================================================================

    private static void placeTownSquare(VillageLayout layout,
                                        BlockPos centre,
                                        LayoutDensityProfile density,
                                        ServerLevel level,
                                        Random rng) {
        int squareDist = density.getRing1Radius() / 2;
        int sx = centre.getX(), sz = centre.getZ() + squareDist;
        int sy = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz);
        BlockPos squarePos = new BlockPos(sx, sy, sz);
        layout.tryAdd(new LayoutSlot(LayoutSlot.SlotType.DECORATION,
                squarePos, TownSquarePlacer.RADIUS + 2));
        layout.setTownSquarePos(squarePos);
    }

    private static void placeFarmPlots(VillageLayout layout,
                                       ServerLevel level,
                                       VillageTypeData typeData,
                                       TerrainProfile terrain,
                                       LayoutDensityProfile density,
                                       Random rng) {
        long fhCount = typeData.getStarterBuildings().stream()
                .filter(sb -> sb.type().equals(BuildingType.FARMHOUSE.name()))
                .count();
        if (fhCount == 0) return;

        int farmRadius = density.getRing3Radius() + 40;
        int[] dir = flatDir(terrain.bestFlatDir());
        int perpX = dir[1], perpZ = -dir[0];

        for (int i = 0; i < fhCount; i++) {
            int sideOffset = (int)((i - fhCount / 2.0) * 28);
            int tx = layout.getCenter().getX() + dir[0] * farmRadius + perpX * sideOffset;
            int tz = layout.getCenter().getZ() + dir[1] * farmRadius + perpZ * sideOffset;
            BlockPos ideal = terrain.bestFlatNear(
                    tx - terrain.origin().getX(), tz - terrain.origin().getZ());
            int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    ideal.getX(), ideal.getZ());
            layout.tryAdd(new LayoutSlot(LayoutSlot.SlotType.FARM_PLOT,
                    new BlockPos(ideal.getX(), sy, ideal.getZ()), 10));
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static int[] flatDir(TerrainAnalyzer.FlatDirection dir) {
        return switch (dir) {
            case NORTH -> new int[]{ 0, -1};
            case SOUTH -> new int[]{ 0,  1};
            case EAST  -> new int[]{ 1,  0};
            case WEST  -> new int[]{-1,  0};
        };
    }

    private static long gridKey(int x, int z) {
        return ((long)(x + 32768)) << 32 | ((z + 32768) & 0xFFFFFFFFL);
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}