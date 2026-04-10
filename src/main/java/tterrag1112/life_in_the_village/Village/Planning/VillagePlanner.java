// src/main/java/tterrag1112/life_in_the_village/Village/Planning/VillagePlanner.java
package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.Inhabitants.BuildingInhabitantRegistry;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.TrunkGraph;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.TrunkRoadPlanner;
import tterrag1112.life_in_the_village.Village.Decoration.TownSquarePlacer;
import tterrag1112.life_in_the_village.Village.Planning.Rules.RuleContext;
import tterrag1112.life_in_the_village.Village.Planning.Rules.ShapeRule;
import tterrag1112.life_in_the_village.Village.VillageTypeData;
import tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.*;

/**
 * Produces a {@link VillageLayout} from a {@link VillageTypeData} and the
 * surrounding terrain. Phase 4b — fully rule-driven.
 *
 * <h3>Architecture</h3>
 * The planner no longer branches on {@link ShapeType}. Instead, it:
 * <ol>
 *   <li>Analyses the terrain into a {@link TerrainProfile}.</li>
 *   <li>Builds a {@link RuleContext} and applies every {@link ShapeRule}
 *       from the village type's rule stack. Rules populate the context
 *       with anchors, axes, zone assignments, adjacency requirements,
 *       density, and the walled flag.</li>
 *   <li>Runs a single unified placement pass that reads from the context.
 *       The placement algorithm is the same for every shape — only the
 *       rule outputs differ.</li>
 *   <li>Runs the orthogonal post-passes (farm plots, decoration clusters,
 *       water-edge buildings, Y clustering) that were already in place.</li>
 * </ol>
 *
 * <h3>Capitals</h3>
 * Capital-tier villages still delegate to {@link CapitalLayoutPlanner}.
 * That subsystem has its own pipeline and is out of scope for this rewrite.
 *
 * <h3>Adding a new shape</h3>
 * No code changes. Author a {@code shape_rules} array in the village type
 * JSON. The rule types currently supported are: {@code anchor}, {@code axis},
 * {@code zone}, {@code adjacency}, {@code density}, {@code walled}.
 * If a shape needs a kind of rule that doesn't exist yet, add the rule
 * type, register its parser, and the planner will automatically pick it
 * up via {@link RuleContext}.
 */
public class VillagePlanner {

    // ── Tunables ─────────────────────────────────────────────────────────────

    private static final int DEFAULT_BUILDING_RADIUS = 8;
    private static final int FARM_PLOT_RADIUS        = 10;
    private static final int DECORATION_RADIUS       = 3;
    private static final int MAX_TERRAIN_ATTEMPTS    = 32;

    /** Angular jitter (degrees) when no axis is set — used for radial layouts. */
    private static final double RADIAL_JITTER_DEG = 8;

    /** How many angular offsets to try when a building's preferred slot collides. */
    private static final double[] PLACEMENT_OFFSETS_DEG = {
            0, 12, -12, 24, -24, 36, -36, 60, -60, 90, -90, 135, -135, 180
    };

    // =========================================================================
    // ENTRY POINT
    // =========================================================================

    public static Optional<VillageLayout> plan(
            ServerLevel level, BlockPos origin,
            VillageTypeData typeData, Random rng, int villageLevel) {

        // ── Step 1: terrain ─────────────────────────────────────────────────
        TerrainProfile terrain = TerrainAnalyzer.analyze(level, origin);
        if (!terrain.isSuitable()) {
            System.out.println("VillagePlanner: unsuitable terrain at " + origin);
            return Optional.empty();
        }

        // ── Step 2: expand the building list (respect min/max counts) ───────
        List<VillageTypeData.StarterBuilding> expanded =
                expandBuildingList(typeData.getStarterBuildings(), rng);
        int buildingCount = expanded.size();

        // ── Step 3: density profile + sizing infrastructure ─────────────────
        boolean isCapital = buildingCount >= LayoutDensityProfile.CAPITAL_THRESHOLD;
        LayoutDensityProfile density = isCapital
                ? LayoutDensityProfile.forCapital(buildingCount)
                : LayoutDensityProfile.forLevel(villageLevel);

        StructureSizeCache sizeCache = new StructureSizeCache(level);


        // ── Step 5: build and apply the rule stack ──────────────────────────
        RuleContext ctx = new RuleContext(terrain, origin, rng.nextLong());
        for (ShapeRule rule : typeData.getShapeRules()) {
            try {
                rule.apply(ctx);
            } catch (Exception e) {
                System.out.println("VillagePlanner: rule '" + rule.typeName()
                        + "' failed: " + e.getMessage());
            }
        }

        // ── Step 6: unified placement pass ──────────────────────────────────
        VillageLayout layout = unifiedPlacement(
                level, terrain, ctx, expanded, typeData,
                density, sizeCache, rng);

        // ── Step 7: orthogonal post-passes ──────────────────────────────────
        if (terrain.hasWater()) {
            placeWaterEdgeBuildings(layout, level, typeData,
                    layout.getCenter(), terrain, rng);
        }
        clusterBuildingYLevels(layout, level);
        placeFarmPlots(layout, level, terrain, density, typeData, rng);
        placeDecorationClusters(layout, level, terrain, density, rng);

        System.out.println("VillagePlanner: planned village (" + buildingCount
                + " buildings, axis="
                + (ctx.axisRad() != null
                ? Math.toDegrees(ctx.axisRad()) + "°"
                : "none")
                + ", walled=" + ctx.forceWalled() + ") — " + layout);
        return Optional.of(layout);
    }

