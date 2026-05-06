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
 * Phase D: declarative port of DUAL_PLAZA — twin-square village.
 *
 * <p>Two plazas separated along the terrain bias by
 * {@code separation = max(48, ring2*2 + totalBuildings*2)}.
 * Primary plaza is CIRCLE/CIVIC; secondary is SQUARE/MARKET.
 * Connector road between them, plus outward gate roads from each.
 *
 * <p>Civic intentions only target the primary plaza. The
 * SlotEmitter's {@link Anchor.PlazaPerimeter} resolver reads
 * {@code layout.getPlazas().get(0)} (i.e. the first registered
 * plaza); the matcher's civic-first claim path likewise reads
 * {@code layout.getPlaza()} which returns plaza[0]. This parity
 * with pre-Phase-A means the secondary plaza is geometric/
 * decorative only — same behavior the matcher had before.
 *
 * <p>A future quirk-resolution pass could route civic slots to a
 * specific plaza by sector ref; for now the secondary's
 * {@code civicSector} field is recorded but unused at emission.
 */
public final class DualPlazaRecipe extends BaseRecipe {

    private static final int UNLIMITED_CAPACITY = 1024;

    private static final Set<SlotTag> TAGS_PRIME_CIVIC =
            EnumSet.of(SlotTag.PRIME_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_SECONDARY_CIVIC =
            EnumSet.of(SlotTag.SECONDARY_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_ROAD = EnumSet.of(
            SlotTag.ROAD_ADJACENT, SlotTag.RESIDENTIAL_INFILL,
            SlotTag.PRODUCTION_INFILL, SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_AGRI =
            EnumSet.of(SlotTag.FIELD_EDGE, SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_DEFENSE =
            EnumSet.of(SlotTag.WALL_ADJACENT, SlotTag.ROAD_ADJACENT);

    @Override
    public LayoutBlueprint compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();
        int totalBuildings = pctx.remaining.size();
        int ring2 = pctx.density.getRing2Radius();

        double mainDirRad = directionRadOf(terrain.bestFlatDir())
                + pctx.cascadeAxisRotation();
        int separation = Math.max(48, ring2 * 2 + totalBuildings * 2);
        int half = separation / 2;

        BlockPos sq1 = pctx.solidSurface(new BlockPos(
                centre.getX() + (int) Math.round(Math.cos(mainDirRad) * half),
                centre.getY(),
                centre.getZ() + (int) Math.round(Math.sin(mainDirRad) * half)));
        BlockPos sq2 = pctx.solidSurface(new BlockPos(
                centre.getX() + (int) Math.round(Math.cos(mainDirRad + Math.PI) * half),
                centre.getY(),
                centre.getZ() + (int) Math.round(Math.sin(mainDirRad + Math.PI) * half)));

        int primaryCap = Math.max(2, Math.min(5, totalBuildings / 4));
        int secondaryCap = Math.max(1, primaryCap - 2);

        SectorRef civicSec1 = SectorRef.of("plaza_civic_primary");
        SectorRef civicSec2 = SectorRef.of("plaza_civic_secondary");
        int sq1Ring = pctx.density.getRing1Radius();

        // Connector road: from sq1's far side to sq2's near side.
        BlockPos roadStart = pctx.solidSurface(new BlockPos(
                sq1.getX() + (int) Math.round(Math.cos(mainDirRad + Math.PI) * sq1Ring),
                sq1.getY(),
                sq1.getZ() + (int) Math.round(Math.sin(mainDirRad + Math.PI) * sq1Ring)));
        BlockPos roadEnd = pctx.solidSurface(new BlockPos(
                sq2.getX() + (int) Math.round(Math.cos(mainDirRad) * sq1Ring),
                sq2.getY(),
                sq2.getZ() + (int) Math.round(Math.sin(mainDirRad) * sq1Ring)));
        EdgeRef connectorRef = EdgeRef.of("dualplaza_connector");
        var connectorPrim = new RoadPrimitive.StraightRoad(
                roadStart, roadEnd, 7.0, RoadShape.RoadTier.VILLAGE_ROAD);

        // Outward gate roads — one per plaza, pointing away from centre.
        int gateLen = ring2 + 16;
        BlockPos gate1Start = pctx.solidSurface(new BlockPos(
                sq1.getX() + (int) Math.round(Math.cos(mainDirRad) * sq1Ring),
                sq1.getY(),
                sq1.getZ() + (int) Math.round(Math.sin(mainDirRad) * sq1Ring)));
        BlockPos gate1End = pctx.solidSurface(new BlockPos(
                sq1.getX() + (int) Math.round(Math.cos(mainDirRad) * (sq1Ring + gateLen)),
                sq1.getY(),
                sq1.getZ() + (int) Math.round(Math.sin(mainDirRad) * (sq1Ring + gateLen))));
        EdgeRef gate1Ref = EdgeRef.of("dualplaza_gate_primary");
        var gate1Prim = new RoadPrimitive.StraightRoad(
                gate1Start, gate1End, 6.0, RoadShape.RoadTier.VILLAGE_ROAD);

        BlockPos gate2Start = pctx.solidSurface(new BlockPos(
                sq2.getX() + (int) Math.round(Math.cos(mainDirRad + Math.PI) * sq1Ring),
                sq2.getY(),
                sq2.getZ() + (int) Math.round(Math.sin(mainDirRad + Math.PI) * sq1Ring)));
        BlockPos gate2End = pctx.solidSurface(new BlockPos(
                sq2.getX() + (int) Math.round(Math.cos(mainDirRad + Math.PI) * (sq1Ring + gateLen)),
                sq2.getY(),
                sq2.getZ() + (int) Math.round(Math.sin(mainDirRad + Math.PI) * (sq1Ring + gateLen))));
        EdgeRef gate2Ref = EdgeRef.of("dualplaza_gate_secondary");
        var gate2Prim = new RoadPrimitive.StraightRoad(
                gate2Start, gate2End, 6.0, RoadShape.RoadTier.VILLAGE_ROAD);

        List<RoadDeclaration> roads = List.of(
                new RoadDeclaration(connectorRef,
                        EdgeRole.SPINE, RoadShape.RoadTier.VILLAGE_ROAD,
                        connectorPrim),
                new RoadDeclaration(gate1Ref,
                        EdgeRole.SPINE, RoadShape.RoadTier.VILLAGE_ROAD,
                        gate1Prim),
                new RoadDeclaration(gate2Ref,
                        EdgeRole.SPINE, RoadShape.RoadTier.VILLAGE_ROAD,
                        gate2Prim));

        // Primary plaza first so it ends up as plazas.get(0).
        var plaza1 = new PlazaDeclaration(sq1,
                pctx.density.getRing1Radius(),
                PlazaShape.CIRCLE, PlazaPurpose.CIVIC, civicSec1);
        var plaza2 = new PlazaDeclaration(sq2,
                pctx.density.getRing1Radius(),
                PlazaShape.SQUARE, PlazaPurpose.MARKET, civicSec2);

        List<SectorDeclaration> sectors = new ArrayList<>();
        sectors.add(new SectorDeclaration("plaza_civic_primary",
                SectorRole.CIVIC_RING, BuildingZone.CIVIC,
                primaryCap, 46, null));
        sectors.add(new SectorDeclaration("plaza_civic_secondary",
                SectorRole.CIVIC_TIGHT, BuildingZone.CIVIC,
                secondaryCap, 46, null));
        sectors.add(new SectorDeclaration("dualplaza_connector",
                SectorRole.RESIDENTIAL_INFILL, BuildingZone.RESIDENTIAL,
                UNLIMITED_CAPACITY, 16, connectorRef));
        sectors.add(new SectorDeclaration("dualplaza_gate_primary",
                SectorRole.RESIDENTIAL_INFILL, BuildingZone.RESIDENTIAL,
                UNLIMITED_CAPACITY, 16, gate1Ref));
        sectors.add(new SectorDeclaration("dualplaza_gate_secondary",
                SectorRole.RESIDENTIAL_INFILL, BuildingZone.PRODUCTION,
                UNLIMITED_CAPACITY, 16, gate2Ref));
        sectors.add(new SectorDeclaration("dualplaza_outer_agri",
                SectorRole.AGRICULTURAL_FRINGE, BuildingZone.AGRICULTURAL,
                UNLIMITED_CAPACITY, 18, null));
        sectors.add(new SectorDeclaration("dualplaza_outer_defense",
                SectorRole.DEFENSIVE_FRINGE, BuildingZone.DEFENSIVE,
                UNLIMITED_CAPACITY, 18, null));

        List<SlotIntention> intentions = List.of(
                // Primary plaza civic — only the primary gets vertex
                // slots (parity with pre-Phase-A matcher behavior:
                // layout.getPlaza() returns plaza[0]).
                new SlotIntention(civicSec1, TAGS_PRIME_CIVIC,
                        1, 46,
                        new Anchor.PlazaPerimeter(civicSec1, 6)),
                new SlotIntention(civicSec1, TAGS_SECONDARY_CIVIC,
                        Math.max(0, primaryCap - 1), 46,
                        new Anchor.PlazaPerimeter(civicSec1, 6)),
                // Connector + gates: residential / production along
                // each road.
                new SlotIntention(SectorRef.of("dualplaza_connector"),
                        TAGS_ROAD, 10, 35,
                        new Anchor.RoadAlong(connectorRef, 8, true)),
                new SlotIntention(SectorRef.of("dualplaza_gate_primary"),
                        TAGS_ROAD, 6, 35,
                        new Anchor.RoadAlong(gate1Ref, 8, true)),
                new SlotIntention(SectorRef.of("dualplaza_gate_secondary"),
                        TAGS_ROAD, 6, 35,
                        new Anchor.RoadAlong(gate2Ref, 8, true)),
                // Agri midpoint of [ring2+8, ring2+26], defense midpoint
                // of [ring2+0, ring2+12].
                new SlotIntention(SectorRef.of("dualplaza_outer_agri"),
                        TAGS_AGRI, 6, 18,
                        new Anchor.RingValidated(ring2 + 17, 0.0)),
                new SlotIntention(SectorRef.of("dualplaza_outer_defense"),
                        TAGS_DEFENSE, 4, 18,
                        new Anchor.RingValidated(ring2 + 6, 0.0)));

        Map<AnchorKind, BlockPos> namedAnchors = new LinkedHashMap<>();
        namedAnchors.put(AnchorKind.TOWN_SQUARE, sq1);
        namedAnchors.put(AnchorKind.MAIN_GATE, gate1End);
        namedAnchors.put(AnchorKind.SECONDARY_GATE, gate2End);

        return new LayoutBlueprint(
                ShapeType.DUAL_PLAZA,
                roads,
                List.of(plaza1, plaza2),
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
