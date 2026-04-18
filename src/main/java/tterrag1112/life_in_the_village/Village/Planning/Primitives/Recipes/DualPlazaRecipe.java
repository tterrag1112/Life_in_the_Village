// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/DualPlazaRecipe.java
package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.*;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.List;

/**
 * DUAL_PLAZA — twin-square village.
 *
 * <p>Two town squares connected by a single main road. The "primary"
 * square gets the town hall and most civic buildings; the "secondary"
 * square gets a smaller civic cluster. Production and residential
 * spurs branch from points along the connecting road and from each
 * square.
 *
 * <p>Reads as: a merged hamlet, a twin village, two settlements that
 * grew together. The two-pole topology is unique among the recipes.
 */
public final class DualPlazaRecipe implements ShapeRecipe {

    @Override
    public void compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();
        int totalBuildings = pctx.remaining.size();

        double mainDirRad = RecipeHelpers.directionRadOf(terrain.bestFlatDir());
        // Distance between the two squares
        int separation = Math.max(48, pctx.density.getRing2Radius() * 2 + totalBuildings * 2);
        int half = separation / 2;

        BlockPos sq1 = RecipeHelpers.offsetSnapped(pctx, centre, mainDirRad, half);
        BlockPos sq2 = RecipeHelpers.offsetSnapped(pctx, centre, mainDirRad + Math.PI, half);

        // Primary square (sq1) gets more civic buildings
        int primaryCap = Math.max(2, Math.min(5, totalBuildings / 4));
        int secondaryCap = Math.max(1, primaryCap - 2);

        new LayoutPrimitive.TownSquare(sq1, primaryCap, null).place(pctx);
        BlockPos sq1Pos = pctx.layout.getTownSquarePos();
        int sq1Ring = pctx.layout.getCivicRingRadius();

        // Connecting road from sq1's far side to sq2's near side
        BlockPos roadStart = RecipeHelpers.offsetSnapped(pctx, sq1Pos, mainDirRad + Math.PI, sq1Ring);
        BlockPos roadEnd = RecipeHelpers.offsetSnapped(pctx, sq2, mainDirRad, sq1Ring);
        List<BlockPos> connector = pctx.layout.addRoad(
                new RoadPrimitive.StraightRoad(roadStart, roadEnd, 7.0,
                        RoadShape.RoadTier.VILLAGE_ROAD),
                pctx.level, pctx.worldSeed);

        // Now place sq2 — it'll set its own civicRingRadius, overwriting sq1's
        // value on the layout. We saved sq1Ring above for any further use.
        new LayoutPrimitive.TownSquare(sq2, secondaryCap, connector).place(pctx);

        // Outward gate roads from each square
        int gateLen = pctx.density.getRing2Radius() + 16;
        BlockPos gate1Start = RecipeHelpers.offsetSnapped(pctx, sq1Pos, mainDirRad, sq1Ring);
        BlockPos gate1End = RecipeHelpers.offsetSnapped(pctx, sq1Pos, mainDirRad, sq1Ring + gateLen);
        List<BlockPos> gate1 = pctx.layout.addRoad(
                new RoadPrimitive.StraightRoad(gate1Start, gate1End, 6.0,
                        RoadShape.RoadTier.VILLAGE_ROAD),
                pctx.level, pctx.worldSeed);

        BlockPos gate2Start = RecipeHelpers.offsetSnapped(pctx, sq2, mainDirRad + Math.PI, sq1Ring);
        BlockPos gate2End = RecipeHelpers.offsetSnapped(pctx, sq2, mainDirRad + Math.PI, sq1Ring + gateLen);
        List<BlockPos> gate2 = pctx.layout.addRoad(
                new RoadPrimitive.StraightRoad(gate2Start, gate2End, 6.0,
                        RoadShape.RoadTier.VILLAGE_ROAD),
                pctx.level, pctx.worldSeed);

        pctx.layout.setMainGateEndpoint(gate1End);
        pctx.layout.addGatePosition(gate1End);
        pctx.layout.addGatePosition(gate2End);

        List<List<BlockPos>> allRoads = new ArrayList<>();
        allRoads.add(connector);
        allRoads.add(gate1);
        allRoads.add(gate2);

        // ── Distribute prod+res across the connector and the two gates ─────
        var stubBuildings = RecipeHelpers.claimAndInterleaveProdResidential(pctx);

        // Split: 60% on connector, 20% each on gates
        int connectorCount = (int) Math.ceil(stubBuildings.size() * 0.6);
        int gate1Count = (stubBuildings.size() - connectorCount) / 2;

        List<VillageTypeData.StarterBuilding> connectorBucket =
                new ArrayList<>(stubBuildings.subList(0, Math.min(connectorCount, stubBuildings.size())));
        List<VillageTypeData.StarterBuilding> gate1Bucket =
                new ArrayList<>(stubBuildings.subList(
                        Math.min(connectorCount, stubBuildings.size()),
                        Math.min(connectorCount + gate1Count, stubBuildings.size())));
        List<VillageTypeData.StarterBuilding> gate2Bucket =
                new ArrayList<>(stubBuildings.subList(
                        Math.min(connectorCount + gate1Count, stubBuildings.size()),
                        stubBuildings.size()));

        allRoads.addAll(RecipeHelpers.stubSpursAlongRoad(
                pctx, connector, connectorBucket,
                RecipeHelpers.SidePolicy.ALTERNATING, 7, 5,
                RoadShape.RoadTier.FOOTPATH));
        allRoads.addAll(RecipeHelpers.stubSpursAlongRoad(
                pctx, gate1, gate1Bucket,
                RecipeHelpers.SidePolicy.ALTERNATING, 7, 5,
                RoadShape.RoadTier.FOOTPATH));
        allRoads.addAll(RecipeHelpers.stubSpursAlongRoad(
                pctx, gate2, gate2Bucket,
                RecipeHelpers.SidePolicy.ALTERNATING, 7, 5,
                RoadShape.RoadTier.FOOTPATH));

        RecipeHelpers.rescueTownHallOnAnyRoad(pctx, allRoads);
        RecipeHelpers.placeAgriculturalRing(pctx, centre, 8, 26, allRoads);
        RecipeHelpers.placeDefensiveRing(pctx, centre, 0, 12, allRoads);
        RecipeHelpers.placeStragglersRingBand(pctx, centre, allRoads);
    }
}