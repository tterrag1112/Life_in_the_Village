// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/ClusteredRecipe.java
package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.*;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * CLUSTERED layout recipe.
 *
 * <p>3-5 separate building clumps connected by winding footpaths. No
 * central plaza, no main road, no concentric anything. The largest
 * clump contains the town hall and is treated as the "main" cluster
 * for trade route attachment, but it doesn't dominate the layout.
 *
 * <p>Reads as: a settlement that grew from separate family compounds.
 * Tribal village, refugee camp, frontier homestead grouping. The lack
 * of central organization is the point.
 */
public final class ClusteredRecipe implements ShapeRecipe {

    /** Minimum XZ distance between cluster centres. */
    private static final int MIN_CLUSTER_SPACING = 60;

    @Override
    public void compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        int totalBuildings = pctx.remaining.size();

        // ── Decide how many clusters ───────────────────────────────────────
        // 3-5 clusters depending on building count. Each cluster wants
        // 3-5 buildings minimum to read as a clump rather than a row.
        int clusterCount = Math.max(3, Math.min(5, totalBuildings / 4));

        // ── Pick cluster centres ───────────────────────────────────────────
        // Distribute around `centre` at distance `ring1` to `ring2`,
        // with random angular offsets and a minimum-spacing check.
        List<BlockPos> clusterCentres = new ArrayList<>();
        int innerR = pctx.density.getRing2Radius();
        int outerR = pctx.density.getRing2Radius() * 2 + 16;
        double baseAngle = pctx.rng.nextDouble() * 2 * Math.PI;
        double angleStep = 2 * Math.PI / clusterCount;

        for (int i = 0; i < clusterCount; i++) {
            BlockPos picked = null;
            for (int attempt = 0; attempt < 8; attempt++) {
                double angle = baseAngle + angleStep * i
                        + (pctx.rng.nextDouble() - 0.5) * (angleStep * 0.5);
                int r = innerR + pctx.rng.nextInt(Math.max(1, outerR - innerR));
                BlockPos candidate = pctx.solidSurface(new BlockPos(
                        centre.getX() + (int)(Math.cos(angle) * r),
                        centre.getY(),
                        centre.getZ() + (int)(Math.sin(angle) * r)));
                // Check minimum spacing against already-placed clusters
                boolean tooClose = false;
                for (BlockPos other : clusterCentres) {
                    if (candidate.distSqr(other) < MIN_CLUSTER_SPACING * MIN_CLUSTER_SPACING) {
                        tooClose = true;
                        break;
                    }
                }
                if (!tooClose) {
                    picked = candidate;
                    break;
                }
            }
            if (picked != null) clusterCentres.add(picked);
        }

        if (clusterCentres.size() < clusterCount) {
            System.out.println("ClusteredRecipe: requested " + clusterCount
                    + " clusters, picker placed " + clusterCentres.size()
                    + " (spacing constraint = " + MIN_CLUSTER_SPACING + ")");
        }
        if (clusterCentres.isEmpty()) {
            System.out.println("ClusteredRecipe: failed to pick any cluster centres "
                    + "— falling back to RADIAL");
            PlacementFailureRecorder
                    .record(PlacementFailureRecorder.Reason.SHAPE_RULE_REJECTED,
                            "failed to pick any cluster centers",
                            centre, VillageTypeData.ShapeType.CLUSTERED.name());
            new RadialRecipe().compose(pctx);
            return;
        }

        // ── Distribute buildings across clusters ───────────────────────────
        // Town hall + civic + half of production go to the largest cluster
        // (cluster 0 by convention). The rest distribute round-robin.
        VillageTypeData.StarterBuilding townHall = pctx.claimTownHall();
        List<VillageTypeData.StarterBuilding> civic =
                pctx.claimByZone(BuildingZone.CIVIC, 1000);
        List<VillageTypeData.StarterBuilding> production =
                pctx.claimByZone(BuildingZone.PRODUCTION, 1000);
        List<VillageTypeData.StarterBuilding> residential =
                pctx.claimByZone(BuildingZone.RESIDENTIAL, 1000);

        List<List<VillageTypeData.StarterBuilding>> buckets = new ArrayList<>();
        for (int i = 0; i < clusterCentres.size(); i++) buckets.add(new ArrayList<>());

        // Main cluster (index 0): town hall + all civic + first slice of production
        if (townHall != null) buckets.get(0).add(townHall);
        buckets.get(0).addAll(civic);

        // Production round-robin starting from cluster 0
        int b = 0;
        for (VillageTypeData.StarterBuilding sb : production) {
            buckets.get(b++ % clusterCentres.size()).add(sb);
        }
        // Residential round-robin starting from cluster 1 (so cluster 0
        // doesn't get overloaded if there are lots of houses)
        b = clusterCentres.size() > 1 ? 1 : 0;
        for (VillageTypeData.StarterBuilding sb : residential) {
            buckets.get(b++ % clusterCentres.size()).add(sb);
        }

