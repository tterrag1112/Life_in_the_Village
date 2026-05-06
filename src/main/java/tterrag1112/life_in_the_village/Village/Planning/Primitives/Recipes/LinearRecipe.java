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

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase D: declarative port of LINEAR — single spine village.
 *
 * <p>Pre-Phase-A pattern preserved:
 * <ul>
 *   <li>One main road (StraightRoad SPINE, terrain-biased + cascade
 *       rotation, length {@code max(60, totalBuildings*10 + ring2)}).</li>
 *   <li>LINEAR plaza at midpoint with axis perpendicular to road.</li>
 *   <li>Both-side residential infill (RoadAlong spacing 7, fp=35).</li>
 *   <li>Both-side production infill (RoadAlong spacing 12, fp=40).</li>
 *   <li>Farm clusters at each road end (5 slots arranged radially
 *       around a focal point 16 blocks past the road end).</li>
 * </ul>
 *
 * <p>Quirk-bundle: LINEAR plaza axis approximated as CIRCLE (no
 * majorAxis on PlazaDeclaration). Farm-cluster radial pattern at
 * road ends approximated as RegionalGather (poisson-disc within
 * a small radius around the farm focal point).
 *
 * <p>{@link #fallbackShape} was null in pre-Phase-A — LINEAR is the
 * tail of the cascade chain; new model uses {@code reEmit -> null}
 * to abort cleanly without further chain advance.
 */
public final class LinearRecipe extends BaseRecipe {

    private static final int FARM_CLUSTER_RADIUS = 12;
    private static final int FARM_CLUSTER_MIN_SPACING = 5;

    private static final Set<SlotTag> TAGS_PRIME_CIVIC =
            EnumSet.of(SlotTag.PRIME_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_SECONDARY_CIVIC =
            EnumSet.of(SlotTag.SECONDARY_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_RESIDENTIAL = EnumSet.of(
            SlotTag.RESIDENTIAL_INFILL, SlotTag.ROAD_ADJACENT,
            SlotTag.CIVIC_ADJACENT);
    private static final Set<SlotTag> TAGS_PRODUCTION = EnumSet.of(
            SlotTag.PRODUCTION_INFILL, SlotTag.ROAD_ADJACENT,
            SlotTag.PRODUCTION_CLUSTER);
    private static final Set<SlotTag> TAGS_FARM = EnumSet.of(
            SlotTag.FIELD_EDGE, SlotTag.PASTURE,
            SlotTag.RESIDENTIAL_OUTER);

    @Override
    public LayoutBlueprint compose(PlanContext pctx) {
        BlockPos origin = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();
        int totalBuildings = pctx.remaining.size();
        int ring2 = pctx.density.getRing2Radius();

        // Terrain pre-check.
        int largest = RecipeHelpers.largestRotatedFootprint(pctx);
        int requiredFlatSide = largest + 4;
        int available = terrain.largestFlatPatchAvailable(2);
        if (available < requiredFlatSide) {
            pctx.layout.markUnplannable(
                    "LINEAR terrain too rough — needs >=" + requiredFlatSide
                    + " flat side, available=" + available);
            return LayoutBlueprint.empty(ShapeType.LINEAR);
        }

        double mainDirRad = directionRadOf(terrain.bestFlatDir())
                + pctx.cascadeAxisRotation();
        double tanX = Math.cos(mainDirRad);
        double tanZ = Math.sin(mainDirRad);

        int halfLength = Math.max(60,
                (totalBuildings * 10) + ring2);

        BlockPos mainStart = pctx.solidSurface(new BlockPos(
                origin.getX() + (int) Math.round(tanX * halfLength),
                origin.getY(),
                origin.getZ() + (int) Math.round(tanZ * halfLength)));
        BlockPos mainEnd = pctx.solidSurface(new BlockPos(
                origin.getX() - (int) Math.round(tanX * halfLength),
                origin.getY(),
                origin.getZ() - (int) Math.round(tanZ * halfLength)));

        EdgeRef mainRef = EdgeRef.of("linear_main");
        var mainPrimitive = new RoadPrimitive.StraightRoad(
                mainStart, mainEnd, 8.0, RoadShape.RoadTier.VILLAGE_ROAD);

        SectorRef civicSec = SectorRef.of("linear_civic");

        // Plaza at midpoint = origin (StraightRoad's centerline mid is
        // approximately origin since we go ±halfLength from it).
        // CIRCLE shape compromise — see Javadoc.
        var plaza = new PlazaDeclaration(origin,
                pctx.density.getRing1Radius(),
                PlazaShape.CIRCLE, PlazaPurpose.CIVIC, civicSec);

        int civicCap = Math.max(2, Math.min(4, totalBuildings / 6 + 1));

        List<SectorDeclaration> sectors = List.of(
                new SectorDeclaration("linear_civic",
                        SectorRole.CIVIC_TIGHT, BuildingZone.CIVIC,
                        civicCap, 46, null),
                new SectorDeclaration("linear_main_residential",
                        SectorRole.RESIDENTIAL_INFILL, BuildingZone.RESIDENTIAL,
                        12, 16, mainRef),
                new SectorDeclaration("linear_main_production",
                        SectorRole.RESIDENTIAL_INFILL, BuildingZone.PRODUCTION,
                        6, 16, mainRef),
                new SectorDeclaration("linear_farm_start",
                        SectorRole.AGRICULTURAL_FRINGE,
                        BuildingZone.AGRICULTURAL,
                        4, 18, mainRef),
                new SectorDeclaration("linear_farm_end",
                        SectorRole.AGRICULTURAL_FRINGE,
                        BuildingZone.AGRICULTURAL,
                        4, 18, mainRef));

        // Farm cluster focal points: 16 blocks past each road end,
        // outward.
        BlockPos farmStartFocal = pctx.solidSurface(new BlockPos(
                mainStart.getX() + (int) Math.round(tanX * 16),
                mainStart.getY(),
                mainStart.getZ() + (int) Math.round(tanZ * 16)));
        BlockPos farmEndFocal = pctx.solidSurface(new BlockPos(
                mainEnd.getX() - (int) Math.round(tanX * 16),
                mainEnd.getY(),
                mainEnd.getZ() - (int) Math.round(tanZ * 16)));

        List<SlotIntention> intentions = List.of(
                new SlotIntention(civicSec, TAGS_PRIME_CIVIC,
                        1, 46, new Anchor.PlazaPerimeter(civicSec, 6)),
                new SlotIntention(civicSec, TAGS_SECONDARY_CIVIC,
                        Math.max(0, civicCap - 1), 46,
                        new Anchor.PlazaPerimeter(civicSec, 6)),
                new SlotIntention(SectorRef.of("linear_main_residential"),
                        TAGS_RESIDENTIAL, 12, 35,
                        new Anchor.RoadAlong(mainRef, 7, true)),
                new SlotIntention(SectorRef.of("linear_main_production"),
                        TAGS_PRODUCTION, 6, 40,
                        new Anchor.RoadAlong(mainRef, 12, true)),
                // Farm clusters: RegionalGather around each focal,
                // ~5 slots within 12 blocks. Approximation of the
                // pre-Phase-A "5 slots arranged radially around focal"
                // pattern; the matcher's commit-time perpendicular
                // recalibration handles individual slot footprints.
                new SlotIntention(SectorRef.of("linear_farm_start"),
                        TAGS_FARM, 5, 14,
                        new Anchor.RegionalGather(
                                farmStartFocal,
                                FARM_CLUSTER_RADIUS,
                                FARM_CLUSTER_MIN_SPACING)),
                new SlotIntention(SectorRef.of("linear_farm_end"),
                        TAGS_FARM, 5, 14,
                        new Anchor.RegionalGather(
                                farmEndFocal,
                                FARM_CLUSTER_RADIUS,
                                FARM_CLUSTER_MIN_SPACING)));

        Map<AnchorKind, BlockPos> namedAnchors = new LinkedHashMap<>();
        namedAnchors.put(AnchorKind.TOWN_SQUARE, origin);
        namedAnchors.put(AnchorKind.MAIN_GATE, mainEnd);
        namedAnchors.put(AnchorKind.SECONDARY_GATE, mainStart);

        return new LayoutBlueprint(
                ShapeType.LINEAR,
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
                // LINEAR is tail of cascade chain — null aborts cleanly.
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
