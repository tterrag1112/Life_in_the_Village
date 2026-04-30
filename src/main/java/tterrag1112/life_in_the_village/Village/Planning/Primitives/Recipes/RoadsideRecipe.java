package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaPurpose;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole;
import tterrag1112.life_in_the_village.Village.Planning.Graph.NodeKind;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.BaseRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.ExtendAlongEdge;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.FixedGrowth;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.Sector;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.SectorRole;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;
import tterrag1112.life_in_the_village.Village.Roads.Planning.GatewayDescriptor;
import tterrag1112.life_in_the_village.Village.Roads.Planning.VillageEdgeDescriptor;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * ROADSIDE layout recipe. Phase 11.2: extends {@link BaseRecipe} and emits sectors.
 *
 * <p>LINEAR with one-side bias. All buildings sit on the side of the main road
 * that faces away from an edge feature (water, cliff). The build side is
 * terrain-driven: if water or a ridge occupies the left-perpendicular sample
 * point, buildings flip to the right side.
 *
 * <p>Emits 3 sectors: civic (CIVIC_TIGHT), residential (RESIDENTIAL_INFILL),
 * production (RESIDENTIAL_INFILL). Both non-civic sectors grow via
 * ExtendAlongEdge so overflow adds more one-sided slots without crossing
 * to the feature-facing side.
 */
public final class RoadsideRecipe extends BaseRecipe {

    // ── Sector identifiers ──────────────────────────────────────────────────
    private static final String SECTOR_CIVIC       = "roadside_civic";
    private static final String SECTOR_RESIDENTIAL = "roadside_residential";
    private static final String SECTOR_PRODUCTION  = "roadside_production";

    // ── Tag sets ────────────────────────────────────────────────────────────
    private static final Set<SlotTag> TAGS_RESIDENTIAL = EnumSet.of(
            SlotTag.RESIDENTIAL_INFILL, SlotTag.ROAD_ADJACENT, SlotTag.CIVIC_ADJACENT);
    private static final Set<SlotTag> TAGS_PRODUCTION = EnumSet.of(
            SlotTag.PRODUCTION_INFILL, SlotTag.ROAD_ADJACENT, SlotTag.PRODUCTION_CLUSTER);

