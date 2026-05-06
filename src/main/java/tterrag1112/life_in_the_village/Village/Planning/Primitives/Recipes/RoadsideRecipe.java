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
 * Phase D: declarative port of ROADSIDE.
 *
 * <p>LINEAR with one-side bias. Pre-Phase-A used a feature-aware
 * sideSign to place all buildings on the side of the main road
 * facing AWAY from a feature (water, cliff). The new flow uses
 * {@code bothSides=true} on {@link Anchor.RoadAlong} and lets the
 * SlotEmitter's FeatureMap check reject candidates on the
 * feature-facing side. Aesthetic difference: buildings may end up
 * split across both sides where the old recipe biased to one — but
 * no slot fails validation due to feature overlap.
 *
 * <p>Plaza: the pre-Phase-A used LINEAR-shape plaza with the road's
 * tangent as major axis. {@link PlazaDeclaration} doesn't carry a
 * majorAxis field today; using CIRCLE as a compromise. Surfaced as
 * part of the Phase D quirk bundle for follow-up if the visual
 * difference matters.
 *
 * <p>Terrain-too-rough fallback: pre-Phase-A delegated to
 * RadialRecipe inline. The new model would need either a
 * blueprint-level "advance to next chain entry" signal (out of
 * scope for Phase D) or in-recipe duplication of RADIAL's blueprint.
 * For now: when the terrain pre-check fails, mark the layout
 * unplannable and return an empty blueprint. Planner aborts cleanly.
 * Phase E measurement may surface the loss; targeted fix follows.
 */
public final class RoadsideRecipe extends BaseRecipe {

    private static final int UNLIMITED_CAPACITY = 1024;

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

    @Override
    public LayoutBlueprint compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();
        int totalBuildings = pctx.remaining.size();

        // Terrain pre-check (pre-Phase-A behavior). On failure: mark
        // unplannable + empty blueprint. Planner aborts cleanly via
        // "recipe produced no buildings" branch.
        int largest = RecipeHelpers.largestRotatedFootprint(pctx);
        int requiredFlatSide = largest + 4;
        int available = terrain.largestFlatPatchAvailable(2);
        if (available < requiredFlatSide) {
            pctx.layout.markUnplannable(
                    "ROADSIDE terrain too rough — needs >=" + requiredFlatSide
                    + " flat side, available=" + available);
            return LayoutBlueprint.empty(ShapeType.ROADSIDE);
        }

        double mainDirRad = directionRadOf(terrain.bestFlatDir())
                + pctx.cascadeAxisRotation();
        double tanX = Math.cos(mainDirRad);
        double tanZ = Math.sin(mainDirRad);

        int halfLength = Math.max(60,
                (totalBuildings * 10) + pctx.density.getRing2Radius());

        BlockPos mainStart = pctx.solidSurface(new BlockPos(
                centre.getX() + (int) Math.round(tanX * halfLength),
                centre.getY(),
                centre.getZ() + (int) Math.round(tanZ * halfLength)));
        BlockPos mainEnd = pctx.solidSurface(new BlockPos(
                centre.getX() - (int) Math.round(tanX * halfLength),
                centre.getY(),
                centre.getZ() - (int) Math.round(tanZ * halfLength)));

        EdgeRef mainRef = EdgeRef.of("roadside_main");
        var mainPrimitive = new RoadPrimitive.StraightRoad(
                mainStart, mainEnd, 8.0, RoadShape.RoadTier.VILLAGE_ROAD);

        SectorRef civicSec = SectorRef.of("plaza_civic");

        // Plaza at midpoint. CIRCLE shape used as a compromise; the
        // old LINEAR-with-axis aesthetic isn't expressible without a
        // PlazaDeclaration.majorAxis field.
        var plaza = new PlazaDeclaration(
                centre, pctx.density.getRing1Radius(),
                PlazaShape.CIRCLE, PlazaPurpose.CIVIC, civicSec);

        int civicCap = Math.max(2, Math.min(4, totalBuildings / 6 + 1));

        List<SectorDeclaration> sectors = List.of(
                new SectorDeclaration("plaza_civic",
                        SectorRole.CIVIC_TIGHT, BuildingZone.CIVIC,
                        civicCap, 46, null),
                new SectorDeclaration("roadside_residential",
                        SectorRole.RESIDENTIAL_INFILL, BuildingZone.RESIDENTIAL,
                        12, 16, mainRef),
                new SectorDeclaration("roadside_production",
                        SectorRole.RESIDENTIAL_INFILL, BuildingZone.PRODUCTION,
                        6, 16, mainRef));

        List<SlotIntention> intentions = List.of(
                new SlotIntention(civicSec, TAGS_PRIME_CIVIC,
                        1, 46,
                        new Anchor.PlazaPerimeter(civicSec, 6)),
                new SlotIntention(civicSec, TAGS_SECONDARY_CIVIC,
                        Math.max(0, civicCap - 1), 46,
                        new Anchor.PlazaPerimeter(civicSec, 6)),
                // Residential along main: spacing 7 matches pre-Phase-A
                // generateOneSidedSlotsAlongCenterline params.
                new SlotIntention(SectorRef.of("roadside_residential"),
                        TAGS_RESIDENTIAL,
                        12, 35,
                        new Anchor.RoadAlong(mainRef, 7, true)),
                // Production: spacing 12, larger fp budget to admit
                // production buildings.
                new SlotIntention(SectorRef.of("roadside_production"),
                        TAGS_PRODUCTION,
                        6, 40,
                        new Anchor.RoadAlong(mainRef, 12, true)));

        Map<AnchorKind, BlockPos> namedAnchors = new LinkedHashMap<>();
        namedAnchors.put(AnchorKind.TOWN_SQUARE, centre);
        namedAnchors.put(AnchorKind.MAIN_GATE, mainEnd);

        return new LayoutBlueprint(
                ShapeType.ROADSIDE,
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
