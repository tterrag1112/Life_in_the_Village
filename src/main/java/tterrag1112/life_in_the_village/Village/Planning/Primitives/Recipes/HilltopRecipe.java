// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/HilltopRecipe.java
package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.LayoutSlot;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.*;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * HILLTOP layout recipe.
 *
 * <p>The town hall sits at the highest point in the terrain sample
 * area and acts as the village's anchor — there is no separate town
 * square. Civic and high-status buildings cluster tightly around the
 * peak; production and residential descend on concentric arc terraces
 * at decreasing radii. A switchback main road climbs from the base of
 * the hill to the peak.
 *
 * <p>Reads as: defensive citadel, mountain monastery, lordly seat.
 * Vertical structure is the point — Y-snap on every centerline point
 * gives the arcs and switchback their stepping for free.
 *
 * <p>Falls back to PLAZA if the terrain is too flat to read as a hill
 * ({@code heightVariance < 12}). On a flat plane HILLTOP is just a
 * worse PLAZA.
 */
public final class HilltopRecipe implements ShapeRecipe {

    /** Minimum Y range across the sample area to qualify as "hilltop terrain." */
    private static final int MIN_VARIANCE = 12;

    @Override
    public void compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();

        BlockPos peak = findPeak(terrain, centre);


        if (terrain.heightVariance() < MIN_VARIANCE) {
            System.out.println("HilltopRecipe: terrain variance "
                    + terrain.heightVariance() + " < " + MIN_VARIANCE
                    + " — falling back to PLAZA");
            PlacementFailureRecorder
                    .record(PlacementFailureRecorder.Reason.SHAPE_RULE_REJECTED,
                            "variance "
                                    + terrain.heightVariance() + " < " + MIN_VARIANCE,
                            peak, VillageTypeData.ShapeType.HILLTOP.name());
            new PlazaRecipe().compose(pctx);
            return;
        }
        pctx.allowRidgePlacement = true;


        int totalBuildings = pctx.remaining.size();

        // ── Find the peak ──────────────────────────────────────────────────
        if (peak == null) {
            System.out.println("HilltopRecipe: no peak found — falling back to PLAZA");
            PlacementFailureRecorder
                    .record(PlacementFailureRecorder.Reason.SHAPE_RULE_REJECTED,
                            "no peak found "
                                    + terrain.heightVariance() + " < " + MIN_VARIANCE,
                            peak, VillageTypeData.ShapeType.HILLTOP.name());

            new PlazaRecipe().compose(pctx);
            return;
        }
        peak = pctx.solidSurface(peak);

        // ── Town hall at the peak ──────────────────────────────────────────
        VillageTypeData.StarterBuilding townHall = pctx.claimTownHall();
        if (townHall == null) {
            System.out.println("HilltopRecipe: no TOWN_HALL in starter list "
                    + "— falling back to PLAZA");
            PlacementFailureRecorder
                    .record(PlacementFailureRecorder.Reason.INSUFFICIENT_BUILDINGS,
                            "no TOWN_HALL in starter list",
                            peak, VillageTypeData.ShapeType.HILLTOP.name());
            new PlazaRecipe().compose(pctx);
            return;
        }
        BuildingType thType = PlanContext.parseType(townHall);
        if (thType == null) {
            new PlazaRecipe().compose(pctx);
            return;
        }

        LayoutSlot peakSlot = pctx.tryCommitWithRetries(peak, townHall, thType, null, 16);
        if (peakSlot == null) {
            System.out.println("HilltopRecipe: town hall failed to place at peak "
                    + peak + " — falling back to PLAZA");
            pctx.remaining.add(0, townHall);
            new PlazaRecipe().compose(pctx);
            return;
        }
        // Town hall faces the gate direction (computed below). For now
        // just use the existing chooseFacing — set after we know the gate.
        BlockPos peakPos = peakSlot.getPos();
        // Mark the peak as the layout's "town square" for downstream
        // systems that expect a centre. No civic ring radius — civic
        // buildings are placed by SCATTER below.
        pctx.layout.setTownSquarePos(peakPos);
        pctx.layout.setTownSquareRadius(4);
        // Phase 17 doc 04 — SQUARE polygon at the hill peak.
        RecipeHelpers.installPlaza(pctx, peakPos,
                tterrag1112.life_in_the_village.Village.Decoration
                        .Plaza.PlazaShape.SQUARE);

