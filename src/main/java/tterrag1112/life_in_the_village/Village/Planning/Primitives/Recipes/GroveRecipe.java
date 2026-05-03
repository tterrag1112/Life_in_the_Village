// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/GroveRecipe.java
package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.LayoutSlot;
import tterrag1112.life_in_the_village.Village.Planning.Plaza;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.*;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.AddRing;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.AddSpur;
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
 * GROVE — sacred-center village.
 *
 * <p>The center is empty (or contains a natural feature treated as a
 * decoration slot), and civic buildings are pushed outward to a much
 * wider ring than usual. Houses and production sit OUTSIDE the civic
 * ring on conventional spurs. Nothing is placed inside the central
 * reserve.
 *
 * <p>Reads as: druid grove, sacred-spring village, animist settlement.
 * The "center" is a place of reverence rather than commerce.
 *
 * <p>Phase 15: extends {@link BaseRecipe} and emits sectors. Geometry
 * is identical to the pre-conversion version — same plaza, same spur
 * angles, same cluster arcs, same outer rings. Building assignment now
 * happens in the matcher rather than during recipe execution.
 */
public final class GroveRecipe extends BaseRecipe {

    /** Multiplier on the civic ring radius vs. RADIAL's default. */
    private static final double GROVE_RING_MULTIPLIER = 1.6;

