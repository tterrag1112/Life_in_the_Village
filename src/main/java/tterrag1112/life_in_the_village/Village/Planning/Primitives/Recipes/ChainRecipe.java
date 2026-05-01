// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/ChainRecipe.java
package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.*;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.AddRing;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.AddSpur;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.FixedGrowth;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.Sector;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.SectorRole;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;
import tterrag1112.life_in_the_village.Village.Roads.Planning.GatewayDescriptor;
import tterrag1112.life_in_the_village.Village.Roads.Planning.VillageEdgeDescriptor;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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
 *
 * <p>Phase 15: extends {@link BaseRecipe} and emits sectors. Obstacle
 * finding happens in {@link #prepareFeatures}; geometry is unchanged.
 */
public final class ChainRecipe extends BaseRecipe {

    /** How sharply the curve bows around the obstacle. */
    private static final double CURVATURE = 0.18;

    private static final Set<SlotTag> TAGS_SPINE_ROAD =
            EnumSet.of(SlotTag.ROAD_ADJACENT, SlotTag.RESIDENTIAL_INFILL,
                    SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_STUB =
            EnumSet.of(SlotTag.PRODUCTION_CLUSTER, SlotTag.ROAD_ADJACENT,
                    SlotTag.RESIDENTIAL_INFILL);
    private static final Set<SlotTag> TAGS_FARM_END =
            EnumSet.of(SlotTag.FIELD_EDGE, SlotTag.PASTURE, SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_OUTER_DEFENSE =
            EnumSet.of(SlotTag.WALL_ADJACENT, SlotTag.HIGH_GROUND,
                    SlotTag.RESIDENTIAL_OUTER);

    private static final int SPUR_CAPACITY = 4;
    private static final int UNLIMITED_CAPACITY = 1024;

    private boolean terrainTooRough;
    private BlockPos obstaclePos;

    @Override
    protected void prepareFeatures(PlanContext pctx) {
        int largest = RecipeHelpers.largestRotatedFootprint(pctx);
        int requiredFlatSide = largest + 4;
        int available = pctx.layout.getTerrain().largestFlatPatchAvailable(2);
        terrainTooRough = available < requiredFlatSide;
        if (terrainTooRough) {
            System.out.println("ChainRecipe: terrain pre-check failed — "
                    + "largest=" + largest + " available=" + available
                    + " — falling back to LINEAR");
            return;
        }

        // Find obstacle early so composeSectors can fall back cleanly.
        obstaclePos = findObstacle(pctx.layout.getTerrain(), pctx.layout.getCenter());
        if (obstaclePos == null) {
            System.out.println("ChainRecipe: no terrain feature to bend around "
                    + "— falling back to LINEAR");
        }
    }

    @Override
    protected void composeSectors(PlanContext pctx) {
        if (terrainTooRough) {
            PlacementFailureRecorder.record(
                    PlacementFailureRecorder.Reason.TERRAIN_UNSUITABLE,
                    "CHAIN: no flat patch large enough for largest building",
                    pctx.layout.getCenter(),
                    VillageTypeData.ShapeType.CHAIN.name());
            new LinearRecipe().compose(pctx);
            return;
        }
        if (obstaclePos == null) {
            PlacementFailureRecorder.record(
                    PlacementFailureRecorder.Reason.SHAPE_RULE_REJECTED,
                    "no terrain feature to bend around",
                    pctx.layout.getCenter(),
                    VillageTypeData.ShapeType.CHAIN.name());
            new LinearRecipe().compose(pctx);
            return;
        }

        BlockPos centre = pctx.layout.getCenter();
        int totalBuildings = pctx.remaining.size();

        // ── Curve direction: tangent to (centre → obstacle) ────────────────
        double obsDx = obstaclePos.getX() - centre.getX();
        double obsDz = obstaclePos.getZ() - centre.getZ();
        double obsLen = Math.sqrt(obsDx * obsDx + obsDz * obsDz);
        if (obsLen < 1) {
            new LinearRecipe().compose(pctx);
            return;
        }
        double obsUx = obsDx / obsLen;
        double obsUz = obsDz / obsLen;
        double chordX = -obsUz;
        double chordZ = obsUx;

        int halfLength = Math.max(50,
                (totalBuildings * 8) + pctx.density.getRing2Radius());

        int chordOffset = pctx.density.getRing1Radius() + 8;
        int anchorX = centre.getX() - (int) Math.round(obsUx * chordOffset);
        int anchorZ = centre.getZ() - (int) Math.round(obsUz * chordOffset);
        BlockPos anchor = pctx.solidSurface(
                new BlockPos(anchorX, centre.getY(), anchorZ));

        BlockPos chordA = pctx.solidSurface(new BlockPos(
                anchor.getX() + (int) Math.round(chordX * halfLength),
                anchor.getY(),
                anchor.getZ() + (int) Math.round(chordZ * halfLength)));
        BlockPos chordB = pctx.solidSurface(new BlockPos(
                anchor.getX() - (int) Math.round(chordX * halfLength),
                anchor.getY(),
                anchor.getZ() - (int) Math.round(chordZ * halfLength)));

        // Flip chord so the bow points away from the obstacle.
        double perpInsideX = -(chordB.getZ() - chordA.getZ());
        double perpInsideZ = (chordB.getX() - chordA.getX());
        double dot = perpInsideX * (-obsUx) + perpInsideZ * (-obsUz);
        if (dot < 0) {
            BlockPos tmp = chordA;
            chordA = chordB;
            chordB = tmp;
        }

        RoadPrimitive.CurvedRoad mainRoad = new RoadPrimitive.CurvedRoad(
                chordA, chordB, CURVATURE, 6.0,
                RoadShape.RoadTier.VILLAGE_ROAD);
        int beforeMain = pctx.layout.getRoadGraph().edgeCount();
        List<BlockPos> mainCenterline = pctx.layout.addRoad(
                mainRoad, pctx.level, pctx.worldSeed);
        int mainEdgeId = pctx.layout.getRoadGraph().edgeCount() > beforeMain
                ? pctx.layout.getRoadGraph().edgeCount() - 1 : -1;

        if (mainCenterline.isEmpty()) {
            System.out.println("ChainRecipe: curved main road came back empty "
                    + "— falling back to LINEAR");
            PlacementFailureRecorder.record(
                    PlacementFailureRecorder.Reason.SHAPE_RULE_REJECTED,
                    "curved main road came back empty",
                    centre, VillageTypeData.ShapeType.CHAIN.name());
            new LinearRecipe().compose(pctx);
            return;
        }

        pctx.layout.setMainGateEndpoint(chordA);
        pctx.layout.addGatePosition(chordA);
        pctx.layout.addGatePosition(chordB);

        // ── Town square at the apex of the curve ──────────────────────────
        BlockPos squareApex = mainCenterline.get(mainCenterline.size() / 2);
        double apexTangentRad = RecipeHelpers.localTangentRad(
                mainCenterline, mainCenterline.size() / 2);
        int civicSnapshot = pctx.slotPoolSize();
        RecipeHelpers.installLinearPlaza(pctx, squareApex,
                tterrag1112.life_in_the_village.Village.Decoration
                        .Plaza.PlazaPurpose.CIVIC,
                RecipeHelpers.cardinalFromRad(apexTangentRad));

        int civicCap = Math.max(2, Math.min(4, totalBuildings / 6 + 1));
        List<PlacementSlot> civicSlots = pctx.drainSlotsSince(civicSnapshot);
        if (!civicSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    "chain_civic_ring",
                    SectorRole.CIVIC_RING,
                    BuildingZone.CIVIC,
                    civicSlots,
                    civicCap,
                    false,
                    FixedGrowth.INSTANCE,
                    mainEdgeId,
                    null,
                    32));
        }

        List<List<BlockPos>> allRoadsForSnap = new ArrayList<>();
        allRoadsForSnap.add(mainCenterline);

        // ── Spine road slots (backfill along the main curve) ──────────────
        // These stay in the flat pool for the matcher to pick up.
        pctx.offerRoadSlots(mainCenterline, 8, 8, TAGS_SPINE_ROAD, 35);

        // ── Stub spurs along the curve ─────────────────────────────────────
        // Four stubs spaced evenly, perpendicular to the local curve tangent,
        // alternating sides. Each stub becomes its own SPUR_CLUSTER sector.
        int stubCount = Math.min(6, Math.max(2, totalBuildings / 3));
        int squareIdx = mainCenterline.size() / 2;
        int squareMargin = Math.max(8, mainCenterline.size() / 8);
        int usableStart = 4;
        int usableEnd = mainCenterline.size() - 4;

        List<Integer> stubIndices =
                RecipeHelpers.branchIndicesAvoidingMidpoint(mainCenterline, stubCount);

        for (int i = 0; i < stubIndices.size(); i++) {
            int idx = stubIndices.get(i);
            if (idx < 0 || idx >= mainCenterline.size()) continue;
            BlockPos branchHint = mainCenterline.get(idx);

            double localTanRad = RecipeHelpers.localTangentRad(mainCenterline, idx);
            boolean leftSide = (i & 1) == 0;
            double stubAngle = localTanRad
                    + (leftSide ? -Math.PI / 2 : Math.PI / 2);
            stubAngle += (pctx.rng.nextDouble() - 0.5) * 0.3;

            int stubLength = 6 + pctx.rng.nextInt(5);
            int beforeStub = pctx.layout.getRoadGraph().edgeCount();
            RoadPrimitive.Spur stub = new RoadPrimitive.Spur(
                    mainCenterline, branchHint, stubAngle, stubLength,
                    1.5, RoadShape.RoadTier.FOOTPATH);
            List<BlockPos> stubCenterline = pctx.layout.addRoad(
                    stub, pctx.level, pctx.worldSeed);
            int stubEdgeId = pctx.layout.getRoadGraph().edgeCount() > beforeStub
                    ? pctx.layout.getRoadGraph().edgeCount() - 1 : mainEdgeId;
            allRoadsForSnap.add(stubCenterline);

            int stubSnapshot = pctx.slotPoolSize();
            pctx.offerRoadSlots(stubCenterline, 6, 5, TAGS_STUB, 55);
            List<PlacementSlot> stubSlots = pctx.drainSlotsSince(stubSnapshot);
            if (!stubSlots.isEmpty()) {
                pctx.offerSector(new Sector(
                        "chain_stub_" + i,
                        SectorRole.SPUR_CLUSTER,
                        BuildingZone.PRODUCTION,
                        stubSlots,
                        SPUR_CAPACITY,
                        true,
                        new AddSpur(32, 3),
                        stubEdgeId,
                        null,
                        16));
            }
        }

        // ── Agricultural at the curve ends ─────────────────────────────────
        // Slots near both chord endpoints share one AGRICULTURAL_FRINGE sector.
        int farmEndLen = Math.min(16, mainCenterline.size() / 5);
        int agriSnapshot = pctx.slotPoolSize();
        if (farmEndLen >= 2) {
            List<BlockPos> farmEndA = mainCenterline.subList(0, farmEndLen);
            List<BlockPos> farmEndB = mainCenterline.subList(
                    mainCenterline.size() - farmEndLen, mainCenterline.size());
            pctx.offerRoadSlots(farmEndA, 12, 6, TAGS_FARM_END, 45);
            pctx.offerRoadSlots(farmEndB, 12, 6, TAGS_FARM_END, 45);
        }
        List<PlacementSlot> agriSlots = pctx.drainSlotsSince(agriSnapshot);
        if (!agriSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    "chain_farm_ends",
                    SectorRole.AGRICULTURAL_FRINGE,
                    BuildingZone.AGRICULTURAL,
                    agriSlots,
                    UNLIMITED_CAPACITY,
                    true,
                    new AddRing(16, 6),
                    mainEdgeId,
                    null,
                    18));
        }

        // ── Defensive on the inland flank (away from obstacle) ─────────────
        BlockPos defCentre = new BlockPos(
                anchor.getX() - (int) Math.round(obsUx * pctx.density.getRing2Radius()),
                anchor.getY(),
                anchor.getZ() - (int) Math.round(obsUz * pctx.density.getRing2Radius()));

        int defSnapshot = pctx.slotPoolSize();
        LayoutPrimitive.RingBand defenseBand = new LayoutPrimitive.RingBand(
                defCentre,
                pctx.density.getRing1Radius(),
                pctx.density.getRing2Radius(),
                BuildingZone.DEFENSIVE, new ArrayList<>(), allRoadsForSnap);
        defenseBand.emitSlots(pctx);
        List<PlacementSlot> defSlots = pctx.drainSlotsSince(defSnapshot);
        if (!defSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    "chain_outer_defense",
                    SectorRole.DEFENSIVE_FRINGE,
                    BuildingZone.DEFENSIVE,
                    defSlots,
                    UNLIMITED_CAPACITY,
                    true,
                    new AddRing(12, 6),
                    -1,
                    null,
                    18));
        }
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

    private static final int MAX_OBSTACLE_DIST = 64;

    private static BlockPos findObstacle(TerrainProfile terrain, BlockPos centre) {
        BlockPos best = null;
        long bestDistSq = (long) MAX_OBSTACLE_DIST * MAX_OBSTACLE_DIST;

        if (terrain.hasRidges()) {
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
}
