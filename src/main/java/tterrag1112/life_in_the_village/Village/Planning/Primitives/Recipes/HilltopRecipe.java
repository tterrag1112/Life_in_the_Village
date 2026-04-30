// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/HilltopRecipe.java
package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaShape;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.Features.CliffFeature;
import tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole;
import tterrag1112.life_in_the_village.Village.Planning.Graph.NodeKind;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.BaseRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.AddRing;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.FixedGrowth;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.Sector;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.SectorRole;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * HILLTOP layout recipe — BaseRecipe conversion.
 *
 * <p>The town hall sits at the highest point in the terrain sample
 * area and is emitted as a NAMED_ANCHOR sector (quality 100) so the
 * matcher's town-hall pre-pass places it there without a direct
 * {@code tryCommitWithRetries} call.
 *
 * <p>Civic and high-status buildings cluster tightly around the peak
 * on the upper arc sector. Production and residential descend on
 * concentric arc terraces (mid and lower arc sectors). An agricultural
 * sector sits at the base of the hill.
 *
 * <p>The switchback main road is now built from two
 * {@link RoadPrimitive.Stairway} segments (base→midpoint, midpoint→peak)
 * rather than surface-snapped StraightRoad primitives. Stairway
 * centerlines interpolate Y linearly between endpoints so the stairs
 * deliberately deviate from the natural terrain contour.
 *
 * <p>Falls back to {@link PlazaRecipe} when
 * {@code heightVariance < }{@value #MIN_VARIANCE} or no peak is found.
 */
public final class HilltopRecipe extends BaseRecipe {

    /** Minimum Y range across the sample area to qualify as "hilltop terrain." */
    private static final int MIN_VARIANCE = 12;

    private static final String SECTOR_PEAK_ANCHOR = "hilltop_peak_anchor";
    private static final String SECTOR_UPPER_ARC   = "hilltop_upper_arc";
    private static final String SECTOR_MID_ARC     = "hilltop_mid_arc";
    private static final String SECTOR_LOWER_ARC   = "hilltop_lower_arc";
    private static final String SECTOR_BASE_AGRI   = "hilltop_base_agri";

    private static final Set<SlotTag> TAGS_PEAK  = Set.of(
            SlotTag.HILLTOP_PEAK, SlotTag.HIGH_GROUND, SlotTag.PRIME_CIVIC);
    private static final Set<SlotTag> TAGS_UPPER = Set.of(
            SlotTag.HIGH_GROUND, SlotTag.CIVIC_ADJACENT, SlotTag.SECONDARY_CIVIC);
    private static final Set<SlotTag> TAGS_MID   = Set.of(
            SlotTag.HIGH_GROUND, SlotTag.RESIDENTIAL_CORE, SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_LOWER = Set.of(
            SlotTag.RESIDENTIAL_OUTER, SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_AGRI  = Set.of(
            SlotTag.FIELD_EDGE, SlotTag.PASTURE);

    // FeatureState — written by prepareFeatures, read by composeSectors.
    // Safe because ShapeRecipe.forShape() creates a fresh instance per village.
    private BlockPos cachedPeak;
    private BlockPos cachedBase;
    private boolean  cachedUsable;

    @Override
    public PlazaShape preferredPlazaShape() {
        return PlazaShape.SQUARE;
    }

    // =========================================================================
    // BaseRecipe lifecycle
    // =========================================================================

    @Override
    protected void prepareFeatures(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();

        if (terrain.heightVariance() < MIN_VARIANCE) {
            System.out.println("[HilltopRecipe] variance "
                    + terrain.heightVariance() + " < " + MIN_VARIANCE + " — will fallback");
            cachedUsable = false;
            return;
        }

        BlockPos peak = findPeakFromFeatures(pctx, centre);
        if (peak == null) {
            peak = findPeakLegacy(terrain, centre);
        }
        if (peak == null) {
            System.out.println("[HilltopRecipe] no peak found — will fallback");
            cachedUsable = false;
            return;
        }

        cachedPeak   = pctx.solidSurface(peak);
        cachedBase   = computeBasePosition(pctx, cachedPeak, centre);
        pctx.allowRidgePlacement = true;
        cachedUsable = true;
    }

    @Override
    protected void composeSectors(PlanContext pctx) {
        if (!cachedUsable) {
            PlacementFailureRecorder.record(
                    PlacementFailureRecorder.Reason.SHAPE_RULE_REJECTED,
                    "hilltop not usable (variance or no peak)",
                    pctx.layout.getCenter(),
                    VillageTypeData.ShapeType.HILLTOP.name());
            new PlazaRecipe().compose(pctx);
            return;
        }

        BlockPos peakPos   = cachedPeak;
        BlockPos baseGate  = cachedBase;
        int totalBuildings = pctx.remaining.size();

        // ── Plaza at peak ─────────────────────────────────────────────────
        pctx.layout.setTownSquarePos(peakPos);
        pctx.layout.setTownSquareRadius(4);
        int plazaSnapshot = pctx.slotPoolSize();
        RecipeHelpers.installPlaza(pctx, peakPos, PlazaShape.SQUARE);
        List<PlacementSlot> plazaSlots = pctx.drainSlotsSince(plazaSnapshot);

        // ── Derive hill geometry ──────────────────────────────────────────
        // downX/downZ: unit vector from peak toward base (downhill direction).
        // sideX/sideZ: perpendicular, used to offset the switchback midpoint.
        double peakDx = peakPos.getX() - pctx.layout.getCenter().getX();
        double peakDz = peakPos.getZ() - pctx.layout.getCenter().getZ();
        double peakLen = Math.sqrt(peakDx * peakDx + peakDz * peakDz);
        double downX, downZ;
        if (peakLen < 1) { downX = 1; downZ = 0; }
        else             { downX = -peakDx / peakLen; downZ = -peakDz / peakLen; }
        double sideX = -downZ, sideZ = downX;

        // ── Switchback road (two Stairway segments) ───────────────────────
        int switchOffset = pctx.density.getRing1Radius() + 4;
        BlockPos midpoint = pctx.solidSurface(new BlockPos(
                (peakPos.getX() + baseGate.getX()) / 2
                        + (int) Math.round(sideX * switchOffset),
                peakPos.getY(),
                (peakPos.getZ() + baseGate.getZ()) / 2
                        + (int) Math.round(sideZ * switchOffset)));

        int[] edgeIds      = buildSwitchbackChain(pctx, baseGate, midpoint, peakPos);
        int seg1EdgeId     = edgeIds[0];
        int seg2EdgeId     = edgeIds[1];
        List<BlockPos> seg1Centerline =
                pctx.layout.getRoadGraph().edge(seg1EdgeId).centerline();
        List<BlockPos> seg2Centerline =
                pctx.layout.getRoadGraph().edge(seg2EdgeId).centerline();

        pctx.layout.setMainGateEndpoint(baseGate);
        pctx.layout.addGatePosition(baseGate);

        // ── Peak anchor sector (town hall) ────────────────────────────────
        BlockPos peakSnapped   = pctx.solidSurface(peakPos);
        Rotation peakFacing    = PlanContext.chooseFacing(peakSnapped, baseGate);
        PlacementSlot peakSlot = new PlacementSlot(
                peakSnapped, seg2Centerline, seg2EdgeId,
                TAGS_PEAK, 8, 8, peakFacing, 100, 0);
        pctx.offerSector(new Sector(
                SECTOR_PEAK_ANCHOR, SectorRole.NAMED_ANCHOR, BuildingZone.CIVIC,
                List.of(peakSlot), 1, false, FixedGrowth.INSTANCE, seg2EdgeId, null));

        // ── Concentric arc roads (270° arcs at three radii) ───────────────
        // Arc centre angle points away from the gate so the gate-side
        // stays clear for the switchback road.
        double upRad         = Math.atan2(-downZ, -downX);
        double arcSpan       = Math.toRadians(270);
        double arcStartAngle = upRad - arcSpan / 2;
        int r1   = pctx.density.getRing1Radius();
        int r2   = pctx.density.getRing2Radius();
        int rMid = r1 + (r2 - r1) / 2;

        List<BlockPos> upperArcLine = pctx.layout.addRoad(
                new RoadPrimitive.Arc(peakPos, r1, arcStartAngle, arcSpan,
                        3.0, RoadShape.RoadTier.VILLAGE_PATH),
                pctx.level, pctx.worldSeed);
        List<BlockPos> midArcLine = pctx.layout.addRoad(
                new RoadPrimitive.Arc(peakPos, rMid, arcStartAngle, arcSpan,
                        3.0, RoadShape.RoadTier.VILLAGE_PATH),
                pctx.level, pctx.worldSeed);
        List<BlockPos> lowerArcLine = pctx.layout.addRoad(
                new RoadPrimitive.Arc(peakPos, r2, arcStartAngle, arcSpan,
                        3.0, RoadShape.RoadTier.VILLAGE_PATH),
                pctx.level, pctx.worldSeed);

        // ── Upper arc sector (civic — plaza slots + ring1 arc slots) ──────
        int civicCap = Math.max(2, Math.min(4, totalBuildings / 6 + 1));
        List<PlacementSlot> upperSlots = new ArrayList<>(plazaSlots);
        upperSlots.addAll(RecipeHelpers.generateSlotsAlongCenterline(
                upperArcLine, -1, TAGS_UPPER, 6, 8, 60));
        pctx.offerSector(new Sector(
                SECTOR_UPPER_ARC, SectorRole.CIVIC_RING, BuildingZone.CIVIC,
                upperSlots, civicCap, false, FixedGrowth.INSTANCE, seg2EdgeId, null));

        // ── Mid arc sector (production/residential) ───────────────────────
        int midCap = Math.max(4, totalBuildings / 4);
        List<PlacementSlot> midSlots = RecipeHelpers.generateSlotsAlongCenterline(
                midArcLine, -1, TAGS_MID, 6, 8, 45);
        pctx.offerSector(new Sector(
                SECTOR_MID_ARC, SectorRole.RESIDENTIAL_CLUSTER, BuildingZone.RESIDENTIAL,
                midSlots, midCap, true, new AddRing(8, 4), seg1EdgeId, null));

        // ── Lower arc sector (residential outer) ─────────────────────────
        List<PlacementSlot> lowerSlots = RecipeHelpers.generateSlotsAlongCenterline(
                lowerArcLine, -1, TAGS_LOWER, 6, 8, 35);
        pctx.offerSector(new Sector(
                SECTOR_LOWER_ARC, SectorRole.RESIDENTIAL_INFILL, BuildingZone.RESIDENTIAL,
                lowerSlots, midCap, true, new AddRing(8, 4), seg1EdgeId, null));

        // ── Agricultural base sector ──────────────────────────────────────
        // Farms sit on the flat below the gate, not on the slope.
        int agriInner = pctx.density.getRing2Radius() / 2;
        int agriOuter = agriInner + 24;
        List<PlacementSlot> agriSlots = RecipeHelpers.generateRingSlots(
                baseGate, agriInner, agriOuter, TAGS_AGRI, 8, 25, pctx);
        pctx.offerSector(new Sector(
                SECTOR_BASE_AGRI, SectorRole.AGRICULTURAL_FRINGE, BuildingZone.AGRICULTURAL,
                agriSlots, 6, true, new AddRing(16, 6), seg1EdgeId, null));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Finds the highest peak using {@link tterrag1112.life_in_the_village.Village.Planning.Features.FeatureMap}
     * cliff features. Returns the centre of the highest-topY cliff cluster,
     * or null if no cliff features are registered.
     */
    private static BlockPos findPeakFromFeatures(PlanContext pctx, BlockPos centre) {
        List<CliffFeature> cliffs = pctx.features.cliffFeatures();
        if (cliffs.isEmpty()) return null;
        CliffFeature best = null;
        int bestTopY = Integer.MIN_VALUE;
        for (CliffFeature cf : cliffs) {
            if (cf.topY() > bestTopY) {
                bestTopY = cf.topY();
                best = cf;
            }
        }
        if (best == null) return null;
        int cx = (best.ridgeFootprint().minX() + best.ridgeFootprint().maxX()) / 2;
        int cz = (best.ridgeFootprint().minZ() + best.ridgeFootprint().maxZ()) / 2;
        return new BlockPos(cx, bestTopY, cz);
    }

    /**
     * Fallback peak finder using the legacy {@link TerrainProfile} ridge/flat
     * candidate lists. Prefers the highest ridge midpoint, then the highest
     * flat candidate, then the village centre.
     */
    private static BlockPos findPeakLegacy(TerrainProfile terrain, BlockPos centre) {
        if (terrain.hasRidges()) {
            int bestY = Integer.MIN_VALUE;
            BlockPos best = null;
            for (TerrainAnalyzer.RidgeInfo r : terrain.ridges()) {
                int y = r.max().getY();
                if (y > bestY) {
                    bestY = y;
                    best = new BlockPos(
                            (r.min().getX() + r.max().getX()) / 2, y,
                            (r.min().getZ() + r.max().getZ()) / 2);
                }
            }
            if (best != null) return best;
        }
        return terrain.flatCandidates().stream()
                .max(Comparator.comparingInt(BlockPos::getY))
                .orElse(centre);
    }

    /**
     * Computes the gate/base position: projects from {@code peak} through
     * the village {@code centre} and extends outward by ring2 + 16 blocks.
     */
    private static BlockPos computeBasePosition(PlanContext pctx,
                                                BlockPos peak,
                                                BlockPos centre) {
        double dx = peak.getX() - centre.getX();
        double dz = peak.getZ() - centre.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        double downX, downZ;
        if (len < 1) { downX = 1; downZ = 0; }
        else         { downX = -dx / len; downZ = -dz / len; }
        int baseDist = pctx.density.getRing2Radius() + 16;
        return pctx.solidSurface(new BlockPos(
                peak.getX() + (int) Math.round(downX * baseDist),
                peak.getY(),
                peak.getZ() + (int) Math.round(downZ * baseDist)));
    }

    /**
     * Adds two {@link RoadPrimitive.Stairway} edges to the road graph:
     * {@code base → mid} and {@code mid → peak}. Returns
     * {@code {seg1EdgeId, seg2EdgeId}}.
     *
     * <p>Stairway Y is linearly interpolated, not surface-snapped, so the
     * stairs deliberately climb the hill rather than following the terrain
     * contour.
     */
    private static int[] buildSwitchbackChain(PlanContext pctx,
                                              BlockPos base,
                                              BlockPos mid,
                                              BlockPos peak) {
        int baseNodeId = pctx.layout.addNode(base, NodeKind.GATE,     RoadShape.RoadTier.VILLAGE_ROAD);
        int midNodeId  = pctx.layout.addNode(mid,  NodeKind.JUNCTION, RoadShape.RoadTier.VILLAGE_ROAD);
        int peakNodeId = pctx.layout.addNode(peak, NodeKind.FOCAL,    RoadShape.RoadTier.VILLAGE_ROAD);

        int seg1EdgeId = pctx.layout.addEdge(
                baseNodeId, midNodeId,
                new RoadPrimitive.Stairway(base, mid, RoadShape.RoadTier.VILLAGE_ROAD),
                pctx.level, pctx.worldSeed, EdgeRole.SPINE);
        int seg2EdgeId = pctx.layout.addEdge(
                midNodeId, peakNodeId,
                new RoadPrimitive.Stairway(mid, peak, RoadShape.RoadTier.VILLAGE_ROAD),
                pctx.level, pctx.worldSeed, EdgeRole.SPINE);

        return new int[]{seg1EdgeId, seg2EdgeId};
    }
}
