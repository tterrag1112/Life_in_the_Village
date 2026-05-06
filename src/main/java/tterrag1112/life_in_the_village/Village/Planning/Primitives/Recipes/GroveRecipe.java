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
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;
import tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase D: declarative port of GROVE — sacred-center village. Empty
 * centre (decoration reservation), wide civic ring, RADIAL-style
 * spurs + cluster arcs outside the wide ring.
 *
 * <p>Pre-Phase-A pattern preserved:
 * <ul>
 *   <li>IRREGULAR plaza at centre.</li>
 *   <li>{@code wideRing = civicRing * 1.6} multiplier.</li>
 *   <li>{@code spurCount = max(3, min(6, totalBuildings/4))} at
 *       evenly-spaced angles, base angle RNG-seeded.</li>
 *   <li>Each spur is a Spur primitive (VILLAGE_PATH, length =
 *       ring2 + 8) branching from synthetic [centre, branchHint]
 *       at wideRing+2.</li>
 *   <li>Each spur gets a 60° cluster arc at the spur tip (Arc
 *       primitive at squarePos) — same RADIAL pattern.</li>
 *   <li>Outer agri at {@code [ring2+8, ring2+28]} (midpoint
 *       ring2+18) and defense at {@code [ring2+4, ring2+14]}
 *       (midpoint ring2+9).</li>
 * </ul>
 *
 * <p>The {@code wideRing} is the spec's "sacred-center" reservation;
 * Phase A's planner orchestration calls
 * {@code RecipeHelpers.installPlaza} which returns the plaza but
 * doesn't widen the civic ring after installation. The pre-Phase-A
 * recipe re-registered the plaza with widened civicRing post-install;
 * Phase D loses that re-registration but retains spurs branching
 * from {@code wideRing+2} so the spatial structure holds.
 */
public final class GroveRecipe extends BaseRecipe {

    private static final double GROVE_RING_MULTIPLIER = 1.6;
    private static final int SPUR_CAPACITY = 5;
    private static final int UNLIMITED_CAPACITY = 1024;

