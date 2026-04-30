package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.StyleAutoDeriver;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.StyleSelection;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.VillageAgeCategoryHook;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.ShapeRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Rules.RuleContext;
import tterrag1112.life_in_the_village.Village.Planning.Rules.ShapeRule;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.*;

/**
 * Orchestrator for village layout planning.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>Analyse terrain</li>
 *   <li>Expand starter building list (resolve min/max counts)</li>
 *   <li>Build {@link LayoutDensityProfile} from village level</li>
 *   <li>Apply the type's rule stack to a fresh {@link RuleContext}</li>
 *   <li>Create a {@link PlanContext} wrapping a fresh {@link VillageLayout}</li>
 *   <li>Dispatch to a {@link ShapeRecipe} which composes primitives
 *       into the layout — roads, town square, building slots, gate endpoint</li>
 *   <li>Validate: every building must sit within
 *       {@code roadHalfWidth + footprintHalf + }{@link #VALIDATOR_ROAD_SLACK}
 *       blocks (Chebyshev) of a road centerline</li>
 *   <li>Run the orthogonal post-passes (farm plots, decoration clusters,
 *       Y clustering) and return the layout</li>
 * </ol>
 *
 * <h3>What's gone</h3>
 * The old cluster system, the TrunkGraph+TrunkRoadPlanner, the doubled
 * placement loop, {@code placeOneBuilding}, {@code placeCluster},
 * {@code refineRotations}, {@code placeWaterEdgeBuildings}, the capital
 * branch. All of it. Shape-specific logic lives in recipes now.
 */
public class VillagePlanner {

    private static final int FARM_PLOT_RADIUS = 10;
    private static final int DECORATION_RADIUS = 3;

    /**
     * Maximum Chebyshev distance allowed between a building's edge and the
     * nearest road centerline, in addition to the road's own reserved
     * half-width. This is wiggle room: it absorbs perturbation drift
     * (bounded by {@code PlacementSlot.maxDriftBlocks}, typically 6) plus a
     * small clearance gap. The full allowed centre-to-road distance is
     * {@code roadHalfWidth + footprintHalf + VALIDATOR_ROAD_SLACK}.
     *
     * <p>Replaces a hardcoded edge-distance limit that didn't scale with
     * footprint or perturbation bound. The slack value covers both — drift
     * up to 6 plus clearance — without admitting buildings that wandered
     * arbitrarily far from any road.
     */
    private static final int VALIDATOR_ROAD_SLACK = 6;

    /**
     * Conservative road half-width used by the validator. Matches
     * {@code RoadShape.RoadTier.VILLAGE_ROAD.reservedHalfWidth()} (3) which
     * is the value all road tiers actually reserve. Hardcoding here avoids
     * threading the per-edge tier through the validator just for this one
     * tolerance check; the formula is robust to small under-estimates.
     */
    private static final int VALIDATOR_ROAD_HALF_WIDTH = 3;

    /**
     * Slack for ring/floating slots whose committed building has no
     * feeding road (PlazaGenerator civic slots, agricultural fringes,
     * hull-floating clusters). The validator falls back to a hull-distance
     * check measured from village centre. The bound is
     * {@code ring2Radius + footprintHalf + VALIDATOR_HULL_SLACK}.
     */
    private static final int VALIDATOR_HULL_SLACK = 8;

    // =========================================================================
    // Entry point
    // =========================================================================

