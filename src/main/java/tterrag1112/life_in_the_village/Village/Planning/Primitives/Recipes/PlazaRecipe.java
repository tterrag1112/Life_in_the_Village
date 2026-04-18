// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/PlazaRecipe.java
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
 * PLAZA layout recipe.
 *
 * <p>An urban village built around a large central plaza. Civic
 * buildings form a tight ring directly tangent to the plaza, then
 * four short spurs head outward at evenly-spaced cardinals to
 * production and residential clusters. Agricultural ring sits
 * outside everything as the urban edge.
 *
 * <p>The plaza is intentionally larger than the RADIAL one — visiting
 * one should feel like arriving in a town centre, not stumbling onto
 * a paved 3x3.
 */
public final class PlazaRecipe implements ShapeRecipe {

    /**
     * Plaza paved radius. Hard-coded larger than the default
     * TownSquarePlacer.RADIUS so the plaza dominates the centre.
     * Recipes pass this to the TownSquare primitive via the layout's
     * townSquareRadius field, but the primitive currently takes its
     * own plaza radius internally — see note in compose() below.
     */
    private static final int PLAZA_BUILDINGS = 6;
    private static final int SPUR_COUNT = 4;

    @Override
    public void compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();

        int totalBuildings = pctx.remaining.size();

        // ── Town square at centre with high civic capacity ─────────────────
        // Higher capacity → larger civic ring (arc-length math is linear
        // in capacity), which is what makes PLAZA feel urban. The civic
        // buildings form a tight band tangent to the plaza area.
        int civicCap = Math.max(PLAZA_BUILDINGS, Math.min(8, totalBuildings / 3));
        new LayoutPrimitive.TownSquare(centre, civicCap, null).place(pctx);
        BlockPos squarePos = pctx.layout.getTownSquarePos();

        List<List<BlockPos>> allRoadsForSnap = new ArrayList<>();

        // ── Four spurs at NE/NW/SE/SW so they don't collide with the
        //    cardinal-aligned civic buildings ──────────────────────────────
        double[] spurAngles = {
                Math.PI / 4,           // SE
                3 * Math.PI / 4,       // SW
                -Math.PI / 4,          // NE
                -3 * Math.PI / 4       // NW
        };

        // Choose the "main road" direction = the spur that aligns best with
        // terrain.bestFlatDir, so trade routes attach somewhere flat
        double terrainRad = directionRadOf(terrain.bestFlatDir());
        int mainSpurIdx = 0;
        double bestDelta = Double.MAX_VALUE;
        for (int i = 0; i < spurAngles.length; i++) {
            double delta = Math.abs(angleDelta(spurAngles[i], terrainRad));
            if (delta < bestDelta) { bestDelta = delta; mainSpurIdx = i; }
        }

        // Distribute production + residential across the four spurs
        List<VillageTypeData.StarterBuilding> production =
                pctx.claimByZone(BuildingZone.PRODUCTION, 1000);
        List<VillageTypeData.StarterBuilding> residential =
                pctx.claimByZone(BuildingZone.RESIDENTIAL, 1000);

        List<List<VillageTypeData.StarterBuilding>> buckets = new ArrayList<>();
        for (int i = 0; i < SPUR_COUNT; i++) buckets.add(new ArrayList<>());
        int b = 0;
        for (VillageTypeData.StarterBuilding sb : production) {
            buckets.get(b++ % SPUR_COUNT).add(sb);
        }
        for (VillageTypeData.StarterBuilding sb : residential) {
            buckets.get(b++ % SPUR_COUNT).add(sb);
        }

