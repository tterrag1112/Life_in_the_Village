package tterrag1112.life_in_the_village.Village.Planning.Adaptive;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Planning.BuildingFootprint;
import tterrag1112.life_in_the_village.Village.Planning.Features.FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole;
import tterrag1112.life_in_the_village.Village.Planning.Plaza;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.VillageLayout;
import tterrag1112.life_in_the_village.Village.Planning.VillagePlanner;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Phase B: full implementation. Six resolvers, one per
 * {@link Anchor} subtype. Every resolver enforces the
 * validator-cap invariant by construction (RegionalGather is the
 * documented exception — ENCLAVE recipes don't require road
 * adjacency).
 *
 * <h3>Routing</h3>
 * Slots emitted from a {@link Anchor.PlazaPerimeter} intention whose
 * tags include {@link SlotTag#PRIME_CIVIC} or
 * {@link SlotTag#SECONDARY_CIVIC} go to {@code plaza.civicSlots()}
 * so the matcher's civic-first claim path (Phase 18) consumes them.
 * Every other slot lands in the flat pool via
 * {@code pctx.offerSlot}.
 *
 * <h3>Default ordering</h3>
 * First-viable. Phase 20's deferred {@code scoreSlot} lift would let
 * resolvers return scored slots; Phase B accepts first-viable order
 * (centerline-walk order, angular-sweep order, etc.). Add scoring in
 * a polish pass after Phase E if measurement shows pathologies.
 */
public final class SlotEmitter {

    /** Matcher's default perturbation budget. PlacementSlot's
     *  {@code maxDriftBlocks} is the wiggle the matcher allows during
     *  commit, NOT the structural offset from the road centerline.
     *  Resolvers always emit at the structural perpendicular offset;
     *  the matcher uses driftBlocks to perturb during commit. */
    private static final int DEFAULT_DRIFT_BLOCKS = 6;

    public EmitterReport emit(
            List<SlotIntention> intentions,
            VillageLayout layout,
            FeatureMap fmap,
            PlanContext pctx) {

        int totalRequested = 0;
        int totalEmitted = 0;
        Map<SlotIntention, Integer> perIntention = new IdentityHashMap<>();
        List<DroppedIntention> drops = new ArrayList<>();

        for (SlotIntention intention : intentions) {
            totalRequested += intention.desiredCount();

            List<PlacementSlot> emitted = switch (intention.anchor()) {
                case Anchor.PlazaPerimeter a ->
                        resolvePlazaPerimeter(intention, a, layout, fmap, pctx);
                case Anchor.RoadAlong a ->
                        resolveRoadAlong(intention, a, layout, fmap, pctx);
                case Anchor.AllSpurs a ->
                        resolveAllSpurs(intention, a, layout, fmap, pctx);
                case Anchor.RingValidated a ->
                        resolveRingValidated(intention, a, layout, fmap, pctx);
                case Anchor.NamedAnchor a ->
                        resolveNamedAnchor(intention, a, layout, fmap, pctx);
                case Anchor.RegionalGather a ->
                        resolveRegionalGather(intention, a, layout, fmap, pctx);
            };

            routeSlots(emitted, intention, layout, pctx);

            int n = emitted.size();
            perIntention.put(intention, n);
            totalEmitted += n;

            if (n < intention.desiredCount()) {
                drops.add(new DroppedIntention(
                        intention, n,
                        "requested=" + intention.desiredCount()
                                + " emitted=" + n));
            }
        }

        return new EmitterReport(totalRequested, totalEmitted,
                perIntention, drops);
    }

    // =========================================================================
    // Resolver: PlazaPerimeter
    // =========================================================================

    /**
     * Slots at plaza polygon vertices, skipping vertices within
     * {@code avoidSpurExitChebDist} of any road exiting the plaza.
     *
     * <p><b>Phase B/C overlap note:</b> PlazaGenerator's
     * {@code buildPlazaCivicSlots} was deleted in Phase B per
     * spec §3 (civic emission moves entirely to the SlotEmitter).
     * Plaza arrives at this resolver with an EMPTY {@code
     * civicSlots()} list; this resolver populates it via
     * {@link #routeSlots} when the intention's tags mark it civic.
     */
    private List<PlacementSlot> resolvePlazaPerimeter(
            SlotIntention intention, Anchor.PlazaPerimeter anchor,
            VillageLayout layout, FeatureMap fmap, PlanContext pctx) {

        Plaza plaza = layout.getPlazas().isEmpty() ? null
                : layout.getPlazas().get(0);
        if (plaza == null || plaza.region() == null) return List.of();
        var region = plaza.region();
        List<BlockPos> vertices = region.footprint().vertices();
        if (vertices.isEmpty()) return List.of();

        // Spur exits: first centerline point that lies outside the
        // plaza polygon for each SPUR-role realised edge.
        List<BlockPos> spurExits = new ArrayList<>();
        for (var edge : pctx.realisedEdges()) {
            if (edge.role() != EdgeRole.SPUR) continue;
            for (BlockPos pt : edge.centerline()) {
                if (!Polygon.contains(region.footprint(), pt)) {
                    spurExits.add(pt);
                    break;
                }
            }
        }

        int fp = intention.maxFootprint();
        int halfW = fp / 2;
        int halfL = fp / 2;
        int maxRoadDist = VillagePlanner.maxRoadDistance(fp, fp);
        int needed = intention.desiredCount();

        List<PlacementSlot> result = new ArrayList<>();
        int emittedIdx = 0;
        for (int i = 0; i < vertices.size() && result.size() < needed; i++) {
            BlockPos v = vertices.get(i);

            // Skip vertices too close to a spur exit
            int minSpurDist = Integer.MAX_VALUE;
            for (BlockPos s : spurExits) {
                int d = chebDist(v, s);
                if (d < minSpurDist) minSpurDist = d;
            }
            if (minSpurDist < anchor.avoidSpurExitChebDist()) continue;

            // FeatureMap + footprint clearance.
            if (!fmap.isClearForFootprint(v, fp, fp)) continue;
            if (!isFootprintClear(v, halfW, halfL, layout)) continue;

            // Validator-cap: vertex must be within maxRoadDist of
            // some realised edge centerline.
            int distToRoad = nearestRoadDistance(v, pctx);
            if (distToRoad > maxRoadDist) continue;

            // Quality: matches PlazaGenerator's deleted
            // buildPlazaCivicSlots formula (90 - i*2, floor 40) so
            // the matcher's civic-first prepass tie-breaks the same
            // way it did pre-Phase-B.
            int quality = Math.max(40, 90 - emittedIdx * 2);
            emittedIdx++;

            result.add(new PlacementSlot(
                    v,
                    /* feedingRoad */ null,
                    /* feedingEdgeId */ -1,
                    intention.tags(),
                    fp, fp,
                    /* forcedRotation */ null,
                    /* qualityScore */ quality,
                    /* terrainPenalty */ 0,
                    DEFAULT_DRIFT_BLOCKS));
        }
        return result;
    }

    // =========================================================================
    // Resolver: RoadAlong
    // =========================================================================

    /**
     * Slots along one specific road's centerline, perpendicular at
     * {@code spacing} intervals. Tangent computation matches
     * BuildSiteFinder's existing {@code Integer.signum} pattern
     * (block-aligned, no Vec3 math).
     */
    private List<PlacementSlot> resolveRoadAlong(
            SlotIntention intention, Anchor.RoadAlong anchor,
            VillageLayout layout, FeatureMap fmap, PlanContext pctx) {

        var edge = pctx.findEdge(anchor.edge());
        if (edge == null) return List.of();
        List<BlockPos> centerline = edge.centerline();
        if (centerline.size() < 2) return List.of();

        int fp = intention.maxFootprint();
        int halfW = fp / 2;
        int halfL = fp / 2;
        int perpOffset = edge.tier().reservedHalfWidth()
                + Math.max(halfW, halfL) + 1;
        int maxRoadDist = VillagePlanner.maxRoadDistance(fp, fp);

        // Sanity check on the validator-cap invariant: the structural
        // offset itself must fit under the cap. If not, the recipe
        // asked for an impossible footprint on this road tier.
        // perpOffset > maxRoadDist → drop the intention; the count
        // reduction is the report's signal.
        if (perpOffset > maxRoadDist) return List.of();

        int needed = intention.desiredCount();
        int spacing = Math.max(1, anchor.spacing());
        int sides = anchor.bothSides() ? 2 : 1;

        List<PlacementSlot> result = new ArrayList<>();
        for (int i = 0; i < centerline.size() && result.size() < needed;
                i += spacing) {
            BlockPos on = centerline.get(i);
            int[] perp = perpendicularUnit(centerline, i);
            int perpX = perp[0];
            int perpZ = perp[1];

            for (int side = 0; side < sides && result.size() < needed; side++) {
                int sign = (side == 0) ? 1 : -1;
                BlockPos candidate = new BlockPos(
                        on.getX() + perpX * perpOffset * sign,
                        on.getY(),
                        on.getZ() + perpZ * perpOffset * sign);

                if (!fmap.isClearForFootprint(candidate, fp, fp)) continue;
                if (!isFootprintClear(candidate, halfW, halfL, layout)) continue;

                result.add(new PlacementSlot(
                        candidate,
                        centerline,
                        edge.edgeId(),
                        intention.tags(),
                        fp, fp,
                        /* forcedRotation */ null,
                        /* qualityScore */ 0,
                        /* terrainPenalty */ 0,
                        DEFAULT_DRIFT_BLOCKS));
            }
        }
        return result;
    }

    // =========================================================================
    // Resolver: AllSpurs
    // =========================================================================

    /**
     * Distribute count across all SPUR-role roads. Delegates to
     * {@link #resolveRoadAlong} per spur; truncation-tolerance is
     * automatic (a truncated spur produces fewer candidates).
     */
    private List<PlacementSlot> resolveAllSpurs(
            SlotIntention intention, Anchor.AllSpurs anchor,
            VillageLayout layout, FeatureMap fmap, PlanContext pctx) {

        List<RealisedEdge> spurs = pctx.realisedEdges().stream()
                .filter(e -> e.role() == EdgeRole.SPUR)
                .toList();
        if (spurs.isEmpty()) return List.of();

        int needed = intention.desiredCount();
        int perSpur = Math.max(1, needed / spurs.size());

        List<PlacementSlot> result = new ArrayList<>();
        for (RealisedEdge spur : spurs) {
            if (result.size() >= needed) break;
            int spurNeeded = Math.min(perSpur, needed - result.size());

            // Synthetic intention scoped to this spur.
            SlotIntention syntheticIntent = new SlotIntention(
                    intention.sector(),
                    intention.tags(),
                    spurNeeded,
                    intention.maxFootprint(),
                    new Anchor.RoadAlong(
                            spur.ref(),
                            anchor.spacingPerSpur(),
                            anchor.bothSides()));

            result.addAll(resolveRoadAlong(syntheticIntent,
                    (Anchor.RoadAlong) syntheticIntent.anchor(),
                    layout, fmap, pctx));
        }
        return result;
    }

    // =========================================================================
    // Resolver: RingValidated
    // =========================================================================

    /**
     * Slots on a ring at radius R, only at angular positions within
     * the validator-cap distance of some realised edge. Solves
     * DUAL_PLAZA / OUTPOST / outer-agri-ring failures: angular
     * positions falling between spurs (too far from any road) get
     * skipped, the intention's count drops, no validator-failing
     * slots emit.
     */
    private List<PlacementSlot> resolveRingValidated(
            SlotIntention intention, Anchor.RingValidated anchor,
            VillageLayout layout, FeatureMap fmap, PlanContext pctx) {

        BlockPos center = layout.getCenter();
        if (center == null) return List.of();
        int radius = anchor.radius();
        int fp = intention.maxFootprint();
        int halfW = fp / 2;
        int halfL = fp / 2;
        int maxRoadDist = VillagePlanner.maxRoadDistance(fp, fp);

        int needed = intention.desiredCount();
        // Oversample: needed × 4, min 32. Lets the resolver find
        // {needed} valid angles even when half the ring is between
        // spurs or blocked by terrain.
        int angularSamples = Math.max(needed * 4, 32);
        Random rng = new Random(pctx.worldSeed ^ ((long) radius * 0x9E3779B97F4A7C15L));

        List<PlacementSlot> result = new ArrayList<>();
        for (int i = 0; i < angularSamples && result.size() < needed; i++) {
            double baseAngle = 2 * Math.PI * i / angularSamples;
            double jitter = anchor.radialJitter() != 0.0
                    ? (rng.nextDouble() - 0.5) * anchor.radialJitter()
                    : 0.0;
            double angle = baseAngle + jitter;

            BlockPos candidate = new BlockPos(
                    center.getX() + (int) Math.round(Math.cos(angle) * radius),
                    center.getY(),
                    center.getZ() + (int) Math.round(Math.sin(angle) * radius));

            if (!fmap.isClearForFootprint(candidate, fp, fp)) continue;
            if (!isFootprintClear(candidate, halfW, halfL, layout)) continue;

            // Validator-cap: position must be within maxRoadDist of
            // SOME realised edge's centerline.
            RealisedEdge nearest = nearestEdge(candidate, pctx);
            if (nearest == null) continue;
            int dist = nearestPointChebyshev(candidate, nearest.centerline());
            if (dist > maxRoadDist) continue;

            result.add(new PlacementSlot(
                    candidate,
                    nearest.centerline(),
                    nearest.edgeId(),
                    intention.tags(),
                    fp, fp,
                    /* forcedRotation */ null,
                    /* qualityScore */ 0,
                    /* terrainPenalty */ 0,
                    DEFAULT_DRIFT_BLOCKS));
        }
        return result;
    }

    // =========================================================================
    // Resolver: NamedAnchor
    // =========================================================================

    /**
     * Slot near a named anchor, offset along the anchor's nearest
     * feeding road by {@code radialOffset}. Solves HILLTOP TOWN_HALL
     * placement: the named anchor (e.g. HIGH_GROUND) lookup gives a
     * point; the resolver finds the nearest road centerline and
     * places the slot perpendicular to that road's nearest point.
     *
     * <p>Returns at most one slot. Recipes wanting multiple slots
     * near an anchor should use {@link Anchor.RoadAlong} with the
     * anchor's feeding edge.
     */
    private List<PlacementSlot> resolveNamedAnchor(
            SlotIntention intention, Anchor.NamedAnchor anchor,
            VillageLayout layout, FeatureMap fmap, PlanContext pctx) {

        var blueprint = pctx.getCurrentBlueprint();
        if (blueprint == null) return List.of();
        BlockPos anchorPos = blueprint.namedAnchors().get(anchor.kind());
        if (anchorPos == null) return List.of();

        int fp = intention.maxFootprint();
        int halfW = fp / 2;
        int halfL = fp / 2;

        RealisedEdge feedingEdge = nearestEdge(anchorPos, pctx);
        if (feedingEdge == null) return List.of();

        List<BlockPos> centerline = feedingEdge.centerline();
        // Centerline point nearest the anchor.
        int nearestIdx = 0;
        int nearestDist = Integer.MAX_VALUE;
        for (int i = 0; i < centerline.size(); i++) {
            int d = chebDist(centerline.get(i), anchorPos);
            if (d < nearestDist) {
                nearestDist = d;
                nearestIdx = i;
            }
        }

        int targetIdx = Math.max(0, Math.min(centerline.size() - 1,
                nearestIdx + anchor.radialOffset()));
        BlockPos on = centerline.get(targetIdx);
        int[] perp = perpendicularUnit(centerline, targetIdx);
        int perpOffset = feedingEdge.tier().reservedHalfWidth()
                + Math.max(halfW, halfL) + 1;
        int maxRoadDist = VillagePlanner.maxRoadDistance(fp, fp);
        if (perpOffset > maxRoadDist) return List.of();

        // Try both sides; first viable wins.
        for (int side = 0; side < 2; side++) {
            int sign = (side == 0) ? 1 : -1;
            BlockPos candidate = new BlockPos(
                    on.getX() + perp[0] * perpOffset * sign,
                    on.getY(),
                    on.getZ() + perp[1] * perpOffset * sign);

            if (!fmap.isClearForFootprint(candidate, fp, fp)) continue;
            if (!isFootprintClear(candidate, halfW, halfL, layout)) continue;

            return List.of(new PlacementSlot(
                    candidate,
                    centerline,
                    feedingEdge.edgeId(),
                    intention.tags(),
                    fp, fp,
                    /* forcedRotation */ null,
                    /* qualityScore */ 0,
                    /* terrainPenalty */ 0,
                    DEFAULT_DRIFT_BLOCKS));
        }
        return List.of();
    }

    // =========================================================================
    // Resolver: RegionalGather
    // =========================================================================

    /**
     * Free placement within a circular region using uniform-disc
     * sampling with min-spacing rejection. ENCLAVE-style hand-rolled
     * courtyards.
     *
     * <p><b>Validator-cap exception.</b> RegionalGather is the only
     * resolver that does NOT enforce the road-adjacency cap.
     * ENCLAVE recipes don't use a road graph; the validator's
     * non-road-mode rules handle these slots (hull-distance check
     * on VillagePlanner.validateBuildingDistance falls through when
     * the slot's feedingRoad is null).
     */
    private List<PlacementSlot> resolveRegionalGather(
            SlotIntention intention, Anchor.RegionalGather anchor,
            VillageLayout layout, FeatureMap fmap, PlanContext pctx) {

        int fp = intention.maxFootprint();
        int halfW = fp / 2;
        int halfL = fp / 2;
        int needed = intention.desiredCount();
        Random rng = new Random(
                pctx.worldSeed ^ ((long) anchor.center().hashCode()));

        List<PlacementSlot> result = new ArrayList<>();
        int maxAttempts = needed * 30;
        int minSpacing = Math.max(1, anchor.minSpacing());
        int radius = Math.max(1, anchor.radius());

        for (int attempt = 0; attempt < maxAttempts && result.size() < needed; attempt++) {
            double angle = rng.nextDouble() * 2 * Math.PI;
            double r = Math.sqrt(rng.nextDouble()) * radius;
            BlockPos candidate = new BlockPos(
                    anchor.center().getX() + (int) Math.round(Math.cos(angle) * r),
                    anchor.center().getY(),
                    anchor.center().getZ() + (int) Math.round(Math.sin(angle) * r));

            // Min-spacing reject against already-emitted slots.
            boolean tooClose = false;
            for (PlacementSlot s : result) {
                if (chebDist(s.pos(), candidate) < minSpacing) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) continue;

            if (!fmap.isClearForFootprint(candidate, fp, fp)) continue;
            if (!isFootprintClear(candidate, halfW, halfL, layout)) continue;

            // No validator-cap check by design (see Javadoc).
            result.add(new PlacementSlot(
                    candidate,
                    /* feedingRoad */ null,
                    /* feedingEdgeId */ -1,
                    intention.tags(),
                    fp, fp,
                    /* forcedRotation */ null,
                    /* qualityScore */ 0,
                    /* terrainPenalty */ 0,
                    DEFAULT_DRIFT_BLOCKS));
        }
        return result;
    }

    // =========================================================================
    // Routing
    // =========================================================================

    /**
     * Phase B / spec §10.3: PRIME_CIVIC + SECONDARY_CIVIC slots from
     * a {@link Anchor.PlazaPerimeter} intention land in
     * {@code plaza.civicSlots()} so the matcher's civic-first claim
     * path consumes them. Everything else goes to the flat pool.
     */
    private void routeSlots(List<PlacementSlot> slots,
            SlotIntention intention, VillageLayout layout,
            PlanContext pctx) {

        boolean civicPerimeter = intention.anchor() instanceof Anchor.PlazaPerimeter
                && (intention.tags().contains(SlotTag.PRIME_CIVIC)
                        || intention.tags().contains(SlotTag.SECONDARY_CIVIC));

        if (civicPerimeter) {
            Plaza plaza = layout.getPlazas().isEmpty() ? null
                    : layout.getPlazas().get(0);
            if (plaza != null) {
                for (PlacementSlot s : slots) {
                    plaza.civicSlots().add(s);
                }
                return;
            }
        }
        for (PlacementSlot s : slots) {
            pctx.offerSlot(s);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Returns the unit perpendicular vector (in XZ) for the
     *  centerline tangent at index {@code i}. Mirrors
     *  BuildSiteFinder's pattern: tangent = signum(next - prev),
     *  perp = (-tz, tx). Defaults to (0, 1) for degenerate
     *  zero-length tangent (single-point centerline). */
    private static int[] perpendicularUnit(List<BlockPos> centerline, int i) {
        int n = centerline.size();
        BlockPos prev = centerline.get(Math.max(0, i - 1));
        BlockPos next = centerline.get(Math.min(n - 1, i + 1));
        int tx = Integer.signum(next.getX() - prev.getX());
        int tz = Integer.signum(next.getZ() - prev.getZ());
        if (tx == 0 && tz == 0) {
            tx = 1;
            tz = 0;
        }
        return new int[]{-tz, tx};
    }

    /** Chebyshev distance from {@code pos} to the nearest centerline
     *  point in any realised edge. */
    private static int nearestRoadDistance(BlockPos pos, PlanContext pctx) {
        int best = Integer.MAX_VALUE;
        for (var edge : pctx.realisedEdges()) {
            for (BlockPos p : edge.centerline()) {
                int d = chebDist(pos, p);
                if (d < best) best = d;
            }
        }
        return best;
    }

    /** Realised edge whose centerline contains the closest point to
     *  {@code pos}. Returns null when no edges exist. */
    private static RealisedEdge nearestEdge(BlockPos pos, PlanContext pctx) {
        RealisedEdge best = null;
        int bestDist = Integer.MAX_VALUE;
        for (var edge : pctx.realisedEdges()) {
            for (BlockPos p : edge.centerline()) {
                int d = chebDist(pos, p);
                if (d < bestDist) {
                    bestDist = d;
                    best = edge;
                }
            }
        }
        return best;
    }

    private static int nearestPointChebyshev(BlockPos pos, List<BlockPos> points) {
        int best = Integer.MAX_VALUE;
        for (BlockPos p : points) {
            int d = chebDist(pos, p);
            if (d < best) best = d;
        }
        return best;
    }

    private static int chebDist(BlockPos a, BlockPos b) {
        return Math.max(Math.abs(a.getX() - b.getX()),
                Math.abs(a.getZ() - b.getZ()));
    }

    /** Footprint clearance test combining occupied-building and
     *  reserved-road checks. {@code BuildingFootprint.isClear} tests
     *  both at once (the spec's pseudocode split into two methods,
     *  but the actual API combines them — single call is cleaner).
     *  {@code BuildingFootprint.isClear} takes the top-left origin
     *  and full width/length, so we offset from the centre by half. */
    private static boolean isFootprintClear(BlockPos centre,
            int halfW, int halfL, VillageLayout layout) {
        BlockPos origin = new BlockPos(
                centre.getX() - halfW, centre.getY(),
                centre.getZ() - halfL);
        BuildingFootprint fp = layout.getRoadFootprint();
        if (fp == null) return true;
        return fp.isClear(origin, halfW * 2, halfL * 2, /* buffer */ 1);
    }

    // =========================================================================
    // Report types
    // =========================================================================

    public record EmitterReport(
            int totalRequested,
            int totalEmitted,
            Map<SlotIntention, Integer> perIntentionEmitted,
            List<DroppedIntention> drops
    ) {
        public double dropRatio() {
            return totalRequested == 0 ? 0.0
                    : 1.0 - ((double) totalEmitted / totalRequested);
        }
    }

    public record DroppedIntention(
            SlotIntention intention,
            int emittedCount,
            String dropReason
    ) {}
}
