// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/LinearRecipe.java
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
 * LINEAR layout recipe.
 *
 * <p>A village strung along a single main street with the town square
 * as a widening at the midpoint. Buildings sit on both sides of the
 * street via short perpendicular stub spurs. No outer ring, no radial
 * spurs — the main road IS the village. Use for trade outposts, trail
 * hamlets, mining camps, anywhere that grew along a route.
 */
public final class LinearRecipe implements ShapeRecipe {

    @Override
    public void compose(PlanContext pctx) {
        BlockPos origin = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();

        int totalBuildings = pctx.remaining.size();

        // ── Main road direction = best flat direction ──────────────────────
        double mainDirRad = directionRadOf(terrain.bestFlatDir());
        double tanX = Math.cos(mainDirRad);
        double tanZ = Math.sin(mainDirRad);

        // ── Main road length: scales hard with building count ──────────────
        // Each side of the street holds totalBuildings/2 stubs at ~10
        // block intervals, so the road needs to be at least that long
        // plus tails on each end.
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
        List<BlockPos> mainCenterline = pctx.layout.addRoad(
                mainRoad, pctx.level, pctx.worldSeed);

        // Gate endpoint = whichever end is closer to the world spawn-ish
        // direction. For now just pick mainEnd; trade routes will work either way.
        pctx.layout.setMainGateEndpoint(mainEnd);

        // ── Town square at the midpoint ────────────────────────────────────
        BlockPos squareMid = mainCenterline.get(mainCenterline.size() / 2);

        // Tight civic capacity — civic forms a small cluster right
        // around the square, the rest of the village hangs off stubs.
        int civicCap = Math.max(2, Math.min(4, totalBuildings / 6 + 1));
        new LayoutPrimitive.TownSquare(squareMid, civicCap, mainCenterline)
                .place(pctx);

        List<List<BlockPos>> allRoadsForSnap = new ArrayList<>();
        allRoadsForSnap.add(mainCenterline);

        // ── Production + residential as alternating-side stubs ─────────────
        List<VillageTypeData.StarterBuilding> production =
                pctx.claimByZone(BuildingZone.PRODUCTION, 1000);
        List<VillageTypeData.StarterBuilding> residential =
                pctx.claimByZone(BuildingZone.RESIDENTIAL, 1000);

        List<VillageTypeData.StarterBuilding> stubBuildings = new ArrayList<>();
        // Interleave production and residential so the street isn't all
        // shops on one end and houses on the other
        int p = 0, r = 0;
        while (p < production.size() || r < residential.size()) {
            if (p < production.size()) stubBuildings.add(production.get(p++));
            if (r < residential.size()) stubBuildings.add(residential.get(r++));
        }

        // Stub spacing: distribute stubs evenly along the road, skipping
        // a margin around the square so the civic cluster has breathing room
        int squareIdx = mainCenterline.size() / 2;
        int squareMargin = Math.max(8, mainCenterline.size() / 8);
        int usableStart = 4;
        int usableEnd = mainCenterline.size() - 4;

        int stubCount = stubBuildings.size();
        if (stubCount > 0) {
            // Build a list of valid centerline indices avoiding the square margin
            List<Integer> stubIndices = new ArrayList<>();
            int leftCount = stubCount / 2;
            int rightCount = stubCount - leftCount;
            // Left of square
            for (int i = 0; i < leftCount; i++) {
                int range = (squareIdx - squareMargin) - usableStart;
                if (range <= 0) break;
                int idx = usableStart + (range * i) / Math.max(1, leftCount);
                stubIndices.add(idx);
            }
            // Right of square
            for (int i = 0; i < rightCount; i++) {
                int range = usableEnd - (squareIdx + squareMargin);
                if (range <= 0) break;
                int idx = (squareIdx + squareMargin) + (range * i) / Math.max(1, rightCount);
                stubIndices.add(idx);
            }

            // Place each building on an alternating side via a 6-block stub
            for (int i = 0; i < stubBuildings.size() && i < stubIndices.size(); i++) {
                int idx = stubIndices.get(i);
                BlockPos branchHint = mainCenterline.get(idx);
                boolean leftSide = (i & 1) == 0;
                double stubAngle = mainDirRad + (leftSide ? -Math.PI / 2 : Math.PI / 2);
                // Small jitter so not laser-perpendicular
                stubAngle += (pctx.rng.nextDouble() - 0.5) * 0.3;

                int stubLength = 6 + pctx.rng.nextInt(4);
                RoadPrimitive.Spur stub = new RoadPrimitive.Spur(
                        mainCenterline,
                        branchHint,
                        stubAngle,
                        stubLength,
                        1.5,
                        RoadShape.RoadTier.FOOTPATH);
                List<BlockPos> stubCenterline = pctx.layout.addRoad(
                        stub, pctx.level, pctx.worldSeed);
                allRoadsForSnap.add(stubCenterline);

                BlockPos focal = stubCenterline.get(stubCenterline.size() - 1);
                List<VillageTypeData.StarterBuilding> single =
                        List.of(stubBuildings.get(i));
                new LayoutPrimitive.BuildingCircle(
                        focal,
                        LayoutPrimitive.BuildingCircle.Mode.SCATTER,
                        single,
                        stubCenterline
                ).place(pctx);
            }
        }

        // ── Agricultural at the two ends of the main road ──────────────────
        List<VillageTypeData.StarterBuilding> agri =
                pctx.claimByZone(BuildingZone.AGRICULTURAL, 1000);
        if (!agri.isEmpty()) {
            // Split between the two ends
            int half = (agri.size() + 1) / 2;
            placeFarmCluster(pctx, mainStart, mainDirRad + Math.PI,
                    agri.subList(0, half), allRoadsForSnap);
            if (half < agri.size()) {
                placeFarmCluster(pctx, mainEnd, mainDirRad,
                        agri.subList(half, agri.size()), allRoadsForSnap);
            }
        }

        // ── Defensive: one at each end of the main road as gatehouses ──────
        List<VillageTypeData.StarterBuilding> defensive =
                pctx.claimByZone(BuildingZone.DEFENSIVE, 1000);
        if (!defensive.isEmpty()) {
            new LayoutPrimitive.RingBand(
                    origin,
                    halfLength - 8, halfLength + 4,
                    BuildingZone.DEFENSIVE, defensive, allRoadsForSnap
            ).place(pctx);
        }

        // ── Stragglers: just snap to the main road ─────────────────────────
        if (!pctx.remaining.isEmpty()) {
            List<VillageTypeData.StarterBuilding> leftovers =
                    new ArrayList<>(pctx.remaining);
            pctx.remaining.clear();
            new LayoutPrimitive.RingBand(
                    origin,
                    8, halfLength,
                    BuildingZone.RESIDENTIAL, leftovers, allRoadsForSnap
            ).place(pctx);
        }
    }

    /** Places a small cluster of farm buildings extending past the road's end. */
    private void placeFarmCluster(PlanContext pctx, BlockPos roadEnd, double outwardAngle,
                                  List<VillageTypeData.StarterBuilding> farms,
                                  List<List<BlockPos>> snapRoads) {
        if (farms.isEmpty()) return;
        BlockPos focal = new BlockPos(
                roadEnd.getX() + (int) Math.round(Math.cos(outwardAngle) * 16),
                roadEnd.getY(),
                roadEnd.getZ() + (int) Math.round(Math.sin(outwardAngle) * 16));
        focal = pctx.solidSurface(focal);

        new LayoutPrimitive.BuildingCircle(
                focal,
                LayoutPrimitive.BuildingCircle.Mode.SCATTER,
                farms,
                snapRoads.get(0) // main road for facing
        ).place(pctx);
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