        // ── Find the base of the hill (gate location) ──────────────────────
        // The gate sits on the OPPOSITE side of the village centre from
        // the peak — i.e. the peak's projection through the centre,
        // extended to ring2 distance.
        double peakDx = peakPos.getX() - centre.getX();
        double peakDz = peakPos.getZ() - centre.getZ();
        double peakLen = Math.sqrt(peakDx * peakDx + peakDz * peakDz);
        // If peak happens to be at centre, pick a fallback direction
        double downX, downZ;
        if (peakLen < 1) {
            downX = 1; downZ = 0;
        } else {
            downX = -peakDx / peakLen;
            downZ = -peakDz / peakLen;
        }
        int baseDist = pctx.density.getRing2Radius() + 16;
        BlockPos base = pctx.solidSurface(new BlockPos(
                peakPos.getX() + (int) Math.round(downX * baseDist),
                peakPos.getY(),
                peakPos.getZ() + (int) Math.round(downZ * baseDist)));

        // Update town hall facing toward the gate
        peakSlot.setRotation(PlanContext.chooseFacing(peakPos, base));

        // ── Switchback main road from base to peak ─────────────────────────
        // Two-segment switchback: base → midpoint (offset perpendicular
        // by some amount) → peak. The Y-snap on every centerline point
        // makes this climb the surface for free.
        double sideX = -downZ;
        double sideZ = downX;
        int switchOffset = pctx.density.getRing1Radius() + 4;
        BlockPos midpoint = pctx.solidSurface(new BlockPos(
                (peakPos.getX() + base.getX()) / 2
                        + (int) Math.round(sideX * switchOffset),
                peakPos.getY(),
                (peakPos.getZ() + base.getZ()) / 2
                        + (int) Math.round(sideZ * switchOffset)));

        List<BlockPos> seg1 = pctx.layout.addRoad(
                new RoadPrimitive.StraightRoad(base, midpoint,
                        4.0, RoadShape.RoadTier.VILLAGE_ROAD),
                pctx.level, pctx.worldSeed);
        List<BlockPos> seg2 = pctx.layout.addRoad(
                new RoadPrimitive.StraightRoad(midpoint, peakPos,
                        4.0, RoadShape.RoadTier.VILLAGE_ROAD),
                pctx.level, pctx.worldSeed);

        pctx.layout.setMainGateEndpoint(base);
        pctx.layout.addGatePosition(base);

        List<List<BlockPos>> allRoadsForSnap = new ArrayList<>();
        allRoadsForSnap.add(seg1);
        allRoadsForSnap.add(seg2);

        // ── Concentric arcs at descending radii ────────────────────────────
        // Three terraces stepping outward from the peak. Each arc spans
        // ~270° centered AWAY from the gate direction (so the gate side
        // stays clear for the switchback). The Y of each centerline
        // point comes from surfaceAt — on a real hill that means each
        // arc sits at a lower Y than the one above it.
        int[] arcRadii = {
                pctx.density.getRing1Radius(),
                pctx.density.getRing1Radius()
                        + (pctx.density.getRing2Radius() - pctx.density.getRing1Radius()) / 2,
                pctx.density.getRing2Radius()
        };
        // Arc centre angle = direction OPPOSITE the gate (i.e. the
        // direction from base to peak, extended past the peak).
        double upRad = Math.atan2(-downZ, -downX);
        double arcCentreAngle = upRad;
        double arcSpan = Math.toRadians(270);
        double arcStartAngle = arcCentreAngle - arcSpan / 2;

        List<List<BlockPos>> arcCenterlines = new ArrayList<>();
        for (int r : arcRadii) {
            RoadPrimitive.Arc arc = new RoadPrimitive.Arc(
                    peakPos, r, arcStartAngle, arcSpan, 3.0,
                    RoadShape.RoadTier.VILLAGE_PATH);
            List<BlockPos> arcLine = pctx.layout.addRoad(
                    arc, pctx.level, pctx.worldSeed);
            arcCenterlines.add(arcLine);
            allRoadsForSnap.add(arcLine);
        }

        // ── Distribute remaining buildings across the three arcs ───────────
        // Inner arc: civic. Middle arc: production. Outer arc: residential.
        List<VillageTypeData.StarterBuilding> civic =
                pctx.claimByZone(BuildingZone.CIVIC, 1000);
        List<VillageTypeData.StarterBuilding> production =
                pctx.claimByZone(BuildingZone.PRODUCTION, 1000);
        List<VillageTypeData.StarterBuilding> residential =
                pctx.claimByZone(BuildingZone.RESIDENTIAL, 1000);

