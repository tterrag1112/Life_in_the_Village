// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/ChainRecipe.java
package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.*;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Roads.Planning.GatewayDescriptor;
import tterrag1112.life_in_the_village.Village.Roads.Planning.VillageEdgeDescriptor;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadEdge;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.List;

/**
 * CHAIN layout recipe.
 *
 * <p>A village strung along a curved spine — like LINEAR, but the main
 * road is a {@link RoadPrimitive.CurvedRoad} that bends around a
 * natural feature (ridge or water body). The town square sits at the
 * apex of the curve. Buildings line both sides via stub spurs like
 * LINEAR.
 *
 * <p>Reads as: a road that grew to follow the land. Hill-skirting
 * hamlet, forest-edge village, anywhere the natural shape of the
 * terrain dictates the village's footprint.
 *
 * <p>Falls back to LINEAR if there's nothing in the terrain to bend
 * around — a straight chain on flat land would be indistinguishable
 * from LINEAR and the curve would feel arbitrary.
 */
public final class ChainRecipe implements ShapeRecipe {

    /** How sharply the curve bows around the obstacle. */
    private static final double CURVATURE = 0.18;

    @Override
    public void compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();

        int totalBuildings = pctx.remaining.size();

        // ── Find an obstacle to bend around ────────────────────────────────
        BlockPos obstacle = findObstacle(terrain, centre);
        if (obstacle == null) {
            System.out.println("ChainRecipe: no terrain feature to bend around "
                    + "— falling back to LINEAR");
            PlacementFailureRecorder
                    .record(PlacementFailureRecorder.Reason.SHAPE_RULE_REJECTED,
                            "no terrain feature to bend around",
                            centre, VillageTypeData.ShapeType.CHAIN.name());
            new LinearRecipe().compose(pctx);
            return;
        }

        // ── Curve direction: tangent to (centre → obstacle) ────────────────
        // The chord runs perpendicular to the obstacle direction so the
        // village wraps around it. The curve bows AWAY from the obstacle
        // so buildings sit on the side opposite the feature.
        double obsDx = obstacle.getX() - centre.getX();
        double obsDz = obstacle.getZ() - centre.getZ();
        double obsLen = Math.sqrt(obsDx * obsDx + obsDz * obsDz);
        if (obsLen < 1) {
            new LinearRecipe().compose(pctx);
            return;
        }
        // Unit vector from centre toward obstacle
        double obsUx = obsDx / obsLen;
        double obsUz = obsDz / obsLen;
        // Chord direction = perpendicular (rotate 90° CCW)
        double chordX = -obsUz;
        double chordZ = obsUx;

        int halfLength = Math.max(50,
                (totalBuildings * 8) + pctx.density.getRing2Radius());

        // Chord endpoints, anchored on the side AWAY from the obstacle
        // (push the chord centre back from the obstacle by some distance)
        int chordOffset = pctx.density.getRing1Radius() + 8;
        int anchorX = centre.getX() - (int) Math.round(obsUx * chordOffset);
        int anchorZ = centre.getZ() - (int) Math.round(obsUz * chordOffset);
        BlockPos anchor = pctx.solidSurface(new BlockPos(anchorX, centre.getY(), anchorZ));

        BlockPos chordA = pctx.solidSurface(new BlockPos(
                anchor.getX() + (int) Math.round(chordX * halfLength),
                anchor.getY(),
                anchor.getZ() + (int) Math.round(chordZ * halfLength)));
        BlockPos chordB = pctx.solidSurface(new BlockPos(
                anchor.getX() - (int) Math.round(chordX * halfLength),
                anchor.getY(),
                anchor.getZ() - (int) Math.round(chordZ * halfLength)));

        // CurvedRoad bows perpendicular to the chord — by default that's
        // one of two perpendicular directions. We want it bowing AWAY
        // from the obstacle. CurvedRoad's perpendicular is computed as
        // (-dz, dx) / chordLen which is one specific rotation; we may
        // need to flip the chord endpoints to get the bow on the right
        // side. Test: the perpendicular direction inside CurvedRoad is
        // (-(chordB.z - chordA.z), (chordB.x - chordA.x)). Compare to
        // -obsU and flip if dot product is negative.
        double perpInsideX = -(chordB.getZ() - chordA.getZ());
        double perpInsideZ = (chordB.getX() - chordA.getX());
        double dot = perpInsideX * (-obsUx) + perpInsideZ * (-obsUz);
        if (dot < 0) {
            // Flip endpoints so the bow direction points away from the obstacle
            BlockPos tmp = chordA;
            chordA = chordB;
            chordB = tmp;
        }

        RoadPrimitive.CurvedRoad mainRoad = new RoadPrimitive.CurvedRoad(
                chordA, chordB, CURVATURE, 6.0,
                RoadShape.RoadTier.VILLAGE_ROAD);
        List<BlockPos> mainCenterline = pctx.layout.addRoad(
                mainRoad, pctx.level, pctx.worldSeed);