    // =========================================================================
    // UNIFIED PLACEMENT
    // =========================================================================

    /**
     * The single placement algorithm that replaces all seven legacy
     * {@code planX} methods.
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li>Resolve the village centre from the {@code town_square} anchor
     *       if set, otherwise snap to the nearest flat candidate.</li>
     *   <li>Place the town hall at the centre.</li>
     *   <li>For every other building, compute a target position from its
     *       zone (ring + sector) and the village axis.</li>
     *   <li>Apply adjacency constraints — if a building requires river or
     *       coast, its target moves to the nearest cell that satisfies it,
     *       or the building is dropped if none exists.</li>
     *   <li>Resolve the target onto actual terrain with footprint-aware
     *       overlap testing and angular retries.</li>
     *   <li>Place the town square as a decoration slot.</li>
     * </ol>
     */
    private static VillageLayout unifiedPlacement(
            ServerLevel level,
            TerrainProfile terrain,
            RuleContext ctx,
            List<VillageTypeData.StarterBuilding> expanded,
            VillageTypeData typeData,
            LayoutDensityProfile density,
            StructureSizeCache sizeCache,
            Random rng) {

        VillageLayout layout = new VillageLayout(terrain, density);

        // ── Resolve the centre ──────────────────────────────────────────────
        BlockPos centre = resolveCentre(level, terrain, ctx);
        layout.setCenter(centre);

        // ── Sort: town hall first, then by zone priority ───────────────────
        List<VillageTypeData.StarterBuilding> sorted = sortBuildings(expanded);
        List<LayoutSlot> placed = new ArrayList<>();

        // ── Place the town hall at the centre ──────────────────────────────
        VillageTypeData.StarterBuilding townHallEntry = null;

        TrunkGraph trunkGraph = TrunkRoadPlanner.plan(
                level, centre,
                typeData.getShapeProfile().shapeType(),
                layout.getDensity(),
                ctx);
        layout.setTrunkGraph(trunkGraph);
        // ── Build clusters and place each as a unit ─────────────────────────
        List<BuildingCluster> clusters = buildClusters(sorted, townHallEntry);

        for (BuildingCluster cluster : clusters) {
            // Pick the cluster's anchor position using zone-default ring/angle
            BuildingType firstMemberType = parseBuildingType(cluster.getMembers().get(0));
            int ring = radiusForRing(layout.getDensity(),
                    defaultRingFor(firstMemberType != null
                            ? firstMemberType
                            : BuildingType.HOUSE));
            // Pick a provisional anchor on the ring, then snap it to the
            // nearest trunk road position. Cluster members will be
            // distributed along the trunk from this snapped anchor.
            double anchorAngle = (cluster.getClusterId() * 360.0
                    / Math.max(1, clusters.size())) + rng.nextInt(20) - 10;
            BlockPos provisional = ringPoint(centre, ring, anchorAngle);
            BlockPos clusterAnchor = trunkGraph.isEmpty()
                    ? provisional
                    : trunkGraph.nearestPointOnAnyEdge(provisional);
            if (clusterAnchor == null) clusterAnchor = provisional;
            cluster.setAnchor(clusterAnchor);

            placeCluster(level, layout, terrain, ctx, cluster,
                    sorted, density, sizeCache, rng, placed);
        }
        if (townHallEntry != null) {
            placed.add(placeTownHallSlot(layout, centre, townHallEntry, sizeCache));
        }

        // ── Place every other building via the unified placer ──────────────
        for (VillageTypeData.StarterBuilding sb : sorted) {
            if (sb == townHallEntry) continue;
            BuildingType btype = parseBuildingType(sb);
            if (btype == null) continue;

            placeOneBuilding(level, layout, terrain, ctx,
                    sb, btype, density, sizeCache, rng, placed);
        }

        // ── Town square ─────────────────────────────────────────────────────
        // The town_square anchor IS the centre when set; otherwise the
        // square sits a short distance south of the town hall.
        BlockPos squarePos = ctx.getAnchor("town_square") != null
                ? ctx.getAnchor("town_square")
                : centre.offset(0, 0, Math.max(density.getRing1Radius() / 3, 6));
        squarePos = solidSurface(level, squarePos);
        layout.addForced(new LayoutSlot(
                LayoutSlot.SlotType.DECORATION, squarePos,
                TownSquarePlacer.RADIUS + 2));
        layout.setTownSquarePos(squarePos);

        refineRotations(layout);

        return layout;
    }

