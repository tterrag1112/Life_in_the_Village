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
 * Phase D: declarative port of CROSSROADS — two main roads crossing
 * at right angles with the town square at the intersection.
 *
 * <p>Pre-Phase-A pattern preserved:
 * <ul>
 *   <li>SQUARE plaza at intersection.</li>
 *   <li>Primary axis aligned with terrain.bestFlatDir +
 *       cascadeAxisRotation; secondary axis perpendicular.</li>
 *   <li>Primary arm length = ring2*2 + totalBuildings*3 + 32
 *       (VILLAGE_ROAD).</li>
 *   <li>Secondary arm length = primary * 0.75 (VILLAGE_PATH).</li>
 *   <li>Each of 4 arms is a StraightRoad. Pre-Phase-A added 2
 *       perpendicular stub spurs per arm at fractions {0.35, 0.65}
 *       — declarative version uses RoadAlong on the arm with both
 *       sides instead of separate stub-roads (loses the visible
 *       stub-branch geometry but functionally equivalent for slot
 *       placement).</li>
 *   <li>Outer agri at midpoint of [ring2+6, ring2+24]; defense at
 *       midpoint of [ring2, ring2+12].</li>
 * </ul>
 *
 * <p>Spec doc Section 7's worked example uses RingValidated for the
 * outer rings; matches the pattern here.
 */
public final class CrossroadsRecipe extends BaseRecipe {

    private static final int ARM_CAPACITY = 8;
    private static final int UNLIMITED_CAPACITY = 1024;

    private static final Set<SlotTag> TAGS_PRIME_CIVIC =
            EnumSet.of(SlotTag.PRIME_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_SECONDARY_CIVIC =
            EnumSet.of(SlotTag.SECONDARY_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_ARM_ROAD =
            EnumSet.of(SlotTag.ROAD_ADJACENT, SlotTag.RESIDENTIAL_INFILL,
                    SlotTag.PRODUCTION_CLUSTER, SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_AGRI =
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
                    "CROSSROADS terrain too rough — needs >="
                    + requiredFlatSide + " flat side, available=" + available);
            return LayoutBlueprint.empty(ShapeType.CROSSROADS);
        }

        double primaryRad = directionRadOf(terrain.bestFlatDir())
                + pctx.cascadeAxisRotation();
        double secondaryRad = primaryRad + Math.PI / 2;
        int civicRing = ring1; // estimate
        int primaryArmLen = ring2 * 2 + totalBuildings * 3 + 32;
        int secondaryArmLen = (int) (primaryArmLen * 0.75);

        // Four arm endpoints (pulled outside civicRing).
        BlockPos primaryEndA = pctx.solidSurface(armEnd(centre, primaryRad, primaryArmLen, civicRing));
        BlockPos primaryEndB = pctx.solidSurface(armEnd(centre, primaryRad + Math.PI, primaryArmLen, civicRing));
        BlockPos secondaryEndA = pctx.solidSurface(armEnd(centre, secondaryRad, secondaryArmLen, civicRing));
        BlockPos secondaryEndB = pctx.solidSurface(armEnd(centre, secondaryRad + Math.PI, secondaryArmLen, civicRing));
        BlockPos primaryStartA = pctx.solidSurface(armStart(centre, primaryRad, civicRing));
        BlockPos primaryStartB = pctx.solidSurface(armStart(centre, primaryRad + Math.PI, civicRing));
        BlockPos secondaryStartA = pctx.solidSurface(armStart(centre, secondaryRad, civicRing));
        BlockPos secondaryStartB = pctx.solidSurface(armStart(centre, secondaryRad + Math.PI, civicRing));

        EdgeRef armPA = EdgeRef.of("crossroads_arm_0");
        EdgeRef armPB = EdgeRef.of("crossroads_arm_1");
        EdgeRef armSA = EdgeRef.of("crossroads_arm_2");
        EdgeRef armSB = EdgeRef.of("crossroads_arm_3");

        List<RoadDeclaration> roads = List.of(
                new RoadDeclaration(armPA,
                        EdgeRole.SPINE, RoadShape.RoadTier.VILLAGE_ROAD,
                        new RoadPrimitive.StraightRoad(
                                primaryStartA, primaryEndA, 8.0,
                                RoadShape.RoadTier.VILLAGE_ROAD)),
                new RoadDeclaration(armPB,
                        EdgeRole.SPINE, RoadShape.RoadTier.VILLAGE_ROAD,
                        new RoadPrimitive.StraightRoad(
                                primaryStartB, primaryEndB, 8.0,
                                RoadShape.RoadTier.VILLAGE_ROAD)),
                new RoadDeclaration(armSA,
                        EdgeRole.SPUR, RoadShape.RoadTier.VILLAGE_PATH,
                        new RoadPrimitive.StraightRoad(
                                secondaryStartA, secondaryEndA, 5.0,
                                RoadShape.RoadTier.VILLAGE_PATH)),
                new RoadDeclaration(armSB,
                        EdgeRole.SPUR, RoadShape.RoadTier.VILLAGE_PATH,
                        new RoadPrimitive.StraightRoad(
                                secondaryStartB, secondaryEndB, 5.0,
                                RoadShape.RoadTier.VILLAGE_PATH)));

        SectorRef civicSec = SectorRef.of("plaza_civic");
        var plaza = new PlazaDeclaration(centre, ring1,
                PlazaShape.SQUARE, PlazaPurpose.CIVIC, civicSec);

        int civicCap = Math.max(2, Math.min(9, totalBuildings / 4));
        EdgeRef[] armRefs = {armPA, armPB, armSA, armSB};

        List<SectorDeclaration> sectors = new ArrayList<>();
        sectors.add(new SectorDeclaration("plaza_civic",
                SectorRole.CIVIC_RING, BuildingZone.CIVIC,
                civicCap, 46, null));
        for (int i = 0; i < 4; i++) {
            sectors.add(new SectorDeclaration(
                    "crossroads_arm_" + i,
                    SectorRole.SPUR_CLUSTER,
                    BuildingZone.PRODUCTION,
                    ARM_CAPACITY, 16, armRefs[i]));
        }
        sectors.add(new SectorDeclaration("crossroads_outer_agri",
                SectorRole.AGRICULTURAL_FRINGE, BuildingZone.AGRICULTURAL,
                UNLIMITED_CAPACITY, 18, null));
        sectors.add(new SectorDeclaration("crossroads_outer_defense",
                SectorRole.DEFENSIVE_FRINGE, BuildingZone.DEFENSIVE,
                UNLIMITED_CAPACITY, 18, null));

        List<SlotIntention> intentions = new ArrayList<>();
        intentions.add(new SlotIntention(civicSec, TAGS_PRIME_CIVIC,
                1, 46, new Anchor.PlazaPerimeter(civicSec, 6)));
        intentions.add(new SlotIntention(civicSec, TAGS_SECONDARY_CIVIC,
                Math.max(0, civicCap - 1), 46,
                new Anchor.PlazaPerimeter(civicSec, 6)));
        for (int i = 0; i < 4; i++) {
            intentions.add(new SlotIntention(
                    SectorRef.of("crossroads_arm_" + i),
                    TAGS_ARM_ROAD,
                    ARM_CAPACITY, 16,
                    new Anchor.RoadAlong(armRefs[i], 8, true)));
        }
        intentions.add(new SlotIntention(
                SectorRef.of("crossroads_outer_agri"),
                TAGS_AGRI, 8, 18,
                new Anchor.RingValidated(ring2 + 15, 0.0)));
        intentions.add(new SlotIntention(
                SectorRef.of("crossroads_outer_defense"),
                TAGS_DEFENSE, 6, 18,
                new Anchor.RingValidated(ring2 + 6, 0.0)));

        Map<AnchorKind, BlockPos> namedAnchors = new LinkedHashMap<>();
        namedAnchors.put(AnchorKind.TOWN_SQUARE, centre);
        namedAnchors.put(AnchorKind.MAIN_GATE, primaryEndA);
        namedAnchors.put(AnchorKind.SECONDARY_GATE, primaryEndB);

        return new LayoutBlueprint(
                ShapeType.CROSSROADS, roads,
                List.of(plaza), sectors, intentions, namedAnchors);
    }