        // ── Place each cluster as a SCATTER BuildingCircle ────────────────
        // The "main" cluster (index 0) is placed first because it gets
        // the town hall. Mark cluster 0 as the gate endpoint source.
        List<List<BlockPos>> allRoadsForSnap = new ArrayList<>();
        for (int i = 0; i < clusterCentres.size(); i++) {
            BlockPos focal = clusterCentres.get(i);
            List<VillageTypeData.StarterBuilding> bucket = buckets.get(i);
            if (bucket.isEmpty()) continue;

            // SCATTER without a feeding road — the cluster wraps the
            // focal point on all sides. Pass a tiny synthetic road so
            // the SCATTER hemisphere logic has something to face away from.
            BlockPos synthRoadEnd = focal.offset(8, 0, 0);
            List<BlockPos> synthRoad = List.of(synthRoadEnd, focal);

            new LayoutPrimitive.BuildingCircle(
                    focal,
                    LayoutPrimitive.BuildingCircle.Mode.SCATTER,
                    bucket,
                    synthRoad
            ).place(pctx);
        }

        // ── Connect clusters with footpaths (greedy MST-ish) ───────────────
        // Each cluster after the first connects to its nearest already-
        // placed cluster. Builds a tree of paths, no cycles.
        List<BlockPos> connected = new ArrayList<>();
        connected.add(clusterCentres.get(0));
        for (int i = 1; i < clusterCentres.size(); i++) {
            BlockPos next = clusterCentres.get(i);
            BlockPos nearest = connected.stream()
                    .min(Comparator.comparingDouble(p -> p.distSqr(next)))
                    .orElse(connected.get(0));
            // Path between them — slightly drifted footpath
            RoadPrimitive.StraightRoad path = new RoadPrimitive.StraightRoad(
                    nearest, next, 4.0, RoadShape.RoadTier.FOOTPATH);
            List<BlockPos> pathLine = pctx.layout.addRoad(
                    path, pctx.level, pctx.worldSeed);
            allRoadsForSnap.add(pathLine);
            connected.add(next);
        }

        // ── Gate endpoint: extend a path outward from the main cluster ────
        // Trade routes need somewhere to attach. Pick a direction away
        // from all other clusters and run a short FOOTPATH outward.
        BlockPos main = clusterCentres.get(0);
        double awayAngle = 0;
        if (clusterCentres.size() > 1) {
            // Angle away from the centroid of the other clusters
            double cx = 0, cz = 0;
            for (int i = 1; i < clusterCentres.size(); i++) {
                cx += clusterCentres.get(i).getX();
                cz += clusterCentres.get(i).getZ();
            }
            cx /= (clusterCentres.size() - 1);
            cz /= (clusterCentres.size() - 1);
            awayAngle = Math.atan2(main.getZ() - cz, main.getX() - cx);
        }
        int gateRunLen = pctx.density.getRing2Radius() + 16;
        BlockPos gateEnd = pctx.solidSurface(new BlockPos(
                main.getX() + (int)(Math.cos(awayAngle) * gateRunLen),
                main.getY(),
                main.getZ() + (int)(Math.sin(awayAngle) * gateRunLen)));
        RoadPrimitive.StraightRoad gateRoad = new RoadPrimitive.StraightRoad(
                main, gateEnd, 6.0, RoadShape.RoadTier.VILLAGE_PATH);
        List<BlockPos> gateLine = pctx.layout.addRoad(
                gateRoad, pctx.level, pctx.worldSeed);
        allRoadsForSnap.add(gateLine);
        pctx.layout.setMainGateEndpoint(gateEnd);

        // Mark cluster 0 as the town square position so the decorator
        // and trade systems have something to anchor to. No civic ring
        // is set — CLUSTERED has no ring concept.
        pctx.layout.setTownSquarePos(main);
        pctx.layout.setTownSquareRadius(4);

        // ── Agricultural: distribute around the cluster ring ──────────────
        List<VillageTypeData.StarterBuilding> agri =
                pctx.claimByZone(BuildingZone.AGRICULTURAL, 1000);
        if (!agri.isEmpty()) {
            new LayoutPrimitive.RingBand(
                    centre,
                    pctx.density.getRing2Radius() + 8,
                    pctx.density.getRing2Radius() + 24,
                    BuildingZone.AGRICULTURAL, agri, allRoadsForSnap
            ).place(pctx);
        }

        // ── Defensive: scatter a few near the gate road ────────────────────
        List<VillageTypeData.StarterBuilding> defensive =
                pctx.claimByZone(BuildingZone.DEFENSIVE, 1000);
        if (!defensive.isEmpty()) {
            // Place defensive near the gate end as a watch post
            new LayoutPrimitive.BuildingCircle(
                    gateEnd,
                    LayoutPrimitive.BuildingCircle.Mode.SCATTER,
                    defensive,
                    gateLine
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
}