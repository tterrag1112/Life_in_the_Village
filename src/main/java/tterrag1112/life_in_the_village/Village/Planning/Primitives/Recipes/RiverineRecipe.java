package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaPurpose;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.Features.PolygonXZ;
import tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole;
import tterrag1112.life_in_the_village.Village.Planning.Graph.NodeKind;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.BaseRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.AddRing;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.ExtendAlongEdge;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.FixedGrowth;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.Sector;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.SectorRole;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * RIVERINE layout recipe. Phase 12.2: extends {@link BaseRecipe} and emits sectors.
 *
 * <p>A village strung along the inland side of a river or lake shore. The
 * spine runs parallel to the nearest shore edge, offset {@link #SHORE_OFFSET}
 * inland; the town square sits at the spine's midpoint; inland slots fill
 * along the inland side; shore slots (FISHERY/DOCK/MILL/WATERMILL) sit
 * shore-side; an inland agricultural ring sits well inland.
 *
 * <p>Falls back to {@link RadialRecipe} when no shore edge is available
 * — riverine geometry is meaningless without a shore to align to.
 *
 * <p>This is the first recipe where {@link #prepareFeatures} does
 * substantive work: it derives spine direction from
 * {@code pctx.features.nearestShoreEdge}.
 */
public final class RiverineRecipe extends BaseRecipe {

    /** Inland offset of the spine from the shore, in blocks. */
    private static final int SHORE_OFFSET = 8;

    /** Building types that prefer to sit on the shore side. (Reference only;
     *  the matcher resolves placement via tag preferences.) */
    @SuppressWarnings("unused")
    private static final List<String> WATER_TYPES =
            List.of("FISHERY", "DOCK", "MILL", "WATERMILL");

    // ── Sector identifiers ──────────────────────────────────────────────────
    private static final String SECTOR_CIVIC       = "riverine_civic";
    private static final String SECTOR_INLAND_RES  = "riverine_inland_residential";
    private static final String SECTOR_INLAND_PROD = "riverine_inland_production";
    private static final String SECTOR_SHORE       = "riverine_shore";
    private static final String SECTOR_INLAND_AGRI = "riverine_inland_agri";

    // ── Tag sets ────────────────────────────────────────────────────────────
    private static final Set<SlotTag> TAGS_INLAND_RESIDENTIAL = EnumSet.of(
            SlotTag.RESIDENTIAL_INFILL, SlotTag.ROAD_ADJACENT, SlotTag.CIVIC_ADJACENT);
    private static final Set<SlotTag> TAGS_INLAND_PRODUCTION = EnumSet.of(
            SlotTag.PRODUCTION_INFILL, SlotTag.ROAD_ADJACENT, SlotTag.PRODUCTION_CLUSTER);
    private static final Set<SlotTag> TAGS_SHORE = EnumSet.of(
            SlotTag.SHORE, SlotTag.RIVER_BANK, SlotTag.PIER_ADJACENT,
            SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_INLAND_AGRI = EnumSet.of(
            SlotTag.FIELD_EDGE, SlotTag.PASTURE, SlotTag.RESIDENTIAL_OUTER);

    /** Cached state populated by {@link #prepareFeatures} and consumed by
     *  {@link #composeSectors}. Each {@code RiverineRecipe} instance is
     *  constructed fresh per village (via {@code ShapeRecipe.forShape}),
     *  so instance state is safe within a single compose() call. */
    private BlockPos cachedShoreNearest;
    private double cachedInlandRad;
    private double cachedAlongRad;
    private boolean cachedUsable;

    @Override
    protected void prepareFeatures(PlanContext pctx) {
        cachedUsable = false;
        BlockPos origin = pctx.layout.getCenter();

        PolygonXZ.Edge shoreEdge = pctx.features.nearestShoreEdge(origin);
        if (shoreEdge == null) return;

        BlockPos a = shoreEdge.a();
        BlockPos b = shoreEdge.b();
        BlockPos shoreMid = new BlockPos(
                (a.getX() + b.getX()) / 2,
                origin.getY(),
                (a.getZ() + b.getZ()) / 2);

        // Inland direction: from shore midpoint toward origin.
        int dx = origin.getX() - shoreMid.getX();
        int dz = origin.getZ() - shoreMid.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0) return;
        double inlandRad = Math.atan2(dz, dx);

        // Along-shore direction: parallel to the shore edge.
        double edgeDx = b.getX() - a.getX();
        double edgeDz = b.getZ() - a.getZ();
        double alongRad = Math.atan2(edgeDz, edgeDx);

        cachedShoreNearest = pctx.solidSurface(shoreMid);
        cachedInlandRad = inlandRad;
        cachedAlongRad = alongRad;
        cachedUsable = true;
    }

    @Override
    protected void composeSectors(PlanContext pctx) {
        BlockPos origin = pctx.layout.getCenter();

        // ── Fallback: no usable shore → RADIAL ────────────────────────────
        if (!cachedUsable) {
            System.out.println("RiverineRecipe: no usable shore edge — "
                    + "falling back to RADIAL");
            PlacementFailureRecorder.record(
                    PlacementFailureRecorder.Reason.SHAPE_RULE_REJECTED,
                    "no usable shore edge",
                    origin, VillageTypeData.ShapeType.RIVERINE.name());
            new RadialRecipe().compose(pctx);
            return;
        }

        // Preserve legacy behavior: non-water-type buildings refuse water tiles.
        pctx.rejectWaterForAll = true;

        BlockPos shoreNearest = cachedShoreNearest;
        double inlandRad = cachedInlandRad;
        double alongRad = cachedAlongRad;

        int totalBuildings = pctx.remaining.size();

        // ── Spine geometry: parallel to shore, offset inland ──────────────
        BlockPos spineCentre = pctx.solidSurface(new BlockPos(
                shoreNearest.getX() + (int) Math.round(Math.cos(inlandRad) * SHORE_OFFSET),
                origin.getY(),
                shoreNearest.getZ() + (int) Math.round(Math.sin(inlandRad) * SHORE_OFFSET)));

        // Preserve legacy halfLength formula so spine length stays comparable.
        int halfLength = pctx.density.getRing2Radius() * 3
                + totalBuildings * 6
                + 48;

        BlockPos spineStart = pctx.solidSurface(new BlockPos(
                spineCentre.getX() + (int) Math.round(Math.cos(alongRad) * halfLength),
                origin.getY(),
                spineCentre.getZ() + (int) Math.round(Math.sin(alongRad) * halfLength)));
        BlockPos spineEnd = pctx.solidSurface(new BlockPos(
                spineCentre.getX() - (int) Math.round(Math.cos(alongRad) * halfLength),
                origin.getY(),
                spineCentre.getZ() - (int) Math.round(Math.sin(alongRad) * halfLength)));

        // Clamp endpoints inland if shoreline curvature put them on water.
        spineStart = clampInlandIfWater(pctx, spineStart, inlandRad);
        spineEnd   = clampInlandIfWater(pctx, spineEnd,   inlandRad);

        int startNodeId = pctx.layout.addNode(
                spineStart, NodeKind.GATE, RoadShape.RoadTier.VILLAGE_ROAD);
        int endNodeId = pctx.layout.addNode(
                spineEnd, NodeKind.GATE, RoadShape.RoadTier.VILLAGE_ROAD);

        RoadPrimitive.StraightRoad spine = new RoadPrimitive.StraightRoad(
                spineStart, spineEnd, 8.0, RoadShape.RoadTier.VILLAGE_ROAD);
        int spineEdgeId = pctx.layout.addEdge(
                startNodeId, endNodeId, spine,
                pctx.level, pctx.worldSeed, EdgeRole.SPINE);

        List<BlockPos> spineCenterline =
                pctx.layout.getRoadGraph().edge(spineEdgeId).centerline();

        // Gate endpoint: whichever spine end is farther from the shore (legacy logic).
        BlockPos gateEndpoint = spineStart.distSqr(shoreNearest)
                > spineEnd.distSqr(shoreNearest) ? spineStart : spineEnd;
        pctx.layout.setMainGateEndpoint(gateEndpoint);
        pctx.layout.addGatePosition(spineStart);
        pctx.layout.addGatePosition(spineEnd);

        // ── Civic ring at the spine midpoint ──────────────────────────────
        BlockPos midpoint = spineCenterline.isEmpty() ? spineCentre
                : spineCenterline.get(spineCenterline.size() / 2);

        // Reduced civic capacity — legacy convention: civic ring shouldn't
        // cross the river so we keep it small.
        int civicCap = Math.max(2, Math.min(3, totalBuildings / 8 + 2));

        int civicSnapshot = pctx.slotPoolSize();
        RecipeHelpers.installLinearPlaza(pctx, midpoint,
                PlazaPurpose.CIVIC, RecipeHelpers.cardinalFromRad(alongRad));
        List<PlacementSlot> civicSlots = pctx.drainSlotsSince(civicSnapshot);
        if (!civicSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    SECTOR_CIVIC, SectorRole.CIVIC_TIGHT, BuildingZone.CIVIC,
                    civicSlots, civicCap, false, FixedGrowth.INSTANCE,
                    spineEdgeId, null,
                    32));
        }

        // ── Inland-side sectors along the spine ───────────────────────────
        int inlandSign = computeInlandSign(alongRad, inlandRad);

        List<PlacementSlot> inlandResSlots =
                RecipeHelpers.generateOneSidedSlotsAlongCenterline(
                        spineCenterline, spineEdgeId, TAGS_INLAND_RESIDENTIAL,
                        7, 8, 50, inlandSign);
        if (!inlandResSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    SECTOR_INLAND_RES, SectorRole.RESIDENTIAL_INFILL,
                    BuildingZone.RESIDENTIAL, inlandResSlots,
                    12, true, new ExtendAlongEdge(32, 4), spineEdgeId, null,
                    16));
        }

        List<PlacementSlot> inlandProdSlots =
                RecipeHelpers.generateOneSidedSlotsAlongCenterline(
                        spineCenterline, spineEdgeId, TAGS_INLAND_PRODUCTION,
                        12, 8, 45, inlandSign);
        if (!inlandProdSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    SECTOR_INLAND_PROD, SectorRole.RESIDENTIAL_INFILL,
                    BuildingZone.PRODUCTION, inlandProdSlots,
                    6, true, new ExtendAlongEdge(32, 3), spineEdgeId, null,
                    16));
        }

        // ── Shore-side sector ─────────────────────────────────────────────
        int shoreSign = -inlandSign;
        List<PlacementSlot> shoreSlots =
                RecipeHelpers.generateOneSidedSlotsAlongCenterline(
                        spineCenterline, spineEdgeId, TAGS_SHORE,
                        10, 5, 60, shoreSign);
        shoreSlots = filterShoreSlots(pctx, shoreSlots);
        if (!shoreSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    SECTOR_SHORE, SectorRole.SHORE_STRIP, BuildingZone.PRODUCTION,
                    shoreSlots,
                    4, false, FixedGrowth.INSTANCE, spineEdgeId, null,
                    16));
        }

        // ── Inland agricultural fringe ────────────────────────────────────
        BlockPos agriCentre = pctx.solidSurface(new BlockPos(
                midpoint.getX() + (int) Math.round(Math.cos(inlandRad)
                        * pctx.density.getRing1Radius()),
                origin.getY(),
                midpoint.getZ() + (int) Math.round(Math.sin(inlandRad)
                        * pctx.density.getRing1Radius())));

        List<PlacementSlot> agriSlots = RecipeHelpers.generateRingSlots(
                agriCentre,
                pctx.density.getRing1Radius() / 2,
                pctx.density.getRing2Radius(),
                TAGS_INLAND_AGRI,
                6, 40, pctx);

        agriSlots = filterInlandSlots(pctx, agriSlots, midpoint, inlandRad);
        if (!agriSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    SECTOR_INLAND_AGRI, SectorRole.AGRICULTURAL_FRINGE,
                    BuildingZone.AGRICULTURAL, agriSlots,
                    6, true, new AddRing(16, 6), spineEdgeId, null,
                    18));
        }
    }

    @Override
    protected void registerAnchors(PlanContext pctx) {
        super.registerAnchors(pctx);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * If {@code pos} sits on water (e.g. the spine wandered onto a curving
     * shoreline), shift it inland by {@link #SHORE_OFFSET} repeatedly until
     * it's no longer on water. Caps at 4 attempts to avoid runaway shifts.
     */
    private static BlockPos clampInlandIfWater(PlanContext pctx, BlockPos pos, double inlandRad) {
        BlockPos current = pos;
        for (int attempt = 0; attempt < 4 && pctx.features.isOnWater(current); attempt++) {
            current = pctx.solidSurface(new BlockPos(
                    current.getX() + (int) Math.round(Math.cos(inlandRad) * SHORE_OFFSET),
                    pos.getY(),
                    current.getZ() + (int) Math.round(Math.sin(inlandRad) * SHORE_OFFSET)));
        }
        return current;
    }

    /**
     * +1 / -1 indicating which perpendicular side of the spine is "inland."
     * Cross product Z component of (along-shore, inland) gives the sign in
     * the same convention used by {@code generateOneSidedSlotsAlongCenterline}
     * (positive perp = +perpZ axis when heading East).
     */
    private static int computeInlandSign(double alongRad, double inlandRad) {
        double ax = Math.cos(alongRad), az = Math.sin(alongRad);
        double ix = Math.cos(inlandRad), iz = Math.sin(inlandRad);
        double cross = ax * iz - az * ix;
        return cross >= 0 ? +1 : -1;
    }

    /**
     * Drops shore slots that ended up on water or too far from any shore
     * edge (10-block tolerance). The centerline-walk slot generator stamps
     * positions blindly; this filter removes anything geometrically wrong.
     */
    private static List<PlacementSlot> filterShoreSlots(PlanContext pctx,
                                                         List<PlacementSlot> slots) {
        List<PlacementSlot> out = new ArrayList<>();
        for (PlacementSlot s : slots) {
            BlockPos pos = s.pos();
            if (pctx.features.isOnWater(pos)) continue;
            PolygonXZ.Edge nearest = pctx.features.nearestShoreEdge(pos);
            if (nearest == null) continue;
            if (distanceFromPosToEdge(pos, nearest) > 10) continue;
            out.add(s);
        }
        return out;
    }

    /**
     * Drops inland slots that landed on the wrong side of the spine (i.e.
     * water-side) or directly on water. Tolerance of 8 blocks across the
     * spine accounts for spine width and slot perpendicular spread.
     */
    private static List<PlacementSlot> filterInlandSlots(PlanContext pctx,
                                                          List<PlacementSlot> slots,
                                                          BlockPos referenceAtSpine,
                                                          double inlandRad) {
        List<PlacementSlot> out = new ArrayList<>();
        double ix = Math.cos(inlandRad), iz = Math.sin(inlandRad);
        for (PlacementSlot s : slots) {
            int dx = s.pos().getX() - referenceAtSpine.getX();
            int dz = s.pos().getZ() - referenceAtSpine.getZ();
            double dot = dx * ix + dz * iz;
            if (dot < -8) continue;
            if (pctx.features.isOnWater(s.pos())) continue;
            out.add(s);
        }
        return out;
    }

    /** Perpendicular XZ distance from {@code pos} to a shore edge segment. */
    private static double distanceFromPosToEdge(BlockPos pos, PolygonXZ.Edge edge) {
        long ax = edge.a().getX(), az = edge.a().getZ();
        long bx = edge.b().getX(), bz = edge.b().getZ();
        long dx = bx - ax, dz = bz - az;
        long lenSq = dx * dx + dz * dz;
        if (lenSq == 0) {
            long ddx = pos.getX() - ax, ddz = pos.getZ() - az;
            return Math.sqrt(ddx * ddx + ddz * ddz);
        }
        double t = ((pos.getX() - ax) * (double) dx + (pos.getZ() - az) * (double) dz) / lenSq;
        t = Math.max(0, Math.min(1, t));
        double cx = ax + t * dx, cz = az + t * dz;
        double ex = pos.getX() - cx, ez = pos.getZ() - cz;
        return Math.sqrt(ex * ex + ez * ez);
    }
}
