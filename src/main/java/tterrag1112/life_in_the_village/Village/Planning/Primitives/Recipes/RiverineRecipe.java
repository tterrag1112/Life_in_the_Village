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
import tterrag1112.life_in_the_village.Village.Planning.Features.PolygonXZ;
import tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.BaseRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.SectorRole;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;
import tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase D: declarative port of RIVERINE — village along the inland
 * side of a river/lake shore.
 *
 * <p>Pre-Phase-A pattern preserved:
 * <ul>
 *   <li>Shore detection via {@code FeatureMap.nearestShoreEdge}
 *       (recipe-internal feature query). Inline RADIAL fallback when
 *       no usable shore.</li>
 *   <li>Spine direction = parallel to shore edge.</li>
 *   <li>Inland direction = from shore midpoint toward village
 *       centre.</li>
 *   <li>Spine is a StraightRoad VILLAGE_ROAD offset
 *       {@value #SHORE_OFFSET} blocks inland from shore midpoint.</li>
 *   <li>Spine length = {@code ring2*3 + totalBuildings*6 + 48}.</li>
 *   <li>Plaza at spine midpoint.</li>
 *   <li>Inland-side residential + production sectors along the
 *       spine.</li>
 *   <li>Shore-side sector for FISHERY/DOCK-style buildings.</li>
 *   <li>Inland agri ring at midpoint + ring1 inland.</li>
 * </ul>
 *
 * <p>Approximation: pre-Phase-A used one-sided slot generation
 * (inland-only or shore-only). New flow uses {@code bothSides=true}
 * on RoadAlong with FeatureMap rejecting candidates on water.
 * SHORE-side slots get a separate sector with shore-near focal via
 * RegionalGather since they're explicitly supposed to be near water
 * (the FeatureMap check would actually reject them otherwise — flag
 * for follow-up if shore slots are missing in measurement).
 *
 * <p>Quirks resolved inline:
 * <ul>
 *   <li>No-shore fallback: {@code new RadialRecipe().compose(pctx)}
 *       inline (matches DOCKSIDE/TERRACED).</li>
 *   <li>{@code rejectWaterForAll = true} preserved.</li>
 *   <li>Shore slot intent: RegionalGather around the shore midpoint
 *       (best-effort approximation).</li>
 * </ul>
 */
public final class RiverineRecipe extends BaseRecipe {

    private static final int SHORE_OFFSET = 8;
    private static final int UNLIMITED_CAPACITY = 1024;

    private static final Set<SlotTag> TAGS_PRIME_CIVIC =
            EnumSet.of(SlotTag.PRIME_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_INLAND_RES = EnumSet.of(
            SlotTag.RESIDENTIAL_INFILL, SlotTag.ROAD_ADJACENT,
            SlotTag.CIVIC_ADJACENT);
    private static final Set<SlotTag> TAGS_INLAND_PROD = EnumSet.of(
            SlotTag.PRODUCTION_INFILL, SlotTag.ROAD_ADJACENT,
            SlotTag.PRODUCTION_CLUSTER);
    private static final Set<SlotTag> TAGS_SHORE = EnumSet.of(
            SlotTag.SHORE, SlotTag.RIVER_BANK, SlotTag.PIER_ADJACENT,
            SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_INLAND_AGRI = EnumSet.of(
            SlotTag.FIELD_EDGE, SlotTag.PASTURE,
            SlotTag.RESIDENTIAL_OUTER);

    @Override
    public LayoutBlueprint compose(PlanContext pctx) {
        BlockPos origin = pctx.layout.getCenter();
        int totalBuildings = pctx.remaining.size();
        int ring1 = pctx.density.getRing1Radius();
        int ring2 = pctx.density.getRing2Radius();

        PolygonXZ.Edge shoreEdge = pctx.features.nearestShoreEdge(origin);
        if (shoreEdge == null) {
            return new RadialRecipe().compose(pctx);
        }

        BlockPos a = shoreEdge.a();
        BlockPos b = shoreEdge.b();
        BlockPos shoreMid = new BlockPos(
                (a.getX() + b.getX()) / 2,
                origin.getY(),
                (a.getZ() + b.getZ()) / 2);

        // Inland direction = shore midpoint toward origin.
        int dx = origin.getX() - shoreMid.getX();
        int dz = origin.getZ() - shoreMid.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0) {
            return new RadialRecipe().compose(pctx);
        }
        double inlandRad = Math.atan2(dz, dx);

        // Along-shore direction = parallel to shore edge.
        double edgeDx = b.getX() - a.getX();
        double edgeDz = b.getZ() - a.getZ();
        double alongRad = Math.atan2(edgeDz, edgeDx);

        BlockPos shoreNearest = pctx.solidSurface(shoreMid);
        // Recipe-internal flag: non-water-type buildings refuse water tiles.
        // SlotEmitter's FeatureMap check enforces this implicitly per
        // candidate; flag preserved for legacy consumers.
        pctx.rejectWaterForAll = true;

        // Spine offset inland from shore.
        BlockPos spineCentre = pctx.solidSurface(new BlockPos(
                shoreNearest.getX() + (int) Math.round(Math.cos(inlandRad) * SHORE_OFFSET),
                origin.getY(),
                shoreNearest.getZ() + (int) Math.round(Math.sin(inlandRad) * SHORE_OFFSET)));

        int halfLength = ring2 * 3 + totalBuildings * 6 + 48;

        BlockPos spineStart = pctx.solidSurface(new BlockPos(
                spineCentre.getX() + (int) Math.round(Math.cos(alongRad) * halfLength),
                origin.getY(),
                spineCentre.getZ() + (int) Math.round(Math.sin(alongRad) * halfLength)));
        BlockPos spineEnd = pctx.solidSurface(new BlockPos(
                spineCentre.getX() - (int) Math.round(Math.cos(alongRad) * halfLength),
                origin.getY(),
                spineCentre.getZ() - (int) Math.round(Math.sin(alongRad) * halfLength)));

        // Inland-clamp endpoints if shore curvature put them on water.
        spineStart = clampInlandIfWater(pctx, spineStart, inlandRad);
        spineEnd = clampInlandIfWater(pctx, spineEnd, inlandRad);

        EdgeRef spineRef = EdgeRef.of("riverine_spine");
        var spinePrimitive = new RoadPrimitive.StraightRoad(
                spineStart, spineEnd, 8.0, RoadShape.RoadTier.VILLAGE_ROAD);

        // Gate endpoint = whichever spine end is farther from shore.
        BlockPos gateEndpoint = spineStart.distSqr(shoreNearest)
                > spineEnd.distSqr(shoreNearest) ? spineStart : spineEnd;

        // Plaza at spine midpoint (use spineCentre as approximation —
        // post-realisation the actual midpoint may differ by a block
        // or two from this estimate).
        BlockPos plazaPos = spineCentre;

        SectorRef civicSec = SectorRef.of("riverine_civic");
        var plaza = new PlazaDeclaration(plazaPos, ring1,
                PlazaShape.CIRCLE, PlazaPurpose.CIVIC, civicSec);

        int civicCap = Math.max(2, Math.min(3, totalBuildings / 8 + 2));

        List<SectorDeclaration> sectors = List.of(
                new SectorDeclaration("riverine_civic",
                        SectorRole.CIVIC_TIGHT, BuildingZone.CIVIC,
                        civicCap, 32, null),
                new SectorDeclaration("riverine_inland_residential",
                        SectorRole.RESIDENTIAL_INFILL,
                        BuildingZone.RESIDENTIAL,
                        12, 16, spineRef),
                new SectorDeclaration("riverine_inland_production",
                        SectorRole.RESIDENTIAL_INFILL,
                        BuildingZone.PRODUCTION,
                        6, 16, spineRef),
                new SectorDeclaration("riverine_shore",
                        SectorRole.SHORE_STRIP, BuildingZone.PRODUCTION,
                        4, 16, spineRef),
                new SectorDeclaration("riverine_inland_agri",
                        SectorRole.AGRICULTURAL_FRINGE,
                        BuildingZone.AGRICULTURAL,
                        UNLIMITED_CAPACITY, 18, spineRef));

        // Inland agri focal: midpoint + ring1 inland.
        BlockPos agriFocal = pctx.solidSurface(new BlockPos(
                plazaPos.getX() + (int) Math.round(Math.cos(inlandRad) * ring1),
                origin.getY(),
                plazaPos.getZ() + (int) Math.round(Math.sin(inlandRad) * ring1)));

        List<SlotIntention> intentions = List.of(
                new SlotIntention(civicSec, TAGS_PRIME_CIVIC,
                        1, 32, new Anchor.PlazaPerimeter(civicSec, 4)),
                // Inland residential — RoadAlong both sides; FeatureMap
                // rejects water-side candidates automatically.
                new SlotIntention(SectorRef.of("riverine_inland_residential"),
                        TAGS_INLAND_RES, 12, 35,
                        new Anchor.RoadAlong(spineRef, 7, true)),
                new SlotIntention(SectorRef.of("riverine_inland_production"),
                        TAGS_INLAND_PROD, 6, 40,
                        new Anchor.RoadAlong(spineRef, 12, true)),
                // Shore-side slots: RegionalGather around shoreNearest
                // — FISHERY/DOCK-tagged buildings prefer this region.
                // Best-effort approximation; if FeatureMap rejects all
                // shore-near candidates, sector starves and the
                // matcher commits these buildings to other sectors.
                new SlotIntention(SectorRef.of("riverine_shore"),
                        TAGS_SHORE, 4, 16,
                        new Anchor.RegionalGather(shoreNearest, 12, 5)),
                new SlotIntention(SectorRef.of("riverine_inland_agri"),
                        TAGS_INLAND_AGRI, 6, 18,
                        new Anchor.RegionalGather(agriFocal, ring1, 6)));

        Map<AnchorKind, BlockPos> namedAnchors = new LinkedHashMap<>();
        namedAnchors.put(AnchorKind.TOWN_SQUARE, plazaPos);
        namedAnchors.put(AnchorKind.MAIN_GATE, gateEndpoint);
        namedAnchors.put(AnchorKind.RIVER_LANDING, shoreNearest);

        return new LayoutBlueprint(
                ShapeType.RIVERINE,
                List.of(new RoadDeclaration(spineRef,
                        EdgeRole.SPINE, RoadShape.RoadTier.VILLAGE_ROAD,
                        spinePrimitive)),
                List.of(plaza),
                sectors,
                intentions,
                namedAnchors);
    }

    @Override
    public LayoutBlueprint reEmit(ReEmitReason reason, PlanContext pctx) {
        return switch (reason) {
            // No axis rotation — direction is shore-determined.
            case ReEmitReason.SevereTruncation t -> null;
            case ReEmitReason.SlotsDropped s -> null;
            case ReEmitReason.SectorStarved s -> null;
            case ReEmitReason.ValidationFailed v -> null;
        };
    }

    /** If {@code pos} sits on water (e.g. spine wandered onto a curving
     *  shoreline), shift inland by SHORE_OFFSET repeatedly until off
     *  water. Caps at 4 attempts (matches pre-Phase-A). */
    private static BlockPos clampInlandIfWater(PlanContext pctx, BlockPos pos,
                                               double inlandRad) {
        BlockPos current = pos;
        for (int attempt = 0; attempt < 4
                && pctx.features.isOnWater(current); attempt++) {
            current = pctx.solidSurface(new BlockPos(
                    current.getX() + (int) Math.round(Math.cos(inlandRad) * SHORE_OFFSET),
                    pos.getY(),
                    current.getZ() + (int) Math.round(Math.sin(inlandRad) * SHORE_OFFSET)));
        }
        return current;
    }
}
