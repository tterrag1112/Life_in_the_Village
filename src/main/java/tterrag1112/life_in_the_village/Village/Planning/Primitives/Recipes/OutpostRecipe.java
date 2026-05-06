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
 * Phase D: declarative port of OUTPOST — small fortified core with
 * outlying stations.
 *
 * <p>Pre-Phase-A pattern preserved:
 * <ul>
 *   <li>SQUARE plaza at centre.</li>
 *   <li>Inner defense ring road at civicRing+8 (Ring primitive,
 *       VILLAGE_PATH).</li>
 *   <li>1–2 outlying station stubs (StraightRoad FOOTPATH tier) at
 *       opposite angles, length {@code ring2*2 + 24}.</li>
 *   <li>Defensive ring + production scatter at station ends.</li>
 *   <li>Agri fringe at {@code [ring2+4, ring2+20]}.</li>
 * </ul>
 */
public final class OutpostRecipe extends BaseRecipe {

    private static final int UNLIMITED_CAPACITY = 1024;
    private static final int STATION_CAPACITY = 6;

    private static final Set<SlotTag> TAGS_PRIME_CIVIC =
            EnumSet.of(SlotTag.PRIME_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_SECONDARY_CIVIC =
            EnumSet.of(SlotTag.SECONDARY_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_DEFENSE_RING =
            EnumSet.of(SlotTag.WALL_ADJACENT, SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_STATION =
            EnumSet.of(SlotTag.ROAD_ADJACENT, SlotTag.PRODUCTION_INFILL,
                    SlotTag.RESIDENTIAL_INFILL, SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_AGRI =
            EnumSet.of(SlotTag.FIELD_EDGE, SlotTag.ROAD_ADJACENT);

    @Override
    public LayoutBlueprint compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();
        int totalBuildings = pctx.remaining.size();

        int civicCap = Math.max(2, Math.min(4, totalBuildings / 5));
        int civicRing = pctx.density.getRing1Radius();   // estimate; actual set by installPlaza
        int defRingR = civicRing + 8;
        int ring2 = pctx.density.getRing2Radius();

        double baseAngle = directionRadOf(terrain.bestFlatDir())
                + pctx.cascadeAxisRotation();

        SectorRef civicSec = SectorRef.of("plaza_civic");

        var plaza = new PlazaDeclaration(
                centre, civicRing,
                PlazaShape.SQUARE, PlazaPurpose.CIVIC, civicSec);

        // Inner defense ring road — Ring primitive at defRingR.
        EdgeRef defRingRef = EdgeRef.of("outpost_def_ring");
        var defRingPrim = new RoadPrimitive.Ring(
                centre, defRingR, 1.5, RoadShape.RoadTier.VILLAGE_PATH);

        // Station stubs at opposite angles. Always 2 stubs declared
        // (the matcher fills whichever has buildings; an empty stub
        // is just an unfilled FOOTPATH).
        int stationDist = ring2 * 2 + 24;
        List<RoadDeclaration> roads = new ArrayList<>();
        roads.add(new RoadDeclaration(defRingRef,
                EdgeRole.RING, RoadShape.RoadTier.VILLAGE_PATH,
                defRingPrim));

        BlockPos mainGate = null;
        List<EdgeRef> stationRefs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            double angle = baseAngle + (i == 0 ? 0 : Math.PI);
            BlockPos pathStart = pctx.solidSurface(new BlockPos(
                    centre.getX() + (int) Math.round(Math.cos(angle) * (civicRing + 8)),
                    centre.getY(),
                    centre.getZ() + (int) Math.round(Math.sin(angle) * (civicRing + 8))));
            BlockPos stationFocal = pctx.solidSurface(new BlockPos(
                    centre.getX() + (int) Math.round(Math.cos(angle) * stationDist),
                    centre.getY(),
                    centre.getZ() + (int) Math.round(Math.sin(angle) * stationDist)));
            EdgeRef stRef = EdgeRef.of("outpost_station_" + i);
            stationRefs.add(stRef);
            roads.add(new RoadDeclaration(stRef,
                    EdgeRole.SPUR, RoadShape.RoadTier.FOOTPATH,
                    new RoadPrimitive.StraightRoad(
                            pathStart, stationFocal, 4.0,
                            RoadShape.RoadTier.FOOTPATH)));
            if (i == 0) mainGate = stationFocal;
        }

        List<SectorDeclaration> sectors = new ArrayList<>();
        sectors.add(new SectorDeclaration("plaza_civic",
                SectorRole.CIVIC_RING, BuildingZone.CIVIC,
                civicCap, 46, null));
        sectors.add(new SectorDeclaration("outpost_def_ring",
                SectorRole.DEFENSIVE_FRINGE, BuildingZone.DEFENSIVE,
                UNLIMITED_CAPACITY, 16, defRingRef));
        for (int i = 0; i < stationRefs.size(); i++) {
            sectors.add(new SectorDeclaration(
                    "outpost_station_" + i,
                    SectorRole.SPUR_CLUSTER,
                    i == 0 ? BuildingZone.RESIDENTIAL : BuildingZone.PRODUCTION,
                    STATION_CAPACITY, 16, stationRefs.get(i)));
        }
        sectors.add(new SectorDeclaration("outpost_outer_agri",
                SectorRole.AGRICULTURAL_FRINGE, BuildingZone.AGRICULTURAL,
                UNLIMITED_CAPACITY, 18, null));

        List<SlotIntention> intentions = new ArrayList<>();
        intentions.add(new SlotIntention(civicSec, TAGS_PRIME_CIVIC,
                1, 46, new Anchor.PlazaPerimeter(civicSec, 6)));
        intentions.add(new SlotIntention(civicSec, TAGS_SECONDARY_CIVIC,
                Math.max(0, civicCap - 1), 46,
                new Anchor.PlazaPerimeter(civicSec, 6)));
        // Defense ring — slots evenly spaced around the inner ring.
        intentions.add(new SlotIntention(
                SectorRef.of("outpost_def_ring"),
                TAGS_DEFENSE_RING,
                8, 16,
                new Anchor.RingValidated(defRingR, 0.0)));
        for (int i = 0; i < stationRefs.size(); i++) {
            intentions.add(new SlotIntention(
                    SectorRef.of("outpost_station_" + i),
                    TAGS_STATION,
                    6, 16,
                    new Anchor.RoadAlong(stationRefs.get(i), 6, true)));
        }
        // Agri fringe at midpoint of [ring2+4, ring2+20].
        intentions.add(new SlotIntention(
                SectorRef.of("outpost_outer_agri"),
                TAGS_AGRI,
                4, 18,
                new Anchor.RingValidated(ring2 + 12, 0.0)));

        Map<AnchorKind, BlockPos> namedAnchors = new LinkedHashMap<>();
        namedAnchors.put(AnchorKind.TOWN_SQUARE, centre);
        if (mainGate != null) {
            namedAnchors.put(AnchorKind.MAIN_GATE, mainGate);
        }

        return new LayoutBlueprint(
                ShapeType.OUTPOST,
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
