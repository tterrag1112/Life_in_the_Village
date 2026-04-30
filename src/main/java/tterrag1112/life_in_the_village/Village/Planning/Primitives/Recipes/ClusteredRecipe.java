package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.LayoutPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.ShapeRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.AddRing;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.Sector;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.SectorRole;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * CLUSTERED layout recipe. Phase 11.2: implements {@link ShapeRecipe} directly
 * (escape-hatch — no central spine, no civic ring, no symmetric layout).
 *
 * <p>3-5 separate building clumps connected by winding footpaths. The largest
 * clump (cluster 0) is CIVIC_TIGHT so it accepts the town hall. No BaseRecipe
 * lifecycle — the geometry can't decompose into prepareFeatures → composeSectors.
 *
 * <p>What changed from the pre-conversion version: claimByZone + bucket
 * distribution + BuildingCircle.place() + RingBand.place() replaced by one
 * sector per cluster (emitted via snapshot/drain around
 * {@code BuildingCircle.emitSlotsWithTags}). Each cluster grows via AddRing.
 */
public final class ClusteredRecipe implements ShapeRecipe {

    /** Minimum XZ distance between cluster centres. */
    private static final int MIN_CLUSTER_SPACING = 60;

    private static final Set<SlotTag> TAGS_CLUSTER = EnumSet.of(
            SlotTag.RESIDENTIAL_INFILL, SlotTag.PRODUCTION_INFILL,
            SlotTag.ROAD_ADJACENT, SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_MAIN_CLUSTER = EnumSet.of(
            SlotTag.CIVIC_ADJACENT, SlotTag.SECONDARY_CIVIC,
            SlotTag.RESIDENTIAL_CORE, SlotTag.ROAD_ADJACENT);

    @Override
    public void compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        int totalBuildings = pctx.remaining.size();

        // ── Decide how many clusters ───────────────────────────────────────
        int clusterCount = Math.max(3, Math.min(5, totalBuildings / 4));

        // ── Pick cluster centres ───────────────────────────────────────────
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
                boolean tooClose = false;
                for (BlockPos other : clusterCentres) {
                    if (candidate.distSqr(other) < MIN_CLUSTER_SPACING * MIN_CLUSTER_SPACING) {
                        tooClose = true;
                        break;
                    }
                }
                if (tooClose) continue;
                if (pctx.features.isOnWater(candidate)) continue;
                if (pctx.features.isOnCliff(candidate)) continue;
                picked = candidate;
                break;
            }
            if (picked != null) clusterCentres.add(picked);
        }

        if (clusterCentres.size() < clusterCount) {
            System.out.println("ClusteredRecipe: requested " + clusterCount
                    + " clusters, picker placed " + clusterCentres.size()
                    + " (spacing constraint = " + MIN_CLUSTER_SPACING + ")");
        }
        if (clusterCentres.isEmpty()) {
            System.out.println("ClusteredRecipe: failed to pick any cluster centres"
                    + " — falling back to RADIAL");
            PlacementFailureRecorder.record(
                    PlacementFailureRecorder.Reason.SHAPE_RULE_REJECTED,
                    "failed to pick any cluster centers",
                    centre, VillageTypeData.ShapeType.CLUSTERED.name());
            new RadialRecipe().compose(pctx);
            return;
        }

        // ── Connect clusters with footpaths (greedy MST) ───────────────────
        List<List<BlockPos>> allRoadsForSnap = new ArrayList<>();
        List<BlockPos> connected = new ArrayList<>();
        connected.add(clusterCentres.get(0));
        for (int i = 1; i < clusterCentres.size(); i++) {
            BlockPos next = clusterCentres.get(i);
            BlockPos nearest = connected.stream()
                    .min(Comparator.comparingDouble(p -> p.distSqr(next)))
                    .orElse(connected.get(0));
            RoadPrimitive.StraightRoad path = new RoadPrimitive.StraightRoad(
                    nearest, next, 4.0, RoadShape.RoadTier.FOOTPATH);
            List<BlockPos> pathLine = pctx.layout.addRoad(
                    path, pctx.level, pctx.worldSeed);
            allRoadsForSnap.add(pathLine);
            connected.add(next);
        }

        // ── Gate road outward from the main cluster ────────────────────────
        BlockPos main = clusterCentres.get(0);
        double awayAngle = 0;
        if (clusterCentres.size() > 1) {
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
        pctx.layout.addGatePosition(gateEnd);

        // Mark cluster 0 as the town square position.
        pctx.layout.setTownSquarePos(main);
        pctx.layout.setTownSquareRadius(4);
        RecipeHelpers.installPlaza(pctx, main,
                tterrag1112.life_in_the_village.Village.Decoration
                        .Plaza.PlazaShape.IRREGULAR);

        // ── Emit one sector per cluster (snapshot/drain pattern) ──────────
        // Placeholder list for BuildingCircle size estimation (count only).
        int perCluster = Math.max(3, totalBuildings / clusterCentres.size());

        for (int i = 0; i < clusterCentres.size(); i++) {
            BlockPos focal = clusterCentres.get(i);
            boolean isMain = (i == 0);

            List<VillageTypeData.StarterBuilding> placeholder = new ArrayList<>();
            int copy = Math.min(perCluster, pctx.remaining.size());
            for (int k = 0; k < copy; k++) placeholder.add(pctx.remaining.get(k));

            // Synthetic road for facing context (all sides → circle wraps focal).
            BlockPos synthRoadEnd = focal.offset(8, 0, 0);
            List<BlockPos> synthRoad = List.of(synthRoadEnd, focal);

            int clusterSnapshot = pctx.slotPoolSize();
            LayoutPrimitive.BuildingCircle circle = new LayoutPrimitive.BuildingCircle(
                    focal,
                    LayoutPrimitive.BuildingCircle.Mode.SCATTER,
                    placeholder,
                    synthRoad);
            circle.emitSlotsWithTags(pctx,
                    isMain ? TAGS_MAIN_CLUSTER : TAGS_CLUSTER,
                    isMain ? 60 : 40);

            List<PlacementSlot> clusterSlots = pctx.drainSlotsSince(clusterSnapshot);
            if (!clusterSlots.isEmpty()) {
                pctx.offerSector(new Sector(
                        "clustered_cluster_" + i,
                        isMain ? SectorRole.CIVIC_TIGHT : SectorRole.RESIDENTIAL_CLUSTER,
                        isMain ? BuildingZone.CIVIC : BuildingZone.RESIDENTIAL,
                        clusterSlots,
                        isMain ? 6 : 4,
                        true,
                        new AddRing(8, 3),
                        -1,
                        null));
            }
        }
    }
}
