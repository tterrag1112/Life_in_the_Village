// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/TerracedRecipe.java
package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.LayoutPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.ShapeRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.List;

/**
 * TERRACED — village built on a moderate slope with stepped rows.
 *
 * <p>Three or four parallel "terrace" roads run along contour lines
 * (perpendicular to the slope direction) at decreasing Y. Each terrace
 * is a {@link RoadPrimitive.StraightRoad} that surface-snaps to its
 * own elevation. Buildings sit in {@link LayoutPrimitive.LinearRow}
 * along each terrace. A short ramp road connects adjacent terraces
 * end-to-end so the village reads as a stepped path zig-zagging up
 * the slope.
 *
 * <p>Reads as: vineyard village, hill town, rice-paddy settlement,
 * Mediterranean coastal town. The slope IS the structure.
 *
 * <p>Falls back to LINEAR if the terrain has no significant slope
 * ({@link TerrainProfile#hasSlope()} returns false).
 */
public final class TerracedRecipe implements ShapeRecipe {

    /** Number of terrace levels. */
    private static final int TERRACE_COUNT = 4;
    /** XZ offset between adjacent terraces, in blocks. */
    private static final int TERRACE_XZ_STEP = 14;

    @Override
    public void compose(PlanContext pctx) {
        TerrainProfile terrain = pctx.layout.getTerrain();
        BlockPos centre = pctx.layout.getCenter();

        if (!terrain.hasSlope()) {
            System.out.println("TerracedRecipe: no significant slope detected "
                    + "— falling back to LINEAR");
            PlacementFailureRecorder
                    .record(PlacementFailureRecorder.Reason.TERRAIN_UNSUITABLE,
                            "no significant slope detected",
                            centre, VillageTypeData.ShapeType.TERRACED.name());
            new LinearRecipe().compose(pctx);
            return;
        }

        // Like HILLTOP, the validator's ridge rejection would refuse
        // most slope positions. Allow ridge placement so terraces can
        // actually exist on hilly ground.
        pctx.allowRidgePlacement = true;

        int totalBuildings = pctx.remaining.size();

        // ── Direction setup ────────────────────────────────────────────────
        // Slope direction is the cardinal in which Y drops most.
        // Terraces run PERPENDICULAR to slope (along contour lines).
        // Climbing direction = opposite of slope direction.
        TerrainAnalyzer.FlatDirection slopeDir = terrain.slopeDir();
        double slopeRad = RecipeHelpers.directionRadOf(slopeDir);
        double uphillRad = slopeRad + Math.PI;
        double terraceRad = slopeRad + Math.PI / 2;  // perpendicular = contour

        double slopeX = Math.cos(slopeRad);
        double slopeZ = Math.sin(slopeRad);
        double terraceX = Math.cos(terraceRad);
        double terraceZ = Math.sin(terraceRad);

        // ── Terrace lengths and counts ─────────────────────────────────────
        // Each terrace holds buildingsPerTerrace buildings on average.
        int buildingsPerTerrace = Math.max(2,
                (int) Math.ceil(totalBuildings / (double) TERRACE_COUNT));
        // Approximate row length: avg building width 12 + spacing 2 = 14
        int terraceLength = buildingsPerTerrace * 14 + 8;

        // ── Distribute buildings across terraces ───────────────────────────
        // Town hall + civic on the TOP terrace (most prestigious).
        // Production on middle terraces, residential on the bottom.
        // The top terrace is the smallest (fewest buildings) to make
        // the visual hierarchy obvious.
        var townHall = pctx.claimTownHall();
        var civic = pctx.claimByZone(
                tterrag1112.life_in_the_village.Village.Planning.BuildingZone.CIVIC, 1000);
        var production = pctx.claimByZone(
                tterrag1112.life_in_the_village.Village.Planning.BuildingZone.PRODUCTION, 1000);
        var residential = pctx.claimByZone(
                tterrag1112.life_in_the_village.Village.Planning.BuildingZone.RESIDENTIAL, 1000);

        List<List<VillageTypeData.StarterBuilding>> terraceBuckets = new ArrayList<>();
        for (int i = 0; i < TERRACE_COUNT; i++) terraceBuckets.add(new ArrayList<>());

        // Top terrace (index 0): town hall + all civic
        if (townHall != null) terraceBuckets.get(0).add(townHall);
        terraceBuckets.get(0).addAll(civic);

        // Distribute production across middle terraces (1 and 2)
        int midCount = Math.max(1, TERRACE_COUNT - 2);
        int b = 1;
        for (var sb : production) {
            terraceBuckets.get(1 + (b++ % midCount)).add(sb);
        }
        // Residential on the bottom terrace (highest index)
        terraceBuckets.get(TERRACE_COUNT - 1).addAll(residential);

        // ── Build terraces ─────────────────────────────────────────────────
        // Terrace i is at XZ offset (i * TERRACE_XZ_STEP) downhill from
        // the centre. The terrace road runs perpendicular (along the
        // contour) for terraceLength blocks. The starting end alternates
        // so adjacent terraces zig-zag rather than stacking on top of
        // each other in plan view.
        List<List<BlockPos>> allRoads = new ArrayList<>();
        BlockPos topCentre = RecipeHelpers.offsetSnapped(
                pctx, centre, uphillRad, TERRACE_XZ_STEP * (TERRACE_COUNT / 2));

        BlockPos prevTerraceEnd = null;
        BlockPos mainGate = null;
        for (int i = 0; i < TERRACE_COUNT; i++) {
            BlockPos terraceCentre = RecipeHelpers.offsetSnapped(
                    pctx, topCentre, slopeRad, i * TERRACE_XZ_STEP);

            // Alternating starting side: even terraces start on the
            // "left" end (negative terrace direction), odd on the right
            boolean rightSide = (i & 1) == 1;
            int dirSign = rightSide ? -1 : 1;

            BlockPos terraceStart = pctx.solidSurface(new BlockPos(
                    terraceCentre.getX() - (int) Math.round(terraceX * (terraceLength / 2.0) * dirSign),
                    terraceCentre.getY(),
                    terraceCentre.getZ() - (int) Math.round(terraceZ * (terraceLength / 2.0) * dirSign)));
            BlockPos terraceEnd = pctx.solidSurface(new BlockPos(
                    terraceCentre.getX() + (int) Math.round(terraceX * (terraceLength / 2.0) * dirSign),
                    terraceCentre.getY(),
                    terraceCentre.getZ() + (int) Math.round(terraceZ * (terraceLength / 2.0) * dirSign)));

            // The terrace road. Drift kept low so the row stays readable.
            List<BlockPos> terraceRoad = pctx.layout.addRoad(
                    new RoadPrimitive.StraightRoad(terraceStart, terraceEnd, 3.0,
                            i == 0 ? RoadShape.RoadTier.VILLAGE_ROAD
                                    : RoadShape.RoadTier.VILLAGE_PATH),
                    pctx.level, pctx.worldSeed);
            allRoads.add(terraceRoad);

            // Connector ramp from previous terrace's end to this one's start.
            // Surface-snap on every centerline point makes it climb the slope.
            if (prevTerraceEnd != null) {
                List<BlockPos> ramp = pctx.layout.addRoad(
                        new RoadPrimitive.StraightRoad(prevTerraceEnd, terraceStart, 2.0,
                                RoadShape.RoadTier.FOOTPATH),
                        pctx.level, pctx.worldSeed);
                allRoads.add(ramp);
            }
            prevTerraceEnd = terraceEnd;

            // ── Place buildings as a LinearRow along the terrace ───────────
            var bucket = terraceBuckets.get(i);
            if (!bucket.isEmpty()) {
                // Row heading = terrace road heading (with sign for alternation)
                double rowHeading = terraceRad;
                if (rightSide) rowHeading += Math.PI;
                // Buildings face DOWNHILL (toward slope direction) so the
                // row looks out over the lower terraces
                int facingSide = facingSideForDownhill(rowHeading, slopeRad);

                // Start the row a few blocks in from the terrace start
                BlockPos rowStart = pctx.solidSurface(new BlockPos(
                        terraceStart.getX() + (int) Math.round(Math.cos(rowHeading) * 4),
                        terraceStart.getY(),
                        terraceStart.getZ() + (int) Math.round(Math.sin(rowHeading) * 4)));

                new LayoutPrimitive.LinearRow(
                        rowStart, rowHeading, facingSide,
                        2,                  // 2 blocks spacing between buildings
                        bucket,
                        terraceRoad
                ).place(pctx);
            }

            if (i == TERRACE_COUNT - 1) mainGate = terraceEnd;
        }

        // Mark the layout
        pctx.layout.setTownSquarePos(topCentre);
        pctx.layout.setTownSquareRadius(4);
        pctx.layout.setMainGateEndpoint(mainGate);
        pctx.layout.addGatePosition(mainGate);

        // ── Agricultural at the very bottom of the slope ───────────────────
        // Farms make sense on the flat below the village
        BlockPos farmCentre = RecipeHelpers.offsetSnapped(
                pctx, prevTerraceEnd != null ? prevTerraceEnd : centre,
                slopeRad, pctx.density.getRing1Radius() + 8);
        var agri = pctx.claimByZone(
                tterrag1112.life_in_the_village.Village.Planning.BuildingZone.AGRICULTURAL, 1000);
        if (!agri.isEmpty()) {
            RecipeHelpers.scatterBucketAt(pctx, farmCentre, agri,
                    allRoads.get(allRoads.size() - 1));
        }

        // ── Defensive: split between top terrace (lookout) and bottom (gate) ──
        var defensive = pctx.claimByZone(
                tterrag1112.life_in_the_village.Village.Planning.BuildingZone.DEFENSIVE, 1000);
        if (!defensive.isEmpty()) {
            int half = (defensive.size() + 1) / 2;
            // Top terrace lookouts
            RecipeHelpers.scatterBucketAt(pctx, topCentre,
                    defensive.subList(0, half), allRoads.get(0));
            if (half < defensive.size()) {
                // Bottom terrace gate guards
                RecipeHelpers.scatterBucketAt(pctx,
                        prevTerraceEnd != null ? prevTerraceEnd : centre,
                        defensive.subList(half, defensive.size()),
                        allRoads.get(allRoads.size() - 1));
            }
        }

        RecipeHelpers.placeStragglersRingBand(pctx, centre, allRoads);
    }

    /**
     * Computes the facing-side parameter for {@link LayoutPrimitive.LinearRow}
     * such that buildings face the downhill direction.
     */
    private static int facingSideForDownhill(double rowHeading, double slopeRad) {
        // The row's "left side" (relative to heading) is at angle rowHeading + π/2.
        // We want buildings to face slopeRad. If left side dot slopeRad > 0,
        // facingSide = +1, else -1.
        double leftX = -Math.sin(rowHeading);
        double leftZ = Math.cos(rowHeading);
        double slopeX = Math.cos(slopeRad);
        double slopeZ = Math.sin(slopeRad);
        return (leftX * slopeX + leftZ * slopeZ) > 0 ? 1 : -1;
    }
}