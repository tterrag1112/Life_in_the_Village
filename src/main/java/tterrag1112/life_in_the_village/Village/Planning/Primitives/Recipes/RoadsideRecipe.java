// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/RoadsideRecipe.java
package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.LayoutPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.ShapeRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Roads.Planning.GatewayDescriptor;
import tterrag1112.life_in_the_village.Village.Roads.Planning.VillageEdgeDescriptor;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadEdge;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.List;

/**
 * ROADSIDE — LINEAR with one-side bias.
 *
 * <p>All buildings sit on one side of the main road. The other side
 * is open — meant to face an edge feature like water, a cliff, or
 * dense forest. The bias direction is chosen by terrain: if water is
 * present, buildings sit AWAY from it; if ridges, away from them;
 * otherwise an arbitrary side.
 *
 * <p>Reads as: a frontier highway hamlet, a coach-stop village, a
 * settlement built up against a natural feature it doesn't engage with.
 */
public final class RoadsideRecipe implements ShapeRecipe {

    @Override
    public void compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();
        int totalBuildings = pctx.remaining.size();

        // ── Main road heading: aligned with bestFlatDir ────────────────────
        double mainDirRad = RecipeHelpers.directionRadOf(terrain.bestFlatDir());
        double tanX = Math.cos(mainDirRad), tanZ = Math.sin(mainDirRad);

        int halfLength = Math.max(60, (totalBuildings * 10) + pctx.density.getRing2Radius());

        BlockPos mainStart = pctx.solidSurface(new BlockPos(
                centre.getX() + (int) Math.round(tanX * halfLength),
                centre.getY(),
                centre.getZ() + (int) Math.round(tanZ * halfLength)));
        BlockPos mainEnd = pctx.solidSurface(new BlockPos(
                centre.getX() - (int) Math.round(tanX * halfLength),
                centre.getY(),
                centre.getZ() - (int) Math.round(tanZ * halfLength)));

        List<BlockPos> mainCenterline = pctx.layout.addRoad(
                new RoadPrimitive.StraightRoad(mainStart, mainEnd, 8.0,
                        RoadShape.RoadTier.VILLAGE_ROAD),
                pctx.level, pctx.worldSeed);
        pctx.layout.setMainGateEndpoint(mainEnd);
        pctx.layout.addGatePosition(mainStart);
        pctx.layout.addGatePosition(mainEnd);

        // ── Determine which side is "away from feature" ────────────────────
        // Default to left. If water or a ridge is on the left side
        // (perpendicular to the road), flip to right.
        double leftPerpX = -tanZ, leftPerpZ = tanX;
        boolean buildLeft = true;
        if (terrain.hasWater()) {
            BlockPos water = terrain.waterBody().centre();
            double waterDx = water.getX() - centre.getX();
            double waterDz = water.getZ() - centre.getZ();
            // Dot product: positive means water is on the left
            if (waterDx * leftPerpX + waterDz * leftPerpZ > 0) buildLeft = false;
        } else if (terrain.hasRidges()) {
            var ridge = terrain.ridges().get(0);
            int rcx = (ridge.min().getX() + ridge.max().getX()) / 2;
            int rcz = (ridge.min().getZ() + ridge.max().getZ()) / 2;
            double rDx = rcx - centre.getX();
            double rDz = rcz - centre.getZ();
            if (rDx * leftPerpX + rDz * leftPerpZ > 0) buildLeft = false;
        }

        // ── Town square at midpoint ────────────────────────────────────────
        BlockPos squareMid = mainCenterline.get(mainCenterline.size() / 2);
        int civicCap = Math.max(2, Math.min(4, totalBuildings / 6 + 1));
        new LayoutPrimitive.TownSquare(squareMid, civicCap, mainCenterline).place(pctx);
        // Phase 17 doc 04 — LINEAR plaza along the road.
        double midTangentRad = RecipeHelpers.localTangentRad(
                mainCenterline, mainCenterline.size() / 2);
        RecipeHelpers.installLinearPlaza(pctx, pctx.layout.getTownSquarePos(),
                tterrag1112.life_in_the_village.Village.Decoration
                        .Plaza.PlazaPurpose.CIVIC,
                RecipeHelpers.cardinalFromRad(midTangentRad));

        List<List<BlockPos>> allRoads = new ArrayList<>();
        allRoads.add(mainCenterline);

        // ── Stub spurs all on one side ─────────────────────────────────────
        List<VillageTypeData.StarterBuilding> stubBuildings =
                RecipeHelpers.claimAndInterleaveProdResidential(pctx);
        RecipeHelpers.SidePolicy policy = buildLeft
                ? RecipeHelpers.SidePolicy.ALWAYS_LEFT
                : RecipeHelpers.SidePolicy.ALWAYS_RIGHT;
        List<List<BlockPos>> stubs = RecipeHelpers.stubSpursAlongRoad(
                pctx, mainCenterline, stubBuildings, policy,
                7, 5, RoadShape.RoadTier.FOOTPATH);
        allRoads.addAll(stubs);

        RecipeHelpers.rescueTownHallOnRoad(pctx, mainCenterline);

        // ── Agricultural at the road ends, defensive on perimeter ──────────
        RecipeHelpers.placeAgriculturalRing(pctx, centre, 6, 24, allRoads);
        RecipeHelpers.placeDefensiveRing(pctx, centre, 0, 12, allRoads);
        RecipeHelpers.placeStragglersRingBand(pctx, centre, allRoads);
    }

    /** Two gateways at the road's two terminal ends (designed for through-road traffic). */
    @Override
    public List<GatewayDescriptor> describeGateways(PlanContext pctx) {
        return GatewayDescriptor.deriveFromLayout(pctx);
    }

    /** Single THROUGH_VILLAGE edge along the main road, connecting the two gateways. */
    @Override
    public List<VillageEdgeDescriptor> describeInternalRoads(PlanContext pctx) {
        return LinearRecipe.deriveThruRoad(pctx);
    }
}