    public static Optional<VillageLayout> plan(
            ServerLevel level, BlockPos origin,
            VillageTypeData typeData, Random rng, int villageLevel) {

        // 1. Terrain analysis
        TerrainProfile terrain = TerrainAnalyzer.analyze(level, origin);
        if (!terrain.isSuitableFor(typeData.getTags())) {
            float typeSuit = TerrainProfile.computeSuitability(
                    terrain.flatRatio(), terrain.waterRatio(), terrain.steepRatio(),
                    typeData.getTags());
            String detail = String.format(
                    "suitability=%.2f type_suitability=%.2f (flat=%.2f water=%.2f steep=%.2f tree=%.2f) hasWater=%s hasRidges=%d",
                    terrain.suitability(), typeSuit,
                    terrain.flatRatio(),
                    terrain.waterRatio(),
                    terrain.steepRatio(),
                    terrain.treeRatio(),
                    terrain.hasWater(),
                    terrain.ridges().size());

            tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder.record(
                    tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder
                            .Reason.TERRAIN_UNSUITABLE,
                    detail, origin, typeData.getType());

            System.out.println("VillagePlanner: unsuitable terrain at " + origin + " — " + detail);
            return Optional.empty();
        }

        // 2. Expand buildings
        List<VillageTypeData.StarterBuilding> expanded =
                expandBuildingList(typeData.getStarterBuildings(), rng);
        if (expanded.isEmpty()) {
            System.out.println("VillagePlanner: no starter buildings for type "
                    + typeData.getType());
            PlacementFailureRecorder
                    .record(PlacementFailureRecorder.Reason.INSUFFICIENT_BUILDINGS,
                            "no starter buildings for type",
                            origin, typeData.getType());
            return Optional.empty();
        }

        // 3. Density profile
        LayoutDensityProfile density = LayoutDensityProfile.forLevel(villageLevel);
        StructureSizeCache sizeCache = new StructureSizeCache(level);

        // 4. Apply rule stack
        RuleContext ctx = new RuleContext(terrain, origin, rng.nextLong());
        for (ShapeRule rule : typeData.getShapeRules()) {
            try {
                rule.apply(ctx);
            } catch (Exception e) {
                System.out.println("VillagePlanner: rule '" + rule.typeName()
                        + "' failed: " + e.getMessage());
                PlacementFailureRecorder
                        .record(PlacementFailureRecorder.Reason.SHAPE_RULE_REJECTED,
                                "rule" + rule.typeName() +" failed:" + e.getMessage(),
                                origin, typeData.getType());
            }
        }

        // 5. Build the layout + plan context
        VillageLayout layout = new VillageLayout(terrain, density);
        BlockPos centre = resolveCentre(level, terrain, ctx);
        layout.setCenter(centre);

        // Remaining list is mutable; the recipe claims from it
        List<VillageTypeData.StarterBuilding> remaining = new ArrayList<>(expanded);

        long worldSeed = ctx.seed();

        // Phase 4: noise-only FeatureMap build. All terrain access flows
        // through FeatureSamplers so the result is deterministic from
        // (worldSeed, centre) regardless of chunk-load order. The map is
        // stashed on the layout so realization can call refine(level)
        // before any consumer reads from it.
        tterrag1112.life_in_the_village.Village.Planning.Features.FeatureMap features =
                tterrag1112.life_in_the_village.Village.Planning.Features.FeatureMap
                        .buildPlanning(centre, worldSeed, level, expanded);
        layout.setFeatures(features);

        PlanContext pctx = new PlanContext(
                level, layout, sizeCache, rng, ctx, density, worldSeed, remaining, features);

        // Variant context — derived once per village from the type
        // data + the resolved tier. The matcher reads this when
        // picking variants per slot (P0a-06).
        VillageSizeTier tier = VillageSizeTier.fromBuildingCount(expanded.size());
        StyleSelection styleSel = StyleAutoDeriver.resolve(typeData, tier);
        pctx.setVariantContext(typeData, styleSel, tier,
                VillageAgeCategoryHook.forNewVillage());

        // 6. Dispatch to the recipe
        try {
            ShapeRecipe.forShape(typeData.getShapeProfile().shapeType()).compose(pctx);
        } catch (Exception e) {
            System.out.println("VillagePlanner: recipe failed — " + e.getMessage());
            e.printStackTrace();
            PlacementFailureRecorder
                    .record(PlacementFailureRecorder.Reason.SHAPE_RULE_REJECTED,
                            "recipe failed",
                            origin, typeData.getType());
            return Optional.empty();
        }
        // Phase 9: snapshot the sectors a BaseRecipe emitted so /liv
        // layout debug show_sectors can render them. Empty for
        // unconverted recipes.
        layout.setDebugSectors(new java.util.ArrayList<>(pctx.offeredSectors()));
        pctx.runMatcher();

        if (layout.buildings().isEmpty()) {
            System.out.println("VillagePlanner: recipe produced no buildings");
            PlacementFailureRecorder
                    .record(PlacementFailureRecorder.Reason.INSUFFICIENT_BUILDINGS,
                            "recipe produced no buildings",
                            origin, typeData.getType());
            return Optional.empty();
        }

        // 7. Validate the plan. Phase 10 retired the fallbackPlaceRemaining
        // and rescueOrphans rescue passes — orphans now indicate a planner
        // bug and the village is rejected outright.
        if (!validatePlan(layout)) {
            System.out.println("VillagePlanner: plan validation failed");
            PlacementFailureRecorder
                    .record(PlacementFailureRecorder.Reason.INSUFFICIENT_BUILDINGS,
                            "plan validation failed",
                            origin, typeData.getType());
            return Optional.empty();
        }

        // Ensure town square has SOMETHING marked if recipe didn't.
        // Doc 04 §"Tier scaling" — the fallback uses HAMLET radius
        // (3) since this branch only runs when the recipe never
        // committed a plaza, which is a degenerate path that
        // shouldn't host larger tiers anyway.
        if (layout.getTownSquarePos() == null) {
            BlockPos sq = solidSurface(level, centre);
            layout.addForced(new LayoutSlot(
                    LayoutSlot.SlotType.DECORATION, sq,
                    tterrag1112.life_in_the_village.Village.Decoration
                            .TownSquare.TownSquareTier.RADIUS_HAMLET));
            layout.setTownSquarePos(sq);
        }

        // 8. Orthogonal post-passes
        clusterBuildingYLevels(layout, level);
        placeFarmPlots(layout, level, terrain, density, typeData, rng);
        placeDecorationClusters(layout, level, terrain, density, rng);

        System.out.println("VillagePlanner: planned "
                + typeData.getShapeProfile().shapeType()
                + " village — " + layout);
        return Optional.of(layout);
    }

