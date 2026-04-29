// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/RecipeHelpers.java
package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaGenerator;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaPurpose;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaRegion;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaShape;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaSpec;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.VillageCenterMarker;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.LayoutSlot;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.LayoutPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Shared building blocks for ShapeRecipes. Most recipes follow the
 * same pattern: claim buildings by zone, distribute them across
 * branch points along a road, place each bucket as a SCATTER cluster
 * at the end of a stub spur. These helpers factor out that logic so
 * recipes can focus on their layout's distinctive geometry instead
 * of repeating boilerplate.
 *
 * <p>Nothing here is stateful. All methods take a {@link PlanContext}
 * and operate on it directly. Helpers are organized by what they do:
 * claim/distribute, build stub spurs, place buckets, rescue critical
 * buildings, and a few small math/conversion utilities.
 */
public final class RecipeHelpers {
    private RecipeHelpers() {}

    // =========================================================================
    // Direction conversion
    // =========================================================================

    /** FlatDirection → math-convention angle in radians. */
    public static double directionRadOf(TerrainAnalyzer.FlatDirection dir) {
        return switch (dir) {
            case EAST  -> 0;
            case SOUTH -> Math.PI / 2;
            case WEST  -> Math.PI;
            case NORTH -> -Math.PI / 2;
        };
    }

    /** Shortest signed angular difference between two angles in radians. */
    public static double angleDelta(double a, double b) {
        double d = (a - b) % (2 * Math.PI);
        if (d > Math.PI) d -= 2 * Math.PI;
        if (d < -Math.PI) d += 2 * Math.PI;
        return d;
    }

    // =========================================================================
    // Bucket distribution
    // =========================================================================

    /**
     * Claims production + residential buildings from {@code remaining}
     * and distributes them round-robin across {@code bucketCount} buckets.
     * Production is distributed first so production-heavy buckets bias
     * toward earlier indices.
     */
    public static List<List<VillageTypeData.StarterBuilding>> claimAndBucketProdResidential(
            PlanContext pctx, int bucketCount) {
        List<VillageTypeData.StarterBuilding> production =
                pctx.claimByZone(BuildingZone.PRODUCTION, 1000);
        List<VillageTypeData.StarterBuilding> residential =
                pctx.claimByZone(BuildingZone.RESIDENTIAL, 1000);

        List<List<VillageTypeData.StarterBuilding>> buckets = new ArrayList<>();
        for (int i = 0; i < bucketCount; i++) buckets.add(new ArrayList<>());

        int b = 0;
        for (VillageTypeData.StarterBuilding sb : production) {
            buckets.get(b++ % bucketCount).add(sb);
        }
        for (VillageTypeData.StarterBuilding sb : residential) {
            buckets.get(b++ % bucketCount).add(sb);
        }
        return buckets;
    }

    /**
     * Interleaves production and residential into a single ordered list.
     * Used by recipes that don't bucket — they place each building on a
     * single sequence of branch points (LINEAR, CHAIN, ROADSIDE, etc.)
     * so visual variety along the road comes from interleaving rather
     * than from spatial grouping.
     */
    public static List<VillageTypeData.StarterBuilding> claimAndInterleaveProdResidential(
            PlanContext pctx) {
        List<VillageTypeData.StarterBuilding> production =
                pctx.claimByZone(BuildingZone.PRODUCTION, 1000);
        List<VillageTypeData.StarterBuilding> residential =
                pctx.claimByZone(BuildingZone.RESIDENTIAL, 1000);

        List<VillageTypeData.StarterBuilding> out = new ArrayList<>();
        int p = 0, r = 0;
        while (p < production.size() || r < residential.size()) {
            if (p < production.size()) out.add(production.get(p++));
            if (r < residential.size()) out.add(residential.get(r++));
        }
        return out;
    }

    // =========================================================================
    // Stub-spur-along-road pattern
    // =========================================================================

    /**
     * Branch point selection along a road centerline. Returns indices
     * into the centerline at the given fractions, clamped to valid range.
     * Avoids the first and last few points so spurs don't bunch at
     * road endpoints.
     */
    public static List<Integer> branchIndicesAlong(List<BlockPos> centerline, double[] fractions) {
        List<Integer> out = new ArrayList<>();
        if (centerline.size() < 4) return out;
        int usableStart = 4;
        int usableEnd = centerline.size() - 4;
        int range = usableEnd - usableStart;
        if (range <= 0) return out;
        for (double f : fractions) {
            int idx = usableStart + (int)(range * f);
            out.add(Math.max(usableStart, Math.min(usableEnd, idx)));
        }
        return out;
    }