        // ── Build each spur ────────────────────────────────────────────────
        BlockPos mainGate = null;
        for (int i = 0; i < SPUR_COUNT; i++) {
            List<VillageTypeData.StarterBuilding> bucket = buckets.get(i);
            if (bucket.isEmpty()) continue;

            double spurAngle = spurAngles[i];
            // The "main" spur is longer and a higher tier (VILLAGE_ROAD)
            boolean isMain = i == mainSpurIdx;
            int spurLength = isMain
                    ? pctx.density.getRing2Radius() * 2 + 24
                    : pctx.density.getRing1Radius() + pctx.density.getRing2Radius() / 2;
            double drift = isMain ? 7.0 : 4.0;
            RoadShape.RoadTier tier = isMain
                    ? RoadShape.RoadTier.VILLAGE_ROAD
                    : RoadShape.RoadTier.VILLAGE_PATH;

            // Branch hint: a point just outside the civic ring in the
            // spur direction. squarePos is the centre, the civic ring sits
            // around townSquareRadius + ~placement; we use a point ~14
            // blocks out so the spur starts cleanly past the civic band.
            int branchOffset = pctx.layout.getCivicRingRadius() + 2;
            BlockPos branchHint = new BlockPos(
                    squarePos.getX() + (int) Math.round(Math.cos(spurAngle) * branchOffset),
                    squarePos.getY(),
                    squarePos.getZ() + (int) Math.round(Math.sin(spurAngle) * branchOffset));
            // Spur needs a parent centerline; PLAZA has no main road yet,
            // so use a tiny synthetic one from the square to the branch hint.
            // The spur primitive will snap branchHint to the nearest point
            // on this synthetic road, which is fine.
            List<BlockPos> synthParent = List.of(squarePos, branchHint);

            RoadPrimitive.Spur spur = new RoadPrimitive.Spur(
                    synthParent,
                    branchHint,
                    spurAngle,
                    spurLength,
                    drift,
                    tier);
            List<BlockPos> spurCenterline = pctx.layout.addRoad(
                    spur, pctx.level, pctx.worldSeed);
            allRoadsForSnap.add(spurCenterline);

            BlockPos focal = spurCenterline.get(spurCenterline.size() - 1);
            if (isMain) mainGate = focal;
            pctx.layout.addGatePosition(focal);

            new LayoutPrimitive.BuildingCircle(
                    focal,
                    LayoutPrimitive.BuildingCircle.Mode.SCATTER,
                    bucket,
                    spurCenterline
            ).place(pctx);
        }

        // Set gate endpoint to the end of the main spur (or fall back to
        // the first spur's end if mainSpur was empty)
        if (mainGate == null && !allRoadsForSnap.isEmpty()) {
            List<BlockPos> first = allRoadsForSnap.get(0);
            mainGate = first.get(first.size() - 1);
        }
        if (mainGate != null) pctx.layout.setMainGateEndpoint(mainGate);

        // ── Agricultural ring on the urban edge ────────────────────────────
        List<VillageTypeData.StarterBuilding> agri =
                pctx.claimByZone(BuildingZone.AGRICULTURAL, 1000);
        if (!agri.isEmpty()) {
            new LayoutPrimitive.RingBand(
                    centre,
                    pctx.density.getRing2Radius() + 10,
                    pctx.density.getRing2Radius() + 28,
                    BuildingZone.AGRICULTURAL, agri, allRoadsForSnap
            ).place(pctx);
        }

        // ── Defensive: outer ring as city walls / towers ───────────────────
        List<VillageTypeData.StarterBuilding> defensive =
                pctx.claimByZone(BuildingZone.DEFENSIVE, 1000);
        if (!defensive.isEmpty()) {
            new LayoutPrimitive.RingBand(
                    centre,
                    pctx.density.getRing2Radius() + 4,
                    pctx.density.getRing2Radius() + 14,
                    BuildingZone.DEFENSIVE, defensive, allRoadsForSnap
            ).place(pctx);
        }

        // ── Stragglers ─────────────────────────────────────────────────────
        if (!pctx.remaining.isEmpty()) {
            List<VillageTypeData.StarterBuilding> leftovers =
                    new ArrayList<>(pctx.remaining);
            pctx.remaining.clear();
            new LayoutPrimitive.RingBand(
                    centre,
                    pctx.density.getRing1Radius(),
                    pctx.density.getRing2Radius(),
                    BuildingZone.RESIDENTIAL, leftovers, allRoadsForSnap
            ).place(pctx);
        }
    }

    private static double angleDelta(double a, double b) {
        double d = (a - b) % (2 * Math.PI);
        if (d > Math.PI) d -= 2 * Math.PI;
        if (d < -Math.PI) d += 2 * Math.PI;
        return d;
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