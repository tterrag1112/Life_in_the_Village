package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaPurpose;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaShape;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.AnchorKind;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.Anchor;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.EdgeRef;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.LayoutBlueprint;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.PlazaDeclaration;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.RoadDeclaration;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.SectorDeclaration;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.SectorRef;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.SlotIntention;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.Features.CliffFeature;
import tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.BaseRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.SectorRole;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;
import tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase D: declarative port of HILLTOP — town hall on a peak with
 * concentric arc terraces below. Spec doc §7 has the worked example
 * for this recipe; this port follows it.
 *
 * <p>Pre-Phase-A pattern preserved:
 * <ul>
 *   <li>Peak detection via {@code FeatureMap.cliffFeatures()} +
 *       legacy ridge fallback (recipe-internal).</li>
 *   <li>{@code heightVariance < 12} or no peak → fallback to
 *       {@link PlazaRecipe} inline.</li>
 *   <li>Switchback main road: 2 Stairway segments
 *       (base → mid → peak), midpoint offset perpendicular.</li>
 *   <li>Plaza at peak (SQUARE).</li>
 *   <li>3 concentric 270° arc roads at radii {ring1, (ring1+ring2)/2,
 *       ring2}, centred on peak.</li>
 *   <li>Per-arc tiered tag sets (UPPER/MID/LOWER).</li>
 *   <li>NAMED_ANCHOR sector for TOWN_HALL via
 *       {@link Anchor.NamedAnchor}({@link AnchorKind#HIGH_GROUND}, 0).</li>
 *   <li>Base agri ring near base gate.</li>
 * </ul>
 *
 * <p>{@code pctx.allowRidgePlacement = true} preserved (recipe-
 * internal flag, gates ridge tolerance for slope-aware placement).
 *
 * <p>The NamedAnchor TOWN_HALL placement is the spec's structural
 * fix for HILLTOP's "76 blocks from feeding road" failure mode —
 * the resolver places the slot adjacent to the peak's nearest
 * centerline point, satisfying the validator-cap by construction.
 */
public final class HilltopRecipe extends BaseRecipe {

    private static final int MIN_VARIANCE = 12;
    private static final int UNLIMITED_CAPACITY = 1024;

    private static final Set<SlotTag> TAGS_PRIME_CIVIC = EnumSet.of(
            SlotTag.PRIME_CIVIC, SlotTag.HILLTOP_PEAK, SlotTag.HIGH_GROUND);
    private static final Set<SlotTag> TAGS_UPPER = EnumSet.of(
            SlotTag.HIGH_GROUND, SlotTag.CIVIC_ADJACENT,
            SlotTag.SECONDARY_CIVIC);
    private static final Set<SlotTag> TAGS_MID = EnumSet.of(
            SlotTag.HIGH_GROUND, SlotTag.RESIDENTIAL_CORE,
            SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_LOWER = EnumSet.of(
            SlotTag.RESIDENTIAL_OUTER, SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_AGRI = EnumSet.of(
            SlotTag.FIELD_EDGE, SlotTag.PASTURE);

    @Override
    public PlazaShape preferredPlazaShape() {
        return PlazaShape.SQUARE;
    }

    @Override
    public LayoutBlueprint compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();
        int totalBuildings = pctx.remaining.size();
        int ring1 = pctx.density.getRing1Radius();
        int ring2 = pctx.density.getRing2Radius();

        if (terrain.heightVariance() < MIN_VARIANCE) {
            return new PlazaRecipe().compose(pctx);
        }

        BlockPos peak = findPeakFromFeatures(pctx, centre);
        if (peak == null) {
            peak = findPeakLegacy(terrain, centre);
        }
        if (peak == null) {
            return new PlazaRecipe().compose(pctx);
        }

        BlockPos peakPos = pctx.solidSurface(peak);
        BlockPos baseGate = computeBasePosition(pctx, peakPos, centre);

        // Recipe-internal flag (allows ridge placement).
        pctx.allowRidgePlacement = true;

        // Hill geometry: downhill direction, perpendicular side
        // direction.
        double peakDx = peakPos.getX() - centre.getX();
        double peakDz = peakPos.getZ() - centre.getZ();
        double peakLen = Math.sqrt(peakDx * peakDx + peakDz * peakDz);
        double downX, downZ;
        if (peakLen < 1) { downX = 1; downZ = 0; }
        else { downX = -peakDx / peakLen; downZ = -peakDz / peakLen; }
        double sideX = -downZ;
        double sideZ = downX;

        // Switchback midpoint: between peak and base, offset
        // perpendicular by ring1+4.
        int switchOffset = ring1 + 4;
        BlockPos midpoint = pctx.solidSurface(new BlockPos(
                (peakPos.getX() + baseGate.getX()) / 2
                        + (int) Math.round(sideX * switchOffset),
                peakPos.getY(),
                (peakPos.getZ() + baseGate.getZ()) / 2
                        + (int) Math.round(sideZ * switchOffset)));

        EdgeRef seg1Ref = EdgeRef.of("hilltop_seg1");
        EdgeRef seg2Ref = EdgeRef.of("hilltop_seg2");
        EdgeRef upperArcRef = EdgeRef.of("hilltop_upper_arc");
        EdgeRef midArcRef = EdgeRef.of("hilltop_mid_arc");
        EdgeRef lowerArcRef = EdgeRef.of("hilltop_lower_arc");

        // 3 arc roads: 270° spans centred opposite the gate so the
        // gate-side is clear.
        double upRad = Math.atan2(-downZ, -downX);
        double arcSpan = Math.toRadians(270);
        double arcStartAngle = upRad - arcSpan / 2;
        int rMid = ring1 + (ring2 - ring1) / 2;

        List<RoadDeclaration> roads = List.of(
                new RoadDeclaration(seg1Ref,
                        EdgeRole.SPINE, RoadShape.RoadTier.VILLAGE_ROAD,
                        new RoadPrimitive.Stairway(
                                baseGate, midpoint,
                                RoadShape.RoadTier.VILLAGE_ROAD)),
                new RoadDeclaration(seg2Ref,
                        EdgeRole.SPINE, RoadShape.RoadTier.VILLAGE_ROAD,
                        new RoadPrimitive.Stairway(
                                midpoint, peakPos,
                                RoadShape.RoadTier.VILLAGE_ROAD)),
                new RoadDeclaration(upperArcRef,
                        EdgeRole.RING, RoadShape.RoadTier.VILLAGE_PATH,
                        new RoadPrimitive.Arc(
                                peakPos, ring1, arcStartAngle, arcSpan,
                                3.0, RoadShape.RoadTier.VILLAGE_PATH)),
                new RoadDeclaration(midArcRef,
                        EdgeRole.RING, RoadShape.RoadTier.VILLAGE_PATH,
                        new RoadPrimitive.Arc(
                                peakPos, rMid, arcStartAngle, arcSpan,
                                3.0, RoadShape.RoadTier.VILLAGE_PATH)),
                new RoadDeclaration(lowerArcRef,
                        EdgeRole.RING, RoadShape.RoadTier.VILLAGE_PATH,
                        new RoadPrimitive.Arc(
                                peakPos, ring2, arcStartAngle, arcSpan,
                                3.0, RoadShape.RoadTier.VILLAGE_PATH)));

        SectorRef civicSec = SectorRef.of("hilltop_civic");
        var plaza = new PlazaDeclaration(peakPos, ring1 / 2,
                PlazaShape.SQUARE, PlazaPurpose.CIVIC, civicSec);

        int civicCap = Math.max(2, Math.min(4, totalBuildings / 6 + 1));
        int midCap = Math.max(4, totalBuildings / 4);

        List<SectorDeclaration> sectors = List.of(
                new SectorDeclaration("hilltop_civic",
                        SectorRole.CIVIC_TIGHT, BuildingZone.CIVIC,
                        civicCap, 32, null),
                new SectorDeclaration("hilltop_peak_anchor",
                        SectorRole.NAMED_ANCHOR, BuildingZone.CIVIC,
                        1, 32, seg2Ref),
                new SectorDeclaration("hilltop_upper_arc",
                        SectorRole.CIVIC_RING, BuildingZone.CIVIC,
                        civicCap, 16, upperArcRef),
                new SectorDeclaration("hilltop_mid_arc",
                        SectorRole.RESIDENTIAL_CLUSTER,
                        BuildingZone.RESIDENTIAL,
                        midCap, 16, midArcRef),
                new SectorDeclaration("hilltop_lower_arc",
                        SectorRole.RESIDENTIAL_INFILL,
                        BuildingZone.RESIDENTIAL,
                        midCap, 16, lowerArcRef),
                new SectorDeclaration("hilltop_base_agri",
                        SectorRole.AGRICULTURAL_FRINGE,
                        BuildingZone.AGRICULTURAL,
                        UNLIMITED_CAPACITY, 18, seg1Ref));

        List<SlotIntention> intentions = List.of(
                // Plaza perimeter civic.
                new SlotIntention(civicSec, TAGS_PRIME_CIVIC,
                        1, 32, new Anchor.PlazaPerimeter(civicSec, 4)),
                // Spec §7 worked example: TOWN_HALL via NamedAnchor.
                // Resolver places slot adjacent to peak's feeding-road
                // nearest centerline point (seg2 = mid→peak Stairway).
                new SlotIntention(SectorRef.of("hilltop_peak_anchor"),
                        TAGS_PRIME_CIVIC, 1, 32,
                        new Anchor.NamedAnchor(AnchorKind.HIGH_GROUND, 0)),
                // Per-arc residential.
                new SlotIntention(SectorRef.of("hilltop_upper_arc"),
                        TAGS_UPPER, civicCap, 16,
                        new Anchor.RoadAlong(upperArcRef, 6, true)),
                new SlotIntention(SectorRef.of("hilltop_mid_arc"),
                        TAGS_MID, midCap, 16,
                        new Anchor.RoadAlong(midArcRef, 6, true)),
                new SlotIntention(SectorRef.of("hilltop_lower_arc"),
                        TAGS_LOWER, midCap, 16,
                        new Anchor.RoadAlong(lowerArcRef, 6, true)),
                // Base agri at the bottom of the hill — RingValidated
                // around village centre with radius approximating the
                // base gate's distance from centre.
                new SlotIntention(SectorRef.of("hilltop_base_agri"),
                        TAGS_AGRI, 6, 18,
                        new Anchor.RingValidated(
                                ring2 + 16, 0.0)));

        Map<AnchorKind, BlockPos> namedAnchors = new LinkedHashMap<>();
        namedAnchors.put(AnchorKind.TOWN_SQUARE, peakPos);
        namedAnchors.put(AnchorKind.HIGH_GROUND, peakPos);
        namedAnchors.put(AnchorKind.MAIN_GATE, baseGate);
        namedAnchors.put(AnchorKind.KEEP, peakPos);

        return new LayoutBlueprint(
                ShapeType.HILLTOP, roads,
                List.of(plaza), sectors, intentions, namedAnchors);
    }

    @Override
    public LayoutBlueprint reEmit(ReEmitReason reason, PlanContext pctx) {
        return switch (reason) {
            // No axis rotation — peak position is terrain-determined.
            case ReEmitReason.SevereTruncation t -> null;
            case ReEmitReason.SlotsDropped s -> null;
            case ReEmitReason.SectorStarved s -> null;
            case ReEmitReason.ValidationFailed v -> null;
        };
    }

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

    private static BlockPos computeBasePosition(PlanContext pctx,
                                                BlockPos peak,
                                                BlockPos centre) {
        double dx = peak.getX() - centre.getX();
        double dz = peak.getZ() - centre.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        double downX, downZ;
        if (len < 1) { downX = 1; downZ = 0; }
        else { downX = -dx / len; downZ = -dz / len; }
        int baseDist = pctx.density.getRing2Radius() + 16;
        return pctx.solidSurface(new BlockPos(
                peak.getX() + (int) Math.round(downX * baseDist),
                peak.getY(),
                peak.getZ() + (int) Math.round(downZ * baseDist)));
    }
}
