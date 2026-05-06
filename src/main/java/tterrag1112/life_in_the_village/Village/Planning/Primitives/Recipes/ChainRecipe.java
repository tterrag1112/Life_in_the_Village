package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaPurpose;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaShape;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.AnchorKind;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.Anchor;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.EdgeRef;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.LayoutBlueprint;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.PlazaDeclaration;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.RoadDeclaration;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.SectorDeclaration;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.SectorRef;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.SlotIntention;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.BaseRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadResult;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.SectorRole;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;
import tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase D: declarative port of CHAIN — village strung along a curved
 * spine that bends around a natural feature.
 *
 * <p>Pre-Phase-A pattern preserved:
 * <ul>
 *   <li>Find a terrain obstacle (ridge or water body within 64).</li>
 *   <li>Curve direction is the chord perpendicular to the
 *       (centre→obstacle) vector; bow points away from obstacle.</li>
 *   <li>Main road is a {@code CurvedRoad} primitive (curvature 0.18,
 *       drift 6.0, VILLAGE_ROAD).</li>
 *   <li>{@code stubCount = min(6, max(2, totalBuildings/3))} short
 *       stub spurs perpendicular to the local tangent at evenly-
 *       spaced indices avoiding the midpoint (where the plaza is).</li>
 *   <li>Plaza at curve apex.</li>
 *   <li>Farm slots near both curve endpoints.</li>
 *   <li>Defensive ring on the inland flank (away from obstacle).</li>
 * </ul>
 *
 * <p>Quirks:
 * <ul>
 *   <li>No-obstacle / curve-empty fallback: pre-Phase-A delegated to
 *       {@code new LinearRecipe().compose(pctx)} inline. New model
 *       marks unplannable + empty blueprint. Loss surfaces if
 *       measurement reveals.</li>
 *   <li>Stub spurs: pre-realised via {@code computeAndRecord} so
 *       slots can RoadAlong them.</li>
 * </ul>
 */
public final class ChainRecipe extends BaseRecipe {

    private static final double CURVATURE = 0.18;
    private static final int SPUR_CAPACITY = 4;
    private static final int UNLIMITED_CAPACITY = 1024;
    private static final int MAX_OBSTACLE_DIST = 64;

