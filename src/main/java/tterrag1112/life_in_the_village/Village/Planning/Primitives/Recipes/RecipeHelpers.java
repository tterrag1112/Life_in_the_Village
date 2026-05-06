// FILE: src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/RecipeHelpers.java
package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import tterrag1112.life_in_the_village.Village.Planning.StructureSizeCache;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaGenerator;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaPurpose;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaRegion;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaShape;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaSpec;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.VillageCenterMarker;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Shared building blocks for ShapeRecipes. Phase D recipes are
 * declarative — they return a {@code LayoutBlueprint} rather than
 * placing buildings imperatively — so most of the legacy claim/
 * distribute/scatter helpers have been retired alongside
 * {@code LayoutPrimitive}. The remaining helpers cover three areas:
 *
 * <ul>
 *   <li>Recipe pre-checks (largest building footprint).</li>
 *   <li>Centerline branch-index + tangent math used by recipes that
 *       pre-realise a road and need to read points off it (CHAIN).</li>
 *   <li>Plaza installation + farm plot slot emission, both invoked
 *       outside the declarative blueprint flow.</li>
 * </ul>
 *
 * <p>Nothing here is stateful.
 */
public final class RecipeHelpers {
    private RecipeHelpers() {}

    // =========================================================================
    // Building size queries (Phase C terrain pre-check)
    // =========================================================================

    /**
     * Returns the largest rotated footprint side (max of W, L) across the
     * buildings still in {@code pctx.remaining}. Used by recipe pre-checks
     * to decide whether the available terrain can host the village's
     * largest building before sinking time into geometry.
     *
     * <p>Falls back to {@link StructureSizeCache#DEFAULT_RADIUS}{@code * 2}
     * when no size is available; that's conservative and keeps the recipe
     * from aborting on a temporary cache miss.
     */
    public static int largestRotatedFootprint(PlanContext pctx) {
        int max = 0;
        for (var sb : pctx.remaining) {
            var info = pctx.sizes.get(sb.structure(), Rotation.NONE);
            if (info != null) {
                int side = Math.max(info.width(), info.length());
                if (side > max) max = side;
            }
        }
        return max > 0 ? max : StructureSizeCache.DEFAULT_RADIUS * 2;
    }

    // =========================================================================
    // Centerline branch indices + tangent
    // =========================================================================

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
     * Returns angle in radians (math convention). Used by CHAIN's stub
     * branch resolution where stubs need to be perpendicular to the
     * LOCAL road direction rather than the global chord direction —
     * important for curved roads.
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
            // marker, no polygon. Phase 18: also register a minimal
            // Plaza (region=null, empty civicSlots) so downstream
            // consumers can call layout.getPlaza() uniformly.
            BlockPos snapped = pctx.solidSurface(targetCenter);
            VillageCenterMarker marker = new VillageCenterMarker(
                    snapped, snapped.getY(), defaultCulture(pctx));
            pctx.setVillageCenter(marker);
            pctx.layout.setTownSquarePos(snapped);
            pctx.layout.setTownSquareRadius(3);
            pctx.layout.setCivicRingRadius(3 + 8);
            pctx.layout.addForced(new tterrag1112.life_in_the_village
                    .Village.Planning.LayoutSlot(
                            tterrag1112.life_in_the_village.Village
                                    .Planning.LayoutSlot.SlotType.DECORATION,
                            snapped, 3));
            pctx.layout.addPlaza(new tterrag1112.life_in_the_village
                    .Village.Planning.Plaza(
                            null, snapped, 3, 3 + 8,
                            java.util.List.of()));
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

    // =========================================================================
    // Phase 17 — farm plot slot emission
    // =========================================================================

    /** Mirrors FarmPlotPlacer's BASE_HALF_W (kept in sync with the realiser). */
    private static final int FARM_PLOT_BASE_HALF_W = 9;
    /** Mirrors FarmPlotPlacer's BASE_HALF_L. */
    private static final int FARM_PLOT_BASE_HALF_L = 7;
    /** Mirrors FarmPlotPlacer's EDGE_JITTER — the slot footprint reserves
     *  this margin on each axis so jittered plot edges can't spill outside
     *  the validated footprint. */
    private static final int FARM_PLOT_EDGE_JITTER = 3;
    /** Animal pen extras on top of the base crop dimensions. */
    private static final int ANIMAL_PEN_EXTRA_W = 4;
    private static final int ANIMAL_PEN_EXTRA_L = 3;

