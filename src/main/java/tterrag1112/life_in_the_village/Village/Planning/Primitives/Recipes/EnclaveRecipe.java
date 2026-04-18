// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/EnclaveRecipe.java
package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.LayoutSlot;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.*;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.List;

/**
 * ENCLAVE — fortified compound with buildings forming the outer wall.
 *
 * <p>Defensive and residential buildings are arranged in four
 * {@link LayoutPrimitive.LinearRow} segments forming a square-ish
 * perimeter. Each row faces inward toward the courtyard. Civic
 * buildings cluster inside the courtyard around the town hall. A
 * single gate gap on one side connects the courtyard to the outside
 * world via a short main road.
 *
 * <p>This is a "best effort" — rectangular NBT structures don't tile
 * perfectly into a closed wall, so the result reads as "compound-ish"
 * rather than a proper fortification. Adjacent buildings may have
 * gaps or near-misses at corners. The visual is still distinct from
 * everything else in the catalog.
 */
public final class EnclaveRecipe implements ShapeRecipe {

    /** Wall length per side, in blocks. Sized to fit a few buildings. */
    private static final int WALL_LENGTH = 56;
    /** Half-width of the courtyard interior. */
    private static final int COURTYARD_HALF = WALL_LENGTH / 2 - 4;

    @Override
    public void compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();
        int totalBuildings = pctx.remaining.size();

        // ── Reserve the courtyard interior ─────────────────────────────────
        // The whole inside is a DECORATION slot so spurs and rings don't
        // intrude. Civic buildings get explicit slots inside via direct
        // tryCommitWithRetries calls.
        pctx.layout.addForced(new LayoutSlot(
                LayoutSlot.SlotType.DECORATION, centre, COURTYARD_HALF + 4));
        pctx.layout.setTownSquarePos(centre);
        pctx.layout.setTownSquareRadius(COURTYARD_HALF);
        pctx.layout.setCivicRingRadius(COURTYARD_HALF + 8);

        // ── Pick gate side ─────────────────────────────────────────────────
        // Gate faces the bestFlatDir so trade routes have somewhere flat
        // to attach. The wall on the gate side gets a gap.
        double gateRad = RecipeHelpers.directionRadOf(terrain.bestFlatDir());
        // Quantize gateRad to the nearest cardinal (north/south/east/west)
        // because the four wall segments are axis-aligned
        int gateSide = nearestCardinalIndex(gateRad);
        // 0=east, 1=south, 2=west, 3=north (matching standard math angles)

        // ── Distribute buildings ───────────────────────────────────────────
        // Wall buildings: defensive first, then residential
        // Courtyard buildings: town hall + all civic
        var townHall = pctx.claimTownHall();
        var civic = pctx.claimByZone(BuildingZone.CIVIC, 1000);
        var defensive = pctx.claimByZone(BuildingZone.DEFENSIVE, 1000);
        var residential = pctx.claimByZone(BuildingZone.RESIDENTIAL, 1000);
        var production = pctx.claimByZone(BuildingZone.PRODUCTION, 1000);

        List<VillageTypeData.StarterBuilding> wallBuildings = new ArrayList<>();
        wallBuildings.addAll(defensive);
        wallBuildings.addAll(residential);
        // Production goes on the wall too if there's room
        wallBuildings.addAll(production);

        // ── Place wall segments ────────────────────────────────────────────
        // Four sides: east, south, west, north. Each gets a quarter of
        // wall buildings (skipping gateSide which gets fewer to leave a gap).
        List<List<VillageTypeData.StarterBuilding>> sideBuckets = new ArrayList<>();
        for (int i = 0; i < 4; i++) sideBuckets.add(new ArrayList<>());

        // Round-robin distribution, skipping the gate side once per round
        // (so the gate side ends up with ~75% of a normal side's buildings)
        int idx = 0;
        int round = 0;
        for (var sb : wallBuildings) {
            int side = idx % 4;
            // Skip gate side every 4th round to thin it out
            if (side == gateSide && round % 4 == 0) {
                idx++;
                side = idx % 4;
            }
            sideBuckets.get(side).add(sb);
            idx++;
            if (side == 3) round++;
        }

        // Wall starts and headings (math convention angles)
        // East wall: starts at SE corner, runs north (heading = -π/2)
        // South wall: starts at SW corner, runs east (heading = 0)
        // West wall: starts at NW corner, runs south (heading = π/2)
        // North wall: starts at NE corner, runs west (heading = π)
        int half = WALL_LENGTH / 2;
        BlockPos seCorner = pctx.solidSurface(centre.offset(half, 0, half));
        BlockPos swCorner = pctx.solidSurface(centre.offset(-half, 0, half));
        BlockPos nwCorner = pctx.solidSurface(centre.offset(-half, 0, -half));
        BlockPos neCorner = pctx.solidSurface(centre.offset(half, 0, -half));

        BlockPos[] wallStarts = {seCorner, swCorner, nwCorner, neCorner};
        double[] wallHeadings = {-Math.PI / 2, 0, Math.PI / 2, Math.PI};
        // Each wall's facing-side parameter for LinearRow such that
        // buildings face TOWARD the courtyard centre
        int[] wallFacings = {1, 1, 1, 1};
        // Validate: for east wall heading -π/2 (north), the "left side"
        // is heading + π/2 = 0 (east) which faces away from centre.
        // So east wall needs facingSide = -1 to face inward. Same logic
        // applies to all four walls — they all need -1 with current heading
        // conventions.
        // Actually: LinearRow's facingSide parameter sets the perpendicular
        // direction the buildings face. heading=0 + facingSide=+1 → north.
        // For east wall, heading=-π/2, facingSide=+1 → east. We want west
        // (toward centre), so facingSide=-1.
        // Conclusion: all four walls use facingSide = -1.
        for (int i = 0; i < 4; i++) wallFacings[i] = -1;