    @Override
    protected void composeSectors(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();
        int totalBuildings = pctx.remaining.size();

        // ── Main road heading aligned with bestFlatDir ────────────────────
        double mainDirRad = RecipeHelpers.directionRadOf(terrain.bestFlatDir());
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

        int startNodeId = pctx.layout.addNode(
                mainStart, NodeKind.GATE, RoadShape.RoadTier.VILLAGE_ROAD);
        int endNodeId = pctx.layout.addNode(
                mainEnd, NodeKind.GATE, RoadShape.RoadTier.VILLAGE_ROAD);

        RoadPrimitive.StraightRoad mainRoad = new RoadPrimitive.StraightRoad(
                mainStart, mainEnd, 8.0, RoadShape.RoadTier.VILLAGE_ROAD);
        int mainEdgeId = pctx.layout.addEdge(
                startNodeId, endNodeId, mainRoad,
                pctx.level, pctx.worldSeed, EdgeRole.SPINE);
        List<BlockPos> mainCenterline =
                pctx.layout.getRoadGraph().edge(mainEdgeId).centerline();

        pctx.layout.setMainGateEndpoint(mainEnd);
        pctx.layout.addGatePosition(mainStart);
        pctx.layout.addGatePosition(mainEnd);

        // ── Determine which side is "away from feature" ───────────────────
        // sideSign: +1 = positive-perp side (right of heading in slot walk),
        //           -1 = negative-perp side (left of heading in slot walk).
        // Mirrors the legacy stubSpursAlongRoad ALWAYS_LEFT / ALWAYS_RIGHT logic.
        int sideSign = determineBuildSide(pctx, centre, mainDirRad);

        // ── Civic ring — linear plaza at midpoint ─────────────────────────
        BlockPos squareMid = mainCenterline.isEmpty() ? centre
                : mainCenterline.get(mainCenterline.size() / 2);
        int civicCap = Math.max(2, Math.min(4, totalBuildings / 6 + 1));
        double midTangentRad = RecipeHelpers.localTangentRad(
                mainCenterline, mainCenterline.size() / 2);

        int civicSnapshot = pctx.slotPoolSize();
        RecipeHelpers.installLinearPlaza(pctx, squareMid,
                PlazaPurpose.CIVIC, RecipeHelpers.cardinalFromRad(midTangentRad));
        List<PlacementSlot> civicSlots = pctx.drainSlotsSince(civicSnapshot);
        if (!civicSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    SECTOR_CIVIC, SectorRole.CIVIC_TIGHT, BuildingZone.CIVIC,
                    civicSlots, civicCap, false, FixedGrowth.INSTANCE,
                    mainEdgeId, null,
                    32));
        }

        // ── One-sided residential infill ──────────────────────────────────
        List<PlacementSlot> residentialSlots =
                RecipeHelpers.generateOneSidedSlotsAlongCenterline(
                        mainCenterline, mainEdgeId, TAGS_RESIDENTIAL,
                        7, 7, 50, sideSign);
        if (!residentialSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    SECTOR_RESIDENTIAL, SectorRole.RESIDENTIAL_INFILL,
                    BuildingZone.RESIDENTIAL, residentialSlots,
                    12, true, new ExtendAlongEdge(32, 4), mainEdgeId, null,
                    16));
        }

        // ── One-sided production infill ───────────────────────────────────
        List<PlacementSlot> productionSlots =
                RecipeHelpers.generateOneSidedSlotsAlongCenterline(
                        mainCenterline, mainEdgeId, TAGS_PRODUCTION,
                        12, 7, 45, sideSign);
        if (!productionSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    SECTOR_PRODUCTION, SectorRole.RESIDENTIAL_INFILL,
                    BuildingZone.PRODUCTION, productionSlots,
                    6, true, new ExtendAlongEdge(32, 3), mainEdgeId, null,
                    16));
        }
    }

    @Override
    protected void registerAnchors(PlanContext pctx) {
        super.registerAnchors(pctx);
    }

    /**
     * Determines which side of the main road to build on, preserving the
     * legacy terrain-dot-product logic but consulting {@code pctx.features}
     * (via a 24-block perpendicular sample) as primary check.
     *
     * <p>Returns -1 when the legacy logic would have used ALWAYS_LEFT (stubs
     * going left-of-heading), +1 for ALWAYS_RIGHT. The slot walk's
     * perpendicular direction is +perpX/+perpZ, so -1 places slots to the
     * negative-perp side (left of heading in the Minecraft XZ convention).
     */
    private int determineBuildSide(PlanContext pctx, BlockPos centre, double mainDirRad) {
        // Perpendicular to the right of heading (headX=cos, headZ=sin →
        // perpX=-sin, perpZ=cos); same as the offerRoadSlots/slot-walk
        // convention for side=+1.
        double perpX = -Math.sin(mainDirRad);
        double perpZ =  Math.cos(mainDirRad);

        // Sample 24 blocks to the "positive perp" side.
        BlockPos perpSample = centre.offset(
                (int) Math.round(perpX * 24), 0, (int) Math.round(perpZ * 24));

        // If the positive-perp side has a feature, build on the negative side.
        if (pctx.features.isOnWater(perpSample)) return -1;
        if (pctx.features.isOnCliff(perpSample)) return -1;

        // Fallback: honour legacy terrain water/ridge dot-product check.
        TerrainProfile terrain = pctx.layout.getTerrain();
        if (terrain.hasWater()) {
            BlockPos water = terrain.waterBody().centre();
            double wDx = water.getX() - centre.getX();
            double wDz = water.getZ() - centre.getZ();
            // Positive dot → water is on the positive-perp side → build negative.
            if (wDx * perpX + wDz * perpZ > 0) return -1;
        } else if (terrain.hasRidges()) {
            var ridge = terrain.ridges().get(0);
            int rcx = (ridge.min().getX() + ridge.max().getX()) / 2;
            int rcz = (ridge.min().getZ() + ridge.max().getZ()) / 2;
            double rDx = rcx - centre.getX();
            double rDz = rcz - centre.getZ();
            if (rDx * perpX + rDz * perpZ > 0) return -1;
        }

        return +1;
    }

    /** Two gateways at the road's terminal ends. */
    @Override
    public List<GatewayDescriptor> describeGateways(PlanContext pctx) {
        return GatewayDescriptor.deriveFromLayout(pctx);
    }

    /** Single THROUGH_VILLAGE edge along the main road. */
    @Override
    public List<VillageEdgeDescriptor> describeInternalRoads(PlanContext pctx) {
        return LinearRecipe.deriveThruRoad(pctx);
    }
}