    private static final Set<SlotTag> TAGS_PRIME_CIVIC =
            EnumSet.of(SlotTag.PRIME_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_SPINE =
            EnumSet.of(SlotTag.ROAD_ADJACENT, SlotTag.RESIDENTIAL_INFILL,
                    SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_STUB =
            EnumSet.of(SlotTag.PRODUCTION_CLUSTER, SlotTag.ROAD_ADJACENT,
                    SlotTag.RESIDENTIAL_INFILL);
    private static final Set<SlotTag> TAGS_FARM =
            EnumSet.of(SlotTag.FIELD_EDGE, SlotTag.PASTURE,
                    SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_DEFENSE =
            EnumSet.of(SlotTag.WALL_ADJACENT, SlotTag.HIGH_GROUND,
                    SlotTag.RESIDENTIAL_OUTER);

    @Override
    public LayoutBlueprint compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();
        int totalBuildings = pctx.remaining.size();
        int ring1 = pctx.density.getRing1Radius();
        int ring2 = pctx.density.getRing2Radius();

        // Terrain pre-check.
        int largest = RecipeHelpers.largestRotatedFootprint(pctx);
        int requiredFlatSide = largest + 4;
        int available = terrain.largestFlatPatchAvailable(2);
        if (available < requiredFlatSide) {
            pctx.layout.markUnplannable(
                    "CHAIN terrain too rough — needs >=" + requiredFlatSide
                    + " flat side, available=" + available);
            return LayoutBlueprint.empty(ShapeType.CHAIN);
        }

        // Find obstacle to bend around.
        BlockPos obstaclePos = findObstacle(terrain, centre);
        if (obstaclePos == null) {
            pctx.layout.markUnplannable(
                    "CHAIN no terrain feature to bend around");
            return LayoutBlueprint.empty(ShapeType.CHAIN);
        }

        // Curve direction: chord perpendicular to (centre→obstacle).
        double obsDx = obstaclePos.getX() - centre.getX();
        double obsDz = obstaclePos.getZ() - centre.getZ();
        double obsLen = Math.sqrt(obsDx * obsDx + obsDz * obsDz);
        if (obsLen < 1) {
            pctx.layout.markUnplannable("CHAIN obstacle too close");
            return LayoutBlueprint.empty(ShapeType.CHAIN);
        }
        double obsUx = obsDx / obsLen;
        double obsUz = obsDz / obsLen;
        double chordX = -obsUz;
        double chordZ = obsUx;

        int halfLength = Math.max(50,
                (totalBuildings * 8) + ring2);
        int chordOffset = ring1 + 8;
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

        EdgeRef mainRef = EdgeRef.of("chain_spine");
        var mainPrimitive = new RoadPrimitive.CurvedRoad(
                chordA, chordB, CURVATURE, 6.0,
                RoadShape.RoadTier.VILLAGE_ROAD);

        // Pre-realise main curve so we can locate the apex and stub
        // branch positions.
        RoadResult mainResult = computeAndRecord(mainPrimitive, pctx);
        List<BlockPos> mainCenterline = mainResult.centerline();
        if (mainCenterline.isEmpty()) {
            pctx.layout.markUnplannable("CHAIN curved main came back empty");
            return LayoutBlueprint.empty(ShapeType.CHAIN);
        }

        BlockPos squareApex = mainCenterline.get(mainCenterline.size() / 2);

        // Stub spurs at indices avoiding the midpoint.
        int stubCount = Math.min(6, Math.max(2, totalBuildings / 3));
        List<Integer> stubIndices = RecipeHelpers
                .branchIndicesAvoidingMidpoint(mainCenterline, stubCount);

        List<RoadDeclaration> roads = new ArrayList<>();
        roads.add(new RoadDeclaration(mainRef,
                EdgeRole.SPINE, RoadShape.RoadTier.VILLAGE_ROAD,
                mainPrimitive));

        List<EdgeRef> stubRefs = new ArrayList<>();
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
            EdgeRef stubRef = EdgeRef.of("chain_stub_" + i);
            stubRefs.add(stubRef);
            roads.add(new RoadDeclaration(stubRef,
                    EdgeRole.SPUR, RoadShape.RoadTier.FOOTPATH,
                    new RoadPrimitive.Spur(
                            mainCenterline, branchHint, stubAngle,
                            stubLength, 1.5,
                            RoadShape.RoadTier.FOOTPATH)));
        }

        SectorRef civicSec = SectorRef.of("chain_civic");
        var plaza = new PlazaDeclaration(squareApex, ring1,
                PlazaShape.CIRCLE, PlazaPurpose.CIVIC, civicSec);

        int civicCap = Math.max(2, Math.min(4, totalBuildings / 6 + 1));

        List<SectorDeclaration> sectors = new ArrayList<>();
        sectors.add(new SectorDeclaration("chain_civic",
                SectorRole.CIVIC_TIGHT, BuildingZone.CIVIC,
                civicCap, 46, null));
        sectors.add(new SectorDeclaration("chain_spine",
                SectorRole.RESIDENTIAL_INFILL, BuildingZone.RESIDENTIAL,
                UNLIMITED_CAPACITY, 16, mainRef));
        for (int i = 0; i < stubRefs.size(); i++) {
            sectors.add(new SectorDeclaration(
                    "chain_stub_" + i, SectorRole.SPUR_CLUSTER,
                    BuildingZone.PRODUCTION,
                    SPUR_CAPACITY, 16, stubRefs.get(i)));
        }
        sectors.add(new SectorDeclaration("chain_farm_ends",
                SectorRole.AGRICULTURAL_FRINGE, BuildingZone.AGRICULTURAL,
                UNLIMITED_CAPACITY, 18, mainRef));
        sectors.add(new SectorDeclaration("chain_outer_defense",
                SectorRole.DEFENSIVE_FRINGE, BuildingZone.DEFENSIVE,
                UNLIMITED_CAPACITY, 18, null));

        // Inland flank for defense ring (away from obstacle).
        BlockPos defCentre = pctx.solidSurface(new BlockPos(
                anchor.getX() - (int) Math.round(obsUx * ring2),
                anchor.getY(),
                anchor.getZ() - (int) Math.round(obsUz * ring2)));
        // Defense radius from VILLAGE centre — RingValidated only knows
        // about the village centre, so approximate.
        int defOffset = (int) Math.round(Math.sqrt(
                (defCentre.getX() - centre.getX())
                        * (defCentre.getX() - centre.getX())
                + (defCentre.getZ() - centre.getZ())
                        * (defCentre.getZ() - centre.getZ())));

        List<SlotIntention> intentions = new ArrayList<>();
        intentions.add(new SlotIntention(civicSec, TAGS_PRIME_CIVIC,
                1, 46, new Anchor.PlazaPerimeter(civicSec, 6)));
        // Spine slots along the curve.
        intentions.add(new SlotIntention(SectorRef.of("chain_spine"),
                TAGS_SPINE, 8, 35,
                new Anchor.RoadAlong(mainRef, 8, true)));
        for (int i = 0; i < stubRefs.size(); i++) {
            intentions.add(new SlotIntention(
                    SectorRef.of("chain_stub_" + i),
                    TAGS_STUB, 3, 16,
                    new Anchor.RoadAlong(stubRefs.get(i), 5, true)));
        }
        // Farms near both curve endpoints — RegionalGather around
        // chordA and chordB (approximation of the pre-Phase-A
        // "slots near both endpoints" pattern).
        intentions.add(new SlotIntention(
                SectorRef.of("chain_farm_ends"),
                TAGS_FARM, 4, 14,
                new Anchor.RegionalGather(chordA, 14, 5)));
        intentions.add(new SlotIntention(
                SectorRef.of("chain_farm_ends"),
                TAGS_FARM, 4, 14,
                new Anchor.RegionalGather(chordB, 14, 5)));
        // Defense ring on the inland side.
        intentions.add(new SlotIntention(
                SectorRef.of("chain_outer_defense"),
                TAGS_DEFENSE, 4, 18,
                new Anchor.RingValidated(defOffset + ring1, 0.0)));

        Map<AnchorKind, BlockPos> namedAnchors = new LinkedHashMap<>();
        namedAnchors.put(AnchorKind.TOWN_SQUARE, squareApex);
        namedAnchors.put(AnchorKind.MAIN_GATE, chordA);
        namedAnchors.put(AnchorKind.SECONDARY_GATE, chordB);

        return new LayoutBlueprint(
                ShapeType.CHAIN, roads,
                List.of(plaza), sectors, intentions, namedAnchors);
    }

    @Override
    public LayoutBlueprint reEmit(ReEmitReason reason, PlanContext pctx) {
        return switch (reason) {
            case ReEmitReason.SevereTruncation t -> null;
            case ReEmitReason.SlotsDropped s -> null;
            case ReEmitReason.SectorStarved s -> null;
            case ReEmitReason.ValidationFailed v -> null;
        };
    }

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
                        || (Math.abs(distSq - bestDistSq) < 100
                                && area > bestArea)) {
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
