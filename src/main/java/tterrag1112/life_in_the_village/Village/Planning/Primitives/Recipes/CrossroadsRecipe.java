// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/CrossroadsRecipe.java
package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.LayoutPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.ShapeRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.List;

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
 */
public final class CrossroadsRecipe implements ShapeRecipe {

    @Override
    public void compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();

        int totalBuildings = pctx.remaining.size();

        // ── Square at the intersection ─────────────────────────────────────
        int civicCap = Math.max(3, Math.min(6, totalBuildings / 4 + 2));
        new LayoutPrimitive.TownSquare(centre, civicCap, null).place(pctx);
        BlockPos squarePos = pctx.layout.getTownSquarePos();
        int civicRing = pctx.layout.getCivicRingRadius();

        // ── Choose axes ────────────────────────────────────────────────────
        // Primary axis aligned with bestFlatDir, secondary perpendicular.
        // Primary arms get full length; secondary arms get 75% length.
        double primaryRad = directionRadOf(terrain.bestFlatDir());
        double secondaryRad = primaryRad + Math.PI / 2;

        int primaryArmLen = pctx.density.getRing2Radius() * 2 + totalBuildings * 3 + 32;
        int secondaryArmLen = (int)(primaryArmLen * 0.75);

        // Four arms: primary+, primary-, secondary+, secondary-
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

        // Primary arms: VILLAGE_ROAD tier, more drift
        List<BlockPos> primaryA = pctx.layout.addRoad(
                new RoadPrimitive.StraightRoad(primaryStartA, primaryEndA,
                        8.0, RoadShape.RoadTier.VILLAGE_ROAD),
                pctx.level, pctx.worldSeed);
        List<BlockPos> primaryB = pctx.layout.addRoad(
                new RoadPrimitive.StraightRoad(primaryStartB, primaryEndB,
                        8.0, RoadShape.RoadTier.VILLAGE_ROAD),
                pctx.level, pctx.worldSeed);
        // Secondary arms: VILLAGE_PATH tier, less drift
        List<BlockPos> secondaryA = pctx.layout.addRoad(
                new RoadPrimitive.StraightRoad(secondaryStartA, secondaryEndA,
                        5.0, RoadShape.RoadTier.VILLAGE_PATH),
                pctx.level, pctx.worldSeed);
        List<BlockPos> secondaryB = pctx.layout.addRoad(
                new RoadPrimitive.StraightRoad(secondaryStartB, secondaryEndB,
                        5.0, RoadShape.RoadTier.VILLAGE_PATH),
                pctx.level, pctx.worldSeed);

        // Gate endpoint: the longer arm's far end (primary)
        pctx.layout.setMainGateEndpoint(primaryEndA);
        // Register all four arm ends so directional routing can pick the
        // gate facing the destination village.
        pctx.layout.addGatePosition(primaryEndA);
        pctx.layout.addGatePosition(primaryEndB);
        pctx.layout.addGatePosition(secondaryEndA);
        pctx.layout.addGatePosition(secondaryEndB);

        List<List<BlockPos>> allRoadsForSnap = new ArrayList<>();
        allRoadsForSnap.add(primaryA);
        allRoadsForSnap.add(primaryB);
        allRoadsForSnap.add(secondaryA);
        allRoadsForSnap.add(secondaryB);

        // ── Distribute production + residential across the four arms ───────
        List<VillageTypeData.StarterBuilding> production =
                pctx.claimByZone(BuildingZone.PRODUCTION, 1000);
        List<VillageTypeData.StarterBuilding> residential =
                pctx.claimByZone(BuildingZone.RESIDENTIAL, 1000);

        List<List<VillageTypeData.StarterBuilding>> armBuckets = new ArrayList<>();
        for (int i = 0; i < 4; i++) armBuckets.add(new ArrayList<>());
        int b = 0;
        for (VillageTypeData.StarterBuilding sb : production) armBuckets.get(b++ % 4).add(sb);
        for (VillageTypeData.StarterBuilding sb : residential) armBuckets.get(b++ % 4).add(sb);

