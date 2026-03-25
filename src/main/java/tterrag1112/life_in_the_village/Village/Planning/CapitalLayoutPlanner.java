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
 *   <li>Town hall at centre.</li>
 *   <li>Build rectangular grid of streets ({@link #buildPureGrid}).</li>
 *   <li>Optionally overlay radial spoke avenues ({@link #addSpokes}) for ROUND layout.</li>
 *   <li>Detect rectangular city blocks from adjacent grid cells ({@link #createCityBlocks}).</li>
 *   <li>Assign each block a {@link CapitalStreetGraph.CapitalDistrict} based on
 *       distance + angle ({@link #assignDistricts}).</li>
 *   <li>Generate face plots on every block with corner insets
 *       ({@link CapitalStreetGraph.CityBlock#generateFacePlots}).</li>
 *   <li>Tag each plot with its angle from centre.</li>
 *   <li>Assign buildings to plots respecting district and angular sector
 *       ({@link #assignBuildings}).</li>
 *   <li>Commit to VillageLayout using addForced (pre-validated).</li>
 *   <li>Store gate positions (outer ring nodes).</li>
 *   <li>Town square at the primary axis intersection.</li>
 *   <li>Farm plots beyond the outer ring.</li>
 * </ol>
 *
 * <h3>Street placement order</h3>
 * Streets are committed to the abstract graph here. The decorator
 * ({@link tterrag1112.life_in_the_village.Village.Decoration.VillageDecorator})
 * places them as straight-line block runs BEFORE buildings are spawned
 * by VillageSpawner, eliminating the road-routing-around-buildings problem.
 */
public class CapitalLayoutPlanner {

    // ── Sizing constants — overridden by CapitalProfile values ────────────────
    // These are fallback defaults used when profile fields are zero.
    private static final int DEFAULT_ROAD_HALF_WIDTH = 2; // SECONDARY halfWidth

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

        VillageTypeData.CapitalProfile profile = typeData.getCapitalProfile();

        // ORGANIC falls back to null → ring planner
        if (profile.layoutType() == VillageTypeData.CapitalLayoutType.ORGANIC) {
            return null;
        }

        VillageLayout layout = new VillageLayout(terrain, density);
        layout.setCenter(centre);
        int cx = centre.getX(), cz = centre.getZ();

        // ── Step 1: Town hall ─────────────────────────────────────────────────
        placeTownHall(layout, typeData, centre, sizeCache);

        // ── Step 2: Build grid + optional spokes ──────────────────────────────
        int gridSpacing = profile.resolvedGridSpacing();
        System.out.println("CapitalLayoutPlanner: layout=" + profile.layoutType()
                + " gridSpacing=" + gridSpacing);

        CapitalStreetGraph graph = new CapitalStreetGraph(cx, cz);
        Map<Long, CapitalStreetGraph.StreetNode> gridNodes =
                buildPureGrid(graph, cx, cz, density, gridSpacing);

        if (profile.layoutType() == VillageTypeData.CapitalLayoutType.ROUND
                && profile.spokeCount() > 0) {
            addSpokes(graph, cx, cz, density, profile.spokeCount(), gridSpacing, rng);
        }

        System.out.println("CapitalLayoutPlanner: grid — "
                + graph.getNodes().size() + " nodes, "
                + graph.getSegments().size() + " segments");

        // ── Step 3: City blocks ───────────────────────────────────────────────
        int minBlock = (int)(gridSpacing * 0.85);
        int maxBlock = (int)(gridSpacing * 2.15);
        createCityBlocks(graph, gridNodes, minBlock, maxBlock);
        System.out.println("CapitalLayoutPlanner: city blocks: "
                + graph.getCityBlocks().size());

        // ── Step 4: District assignment ───────────────────────────────────────
        assignDistricts(graph, cx, cz, density);

        // ── Step 5: Generate face plots with corner insets ────────────────────
        int roadHW = DEFAULT_ROAD_HALF_WIDTH;
        for (CapitalStreetGraph.CityBlock block : graph.getCityBlocks()) {
            block.generateFacePlots(
                    profile.buildingWidth(), profile.buildingDepth(),
                    profile.alleyGap(), profile.faceSetback(), roadHW);
            // Tag each plot with its angle from centre
            for (CapitalStreetGraph.BlockFacePlot plot : block.plots) {
                plot.angleFromCentre = Math.toDegrees(
                        Math.atan2(plot.z - cz, plot.x - cx));
            }
        }

        int totalPlots = graph.getCityBlocks().stream()
                .mapToInt(b -> b.plots.size()).sum();
        System.out.println("CapitalLayoutPlanner: total face plots: " + totalPlots);

        // ── Step 6: Assign buildings to plots ────────────────────────────────
        assignBuildings(graph, typeData, sizeCache, rng, cx, cz);
        long assigned = graph.getCityBlocks().stream()
                .flatMap(b -> b.plots.stream())
                .filter(p -> p.occupied).count();
        System.out.println("CapitalLayoutPlanner: assigned buildings: " + assigned);

        // ── Step 7: Commit to layout ──────────────────────────────────────────
        commitToLayout(layout, graph, level);

        // ── Step 8: Store graph for decorator ────────────────────────────────
        layout.setCapitalStreetGraph(graph);

        // ── Step 9: Gate positions ────────────────────────────────────────────
        storeGatePositions(layout, graph, cx, cz, density, gridSpacing);

        // ── Step 10: Town square at axis centre ───────────────────────────────
        placeTownSquare(layout, centre, density, level, rng);

        // ── Step 11: Farm plots ───────────────────────────────────────────────
        placeFarmPlots(layout, level, typeData, terrain, density, rng);

        System.out.println("CapitalLayoutPlanner: planned capital — " + layout);
        return layout;
    }

    // =========================================================================
    // Grid builder
    // =========================================================================

    /**
     * Builds the core rectangular grid. No node snapping — every node is
     * placed at exactly its grid coordinate so block detection works reliably.
     * The two main axes (nx==cx and nz==cz) are PRIMARY; inner nodes are
     * SECONDARY; outer nodes are TERTIARY.
     */
    private static Map<Long, CapitalStreetGraph.StreetNode> buildPureGrid(
            CapitalStreetGraph graph, int cx, int cz,
            LayoutDensityProfile density, int gridSpacing) {

        int r1 = density.getRing1Radius();
        int r2 = density.getRing2Radius();
        Map<Long, CapitalStreetGraph.StreetNode> nodeByXZ = new LinkedHashMap<>();
        List<Integer> xs = new ArrayList<>(), zs = new ArrayList<>();

        // ── Create nodes ──────────────────────────────────────────────────────
        for (int offX = -r2 - gridSpacing; offX <= r2 + gridSpacing; offX += gridSpacing) {
            int nx = cx + (int)Math.round((double)offX / gridSpacing) * gridSpacing;
            for (int offZ = -r2 - gridSpacing; offZ <= r2 + gridSpacing; offZ += gridSpacing) {
                int nz = cz + (int)Math.round((double)offZ / gridSpacing) * gridSpacing;

                double dx = nx - cx, dz = nz - cz;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > r2 + gridSpacing * 0.5) continue;

                long key = gridKey(nx, nz);
                if (nodeByXZ.containsKey(key)) continue;

                // Tier: main axes PRIMARY, inner SECONDARY, outer TERTIARY
                CapitalStreetGraph.StreetTier tier;
                if (nx == cx || nz == cz) {
                    tier = CapitalStreetGraph.StreetTier.PRIMARY;
                } else if (dist < r1) {
                    tier = CapitalStreetGraph.StreetTier.SECONDARY;
                } else {
                    tier = CapitalStreetGraph.StreetTier.TERTIARY;
                }

                CapitalStreetGraph.StreetNode node = graph.addNode(nx, nz, tier);
                nodeByXZ.put(key, node);
                if (!xs.contains(nx)) xs.add(nx);
                if (!zs.contains(nz)) zs.add(nz);
            }
        }

        Collections.sort(xs); Collections.sort(zs);

        // ── Connect horizontal lines ──────────────────────────────────────────
        for (int nz : zs) {
            for (int i = 0; i < xs.size() - 1; i++) {
                CapitalStreetGraph.StreetNode a = nodeByXZ.get(gridKey(xs.get(i),     nz));
                CapitalStreetGraph.StreetNode b = nodeByXZ.get(gridKey(xs.get(i + 1), nz));
                if (a == null || b == null || a == b) continue;
                if (segmentExists(graph, a, b)) continue;
                CapitalStreetGraph.StreetTier tier =
                        (nz == cz) ? CapitalStreetGraph.StreetTier.PRIMARY
                                : (Math.abs(nz - cz) <= density.getRing1Radius())
                                ? CapitalStreetGraph.StreetTier.SECONDARY
                                : CapitalStreetGraph.StreetTier.TERTIARY;
                graph.connect(a, b, tier);
            }
        }

        // ── Connect vertical lines ────────────────────────────────────────────
        for (int nx : xs) {
            for (int i = 0; i < zs.size() - 1; i++) {
                CapitalStreetGraph.StreetNode a = nodeByXZ.get(gridKey(nx, zs.get(i)));
                CapitalStreetGraph.StreetNode b = nodeByXZ.get(gridKey(nx, zs.get(i + 1)));
                if (a == null || b == null || a == b) continue;
                if (segmentExists(graph, a, b)) continue;
                CapitalStreetGraph.StreetTier tier =
                        (nx == cx) ? CapitalStreetGraph.StreetTier.PRIMARY
                                : (Math.abs(nx - cx) <= density.getRing1Radius())
                                ? CapitalStreetGraph.StreetTier.SECONDARY
                                : CapitalStreetGraph.StreetTier.TERTIARY;
                graph.connect(a, b, tier);
            }
        }

        return nodeByXZ;
    }

    // =========================================================================
    // Spoke overlay (ROUND only)
    // =========================================================================

    /**
     * Adds radial spoke avenues over the grid. Spokes connect the centre
     * node to the outermost grid node in each spoke direction, then add
     * a gate node beyond the grid boundary. This is cosmetic — spokes
     * do NOT participate in city block detection (they are diagonal or
     * near-diagonal relative to the grid).
     */
    private static void addSpokes(CapitalStreetGraph graph,
                                  int cx, int cz,
                                  LayoutDensityProfile density,
                                  int spokeCount, int gridSpacing,
                                  Random rng) {

        CapitalStreetGraph.StreetNode centreNode =
                graph.nearestNode(cx, cz, gridSpacing * 0.6);
        if (centreNode == null) {
            centreNode = graph.addNode(cx, cz, CapitalStreetGraph.StreetTier.PRIMARY);
        }

        double baseAngle = rng.nextDouble() * (Math.PI / spokeCount);
        int r3 = density.getRing3Radius();
        int r4 = r3 + (r3 - density.getRing2Radius());

        for (int s = 0; s < spokeCount; s++) {
            double angle = baseAngle + 2 * Math.PI * s / spokeCount;
            double cos = Math.cos(angle), sin = Math.sin(angle);

            // Find the outermost grid node roughly aligned with this spoke
            CapitalStreetGraph.StreetNode outerNode = null;
            double bestDot = -1;
            for (CapitalStreetGraph.StreetNode n : graph.getNodes()) {
                double dx = n.x - cx, dz = n.z - cz, d = Math.sqrt(dx*dx+dz*dz);
                if (d < gridSpacing * 0.5) continue;
                double dot = (dx * cos + dz * sin) / d;
                if (dot > 0.85 && d > bestDot) { bestDot = d; outerNode = n; }
            }
            if (outerNode == null) continue;

            // Gate node at r4
            int gx = cx + (int)(cos * r4), gz = cz + (int)(sin * r4);
            CapitalStreetGraph.StreetNode gateNode =
                    graph.nearestNode(gx, gz, gridSpacing * 0.5);
            if (gateNode == null) {
                gateNode = graph.addNode(gx, gz, CapitalStreetGraph.StreetTier.SECONDARY);
            }

            if (!segmentExists(graph, centreNode, outerNode)) {
                graph.connect(centreNode, outerNode, CapitalStreetGraph.StreetTier.PRIMARY);
            }
            if (!segmentExists(graph, outerNode, gateNode)) {
                graph.connect(outerNode, gateNode, CapitalStreetGraph.StreetTier.SECONDARY);
            }
        }
    }

    // =========================================================================
    // City block detection
    // =========================================================================

    private static void createCityBlocks(CapitalStreetGraph graph,
                                         Map<Long, CapitalStreetGraph.StreetNode> gridNodes,
                                         int minBlock, int maxBlock) {
        // Decode XZ from map keys (not from node.x/z — nodes may be shared)
        TreeSet<Integer> xs = new TreeSet<>(), zs = new TreeSet<>();
        for (long key : gridNodes.keySet()) {
            xs.add((int)((key >> 32) - 32768));
            zs.add((int)((key & 0xFFFFFFFFL) - 32768));
        }

        Integer[] xArr = xs.toArray(new Integer[0]);
        Integer[] zArr = zs.toArray(new Integer[0]);

        for (int xi = 0; xi < xArr.length - 1; xi++) {
            for (int zi = 0; zi < zArr.length - 1; zi++) {
                int x0 = xArr[xi], x1 = xArr[xi + 1];
                int z0 = zArr[zi], z1 = zArr[zi + 1];
                int w = x1 - x0, d = z1 - z0;

                if (w < minBlock || d < minBlock) continue;
                if (w > maxBlock || d > maxBlock) continue;

                CapitalStreetGraph.StreetNode sw = gridNodes.get(gridKey(x0, z0));
                CapitalStreetGraph.StreetNode se = gridNodes.get(gridKey(x1, z0));
                CapitalStreetGraph.StreetNode nw = gridNodes.get(gridKey(x0, z1));
                CapitalStreetGraph.StreetNode ne = gridNodes.get(gridKey(x1, z1));

                if (sw == null || se == null || nw == null || ne == null) continue;
                if (!segmentExists(graph, sw, se)) continue;
                if (!segmentExists(graph, nw, ne)) continue;
                if (!segmentExists(graph, sw, nw)) continue;
                if (!segmentExists(graph, se, ne)) continue;

                CapitalStreetGraph.CityBlock block =
                        new CapitalStreetGraph.CityBlock(x0, z0, x1, z1);
                graph.registerCityBlock(block);
            }
        }
    }

    // =========================================================================
    // District assignment
    // =========================================================================

    /**
     * Assigns each city block to a {@link CapitalStreetGraph.CapitalDistrict}
     * based on its distance from the city centre and its compass bearing.
     * This creates coherent neighbourhoods that players can learn to navigate.
     *
     * <pre>
     *   Dist < r1                         → PALACE_WARD
     *   r1..r2, south quadrant (−45..45°) → CIVIC_FORUM
     *   r1..r2, east quadrant  (45..135°) → CRAFT_QUARTER
     *   r1..r2, northwest      (225..315°)→ NOBLE_QUARTER
     *   r1..r2, other angles              → RESIDENTIAL (mid-ring default)
     *   dist > r2                         → RESIDENTIAL
     *   dist > r3                         → OUTER_WALL
     * </pre>
     *
     * Angles use atan2(dz, dx) where east=0°, north=−90° (standard math,
     * not Minecraft's inverted Z). The BuildingZone angular sectors use
     * the same convention so they align correctly.
     */
    private static void assignDistricts(CapitalStreetGraph graph,
                                        int cx, int cz,
                                        LayoutDensityProfile density) {
        double r1 = density.getRing1Radius();
        double r2 = density.getRing2Radius();
        double r3 = density.getRing3Radius();

        for (CapitalStreetGraph.CityBlock block : graph.getCityBlocks()) {
            double dx   = block.centreX() - cx;
            double dz   = block.centreZ() - cz;
            double dist = Math.sqrt(dx * dx + dz * dz);
            double angle = Math.toDegrees(Math.atan2(dz, dx)); // −180..180

            block.distFromCentre = dist;
            block.angleFromCentre = angle;

            CapitalStreetGraph.CapitalDistrict district;

            if (dist < r1) {
                district = CapitalStreetGraph.CapitalDistrict.PALACE_WARD;
            } else if (dist > r3) {
                district = CapitalStreetGraph.CapitalDistrict.OUTER_WALL;
            } else if (dist <= r2) {
                // Mid-ring — use angle to pick sector
                // South quadrant: dz > 0 and roughly equal magnitude → angle near 90°
                // Using atan2(dz,dx): south = ~+90°, east = ~0°, north = ~−90°, west = ~180°
                double absAngle = Math.abs(angle);
                if (dz > 0 && absAngle > 45 && absAngle < 135) {
                    // South quadrant (positive Z in Minecraft = south)
                    district = CapitalStreetGraph.CapitalDistrict.CIVIC_FORUM;
                } else if (dx > 0 && absAngle < 45) {
                    // East quadrant
                    district = CapitalStreetGraph.CapitalDistrict.CRAFT_QUARTER;
                } else if (dx < 0 && (angle < -135 || angle > 135)) {
                    // West quadrant — check if northwest (dz < 0) or southwest
                    district = dz < 0
                            ? CapitalStreetGraph.CapitalDistrict.NOBLE_QUARTER
                            : CapitalStreetGraph.CapitalDistrict.RESIDENTIAL;
                } else {
                    district = CapitalStreetGraph.CapitalDistrict.RESIDENTIAL;
                }
            } else {
                district = CapitalStreetGraph.CapitalDistrict.RESIDENTIAL;
            }

            block.district = district;
        }
    }

    // =========================================================================
    // Building assignment
    // =========================================================================

    /**
     * Assigns buildings to city block face plots respecting district and zone.
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li>Expand the building list by min/maxCount.</li>
     *   <li>Sort by zone priority (civic first, defensive last).</li>
     *   <li>For each building, find the best plot in the matching district.
     *       Use actual structure width from StructureSizeCache to pick
     *       plots that fit the building footprint.</li>
     *   <li>If no district match, fall back to any unoccupied plot.</li>
     * </ol>
     *
     * <h3>District–zone mapping</h3>
     * <pre>
     *   PALACE_WARD   → CIVIC (innermost)
     *   CIVIC_FORUM   → CIVIC, PRODUCTION (lighter)
     *   CRAFT_QUARTER → PRODUCTION
     *   NOBLE_QUARTER → CIVIC (wealthy), RESIDENTIAL
     *   RESIDENTIAL   → RESIDENTIAL
     *   OUTER_WALL    → DEFENSIVE
     * </pre>
     */
    private static void assignBuildings(CapitalStreetGraph graph,
                                        VillageTypeData typeData,
                                        StructureSizeCache sizeCache,
                                        Random rng, int cx, int cz) {
        // Expand building list
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

        // Pre-sort city blocks nearest-first for deterministic assignment
        List<CapitalStreetGraph.CityBlock> sortedBlocks =
                new ArrayList<>(graph.getCityBlocks());
        sortedBlocks.sort(Comparator.comparingDouble(b -> b.distFromCentre));

        int unassigned = 0;
        for (VillageTypeData.StarterBuilding sb : buildings) {
            BuildingType btype;
            try { btype = BuildingType.valueOf(sb.type()); }
            catch (IllegalArgumentException e) { continue; }

            ZoneRegistry.ZoneEntry zone = ZoneRegistry.get(btype);
            BuildingZone bzone = zone.zone();

            // Get the actual structure width so we don't place a 20-wide building
            // into a 12-wide plot
            StructureSizeCache.FootprintInfo info =
                    sizeCache.get(sb.structure(), Rotation.NONE);
            int neededW = info != null ? Math.max(info.width(), info.length()) : 12;

            // Preferred districts for this zone
            List<CapitalStreetGraph.CapitalDistrict> preferred =
                    preferredDistricts(bzone);

            CapitalStreetGraph.BlockFacePlot best = null;

            // Pass 1: preferred district + fits
            outer:
            for (CapitalStreetGraph.CapitalDistrict pref : preferred) {
                for (CapitalStreetGraph.CityBlock block : sortedBlocks) {
                    if (block.district != pref) continue;
                    for (CapitalStreetGraph.BlockFacePlot plot : block.plots) {
                        if (plot.occupied) continue;
                        if (plot.plotW < neededW - 4) continue;
                        best = plot;
                        break outer;
                    }
                }
            }

            // Pass 2: any district that fits
            if (best == null) {
                for (CapitalStreetGraph.CityBlock block : sortedBlocks) {
                    for (CapitalStreetGraph.BlockFacePlot plot : block.plots) {
                        if (plot.occupied) continue;
                        if (plot.plotW < neededW - 2) continue;
                        best = plot;
                        break;
                    }
                    if (best != null) break;
                }
            }

            if (best != null) {
                best.assignedType  = btype;
                best.structurePath = sb.structure();
                best.occupied      = true;
            } else {
                unassigned++;
            }
        }

        if (unassigned > 0) {
            System.out.println("CapitalLayoutPlanner: WARNING — "
                    + unassigned + " buildings unassigned (not enough plots). "
                    + "Reduce gridSpacing or increase buildingsPerFace.");
        }
    }

    /** Returns the preferred districts for a given zone, in priority order. */
    private static List<CapitalStreetGraph.CapitalDistrict> preferredDistricts(
            BuildingZone zone) {
        return switch (zone) {
            case CIVIC -> List.of(
                    CapitalStreetGraph.CapitalDistrict.PALACE_WARD,
                    CapitalStreetGraph.CapitalDistrict.CIVIC_FORUM,
                    CapitalStreetGraph.CapitalDistrict.NOBLE_QUARTER);
            case PRODUCTION -> List.of(
                    CapitalStreetGraph.CapitalDistrict.CRAFT_QUARTER,
                    CapitalStreetGraph.CapitalDistrict.CIVIC_FORUM);
            case RESIDENTIAL -> List.of(
                    CapitalStreetGraph.CapitalDistrict.RESIDENTIAL,
                    CapitalStreetGraph.CapitalDistrict.NOBLE_QUARTER);
            case DEFENSIVE -> List.of(
                    CapitalStreetGraph.CapitalDistrict.OUTER_WALL,
                    CapitalStreetGraph.CapitalDistrict.RESIDENTIAL);
            case AGRICULTURAL -> List.of(
                    CapitalStreetGraph.CapitalDistrict.RESIDENTIAL,
                    CapitalStreetGraph.CapitalDistrict.OUTER_WALL);
        };
    }

    // =========================================================================
    // Commit to VillageLayout
    // =========================================================================

    /**
     * Converts assigned face plots into LayoutSlots using addForced —
     * plots are geometrically pre-validated, bypassing the ring-planner's
     * 22-block rejection threshold.
     */
    private static void commitToLayout(VillageLayout layout,
                                       CapitalStreetGraph graph,
                                       ServerLevel level) {
        for (CapitalStreetGraph.CityBlock block : graph.getCityBlocks()) {
            for (CapitalStreetGraph.BlockFacePlot plot : block.plots) {
                if (!plot.occupied || plot.assignedType == null) continue;

                int surfY = level.getHeight(
                        Heightmap.Types.WORLD_SURFACE, plot.x, plot.z);
                if (surfY <= level.getMinY() + 2) surfY = layout.getCenter().getY();

                BlockPos pos = new BlockPos(plot.x, surfY, plot.z);
                // LayoutSlotWithRotation carries the face-derived rotation so
                // VillageSpawner uses it instead of recomputing toward town square
                LayoutSlot.LayoutSlotWithRotation slot = new LayoutSlot.LayoutSlotWithRotation(
                        pos, plot.assignedType, plot.structurePath,
                        StructureSizeCache.DEFAULT_RADIUS,
                        plot.facingRotation());
                layout.addForced(slot);
            }
        }
    }

    // =========================================================================
    // Gate positions
    // =========================================================================

    private static void storeGatePositions(VillageLayout layout,
                                           CapitalStreetGraph graph,
                                           int cx, int cz,
                                           LayoutDensityProfile density,
                                           int gridSpacing) {
        double r3 = density.getRing3Radius();
        double[] angles = {0, Math.PI/4, Math.PI/2, 3*Math.PI/4,
                Math.PI, 5*Math.PI/4, 3*Math.PI/2, 7*Math.PI/4};

        for (double angle : angles) {
            double cos = Math.cos(angle), sin = Math.sin(angle);
            CapitalStreetGraph.StreetNode best = null;
            double bestDot = 0.6;
            for (CapitalStreetGraph.StreetNode n : graph.getNodes()) {
                double dx = n.x - cx, dz = n.z - cz;
                double d  = Math.sqrt(dx*dx + dz*dz);
                if (d < r3 * 0.6) continue;
                double dot = (dx * cos + dz * sin) / Math.max(d, 1);
                if (dot > bestDot) { bestDot = dot; best = n; }
            }
            if (best != null) {
                layout.addGatePosition(
                        new BlockPos(best.x, layout.getCenter().getY(), best.z));
            }
        }
        System.out.println("CapitalLayoutPlanner: registered "
                + layout.getGatePositions().size() + " gate positions");
    }

    // =========================================================================
    // Town hall, town square, farm plots
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
        if (info != null) slot.setFootprint(info.width(), info.length());
        layout.tryAdd(slot);
    }

    private static void placeTownSquare(VillageLayout layout,
                                        BlockPos centre,
                                        LayoutDensityProfile density,
                                        ServerLevel level,
                                        Random rng) {
        // Place the square between the palace ward and civic forum —
        // at the point where the two PRIMARY axes cross, offset slightly south
        int squareDist = Math.max(density.getRing1Radius() / 3, 14);
        int sx = centre.getX(), sz = centre.getZ() + squareDist;
        int sy = level.getHeight(Heightmap.Types.WORLD_SURFACE, sx, sz);
        if (sy <= level.getMinY() + 2) sy = centre.getY();
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
            int sy = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                    ideal.getX(), ideal.getZ());
            if (sy <= level.getMinY() + 2) sy = layout.getCenter().getY();
            layout.tryAdd(new LayoutSlot(LayoutSlot.SlotType.FARM_PLOT,
                    new BlockPos(ideal.getX(), sy, ideal.getZ()), 10));
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static boolean segmentExists(CapitalStreetGraph graph,
                                         CapitalStreetGraph.StreetNode a,
                                         CapitalStreetGraph.StreetNode b) {
        for (var s : graph.getSegments()) {
            if ((s.a == a && s.b == b) || (s.a == b && s.b == a)) return true;
        }
        return false;
    }

    private static long gridKey(int x, int z) {
        return ((long)(x + 32768)) << 32 | ((z + 32768) & 0xFFFFFFFFL);
    }

    private static int[] flatDir(TerrainAnalyzer.FlatDirection dir) {
        return switch (dir) {
            case NORTH -> new int[]{ 0, -1};
            case SOUTH -> new int[]{ 0,  1};
            case EAST  -> new int[]{ 1,  0};
            case WEST  -> new int[]{-1,  0};
        };
    }
}