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
 * Phase D: declarative port of DUMBELL — civic ring + production
 * ring connected by a single trunk road. Three production spurs
 * branch off Ring B.
 *
 * <p>Pre-Phase-A geometry preserved:
 * <ul>
 *   <li>Civic plaza at centre (IRREGULAR).</li>
 *   <li>Trunk: StraightRoad VILLAGE_PATH from civic edge to ring B.
 *       Length = ring1 + ring2.</li>
 *   <li>Ring B: Ring primitive at ringBCentre, radius
 *       {@code max(8, ring1/2 + 4)}.</li>
 *   <li>3 spurs from Ring B at angles {0, +π/2, -π/2} relative to
 *       mainDirRad. Spur length = 8 + rng.nextInt(6) (FOOTPATH).</li>
 *   <li>Outer agri at ringBCentre {@code [ring2+4, ring2+20]} and
 *       defense at centre {@code [ring2+0, ring2+12]}.</li>
 * </ul>
 *
 * <p>Pre-realises trunk + ringB centerlines via
 * {@link BaseRecipe#computeAndRecord} so spurs can branch from
 * Ring B's actual perimeter (same pattern as RADIAL's
 * cluster-arc-on-spur-tip).
 */
public final class DumbellRecipe extends BaseRecipe {

    private static final int UNLIMITED_CAPACITY = 1024;
    private static final int SPUR_CAPACITY = 6;

