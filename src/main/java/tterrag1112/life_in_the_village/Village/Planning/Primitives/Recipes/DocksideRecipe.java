// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/DocksideRecipe.java
package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.*;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.List;

/**
 * DOCKSIDE — pier village running into the water.
 *
 * <p>Like RIVERINE but the main road runs PERPENDICULAR to the shore,
 * heading toward the water and terminating at the shore as a "pier."
 * Buildings line both sides of the road on the inland portion. The
 * gate endpoint is the shore terminus, so trade routes attach there
 * (representing arriving boats rather than overland traffic).
 *
 * <p>Falls back to LINEAR if no water is present.
 */
public final class DocksideRecipe implements ShapeRecipe {

    @Override
    public void compose(PlanContext pctx) {
        TerrainProfile terrain = pctx.layout.getTerrain();
        BlockPos origin = pctx.layout.getCenter();

        if (!terrain.hasWater()) {
            System.out.println("DocksideRecipe: no water — falling back to LINEAR");
            PlacementFailureRecorder
                    .record(PlacementFailureRecorder.Reason.SHAPE_RULE_REJECTED,
                            "no water",
                            origin, VillageTypeData.ShapeType.DOCKSIDE.name());
            new LinearRecipe().compose(pctx);
            return;
        }

        pctx.rejectWaterForAll = true;
        TerrainAnalyzer.WaterBodyInfo water = terrain.waterBody();
        BlockPos shore = water.nearestShore();
        int totalBuildings = pctx.remaining.size();

        // ── Direction: from inland TOWARD the shore ────────────────────────
        double waterDx = shore.getX() - origin.getX();
        double waterDz = shore.getZ() - origin.getZ();
        double waterLen = Math.sqrt(waterDx * waterDx + waterDz * waterDz);
        if (waterLen < 1) {
            new LinearRecipe().compose(pctx);
            PlacementFailureRecorder
                    .record(PlacementFailureRecorder.Reason.SHAPE_RULE_REJECTED,
                            "not enough water",
                            origin, VillageTypeData.ShapeType.DOCKSIDE.name());

            return;
        }
        double towardWaterRad = Math.atan2(waterDz / waterLen, waterDx / waterLen);
        double inlandRad = towardWaterRad + Math.PI;

        // Main road runs from inland anchor TO the shore
        int inlandLength = pctx.density.getRing2Radius() * 2 + totalBuildings * 5;
        BlockPos inlandEnd = RecipeHelpers.offsetSnapped(pctx, shore, inlandRad, inlandLength);
        // Shore terminus pulled back 4 blocks so it doesn't end IN the water
        BlockPos shoreEnd = RecipeHelpers.offsetSnapped(pctx, shore, inlandRad, 4);

        List<BlockPos> mainCenterline = pctx.layout.addRoad(
                new RoadPrimitive.StraightRoad(inlandEnd, shoreEnd, 6.0,
                        RoadShape.RoadTier.VILLAGE_ROAD),
                pctx.level, pctx.worldSeed);
        // Gate endpoint = shore (boats arrive there)
        pctx.layout.setMainGateEndpoint(shoreEnd);
        pctx.layout.addGatePosition(inlandEnd);
        pctx.layout.addGatePosition(shoreEnd);

        // Town square ~1/3 of the way from inland end toward the shore
        BlockPos squarePos = mainCenterline.get(mainCenterline.size() / 3);
        int civicCap = Math.max(2, Math.min(4, totalBuildings / 6 + 1));
        new LayoutPrimitive.TownSquare(squarePos, civicCap, mainCenterline).place(pctx);
        // Phase 17 doc 04 — LINEAR plaza along the shore (perpendicular
        // to the inland-shore main road).
        RecipeHelpers.installLinearPlaza(pctx, pctx.layout.getTownSquarePos(),
                tterrag1112.life_in_the_village.Village.Decoration
                        .Plaza.PlazaPurpose.CIVIC,
                RecipeHelpers.cardinalFromRad(inlandRad + Math.PI / 2));

        List<List<BlockPos>> allRoads = new ArrayList<>();
        allRoads.add(mainCenterline);

        // Stubs on both sides of the main road
        List<VillageTypeData.StarterBuilding> stubBuildings =
                RecipeHelpers.claimAndInterleaveProdResidential(pctx);
        List<List<BlockPos>> stubs = RecipeHelpers.stubSpursAlongRoad(
                pctx, mainCenterline, stubBuildings,
                RecipeHelpers.SidePolicy.ALTERNATING,
                7, 5, RoadShape.RoadTier.FOOTPATH);
        allRoads.addAll(stubs);

        RecipeHelpers.rescueTownHallOnRoad(pctx, mainCenterline);

        // Agricultural inland (away from water) only
        BlockPos agriCentre = RecipeHelpers.offsetSnapped(
                pctx, inlandEnd, inlandRad, pctx.density.getRing2Radius());
        var agri = pctx.claimByZone(
                tterrag1112.life_in_the_village.Village.Planning.BuildingZone.AGRICULTURAL, 1000);
        if (!agri.isEmpty()) {
            RecipeHelpers.scatterBucketAt(pctx, agriCentre, agri, mainCenterline);
        }

        RecipeHelpers.placeDefensiveRing(pctx, inlandEnd, 0, 12, allRoads);
        RecipeHelpers.placeStragglersRingBand(pctx, inlandEnd, allRoads);
    }
}