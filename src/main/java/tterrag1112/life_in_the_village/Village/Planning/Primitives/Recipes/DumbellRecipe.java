package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.LayoutPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.ShapeRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * DUMBELL layout — a civic ring (town square) and a production ring connected
 * by a single trunk road. The trunk orients along the flattest cardinal
 * direction. Residential buildings infill the trunk; agricultural and
 * defensive buildings sit in outer bands beyond the production ring.
 *
 * <p>No terrain guard — this layout spawns on any terrain.
 */
public final class DumbellRecipe implements ShapeRecipe {

    private static final Set<SlotTag> TAGS_RESIDENTIAL =
            EnumSet.of(SlotTag.RESIDENTIAL_INFILL, SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_PRODUCTION =
            EnumSet.of(SlotTag.PRODUCTION_CLUSTER, SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_SPUR_END =
            EnumSet.of(SlotTag.PRODUCTION_SPUR_END, SlotTag.ROAD_ADJACENT);

    @Override
    public void compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        double mainDirRad = RecipeHelpers.directionRadOf(
                pctx.layout.getTerrain().bestFlatDir());

        // ── Ring A: civic end (polygon plaza) ──────────────────────────────
        // Phase 18 doc 04 — polygon plaza handles all civic / layout setup.
        RecipeHelpers.installPlaza(pctx, centre,
                tterrag1112.life_in_the_village.Village.Decoration
                        .Plaza.PlazaShape.IRREGULAR);

        // Trunk starts at the outer edge of the civic ring road, not at
        // centre, so the trunk and ring road do not overlap.
        // Ring A: trunk starts at the ring road's outer edge, not the building placement radius
        int civicRing = pctx.layout.getCivicRingRadius();
        int ringRoadOuter = Math.max(9, civicRing - 3); // ring road is at (civicRing-6), halfwidth=3
        BlockPos trunkStart = pctx.solidSurface(new BlockPos(
                centre.getX() + (int) Math.round(Math.cos(mainDirRad) * ringRoadOuter),
                centre.getY(),
                centre.getZ() + (int) Math.round(Math.sin(mainDirRad) * ringRoadOuter)));

        int ringBRadius = Math.max(8, pctx.density.getRing1Radius() / 2 + 4);
        int armLength = pctx.density.getRing1Radius() + pctx.density.getRing2Radius();

// Ring B: trunk ends at ring B's PERIMETER, so the ring centre is armLength + ringBRadius
// from trunkStart along mainDirRad.
        BlockPos trunkEnd = pctx.solidSurface(new BlockPos(
                trunkStart.getX() + (int) Math.round(Math.cos(mainDirRad) * armLength),
                trunkStart.getY(),
                trunkStart.getZ() + (int) Math.round(Math.sin(mainDirRad) * armLength)));
        BlockPos ringBCentre = pctx.solidSurface(new BlockPos(
                trunkEnd.getX() + (int) Math.round(Math.cos(mainDirRad) * ringBRadius),
                trunkEnd.getY(),
                trunkEnd.getZ() + (int) Math.round(Math.sin(mainDirRad) * ringBRadius)));

        List<BlockPos> trunkCenterline = pctx.layout.addRoad(
                new RoadPrimitive.StraightRoad(
                        trunkStart, trunkEnd, 4.0, RoadShape.RoadTier.VILLAGE_PATH),
                pctx.level, pctx.worldSeed);

        pctx.offerRoadSlots(trunkCenterline, 7, 7, TAGS_RESIDENTIAL, 50);

        // ── Ring B: production end ─────────────────────────────────────────
        // Ring starts at its own centre — no overlap with trunk because
        // the trunk ends exactly at ringBCentre.
        List<BlockPos> ringBCenterline = pctx.layout.addRoad(
                new RoadPrimitive.Ring(
                        ringBCentre, ringBRadius, 2.0, RoadShape.RoadTier.VILLAGE_PATH),
                pctx.level, pctx.worldSeed);

        pctx.offerRoadSlots(ringBCenterline, 6, 7, TAGS_PRODUCTION, 60);

        // ── Production spurs off Ring B ────────────────────────────────────
        // Spurs branch from the ring perimeter (branchPointHint snaps to
        // the nearest centerline point), so they naturally start at the ring edge.
        List<List<BlockPos>> allSpurs = new ArrayList<>();
        double[] spurAngles = {
                mainDirRad,               // dead ahead
                mainDirRad + Math.PI / 2, // left flank
                mainDirRad - Math.PI / 2  // right flank
        };
        for (double angle : spurAngles) {
            BlockPos branchHint = new BlockPos(
                    ringBCentre.getX() + (int) Math.round(Math.cos(angle) * ringBRadius),
                    ringBCentre.getY(),
                    ringBCentre.getZ() + (int) Math.round(Math.sin(angle) * ringBRadius));
            int spurLength = 8 + pctx.rng.nextInt(6);
            List<BlockPos> spurLine = pctx.layout.addRoad(
                    new RoadPrimitive.Spur(
                            ringBCenterline, branchHint, angle,
                            spurLength, 2.0, RoadShape.RoadTier.FOOTPATH),
                    pctx.level, pctx.worldSeed);
            pctx.offerRoadSlots(spurLine, 5, 6, TAGS_SPUR_END, 55);
            allSpurs.add(spurLine);
        }

        // ── Collect all roads ──────────────────────────────────────────────
        List<List<BlockPos>> allRoads = new ArrayList<>();
        allRoads.add(trunkCenterline);
        allRoads.add(ringBCenterline);
        allRoads.addAll(allSpurs);

        // ── Outer rings ────────────────────────────────────────────────────
        RecipeHelpers.placeAgriculturalRing(pctx, ringBCentre, 4, 20, allRoads);
        RecipeHelpers.placeDefensiveRing(pctx, centre, 0, 12, allRoads);
        RecipeHelpers.placeStragglersRingBand(pctx, centre, allRoads);

        // ── Town hall rescue ───────────────────────────────────────────────
        RecipeHelpers.rescueTownHallOnAnyRoad(pctx, allRoads);
    }
}