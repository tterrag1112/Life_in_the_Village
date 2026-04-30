// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/TerracedRecipe.java
package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaPurpose;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
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
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * TERRACED — village built on a moderate slope with stepped rows.
 *
 * <p>{@value #TERRACE_COUNT} parallel terrace roads run along contour
 * lines (perpendicular to the slope direction) at decreasing Y. Each
 * terrace is its own row of buildings. {@link RoadPrimitive.Stairway}
 * ramps connect adjacent terraces end-to-end (alternating sides) so the
 * village reads as a stepped path zig-zagging up the slope.
 *
 * <p>Reads as: vineyard village, hill town, rice-paddy settlement,
 * Mediterranean coastal town. The slope IS the structure.
 *
 * <p>Falls back to {@link LinearRecipe} if the terrain has no
 * significant slope ({@link TerrainProfile#hasSlope()} returns false).
 */
public final class TerracedRecipe extends BaseRecipe {

    /** Number of terrace levels. */
    private static final int TERRACE_COUNT = 4;

    /** XZ offset between adjacent terraces, in blocks. */
    private static final int TERRACE_XZ_STEP = 14;

    private static final Set<SlotTag> TAGS_UPPER_TERRACE =
            EnumSet.of(SlotTag.SECONDARY_CIVIC, SlotTag.PRODUCTION_CLUSTER,
                       SlotTag.HIGH_GROUND, SlotTag.ROAD_ADJACENT,
                       SlotTag.TERRACE_EDGE);
    private static final Set<SlotTag> TAGS_MID_TERRACE =
            EnumSet.of(SlotTag.RESIDENTIAL_INFILL, SlotTag.CIVIC_ADJACENT,
                       SlotTag.ROAD_ADJACENT, SlotTag.TERRACE_EDGE);
    private static final Set<SlotTag> TAGS_LOWER_TERRACE =
            EnumSet.of(SlotTag.RESIDENTIAL_INFILL, SlotTag.RESIDENTIAL_OUTER,
                       SlotTag.PRODUCTION_INFILL, SlotTag.ROAD_ADJACENT,
                       SlotTag.TERRACE_EDGE);
    private static final Set<SlotTag> TAGS_BASE_AGRI =
            EnumSet.of(SlotTag.FIELD_EDGE, SlotTag.PASTURE,
                       SlotTag.TERRACE_EDGE);

    // Feature state — written by prepareFeatures, read by composeSectors.
    // Safe because ShapeRecipe.forShape() creates a fresh instance per village.
    private TerrainAnalyzer.FlatDirection cachedSlopeDir;
    private boolean cachedUsable;

    @Override
    protected void prepareFeatures(PlanContext pctx) {
        TerrainProfile terrain = pctx.layout.getTerrain();

        if (!terrain.hasSlope()) {
            cachedUsable = false;
            return;
        }

        cachedSlopeDir = terrain.slopeDir();
        cachedUsable   = true;
        // Like HILLTOP, the validator's ridge rejection would refuse most
        // slope positions. Allow ridge placement so terraces can exist on
        // hilly ground.
        pctx.allowRidgePlacement = true;
    }

    @Override
    protected void composeSectors(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();

        // ── Fallback: no slope → LINEAR ───────────────────────────────────
        if (!cachedUsable) {
            System.out.println("TerracedRecipe: no significant slope detected — "
                    + "falling back to LINEAR");
            PlacementFailureRecorder.record(
                    PlacementFailureRecorder.Reason.TERRAIN_UNSUITABLE,
                    "no significant slope detected",
                    centre, VillageTypeData.ShapeType.TERRACED.name());
            new LinearRecipe().compose(pctx);
            return;
        }

        // ── Slope and terrace direction setup ─────────────────────────────
        // Slope direction points downhill. Terraces run perpendicular to
        // it (along contour lines). Terrace heading = slope + π/2.
        double slopeRad           = RecipeHelpers.directionRadOf(cachedSlopeDir);
        double terraceHeadingRad  = slopeRad + Math.PI / 2;
        double tanX               = Math.cos(terraceHeadingRad);
        double tanZ               = Math.sin(terraceHeadingRad);
        double slopeUx            = Math.cos(slopeRad);
        double slopeUz            = Math.sin(slopeRad);

        int totalBuildings = pctx.remaining.size();
        int terraceLength  = Math.max(48, totalBuildings * 4
                + pctx.density.getRing2Radius() / 2);
        int middleIndex    = TERRACE_COUNT / 2;

        // ── Build each terrace as its own SPINE edge ──────────────────────
        // Index 0 = topmost (uphill); TERRACE_COUNT-1 = bottommost.
        List<TerraceData> terraces = new ArrayList<>();
        for (int i = 0; i < TERRACE_COUNT; i++) {
            int slopeOffset = (i - middleIndex) * TERRACE_XZ_STEP;
            // Negative offset (i < middle) = uphill (against slope);
            // positive offset = downhill.
            BlockPos terraceMid = pctx.solidSurface(new BlockPos(
                    centre.getX() - (int) Math.round(slopeUx * slopeOffset),
                    centre.getY(),
                    centre.getZ() - (int) Math.round(slopeUz * slopeOffset)));

            BlockPos terraceStart = pctx.solidSurface(new BlockPos(
                    terraceMid.getX() - (int) Math.round(tanX * terraceLength / 2.0),
                    terraceMid.getY(),
                    terraceMid.getZ() - (int) Math.round(tanZ * terraceLength / 2.0)));
            BlockPos terraceEnd = pctx.solidSurface(new BlockPos(
                    terraceMid.getX() + (int) Math.round(tanX * terraceLength / 2.0),
                    terraceMid.getY(),
                    terraceMid.getZ() + (int) Math.round(tanZ * terraceLength / 2.0)));

            int startNodeId = pctx.layout.addNode(
                    terraceStart, NodeKind.TERMINUS, RoadShape.RoadTier.VILLAGE_PATH);
            int endNodeId = pctx.layout.addNode(
                    terraceEnd, NodeKind.TERMINUS, RoadShape.RoadTier.VILLAGE_PATH);

            RoadPrimitive.StraightRoad terraceRoad = new RoadPrimitive.StraightRoad(
                    terraceStart, terraceEnd, 3.0, RoadShape.RoadTier.VILLAGE_PATH);
            int terraceEdgeId = pctx.layout.addEdge(
                    startNodeId, endNodeId, terraceRoad,
                    pctx.level, pctx.worldSeed, EdgeRole.SPINE);

            List<BlockPos> terraceCenterline =
                    pctx.layout.getRoadGraph().edge(terraceEdgeId).centerline();

            terraces.add(new TerraceData(i, startNodeId, endNodeId,
                    terraceEdgeId, terraceCenterline, terraceMid));
        }

        // ── Connect adjacent terraces with Stairway ramps ─────────────────
        // Even-i ramps go from upper.end → lower.end (right side);
        // odd-i ramps go from upper.start → lower.start (left side).
        // The result is a zig-zag switchback up the slope.
        for (int i = 0; i < TERRACE_COUNT - 1; i++) {
            TerraceData upper = terraces.get(i);
            TerraceData lower = terraces.get(i + 1);
            boolean rightSide      = (i % 2 == 0);
            int upperEndNodeId     = rightSide ? upper.endNodeId   : upper.startNodeId;
            int lowerEndNodeId     = rightSide ? lower.endNodeId   : lower.startNodeId;

            BlockPos upperPos = pctx.layout.getRoadGraph().node(upperEndNodeId).pos();
            BlockPos lowerPos = pctx.layout.getRoadGraph().node(lowerEndNodeId).pos();

            RoadPrimitive.Stairway ramp = new RoadPrimitive.Stairway(
                    lowerPos, upperPos, RoadShape.RoadTier.VILLAGE_PATH);
            pctx.layout.addEdge(
                    upperEndNodeId, lowerEndNodeId, ramp,
                    pctx.level, pctx.worldSeed, EdgeRole.SPUR);
        }

        // ── Promote the bottom-terrace approach end to a GATE ─────────────
        TerraceData lowestTerrace      = terraces.get(TERRACE_COUNT - 1);
        // The ramp from terrace TERRACE_COUNT-2 to TERRACE_COUNT-1 attaches
        // to the right side iff (TERRACE_COUNT-2) is even. The main gate
        // sits on the OPPOSITE end so the player approaches the village
        // from the bottom row first.
        boolean lowestConnectsRight    = ((TERRACE_COUNT - 2) % 2 == 0);
        int mainGateNodeId             = lowestConnectsRight
                ? lowestTerrace.startNodeId
                : lowestTerrace.endNodeId;
        BlockPos mainGatePos           =
                pctx.layout.getRoadGraph().node(mainGateNodeId).pos();
        pctx.layout.getRoadGraph().markAsGate(mainGateNodeId);
        pctx.layout.setMainGateEndpoint(mainGatePos);
        pctx.layout.addGatePosition(mainGatePos);

        // ── Town square on the middle terrace ─────────────────────────────
        TerraceData middleTerrace = terraces.get(middleIndex);
        BlockPos squareCentre     = middleTerrace.midPoint;
        int squareCapacity        = Math.max(3, Math.min(6, totalBuildings / 6 + 2));

        pctx.layout.setTownSquarePos(squareCentre);
        pctx.layout.setTownSquareRadius(4);

        int civicSnapshot = pctx.slotPoolSize();
        RecipeHelpers.installLinearPlaza(pctx, squareCentre, PlazaPurpose.CIVIC,
                RecipeHelpers.cardinalFromRad(terraceHeadingRad));
        List<PlacementSlot> civicSlots = pctx.drainSlotsSince(civicSnapshot);

        pctx.offerSector(new Sector(
                "terraced_civic",
                SectorRole.CIVIC_TIGHT,
                BuildingZone.CIVIC,
                civicSlots,
                squareCapacity,
                false,
                FixedGrowth.INSTANCE,
                middleTerrace.edgeId,
                null,
                32));

        // ── A sector per terrace ──────────────────────────────────────────
        // Tier the sectors by terrace index: top = upper (civic/production
        // priority), middle = mid (residential infill, filtered to avoid
        // the plaza), bottom = lower (residential overflow).
        for (TerraceData t : terraces) {
            Set<SlotTag> tags;
            int baseQuality;
            BuildingZone zoneHint;
            int capacity;

            if (t.index < middleIndex) {
                tags        = TAGS_UPPER_TERRACE;
                baseQuality = 55;
                zoneHint    = BuildingZone.CIVIC;
                capacity    = 6;
            } else if (t.index == middleIndex) {
                tags        = TAGS_MID_TERRACE;
                baseQuality = 50;
                zoneHint    = BuildingZone.RESIDENTIAL;
                capacity    = 8;
            } else {
                tags        = TAGS_LOWER_TERRACE;
                baseQuality = 40;
                zoneHint    = BuildingZone.RESIDENTIAL;
                capacity    = 8;
            }

            List<PlacementSlot> terraceSlots =
                    RecipeHelpers.generateSlotsAlongCenterline(
                            t.centerline, t.edgeId, tags, 8, 6, baseQuality);

            // For the middle terrace, filter slots that overlap the plaza
            // so terrace residential doesn't conflict with civic placement.
            if (t.index == middleIndex) {
                BlockPos sqCentre = pctx.layout.getTownSquarePos();
                int sqRadius      = pctx.layout.getCivicRingRadius();
                if (sqCentre != null && sqRadius > 0) {
                    int squareGap = sqRadius + 4;
                    long gapSq    = (long) squareGap * squareGap;
                    terraceSlots = terraceSlots.stream()
                            .filter(s -> s.pos().distSqr(sqCentre) > gapSq)
                            .toList();
                }
            }

            pctx.offerSector(new Sector(
                    "terraced_terrace_" + t.index,
                    SectorRole.RESIDENTIAL_INFILL,
                    zoneHint,
                    terraceSlots,
                    capacity,
                    true,
                    new ExtendAlongEdge(28, 4),
                    t.edgeId,
                    null,
                    16));
        }

        // ── Base agricultural ring (downhill of the bottom terrace) ───────
        BlockPos agriCentre = pctx.solidSurface(new BlockPos(
                lowestTerrace.midPoint.getX()
                        + (int) Math.round(slopeUx * pctx.density.getRing1Radius()),
                centre.getY(),
                lowestTerrace.midPoint.getZ()
                        + (int) Math.round(slopeUz * pctx.density.getRing1Radius())));

        List<PlacementSlot> agriSlots = RecipeHelpers.generateRingSlots(
                agriCentre,
                pctx.density.getRing1Radius() / 2,
                pctx.density.getRing2Radius(),
                TAGS_BASE_AGRI,
                6, 35, pctx);

        pctx.offerSector(new Sector(
                "terraced_base_agri",
                SectorRole.AGRICULTURAL_FRINGE,
                BuildingZone.AGRICULTURAL,
                agriSlots,
                6,
                true,
                new AddRing(14, 6),
                -1,
                null,
                18));
    }

    /** Bookkeeping holder for each terrace's identity in the road graph. */
    private record TerraceData(
            int index,
            int startNodeId,
            int endNodeId,
            int edgeId,
            List<BlockPos> centerline,
            BlockPos midPoint
    ) {}
}