    /**
     * Phase 17: emit farm plot slots around the village.
     *
     * <p>Honors {@link tterrag1112.life_in_the_village.Village.VillageTypeData
     * .FarmPlotConfig#placement()}:
     * <ul>
     *   <li>{@code INTEGRATED}: ring [0, max(16, maxDistance)] from centre
     *   <li>{@code PERIMETER_OUTSIDE}: ring
     *       [villageRadius + min, villageRadius + max] (defaults 12 / 36)
     *   <li>{@code DISTANT_FIELDS}: ring [min, max] absolute (defaults 40 / 80)
     *   <li>{@code NONE}: returns empty
     * </ul>
     *
     * <p>{@code FarmPlotConfig.minDistance} and {@code maxDistance} override
     * the per-mode defaults when non-zero. Sentinel zero means "use the
     * mode default" (matches the legacy {@code FarmPlotConfig.integrated()}
     * convention where minDistance=0).
     *
     * <p>Each emitted slot carries {@link SlotTag#FARM_PLOT_CROP} or
     * {@link SlotTag#FARM_PLOT_ANIMAL} and the appropriate footprint
     * budget — base half-dim doubled, plus {@code EDGE_JITTER} margin on
     * each axis. The actual {@link tterrag1112.life_in_the_village.Village
     * .Planning.FarmPlotSpec} is constructed by the planner's claim pass
     * (which has the owning farmhouse position and an rng for the jitter
     * seed); this helper only emits position + footprint + tag.
     *
     * @param farmhouseCount number of FARMHOUSE LayoutSlots placed —
     *                       drives total plot count via
     *                       {@code config.plotsPerFarmhouse()}
     */
    public static List<PlacementSlot> emitFarmPlotSlots(
            PlanContext pctx, int farmhouseCount,
            VillageTypeData.FarmPlotConfig config) {
        List<PlacementSlot> out = new ArrayList<>();
        if (farmhouseCount <= 0) return out;
        if (config.placement()
                == VillageTypeData.FarmPlotPlacement.NONE) return out;

        int totalPlots = farmhouseCount * config.plotsPerFarmhouse();
        if (totalPlots <= 0) return out;

        int cropPlots   = config.allowAnimalPens()
                ? (int) Math.round(totalPlots * 0.7)
                : totalPlots;
        int animalPlots = totalPlots - cropPlots;

        int[] ring = ringForPlacement(pctx, config);
        int innerR = ring[0];
        int outerR = ring[1];
        int span   = Math.max(1, outerR - innerR);

        BlockPos centre = pctx.layout.getCenter();

        // Footprint budgets: base half-dim doubled, plus EDGE_JITTER on each
        // axis so jittered plot edges fit inside the validated footprint.
        int cropFpW   = (FARM_PLOT_BASE_HALF_W + FARM_PLOT_EDGE_JITTER) * 2;
        int cropFpL   = (FARM_PLOT_BASE_HALF_L + FARM_PLOT_EDGE_JITTER) * 2;
        int animalFpW = (FARM_PLOT_BASE_HALF_W + ANIMAL_PEN_EXTRA_W
                + FARM_PLOT_EDGE_JITTER) * 2;
        int animalFpL = (FARM_PLOT_BASE_HALF_L + ANIMAL_PEN_EXTRA_L
                + FARM_PLOT_EDGE_JITTER) * 2;

        Set<SlotTag> cropTags   = java.util.EnumSet.of(
                SlotTag.FARM_PLOT_CROP, SlotTag.FIELD_EDGE);
        Set<SlotTag> animalTags = java.util.EnumSet.of(
                SlotTag.FARM_PLOT_ANIMAL, SlotTag.PASTURE);

        for (int i = 0; i < totalPlots; i++) {
            double angle = 2 * Math.PI * i / totalPlots;
            int r = innerR + (span > 1 ? pctx.rng.nextInt(span) : 0);
            BlockPos pos = pctx.solidSurface(new BlockPos(
                    centre.getX() + (int) Math.round(Math.cos(angle) * r),
                    centre.getY(),
                    centre.getZ() + (int) Math.round(Math.sin(angle) * r)));
            boolean isCrop = i < cropPlots;
            out.add(new PlacementSlot(
                    pos,
                    List.of(centre),    // no road feed; planner pass uses bbox-based validation
                    -1,                 // edgeId — plots aren't road-fed
                    isCrop ? cropTags : animalTags,
                    isCrop ? cropFpW : animalFpW,
                    isCrop ? cropFpL : animalFpL,
                    null,               // forcedRotation
                    50,                 // qualityScore (not used by plot pass)
                    0,                  // terrainPenalty
                    0));                // maxDriftBlocks — plots aren't perturbed
        }
        return out;
    }

    /** Returns {@code [innerR, outerR]} for a placement mode, applying
     *  the config's min/max overrides when non-zero. */
    private static int[] ringForPlacement(
            PlanContext pctx, VillageTypeData.FarmPlotConfig config) {
        int villageRadius = pctx.density.getRing2Radius();
        int min = config.minDistance();
        int max = config.maxDistance();
        return switch (config.placement()) {
            case INTEGRATED -> new int[]{
                    min > 0 ? min : 0,
                    max > 0 ? max : 16
            };
            case PERIMETER_OUTSIDE -> new int[]{
                    villageRadius + (min > 0 ? min : 12),
                    villageRadius + (max > 0 ? max : 36)
            };
            case DISTANT_FIELDS -> new int[]{
                    min > 0 ? min : 40,
                    max > 0 ? max : 80
            };
            case NONE -> new int[]{0, 0};   // unreachable; caller filters
        };
    }
}