    /**
     * Branch indices avoiding a margin around the road's midpoint.
     * Used by recipes that have a town square at the midpoint and
     * don't want stubs landing inside the square's reserved area.
     * Half the indices fall on each side of the midpoint.
     */
    public static List<Integer> branchIndicesAvoidingMidpoint(
            List<BlockPos> centerline, int count) {
        List<Integer> out = new ArrayList<>();
        if (centerline.size() < 8 || count <= 0) return out;
        int squareIdx = centerline.size() / 2;
        int squareMargin = Math.max(8, centerline.size() / 8);
        int usableStart = 4;
        int usableEnd = centerline.size() - 4;
        int leftCount = count / 2;
        int rightCount = count - leftCount;

        for (int i = 0; i < leftCount; i++) {
            int range = (squareIdx - squareMargin) - usableStart;
            if (range <= 0) break;
            int idx = usableStart + (range * i) / Math.max(1, leftCount);
            out.add(idx);
        }
        for (int i = 0; i < rightCount; i++) {
            int range = usableEnd - (squareIdx + squareMargin);
            if (range <= 0) break;
            int idx = (squareIdx + squareMargin) + (range * i) / Math.max(1, rightCount);
            out.add(idx);
        }
        return out;
    }

    /**
     * Tangent direction at a centerline point, computed from neighbours.
     * Returns angle in radians (math convention). Used for stub spurs
     * that need to be perpendicular to the LOCAL road direction rather
     * than the global chord direction — important for curved roads.
     */
    public static double localTangentRad(List<BlockPos> centerline, int idx) {
        int prev = Math.max(0, idx - 1);
        int next = Math.min(centerline.size() - 1, idx + 1);
        BlockPos a = centerline.get(prev);
        BlockPos b = centerline.get(next);
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        if (Math.abs(dx) + Math.abs(dz) < 1) return 0;
        return Math.atan2(dz, dx);
    }

    /**
     * Builds a single stub spur perpendicular to its parent road and
     * places a single building at the spur's endpoint as a SCATTER
     * cluster of size 1. Returns the stub centerline so the caller
     * can add it to its allRoadsForSnap list.
     *
     * @param parentRoad the road the stub branches off
     * @param branchIdx index into parentRoad to branch from
     * @param building the single building to place at the stub end
     * @param leftSide true to put the stub on the left side of the
     *                 road heading, false for the right
     * @param stubLength approximate length of the stub in blocks
     * @param tier road tier for the stub
     */
    public static List<BlockPos> stubSpurWithBuilding(
            PlanContext pctx,
            List<BlockPos> parentRoad,
            int branchIdx,
            VillageTypeData.StarterBuilding building,
            boolean leftSide,
            int stubLength,
            RoadShape.RoadTier tier) {
        if (parentRoad.isEmpty() || branchIdx < 0 || branchIdx >= parentRoad.size()) {
            return List.of();
        }
        BlockPos branchHint = parentRoad.get(branchIdx);
        double tangent = localTangentRad(parentRoad, branchIdx);
        double stubAngle = tangent + (leftSide ? -Math.PI / 2 : Math.PI / 2);
        // Small jitter so adjacent stubs aren't laser-perpendicular
        stubAngle += (pctx.rng.nextDouble() - 0.5) * 0.3;

        RoadPrimitive.Spur stub = new RoadPrimitive.Spur(
                parentRoad, branchHint, stubAngle, stubLength,
                1.5, tier);
        List<BlockPos> stubLine = pctx.layout.addRoad(stub, pctx.level, pctx.worldSeed);

        if (!stubLine.isEmpty()) {
            BlockPos focal = stubLine.get(stubLine.size() - 1);
            new LayoutPrimitive.BuildingCircle(
                    focal,
                    LayoutPrimitive.BuildingCircle.Mode.SCATTER,
                    List.of(building),
                    stubLine
            ).place(pctx);
        }
        return stubLine;
    }