    @Override
    public LayoutBlueprint reEmit(ReEmitReason reason, PlanContext pctx) {
        return switch (reason) {
            case ReEmitReason.SevereTruncation t -> {
                pctx.recordTruncation();
                if (pctx.cascadeRetryCount() < 1) {
                    pctx.recordCascadeRetry();
                    pctx.setCascadeAxisRotation(
                            pctx.cascadeAxisRotation() + Math.PI / 4);
                    // 45° rotation for CROSSROADS — flips primary/
                    // secondary axes so a truncated arm gets a new
                    // direction.
                    yield compose(pctx);
                }
                yield null;
            }
            case ReEmitReason.SlotsDropped s -> null;
            case ReEmitReason.SectorStarved s -> null;
            case ReEmitReason.ValidationFailed v -> null;
        };
    }

    private static BlockPos armStart(BlockPos sq, double rad, int civicRing) {
        return new BlockPos(
                sq.getX() + (int) Math.round(Math.cos(rad) * civicRing),
                sq.getY(),
                sq.getZ() + (int) Math.round(Math.sin(rad) * civicRing));
    }

    private static BlockPos armEnd(BlockPos sq, double rad, int len, int civicRing) {
        return new BlockPos(
                sq.getX() + (int) Math.round(Math.cos(rad) * (civicRing + len)),
                sq.getY(),
                sq.getZ() + (int) Math.round(Math.sin(rad) * (civicRing + len)));
    }

    private static double directionRadOf(TerrainAnalyzer.FlatDirection dir) {
        return switch (dir) {
            case EAST  -> 0;
            case SOUTH -> Math.PI / 2;
            case WEST  -> Math.PI;
            case NORTH -> -Math.PI / 2;
        };
    }
}
