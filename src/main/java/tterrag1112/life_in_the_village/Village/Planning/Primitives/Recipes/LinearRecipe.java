package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaPurpose;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole;
import tterrag1112.life_in_the_village.Village.Planning.Graph.NodeKind;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.BaseRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.CenterlineResult;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PrimitiveContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.AddRing;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.ExtendAlongEdge;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.FixedGrowth;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.Sector;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.SectorRole;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Planning.GatewayDescriptor;
import tterrag1112.life_in_the_village.Village.Roads.Planning.VillageEdgeDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * LINEAR layout recipe. Phase 11.2: extends {@link BaseRecipe} and emits sectors.
 *
 * <p>A village strung along a single main street with the town square
 * at the midpoint. Buildings sit on both sides of the street (generated
 * as sectors along the centerline). Farm clusters cap each road end.
 *
 * <p>Road geometry unchanged from the pre-conversion version. What changed:
 * stub-spur + BuildingCircle.place() is replaced by two sectors on the main
 * centerline (residential and production), each growing via ExtendAlongEdge.
 */
public final class LinearRecipe extends BaseRecipe {

    // ── Sector identifiers ──────────────────────────────────────────────────
    private static final String SECTOR_CIVIC       = "linear_civic";
    private static final String SECTOR_RESIDENTIAL = "linear_main_residential";
    private static final String SECTOR_PRODUCTION  = "linear_main_production";
    private static final String SECTOR_FARM_START  = "linear_farm_start";
    private static final String SECTOR_FARM_END    = "linear_farm_end";

    // ── Tag sets ────────────────────────────────────────────────────────────
    private static final Set<SlotTag> TAGS_MAIN_RESIDENTIAL = EnumSet.of(
            SlotTag.RESIDENTIAL_INFILL, SlotTag.ROAD_ADJACENT, SlotTag.CIVIC_ADJACENT);
    private static final Set<SlotTag> TAGS_MAIN_PRODUCTION = EnumSet.of(
            SlotTag.PRODUCTION_INFILL, SlotTag.ROAD_ADJACENT, SlotTag.PRODUCTION_CLUSTER);
    private static final Set<SlotTag> TAGS_END_FARM = EnumSet.of(
            SlotTag.FIELD_EDGE, SlotTag.PASTURE, SlotTag.RESIDENTIAL_OUTER);

    private boolean terrainTooRough;

    @Override
    protected void prepareFeatures(PlanContext pctx) {
        int largest = RecipeHelpers.largestRotatedFootprint(pctx);
        int requiredFlatSide = largest + 4;
        int available = pctx.layout.getTerrain().largestFlatPatchAvailable(2);
        terrainTooRough = available < requiredFlatSide;
        if (terrainTooRough) {
            System.out.println("LinearRecipe: terrain pre-check failed — "
                    + "largest building " + largest + "x" + largest
                    + " needs ≥" + requiredFlatSide + " flat side, "
                    + "available=" + available);
        }
    }