    // =========================================================================
    // Validation
    // =========================================================================

    private static boolean validatePlan(VillageLayout layout) {
        List<LayoutSlot> buildings = layout.buildings();
        for (LayoutSlot slot : buildings) {
            if (!validateBuildingDistance(slot, layout)) return false;
        }
        return true;
    }

    /**
     * Validates a single building's distance to the road that fed its
     * placement slot. Two branches:
     *
     * <ul>
     *   <li><b>Has feeding road</b> — slot was emitted along a specific
     *       road's centerline. Measured distance is to <em>that</em> road
     *       only, not the nearest road in the graph. Fixes the legacy bug
     *       where civic buildings on plaza-tangent slots were measured
     *       across the plaza interior to a road on the opposite side.</li>
     *   <li><b>Ring/floating slot</b> — no feeding road (PlazaGenerator
     *       civic slot, agricultural fringe, hull-floating cluster).
     *       Falls back to a hull-distance check: the building must be
     *       within {@code ring2 + footprintHalf + VALIDATOR_HULL_SLACK}
     *       of the village centre.</li>
     * </ul>
     */
    private static boolean validateBuildingDistance(LayoutSlot slot,
                                                    VillageLayout layout) {
        BlockPos centre = slot.getPos();
        int footprintHalf = Math.max(slot.getFootprintWidth(),
                slot.getFootprintLength()) / 2;
        java.util.List<BlockPos> feedingRoad = slot.getFeedingRoad();

        if (feedingRoad != null && !feedingRoad.isEmpty()) {
            int distance = nearestPointChebyshev(centre, feedingRoad);
            int allowed = VALIDATOR_ROAD_HALF_WIDTH
                    + footprintHalf
                    + VALIDATOR_ROAD_SLACK;
            if (distance > allowed) {
                System.out.println("VillagePlanner: building " + slot.getBuildingType()
                        + " at " + centre + " is " + distance
                        + " blocks from feeding road (max " + allowed
                        + " for footprint " + slot.getFootprintWidth()
                        + "x" + slot.getFootprintLength()
                        + ", feedingRoad=" + feedingRoad.size() + " pts)");
                return false;
            }
            return true;
        }

        // Ring/floating slot — no feeding road. Hull-distance check.
        BlockPos villageCentre = layout.getCenter();
        int ring2 = layout.getDensity().getRing2Radius();
        int dx = centre.getX() - villageCentre.getX();
        int dz = centre.getZ() - villageCentre.getZ();
        int distance = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
        int allowed = ring2 + footprintHalf + VALIDATOR_HULL_SLACK;
        if (distance > allowed) {
            System.out.println("VillagePlanner: building " + slot.getBuildingType()
                    + " at " + centre + " is " + distance
                    + " blocks from village centre (max " + allowed
                    + " for footprint " + slot.getFootprintWidth()
                    + "x" + slot.getFootprintLength()
                    + ", feedingRoad=none ring/floating)");
            return false;
        }
        return true;
    }