    private static final Set<SlotTag> TAGS_SPUR_CLUSTER =
            EnumSet.of(SlotTag.PRODUCTION_CLUSTER, SlotTag.PRODUCTION_SPUR_END,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_SPUR_ROAD =
            EnumSet.of(SlotTag.ROAD_ADJACENT, SlotTag.RESIDENTIAL_INFILL,
                    SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_OUTER_AGRI =
            EnumSet.of(SlotTag.FIELD_EDGE, SlotTag.PASTURE, SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_OUTER_DEFENSE =
            EnumSet.of(SlotTag.WALL_ADJACENT, SlotTag.HIGH_GROUND,
                    SlotTag.RESIDENTIAL_OUTER);

    private static final int SPUR_CAPACITY = 5;
    private static final int UNLIMITED_CAPACITY = 1024;

    private boolean terrainTooRough;

    @Override
    protected void prepareFeatures(PlanContext pctx) {
        int largest = RecipeHelpers.largestRotatedFootprint(pctx);
        int requiredFlatSide = largest + 4;
        int available = pctx.layout.getTerrain().largestFlatPatchAvailable(2);
        terrainTooRough = available < requiredFlatSide;
        if (terrainTooRough) {
            System.out.println("GroveRecipe: terrain pre-check failed — "
                    + "largest=" + largest + " available=" + available
                    + " — falling back to RADIAL");
        }
    }

    @Override
    protected void composeSectors(PlanContext pctx) {
        if (terrainTooRough) {
            PlacementFailureRecorder.record(
                    PlacementFailureRecorder.Reason.TERRAIN_UNSUITABLE,
                    "GROVE: no flat patch large enough for largest building",
                    pctx.layout.getCenter(),
                    VillageTypeData.ShapeType.GROVE.name());
            new RadialRecipe().compose(pctx);
            return;
        }

        BlockPos centre = pctx.layout.getCenter();
        int totalBuildings = pctx.remaining.size();

        // ── Wide civic ring ──────────────────────────────────────────────────
        // Phase 18: civic slots live on Plaza, not the flat pool. GROVE
        // widens the civic ring after installPlaza via the Plaza façade
        // (plaza.withCivicRingRadius) and re-registers via replacePlaza.
        // The legacy setCivicRingRadius is kept in sync for plaza-less
        // consumers (decoration / footprint) that still read the field.
        RecipeHelpers.installPlaza(pctx, centre,
                tterrag1112.life_in_the_village.Village.Decoration
                        .Plaza.PlazaShape.IRREGULAR);
        BlockPos squarePos = pctx.layout.getTownSquarePos();
        int civicRing = pctx.layout.getCivicRingRadius();

        int wideRing = (int)(civicRing * GROVE_RING_MULTIPLIER);
        Plaza grovePlaza = pctx.layout.getPlaza();
        if (grovePlaza != null) {
            pctx.layout.replacePlaza(grovePlaza,
                    grovePlaza.withCivicRingRadius(wideRing));
        }
        pctx.layout.setCivicRingRadius(wideRing);
        pctx.layout.addForced(new LayoutSlot(
                LayoutSlot.SlotType.DECORATION, squarePos, wideRing - 4));

        // ── Spurs outward at evenly spaced angles ────────────────────────────
        int spurCount = Math.max(3, Math.min(6, totalBuildings / 4));
        List<List<BlockPos>> allRoads = new ArrayList<>();

        double baseAngle = pctx.rng.nextDouble() * 2 * Math.PI;
        double angleStep = 2 * Math.PI / spurCount;

        BlockPos mainGate = null;
        for (int i = 0; i < spurCount; i++) {
            double spurAngle = baseAngle + angleStep * i;
            BlockPos branchHint = RecipeHelpers.offsetSnapped(
                    pctx, squarePos, spurAngle, wideRing + 2);

            List<BlockPos> synthParent = List.of(squarePos, branchHint);
            int spurLen = pctx.density.getRing2Radius() + 8;
            RoadPrimitive.Spur spur = new RoadPrimitive.Spur(
                    synthParent, branchHint, spurAngle, spurLen,
                    5.0, RoadShape.RoadTier.VILLAGE_PATH);

            int beforeEdges = pctx.layout.getRoadGraph().edgeCount();
            List<BlockPos> spurCenterline = pctx.layout.addRoad(
                    spur, pctx.level, pctx.worldSeed);
            int spurEdgeId = pctx.layout.getRoadGraph().edgeCount() > beforeEdges
                    ? pctx.layout.getRoadGraph().edgeCount() - 1 : -1;
            allRoads.add(spurCenterline);

            BlockPos focal = spurCenterline.isEmpty() ? null
                    : spurCenterline.get(spurCenterline.size() - 1);
            if (focal != null) pctx.layout.addGatePosition(focal);
            if (i == 0 && focal != null) mainGate = focal;

            // ── Cluster arc at spur tip (same pattern as RADIAL) ─────────────
            BlockPos spurTip = spurCenterline.isEmpty() ? branchHint
                    : spurCenterline.get(spurCenterline.size() - 1);
            double tipDx = spurTip.getX() - squarePos.getX();
            double tipDz = spurTip.getZ() - squarePos.getZ();
            int clusterRadius = Math.max(8,
                    (int) Math.round(Math.sqrt(tipDx * tipDx + tipDz * tipDz)));
            double tipAngle = Math.atan2(tipDz, tipDx);

            RoadPrimitive.Arc clusterArc = new RoadPrimitive.Arc(
                    squarePos, clusterRadius,
                    tipAngle - Math.PI / 6,
                    Math.PI / 3,
                    3.0,
                    RoadShape.RoadTier.VILLAGE_PATH);
            int beforeClusterEdges = pctx.layout.getRoadGraph().edgeCount();
            List<BlockPos> clusterArcCenterline = pctx.layout.addRoad(
                    clusterArc, pctx.level, pctx.worldSeed);
            int clusterArcEdgeId = pctx.layout.getRoadGraph().edgeCount() > beforeClusterEdges
                    ? pctx.layout.getRoadGraph().edgeCount() - 1 : spurEdgeId;
            allRoads.add(clusterArcCenterline);

            // Snapshot before emitting per-spur slots; everything emitted
            // until drain becomes this spur's sector.
            int spurSnapshot = pctx.slotPoolSize();
            pctx.offerRoadSlots(spurCenterline, 6, 7, TAGS_SPUR_ROAD, 40);

            int clusterFp = 16;
            int clusterPerpOffset = 3 + clusterFp / 2 + 2;
            int clusterStride = 10;
            for (int j = 0; j < clusterArcCenterline.size(); j += clusterStride) {
                BlockPos arcPt = clusterArcCenterline.get(j);
                double outDx = arcPt.getX() - squarePos.getX();
                double outDz = arcPt.getZ() - squarePos.getZ();
                double outLen = Math.sqrt(outDx * outDx + outDz * outDz);
                if (outLen < 1) continue;
                int slotX = arcPt.getX()
                        + (int) Math.round(outDx / outLen * clusterPerpOffset);
                int slotZ = arcPt.getZ()
                        + (int) Math.round(outDz / outLen * clusterPerpOffset);
                BlockPos slotPos = pctx.solidSurface(
                        new BlockPos(slotX, arcPt.getY(), slotZ));
                if (pctx.features.isOnWater(slotPos)) continue;
                if (pctx.features.isOnCliff(slotPos)) continue;
                pctx.offerSlot(new PlacementSlot(
                        slotPos,
                        clusterArcCenterline,
                        clusterArcEdgeId,
                        TAGS_SPUR_CLUSTER,
                        clusterFp, clusterFp,
                        null,
                        65,
                        0));
            }

            List<PlacementSlot> spurSlots = pctx.drainSlotsSince(spurSnapshot);
            if (!spurSlots.isEmpty()) {
                pctx.offerSector(new Sector(
                        "grove_spur_" + i,
                        SectorRole.SPUR_CLUSTER,
                        BuildingZone.PRODUCTION,
                        spurSlots,
                        SPUR_CAPACITY,
                        true,
                        new AddSpur(48, 4),
                        spurEdgeId,
                        null,
                        16));
            }
        }

        if (mainGate != null) pctx.layout.setMainGateEndpoint(mainGate);

        // ── Outer agricultural FRINGE sector ─────────────────────────────────
        int outerR = pctx.density.getRing2Radius();
        int agriSnapshot = pctx.slotPoolSize();
        LayoutPrimitive.RingBand agriBand = new LayoutPrimitive.RingBand(
                centre, outerR + 8, outerR + 28,
                BuildingZone.AGRICULTURAL, new ArrayList<>(), allRoads);
        agriBand.emitSlots(pctx);
        List<PlacementSlot> agriSlots = pctx.drainSlotsSince(agriSnapshot);
        if (!agriSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    "grove_outer_agri",
                    SectorRole.AGRICULTURAL_FRINGE,
                    BuildingZone.AGRICULTURAL,
                    agriSlots,
                    UNLIMITED_CAPACITY,
                    true,
                    new AddRing(16, 8),
                    -1,
                    null,
                    18));
        }

        // ── Outer defensive FRINGE sector ────────────────────────────────────
        int defSnapshot = pctx.slotPoolSize();
        LayoutPrimitive.RingBand defenseBand = new LayoutPrimitive.RingBand(
                centre, outerR + 4, outerR + 14,
                BuildingZone.DEFENSIVE, new ArrayList<>(), allRoads);
        defenseBand.emitSlots(pctx);
        List<PlacementSlot> defSlots = pctx.drainSlotsSince(defSnapshot);
        if (!defSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    "grove_outer_defense",
                    SectorRole.DEFENSIVE_FRINGE,
                    BuildingZone.DEFENSIVE,
                    defSlots,
                    UNLIMITED_CAPACITY,
                    true,
                    new AddRing(12, 6),
                    -1,
                    null,
                    18));
        }
    }
}