    // -------------------------------------------------------------------------
    // Placement of a single non-town-hall building
    // -------------------------------------------------------------------------

    /**
     * Decides where one building should go and registers it with the layout.
     *
     * <p>Reads zone and adjacency constraints from {@link RuleContext}.
     * Falls back to {@link ZoneRegistry} for buildings that no rule has
     * explicitly assigned a zone to.
     */
    private static void placeOneBuilding(
            ServerLevel level,
            VillageLayout layout,
            TerrainProfile terrain,
            RuleContext ctx,
            VillageTypeData.StarterBuilding sb,
            BuildingType btype,
            LayoutDensityProfile density,
            StructureSizeCache sizeCache,
            Random rng,
            List<LayoutSlot> placed) {

        BlockPos centre = layout.getCenter();

        // ── Resolve zone from rules, else fall back to ZoneRegistry ─────────
        RuleContext.ZoneSpec ruleZone = ctx.getZone(btype);
        int radius = radiusForRing(density,
                ruleZone != null ? ruleZone.ring() : defaultRingFor(btype));

        // ── Compute the building's preferred angle ──────────────────────────
        // If the rule context has an angular sector, pick a random angle
        // within it. Otherwise, distribute around the village.
        double preferredDeg = pickPreferredAngle(ruleZone, ctx, placed.size(), rng);

        // ── Compute the ideal world position ────────────────────────────────
        BlockPos ideal = ringPoint(centre, radius, preferredDeg);

        // ── Apply adjacency constraints ─────────────────────────────────────
        // ── Resolve effective adjacency requirements ─────────────────────────
        // Combine village-type rules (RuleContext) with building-intrinsic
        // rules (BuildingInhabitantRegistry). Rule-context entries take
        // precedence — if both sources mention the same feature, only the
        // rule-context version is applied. This lets a village type override
        // a building's intrinsic requirements when needed.
        List<RuleContext.AdjacencyReq> reqs =
                effectiveAdjacencyReqs(ctx, btype);

        if (!reqs.isEmpty()) {
            BlockPos adjusted = applyAdjacency(level, ideal, reqs, rng);
            if (adjusted == null) {
                System.out.println("VillagePlanner: dropping " + btype
                        + " — adjacency requirement(s) unsatisfied: " + reqs);
                return;
            }
            ideal = adjusted;
        }

        // ── Resolve onto actual terrain with retries ────────────────────────
        LayoutSlot slot = resolveAndPlace(level, layout, ideal,
                preferredDeg, radius, sb, btype, sizeCache, density, rng, placed);

        if (slot != null) {
            placed.add(slot);
        } else {
            System.out.println("VillagePlanner: WARN — could not place " + btype
                    + " (preferred ring r=" + radius + ", angle=" + preferredDeg + "°)");
        }
    }

    // -------------------------------------------------------------------------
    // Centre resolution
    // -------------------------------------------------------------------------

    private static BlockPos resolveCentre(ServerLevel level,
                                          TerrainProfile terrain,
                                          RuleContext ctx) {
        BlockPos anchor = ctx.getAnchor("town_square");
        if (anchor != null) {
            return solidSurface(level, anchor);
        }
        return snapToFlat(level, terrain, terrain.origin());
    }

    // -------------------------------------------------------------------------
    // Ring & angle math
    // -------------------------------------------------------------------------

    /**
     * Returns the world position on a ring at a given radius and degree
     * angle, snapped to the surface Y. The angle is interpreted in the
     * standard math convention (counterclockwise from +X / east).
     */
    private static BlockPos ringPoint(BlockPos centre, int radius, double angleDeg) {
        double rad = Math.toRadians(angleDeg);
        int x = centre.getX() + (int) (Math.cos(rad) * radius);
        int z = centre.getZ() + (int) (Math.sin(rad) * radius);
        return new BlockPos(x, centre.getY(), z);
    }