    /**
     * Distributes a list of buildings as alternating-side stub spurs
     * along a parent road. Branch points avoid a margin around the
     * road midpoint (for recipes with a square at the midpoint).
     * Returns the list of stub centerlines for adding to road snap lists.
     *
     * @param sidePolicy how to choose sides for each stub. SEE javadoc
     *                   on {@link SidePolicy}.
     */
    public static List<List<BlockPos>> stubSpursAlongRoad(
            PlanContext pctx,
            List<BlockPos> parentRoad,
            List<VillageTypeData.StarterBuilding> buildings,
            SidePolicy sidePolicy,
            int stubLengthMin,
            int stubLengthVariance,
            RoadShape.RoadTier tier) {
        List<List<BlockPos>> out = new ArrayList<>();
        if (buildings.isEmpty()) return out;

        List<Integer> branchIndices = branchIndicesAvoidingMidpoint(parentRoad, buildings.size());
        for (int i = 0; i < buildings.size() && i < branchIndices.size(); i++) {
            int idx = branchIndices.get(i);
            boolean leftSide = sidePolicy.sideFor(i);
            int stubLen = stubLengthMin + (stubLengthVariance > 0
                    ? pctx.rng.nextInt(stubLengthVariance) : 0);
            List<BlockPos> stub = stubSpurWithBuilding(
                    pctx, parentRoad, idx, buildings.get(i),
                    leftSide, stubLen, tier);
            if (!stub.isEmpty()) out.add(stub);
        }
        // Push any leftovers (more buildings than branch points) back
        // to remaining for the planner's fallback passes.
        for (int i = branchIndices.size(); i < buildings.size(); i++) {
            pctx.remaining.add(buildings.get(i));
        }
        return out;
    }

    /**
     * Side selection policy for {@link #stubSpursAlongRoad}.
     */
    @FunctionalInterface
    public interface SidePolicy {
        boolean sideFor(int stubIndex);

        /** Alternates left, right, left, right, ... */
        SidePolicy ALTERNATING = i -> (i & 1) == 0;
        /** Always left. Used by ROADSIDE for one-side bias. */
        SidePolicy ALWAYS_LEFT = i -> true;
        /** Always right. Mirror of ALWAYS_LEFT. */
        SidePolicy ALWAYS_RIGHT = i -> false;
    }

    // =========================================================================
    // Town hall rescue
    // =========================================================================

    /**
     * If the town hall is in {@code remaining}, tries to place it on
     * the given road at a fraction near the start (default 0.16).
     * Used by recipes after their main spur loops to ensure the town
     * hall doesn't fall through to the planner's last-resort passes.
     *
     * @return true if the town hall was successfully placed, false
     *         if it remains in {@code remaining} for downstream rescue
     */
    public static boolean rescueTownHallOnRoad(PlanContext pctx, List<BlockPos> road) {
        VillageTypeData.StarterBuilding townHall = null;
        Iterator<VillageTypeData.StarterBuilding> it = pctx.remaining.iterator();
        while (it.hasNext()) {
            VillageTypeData.StarterBuilding sb = it.next();
            if ("TOWN_HALL".equals(sb.type())) {
                townHall = sb;
                it.remove();
                break;
            }
        }
        if (townHall == null) return false;

        BuildingType bt = PlanContext.parseType(townHall);
        if (bt == null) return false;

        if (road.size() < 4) {
            pctx.remaining.add(townHall);
            return false;
        }
        int idx = Math.max(2, road.size() / 6);
        BlockPos branchHint = road.get(idx);
        LayoutSlot placed = pctx.tryCommitWithRetries(branchHint, townHall, bt, road, 16);
        if (placed != null) {
            System.out.println("RecipeHelpers: rescued town hall to " + placed.getPos());
            return true;
        }
        pctx.remaining.add(townHall);
        return false;
    }

    /** Same as {@link #rescueTownHallOnRoad} but tries multiple roads in order. */
    public static boolean rescueTownHallOnAnyRoad(PlanContext pctx, List<List<BlockPos>> roads) {
        VillageTypeData.StarterBuilding townHall = null;
        Iterator<VillageTypeData.StarterBuilding> it = pctx.remaining.iterator();
        while (it.hasNext()) {
            VillageTypeData.StarterBuilding sb = it.next();
            if ("TOWN_HALL".equals(sb.type())) {
                townHall = sb;
                it.remove();
                break;
            }
        }
        if (townHall == null) return false;
        BuildingType bt = PlanContext.parseType(townHall);
        if (bt == null) return false;

        for (List<BlockPos> road : roads) {
            if (road.size() < 4) continue;
            int idx = Math.max(2, road.size() / 6);
            BlockPos branchHint = road.get(idx);
            LayoutSlot placed = pctx.tryCommitWithRetries(branchHint, townHall, bt, road, 16);
            if (placed != null) {
                System.out.println("RecipeHelpers: rescued town hall to " + placed.getPos());
                return true;
            }
        }
        pctx.remaining.add(townHall);
        return false;
    }

