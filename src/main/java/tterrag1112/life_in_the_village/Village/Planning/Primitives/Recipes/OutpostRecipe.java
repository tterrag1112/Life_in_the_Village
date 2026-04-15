// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/OutpostRecipe.java
package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.*;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.List;

/**
 * OUTPOST — small fortified core with outlying stations.
 *
 * <p>A tight central cluster (civic + town hall) ringed by defensive
 * buildings as a perimeter "wall." One or two outlying production
 * stations connected to the core by long footpaths. Reads as a
 * frontier military post or a remote logging/mining camp.
 */
public final class OutpostRecipe implements ShapeRecipe {

    @Override
    public void compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();
        int totalBuildings = pctx.remaining.size();

        // ── Tight central core ─────────────────────────────────────────────
        // Small civic capacity, tight ring
        int civicCap = Math.max(2, Math.min(4, totalBuildings / 5));
        new LayoutPrimitive.TownSquare(centre, civicCap, null).place(pctx);
        BlockPos squarePos = pctx.layout.getTownSquarePos();
        int civicRing = pctx.layout.getCivicRingRadius();

        // ── Defensive perimeter as the "wall" ──────────────────────────────
        // Place defensive in a TIGHT ring around the civic ring,
        // tangent to it so they form a continuous-feeling perimeter.
        var defensive = pctx.claimByZone(BuildingZone.DEFENSIVE, 1000);
        if (!defensive.isEmpty()) {
            // Ring road for defensive perimeter
            int defRingR = civicRing + 8;
            var defRing = new RoadPrimitive.Ring(
                    squarePos, defRingR, 1.5,
                    RoadShape.RoadTier.VILLAGE_PATH);
            pctx.layout.addRoad(defRing, pctx.level, pctx.worldSeed);

            new LayoutPrimitive.BuildingCircle(
                    squarePos,
                    LayoutPrimitive.BuildingCircle.Mode.TIGHT,
                    defensive,
                    null
            ).place(pctx);
        }

        // ── Outlying production stations ───────────────────────────────────
        // 1-2 outlying stations connected by long footpaths
        var production = pctx.claimByZone(BuildingZone.PRODUCTION, 1000);
        var residential = pctx.claimByZone(BuildingZone.RESIDENTIAL, 1000);

        int stationCount = Math.min(2, Math.max(1, production.size() / 3 + 1));
        List<List<VillageTypeData.StarterBuilding>> stationBuckets = new ArrayList<>();
        for (int i = 0; i < stationCount; i++) stationBuckets.add(new ArrayList<>());
        int b = 0;
        for (var sb : production) stationBuckets.get(b++ % stationCount).add(sb);
        // Residential goes to the first station (the "barracks side")
        if (!residential.isEmpty()) stationBuckets.get(0).addAll(residential);

        List<List<BlockPos>> allRoads = new ArrayList<>();
        double baseAngle = RecipeHelpers.directionRadOf(terrain.bestFlatDir());

        BlockPos mainGate = null;
        for (int i = 0; i < stationCount; i++) {
            var bucket = stationBuckets.get(i);
            if (bucket.isEmpty()) continue;
            // Stations on opposite sides of the core
            double angle = baseAngle + (i == 0 ? 0 : Math.PI);
            int stationDist = pctx.density.getRing2Radius() * 2 + 24;

            BlockPos pathStart = RecipeHelpers.offsetSnapped(pctx, squarePos, angle, civicRing + 8);
            BlockPos stationFocal = RecipeHelpers.offsetSnapped(pctx, squarePos, angle, stationDist);

            List<BlockPos> path = pctx.layout.addRoad(
                    new RoadPrimitive.StraightRoad(pathStart, stationFocal, 4.0,
                            RoadShape.RoadTier.FOOTPATH),
                    pctx.level, pctx.worldSeed);
            allRoads.add(path);
            RecipeHelpers.scatterBucketAtRoadEnd(pctx, path, bucket);

            if (i == 0) mainGate = stationFocal;
        }
        if (mainGate != null) pctx.layout.setMainGateEndpoint(mainGate);

        RecipeHelpers.rescueTownHallOnAnyRoad(pctx, allRoads);
        RecipeHelpers.placeAgriculturalRing(pctx, centre, 4, 20, allRoads);
        RecipeHelpers.placeStragglersRingBand(pctx, centre, allRoads);
    }
}