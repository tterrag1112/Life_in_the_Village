// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/RiverineRecipe.java
package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder;
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
import java.util.Iterator;
import java.util.List;

/**
 * RIVERINE layout recipe.
 *
 * <p>A village strung along the inland side of a river or lake shore.
 * The main road runs parallel to the shore, the town square sits at
 * its midpoint, spurs branch inland (never toward water), and
 * water-adjacency buildings get short stubs reaching toward the shore.
 *
 * <p>Falls back to RADIAL with a warning if no water body was detected
 * by the terrain analyzer — riverine geometry is meaningless without
 * a shore to align to.
 */
public final class RiverineRecipe implements ShapeRecipe {

    /** Inland offset of the main road from the shore, in blocks. */
    private static final int SHORE_OFFSET = 8;
    /** Building types that prefer to sit on the shore side. */
    private static final List<String> WATER_TYPES =
            List.of("FISHERY", "DOCK", "MILL", "WATERMILL");

    @Override
    public void compose(PlanContext pctx) {
        TerrainProfile terrain = pctx.layout.getTerrain();
        BlockPos origin = pctx.layout.getCenter();


        if (!terrain.hasWater()) {
            System.out.println("RiverineRecipe: no water body detected — "
                    + "falling back to RADIAL");
            new RadialRecipe().compose(pctx);
            PlacementFailureRecorder
                    .record(PlacementFailureRecorder.Reason.SHAPE_RULE_REJECTED,
                            "no water body detected",
                            origin, VillageTypeData.ShapeType.RIVERINE.name());
            return;
        }

        pctx.rejectWaterForAll = true;


        TerrainAnalyzer.WaterBodyInfo water = terrain.waterBody();
        BlockPos shore = water.nearestShore();

        // ── Inland direction: from shore toward origin ─────────────────────
        // Computed as a unit vector in the XZ plane. Reliable even when
        // waterFacingDir's cardinal quantization would point wrong.
        double inlandDx = origin.getX() - shore.getX();
        double inlandDz = origin.getZ() - shore.getZ();
        double inlandLen = Math.sqrt(inlandDx * inlandDx + inlandDz * inlandDz);
        if (inlandLen < 1) {
            // origin is sitting on top of the shore — push out arbitrarily
            inlandDx = 1; inlandDz = 0; inlandLen = 1;
        }
        double inX = inlandDx / inlandLen;
        double inZ = inlandDz / inlandLen;
        // Shore tangent = perpendicular to inland (rotate 90° CCW)
        double tanX = -inZ;
        double tanZ = inX;

        // Inland direction in radians for spur angles
        double inlandRad = Math.atan2(inZ, inX);
        // Shore-parallel direction in radians for the main road
        double mainDirRad = Math.atan2(tanZ, tanX);

        // ── Main road: parallel to shore, offset inland ────────────────────
        int totalBuildings = pctx.remaining.size();
        int halfLength = pctx.density.getRing2Radius() * 3
                + totalBuildings * 6
                + 48;

        // Anchor: shore point pushed inland by SHORE_OFFSET, then the road
        // extends ±halfLength along the shore tangent from there.
        int anchorX = shore.getX() + (int) Math.round(inX * SHORE_OFFSET);
        int anchorZ = shore.getZ() + (int) Math.round(inZ * SHORE_OFFSET);
        BlockPos anchor = pctx.solidSurface(new BlockPos(anchorX, origin.getY(), anchorZ));

        BlockPos mainStart = pctx.solidSurface(new BlockPos(
                anchor.getX() + (int) Math.round(tanX * halfLength),
                anchor.getY(),
                anchor.getZ() + (int) Math.round(tanZ * halfLength)));
        BlockPos mainEnd = pctx.solidSurface(new BlockPos(
                anchor.getX() - (int) Math.round(tanX * halfLength),
                anchor.getY(),
                anchor.getZ() - (int) Math.round(tanZ * halfLength)));

        RoadPrimitive.StraightRoad mainRoad = new RoadPrimitive.StraightRoad(
                mainStart, mainEnd, 8.0, RoadShape.RoadTier.VILLAGE_ROAD);
        List<BlockPos> mainCenterline = pctx.layout.addRoad(
                mainRoad, pctx.level, pctx.worldSeed);

        // Gate endpoint: the inland end of the main road. Use whichever
        // end is farther from the shore (drift can flip which one is which).
        BlockPos gate = mainStart.distSqr(shore) > mainEnd.distSqr(shore)
                ? mainStart : mainEnd;
        pctx.layout.setMainGateEndpoint(gate);
        pctx.layout.addGatePosition(mainStart);
        pctx.layout.addGatePosition(mainEnd);

        // ── Town square at the midpoint of the main road ───────────────────
        BlockPos squareMid = mainCenterline.get(mainCenterline.size() / 2);

        // Reduced civic capacity — the TownSquare primitive lays civic in a
        // full ring, which would cross the river. A smaller ring stays
        // mostly on the inland side. (Real fix is a HALF_RING mode later.)
        int civicCap = Math.max(2, Math.min(3, totalBuildings / 8 + 2));
        new LayoutPrimitive.TownSquare(squareMid, civicCap, mainCenterline)
                .place(pctx);

        List<List<BlockPos>> allRoadsForSnap = new ArrayList<>();
        allRoadsForSnap.add(mainCenterline);

        // ── Water-side stubs for water-adjacency buildings ─────────────────
        List<VillageTypeData.StarterBuilding> waterBuildings =
                claimWaterTypes(pctx);
        if (!waterBuildings.isEmpty()) {
            // Stub length: just enough to reach near the shore but stop
            // short of the water. SHORE_OFFSET - 2 keeps the building's
            // footprint dry.
            int stubLength = SHORE_OFFSET - 2;
            int stubSpacing = Math.max(8,
                    mainCenterline.size() / (waterBuildings.size() + 1));

            for (int i = 0; i < waterBuildings.size(); i++) {
                int idx = Math.min(mainCenterline.size() - 1,
                        stubSpacing * (i + 1));
                BlockPos branchHint = mainCenterline.get(idx);

                // Stub points TOWARD the shore (opposite of inland)
                double stubAngle = inlandRad + Math.PI;
                RoadPrimitive.Spur stub = new RoadPrimitive.Spur(
                        mainCenterline,
                        branchHint,
                        stubAngle,
                        stubLength,
                        1.5,
                        RoadShape.RoadTier.FOOTPATH);
                List<BlockPos> stubCenterline = pctx.layout.addRoad(
                        stub, pctx.level, pctx.worldSeed);
                allRoadsForSnap.add(stubCenterline);

                BlockPos focal = stubCenterline.get(stubCenterline.size() - 1);
                // Single-building "circle" — SCATTER with one entry just
                // places it next to the focal on the back side.
                List<VillageTypeData.StarterBuilding> single =
                        List.of(waterBuildings.get(i));
                new LayoutPrimitive.BuildingCircle(
                        focal,
                        LayoutPrimitive.BuildingCircle.Mode.SCATTER,
                        single,
                        stubCenterline
                ).place(pctx);
            }
        }

        // ── Inland spurs for production + residential ──────────────────────
        List<VillageTypeData.StarterBuilding> production =
                pctx.claimByZone(BuildingZone.PRODUCTION, 1000);
        List<VillageTypeData.StarterBuilding> residential =
                pctx.claimByZone(BuildingZone.RESIDENTIAL, 1000);

        int spurCount = Math.max(2,
                (int) Math.ceil((production.size() + residential.size()) / 4.0));
        spurCount = Math.min(spurCount, 5);

        // Distribute buildings across inland spurs
        List<List<VillageTypeData.StarterBuilding>> buckets = new ArrayList<>();
        for (int i = 0; i < spurCount; i++) buckets.add(new ArrayList<>());
        int b = 0;
        for (VillageTypeData.StarterBuilding sb : production) {
            buckets.get(b++ % spurCount).add(sb);
        }
        for (VillageTypeData.StarterBuilding sb : residential) {
            buckets.get(b++ % spurCount).add(sb);
        }

        // Spur branch fractions along the main road, biased away from
        // the square at the midpoint
        double[] fractions = spurCount == 2 ? new double[]{0.3, 0.7}
                : spurCount == 3 ? new double[]{0.25, 0.5, 0.75}
                : spurCount == 4 ? new double[]{0.2, 0.4, 0.6, 0.8}
                : new double[]{0.15, 0.32, 0.5, 0.68, 0.85};

        for (int i = 0; i < spurCount; i++) {
            List<VillageTypeData.StarterBuilding> bucket = buckets.get(i);
            if (bucket.isEmpty()) continue;

            int idx = Math.min(mainCenterline.size() - 1,
                    Math.max(0, (int)(fractions[i] * mainCenterline.size())));
            BlockPos branchHint = mainCenterline.get(idx);

            // Inland angle with small jitter
            double spurAngle = inlandRad
                    + (pctx.rng.nextDouble() - 0.5) * 0.5;
            int spurLength = pctx.density.getRing1Radius() + 4
                    + pctx.rng.nextInt(Math.max(1,
                    pctx.density.getRing2Radius() - pctx.density.getRing1Radius()));

            RoadPrimitive.Spur spur = new RoadPrimitive.Spur(
                    mainCenterline,
                    branchHint,
                    spurAngle,
                    spurLength,
                    5.0,
                    RoadShape.RoadTier.VILLAGE_PATH);
            List<BlockPos> spurCenterline = pctx.layout.addRoad(
                    spur, pctx.level, pctx.worldSeed);
            allRoadsForSnap.add(spurCenterline);

            BlockPos focal = spurCenterline.get(spurCenterline.size() - 1);
            new LayoutPrimitive.BuildingCircle(
                    focal,
                    LayoutPrimitive.BuildingCircle.Mode.SCATTER,
                    bucket,
                    spurCenterline
            ).place(pctx);
        }

        // ── Agricultural ring on the inland side ───────────────────────────
        // Centered on the inland anchor, not the geometric origin, so the
        // ring sits inland and crops don't grow in the river.
        List<VillageTypeData.StarterBuilding> agri =
                pctx.claimByZone(BuildingZone.AGRICULTURAL, 1000);
        if (!agri.isEmpty()) {
            int ringInner = pctx.density.getRing2Radius() + 4;
            int ringOuter = pctx.density.getRing2Radius() + 20;
            // Anchor pushed further inland so the ring band clears the shore
            BlockPos agriCentre = new BlockPos(
                    anchor.getX() + (int) Math.round(inX * ringInner / 2),
                    anchor.getY(),
                    anchor.getZ() + (int) Math.round(inZ * ringInner / 2));
            new LayoutPrimitive.RingBand(
                    agriCentre, ringInner, ringOuter,
                    BuildingZone.AGRICULTURAL, agri, allRoadsForSnap
            ).place(pctx);
        }

        // ── Defensive on the inland flank ──────────────────────────────────
        List<VillageTypeData.StarterBuilding> defensive =
                pctx.claimByZone(BuildingZone.DEFENSIVE, 1000);
        if (!defensive.isEmpty()) {
            BlockPos defCentre = new BlockPos(
                    anchor.getX() + (int) Math.round(inX * pctx.density.getRing2Radius()),
                    anchor.getY(),
                    anchor.getZ() + (int) Math.round(inZ * pctx.density.getRing2Radius()));
            new LayoutPrimitive.RingBand(
                    defCentre,
                    pctx.density.getRing1Radius(),
                    pctx.density.getRing2Radius(),
                    BuildingZone.DEFENSIVE, defensive, allRoadsForSnap
            ).place(pctx);
        }

        // ── Stragglers ─────────────────────────────────────────────────────
        if (!pctx.remaining.isEmpty()) {
            List<VillageTypeData.StarterBuilding> leftovers =
                    new ArrayList<>(pctx.remaining);
            pctx.remaining.clear();
            new LayoutPrimitive.RingBand(
                    anchor,
                    pctx.density.getRing1Radius(),
                    pctx.density.getRing2Radius(),
                    BuildingZone.RESIDENTIAL, leftovers, allRoadsForSnap
            ).place(pctx);
        }
    }

    /** Pulls fishery/dock/mill-style buildings out of remaining. */
    private static List<VillageTypeData.StarterBuilding> claimWaterTypes(PlanContext pctx) {
        List<VillageTypeData.StarterBuilding> taken = new ArrayList<>();
        Iterator<VillageTypeData.StarterBuilding> it = pctx.remaining.iterator();
        while (it.hasNext()) {
            VillageTypeData.StarterBuilding sb = it.next();
            if (WATER_TYPES.contains(sb.type())) {
                taken.add(sb);
                it.remove();
            }
        }
        return taken;
    }
}