    // =========================================================================
    // Bucket placement at a focal point
    // =========================================================================

    /**
     * Places a bucket of buildings as a SCATTER cluster at the end of
     * a road, with the road as the feeding road for hemisphere bias.
     * Convenience for the very common "spur ends in a clump" pattern.
     */
    public static void scatterBucketAtRoadEnd(
            PlanContext pctx,
            List<BlockPos> road,
            List<VillageTypeData.StarterBuilding> bucket) {
        if (road.isEmpty() || bucket.isEmpty()) return;
        BlockPos focal = road.get(road.size() - 1);
        new LayoutPrimitive.BuildingCircle(
                focal,
                LayoutPrimitive.BuildingCircle.Mode.SCATTER,
                bucket,
                road
        ).place(pctx);
    }

    /**
     * Places a bucket of buildings as a SCATTER cluster at an arbitrary
     * focal point with a road for facing-direction context.
     */
    public static void scatterBucketAt(
            PlanContext pctx,
            BlockPos focal,
            List<VillageTypeData.StarterBuilding> bucket,
            List<BlockPos> facingRoad) {
        if (bucket.isEmpty()) return;
        new LayoutPrimitive.BuildingCircle(
                pctx.solidSurface(focal),
                LayoutPrimitive.BuildingCircle.Mode.SCATTER,
                bucket,
                facingRoad
        ).place(pctx);
    }

    // =========================================================================
    // Outer ring placement (agri/defensive/stragglers)
    // =========================================================================

    /**
     * Claims agricultural buildings and places them in a RingBand
     * outside the village core. Skips quietly if there are no agricultural
     * buildings to place.
     */
    public static void placeAgriculturalRing(
            PlanContext pctx,
            BlockPos centre,
            int innerOffset,
            int outerOffset,
            List<List<BlockPos>> snapRoads) {
        List<VillageTypeData.StarterBuilding> agri =
                pctx.claimByZone(BuildingZone.AGRICULTURAL, 1000);
        if (agri.isEmpty()) return;
        new LayoutPrimitive.RingBand(
                centre,
                pctx.density.getRing2Radius() + innerOffset,
                pctx.density.getRing2Radius() + outerOffset,
                BuildingZone.AGRICULTURAL, agri, snapRoads
        ).place(pctx);
    }

    /**
     * Claims defensive buildings and places them in a RingBand on the
     * village perimeter. Skips quietly if there are no defensive buildings.
     */
    public static void placeDefensiveRing(
            PlanContext pctx,
            BlockPos centre,
            int innerOffset,
            int outerOffset,
            List<List<BlockPos>> snapRoads) {
        List<VillageTypeData.StarterBuilding> defensive =
                pctx.claimByZone(BuildingZone.DEFENSIVE, 1000);
        if (defensive.isEmpty()) return;
        new LayoutPrimitive.RingBand(
                centre,
                pctx.density.getRing2Radius() + innerOffset,
                pctx.density.getRing2Radius() + outerOffset,
                BuildingZone.DEFENSIVE, defensive, snapRoads
        ).place(pctx);
    }

    /**
     * Pushes any remaining buildings into a final RingBand spanning
     * ring1 to ring2 around the village centre. Should be the last
     * thing a recipe does — anything left over after this gets handled
     * by the planner's fallback passes.
     */
    public static void placeStragglersRingBand(
            PlanContext pctx,
            BlockPos centre,
            List<List<BlockPos>> snapRoads) {
        if (pctx.remaining.isEmpty()) return;
        List<VillageTypeData.StarterBuilding> leftovers = new ArrayList<>(pctx.remaining);
        pctx.remaining.clear();
        new LayoutPrimitive.RingBand(
                centre,
                pctx.density.getRing1Radius(),
                pctx.density.getRing2Radius(),
                BuildingZone.RESIDENTIAL, leftovers, snapRoads
        ).place(pctx);
    }

    // =========================================================================
    // Surface-snapped position math
    // =========================================================================

    // =========================================================================
    // Plaza generation (Phase 17 doc 04)
    // =========================================================================