    @Override
    protected void composeSectors(PlanContext pctx) {
        if (terrainTooRough) {
            tterrag1112.life_in_the_village.Kingdom.Placement
                    .PlacementFailureRecorder.record(
                    tterrag1112.life_in_the_village.Kingdom.Placement
                            .PlacementFailureRecorder.Reason.TERRAIN_UNSUITABLE,
                    "LINEAR: no flat patch large enough — falling back to RADIAL",
                    pctx.layout.getCenter(),
                    tterrag1112.life_in_the_village.Village.VillageTypeData
                            .ShapeType.LINEAR.name());
            new RadialRecipe().compose(pctx);
            return;
        }
        BlockPos origin = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();
        int totalBuildings = pctx.remaining.size();

        // ── Main road ─────────────────────────────────────────────────────
        double mainDirRad = RecipeHelpers.directionRadOf(terrain.bestFlatDir());
        double tanX = Math.cos(mainDirRad);
        double tanZ = Math.sin(mainDirRad);

        // Scale with building count: each side needs ~10 blocks per building.
        int halfLength = Math.max(60,
                (totalBuildings * 10) + pctx.density.getRing2Radius());

        BlockPos mainStart = pctx.solidSurface(new BlockPos(
                origin.getX() + (int) Math.round(tanX * halfLength),
                origin.getY(),
                origin.getZ() + (int) Math.round(tanZ * halfLength)));
        BlockPos mainEnd = pctx.solidSurface(new BlockPos(
                origin.getX() - (int) Math.round(tanX * halfLength),
                origin.getY(),
                origin.getZ() - (int) Math.round(tanZ * halfLength)));

        RoadPrimitive.StraightRoad mainRoad = new RoadPrimitive.StraightRoad(
                mainStart, mainEnd, 8.0, RoadShape.RoadTier.VILLAGE_ROAD);

        // Phase 17 Step 3: probe spine before adding to graph; try 90° rotation
        // if severely truncated. addNode calls are deferred to after the
        // rotation decision so we don't leave orphaned GATE nodes in the graph.
        CenterlineResult probeMain = mainRoad.computeCenterline(
                PrimitiveContext.basic(pctx.level, pctx.worldSeed));
        SpineViability mainViability = checkSpineViability(probeMain, 2 * halfLength);
        if (mainViability == SpineViability.ABORT) {
            pctx.recordSpineTruncation();
            tterrag1112.life_in_the_village.Kingdom.Placement
                    .PlacementFailureRecorder.record(
                    tterrag1112.life_in_the_village.Kingdom.Placement
                            .PlacementFailureRecorder.Reason.TERRAIN_UNSUITABLE,
                    "LINEAR: main road NO_SURFACE abort",
                    pctx.layout.getCenter(),
                    tterrag1112.life_in_the_village.Village.VillageTypeData
                            .ShapeType.LINEAR.name());
            return;
        }
        if (mainViability == SpineViability.ROTATE
                || mainViability == SpineViability.FALLBACK) {
            double rotDir = mainDirRad + Math.PI / 2;
            BlockPos rotStart = pctx.solidSurface(new BlockPos(
                    origin.getX() + (int) Math.round(Math.cos(rotDir) * halfLength),
                    origin.getY(),
                    origin.getZ() + (int) Math.round(Math.sin(rotDir) * halfLength)));
            BlockPos rotEnd = pctx.solidSurface(new BlockPos(
                    origin.getX() - (int) Math.round(Math.cos(rotDir) * halfLength),
                    origin.getY(),
                    origin.getZ() - (int) Math.round(Math.sin(rotDir) * halfLength)));
            RoadPrimitive.StraightRoad rotRoad = new RoadPrimitive.StraightRoad(
                    rotStart, rotEnd, 8.0, RoadShape.RoadTier.VILLAGE_ROAD);
            CenterlineResult probeRot = rotRoad.computeCenterline(
                    PrimitiveContext.basic(pctx.level, pctx.worldSeed));
            SpineViability rotViability = checkSpineViability(probeRot, 2 * halfLength);
            pctx.recordSpineTruncation();
            if (rotViability == SpineViability.OK) {
                mainDirRad = rotDir;
                mainStart  = rotStart;
                mainEnd    = rotEnd;
                mainRoad   = rotRoad;
                pctx.recordSpinePivot();
            } else {
                pctx.recordSpinePivot();
                System.out.println("LinearRecipe: spine truncated in both directions,"
                        + " continuing with best available");
            }
        }

        int startNodeId = pctx.layout.addNode(
                mainStart, NodeKind.GATE, RoadShape.RoadTier.VILLAGE_ROAD);
        int endNodeId = pctx.layout.addNode(
                mainEnd, NodeKind.GATE, RoadShape.RoadTier.VILLAGE_ROAD);
        int mainEdgeId = pctx.layout.addEdge(
                startNodeId, endNodeId, mainRoad,
                pctx.level, pctx.worldSeed, EdgeRole.SPINE);
        List<BlockPos> mainCenterline =
                pctx.layout.getRoadGraph().edge(mainEdgeId).centerline();

        pctx.layout.setMainGateEndpoint(mainEnd);
        pctx.layout.addGatePosition(mainStart);
        pctx.layout.addGatePosition(mainEnd);

        // ── Civic ring — linear plaza at midpoint ─────────────────────────
        BlockPos squareMid = mainCenterline.isEmpty() ? origin
                : mainCenterline.get(mainCenterline.size() / 2);
        int civicCap = Math.max(2, Math.min(4, totalBuildings / 6 + 1));

        int civicSnapshot = pctx.slotPoolSize();
        RecipeHelpers.installLinearPlaza(pctx, squareMid,
                PlazaPurpose.CIVIC, RecipeHelpers.cardinalFromRad(mainDirRad));
        List<PlacementSlot> civicSlots = pctx.drainSlotsSince(civicSnapshot);
        if (!civicSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    SECTOR_CIVIC, SectorRole.CIVIC_TIGHT, BuildingZone.CIVIC,
                    civicSlots, civicCap, false, FixedGrowth.INSTANCE,
                    mainEdgeId, null,
                    32));
        }

        // ── Both-side residential infill along the main road ─────────────
        List<PlacementSlot> residentialSlots =
                RecipeHelpers.generateSlotsAlongCenterline(
                        mainCenterline, mainEdgeId, TAGS_MAIN_RESIDENTIAL,
                        8, 7, 50);
        if (!residentialSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    SECTOR_RESIDENTIAL, SectorRole.RESIDENTIAL_INFILL,
                    BuildingZone.RESIDENTIAL, residentialSlots,
                    12, true, new ExtendAlongEdge(32, 4), mainEdgeId, null,
                    16));
        }

        // ── Both-side production infill along the main road ───────────────
        List<PlacementSlot> productionSlots =
                RecipeHelpers.generateSlotsAlongCenterline(
                        mainCenterline, mainEdgeId, TAGS_MAIN_PRODUCTION,
                        12, 7, 45);
        if (!productionSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    SECTOR_PRODUCTION, SectorRole.RESIDENTIAL_INFILL,
                    BuildingZone.PRODUCTION, productionSlots,
                    6, true, new ExtendAlongEdge(32, 3), mainEdgeId, null,
                    16));
        }

        // ── Farm clusters at each end ─────────────────────────────────────
        // Phase 17 Step 2: use actual road endpoints, not geometric targets,
        // so farms aren't placed at truncated-road positions that may be in
        // bad terrain (the road stopped short for exactly that reason).
        BlockPos actualStart = mainCenterline.isEmpty()
                ? mainStart : mainCenterline.get(0);
        BlockPos actualEnd = mainCenterline.isEmpty()
                ? mainEnd : mainCenterline.get(mainCenterline.size() - 1);

        List<PlacementSlot> farmStart = generateFarmCluster(
                pctx, actualStart, mainDirRad + Math.PI, mainEdgeId);
        if (!farmStart.isEmpty()) {
            pctx.offerSector(new Sector(
                    SECTOR_FARM_START, SectorRole.AGRICULTURAL_FRINGE,
                    BuildingZone.AGRICULTURAL, farmStart,
                    4, true, new AddRing(12, 4), mainEdgeId, null,
                    18));
        }

        List<PlacementSlot> farmEnd = generateFarmCluster(
                pctx, actualEnd, mainDirRad, mainEdgeId);
        if (!farmEnd.isEmpty()) {
            pctx.offerSector(new Sector(
                    SECTOR_FARM_END, SectorRole.AGRICULTURAL_FRINGE,
                    BuildingZone.AGRICULTURAL, farmEnd,
                    4, true, new AddRing(12, 4), mainEdgeId, null,
                    18));
        }
    }

    /** 5 farm slots in a cluster 16 blocks past the road's end. */
    private static List<PlacementSlot> generateFarmCluster(
            PlanContext pctx, BlockPos roadEnd, double outwardAngle, int parentEdgeId) {
        List<PlacementSlot> slots = new ArrayList<>();
        BlockPos focal = pctx.solidSurface(roadEnd.offset(
                (int) Math.round(Math.cos(outwardAngle) * 16), 0,
                (int) Math.round(Math.sin(outwardAngle) * 16)));

        for (int i = 0; i < 5; i++) {
            double angle = 2 * Math.PI * i / 5;
            BlockPos slotPos = pctx.solidSurface(focal.offset(
                    (int) Math.round(Math.cos(angle) * 8), 0,
                    (int) Math.round(Math.sin(angle) * 8)));
            if (pctx.features.isOnWater(slotPos)) continue;
            if (pctx.features.isOnCliff(slotPos)) continue;
            slots.add(new PlacementSlot(
                    slotPos, List.of(focal), parentEdgeId,
                    TAGS_END_FARM, 14, 14, null, 30, 0));
        }
        return slots;
    }

    @Override
    protected void registerAnchors(PlanContext pctx) {
        super.registerAnchors(pctx);
    }

    /** Two gateways: PRIMARY at mainEnd, SIDE at mainStart. */
    @Override
    public List<GatewayDescriptor> describeGateways(PlanContext pctx) {
        return GatewayDescriptor.deriveFromLayout(pctx);
    }

    /** Single THROUGH_VILLAGE edge along the main street. */
    @Override
    public List<VillageEdgeDescriptor> describeInternalRoads(PlanContext pctx) {
        return deriveThruRoad(pctx);
    }

    static List<VillageEdgeDescriptor> deriveThruRoad(PlanContext pctx) {
        List<BlockPos> gates = pctx.layout.getGatePositions();
        if (gates.size() < 2) return List.of();
        java.util.Collection<List<BlockPos>> cls = pctx.layout.getAllCenterlines();
        if (cls.isEmpty()) return List.of();

        BlockPos a = gates.get(0), b = gates.get(1);
        List<BlockPos> best = findBestCenterline(cls, a, b);
        if (best == null || best.isEmpty()) return List.of();

        boolean rev = best.get(0).distSqr(b) < best.get(0).distSqr(a);
        List<BlockPos> oriented;
        if (rev) { oriented = new ArrayList<>(best); Collections.reverse(oriented); }
        else oriented = best;

        return List.of(new VillageEdgeDescriptor(a, b, oriented,
                VillageRoadEdge.EdgeCharacter.THROUGH_VILLAGE));
    }

    static List<BlockPos> findBestCenterline(
            java.util.Collection<List<BlockPos>> cls, BlockPos a, BlockPos b) {
        List<BlockPos> best = null;
        double bestScore = Double.MAX_VALUE;
        int bestLen = 0;
        for (List<BlockPos> cl : cls) {
            if (cl.size() < 2) continue;
            BlockPos s = cl.get(0), e = cl.get(cl.size() - 1);
            double score = Math.min(s.distSqr(a) + e.distSqr(b),
                                    s.distSqr(b) + e.distSqr(a));
            if (score < bestScore || (score == bestScore && cl.size() > bestLen)) {
                bestScore = score; best = cl; bestLen = cl.size();
            }
        }
        return best;
    }
}