        List<List<BlockPos>> arms = List.of(primaryA, primaryB, secondaryA, secondaryB);
        double[] armAngles = {primaryRad, primaryRad + Math.PI,
                secondaryRad, secondaryRad + Math.PI};

        // ── Stub spurs along each arm ──────────────────────────────────────
        for (int armIdx = 0; armIdx < 4; armIdx++) {
            List<VillageTypeData.StarterBuilding> bucket = armBuckets.get(armIdx);
            if (bucket.isEmpty()) continue;
            List<BlockPos> arm = arms.get(armIdx);
            double armAngle = armAngles[armIdx];

            // Distribute stub branch points along the arm at fractions
            // 0.3, 0.55, 0.8 (skip 0 so they don't bunch at the square)
            double[] fractions = bucket.size() == 1 ? new double[]{0.5}
                    : bucket.size() == 2 ? new double[]{0.4, 0.75}
                    : new double[]{0.3, 0.55, 0.8};

            for (int i = 0; i < bucket.size(); i++) {
                double frac = fractions[Math.min(i, fractions.length - 1)];
                int idx = Math.min(arm.size() - 1, (int)(frac * arm.size()));
                BlockPos branchHint = arm.get(idx);

                // Stub perpendicular to arm, alternating sides
                boolean leftSide = (i & 1) == 0;
                double stubAngle = armAngle + (leftSide ? -Math.PI / 2 : Math.PI / 2);
                stubAngle += (pctx.rng.nextDouble() - 0.5) * 0.3;

                int stubLen = 8 + pctx.rng.nextInt(6);
                RoadPrimitive.Spur stub = new RoadPrimitive.Spur(
                        arm, branchHint, stubAngle, stubLen,
                        2.0, RoadShape.RoadTier.VILLAGE_PATH);
                List<BlockPos> stubLine = pctx.layout.addRoad(
                        stub, pctx.level, pctx.worldSeed);
                allRoadsForSnap.add(stubLine);

                BlockPos focal = stubLine.get(stubLine.size() - 1);
                List<VillageTypeData.StarterBuilding> single = List.of(bucket.get(i));
                new LayoutPrimitive.BuildingCircle(
                        focal, LayoutPrimitive.BuildingCircle.Mode.SCATTER,
                        single, stubLine
                ).place(pctx);

                // If bucket is larger than fractions list, the rest snap
                // to nearest arm via the straggler RingBand at the bottom.
                if (i + 1 >= fractions.length && i + 1 < bucket.size()) {
                    // Push remaining bucket buildings back to remaining
                    for (int j = i + 1; j < bucket.size(); j++) {
                        pctx.remaining.add(bucket.get(j));
                    }
                    break;
                }
            }
        }

        // ── Agricultural in the four wedges between the arms ───────────────
        List<VillageTypeData.StarterBuilding> agri =
                pctx.claimByZone(BuildingZone.AGRICULTURAL, 1000);
        if (!agri.isEmpty()) {
            new LayoutPrimitive.RingBand(
                    centre,
                    pctx.density.getRing2Radius() + 6,
                    pctx.density.getRing2Radius() + 24,
                    BuildingZone.AGRICULTURAL, agri, allRoadsForSnap
            ).place(pctx);
        }

        // ── Defensive at the four arm ends as gate guards ──────────────────
        List<VillageTypeData.StarterBuilding> defensive =
                pctx.claimByZone(BuildingZone.DEFENSIVE, 1000);
        if (!defensive.isEmpty()) {
            new LayoutPrimitive.RingBand(
                    centre,
                    pctx.density.getRing2Radius(),
                    pctx.density.getRing2Radius() + 12,
                    BuildingZone.DEFENSIVE, defensive, allRoadsForSnap
            ).place(pctx);
        }

        // ── Stragglers ─────────────────────────────────────────────────────
        if (!pctx.remaining.isEmpty()) {
            List<VillageTypeData.StarterBuilding> leftovers =
                    new ArrayList<>(pctx.remaining);
            pctx.remaining.clear();
            new LayoutPrimitive.RingBand(
                    centre,
                    civicRing + 4,
                    pctx.density.getRing2Radius(),
                    BuildingZone.RESIDENTIAL, leftovers, allRoadsForSnap
            ).place(pctx);
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