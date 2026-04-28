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
 *   <li>Validate: every building must be within {@link #MAX_BUILDING_TO_ROAD}
 *       blocks of a road centerline</li>
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
     * Maximum Chebyshev distance allowed between a building's centre and
     * the nearest road centerline. Buildings farther than this from any
     * road cause the plan to be rejected — a clear signal that the
     * recipe didn't produce enough spurs.
     */
    private static final int MAX_BUILDING_TO_ROAD = 6;

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
        PlanContext pctx = new PlanContext(
                level, layout, sizeCache, rng, ctx, density, worldSeed, remaining);

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
        pctx.runMatcher();

        if (layout.buildings().isEmpty()) {
            System.out.println("VillagePlanner: recipe produced no buildings");
            PlacementFailureRecorder
                    .record(PlacementFailureRecorder.Reason.INSUFFICIENT_BUILDINGS,
                            "recipe produced no buildings",
                            origin, typeData.getType());
            return Optional.empty();
        }

// 7a. Any buildings the recipe couldn't fit — try placing them
        // anywhere along the road network as a fallback.
        fallbackPlaceRemaining(pctx);
        // 7. Rescue orphan buildings with emergency spurs, then validate
        rescueOrphans(layout, level, ctx.seed());
        if (!validatePlan(layout)) {
            System.out.println("VillagePlanner: plan validation failed after rescue pass");
            PlacementFailureRecorder
                    .record(PlacementFailureRecorder.Reason.INSUFFICIENT_BUILDINGS,
                            "plan validation failed after rescue pass",
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
            BlockPos nearest = layout.nearestCenterlinePoint(slot.getPos());
            if (nearest == null) {
                System.out.println("VillagePlanner: building " + slot.getBuildingType()
                        + " at " + slot.getPos() + " — no roads in layout");
                return false;
            }
            int dx = Math.abs(nearest.getX() - slot.getPos().getX());
            int dz = Math.abs(nearest.getZ() - slot.getPos().getZ());
            int chebyshev = Math.max(dx, dz);
            // Account for building half-footprint — edge of building to road
            int edge = chebyshev - Math.max(slot.getFootprintWidth(),
                    slot.getFootprintLength()) / 2;
            if (edge > MAX_BUILDING_TO_ROAD) {
                System.out.println("VillagePlanner: building " + slot.getBuildingType()
                        + " at " + slot.getPos() + " is " + edge
                        + " blocks from nearest road (max " + MAX_BUILDING_TO_ROAD + ")");
                return false;
            }
        }
        return true;
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
    /**
     * For any building too far from a road, add a short StraightRoad
     * primitive from the nearest existing centerline point to the
     * building's entrance. Fixes the "adjacency relocated me miles
     * away" case that used to fail validation.
     */
    private static void rescueOrphans(VillageLayout layout,
                                      ServerLevel level, long worldSeed) {
        List<LayoutSlot> buildings = layout.buildings();
        for (LayoutSlot slot : buildings) {
            BlockPos nearest = layout.nearestCenterlinePoint(slot.getPos());
            if (nearest == null) continue;
            int dx = Math.abs(nearest.getX() - slot.getPos().getX());
            int dz = Math.abs(nearest.getZ() - slot.getPos().getZ());
            int edge = Math.max(dx, dz)
                    - Math.max(slot.getFootprintWidth(), slot.getFootprintLength()) / 2;
            if (edge <= MAX_BUILDING_TO_ROAD) continue;

            // Add a straight rescue road from nearest centerline to the
            // building. Uses the smallest tier so it doesn't look like
            // a main artery — just a footpath.
            BlockPos target = solidSurface(level, slot.getPos());
            BlockPos source = solidSurface(level, nearest);
            var rescue = new tterrag1112.life_in_the_village.Village.Planning
                    .Primitives.RoadPrimitive.StraightRoad(
                    source, target, 1.5,
                    tterrag1112.life_in_the_village.Village.Decoration
                            .Roads.RoadShape.RoadTier.FOOTPATH);
            layout.addRoad(rescue, level, worldSeed);
            System.out.println("VillagePlanner: rescue spur from " + source
                    + " to " + target + " for " + slot.getBuildingType());
        }
    }
    /**
     * Attempts to place any buildings the recipe couldn't fit into
     * their preferred zone. Walks each road's centerline and tries
     * offsets perpendicular to the road until a free spot is found.
     * This is the "just put it somewhere along a road" fallback
     * that catches buildings whose zone was full.
     */
    private static void fallbackPlaceRemaining(
            tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext pctx) {
        if (pctx.remaining.isEmpty()) return;

        System.out.println("VillagePlanner: fallback placing "
                + pctx.remaining.size() + " unfit buildings");

        List<VillageTypeData.StarterBuilding> toPlace =
                new ArrayList<>(pctx.remaining);
        pctx.remaining.clear();

        var centerlines = pctx.layout.getAllCenterlines();
        int[] perpOffsets = {8, -8, 12, -12, 16, -16, 20, -20};

        for (VillageTypeData.StarterBuilding sb : toPlace) {
            tterrag1112.life_in_the_village.Village.Buildings.BuildingType bt =
                    tterrag1112.life_in_the_village.Village.Planning.Primitives
                            .PlanContext.parseType(sb);
            if (bt == null) continue;

            LayoutSlot slot = null;
            outer:
            for (List<BlockPos> centerline : centerlines) {
                if (centerline.size() < 2) continue;
                // Sample every ~6 blocks along the road
                for (int i = 3; i < centerline.size(); i += 6) {
                    BlockPos on = centerline.get(i);
                    BlockPos prev = centerline.get(Math.max(0, i - 1));
                    BlockPos next = centerline.get(
                            Math.min(centerline.size() - 1, i + 1));
                    int headX = Integer.signum(next.getX() - prev.getX());
                    int headZ = Integer.signum(next.getZ() - prev.getZ());
                    int perpX = -headZ;
                    int perpZ = headX;
                    if (perpX == 0 && perpZ == 0) { perpX = 1; perpZ = 0; }

                    for (int d : perpOffsets) {
                        BlockPos target = on.offset(perpX * d, 0, perpZ * d);
                        slot = pctx.tryCommitBuilding(target, sb, bt, centerline);
                        if (slot != null) break outer;
                    }
                }
            }
            if (slot == null) {
                System.out.println("VillagePlanner: fallback could not place " + bt);
            }
        }
    }
}