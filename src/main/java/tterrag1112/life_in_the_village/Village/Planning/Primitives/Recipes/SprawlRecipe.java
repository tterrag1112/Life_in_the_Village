// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/SprawlRecipe.java
package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.ShapeRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * SPRAWL — sparse, low-density village.
 *
 * <p>One very long main road with few spurs and buildings spread far
 * apart. No civic ring — the town hall just sits along the main road
 * like any other building. Long stub spurs with extra spacing so the
 * village feels stretched thin.
 *
 * <p>Reads as: a poor frontier hamlet, a settlement losing population,
 * a sparse trail-side community where each homestead is its own world.
 */
public final class SprawlRecipe implements ShapeRecipe {

    @Override
    public void compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();
        int totalBuildings = pctx.remaining.size();

        // Very long main road — much longer than LINEAR per building
        double mainDirRad = RecipeHelpers.directionRadOf(terrain.bestFlatDir());
        int halfLength = Math.max(80, (totalBuildings * 16) + pctx.density.getRing2Radius());

        BlockPos mainStart = RecipeHelpers.offsetSnapped(pctx, centre, mainDirRad, halfLength);
        BlockPos mainEnd = RecipeHelpers.offsetSnapped(pctx, centre, mainDirRad + Math.PI, halfLength);

        List<BlockPos> mainCenterline = pctx.layout.addRoad(
                new RoadPrimitive.StraightRoad(mainStart, mainEnd, 10.0,
                        RoadShape.RoadTier.VILLAGE_ROAD),
                pctx.level, pctx.worldSeed);
        pctx.layout.setMainGateEndpoint(mainEnd);
        // No town square call — SPRAWL has no central plaza.
        // Mark a nominal centre for downstream systems.
        pctx.layout.setTownSquarePos(centre);
        pctx.layout.setTownSquareRadius(3);

        List<List<BlockPos>> allRoads = new ArrayList<>();
        allRoads.add(mainCenterline);

        // Claim civic + town hall and add them to the interleave queue
        // so they sit along the road like everything else
        var townHall = pctx.claimTownHall();
        var civic = pctx.claimByZone(
                tterrag1112.life_in_the_village.Village.Planning.BuildingZone.CIVIC, 1000);
        var stubBuildings = RecipeHelpers.claimAndInterleaveProdResidential(pctx);
        if (townHall != null) stubBuildings.add(0, townHall);
        stubBuildings.addAll(1, civic);

        // Long stubs (10-16 blocks) so neighbours feel distant
        RecipeHelpers.SidePolicy policy = RecipeHelpers.SidePolicy.ALTERNATING;
        List<List<BlockPos>> stubs = RecipeHelpers.stubSpursAlongRoad(
                pctx, mainCenterline, stubBuildings, policy,
                10, 7, RoadShape.RoadTier.FOOTPATH);
        allRoads.addAll(stubs);

        RecipeHelpers.placeAgriculturalRing(pctx, centre, 8, 28, allRoads);
        RecipeHelpers.placeDefensiveRing(pctx, centre, 0, 12, allRoads);
        RecipeHelpers.placeStragglersRingBand(pctx, centre, allRoads);
    }
}