    /** Chebyshev distance from {@code pos} to the nearest point in {@code points}. */
    private static int nearestPointChebyshev(BlockPos pos, java.util.List<BlockPos> points) {
        int best = Integer.MAX_VALUE;
        for (BlockPos p : points) {
            int dx = Math.abs(p.getX() - pos.getX());
            int dz = Math.abs(p.getZ() - pos.getZ());
            int cheb = Math.max(dx, dz);
            if (cheb < best) best = cheb;
        }
        return best;
    }

    // =========================================================================
    // Centre resolution
    // =========================================================================

    private static BlockPos resolveCentre(ServerLevel level, TerrainProfile terrain,
                                          RuleContext ctx) {
        BlockPos anchor = ctx.getAnchor("town_square");
        if (anchor != null) return solidSurface(level, anchor);
        return snapToFlat(level, terrain, terrain.origin());
    }

    private static BlockPos snapToFlat(ServerLevel level, TerrainProfile terrain,
                                       BlockPos origin) {
        return terrain.flatCandidates().stream()
                .filter(p -> p.distSqr(origin) <= 16 * 16)
                .min(Comparator.comparingDouble(p -> p.distSqr(origin)))
                .orElseGet(() -> solidSurface(level, origin));
    }

    private static BlockPos solidSurface(ServerLevel level, BlockPos pos) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getX(), pos.getZ());
        return new BlockPos(pos.getX(), y, pos.getZ());
    }

    // =========================================================================
    // Building list expansion (kept public — used by spawner)
    // =========================================================================

    public static List<VillageTypeData.StarterBuilding> expandBuildingList(
            List<VillageTypeData.StarterBuilding> starters, Random rng) {
        List<VillageTypeData.StarterBuilding> expanded = new ArrayList<>();
        for (VillageTypeData.StarterBuilding sb : starters) {
            int min = Math.max(1, sb.minCount());
            int max = Math.max(min, sb.maxCount());
            int count = min == max ? min : min + rng.nextInt(max - min + 1);
            for (int i = 0; i < count; i++) expanded.add(sb);
        }
        return expanded;
    }

    // =========================================================================
    // Post-passes — farm plots, decorations, Y clustering
    // =========================================================================

    private static void placeFarmPlots(VillageLayout layout, ServerLevel level,
                                       TerrainProfile terrain,
                                       LayoutDensityProfile density,
                                       VillageTypeData typeData, Random rng) {
        long farmhouseCount = typeData.getStarterBuildings().stream()
                .filter(sb -> sb.type().equals("FARMHOUSE"))
                .mapToInt(sb -> Math.max(1, sb.minCount()))
                .sum();
        if (farmhouseCount == 0) return;
        if (typeData.getShapeProfile().shapeType() == VillageTypeData.ShapeType.HILLTOP) return;

        int[] dir = flatDirVector(terrain.bestFlatDir());
        int perpX = dir[1], perpZ = -dir[0];
        int perimeterRadius = density.getRing2Radius() + 12;
        BlockPos centre = layout.getCenter();

        for (int i = 0; i < farmhouseCount; i++) {
            int sideOffset = (i - (int) farmhouseCount / 2) * (FARM_PLOT_RADIUS * 2 + 4);
            int tx = centre.getX()
                    + dir[0] * Math.max(density.getFarmOffset(), perimeterRadius)
                    + perpX * sideOffset;
            int tz = centre.getZ()
                    + dir[1] * Math.max(density.getFarmOffset(), perimeterRadius)
                    + perpZ * sideOffset;
            BlockPos ideal = terrain.bestFlatNear(
                    tx - terrain.origin().getX(),
                    tz - terrain.origin().getZ());
            BlockPos resolved = solidSurface(level, ideal);
            layout.tryAdd(new LayoutSlot(
                    LayoutSlot.SlotType.FARM_PLOT, resolved, FARM_PLOT_RADIUS));
        }
    }

    private static void placeDecorationClusters(VillageLayout layout,
                                                ServerLevel level,
                                                TerrainProfile terrain,
                                                LayoutDensityProfile density,
                                                Random rng) {
        int clusters = density.getDecorationClusters();
        int gapRadius = (density.getRing1Radius() + density.getRing2Radius()) / 2;
        BlockPos centre = layout.getCenter();
        for (int i = 0; i < clusters; i++) {
            double angle = (2 * Math.PI * i / clusters) + rng.nextDouble() * 0.8;
            int dx = (int)(Math.cos(angle) * gapRadius);
            int dz = (int)(Math.sin(angle) * gapRadius);
            BlockPos ideal = terrain.bestFlatNear(dx, dz);
            BlockPos resolved = solidSurface(level, ideal);
            layout.addForced(new LayoutSlot(
                    LayoutSlot.SlotType.DECORATION, resolved, DECORATION_RADIUS));
        }
    }

    private static void clusterBuildingYLevels(VillageLayout layout, ServerLevel level) {
        List<LayoutSlot> buildings = layout.buildings();
        for (LayoutSlot slot : buildings) {
            List<Integer> nearbyY = new ArrayList<>();
            for (LayoutSlot other : buildings) {
                if (other == slot) continue;
                int dx = Math.abs(slot.getPos().getX() - other.getPos().getX());
                int dz = Math.abs(slot.getPos().getZ() - other.getPos().getZ());
                if (Math.max(dx, dz) <= layout.getDensity().getRing2Radius()) {
                    nearbyY.add(other.getPos().getY());
                }
            }
            if (nearbyY.isEmpty()) continue;
            Collections.sort(nearbyY);
            int medianY = nearbyY.get(nearbyY.size() / 2);
            int diff = Math.abs(slot.getPos().getY() - medianY);
            if (diff > 0 && diff <= 6) {
                int actual = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        slot.getPos().getX(), slot.getPos().getZ());
                if (Math.abs(actual - medianY) <= 4) slot.snapY(medianY);
            }
        }
    }

    private static int[] flatDirVector(TerrainAnalyzer.FlatDirection dir) {
        return switch (dir) {
            case NORTH -> new int[]{0, -1};
            case SOUTH -> new int[]{0, 1};
            case EAST -> new int[]{1, 0};
            case WEST -> new int[]{-1, 0};
        };
    }
}