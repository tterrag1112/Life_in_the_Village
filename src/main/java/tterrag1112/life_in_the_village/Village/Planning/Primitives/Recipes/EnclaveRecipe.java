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
 * Phase D: declarative port of ENCLAVE — fortified compound. Spec
 * doc §5.6 names ENCLAVE as the canonical {@link Anchor.RegionalGather}
 * use case ("ENCLAVE-style hand-rolled courtyards without a formal
 * road graph").
 *
 * <p>Pre-Phase-A used 4 {@code LayoutPrimitive.LinearRow} segments
 * forming a square perimeter, with buildings facing inward. The
 * declarative port uses 4 {@link Anchor.RegionalGather} intentions
 * around each wall midpoint plus 1 RegionalGather for the courtyard
 * interior. The "perfect wall aesthetic" of buildings forming a
 * tight line is lost; the "compound with buildings on each side"
 * structure is preserved.
 *
 * <p>One short gate road (gap in the wall) gives the matcher a
 * MAIN_GATE anchor. The gate side is the cardinal nearest
 * {@code terrain.bestFlatDir()}.
 */
public final class EnclaveRecipe extends BaseRecipe {

    private static final int WALL_LENGTH = 56;
    private static final int COURTYARD_HALF = WALL_LENGTH / 2 - 4;
    private static final int WALL_REGION_RADIUS = 18;
    private static final int WALL_MIN_SPACING = 7;
    private static final int COURTYARD_REGION_RADIUS = 12;
    private static final int COURTYARD_MIN_SPACING = 8;

    private static final Set<SlotTag> TAGS_PRIME_CIVIC =
            EnumSet.of(SlotTag.PRIME_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_SECONDARY_CIVIC =
            EnumSet.of(SlotTag.SECONDARY_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_WALL = EnumSet.of(
            SlotTag.WALL_ADJACENT, SlotTag.RESIDENTIAL_INFILL,
            SlotTag.PRODUCTION_INFILL, SlotTag.BACKFILL);

    @Override
    public LayoutBlueprint compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();

        // Gate side: cardinal nearest bestFlatDir.
        double gateRad = directionRadOf(terrain.bestFlatDir());
        int gateSide = nearestCardinalIndex(gateRad);
        // 0=east, 1=south, 2=west, 3=north (math-angle convention).

        int half = WALL_LENGTH / 2;
        BlockPos eastMid = pctx.solidSurface(centre.offset(half, 0, 0));
        BlockPos southMid = pctx.solidSurface(centre.offset(0, 0, half));
        BlockPos westMid = pctx.solidSurface(centre.offset(-half, 0, 0));
        BlockPos northMid = pctx.solidSurface(centre.offset(0, 0, -half));
        BlockPos[] wallMids = {eastMid, southMid, westMid, northMid};

        // Single gate road outward from the gate side. Length matches
        // the rough scale of the courtyard.
        BlockPos gateStart = wallMids[gateSide];
        double gateAngle = switch (gateSide) {
            case 0 -> 0;                  // east
            case 1 -> Math.PI / 2;        // south
            case 2 -> Math.PI;            // west
            case 3 -> -Math.PI / 2;       // north
            default -> 0;
        };
        int gateLen = pctx.density.getRing2Radius() + 8;
        BlockPos gateEnd = pctx.solidSurface(new BlockPos(
                gateStart.getX() + (int) Math.round(Math.cos(gateAngle) * gateLen),
                gateStart.getY(),
                gateStart.getZ() + (int) Math.round(Math.sin(gateAngle) * gateLen)));
        EdgeRef gateRef = EdgeRef.of("enclave_gate");
        var gatePrim = new RoadPrimitive.StraightRoad(
                gateStart, gateEnd, 4.0, RoadShape.RoadTier.VILLAGE_PATH);

        // SQUARE plaza inside courtyard.
        SectorRef civicSec = SectorRef.of("plaza_civic");
        var plaza = new PlazaDeclaration(centre,
                COURTYARD_HALF,
                PlazaShape.SQUARE, PlazaPurpose.CIVIC, civicSec);

        List<SectorDeclaration> sectors = new ArrayList<>();
        sectors.add(new SectorDeclaration("plaza_civic",
                SectorRole.CIVIC_TIGHT, BuildingZone.CIVIC,
                3, 32, null));
        for (int side = 0; side < 4; side++) {
            sectors.add(new SectorDeclaration(
                    "enclave_wall_" + side,
                    SectorRole.DEFENSIVE_FRINGE,
                    side == gateSide ? BuildingZone.RESIDENTIAL
                            : BuildingZone.DEFENSIVE,
                    side == gateSide ? 3 : 5,
                    16, null));
        }

        List<SlotIntention> intentions = new ArrayList<>();
        intentions.add(new SlotIntention(civicSec, TAGS_PRIME_CIVIC,
                1, 32, new Anchor.PlazaPerimeter(civicSec, 4)));
        intentions.add(new SlotIntention(civicSec, TAGS_SECONDARY_CIVIC,
                2, 32, new Anchor.PlazaPerimeter(civicSec, 4)));
        for (int side = 0; side < 4; side++) {
            int desired = (side == gateSide) ? 2 : 4;
            intentions.add(new SlotIntention(
                    SectorRef.of("enclave_wall_" + side),
                    TAGS_WALL, desired, 16,
                    new Anchor.RegionalGather(
                            wallMids[side],
                            WALL_REGION_RADIUS,
                            WALL_MIN_SPACING)));
        }

        Map<AnchorKind, BlockPos> namedAnchors = new LinkedHashMap<>();
        namedAnchors.put(AnchorKind.TOWN_SQUARE, centre);
        namedAnchors.put(AnchorKind.MAIN_GATE, gateEnd);

        return new LayoutBlueprint(
                ShapeType.ENCLAVE,
                List.of(new RoadDeclaration(gateRef,
                        EdgeRole.SPINE, RoadShape.RoadTier.VILLAGE_PATH,
                        gatePrim)),
                List.of(plaza),
                sectors,
                intentions,
                namedAnchors);
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

    private static double directionRadOf(TerrainAnalyzer.FlatDirection dir) {
        return switch (dir) {
            case EAST  -> 0;
            case SOUTH -> Math.PI / 2;
            case WEST  -> Math.PI;
            case NORTH -> -Math.PI / 2;
        };
    }

    /** Quantize an angle to the nearest cardinal index:
     *  0=east, 1=south, 2=west, 3=north (math-angle convention). */
    private static int nearestCardinalIndex(double rad) {
        double normalized = ((rad % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
        int idx = (int) Math.round(normalized / (Math.PI / 2));
        return idx % 4;
    }
}
