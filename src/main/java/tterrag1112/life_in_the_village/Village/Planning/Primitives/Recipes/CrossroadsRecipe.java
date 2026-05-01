// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/CrossroadsRecipe.java
package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.BaseRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.LayoutPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.AddRing;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.AddSpur;
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
 * CROSSROADS layout recipe.
 *
 * <p>Two main roads crossing at right angles, with the town square at
 * the intersection. Civic buildings cluster in the four wedges between
 * the road arms. Production and residential branch off via stub spurs
 * along all four arms. The two arms forming the "primary" axis (chosen
 * by terrain) are longer and act as gate roads for trade routes.
 *
 * <p>Reads as: a market town that grew at a junction. The two-axis
 * topology is fundamentally different from RADIAL's single main road
 * and PLAZA's central plaza with diagonals.
 *
 * <p>Phase 15: extends {@link BaseRecipe} and emits sectors. Four arm
 * sectors (one per road arm) each contain the arm road slots plus two
 * stub spur slots, allowing the matcher to distribute buildings across
 * all four arms without recipe-level bucket splitting.
 */
public final class CrossroadsRecipe extends BaseRecipe {

    private static final Set<SlotTag> TAGS_ARM_ROAD =
            EnumSet.of(SlotTag.ROAD_ADJACENT, SlotTag.RESIDENTIAL_INFILL,
                    SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_ARM_STUB =
            EnumSet.of(SlotTag.PRODUCTION_CLUSTER, SlotTag.ROAD_ADJACENT,
                    SlotTag.RESIDENTIAL_INFILL);
    private static final Set<SlotTag> TAGS_OUTER_AGRI =
            EnumSet.of(SlotTag.FIELD_EDGE, SlotTag.PASTURE, SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_OUTER_DEFENSE =
            EnumSet.of(SlotTag.WALL_ADJACENT, SlotTag.HIGH_GROUND,
                    SlotTag.RESIDENTIAL_OUTER);

    private static final int ARM_CAPACITY = 8;
    private static final int UNLIMITED_CAPACITY = 1024;

    private boolean terrainTooRough;

    @Override
    protected void prepareFeatures(PlanContext pctx) {
        int largest = RecipeHelpers.largestRotatedFootprint(pctx);
        int requiredFlatSide = largest + 4;
        int available = pctx.layout.getTerrain().largestFlatPatchAvailable(2);
        terrainTooRough = available < requiredFlatSide;
        if (terrainTooRough) {
            System.out.println("CrossroadsRecipe: terrain pre-check failed — "
                    + "largest=" + largest + " available=" + available
                    + " — falling back to RADIAL");
        }
    }

    @Override
    protected void composeSectors(PlanContext pctx) {
        if (terrainTooRough) {
            PlacementFailureRecorder.record(
                    PlacementFailureRecorder.Reason.TERRAIN_UNSUITABLE,
                    "CROSSROADS: no flat patch large enough for largest building",
                    pctx.layout.getCenter(),
                    VillageTypeData.ShapeType.CROSSROADS.name());
            new RadialRecipe().compose(pctx);
            return;
        }

        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();
        int totalBuildings = pctx.remaining.size();

        // ── Square at the intersection ─────────────────────────────────────
        int civicCap = Math.max(3, Math.min(6, totalBuildings / 4 + 2));
        int civicSnapshot = pctx.slotPoolSize();
        RecipeHelpers.installPlaza(pctx, centre,
                tterrag1112.life_in_the_village.Village.Decoration
                        .Plaza.PlazaShape.SQUARE);
        BlockPos squarePos = pctx.layout.getTownSquarePos();
        int civicRing = pctx.layout.getCivicRingRadius();

        List<PlacementSlot> civicSlots = pctx.drainSlotsSince(civicSnapshot);
        if (!civicSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    "crossroads_civic_ring",
                    SectorRole.CIVIC_RING,
                    BuildingZone.CIVIC,
                    civicSlots,
                    civicCap,
                    false,
                    FixedGrowth.INSTANCE,
                    -1,
                    null,
                    32));
        }

        // ── Choose axes ────────────────────────────────────────────────────
        double primaryRad = directionRadOf(terrain.bestFlatDir());
        double secondaryRad = primaryRad + Math.PI / 2;

        int primaryArmLen = pctx.density.getRing2Radius() * 2 + totalBuildings * 3 + 32;
        int secondaryArmLen = (int)(primaryArmLen * 0.75);

        BlockPos primaryEndA = armEnd(squarePos, primaryRad, primaryArmLen, civicRing);
        BlockPos primaryEndB = armEnd(squarePos, primaryRad + Math.PI, primaryArmLen, civicRing);
        BlockPos secondaryEndA = armEnd(squarePos, secondaryRad, secondaryArmLen, civicRing);
        BlockPos secondaryEndB = armEnd(squarePos, secondaryRad + Math.PI, secondaryArmLen, civicRing);

        BlockPos primaryStartA = armStart(squarePos, primaryRad, civicRing);
        BlockPos primaryStartB = armStart(squarePos, primaryRad + Math.PI, civicRing);
        BlockPos secondaryStartA = armStart(squarePos, secondaryRad, civicRing);
        BlockPos secondaryStartB = armStart(squarePos, secondaryRad + Math.PI, civicRing);

        primaryEndA = pctx.solidSurface(primaryEndA);
        primaryEndB = pctx.solidSurface(primaryEndB);
        secondaryEndA = pctx.solidSurface(secondaryEndA);
        secondaryEndB = pctx.solidSurface(secondaryEndB);
        primaryStartA = pctx.solidSurface(primaryStartA);
        primaryStartB = pctx.solidSurface(primaryStartB);
        secondaryStartA = pctx.solidSurface(secondaryStartA);
        secondaryStartB = pctx.solidSurface(secondaryStartB);

        int beforePA = pctx.layout.getRoadGraph().edgeCount();
        List<BlockPos> primaryA = pctx.layout.addRoad(
                new RoadPrimitive.StraightRoad(primaryStartA, primaryEndA,
                        8.0, RoadShape.RoadTier.VILLAGE_ROAD),
                pctx.level, pctx.worldSeed);
        int edgePA = pctx.layout.getRoadGraph().edgeCount() > beforePA
                ? pctx.layout.getRoadGraph().edgeCount() - 1 : -1;

        int beforePB = pctx.layout.getRoadGraph().edgeCount();
        List<BlockPos> primaryB = pctx.layout.addRoad(
                new RoadPrimitive.StraightRoad(primaryStartB, primaryEndB,
                        8.0, RoadShape.RoadTier.VILLAGE_ROAD),
                pctx.level, pctx.worldSeed);
        int edgePB = pctx.layout.getRoadGraph().edgeCount() > beforePB
                ? pctx.layout.getRoadGraph().edgeCount() - 1 : -1;

        int beforeSA = pctx.layout.getRoadGraph().edgeCount();
        List<BlockPos> secondaryA = pctx.layout.addRoad(
                new RoadPrimitive.StraightRoad(secondaryStartA, secondaryEndA,
                        5.0, RoadShape.RoadTier.VILLAGE_PATH),
                pctx.level, pctx.worldSeed);
        int edgeSA = pctx.layout.getRoadGraph().edgeCount() > beforeSA
                ? pctx.layout.getRoadGraph().edgeCount() - 1 : -1;

        int beforeSB = pctx.layout.getRoadGraph().edgeCount();
        List<BlockPos> secondaryB = pctx.layout.addRoad(
                new RoadPrimitive.StraightRoad(secondaryStartB, secondaryEndB,
                        5.0, RoadShape.RoadTier.VILLAGE_PATH),
                pctx.level, pctx.worldSeed);
        int edgeSB = pctx.layout.getRoadGraph().edgeCount() > beforeSB
                ? pctx.layout.getRoadGraph().edgeCount() - 1 : -1;

        pctx.layout.setMainGateEndpoint(primaryEndA);
        pctx.layout.addGatePosition(primaryEndA);
        pctx.layout.addGatePosition(primaryEndB);
        pctx.layout.addGatePosition(secondaryEndA);
        pctx.layout.addGatePosition(secondaryEndB);

        List<List<BlockPos>> allRoadsForSnap = new ArrayList<>();
        allRoadsForSnap.add(primaryA);
        allRoadsForSnap.add(primaryB);
        allRoadsForSnap.add(secondaryA);
        allRoadsForSnap.add(secondaryB);

        // ── Per-arm sectors: road slots + two perpendicular stubs ──────────
        // Two fixed stubs per arm at fractions 0.35 and 0.65, alternating sides.
        List<List<BlockPos>> arms = List.of(primaryA, primaryB, secondaryA, secondaryB);
        double[] armAngles = {primaryRad, primaryRad + Math.PI,
                secondaryRad, secondaryRad + Math.PI};
        int[] armEdgeIds = {edgePA, edgePB, edgeSA, edgeSB};
        double[] stubFractions = {0.35, 0.65};

        for (int armIdx = 0; armIdx < 4; armIdx++) {
            List<BlockPos> arm = arms[armIdx];
            double armAngle = armAngles[armIdx];
            int armEdgeId = armEdgeIds[armIdx];

            int armSnapshot = pctx.slotPoolSize();
            pctx.offerRoadSlots(arm, 8, 8, TAGS_ARM_ROAD, 40);

            for (int si = 0; si < stubFractions.length; si++) {
                if (arm.size() < 4) break;
                int idx = Math.min(arm.size() - 1,
                        (int)(stubFractions[si] * arm.size()));
                BlockPos branchHint = arm.get(idx);

                boolean leftSide = (si & 1) == 0;
                double stubAngle = armAngle + (leftSide ? -Math.PI / 2 : Math.PI / 2);
                stubAngle += (pctx.rng.nextDouble() - 0.5) * 0.3;

                int stubLen = 8 + pctx.rng.nextInt(6);
                List<BlockPos> stubLine = pctx.layout.addRoad(
                        new RoadPrimitive.Spur(arm, branchHint, stubAngle, stubLen,
                                2.0, RoadShape.RoadTier.VILLAGE_PATH),
                        pctx.level, pctx.worldSeed);
                allRoadsForSnap.add(stubLine);
                pctx.offerRoadSlots(stubLine, 6, 6, TAGS_ARM_STUB, 55);
            }

            List<PlacementSlot> armSlots = pctx.drainSlotsSince(armSnapshot);
            if (!armSlots.isEmpty()) {
                pctx.offerSector(new Sector(
                        "crossroads_arm_" + armIdx,
                        SectorRole.SPUR_CLUSTER,
                        BuildingZone.PRODUCTION,
                        armSlots,
                        ARM_CAPACITY,
                        true,
                        new ExtendAlongEdge(40, 4),
                        armEdgeId,
                        null,
                        16));
            }
        }

        // ── Agricultural in the four wedges between the arms ───────────────
        int agriSnapshot = pctx.slotPoolSize();
        LayoutPrimitive.RingBand agriBand = new LayoutPrimitive.RingBand(
                centre,
                pctx.density.getRing2Radius() + 6,
                pctx.density.getRing2Radius() + 24,
                BuildingZone.AGRICULTURAL, new ArrayList<>(), allRoadsForSnap);
        agriBand.emitSlots(pctx);
        List<PlacementSlot> agriSlots = pctx.drainSlotsSince(agriSnapshot);
        if (!agriSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    "crossroads_outer_agri",
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

        // ── Defensive at the four arm ends as gate guards ──────────────────
        int defSnapshot = pctx.slotPoolSize();
        LayoutPrimitive.RingBand defenseBand = new LayoutPrimitive.RingBand(
                centre,
                pctx.density.getRing2Radius(),
                pctx.density.getRing2Radius() + 12,
                BuildingZone.DEFENSIVE, new ArrayList<>(), allRoadsForSnap);
        defenseBand.emitSlots(pctx);
        List<PlacementSlot> defSlots = pctx.drainSlotsSince(defSnapshot);
        if (!defSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    "crossroads_outer_defense",
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

    private static BlockPos armStart(BlockPos sq, double rad, int civicRing) {
        return new BlockPos(
                sq.getX() + (int) Math.round(Math.cos(rad) * civicRing),
                sq.getY(),
                sq.getZ() + (int) Math.round(Math.sin(rad) * civicRing));
    }

    private static BlockPos armEnd(BlockPos sq, double rad, int len, int civicRing) {
        return new BlockPos(
                sq.getX() + (int) Math.round(Math.cos(rad) * (civicRing + len)),
                sq.getY(),
                sq.getZ() + (int) Math.round(Math.sin(rad) * (civicRing + len)));
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