        placeOnArc(pctx, civic, arcCenterlines.get(0));
        placeOnArc(pctx, production, arcCenterlines.get(1));
        placeOnArc(pctx, residential, arcCenterlines.get(2));

        // ── Agricultural at the base of the hill ───────────────────────────
        // Farms make no sense on the slope; put them on the flat below
        // the gate.
        List<VillageTypeData.StarterBuilding> agri =
                pctx.claimByZone(BuildingZone.AGRICULTURAL, 1000);
        if (!agri.isEmpty()) {
            BlockPos farmCentre = new BlockPos(
                    base.getX() + (int) Math.round(downX * 16),
                    base.getY(),
                    base.getZ() + (int) Math.round(downZ * 16));
            new LayoutPrimitive.BuildingCircle(
                    pctx.solidSurface(farmCentre),
                    LayoutPrimitive.BuildingCircle.Mode.SCATTER,
                    agri, seg1
            ).place(pctx);
        }

        // ── Defensive: gatehouses near the base + watchtowers near the peak ──
        List<VillageTypeData.StarterBuilding> defensive =
                pctx.claimByZone(BuildingZone.DEFENSIVE, 1000);
        if (!defensive.isEmpty()) {
            // First half near the gate
            int half = (defensive.size() + 1) / 2;
            new LayoutPrimitive.BuildingCircle(
                    base,
                    LayoutPrimitive.BuildingCircle.Mode.SCATTER,
                    defensive.subList(0, half), seg1
            ).place(pctx);
            if (half < defensive.size()) {
                // Rest on the inner arc near the peak
                new LayoutPrimitive.BuildingCircle(
                        peakPos,
                        LayoutPrimitive.BuildingCircle.Mode.SCATTER,
                        defensive.subList(half, defensive.size()),
                        arcCenterlines.get(0)
                ).place(pctx);
            }
        }

        // ── Stragglers along whichever arc is closest ──────────────────────
        if (!pctx.remaining.isEmpty()) {
            List<VillageTypeData.StarterBuilding> leftovers =
                    new ArrayList<>(pctx.remaining);
            pctx.remaining.clear();
            new LayoutPrimitive.RingBand(
                    peakPos,
                    pctx.density.getRing1Radius(),
                    pctx.density.getRing2Radius(),
                    BuildingZone.RESIDENTIAL, leftovers, allRoadsForSnap
            ).place(pctx);
        }
    }

    /**
     * Finds the highest point in the sample area. Prefers the highest
     * ridge column (which the analyzer collected explicitly), falling
     * back to the highest flat candidate, falling back to the centre.
     */
    private static BlockPos findPeak(TerrainProfile terrain, BlockPos centre) {
        if (terrain.hasRidges()) {
            int bestY = Integer.MIN_VALUE;
            BlockPos best = null;
            for (TerrainAnalyzer.RidgeInfo r : terrain.ridges()) {
                int y = r.max().getY();
                if (y > bestY) {
                    bestY = y;
                    best = new BlockPos(
                            (r.min().getX() + r.max().getX()) / 2,
                            y,
                            (r.min().getZ() + r.max().getZ()) / 2);
                }
            }
            if (best != null) return best;
        }
        // Fallback: highest flat candidate
        return terrain.flatCandidates().stream()
                .max(Comparator.comparingInt(BlockPos::getY))
                .orElse(centre);
    }

    private void placeOnArc(PlanContext pctx,
                            List<VillageTypeData.StarterBuilding> buildings,
                            List<BlockPos> arcLine) {
        if (buildings.isEmpty() || arcLine.isEmpty()) return;
        // Distribute evenly along the arc, snapping each building to its
        // assigned arc point with a small tangent fake-road for retries.
        int n = buildings.size();
        for (int i = 0; i < n; i++) {
            VillageTypeData.StarterBuilding sb = buildings.get(i);
            BuildingType bt = PlanContext.parseType(sb);
            if (bt == null) continue;
            int idx = (int) ((i + 0.5) * arcLine.size() / n);
            idx = Math.max(0, Math.min(arcLine.size() - 1, idx));
            BlockPos target = arcLine.get(idx);

            LayoutSlot slot = pctx.tryCommitWithRetries(target, sb, bt, arcLine, 16);
            if (slot == null) {
                System.out.println("HilltopRecipe: dropped " + bt + " on arc");
            }
        }
    }
}