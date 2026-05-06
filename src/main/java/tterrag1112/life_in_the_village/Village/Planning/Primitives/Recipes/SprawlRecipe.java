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
 * Phase D: declarative port of SPRAWL — sparse, low-density frontier
 * village. One very long main road, no civic plaza ring, building
 * spread along the spine plus two outer rings (agri, defense).
 *
 * <p>Pre-Phase-A used {@code placeAgriculturalRing(8, 28)} +
 * {@code placeDefensiveRing(0, 12)} (RingBand on
 * {@code ring2 + offset} radii). Translated to two
 * {@link Anchor.RingValidated} intentions at the band midpoints.
 *
 * <p>The IRREGULAR plaza is preserved (sprawling layout aesthetic).
 * The recipe sets a nominal 1-vertex civic intention so a TOWN_HALL
 * lands at a plaza-perimeter slot when the polygon vertices survive
 * the spur-exit avoidance check.
 */
public final class SprawlRecipe extends BaseRecipe {

    private static final int UNLIMITED_CAPACITY = 1024;

    private static final Set<SlotTag> TAGS_PRIME_CIVIC =
            EnumSet.of(SlotTag.PRIME_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_MAIN_ROAD =
            EnumSet.of(SlotTag.ROAD_ADJACENT, SlotTag.RESIDENTIAL_INFILL,
                    SlotTag.CIVIC_ADJACENT, SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_AGRI =
            EnumSet.of(SlotTag.FIELD_EDGE, SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_DEFENSE =
            EnumSet.of(SlotTag.WALL_ADJACENT, SlotTag.ROAD_ADJACENT);

    @Override
    public LayoutBlueprint compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();
        int totalBuildings = pctx.remaining.size();

        // Direction biased by terrain + cumulative cascade rotation.
        double mainDirRad = directionRadOf(terrain.bestFlatDir())
                + pctx.cascadeAxisRotation();

        // Very long main road — much longer than LINEAR per building.
        // halfLength matches the pre-Phase-A formula.
        int halfLength = Math.max(80,
                (totalBuildings * 16) + pctx.density.getRing2Radius());

        BlockPos mainStart = pctx.solidSurface(new BlockPos(
                centre.getX() + (int) Math.round(Math.cos(mainDirRad) * halfLength),
                centre.getY(),
                centre.getZ() + (int) Math.round(Math.sin(mainDirRad) * halfLength)));
        BlockPos mainEnd = pctx.solidSurface(new BlockPos(
                centre.getX() + (int) Math.round(Math.cos(mainDirRad + Math.PI) * halfLength),
                centre.getY(),
                centre.getZ() + (int) Math.round(Math.sin(mainDirRad + Math.PI) * halfLength)));

        EdgeRef mainRef = EdgeRef.of("sprawl_main");
        var mainPrimitive = new RoadPrimitive.StraightRoad(
                mainStart, mainEnd, 10.0, RoadShape.RoadTier.VILLAGE_ROAD);

        SectorRef civicSec = SectorRef.of("plaza_civic");

        var plaza = new PlazaDeclaration(
                centre, pctx.density.getRing1Radius(),
                PlazaShape.IRREGULAR, PlazaPurpose.CIVIC, civicSec);

        List<SectorDeclaration> sectors = List.of(
                new SectorDeclaration("plaza_civic",
                        SectorRole.CIVIC_RING, BuildingZone.CIVIC,
                        2, 46, null),
                new SectorDeclaration("sprawl_main_road",
                        SectorRole.RESIDENTIAL_INFILL, BuildingZone.RESIDENTIAL,
                        UNLIMITED_CAPACITY, 16, mainRef),
                new SectorDeclaration("sprawl_outer_agri",
                        SectorRole.AGRICULTURAL_FRINGE, BuildingZone.AGRICULTURAL,
                        UNLIMITED_CAPACITY, 18, null),
                new SectorDeclaration("sprawl_outer_defense",
                        SectorRole.DEFENSIVE_FRINGE, BuildingZone.DEFENSIVE,
                        UNLIMITED_CAPACITY, 18, null));

        // Ring radii match pre-Phase-A:
        // agri = [ring2+8, ring2+28] → midpoint ring2+18
        // defense = [ring2+0, ring2+12] → midpoint ring2+6
        int ring2 = pctx.density.getRing2Radius();

        List<SlotIntention> intentions = List.of(
                new SlotIntention(civicSec, TAGS_PRIME_CIVIC,
                        1, 46,
                        new Anchor.PlazaPerimeter(civicSec, 6)),
                new SlotIntention(SectorRef.of("sprawl_main_road"),
                        TAGS_MAIN_ROAD,
                        12, 35,
                        new Anchor.RoadAlong(mainRef, 12, true)),
                new SlotIntention(SectorRef.of("sprawl_outer_agri"),
                        TAGS_AGRI,
                        6, 18,
                        new Anchor.RingValidated(ring2 + 18, 0.0)),
                new SlotIntention(SectorRef.of("sprawl_outer_defense"),
                        TAGS_DEFENSE,
                        4, 18,
                        new Anchor.RingValidated(ring2 + 6, 0.0)));

        Map<AnchorKind, BlockPos> namedAnchors = new LinkedHashMap<>();
        namedAnchors.put(AnchorKind.TOWN_SQUARE, centre);
        namedAnchors.put(AnchorKind.MAIN_GATE, mainEnd);

        return new LayoutBlueprint(
                ShapeType.SPRAWL,
                List.of(new RoadDeclaration(mainRef,
                        EdgeRole.SPINE, RoadShape.RoadTier.VILLAGE_ROAD,
                        mainPrimitive)),
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