    private static final Set<SlotTag> TAGS_PRIME_CIVIC =
            EnumSet.of(SlotTag.PRIME_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_TRUNK =
            EnumSet.of(SlotTag.RESIDENTIAL_INFILL, SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_RING_B =
            EnumSet.of(SlotTag.PRODUCTION_CLUSTER, SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_SPUR_END =
            EnumSet.of(SlotTag.PRODUCTION_SPUR_END, SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_AGRI =
            EnumSet.of(SlotTag.FIELD_EDGE, SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_DEFENSE =
            EnumSet.of(SlotTag.WALL_ADJACENT, SlotTag.ROAD_ADJACENT);

    @Override
    public LayoutBlueprint compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();
        int ring1 = pctx.density.getRing1Radius();
        int ring2 = pctx.density.getRing2Radius();

        double mainDirRad = directionRadOf(terrain.bestFlatDir())
                + pctx.cascadeAxisRotation();

        // Trunk start at civic ring's outer edge — match pre-Phase-A
        // ringRoadOuter = max(9, civicRing - 3).
        int civicRingEstimate = ring1;
        int ringRoadOuter = Math.max(9, civicRingEstimate - 3);
        BlockPos trunkStart = pctx.solidSurface(new BlockPos(
                centre.getX() + (int) Math.round(Math.cos(mainDirRad) * ringRoadOuter),
                centre.getY(),
                centre.getZ() + (int) Math.round(Math.sin(mainDirRad) * ringRoadOuter)));

        int ringBRadius = Math.max(8, ring1 / 2 + 4);
        int armLength = ring1 + ring2;

        BlockPos trunkEnd = pctx.solidSurface(new BlockPos(
                trunkStart.getX() + (int) Math.round(Math.cos(mainDirRad) * armLength),
                trunkStart.getY(),
                trunkStart.getZ() + (int) Math.round(Math.sin(mainDirRad) * armLength)));
        BlockPos ringBCentre = pctx.solidSurface(new BlockPos(
                trunkEnd.getX() + (int) Math.round(Math.cos(mainDirRad) * ringBRadius),
                trunkEnd.getY(),
                trunkEnd.getZ() + (int) Math.round(Math.sin(mainDirRad) * ringBRadius)));

        EdgeRef trunkRef = EdgeRef.of("dumbell_trunk");
        var trunkPrim = new RoadPrimitive.StraightRoad(
                trunkStart, trunkEnd, 4.0, RoadShape.RoadTier.VILLAGE_PATH);

        EdgeRef ringBRef = EdgeRef.of("dumbell_ringB");
        var ringBPrim = new RoadPrimitive.Ring(
                ringBCentre, ringBRadius, 2.0, RoadShape.RoadTier.VILLAGE_PATH);

        // Pre-realise ring B's centerline so spurs can branch from it.
        RoadResult ringBResult = computeAndRecord(ringBPrim, pctx);
        List<BlockPos> ringBCenterline = ringBResult.centerline();

        // 3 spurs at angles {0, +π/2, -π/2} relative to mainDirRad.
        double[] spurAngles = {
                mainDirRad,
                mainDirRad + Math.PI / 2,
                mainDirRad - Math.PI / 2
        };
        List<RoadDeclaration> roads = new ArrayList<>();
        roads.add(new RoadDeclaration(trunkRef,
                EdgeRole.SPINE, RoadShape.RoadTier.VILLAGE_PATH, trunkPrim));
        roads.add(new RoadDeclaration(ringBRef,
                EdgeRole.RING, RoadShape.RoadTier.VILLAGE_PATH, ringBPrim));

        List<EdgeRef> spurRefs = new ArrayList<>();
        for (int i = 0; i < spurAngles.length; i++) {
            double angle = spurAngles[i];
            BlockPos branchHint = new BlockPos(
                    ringBCentre.getX() + (int) Math.round(Math.cos(angle) * ringBRadius),
                    ringBCentre.getY(),
                    ringBCentre.getZ() + (int) Math.round(Math.sin(angle) * ringBRadius));
            int spurLength = 8 + pctx.rng.nextInt(6);
            EdgeRef spurRef = EdgeRef.of("dumbell_spur_" + i);
            spurRefs.add(spurRef);
            roads.add(new RoadDeclaration(spurRef,
                    EdgeRole.SPUR, RoadShape.RoadTier.FOOTPATH,
                    new RoadPrimitive.Spur(
                            ringBCenterline, branchHint, angle,
                            spurLength, 2.0, RoadShape.RoadTier.FOOTPATH)));
        }

        SectorRef civicSec = SectorRef.of("plaza_civic");
        var plaza = new PlazaDeclaration(centre,
                civicRingEstimate,
                PlazaShape.IRREGULAR, PlazaPurpose.CIVIC, civicSec);

        List<SectorDeclaration> sectors = new ArrayList<>();
        sectors.add(new SectorDeclaration("plaza_civic",
                SectorRole.CIVIC_RING, BuildingZone.CIVIC,
                3, 46, null));
        sectors.add(new SectorDeclaration("dumbell_trunk",
                SectorRole.RESIDENTIAL_INFILL, BuildingZone.RESIDENTIAL,
                UNLIMITED_CAPACITY, 16, trunkRef));
        sectors.add(new SectorDeclaration("dumbell_ringB",
                SectorRole.RESIDENTIAL_INFILL, BuildingZone.PRODUCTION,
                UNLIMITED_CAPACITY, 16, ringBRef));
        for (int i = 0; i < spurRefs.size(); i++) {
            sectors.add(new SectorDeclaration(
                    "dumbell_spur_" + i,
                    SectorRole.SPUR_CLUSTER, BuildingZone.PRODUCTION,
                    SPUR_CAPACITY, 16, spurRefs.get(i)));
        }
        sectors.add(new SectorDeclaration("dumbell_outer_agri",
                SectorRole.AGRICULTURAL_FRINGE, BuildingZone.AGRICULTURAL,
                UNLIMITED_CAPACITY, 18, null));
        sectors.add(new SectorDeclaration("dumbell_outer_defense",
                SectorRole.DEFENSIVE_FRINGE, BuildingZone.DEFENSIVE,
                UNLIMITED_CAPACITY, 18, null));

        List<SlotIntention> intentions = new ArrayList<>();
        intentions.add(new SlotIntention(civicSec, TAGS_PRIME_CIVIC,
                1, 46, new Anchor.PlazaPerimeter(civicSec, 6)));
        intentions.add(new SlotIntention(SectorRef.of("dumbell_trunk"),
                TAGS_TRUNK, 6, 35,
                new Anchor.RoadAlong(trunkRef, 7, true)));
        intentions.add(new SlotIntention(SectorRef.of("dumbell_ringB"),
                TAGS_RING_B, 8, 16,
                new Anchor.RoadAlong(ringBRef, 6, true)));
        for (int i = 0; i < spurRefs.size(); i++) {
            intentions.add(new SlotIntention(
                    SectorRef.of("dumbell_spur_" + i),
                    TAGS_SPUR_END, 3, 16,
                    new Anchor.RoadAlong(spurRefs.get(i), 5, true)));
        }
        // Agri ring centred on ringBCentre (offset from village
        // centre by armLength + ringBRadius along mainDir). The
        // RingValidated resolver only operates relative to
        // layout.getCenter(); approximate by using the village centre
        // ring with adjusted radius.
        intentions.add(new SlotIntention(
                SectorRef.of("dumbell_outer_agri"),
                TAGS_AGRI, 4, 18,
                new Anchor.RingValidated(ring2 + 12, 0.0)));
        intentions.add(new SlotIntention(
                SectorRef.of("dumbell_outer_defense"),
                TAGS_DEFENSE, 4, 18,
                new Anchor.RingValidated(ring2 + 6, 0.0)));

        Map<AnchorKind, BlockPos> namedAnchors = new LinkedHashMap<>();
        namedAnchors.put(AnchorKind.TOWN_SQUARE, centre);
        namedAnchors.put(AnchorKind.MAIN_GATE, ringBCentre);

        return new LayoutBlueprint(
                ShapeType.DUMBELL,
                roads,
                List.of(plaza),
                sectors,
                intentions,
                namedAnchors);
    }

    @Override
    public LayoutBlueprint reEmit(ReEmitReason reason, PlanContext pctx) {
        return switch (reason) {
            case ReEmitReason.SevereTruncation t -> {
                pctx.recordTruncation();
                if (pctx.cascadeRetryCount() < 1) {
                    pctx.recordCascadeRetry();
                    pctx.setCascadeAxisRotation(
                            pctx.cascadeAxisRotation() + Math.PI / 2);
                    yield compose(pctx);
                }
                yield null;
            }
            case ReEmitReason.SlotsDropped s -> null;
            case ReEmitReason.SectorStarved s -> null;
            case ReEmitReason.ValidationFailed v -> null;
        };
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