        if (mainCenterline.isEmpty()) {
            System.out.println("ChainRecipe: curved main road came back empty "
                    + "— falling back to LINEAR");
            PlacementFailureRecorder
                    .record(PlacementFailureRecorder.Reason.SHAPE_RULE_REJECTED,
                            "curved main road came back empty",
                            centre, VillageTypeData.ShapeType.CHAIN.name());
            new LinearRecipe().compose(pctx);
            return;
        }

        // Gate endpoint = chord A (one end of the curve)
        pctx.layout.setMainGateEndpoint(chordA);
        pctx.layout.addGatePosition(chordA);
        pctx.layout.addGatePosition(chordB);

        // ── Town square at the apex of the curve (centerline midpoint) ─────
        BlockPos squareApex = mainCenterline.get(mainCenterline.size() / 2);
        int civicCap = Math.max(2, Math.min(4, totalBuildings / 6 + 1));
        // Phase 18 doc 04 — polygon plaza handles all civic / layout setup.
        double apexTangentRad = RecipeHelpers.localTangentRad(
                mainCenterline, mainCenterline.size() / 2);
        RecipeHelpers.installLinearPlaza(pctx, squareApex,
                tterrag1112.life_in_the_village.Village.Decoration
                        .Plaza.PlazaPurpose.CIVIC,
                RecipeHelpers.cardinalFromRad(apexTangentRad));

        List<List<BlockPos>> allRoadsForSnap = new ArrayList<>();
        allRoadsForSnap.add(mainCenterline);

        // ── Stub spurs along the curve, alternating sides ──────────────────
        List<VillageTypeData.StarterBuilding> production =
                pctx.claimByZone(BuildingZone.PRODUCTION, 1000);
        List<VillageTypeData.StarterBuilding> residential =
                pctx.claimByZone(BuildingZone.RESIDENTIAL, 1000);

        List<VillageTypeData.StarterBuilding> stubBuildings = new ArrayList<>();
        int p = 0, r = 0;
        while (p < production.size() || r < residential.size()) {
            if (p < production.size()) stubBuildings.add(production.get(p++));
            if (r < residential.size()) stubBuildings.add(residential.get(r++));
        }

        int squareIdx = mainCenterline.size() / 2;
        int squareMargin = Math.max(8, mainCenterline.size() / 8);
        int usableStart = 4;
        int usableEnd = mainCenterline.size() - 4;
        int stubCount = stubBuildings.size();

        if (stubCount > 0) {
            List<Integer> stubIndices = new ArrayList<>();
            int leftCount = stubCount / 2;
            int rightCount = stubCount - leftCount;
            for (int i = 0; i < leftCount; i++) {
                int range = (squareIdx - squareMargin) - usableStart;
                if (range <= 0) break;
                int idx = usableStart + (range * i) / Math.max(1, leftCount);
                stubIndices.add(idx);
            }
            for (int i = 0; i < rightCount; i++) {
                int range = usableEnd - (squareIdx + squareMargin);
                if (range <= 0) break;
                int idx = (squareIdx + squareMargin) + (range * i) / Math.max(1, rightCount);
                stubIndices.add(idx);
            }

            for (int i = 0; i < stubBuildings.size() && i < stubIndices.size(); i++) {
                int idx = stubIndices.get(i);
                BlockPos branchHint = mainCenterline.get(idx);

                // Compute the LOCAL tangent at this centerline point so
                // stubs are perpendicular to the curve, not perpendicular
                // to the chord. This is the key difference from LINEAR.
                int prevIdx = Math.max(0, idx - 1);
                int nextIdx = Math.min(mainCenterline.size() - 1, idx + 1);
                BlockPos prev = mainCenterline.get(prevIdx);
                BlockPos next = mainCenterline.get(nextIdx);
                double localTanX = next.getX() - prev.getX();
                double localTanZ = next.getZ() - prev.getZ();
                double localTanLen = Math.sqrt(localTanX * localTanX + localTanZ * localTanZ);
                if (localTanLen < 1) { localTanX = 1; localTanZ = 0; localTanLen = 1; }
                double localTanRad = Math.atan2(localTanZ / localTanLen,
                        localTanX / localTanLen);

                boolean leftSide = (i & 1) == 0;
                double stubAngle = localTanRad
                        + (leftSide ? -Math.PI / 2 : Math.PI / 2);
                stubAngle += (pctx.rng.nextDouble() - 0.5) * 0.3;

                int stubLength = 6 + pctx.rng.nextInt(5);
                RoadPrimitive.Spur stub = new RoadPrimitive.Spur(
                        mainCenterline,
                        branchHint,
                        stubAngle,
                        stubLength,
                        1.5,
                        RoadShape.RoadTier.FOOTPATH);
                List<BlockPos> stubCenterline = pctx.layout.addRoad(
                        stub, pctx.level, pctx.worldSeed);
                allRoadsForSnap.add(stubCenterline);

                BlockPos focal = stubCenterline.get(stubCenterline.size() - 1);
                List<VillageTypeData.StarterBuilding> single =
                        List.of(stubBuildings.get(i));
                new LayoutPrimitive.BuildingCircle(
                        focal,
                        LayoutPrimitive.BuildingCircle.Mode.SCATTER,
                        single,
                        stubCenterline
                ).place(pctx);
            }
        }

