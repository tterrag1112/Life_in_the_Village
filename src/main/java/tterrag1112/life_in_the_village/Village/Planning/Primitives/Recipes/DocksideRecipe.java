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
 * Phase D: declarative port of DOCKSIDE — pier village running into
 * the water. Main road runs PERPENDICULAR to the shore, terminating
 * at the shore as a "pier". Falls back to LINEAR if no water.
 *
 * <p>Pre-Phase-A used {@code terrain.waterBody().nearestShore()} for
 * water-edge query; preserved here. The no-water fallback delegates
 * to {@link LinearRecipe} — compose() returns the LinearRecipe's
 * blueprint directly. This is the only inline-recipe-delegation in
 * the Phase D ports; preserves pre-Phase-A behavior for sites
 * spawned without water.
 */
public final class DocksideRecipe extends BaseRecipe {

    private static final int UNLIMITED_CAPACITY = 1024;

    private static final Set<SlotTag> TAGS_PRIME_CIVIC =
            EnumSet.of(SlotTag.PRIME_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_SECONDARY_CIVIC =
            EnumSet.of(SlotTag.SECONDARY_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_MAIN_ROAD =
            EnumSet.of(SlotTag.ROAD_ADJACENT, SlotTag.RESIDENTIAL_INFILL,
                    SlotTag.PRODUCTION_INFILL, SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_AGRI =
            EnumSet.of(SlotTag.FIELD_EDGE, SlotTag.PASTURE,
                    SlotTag.RESIDENTIAL_OUTER);

    @Override
    public LayoutBlueprint compose(PlanContext pctx) {
        TerrainProfile terrain = pctx.layout.getTerrain();
        BlockPos origin = pctx.layout.getCenter();

        if (!terrain.hasWater()) {
            // Inline delegation — same pattern as pre-Phase-A but on
            // the new compose() return type. LinearRecipe handles its
            // own terrain pre-check and fallback chain.
            return new LinearRecipe().compose(pctx);
        }

        TerrainAnalyzer.WaterBodyInfo water = terrain.waterBody();
        BlockPos shore = water.nearestShore();
        int totalBuildings = pctx.remaining.size();

        double waterDx = shore.getX() - origin.getX();
        double waterDz = shore.getZ() - origin.getZ();
        double waterLen = Math.sqrt(waterDx * waterDx + waterDz * waterDz);
        if (waterLen < 1) {
            return new LinearRecipe().compose(pctx);
        }
        double towardWaterRad = Math.atan2(waterDz / waterLen, waterDx / waterLen);
        double inlandRad = towardWaterRad + Math.PI;

        int inlandLength = pctx.density.getRing2Radius() * 2 + totalBuildings * 5;
        BlockPos inlandEnd = pctx.solidSurface(new BlockPos(
                shore.getX() + (int) Math.round(Math.cos(inlandRad) * inlandLength),
                shore.getY(),
                shore.getZ() + (int) Math.round(Math.sin(inlandRad) * inlandLength)));
        // Shore terminus pulled back 4 blocks so it doesn't end IN
        // the water (matches pre-Phase-A).
        BlockPos shoreEnd = pctx.solidSurface(new BlockPos(
                shore.getX() + (int) Math.round(Math.cos(inlandRad) * 4),
                shore.getY(),
                shore.getZ() + (int) Math.round(Math.sin(inlandRad) * 4)));

        EdgeRef mainRef = EdgeRef.of("dockside_main");
        var mainPrimitive = new RoadPrimitive.StraightRoad(
                inlandEnd, shoreEnd, 6.0, RoadShape.RoadTier.VILLAGE_ROAD);

        // Plaza ~1/3 of the way from inland end toward shore. Use the
        // village centre as a proxy for the plaza centre (matches
        // pre-Phase-A's mainCenterline.get(size/3) approximately).
        BlockPos plazaPos = origin;

        SectorRef civicSec = SectorRef.of("dockside_civic");
        // Plaza shape CIRCLE — pre-Phase-A used LINEAR with axis
        // perpendicular to inland direction; PlazaDeclaration doesn't
        // carry majorAxis, so CIRCLE is the compromise.
        var plaza = new PlazaDeclaration(plazaPos,
                pctx.density.getRing1Radius(),
                PlazaShape.CIRCLE, PlazaPurpose.CIVIC, civicSec);

        int civicCap = Math.max(2, Math.min(4, totalBuildings / 6 + 1));

        List<SectorDeclaration> sectors = List.of(
                new SectorDeclaration("dockside_civic",
                        SectorRole.CIVIC_TIGHT, BuildingZone.CIVIC,
                        civicCap, 46, null),
                new SectorDeclaration("dockside_main_road",
                        SectorRole.RESIDENTIAL_INFILL, BuildingZone.RESIDENTIAL,
                        UNLIMITED_CAPACITY, 16, mainRef),
                // Agri inland only (away from water). pctx.rejectWaterForAll
                // pre-Phase-A flag is implicit now since SlotEmitter's
                // FeatureMap check rejects water positions anyway.
                new SectorDeclaration("dockside_inland_agri",
                        SectorRole.AGRICULTURAL_FRINGE, BuildingZone.AGRICULTURAL,
                        UNLIMITED_CAPACITY, 18, null));

        // Inland focal for agri RegionalGather — past inlandEnd along
        // inlandRad by ring2.
        BlockPos agriFocal = pctx.solidSurface(new BlockPos(
                inlandEnd.getX() + (int) Math.round(Math.cos(inlandRad)
                        * pctx.density.getRing2Radius()),
                inlandEnd.getY(),
                inlandEnd.getZ() + (int) Math.round(Math.sin(inlandRad)
                        * pctx.density.getRing2Radius())));

        List<SlotIntention> intentions = List.of(
                new SlotIntention(civicSec, TAGS_PRIME_CIVIC,
                        1, 46, new Anchor.PlazaPerimeter(civicSec, 6)),
                new SlotIntention(civicSec, TAGS_SECONDARY_CIVIC,
                        Math.max(0, civicCap - 1), 46,
                        new Anchor.PlazaPerimeter(civicSec, 6)),
                // Both sides of main road. SlotEmitter's FeatureMap
                // check rejects water-side candidates automatically.
                new SlotIntention(SectorRef.of("dockside_main_road"),
                        TAGS_MAIN_ROAD, 12, 35,
                        new Anchor.RoadAlong(mainRef, 7, true)),
                // Agri RegionalGather inland. Approximation of
                // pre-Phase-A scatterBucketAt around agriCentre.
                new SlotIntention(SectorRef.of("dockside_inland_agri"),
                        TAGS_AGRI, 6, 18,
                        new Anchor.RegionalGather(agriFocal, 18, 6)));

        Map<AnchorKind, BlockPos> namedAnchors = new LinkedHashMap<>();
        namedAnchors.put(AnchorKind.TOWN_SQUARE, plazaPos);
        // Gate endpoint = shore (boats arrive there) — pre-Phase-A.
        namedAnchors.put(AnchorKind.MAIN_GATE, shoreEnd);
        namedAnchors.put(AnchorKind.HARBOR, shore);

        return new LayoutBlueprint(
                ShapeType.DOCKSIDE,
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
            // No 90deg rotation — DOCKSIDE direction is water-determined,
            // not terrain-flat-direction.
            case ReEmitReason.SevereTruncation t -> null;
            case ReEmitReason.SlotsDropped s -> null;
            case ReEmitReason.SectorStarved s -> null;
            case ReEmitReason.ValidationFailed v -> null;
        };
    }
}
