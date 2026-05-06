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
import tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.BaseRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.SectorRole;
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
 * Phase D: declarative port of CLUSTERED — 3–5 separate building
 * clumps connected by winding footpaths.
 *
 * <p>Pre-Phase-A stochastic cluster-centre picking preserved
 * ({@code pctx.rng} drives angle + radius). MST-style footpath
 * connections preserved. Gate road outward from the main cluster
 * preserved.
 *
 * <p>Cluster slot emission uses {@link Anchor.RegionalGather}
 * (poisson-disc free placement within a circular region around
 * each cluster centre). Pre-Phase-A used
 * {@code BuildingCircle.SCATTER} which produced similar
 * non-road-anchored placements; the resolver's poisson-disc
 * rejection sampling is the closest declarative analogue.
 *
 * <p>Quirk-bundle items:
 * <ul>
 *   <li>Terrain-too-rough fallback: pre-Phase-A delegated to
 *       {@code new RadialRecipe().compose(pctx)} inline. New model:
 *       mark unplannable + empty blueprint. Loss surfaces if
 *       measurement reveals.</li>
 *   <li>Cluster-pick failure (no viable cluster centre after 8
 *       attempts each): same fallback path.</li>
 * </ul>
 */
public final class ClusteredRecipe extends BaseRecipe {

    private static final int MIN_CLUSTER_SPACING = 60;
    private static final int CLUSTER_REGION_RADIUS = 16;
    private static final int CLUSTER_MIN_SPACING = 6;

    private static final Set<SlotTag> TAGS_PRIME_CIVIC =
            EnumSet.of(SlotTag.PRIME_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_MAIN_CLUSTER = EnumSet.of(
            SlotTag.CIVIC_ADJACENT, SlotTag.SECONDARY_CIVIC,
            SlotTag.RESIDENTIAL_CORE, SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_CLUSTER = EnumSet.of(
            SlotTag.RESIDENTIAL_INFILL, SlotTag.PRODUCTION_INFILL,
            SlotTag.ROAD_ADJACENT, SlotTag.BACKFILL);

    @Override
    public LayoutBlueprint compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        var terrain = pctx.layout.getTerrain();
        int totalBuildings = pctx.remaining.size();

        // Terrain pre-check (pre-Phase-A behavior).
        int largest = RecipeHelpers.largestRotatedFootprint(pctx);
        int requiredFlatSide = largest + 4;
        int available = terrain.largestFlatPatchAvailable(2);
        if (available < requiredFlatSide) {
            pctx.layout.markUnplannable(
                    "CLUSTERED terrain too rough — needs >="
                    + requiredFlatSide + " flat side, available=" + available);
            return LayoutBlueprint.empty(ShapeType.CLUSTERED);
        }

        int clusterCount = Math.max(3, Math.min(5, totalBuildings / 4));

        // Pick cluster centres — same RNG sequence as pre-Phase-A
        // for byte-equality on the ones that do coincide.
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
                        centre.getX() + (int) (Math.cos(angle) * r),
                        centre.getY(),
                        centre.getZ() + (int) (Math.sin(angle) * r)));
                boolean tooClose = false;
                for (BlockPos other : clusterCentres) {
                    if (candidate.distSqr(other)
                            < MIN_CLUSTER_SPACING * MIN_CLUSTER_SPACING) {
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

        if (clusterCentres.isEmpty()) {
            pctx.layout.markUnplannable(
                    "CLUSTERED failed to pick any cluster centres "
                    + "(spacing=" + MIN_CLUSTER_SPACING + ")");
            return LayoutBlueprint.empty(ShapeType.CLUSTERED);
        }

        // MST-style footpath connections between cluster centres.
        List<RoadDeclaration> roads = new ArrayList<>();
        List<BlockPos> connected = new ArrayList<>();
        connected.add(clusterCentres.get(0));
        for (int i = 1; i < clusterCentres.size(); i++) {
            BlockPos next = clusterCentres.get(i);
            BlockPos nearest = connected.stream()
                    .min(Comparator.comparingDouble(p -> p.distSqr(next)))
                    .orElse(connected.get(0));
            EdgeRef pathRef = EdgeRef.of("clustered_path_" + i);
            roads.add(new RoadDeclaration(pathRef,
                    EdgeRole.SPUR, RoadShape.RoadTier.FOOTPATH,
                    new RoadPrimitive.StraightRoad(
                            nearest, next, 4.0, RoadShape.RoadTier.FOOTPATH)));
            connected.add(next);
        }

        // Gate road outward from main cluster (away from centroid of
        // other clusters).
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
                main.getX() + (int) (Math.cos(awayAngle) * gateRunLen),
                main.getY(),
                main.getZ() + (int) (Math.sin(awayAngle) * gateRunLen)));
        EdgeRef gateRef = EdgeRef.of("clustered_gate");
        roads.add(new RoadDeclaration(gateRef,
                EdgeRole.SPINE, RoadShape.RoadTier.VILLAGE_PATH,
                new RoadPrimitive.StraightRoad(
                        main, gateEnd, 6.0, RoadShape.RoadTier.VILLAGE_PATH)));