        List<List<BlockPos>> allRoads = new ArrayList<>();

        for (int side = 0; side < 4; side++) {
            var bucket = sideBuckets.get(side);
            if (bucket.isEmpty()) continue;

            // For the gate side, place fewer buildings and leave the
            // middle of the wall empty. Easiest implementation: split
            // the bucket and place each half offset from the wall start.
            if (side == gateSide && bucket.size() > 1) {
                int halfCount = bucket.size() / 2;
                List<VillageTypeData.StarterBuilding> firstHalf =
                        new ArrayList<>(bucket.subList(0, halfCount));
                List<VillageTypeData.StarterBuilding> secondHalf =
                        new ArrayList<>(bucket.subList(halfCount, bucket.size()));

                new LayoutPrimitive.LinearRow(
                        wallStarts[side], wallHeadings[side], wallFacings[side],
                        2, firstHalf, null
                ).place(pctx);

                // Second half starts past the gate gap (~16 blocks in)
                BlockPos gapStart = pctx.solidSurface(new BlockPos(
                        wallStarts[side].getX()
                                + (int) Math.round(Math.cos(wallHeadings[side]) * (WALL_LENGTH * 0.6)),
                        wallStarts[side].getY(),
                        wallStarts[side].getZ()
                                + (int) Math.round(Math.sin(wallHeadings[side]) * (WALL_LENGTH * 0.6))));
                new LayoutPrimitive.LinearRow(
                        gapStart, wallHeadings[side], wallFacings[side],
                        2, secondHalf, null
                ).place(pctx);
            } else {
                new LayoutPrimitive.LinearRow(
                        wallStarts[side], wallHeadings[side], wallFacings[side],
                        2, bucket, null
                ).place(pctx);
            }
        }

        // ── Courtyard interior: town hall + civic ──────────────────────────
        // Town hall at the centre, civic clustered loosely around it.
        if (townHall != null) {
            BuildingType bt = PlanContext.parseType(townHall);
            if (bt != null) {
                LayoutSlot peakSlot = pctx.tryCommitWithRetries(centre, townHall, bt, null, 12);
                if (peakSlot == null) {
                    pctx.remaining.add(townHall);
                }
            }
        }
        if (!civic.isEmpty()) {
            // Place civic in a small TIGHT circle around the centre
            new LayoutPrimitive.BuildingCircle(
                    centre,
                    LayoutPrimitive.BuildingCircle.Mode.TIGHT,
                    civic,
                    null
            ).place(pctx);
        }

        // ── Gate road from outside the wall to the courtyard centre ───────
        BlockPos gateOutside = RecipeHelpers.offsetSnapped(
                pctx, centre, gateRad, half + pctx.density.getRing1Radius() + 8);
        BlockPos gateInside = RecipeHelpers.offsetSnapped(pctx, centre, gateRad, half - 2);
        List<BlockPos> gateRoad = pctx.layout.addRoad(
                new RoadPrimitive.StraightRoad(gateInside, gateOutside, 5.0,
                        RoadShape.RoadTier.VILLAGE_ROAD),
                pctx.level, pctx.worldSeed);
        allRoads.add(gateRoad);
        pctx.layout.setMainGateEndpoint(gateOutside);
        pctx.layout.addGatePosition(gateOutside);

        // ── Agricultural OUTSIDE the wall (only on the gate side) ──────────
        var agri = pctx.claimByZone(BuildingZone.AGRICULTURAL, 1000);
        if (!agri.isEmpty()) {
            BlockPos farmCentre = RecipeHelpers.offsetSnapped(
                    pctx, gateOutside, gateRad, pctx.density.getRing1Radius());
            RecipeHelpers.scatterBucketAt(pctx, farmCentre, agri, gateRoad);
        }

        // ── Stragglers fall back to the gate road area ─────────────────────
        if (!pctx.remaining.isEmpty()) {
            // Don't ring-band stragglers because the wall would block them.
            // Place them outside the gate as a SCATTER instead.
            BlockPos overflowCentre = RecipeHelpers.offsetSnapped(
                    pctx, gateOutside, gateRad, pctx.density.getRing1Radius() + 16);
            List<VillageTypeData.StarterBuilding> leftovers =
                    new ArrayList<>(pctx.remaining);
            pctx.remaining.clear();
            new LayoutPrimitive.BuildingCircle(
                    overflowCentre,
                    LayoutPrimitive.BuildingCircle.Mode.SCATTER,
                    leftovers,
                    gateRoad
            ).place(pctx);
        }
    }

    /**
     * Quantizes a math-convention angle to the nearest cardinal index:
     * 0=east, 1=south, 2=west, 3=north.
     */
    private static int nearestCardinalIndex(double rad) {
        // Normalize to [0, 2π)
        double r = rad % (2 * Math.PI);
        if (r < 0) r += 2 * Math.PI;
        // East = 0, south = π/2, west = π, north = 3π/2
        int q = (int) Math.round(r / (Math.PI / 2)) % 4;
        return q;
    }
}