    /**
     * Picks a preferred placement angle for a building.
     *
     * <p>Order of precedence:
     * <ol>
     *   <li>If the building's zone has an angular sector, pick a random
     *       angle uniformly within it.</li>
     *   <li>If the village has an axis, distribute buildings perpendicular
     *       to it (linear-style — they fan out along the axis).</li>
     *   <li>Otherwise, distribute around the full circle by index, with a
     *       small random jitter so identical seeds don't overlap.</li>
     * </ol>
     */
    private static double pickPreferredAngle(
            RuleContext.ZoneSpec ruleZone,
            RuleContext ctx,
            int alreadyPlacedCount,
            Random rng) {

        // 1. Sector wins
        if (ruleZone != null && ruleZone.sector() != null) {
            int s = ruleZone.sector().startDeg();
            int e = ruleZone.sector().endDeg();
            // Handle wrap-around sectors
            if (s > e) e += 360;
            double pick = s + rng.nextDouble() * (e - s);
            return ((pick % 360) + 360) % 360;
        }

        // 2. Axis-aware: spread along the perpendicular of the axis
        if (ctx.axisRad() != null) {
            // Half the buildings on each side, alternating
            double axisDeg = Math.toDegrees(ctx.axisRad());
            // Index 0 → 90° offset (one side), 1 → -90° (other), 2 → 90° + step, etc.
            int side = (alreadyPlacedCount % 2 == 0) ? 1 : -1;
            int step = alreadyPlacedCount / 2;
            double offset = side * (90.0 - step * 8.0);
            return ((axisDeg + offset) % 360 + 360) % 360;
        }

        // 3. Radial fallback — distribute around the circle by index
        double base = (alreadyPlacedCount * 360.0) / Math.max(1, alreadyPlacedCount + 6);
        double jitter = (rng.nextDouble() - 0.5) * 2 * RADIAL_JITTER_DEG;
        return ((base + jitter) % 360 + 360) % 360;
    }

    private static int radiusForRing(LayoutDensityProfile density,
                                     RuleContext.Ring ring) {
        return switch (ring) {
            case INNER  -> density.getRing1Radius();
            case MIDDLE -> (density.getRing1Radius() + density.getRing2Radius()) / 2;
            case OUTER  -> density.getRing2Radius();
        };
    }

    private static RuleContext.Ring defaultRingFor(BuildingType type) {
        BuildingZone zone = ZoneRegistry.zoneOf(type);
        return zone.preferredRing <= 1 ? RuleContext.Ring.INNER : RuleContext.Ring.OUTER;
    }

    // -------------------------------------------------------------------------
    // Adjacency
    // -------------------------------------------------------------------------

    /**
     * Applies adjacency constraints to a target position. Returns the
     * adjusted position, or null if no required constraint can be satisfied.
     *
     * <p>For now this consults the {@link WorldAtlas}: river/coast/water
     * features are exactly what the atlas tracks per cell. If the atlas
     * isn't filled around the target, falls back to a small radial scan
     * using the level directly.
     */
    private static BlockPos applyAdjacency(ServerLevel level,
                                           BlockPos target,
                                           List<RuleContext.AdjacencyReq> reqs,
                                           Random rng) {
        WorldAtlas atlas = WorldAtlas.get(level);

        for (RuleContext.AdjacencyReq req : reqs) {
            boolean satisfied = checkFeatureAtlas(atlas, target, req.feature());
            if (satisfied) continue;

            // Search nearby cells for a satisfying position
            BlockPos found = findFeatureNearby(atlas, target,
                    req.feature(), req.maxDist());
            if (found != null) {
                target = new BlockPos(found.getX(), target.getY(), found.getZ());
            } else if (req.required()) {
                return null;
            }
        }
        return target;
    }

    private static boolean checkFeatureAtlas(WorldAtlas atlas,
                                             BlockPos pos, String feature) {
        AtlasCell cell = atlas.getCellAtBlock(pos.getX(), pos.getZ());
        if (cell == null) return false;
        return switch (feature) {
            case "river"  -> cell.isRiverAdj() || cell.isFreshwater();
            case "coast"  -> cell.isCoast();
            case "water"  -> cell.isCoast() || cell.isRiverAdj() || cell.isFreshwater();
            case "forest" -> cell.category() == BiomeCategory.FOREST;
            default        -> false;
        };
    }

