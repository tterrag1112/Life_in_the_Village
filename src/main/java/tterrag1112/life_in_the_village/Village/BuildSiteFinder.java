// src/main/java/tterrag1112/life_in_the_village/Village/BuildSiteFinder.java
package tterrag1112.life_in_the_village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.VillagePath;
import tterrag1112.life_in_the_village.Village.Planning.BuildingFootprint;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.Features.FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.LayoutPlan;
import tterrag1112.life_in_the_village.Village.Planning.ZoneRegistry;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.List;
import java.util.Optional;

/**
 * Finds a suitable placement site for an expansion building.
 *
 * <h3>Phase 20 — graph-aware path</h3>
 * When the village has a persisted {@link LayoutPlan} (Phase 20a),
 * the finder walks the plan's road graph + queries the FeatureMap to
 * produce a placement decision consistent with initial planning:
 *
 * <ol>
 *   <li>Pick a feeding road of an appropriate tier for the building's
 *       {@link BuildingZone}.</li>
 *   <li>Generate transient candidate positions perpendicularly along
 *       the road's centerline, sized to the building's footprint plus
 *       road reservation + 1-block gap.</li>
 *   <li>Reject candidates the FeatureMap classifies as on water / on
 *       cliff / outside hull.</li>
 *   <li>Reject candidates whose footprint overlaps existing buildings
 *       (read from {@link VillageSavedData} so expansion-added
 *       buildings count too), road reservations, or planned plots.</li>
 *   <li>Reject candidates whose terrain isn't flat enough.</li>
 *   <li>Return the first viable candidate (Phase 20 Open Question 2
 *       default — full matcher scoring lifted to expansion is
 *       deferred).</li>
 * </ol>
 *
 * <h3>Legacy fallback</h3>
 * Villages with no persisted plan ({@code village.getPlan() == null}
 * — old saves predating Phase 20a, or villages whose planning failed)
 * use {@link #legacyFindSite(ServerLevel, Village, VillageSavedData,
 * BuildingType, int, int)}: the prior ring-aware + spiral
 * implementation, kept as-is.
 *
 * <h3>Y convention</h3>
 * Uses {@code MOTION_BLOCKING_NO_LEAVES} throughout — same heightmap
 * as {@code RoadRouter} and {@code VillagePlanner}.
 */
public class BuildSiteFinder {

    private static final int MAX_HEIGHT_VARIATION = 4;
    private static final int RING_ANGLE_STEP      = 15;
    private static final int RING_OVERFLOW        = 1;
    private static final int MAX_SPIRAL_RADIUS    = 128;
    private static final int SPIRAL_STEP          = 8;

    /**
     * Finds a placement site for a building of the given type. See class
     * Javadoc for the graph-aware vs legacy decision.
     */
    public static Optional<BlockPos> findSite(
            ServerLevel level,
            Village village,
            VillageSavedData data,
            BuildingType buildingType,
            int requiredWidth,
            int requiredLength) {
        LayoutPlan plan = village.getPlan();
        if (plan == null) {
            return legacyFindSite(level, village, data, buildingType,
                    requiredWidth, requiredLength);
        }
        Optional<BlockPos> graphSite = findSiteOnGraph(
                level, village, data, plan, buildingType,
                requiredWidth, requiredLength);
        if (graphSite.isPresent()) return graphSite;
        // Graph-aware path failed — fall back to legacy. This isn't a
        // "graph broken" signal; it just means no road yielded a viable
        // candidate (e.g. dense buildings or terrain gates blocked every
        // try). Legacy spiral is more permissive.
        return legacyFindSite(level, village, data, buildingType,
                requiredWidth, requiredLength);
    }

    // =========================================================================
    // Phase 20 graph-aware path
    // =========================================================================

