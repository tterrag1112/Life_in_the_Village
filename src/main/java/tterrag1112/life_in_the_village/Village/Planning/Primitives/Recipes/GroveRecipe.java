// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/GroveRecipe.java
package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.LayoutSlot;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.*;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.List;

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
 */
public final class GroveRecipe implements ShapeRecipe {

    /** Multiplier on the civic ring radius vs. RADIAL's default. */
    private static final double GROVE_RING_MULTIPLIER = 1.6;

    @Override
    public void compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        int totalBuildings = pctx.remaining.size();

        // ── Wide civic ring with high capacity ─────────────────────────────
        // Higher capacity → larger ring (arc-length scaling). The grove
        // center is bigger than RADIAL's by design.
        int civicCap = Math.max(4, Math.min(8, totalBuildings / 3));
        new LayoutPrimitive.TownSquare(centre, civicCap, null).place(pctx);
        BlockPos squarePos = pctx.layout.getTownSquarePos();
        int civicRing = pctx.layout.getCivicRingRadius();

        // Override the layout's civic ring to push buildings even further
        // out. The TownSquare primitive set it; we widen it after.
        int wideRing = (int)(civicRing * GROVE_RING_MULTIPLIER);
        pctx.layout.setCivicRingRadius(wideRing);

        // Place a large DECORATION slot covering the entire wide ring,
        // so spur buildings can't intrude into the sacred area.
        pctx.layout.addForced(new LayoutSlot(
                LayoutSlot.SlotType.DECORATION, squarePos, wideRing - 4));

        // ── Spurs outward at evenly spaced angles ──────────────────────────
        int spurCount = Math.max(3, Math.min(6, totalBuildings / 4));
        List<List<BlockPos>> allRoads = new ArrayList<>();
        List<List<VillageTypeData.StarterBuilding>> buckets =
                RecipeHelpers.claimAndBucketProdResidential(pctx, spurCount);

        double baseAngle = pctx.rng.nextDouble() * 2 * Math.PI;
        double angleStep = 2 * Math.PI / spurCount;

        BlockPos mainGate = null;
        for (int i = 0; i < spurCount; i++) {
            var bucket = buckets.get(i);
            if (bucket.isEmpty()) continue;
            double spurAngle = baseAngle + angleStep * i;
            BlockPos branchHint = RecipeHelpers.offsetSnapped(pctx, squarePos, spurAngle, wideRing + 2);

            // Synthetic parent so the spur snaps cleanly
            List<BlockPos> synthParent = List.of(squarePos, branchHint);
            int spurLen = pctx.density.getRing2Radius() + 8;
            RoadPrimitive.Spur spur = new RoadPrimitive.Spur(
                    synthParent, branchHint, spurAngle, spurLen,
                    5.0, RoadShape.RoadTier.VILLAGE_PATH);
            List<BlockPos> spurLine = pctx.layout.addRoad(spur, pctx.level, pctx.worldSeed);
            allRoads.add(spurLine);

            RecipeHelpers.scatterBucketAtRoadEnd(pctx, spurLine, bucket);
            if (i == 0 && !spurLine.isEmpty()) {
                mainGate = spurLine.get(spurLine.size() - 1);
            }
        }
        if (mainGate != null) pctx.layout.setMainGateEndpoint(mainGate);

        RecipeHelpers.rescueTownHallOnAnyRoad(pctx, allRoads);
        RecipeHelpers.placeAgriculturalRing(pctx, centre, 8, 28, allRoads);
        RecipeHelpers.placeDefensiveRing(pctx, centre, 4, 14, allRoads);
        RecipeHelpers.placeStragglersRingBand(pctx, centre, allRoads);
    }
}