    private static BlockPos findFeatureNearby(WorldAtlas atlas, BlockPos centre,
                                              String feature, int maxDist) {
        // Atlas cells are 64 blocks; expand search radius accordingly
        var nearby = atlas.cellsWithinRadius(
                centre.getX(), centre.getZ(), maxDist + AtlasCell.CELL_SIZE);

        AtlasCell best = null;
        long bestDistSq = Long.MAX_VALUE;
        for (AtlasCell cell : nearby) {
            boolean hit = switch (feature) {
                case "river"  -> cell.isRiverAdj() || cell.isFreshwater();
                case "coast"  -> cell.isCoast();
                case "water"  -> cell.isCoast() || cell.isRiverAdj() || cell.isFreshwater();
                case "forest" -> cell.category() == BiomeCategory.FOREST;
                default        -> false;
            };
            if (!hit) continue;
            long dx = cell.blockCenterX() - centre.getX();
            long dz = cell.blockCenterZ() - centre.getZ();
            long dSq = dx * dx + dz * dz;
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = cell;
            }
        }
        if (best == null) return null;
        return new BlockPos(best.blockCenterX(),
                centre.getY(), best.blockCenterZ());
    }

    // -------------------------------------------------------------------------
    // Resolve and place — terrain + footprint + retry
    // -------------------------------------------------------------------------

    /**
     * Tries to place a building at its preferred angle, then at a series
     * of angular offsets, performing footprint-aware overlap testing
     * and terrain validation at each candidate position.
     *
     * <h3>Rotation handling</h3>
     * Rotation is decided FIRST, before footprint dimensions are looked
     * up. The cache is then queried with the chosen rotation so width
     * and length are correct for the actual placement orientation.
     */
    private static LayoutSlot resolveAndPlace(
            ServerLevel level,
            VillageLayout layout,
            BlockPos ideal,
            double preferredDeg,
            int radius,
            VillageTypeData.StarterBuilding sb,
            BuildingType btype,
            StructureSizeCache sizeCache,
            LayoutDensityProfile density,
            Random rng,
            List<LayoutSlot> placed) {

        BlockPos centre = layout.getCenter();
        int minGap = VillageLayout.MIN_BUILDING_GAP;

        // Try the ideal position first, then sweep through angular offsets.
        for (double offsetDeg : PLACEMENT_OFFSETS_DEG) {
            BlockPos target = (offsetDeg == 0)
                    ? ideal
                    : ringPoint(centre, radius, preferredDeg + offsetDeg);

            // ── Step 1: decide rotation FIRST (face the centre/square) ──────
            Rotation rotation = chooseFacingRotation(target, centre);

            // ── Step 2: ask the cache for footprint AT that rotation ────────
            StructureSizeCache.FootprintInfo info =
                    sizeCache.get(sb.structure(), rotation);
            int w = info != null ? info.width()  : 12;
            int l = info != null ? info.length() : 12;
            int slotRadius = Math.max(w, l) / 2 + 1;

            // ── Step 3: resolve onto terrain ────────────────────────────────
            BlockPos resolved = resolveOnTerrain(level, layout, target,
                    density.getBuildingJitter(), slotRadius, rng,
                    ZoneRegistry.zoneOf(btype) == BuildingZone.AGRICULTURAL);
            if (resolved == null) continue;

            // ── Step 4: footprint overlap check against placed slots ────────
            if (overlapsAny(resolved, w, l, placed, minGap)) continue;

            LayoutSlot slot = new LayoutSlot(
                    resolved, btype, sb.structure(), slotRadius, rotation);
            slot.setFootprint(w, l);
            slot.setPadY(computePadY(level, resolved, w, l));
            if (layout.tryAdd(slot)) return slot;
        }

        // ── Last-resort spiral outward — same shape, with rotation ──────────
        for (int extraR = radius + 20; extraR <= radius + 200; extraR += 15) {
            BlockPos target = ringPoint(centre, extraR,
                    preferredDeg + rng.nextInt(60));
            Rotation rotation = chooseFacingRotation(target, centre);

            StructureSizeCache.FootprintInfo info =
                    sizeCache.get(sb.structure(), rotation);
            int w = info != null ? info.width()  : 12;
            int l = info != null ? info.length() : 12;
            int slotRadius = Math.max(w, l) / 2 + 1;

            BlockPos resolved = resolveOnTerrain(level, layout, target,
                    density.getBuildingJitter(), slotRadius, rng,
                    ZoneRegistry.zoneOf(btype) == BuildingZone.AGRICULTURAL);
            if (resolved == null) continue;
            if (overlapsAny(resolved, w, l, placed, minGap)) continue;

            LayoutSlot slot = new LayoutSlot(
                    resolved, btype, sb.structure(), slotRadius, rotation);
            slot.setFootprint(w, l);


            layout.addForced(slot);
            return slot;
        }

        return null;
    }

    private static boolean overlapsAny(BlockPos pos, int w, int l,
                                       List<LayoutSlot> placed, int minGap) {
        for (LayoutSlot existing : placed) {
            int eW = existing.getFootprintWidth();
            int eL = existing.getFootprintLength();
            if (eW > 0 && eL > 0) {
                if (VillageLayout.footprintOverlap(
                        pos, w, l, existing.getPos(), eW, eL, minGap)) {
                    return true;
                }
            } else {
                double minDist = (Math.max(w, l) / 2.0)
                        + existing.getRadius() + minGap;
                if (pos.distSqr(existing.getPos()) < minDist * minDist) return true;
            }
        }
        return false;
    }

    // =========================================================================
    // SHARED HELPERS — town hall, building list, sorting, parsing
    // =========================================================================

    private static LayoutSlot placeTownHallSlot(VillageLayout layout,
                                                BlockPos pos,
                                                VillageTypeData.StarterBuilding sb,
                                                StructureSizeCache sizeCache) {
        StructureSizeCache.FootprintInfo info =
                sizeCache.get(sb.structure(), Rotation.NONE);
        int w = info != null ? info.width()  : 12;
        int l = info != null ? info.length() : 12;
        int slotR = Math.max(w, l) / 2 + 1;

        LayoutSlot slot = new LayoutSlot(pos, BuildingType.TOWN_HALL,
                sb.structure(), slotR);
        slot.setFootprint(w, l);
        layout.tryAdd(slot);
        return slot;
    }

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
    // ORTHOGONAL POST-PASSES (unchanged from previous version)
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

        // Hilltop villages historically opted out of farms; preserve that.
        if (typeData.getShapeProfile().shapeType() == ShapeType.HILLTOP) return;

        int[] dir = flatDirVector(terrain.bestFlatDir());
        int perpX = dir[1], perpZ = -dir[0];
        int perimeterRadius = density.getRing2Radius() + 12;
        BlockPos centre = layout.getCenter();

        for (int i = 0; i < farmhouseCount; i++) {
            int sideOffset = (i - (int) farmhouseCount / 2)
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
            int dx = (int) (Math.cos(angle) * gapRadius);
            int dz = (int) (Math.sin(angle) * gapRadius);

            BlockPos ideal = terrain.bestFlatNear(dx, dz);
            BlockPos resolved = resolveOnTerrain(level, layout, ideal,
                    6, DECORATION_RADIUS, rng);
            if (resolved != null) {
                layout.addForced(new LayoutSlot(
                        LayoutSlot.SlotType.DECORATION, resolved, DECORATION_RADIUS));
            }
        }
    }

    private static void placeWaterEdgeBuildings(VillageLayout layout,
                                                ServerLevel level,
                                                VillageTypeData typeData,
                                                BlockPos centre,
                                                TerrainProfile terrain,
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
            String path = findBuildingPath(typeData,
                    BuildingType.DOCKS, "docks/level_1");
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
            String path = findBuildingPath(typeData,
                    BuildingType.MILLER, "miller/level_1");
            layout.tryAdd(new LayoutSlot(millPos, BuildingType.MILLER,
                    path, DEFAULT_BUILDING_RADIUS));
        }
    }

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
    // TERRAIN RESOLUTION
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

        for (int a = 0; a < MAX_TERRAIN_ATTEMPTS / 2; a++) {
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
        for (int ring = 1; ring <= MAX_TERRAIN_ATTEMPTS / 2; ring++) {
            for (int[] off : new int[][]{
                    {ring, 0}, {-ring, 0}, {0, ring}, {0, -ring},
                    {ring, ring}, {-ring, ring}, {ring, -ring}, {-ring, -ring}}) {
                candidate = solidSurface(level,
                        ideal.offset(off[0] * step, 0, off[1] * step));
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
    // SMALL UTILITIES
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
    /**
     * Chooses the rotation that makes the building face toward {@code target}.
     * Mirrors {@code VillageSpawner.chooseFacingRotation} so the planner
     * and spawner agree on which side is the entrance.
     */
    private static Rotation chooseFacingRotation(BlockPos buildPos, BlockPos target) {
        int dx = target.getX() - buildPos.getX();
        int dz = target.getZ() - buildPos.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0
                    ? Rotation.COUNTERCLOCKWISE_90
                    : Rotation.CLOCKWISE_90;
        } else {
            return dz > 0
                    ? Rotation.NONE
                    : Rotation.CLOCKWISE_180;
        }
    }
    /**
     * Returns the effective adjacency requirements for a building type by
     * merging village-type rules with building-intrinsic specs.
     *
     * <p>Village-type rules win on feature collisions. If the rule context
     * already says something about {@code river}, the intrinsic
     * {@code river} requirement is suppressed even if it has a different
     * distance or required flag.
     *
     * <p>Features mentioned only by the intrinsic spec are added as-is.
     */
    private static List<RuleContext.AdjacencyReq> effectiveAdjacencyReqs(
            RuleContext ctx,
            BuildingType btype) {

        List<RuleContext.AdjacencyReq> ruleReqs = ctx.getAdjacencyReqs(btype);
        var intrinsicSpec = BuildingInhabitantRegistry.getAdjacency(btype);

        if (intrinsicSpec.isEmpty()) return ruleReqs;
        if (ruleReqs.isEmpty()) return intrinsicSpec.getRequirements();

        // Both sources present — merge with rule-context taking precedence
        java.util.Set<String> covered = new java.util.HashSet<>();
        for (RuleContext.AdjacencyReq r : ruleReqs) covered.add(r.feature());

        List<RuleContext.AdjacencyReq> merged = new java.util.ArrayList<>(ruleReqs);
        for (RuleContext.AdjacencyReq intrinsic : intrinsicSpec.getRequirements()) {
            if (!covered.contains(intrinsic.feature())) {
                merged.add(intrinsic);
            }
        }
        return merged;
    }
    /**
     * Computes the target pad Y for a building by sampling the terrain
     * across its footprint and taking the median. Median is used rather
     * than mean to avoid outlier columns (a single steep corner) from
     * dragging the pad off the surrounding elevation.
     *
     * <p>This is called during planning so the slot has its committed
     * pad Y before any terrain modification happens. The terrain prep
     * step reads this value and makes the world match.
     */
    private static int computePadY(ServerLevel level, BlockPos centre,
                                   int width, int length) {
        int minX = centre.getX() - width / 2;
        int maxX = centre.getX() + width / 2;
        int minZ = centre.getZ() - length / 2;
        int maxZ = centre.getZ() + length / 2;

        java.util.List<Integer> samples = new java.util.ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                samples.add(level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
            }
        }
        java.util.Collections.sort(samples);
        return samples.get(samples.size() / 2);
    }

    private static List<BuildingCluster> buildClusters(
            List<VillageTypeData.StarterBuilding> sorted,
            VillageTypeData.StarterBuilding townHallEntry) {

        Map<BuildingZone, BuildingCluster> byZone = new EnumMap<>(BuildingZone.class);
        int nextId = 0;
        List<BuildingCluster> clusters = new ArrayList<>();

        for (VillageTypeData.StarterBuilding sb : sorted) {
            if (sb == townHallEntry) continue;
            if ("TOWN_HALL".equals(sb.type())) continue;
            BuildingType bt = parseBuildingType(sb);
            if (bt == null) continue;

            BuildingZone zone = ZoneRegistry.zoneOf(bt);
            BuildingCluster cluster = byZone.get(zone);
            if (cluster == null || cluster.getMembers().size() >= 5) {
                cluster = new BuildingCluster(
                        nextId++, zone, BuildingCluster.defaultShapeFor(zone));
                byZone.put(zone, cluster);
                clusters.add(cluster);
            }
            cluster.addMember(sb);  // ← StarterBuilding, not BuildingType
        }
        return clusters;
    }
    private static void placeCluster(
            ServerLevel level, VillageLayout layout, TerrainProfile terrain,
            RuleContext ctx, BuildingCluster cluster,
            List<VillageTypeData.StarterBuilding> sorted,
            LayoutDensityProfile density, StructureSizeCache sizeCache,
            Random rng, List<LayoutSlot> placed) {

        BlockPos anchor = cluster.getAnchor();
        int memberCount = cluster.getMembers().size();
        int memberIdx = 0;

        for (VillageTypeData.StarterBuilding sb : cluster.getMembers()) {
            BuildingType bt = parseBuildingType(sb);
            if (bt == null) continue;

            BlockPos memberPos = computeClusterMemberPos(
                    cluster.getShape(), anchor, ctx, memberIdx, memberCount, rng);

            int radius = radiusForRing(density,
                    ctx.getZone(bt) != null ? ctx.getZone(bt).ring()
                            : defaultRingFor(bt));
            double preferredDeg = pickPreferredAngle(
                    ctx.getZone(bt), ctx, placed.size(), rng);

            List<RuleContext.AdjacencyReq> reqs = effectiveAdjacencyReqs(ctx, bt);
            BlockPos ideal = memberPos;
            if (!reqs.isEmpty()) {
                BlockPos adjusted = applyAdjacency(level, ideal, reqs, rng);
                if (adjusted == null) { memberIdx++; continue; }
                ideal = adjusted;
            }

            LayoutSlot slot = resolveAndPlace(level, layout, ideal,
                    preferredDeg, radius, sb, bt, sizeCache, density, rng, placed);
            if (slot != null) {
                slot.setClusterId(cluster.getClusterId());
                placed.add(slot);
            }
            memberIdx++;
        }
    }

    private static BlockPos computeClusterMemberPos(
            BuildingCluster.Shape shape, BlockPos anchor,
            RuleContext ctx, int memberIdx, int memberCount, Random rng) {

        final int BUILDING_SPACING = 18;  // typical building width + gap

        return switch (shape) {
            case TIGHT_RING -> {
                // Ring radius scales with member count so members don't crowd
                int r = Math.max(12, (memberCount * BUILDING_SPACING) / 6);
                double angle = (memberIdx * 360.0 / memberCount);
                yield new BlockPos(
                        anchor.getX() + (int)(Math.cos(Math.toRadians(angle)) * r),
                        anchor.getY(),
                        anchor.getZ() + (int)(Math.sin(Math.toRadians(angle)) * r));
            }
            case LINEAR_ROW -> {
                double axisDeg = ctx.axisRad() != null
                        ? Math.toDegrees(ctx.axisRad()) + 90
                        : 0;
                int offset = (memberIdx - memberCount / 2) * BUILDING_SPACING;
                double rad = Math.toRadians(axisDeg);
                yield new BlockPos(
                        anchor.getX() + (int)(Math.cos(rad) * offset),
                        anchor.getY(),
                        anchor.getZ() + (int)(Math.sin(rad) * offset));
            }
            case LOOSE -> {
                // Spread loosely scales with member count
                int spread = BUILDING_SPACING + memberCount * 4;
                int dx = rng.nextInt(spread * 2) - spread;
                int dz = rng.nextInt(spread * 2) - spread;
                yield new BlockPos(anchor.getX() + dx, anchor.getY(), anchor.getZ() + dz);
            }
        };
    }
    /**
     * Walks the placed buildings and updates rotations to face the
     * nearest neighbour rather than the village centre. This produces
     * more natural-looking layouts where buildings face each other
     * across small spaces rather than uniformly facing the distant centre.
     *
     * <p>Buildings within the same cluster prefer to face other cluster
     * members. Buildings outside any cluster face their nearest building
     * neighbour regardless of cluster.
     */
    private static void refineRotations(VillageLayout layout) {
        List<LayoutSlot> buildings = layout.buildings();
        for (LayoutSlot slot : buildings) {
            LayoutSlot nearest = findNearestNeighbour(slot, buildings);
            if (nearest == null) continue;

            // Skip if the nearest is too close (would face directly into it)
            int dx = nearest.getPos().getX() - slot.getPos().getX();
            int dz = nearest.getPos().getZ() - slot.getPos().getZ();
            if (dx * dx + dz * dz < 64) continue;  // less than 8 blocks away

            slot.setRotation(chooseFacingRotation(slot.getPos(), nearest.getPos()));
        }
    }

    private static LayoutSlot findNearestNeighbour(LayoutSlot self, List<LayoutSlot> all) {
        LayoutSlot best = null;
        double bestDistSq = Double.MAX_VALUE;
        boolean preferCluster = self.getClusterId() >= 0;

        for (LayoutSlot other : all) {
            if (other == self) continue;
            // If we're in a cluster, prefer cluster-mates
            if (preferCluster && other.getClusterId() != self.getClusterId()) continue;

            double d = self.getPos().distSqr(other.getPos());
            if (d < bestDistSq) {
                bestDistSq = d;
                best = other;
            }
        }

        // If no cluster-mate found, fall back to any neighbour
        if (best == null && preferCluster) {
            for (LayoutSlot other : all) {
                if (other == self) continue;
                double d = self.getPos().distSqr(other.getPos());
                if (d < bestDistSq) {
                    bestDistSq = d;
                    best = other;
                }
            }
        }
        return best;
    }
}