        // ── Agricultural at the curve ends ─────────────────────────────────
        List<VillageTypeData.StarterBuilding> agri =
                pctx.claimByZone(BuildingZone.AGRICULTURAL, 1000);
        if (!agri.isEmpty()) {
            int half = (agri.size() + 1) / 2;
            placeFarmCluster(pctx, chordA, agri.subList(0, half), mainCenterline);
            if (half < agri.size()) {
                placeFarmCluster(pctx, chordB, agri.subList(half, agri.size()), mainCenterline);
            }
        }

        // ── Defensive on the inland flank (away from obstacle) ─────────────
        List<VillageTypeData.StarterBuilding> defensive =
                pctx.claimByZone(BuildingZone.DEFENSIVE, 1000);
        if (!defensive.isEmpty()) {
            BlockPos defCentre = new BlockPos(
                    anchor.getX() - (int) Math.round(obsUx
                            * pctx.density.getRing2Radius()),
                    anchor.getY(),
                    anchor.getZ() - (int) Math.round(obsUz
                            * pctx.density.getRing2Radius()));
            new LayoutPrimitive.RingBand(
                    defCentre,
                    pctx.density.getRing1Radius(),
                    pctx.density.getRing2Radius(),
                    BuildingZone.DEFENSIVE, defensive, allRoadsForSnap
            ).place(pctx);
        }

        // ── Stragglers ─────────────────────────────────────────────────────
        if (!pctx.remaining.isEmpty()) {
            List<VillageTypeData.StarterBuilding> leftovers =
                    new ArrayList<>(pctx.remaining);
            pctx.remaining.clear();
            new LayoutPrimitive.RingBand(
                    anchor,
                    pctx.density.getRing1Radius(),
                    pctx.density.getRing2Radius(),
                    BuildingZone.RESIDENTIAL, leftovers, allRoadsForSnap
            ).place(pctx);
        }
    }

    /**
     * Looks for something in the terrain to bend the chain around.
     * Priority: largest ridge → water body → null (caller falls back
     * to LINEAR).
     */
    private static final int MAX_OBSTACLE_DIST = 64;

    private static BlockPos findObstacle(TerrainProfile terrain, BlockPos centre) {
        BlockPos best = null;
        long bestDistSq = (long) MAX_OBSTACLE_DIST * MAX_OBSTACLE_DIST;

        if (terrain.hasRidges()) {
            // Pick the closest ridge whose centre is within range, breaking
            // ties by bounding box area (larger wins).
            long bestArea = 0;
            for (TerrainAnalyzer.RidgeInfo r : terrain.ridges()) {
                int cx = (r.min().getX() + r.max().getX()) / 2;
                int cz = (r.min().getZ() + r.max().getZ()) / 2;
                long dx = cx - centre.getX();
                long dz = cz - centre.getZ();
                long distSq = dx * dx + dz * dz;
                if (distSq > bestDistSq) continue;
                long w = r.max().getX() - r.min().getX();
                long h = r.max().getZ() - r.min().getZ();
                long area = w * h;
                // Prefer closer; if roughly equal distance, prefer larger
                if (best == null
                        || distSq < bestDistSq - 100
                        || (Math.abs(distSq - bestDistSq) < 100 && area > bestArea)) {
                    best = new BlockPos(cx, centre.getY(), cz);
                    bestDistSq = distSq;
                    bestArea = area;
                }
            }
            if (best != null) return best;
        }
        if (terrain.hasWater()) {
            BlockPos waterCentre = terrain.waterBody().centre();
            long dx = waterCentre.getX() - centre.getX();
            long dz = waterCentre.getZ() - centre.getZ();
            long distSq = dx * dx + dz * dz;
            if (distSq <= (long) MAX_OBSTACLE_DIST * MAX_OBSTACLE_DIST) {
                return waterCentre;
            }
        }
        return null;
    }

    /** Two gateways at the curve's two chord endpoints (PRIMARY = chordA, SIDE = chordB). */
    @Override
    public List<GatewayDescriptor> describeGateways(PlanContext pctx) {
        return GatewayDescriptor.deriveFromLayout(pctx);
    }

    /** Single THROUGH_VILLAGE edge along the curved main road, connecting the two gateways. */
    @Override
    public List<VillageEdgeDescriptor> describeInternalRoads(PlanContext pctx) {
        return LinearRecipe.deriveThruRoad(pctx);
    }

    private void placeFarmCluster(PlanContext pctx, BlockPos endPoint,
                                  List<VillageTypeData.StarterBuilding> farms,
                                  List<BlockPos> mainRoad) {
        if (farms.isEmpty()) return;
        new LayoutPrimitive.BuildingCircle(
                pctx.solidSurface(endPoint),
                LayoutPrimitive.BuildingCircle.Mode.SCATTER,
                farms, mainRoad
        ).place(pctx);
    }
}