    private static final Set<SlotTag> TAGS_PRIME_CIVIC =
            EnumSet.of(SlotTag.PRIME_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_SPUR_ROAD =
            EnumSet.of(SlotTag.ROAD_ADJACENT, SlotTag.RESIDENTIAL_INFILL,
                    SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_SPUR_CLUSTER =
            EnumSet.of(SlotTag.PRODUCTION_CLUSTER, SlotTag.PRODUCTION_SPUR_END,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_AGRI =
            EnumSet.of(SlotTag.FIELD_EDGE, SlotTag.PASTURE, SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_DEFENSE =
            EnumSet.of(SlotTag.WALL_ADJACENT, SlotTag.HIGH_GROUND,
                    SlotTag.RESIDENTIAL_OUTER);

    @Override
    public LayoutBlueprint compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        var terrain = pctx.layout.getTerrain();
        int totalBuildings = pctx.remaining.size();
        int ring1 = pctx.density.getRing1Radius();
        int ring2 = pctx.density.getRing2Radius();

        // Terrain pre-check (pre-Phase-A behavior).
        int largest = RecipeHelpers.largestRotatedFootprint(pctx);
        int requiredFlatSide = largest + 4;
        int available = terrain.largestFlatPatchAvailable(2);
        if (available < requiredFlatSide) {
            pctx.layout.markUnplannable(
                    "GROVE terrain too rough — needs >=" + requiredFlatSide
                    + " flat side, available=" + available);
            return LayoutBlueprint.empty(ShapeType.GROVE);
        }

        int wideRing = (int) (ring1 * GROVE_RING_MULTIPLIER);

        SectorRef civicSec = SectorRef.of("plaza_civic");
        var plaza = new PlazaDeclaration(centre, ring1,
                PlazaShape.IRREGULAR, PlazaPurpose.CIVIC, civicSec);

        int spurCount = Math.max(3, Math.min(6, totalBuildings / 4));
        double baseAngle = pctx.rng.nextDouble() * 2 * Math.PI;
        double angleStep = 2 * Math.PI / spurCount;

        List<RoadDeclaration> roads = new ArrayList<>();
        List<EdgeRef> spurRefs = new ArrayList<>();
        List<EdgeRef> clusterArcRefs = new ArrayList<>();
        BlockPos mainGate = null;

        for (int i = 0; i < spurCount; i++) {
            double spurAngle = baseAngle + angleStep * i
                    + pctx.cascadeAxisRotation();
            BlockPos branchHint = pctx.solidSurface(new BlockPos(
                    centre.getX() + (int) Math.round(Math.cos(spurAngle) * (wideRing + 2)),
                    centre.getY(),
                    centre.getZ() + (int) Math.round(Math.sin(spurAngle) * (wideRing + 2))));
            List<BlockPos> synthParent = List.of(centre, branchHint);
            int spurLen = ring2 + 8;

            EdgeRef spurRef = EdgeRef.of("grove_spur_" + i);
            spurRefs.add(spurRef);
            var spurPrim = new RoadPrimitive.Spur(
                    synthParent, branchHint, spurAngle, spurLen,
                    5.0, RoadShape.RoadTier.VILLAGE_PATH);
            roads.add(new RoadDeclaration(spurRef,
                    EdgeRole.SPUR, RoadShape.RoadTier.VILLAGE_PATH, spurPrim));

            // Pre-realise spur to compute cluster arc geometry.
            RoadResult spurResult = computeAndRecord(spurPrim, pctx);
            List<BlockPos> spurCenterline = spurResult.centerline();
            BlockPos spurTip = spurCenterline.isEmpty() ? branchHint
                    : spurCenterline.get(spurCenterline.size() - 1);
            if (i == 0) mainGate = spurTip;

            // Cluster arc at spur tip (60° centred on plaza).
            double tipDx = spurTip.getX() - centre.getX();
            double tipDz = spurTip.getZ() - centre.getZ();
            int clusterRadius = Math.max(8,
                    (int) Math.round(Math.sqrt(tipDx * tipDx + tipDz * tipDz)));
            double tipAngle = Math.atan2(tipDz, tipDx);

            EdgeRef clusterRef = EdgeRef.of("grove_spur_" + i + "_cluster");
            clusterArcRefs.add(clusterRef);
            var clusterArc = new RoadPrimitive.Arc(
                    centre, clusterRadius,
                    tipAngle - Math.PI / 6, Math.PI / 3,
                    3.0, RoadShape.RoadTier.VILLAGE_PATH);
            roads.add(new RoadDeclaration(clusterRef,
                    EdgeRole.SPUR, RoadShape.RoadTier.VILLAGE_PATH,
                    clusterArc));
        }

        List<SectorDeclaration> sectors = new ArrayList<>();
        sectors.add(new SectorDeclaration("plaza_civic",
                SectorRole.CIVIC_RING, BuildingZone.CIVIC,
                3, 46, null));
        for (int i = 0; i < spurCount; i++) {
            sectors.add(new SectorDeclaration(
                    "grove_spur_" + i, SectorRole.SPUR_CLUSTER,
                    BuildingZone.PRODUCTION,
                    SPUR_CAPACITY, 16, spurRefs.get(i)));
        }
        sectors.add(new SectorDeclaration("grove_outer_agri",
                SectorRole.AGRICULTURAL_FRINGE, BuildingZone.AGRICULTURAL,
                UNLIMITED_CAPACITY, 18, null));
        sectors.add(new SectorDeclaration("grove_outer_defense",
                SectorRole.DEFENSIVE_FRINGE, BuildingZone.DEFENSIVE,
                UNLIMITED_CAPACITY, 18, null));

        List<SlotIntention> intentions = new ArrayList<>();
        intentions.add(new SlotIntention(civicSec, TAGS_PRIME_CIVIC,
                1, 46, new Anchor.PlazaPerimeter(civicSec, 6)));
        for (int i = 0; i < spurCount; i++) {
            intentions.add(new SlotIntention(
                    SectorRef.of("grove_spur_" + i),
                    TAGS_SPUR_ROAD, 4, 16,
                    new Anchor.RoadAlong(spurRefs.get(i), 7, true)));
            intentions.add(new SlotIntention(
                    SectorRef.of("grove_spur_" + i),
                    TAGS_SPUR_CLUSTER, 3, 16,
                    new Anchor.RoadAlong(clusterArcRefs.get(i), 10, true)));
        }
        intentions.add(new SlotIntention(
                SectorRef.of("grove_outer_agri"),
                TAGS_AGRI, 6, 18,
                new Anchor.RingValidated(ring2 + 18, 0.0)));
        intentions.add(new SlotIntention(
                SectorRef.of("grove_outer_defense"),
                TAGS_DEFENSE, 4, 18,
                new Anchor.RingValidated(ring2 + 9, 0.0)));

        Map<AnchorKind, BlockPos> namedAnchors = new LinkedHashMap<>();
        namedAnchors.put(AnchorKind.TOWN_SQUARE, centre);
        if (mainGate != null) {
            namedAnchors.put(AnchorKind.MAIN_GATE, mainGate);
        }

        return new LayoutBlueprint(
                ShapeType.GROVE, roads,
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
}