    private static Optional<BlockPos> findSiteOnGraph(
            ServerLevel level, Village village, VillageSavedData data,
            LayoutPlan plan, BuildingType buildingType,
            int requiredWidth, int requiredLength) {

        BuildingZone zone = ZoneRegistry.zoneOf(buildingType);

        Optional<LayoutPlan.RoadEdge> feederOpt = pickFeedingRoad(plan, zone);
        if (feederOpt.isEmpty()) return Optional.empty();
        LayoutPlan.RoadEdge feeder = feederOpt.get();

        FeatureMap fmap = getOrRebuildFeatureMap(level, village, plan);

        int roadHalfWidth = feeder.tier().reservedHalfWidth();
        int halfW = requiredWidth / 2;
        int halfL = requiredLength / 2;
        // perpOffset = road clearance + half-footprint + 1 gap
        int perpOffset = roadHalfWidth + Math.max(halfW, halfL) + 1;
        int stride = Math.max(8, Math.max(halfW, halfL) * 2);

        BuildingFootprint footprint = BuildingFootprint.fromVillage(village, data);
        // Reserve roads from the plan's road graph; existing legacy code
        // also reserves VillagePaths, but those are subsumed by plan.roads()
        // for villages that have a plan.
        for (LayoutPlan.RoadEdge edge : plan.roads()) {
            footprint.reserveRoad(edge.centerline(),
                    edge.tier().reservedHalfWidth());
        }

        List<BlockPos> centerline = feeder.centerline();
        for (int i = stride; i < centerline.size() - 1; i += stride) {
            BlockPos on   = centerline.get(i);
            BlockPos prev = centerline.get(Math.max(0, i - 1));
            BlockPos next = centerline.get(Math.min(centerline.size() - 1, i + 1));
            int headX = Integer.signum(next.getX() - prev.getX());
            int headZ = Integer.signum(next.getZ() - prev.getZ());
            if (headX == 0 && headZ == 0) { headX = 1; headZ = 0; }
            int perpX = -headZ;
            int perpZ =  headX;

            for (int side : new int[]{+1, -1}) {
                int x = on.getX() + perpX * perpOffset * side;
                int z = on.getZ() + perpZ * perpOffset * side;
                int y = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (y <= level.getMinY()) continue;
                BlockPos candidate = new BlockPos(x, y, z);

                // FeatureMap gate (water / cliff / outside hull).
                if (!fmap.isClearForFootprint(candidate,
                        requiredWidth, requiredLength)) continue;

                // Footprint clearance against existing buildings + roads
                // + plots (the BuildingFootprint grid includes everything
                // in `data` plus the plan's roads).
                BlockPos origin = new BlockPos(x - halfW, y, z - halfL);
                if (!footprint.isClear(origin, requiredWidth, requiredLength,
                        BuildingFootprint.DEFAULT_BUFFER)) continue;
                if (overlapsPlannedPlot(candidate, requiredWidth,
                        requiredLength, plan)) continue;

                if (!isSiteFlat(level, origin, requiredWidth, requiredLength)) continue;

                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Picks the feeding road for a zone. CIVIC types prefer SPINE
     * (closest to plaza); AGRICULTURAL prefer OUTER_RING; PRODUCTION
     * prefer SPUR; everything else prefers SPINE then SPUR.
     *
     * <p>Returns the first matching edge — order in {@code plan.roads()}
     * is creation order, which for cascade-aware recipes is centre-out
     * (spine → spurs → arcs → outer ring), so "first matching" picks the
     * most-central road of the preferred role.
     */
    private static Optional<LayoutPlan.RoadEdge> pickFeedingRoad(
            LayoutPlan plan, BuildingZone zone) {
        tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole[] preferredRoles =
                switch (zone) {
            case AGRICULTURAL -> new tterrag1112.life_in_the_village.Village.Planning
                    .Graph.EdgeRole[]{
                tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole.OUTER_RING,
                tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole.RING,
                tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole.SPUR
            };
            case DEFENSIVE -> new tterrag1112.life_in_the_village.Village.Planning
                    .Graph.EdgeRole[]{
                tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole.OUTER_RING,
                tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole.RING,
                tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole.SPINE
            };
            case PRODUCTION -> new tterrag1112.life_in_the_village.Village.Planning
                    .Graph.EdgeRole[]{
                tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole.SPUR,
                tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole.SPINE,
                tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole.RING
            };
            default -> new tterrag1112.life_in_the_village.Village.Planning
                    .Graph.EdgeRole[]{
                tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole.SPINE,
                tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole.SPUR,
                tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole.RING
            };
        };
        for (var role : preferredRoles) {
            for (LayoutPlan.RoadEdge edge : plan.roads()) {
                if (edge.role() == role && !edge.centerline().isEmpty()) {
                    return Optional.of(edge);
                }
            }
        }
        // Last-resort: any road.
        for (LayoutPlan.RoadEdge edge : plan.roads()) {
            if (!edge.centerline().isEmpty()) return Optional.of(edge);
        }
        return Optional.empty();
    }

    /**
     * Checks if a candidate's footprint overlaps any planned plot.
     * Uses planned-plot centre+halfW/halfL + 1-block buffer.
     */
    private static boolean overlapsPlannedPlot(
            BlockPos candidate, int w, int l, LayoutPlan plan) {
        int halfW = w / 2;
        int halfL = l / 2;
        int candMinX = candidate.getX() - halfW - 1;
        int candMaxX = candidate.getX() + halfW + 1;
        int candMinZ = candidate.getZ() - halfL - 1;
        int candMaxZ = candidate.getZ() + halfL + 1;
        for (LayoutPlan.PlannedPlot plot : plan.plotSlots()) {
            int pMinX = plot.centre().getX() - plot.halfW();
            int pMaxX = plot.centre().getX() + plot.halfW();
            int pMinZ = plot.centre().getZ() - plot.halfL();
            int pMaxZ = plot.centre().getZ() + plot.halfL();
            if (candMinX < pMaxX && candMaxX > pMinX
                    && candMinZ < pMaxZ && candMaxZ > pMinZ) {
                return true;
            }
        }
        return false;
    }

    /**
     * Phase 20a chose Option B (rebuild on demand). Construction is
     * deterministic from {@code (origin, worldSeed, plazaPolygons)}; the
     * level provides surface Y for the polygon clustering pass.
     *
     * <p>Cached on the village's transient {@code debugFeatureMap}
     * field so a session of repeated expansions doesn't pay the rebuild
     * cost more than once.
     */
    private static FeatureMap getOrRebuildFeatureMap(
            ServerLevel level, Village village, LayoutPlan plan) {
        FeatureMap cached = village.getDebugFeatureMap();
        if (cached != null) return cached;
        // Phase 20a: rebuild on demand. The buildPlanning signature takes
        // a List<StarterBuilding> (the planning-time roster); for an
        // expansion-time rebuild we pass an empty list — the FeatureMap's
        // hull / water / cliff classifications come from heightmap
        // sampling and are independent of the building list.
        FeatureMap fm = FeatureMap.buildPlanning(
                plan.centre(),
                level.getSeed(),
                level,
                List.of());
        village.setDebugFeatureMap(fm);
        return fm;
    }

    // =========================================================================
    // Legacy spiral path — preserved for old saves with no persisted plan
    // =========================================================================

    private static Optional<BlockPos> legacyFindSite(
            ServerLevel level,
            Village village,
            VillageSavedData data,
            BuildingType buildingType,
            int requiredWidth,
            int requiredLength) {

        BuildingFootprint footprint = BuildingFootprint.fromVillage(village, data);
        for (VillagePath path : data.getPathsForVillage(village.getId())) {
            if (!path.isObsolete()) {
                footprint.reserveRoad(path.getCenterline(),
                        RoadShape.RoadTier.TOWN_ROAD.reservedHalfWidth());
            }
        }

        BlockPos centre = village.getVillageCentre();
        if (centre != null && village.getRing1Radius() > 0) {
            Optional<BlockPos> ringSite = findSiteOnRing(
                    level, village, data, buildingType,
                    requiredWidth, requiredLength, centre, footprint);
            if (ringSite.isPresent()) return ringSite;
        }

        Optional<AABB> bounds = village.getBounds(data);
        if (bounds.isEmpty()) return Optional.empty();
        return findSiteSpiral(level, bounds.get(),
                requiredWidth, requiredLength, footprint, centre);
    }

    private static Optional<BlockPos> findSiteOnRing(
            ServerLevel level, Village village, VillageSavedData data,
            BuildingType buildingType, int w, int l,
            BlockPos centre, BuildingFootprint footprint) {

        BuildingZone zone = ZoneRegistry.zoneOf(buildingType);
        int preferredRing = zone.preferredRing;

        for (int ringOffset = 0; ringOffset <= RING_OVERFLOW; ringOffset++) {
            int ringIndex = preferredRing + ringOffset;
            int radius    = ringRadiusFor(village, ringIndex);

            for (int pass = 0; pass < 2; pass++) {
                boolean sectorOnly = (pass == 0);
                for (int angle = 0; angle < 360; angle += RING_ANGLE_STEP) {
                    double rad = Math.toRadians(angle);
                    if (sectorOnly && !zone.containsAngle(angle)) continue;
                    int cx = centre.getX() + (int)(Math.cos(rad) * radius);
                    int cz = centre.getZ() + (int)(Math.sin(rad) * radius);
                    int surfY = level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
                    if (surfY < 0) continue;
                    BlockPos candidate = new BlockPos(cx, surfY, cz);
                    if (!footprint.isClear(candidate, w, l,
                            BuildingFootprint.DEFAULT_BUFFER)) {
                        candidate = footprint.findClearPosition(
                                candidate, centre, w, l,
                                BuildingFootprint.DEFAULT_BUFFER, 30);
                        if (candidate == null) continue;
                        surfY = level.getHeight(
                                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                candidate.getX(), candidate.getZ());
                        candidate = new BlockPos(
                                candidate.getX(), surfY, candidate.getZ());
                    }
                    if (!isSiteFlat(level, candidate, w, l)) continue;
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static int ringRadiusFor(Village village, int ringIndex) {
        int r1 = village.getRing1Radius();
        int r2 = village.getRing2Radius() > 0
                ? village.getRing2Radius() : r1 + 20;
        int r3 = r2 + (r2 - r1);
        return switch (ringIndex) {
            case 1  -> r1;
            case 2  -> r2;
            default -> r3 + (ringIndex - 3) * 15;
        };
    }

    private static Optional<BlockPos> findSiteSpiral(
            ServerLevel level, AABB villageBounds,
            int requiredWidth, int requiredLength,
            BuildingFootprint footprint, BlockPos centre) {

        int centerX = centre != null ? centre.getX()
                : (int)((villageBounds.minX + villageBounds.maxX) / 2);
        int centerZ = centre != null ? centre.getZ()
                : (int)((villageBounds.minZ + villageBounds.maxZ) / 2);
        int startRadius = (int)(Math.max(
                villageBounds.maxX - villageBounds.minX,
                villageBounds.maxZ - villageBounds.minZ) / 2) + 8;
        BlockPos centrePos = new BlockPos(centerX, 0, centerZ);

        for (int radius = startRadius;
             radius <= startRadius + MAX_SPIRAL_RADIUS;
             radius += SPIRAL_STEP) {
            for (int angle = 0; angle < 360; angle += RING_ANGLE_STEP) {
                double rad = Math.toRadians(angle);
                int x = centerX + (int)(radius * Math.cos(rad));
                int z = centerZ + (int)(radius * Math.sin(rad));
                int y = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (y < 0) continue;
                BlockPos candidate = new BlockPos(x, y, z);
                if (!footprint.isClear(candidate, requiredWidth, requiredLength,
                        BuildingFootprint.DEFAULT_BUFFER)) {
                    candidate = footprint.findClearPosition(
                            candidate, centrePos,
                            requiredWidth, requiredLength,
                            BuildingFootprint.DEFAULT_BUFFER, 20);
                    if (candidate == null) continue;
                    y = level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            candidate.getX(), candidate.getZ());
                    candidate = new BlockPos(candidate.getX(), y, candidate.getZ());
                }
                if (isSiteFlat(level, candidate, requiredWidth, requiredLength)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    static int surfaceY(ServerLevel level, int x, int z) {
        int y = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return y > level.getMinY() ? y : -1;
    }

    private static boolean isSiteFlat(ServerLevel level, BlockPos origin,
                                      int width, int length) {
        int baseY = origin.getY();
        int minY  = baseY, maxY = baseY;
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < length; dz++) {
                int sy = surfaceY(level,
                        origin.getX() + dx, origin.getZ() + dz);
                if (sy < 0) return false;
                minY = Math.min(minY, sy);
                maxY = Math.max(maxY, sy);
                if (maxY - minY > MAX_HEIGHT_VARIATION) return false;
                BlockState below = level.getBlockState(
                        new BlockPos(origin.getX() + dx, sy - 1,
                                origin.getZ() + dz));
                if (below.liquid()) return false;
            }
        }
        return true;
    }
}