    /**
     * Doc 04 §"Plaza generation algorithm" — recipe-friendly
     * one-liner for plaza installation. Recipes pick a
     * {@link PlazaShape} matching their layout and call this
     * helper; it does the tier→area mapping, builds a
     * {@link PlazaSpec}, and dispatches to {@link PlazaGenerator}.
     *
     * <p>HAMLET tier: skips polygon generation entirely and
     * registers a {@link VillageCenterMarker} so the existing
     * decoration profile path can place a "well" piece at the
     * marker. Larger tiers register a {@link PlazaRegion}.</p>
     *
     * <p>This helper is additive to the existing
     * {@code LayoutPrimitive.TownSquare} call. Both run during
     * prompt 17 — the plaza polygon and the legacy ring road
     * coexist; prompt 18 retires the ring road in favor of the
     * polygon's PLAZA_ADJACENT slots.</p>
     *
     * @return the registered region, or empty if generation was
     *         skipped (HAMLET tier) or failed (terrain hostile)
     */
    public static Optional<PlazaRegion> installPlaza(
            PlanContext pctx,
            BlockPos targetCenter,
            PlazaPurpose purpose,
            PlazaShape preferredShape,
            Optional<Direction> majorAxis) {
        if (pctx == null) return Optional.empty();
        VillageSizeTier tier = pctx.sizeTier();

        if (tier == VillageSizeTier.HAMLET) {
            // Doc 04 §"Tier-based sizing" — HAMLETs get a center
            // marker, no polygon. The existing decoration profile
            // path places a well at this position when the marker
            // is consumed (prompt 18 will explicitly stamp; prompt
            // 17 just registers).
            BlockPos snapped = pctx.solidSurface(targetCenter);
            VillageCenterMarker marker = new VillageCenterMarker(
                    snapped, snapped.getY(), defaultCulture(pctx));
            pctx.setVillageCenter(marker);
            return Optional.empty();
        }

        int targetArea = plazaTargetArea(tier);
        PlazaSpec spec = new PlazaSpec(
                purpose, targetCenter, targetArea, preferredShape, majorAxis);
        return PlazaGenerator.generate(pctx, spec);
    }

    /** Convenience overload: CIVIC purpose, no major axis. The most
     *  common call shape; recipes that just want "a plaza of this
     *  shape, please" use this. */
    public static Optional<PlazaRegion> installPlaza(
            PlanContext pctx,
            BlockPos targetCenter,
            PlazaShape preferredShape) {
        return installPlaza(pctx, targetCenter, PlazaPurpose.CIVIC,
                preferredShape, Optional.empty());
    }

    /** Convenience overload for LINEAR shapes that need an axis. */
    public static Optional<PlazaRegion> installLinearPlaza(
            PlanContext pctx,
            BlockPos targetCenter,
            PlazaPurpose purpose,
            Direction axis) {
        return installPlaza(pctx, targetCenter, purpose,
                PlazaShape.LINEAR, Optional.of(axis));
    }

    /** Doc 04 §"Tier-based sizing" — area target by tier. */
    public static int plazaTargetArea(VillageSizeTier tier) {
        if (tier == null) return 50;
        return switch (tier) {
            case HAMLET  -> 0;
            case VILLAGE -> 50;
            case TOWN    -> 150;
            case CITY    -> 300;
        };
    }

    /** Until per-village culture lookup lands, default-culture is
     *  the only culture in the system. */
    private static String defaultCulture(PlanContext pctx) {
        return "default";
    }

    /** Convert a math-convention angle (radians) to the nearest
     *  cardinal {@link Direction}. Used by recipes whose major axis
     *  is in radian form to call
     *  {@link #installLinearPlaza(PlanContext, BlockPos, PlazaPurpose, Direction)}. */
    public static Direction cardinalFromRad(double angleRad) {
        double deg = ((angleRad * 180.0 / Math.PI) % 360 + 360) % 360;
        if (deg >= 315 || deg < 45) return Direction.EAST;
        if (deg < 135) return Direction.SOUTH;
        if (deg < 225) return Direction.WEST;
        return Direction.NORTH;
    }

    // =========================================================================
    // Surface-snapped position math
    // =========================================================================

    /**
     * Returns a surface-snapped position offset from {@code from}
     * by {@code distance} in {@code direction} (radians).
     */
    public static BlockPos offsetSnapped(PlanContext pctx, BlockPos from, double direction, int distance) {
        return pctx.solidSurface(new BlockPos(
                from.getX() + (int) Math.round(Math.cos(direction) * distance),
                from.getY(),
                from.getZ() + (int) Math.round(Math.sin(direction) * distance)));
    }
}