        // Plaza at main cluster.
        SectorRef civicSec = SectorRef.of("plaza_civic");
        var plaza = new PlazaDeclaration(main,
                pctx.density.getRing1Radius(),
                PlazaShape.IRREGULAR, PlazaPurpose.CIVIC, civicSec);

        // Sectors: one per cluster + civic.
        int perCluster = Math.max(3, totalBuildings / clusterCentres.size());
        List<SectorDeclaration> sectors = new ArrayList<>();
        sectors.add(new SectorDeclaration("plaza_civic",
                SectorRole.CIVIC_TIGHT, BuildingZone.CIVIC,
                3, 46, null));
        for (int i = 0; i < clusterCentres.size(); i++) {
            boolean isMain = (i == 0);
            sectors.add(new SectorDeclaration(
                    "clustered_cluster_" + i,
                    isMain ? SectorRole.CIVIC_TIGHT
                            : SectorRole.RESIDENTIAL_CLUSTER,
                    isMain ? BuildingZone.CIVIC : BuildingZone.RESIDENTIAL,
                    isMain ? 6 : 4,
                    isMain ? 32 : 16,
                    null));
        }

        // Intentions: civic + per-cluster RegionalGather.
        List<SlotIntention> intentions = new ArrayList<>();
        intentions.add(new SlotIntention(civicSec, TAGS_PRIME_CIVIC,
                1, 46, new Anchor.PlazaPerimeter(civicSec, 6)));
        for (int i = 0; i < clusterCentres.size(); i++) {
            boolean isMain = (i == 0);
            intentions.add(new SlotIntention(
                    SectorRef.of("clustered_cluster_" + i),
                    isMain ? TAGS_MAIN_CLUSTER : TAGS_CLUSTER,
                    perCluster,
                    isMain ? 32 : 16,
                    new Anchor.RegionalGather(
                            clusterCentres.get(i),
                            CLUSTER_REGION_RADIUS,
                            CLUSTER_MIN_SPACING)));
        }

        Map<AnchorKind, BlockPos> namedAnchors = new LinkedHashMap<>();
        namedAnchors.put(AnchorKind.TOWN_SQUARE, main);
        namedAnchors.put(AnchorKind.MAIN_GATE, gateEnd);

        return new LayoutBlueprint(
                ShapeType.CLUSTERED,
                roads,
                List.of(plaza),
                sectors,
                intentions,
                namedAnchors);
    }

    @Override
    public LayoutBlueprint reEmit(ReEmitReason reason, PlanContext pctx) {
        return switch (reason) {
            case ReEmitReason.SevereTruncation t -> null;
            case ReEmitReason.SlotsDropped s -> null;
            case ReEmitReason.SectorStarved s -> null;
            case ReEmitReason.ValidationFailed v -> null;
        };
    }
}
