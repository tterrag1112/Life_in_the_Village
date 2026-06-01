package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;
import tterrag1112.life_in_the_village.Village.Planning.StructureSizeCache;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.BlockCategory;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.Cell;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.Anchor;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.Gateways;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.Hub;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NetworkSpec;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.Zone;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ZonePartition;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NetworkNode;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NodeKind;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NucleusAffinity;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NucleusKind;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NucleusRef;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NucleusRules;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ProximityPenalty;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteContext;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SpinePath;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.AdjacencyFactor;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DropReason;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DroppedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.Footprint;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.FrontageStrip;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.Parcel;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacementDefaults;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacementProfile;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacementResult;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.TerrainFactor;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.UnavailableBuilding;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.VariantResolver;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.StructureAvailabilityRegistry;
import tterrag1112.life_in_the_village.Village.Buildings.Complex.BuildingComplexRegistry;
import tterrag1112.life_in_the_village.Village.Buildings.Complex.BuildingComplexSpec;
import tterrag1112.life_in_the_village.Village.Buildings.Complex.MarketComplexRegistry;
import tterrag1112.life_in_the_village.Village.Buildings.Complex.MarketComplexSpec;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * V2 Phased Planner — Phases 3 through 6.
 *
 * <p>Phase 2 (road network) is produced by
 * {@link tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NetworkPlanner}
 * inside {@link tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteAnalyzer}
 * and arrives on the {@code SiteContext} before this orchestrator
 * is invoked. A backwards-compat {@code SpinePath} view is derived
 * from the network's edges. Phase 3 places foundation buildings
 * (TOWN_HALL + buildings with terrain aggregates) along the spine.
 * Phase 4 iterates the remaining selection, inserting perpendicular
 * cross streets when a cluster of K consecutive buildings fails for
 * road-frontage reasons. Phase 5 reassesses connectivity, trims
 * empty road segments, and marks junctions. Phase 6 emits.
 *
 * <p>Reservations include both the building footprint and its
 * frontage strip; subsequent buildings can't claim cells inside
 * either.
 */
public final class PhasedPlanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(PhasedPlanner.class);
    private static final int LEVEL = 1;
    /** Max localSlope for a candidate cell. */
    private static final int MAX_SLOPE = 3;
    /** Track E1 — debug flag for per-{@code findBestCandidate}-call
     *  rejection histograms. Read once at class load so the per-call
     *  check is a constant-folded field load. Behavior-neutral; gated
     *  off by default. Enable with
     *  {@code -Dharness.debug.candidates=true}. */
    private static final boolean DEBUG_CANDIDATES =
            Boolean.getBoolean("harness.debug.candidates");
    /** V1 spine width (logical, for frontage math). Distinct from
     *  the painted width V1 RoadShape applies at decoration time. */
    public static final int SPINE_WIDTH = 3;
    /** Hub openness fan length (blocks). */
    private static final int HUB_FAN_LENGTH = 50;
    /** Hub openness fan half-angle (degrees). */
    private static final double HUB_FAN_HALF_ANGLE_DEG = 20;
    /** Hub openness fan sample step. */
    private static final int HUB_FAN_SAMPLE_STEP = 4;
    /** Hard cap on cross streets per village. */
    private static final int CROSS_STREET_CAP = 6;
    /** Cross-street walk step in blocks (matches cellSize x 1). */
    private static final int CROSS_STREET_STEP = 2;
    /** Phase 5 spine trim floor — never trim shorter than this. */
    private static final int MIN_SPINE_LENGTH = 20;
    /** Phase 4a junction-dedup distance — two cross streets within
     *  this many blocks of each other on the spine would have
     *  overlapping intersection cells. */
    private static final int JUNCTION_DEDUP_DISTANCE = 6;
    /** Phase 4a window radius (as fraction of spine length) around
     *  each ideal cross-street position. Widened for Cycle 2's
     *  stochastic positioning — the random draw needs enough room
     *  to produce visibly different layouts across seeds. */
    private static final double JUNCTION_SEARCH_WINDOW = 0.15;
    /** Number of sample positions to evaluate per window. */
    private static final int JUNCTION_SAMPLES = 5;

    private PhasedPlanner() {}

    // =========================================================================
    // Public entry
    // =========================================================================

    public static Result run(SiteContext ctx, V2FeatureMap fmap,
                             List<BuildingType> sortedSelection,
                             List<UnavailableBuilding> unavailable,
                             ServerLevel level) {
        return run(ctx, fmap, sortedSelection, unavailable, level, Set.of());
    }

    /**
     * B2.8 — overload that accepts the set of {@link BuildingType}s
     * whose missing dependencies were resolved by trade in
     * {@code ReconciliationEngine}. Those types skip the
     * {@code DEPENDENCY_MISSING} drop in {@link #placeOne} so a
     * BLACKSMITH that gets metal_ore via a trade route still places
     * even when no MINE is in the village.
     */
    public static Result run(SiteContext ctx, V2FeatureMap fmap,
                             List<BuildingType> sortedSelection,
                             List<UnavailableBuilding> unavailable,
                             ServerLevel level,
                             Set<BuildingType> tradeFulfilledTypes) {
        // Live path: build the cache here so the warn-once state and
        // NBT-fallback semantics live where they always have. The
        // resulting StructureSizeCache implements FootprintProvider
        // so the synthetic-friendly overload below sees the same
        // object shape. Availability is the production singleton —
        // the same one the call site at findBestCandidate hardcoded
        // pre-seam, so the live spawn path's variant-resolution
        // behaviour is unchanged.
        return run(ctx, fmap, sortedSelection, unavailable,
                new StructureSizeCache(level),
                StructureAvailabilityRegistry.INSTANCE,
                tradeFulfilledTypes);
    }

    /**
     * Track E1 — headless overload. The {@link
     * tterrag1112.life_in_the_village.Village.Planning.FootprintProvider}
     * replaces the {@link ServerLevel} the live path uses to construct
     * a {@code StructureSizeCache}, and the {@link
     * tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.BuildingAvailability}
     * replaces the hardcoded {@code StructureAvailabilityRegistry
     * .INSTANCE} read inside placement. All other behaviour is
     * identical.
     */
    public static Result run(SiteContext ctx, V2FeatureMap fmap,
                             List<BuildingType> sortedSelection,
                             List<UnavailableBuilding> unavailable,
                             tterrag1112.life_in_the_village.Village.Planning.FootprintProvider footprints,
                             tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.BuildingAvailability availability,
                             Set<BuildingType> tradeFulfilledTypes) {
        // Spine path is now planned by SiteAnalyzer (Layer 2) and
        // arrives on the SiteContext. Skeleton wraps it as a list of
        // SpineSegments (one per primitive in the path).
        State state = new State(ctx, fmap, footprints, availability);
        state.tradeFulfilledTypes = tradeFulfilledTypes != null
                ? Set.copyOf(tradeFulfilledTypes) : Set.of();
        Set<BuildingType> foundationTypes = computeFoundationTypes(sortedSelection);
        LOGGER.info("PhasedPlanner.run: tier={} axis={} anchor=({},{},{})"
                + " selection={} foundation={} unavailable={}",
                ctx.tier(), ctx.primaryAxis(), ctx.anchor().getX(),
                ctx.anchor().getY(), ctx.anchor().getZ(),
                sortedSelection.size(), foundationTypes.size(), unavailable.size());
        EnumMap<BuildingType, Integer> selectionCounts = new EnumMap<>(BuildingType.class);
        for (BuildingType t : sortedSelection) selectionCounts.merge(t, 1, Integer::sum);
        LOGGER.info("selection: {}", selectionCounts);
        if (!unavailable.isEmpty()) {
            LOGGER.info("unavailable (no NBT): {}",
                    unavailable.stream().map(u -> u.type().name()).toList());
        }

        // Layout Rework Stage 3c — proactive cross-street planning is
        // GONE. The block-serving router builds the whole road network
        // after placement, so there are no roads (and no cross-streets
        // to pre-plan) during the placement loop. planCrossStreetsProactively
        // is now dead (left for Stage 3d teardown alongside NetworkPlanner's
        // road recipes); the placement loop scores cells by zone, not road
        // frontage.

        // Track E1 prompt-4 — seven-batch placement order. The seven
        // batches run in sequence so each subsequent pass can read the
        // already-placed nuclei (rural farmhouses, civic core, resource
        // core). Within each pass, the existing topo-sorted order is
        // preserved so dependencies still resolve correctly.
        //
        //   1 — primary bindings (lead types at strategy-bound anchors)
        //   2 — rural nucleus  (FARMHOUSE for AGRICULTURAL)
        //   3 — civic core     (TOWN_HALL, MARKET, INN, BAKERY, CHAPEL,
        //                       BLACKSMITH near civic nucleus)
        //   4 — resource core  (MINE, WOODCUTTER and their workshops)
        //   5 — HOUSE distribution (the bulk; reads all prior nuclei)
        //   6 — decorative / small (STOCKPILE, WELL, etc.)
        //   7 — farm plots (deferred; FarmComplexPlanner in Layer 5)
        //
        // Batches 1 + 2 are flagged as "foundation" for cell-scoring
        // purposes so they can land off-road if a strong nucleus pull
        // exists. Batch 7 runs post-spawn in Layer 5 and isn't a
        // PhasedPlanner concern.
        int[] perBatchCounts = new int[8];
        for (int batch = 1; batch <= 6; batch++) {
            for (BuildingType type : sortedSelection) {
                if (getBatch(state.ctx, type) != batch) continue;
                boolean foundation = (batch == 1 || batch == 2)
                        || foundationTypes.contains(type);
                if (placeOne(state, type, foundation)) perBatchCounts[batch]++;
            }
        }
        LOGGER.info("placement: {} primary, {} rural, {} civic, {} resource,"
                + " {} houses, {} decorative; drops {}",
                perBatchCounts[1], perBatchCounts[2], perBatchCounts[3],
                perBatchCounts[4], perBatchCounts[5], perBatchCounts[6],
                state.dropped.size());

        // Layout Rework Stage 3c — roads-last. Now that the buildings
        // are placed (position-only, into their zones), route the real
        // road network to serve them + the gateways, and wrap it as the
        // skeleton. NetworkPlanner's network stays on the SiteContext for
        // bindings/batches; only its road GEOMETRY is superseded here.
        NetworkSpec routed = BlockServingRouter.route(
                state.placed, ctx.gateways(), fmap, ctx.anchor());
        state.skeleton = new Skeleton(routed, ctx.primaryAxis(),
                ctx.anchor(), SPINE_WIDTH);
        LOGGER.info("routed network: {} nodes, {} edges → {} road segments",
                routed.nodes().size(), routed.edges().size(),
                state.skeleton.allSegments().size());

        // Orientation pass — attach each placed building to its nearest
        // routed road (fills frontage + facingRoad). Rotation is NOT
        // changed (that would move the footprint AABB).
        orientToRoads(state);

        // Phase 5.
        reassess(state);

        // Hub designation (post-Phase-5; spine path is final).
        designateHubs(state);

        // Phase 6 — emit.
        EnumMap<BuildingType, Integer> counts = new EnumMap<>(BuildingType.class);
        for (PlacedBuilding pb : state.placed) counts.merge(pb.type(), 1, Integer::sum);
        PlacementResult placement = new PlacementResult(
                List.copyOf(state.placed), List.copyOf(state.dropped),
                List.copyOf(unavailable), Map.copyOf(counts), state.viable);

        Map<BlockPos, BuildingType> frontageOwners = new HashMap<>();
        for (PlacedBuilding pb : state.placed) {
            // Stage 3c — frontage is set by the orientation pass above;
            // null-guard defensively in case a building was left
            // unoriented (no routed road at all on a degenerate site).
            if (pb.frontage() != null) {
                frontageOwners.put(pb.frontage().buildingFront(), pb.type());
            }
        }
        RoadNetwork network = new RoadNetwork(state.skeleton, Map.copyOf(frontageOwners));

        LOGGER.info("PhasedPlanner.run done: placed={} dropped={} crossStreets={} viable={}",
                state.placed.size(), state.dropped.size(),
                state.skeleton.crossStreets().size(), state.viable);

        return new Result(placement, network, List.copyOf(state.events),
                java.util.Map.copyOf(state.nucleusContexts),
                java.util.Set.copyOf(state.droppedBindings));
    }

    // =========================================================================
    // Phase 3 + 4 — placement
    // =========================================================================

    /**
     * Phase 4a — capacity-driven cross-street planning.
     *
     * <p>Sums remaining building frontage, subtracts spine capacity
     * already consumed by Phase 3 foundation placements, and
     * computes how many cross streets are needed to host the
     * overflow. Each cross-street can supply
     * {@code 2 × villageRadius × 2 sides = 4 × villageRadius}
     * frontage blocks at full extension.
     *
     * <p>Cross streets are placed at evenly-spaced parameters along
     * the spine ({@code i / (N+1)} for {@code i} in 1..N), each
     * refined within a {@code ±10%} window by terrain extension
     * capacity. Junctions within {@link #JUNCTION_DEDUP_DISTANCE} of
     * an existing cross street are skipped. Cross streets shorter
     * than {@link #minUsefulLength}{@code (tier)} are skipped.
     */
    private static void planCrossStreetsProactively(State state,
                                                    List<BuildingType> sortedSelection,
                                                    Set<BuildingType> foundationTypes) {
        // Track E1 prompt-3 — cross streets are only meaningful for
        // linear-spine topologies. Non-linear topologies (HAUFENDORF,
        // ANGERDORF, RUNDLING, EINZELHOF) already carry rich edge
        // structure (Ring + Spurs) and don't benefit from
        // perpendicular subdivisions of the spine path.
        if (state.ctx.network() != null) {
            switch (state.ctx.network().topology()) {
                case REIHENDORF, CLUSTER -> { /* fall through to plan */ }
                default -> {
                    LOGGER.info("phase 4a: skipped (topology={})",
                            state.ctx.network().topology());
                    return;
                }
            }
        }
        // 1. Capacity math.
        int countRemaining = 0;
        int totalRemainingFrontage = 0;
        for (BuildingType type : sortedSelection) {
            if (foundationTypes.contains(type)) continue;
            countRemaining++;
            totalRemainingFrontage += estimatedFpAlongRoad(state, type);
        }

        SpinePath spinePath = state.skeleton.spinePath();
        int spineLength = spinePath.totalLength();
        int spineCapacity = spineLength * 2;  // both sides

        // Phase 4a runs BEFORE Phase 3, so state.placed is empty.
        // Estimate spine consumption from foundation types' frontage
        // — they're the only buildings that will land on the spine
        // by the time Phase 4b starts iterating.
        int spineConsumed = 0;
        for (BuildingType type : sortedSelection) {
            if (foundationTypes.contains(type)) {
                spineConsumed += estimatedFpAlongRoad(state, type);
            }
        }
        int spineAvailable = Math.max(0, spineCapacity - spineConsumed);
        int overflow = Math.max(0, totalRemainingFrontage - spineAvailable);

        // Each cross-street: 2 * villageRadius total length × 2 sides.
        int crossStreetCapacity = 4 * state.villageRadius;
        int crossStreetsNeeded = crossStreetCapacity > 0
                ? (int) Math.ceil((double) overflow / crossStreetCapacity)
                : 0;
        crossStreetsNeeded = Math.min(crossStreetsNeeded, CROSS_STREET_CAP);

        // 1b. Spacing-floor cap. Two adjacent cross-streets need a
        // buildable strip between them — minimum spacing is the
        // worst-case rotated footprint along the spine plus a
        // frontage strip on each side (frontage = SPINE_WIDTH = 3).
        int maxFpAlongSpine = 0;
        for (BuildingType type : sortedSelection) {
            if (foundationTypes.contains(type)) continue;
            int fp = estimatedFpMaxAlongRoad(state, type);
            if (fp > maxFpAlongSpine) maxFpAlongSpine = fp;
        }
        if (maxFpAlongSpine == 0) maxFpAlongSpine = 20;  // defensive fallback
        int minCrossStreetSpacing = maxFpAlongSpine + 2 * SPINE_WIDTH;
        int maxCrossStreetsThatFit = Math.max(0,
                (spineLength / minCrossStreetSpacing) - 1);
        int spacingCappedCount = Math.min(crossStreetsNeeded, maxCrossStreetsThatFit);

        // A1: stochastic count. Sample uniformly from
        // [target-1, target+1] clamped to [0, spacing-allowed].
        int targetCount = spacingCappedCount;
        int low = Math.max(0, targetCount - 1);
        int high = Math.min(maxCrossStreetsThatFit, targetCount + 1);
        int actualCount = high >= low ? low + state.rng.nextInt(high - low + 1) : 0;

        state.events.add(PhaseEvent.capacityPlan(countRemaining, spineCapacity,
                spineConsumed, spineAvailable, totalRemainingFrontage,
                overflow, crossStreetsNeeded, CROSS_STREET_CAP,
                maxFpAlongSpine, minCrossStreetSpacing,
                maxCrossStreetsThatFit, actualCount));
        LOGGER.info("phase 4a: remaining={} spineLen={} spineCap={} (used={} avail={})"
                + " frontageNeeded={} overflow={} capacity={} cap={}"
                + " maxFp={} minSpacing={} fits={} target={} sampled=[{},{}]→{}",
                countRemaining, spineLength, spineCapacity, spineConsumed,
                spineAvailable, totalRemainingFrontage, overflow,
                crossStreetsNeeded, CROSS_STREET_CAP, maxFpAlongSpine,
                minCrossStreetSpacing, maxCrossStreetsThatFit, targetCount,
                low, high, actualCount);

        crossStreetsNeeded = actualCount;
        if (crossStreetsNeeded == 0) return;

        // 2. Spread along spine: ideal parameters = i / (N+1).
        // For multi-segment spine paths, the parameter is along the
        // straight start→end chord (close enough for V1; the spine
        // hasn't deflected very far on the chord scale).
        int minLen = minUsefulLength(state.ctx.tier());
        for (int i = 1; i <= crossStreetsNeeded; i++) {
            double idealParam = (double) i / (crossStreetsNeeded + 1);
            BlockPos junction = findBestJunctionInWindow(state, idealParam,
                    JUNCTION_SEARCH_WINDOW);

            if (junctionTooClose(junction, state.skeleton.crossStreets(),
                    JUNCTION_DEDUP_DISTANCE)) {
                state.events.add(PhaseEvent.proactiveSkippedAtParam(idealParam,
                        "within " + JUNCTION_DEDUP_DISTANCE
                                + " blocks of existing junction"));
                LOGGER.info("cross-street {} skipped (idealParam={}): junction-dedup",
                        i, String.format("%.2f", idealParam));
                continue;
            }

            Optional<CrossStreet> cs = generateCrossStreetAtJunction(state, junction);
            if (cs.isEmpty()) {
                state.events.add(PhaseEvent.proactiveSkippedAtParam(idealParam,
                        "terrain unviable (zero perpendicular extension)"));
                LOGGER.info("cross-street {} skipped (idealParam={}): zero extension",
                        i, String.format("%.2f", idealParam));
                continue;
            }
            int totalLen = (int) Math.round(distance(cs.get().start(), cs.get().end()));
            if (totalLen < minLen) {
                state.events.add(PhaseEvent.proactiveSkippedAtParam(idealParam,
                        "extension " + totalLen + " < tier min " + minLen));
                LOGGER.info("cross-street {} skipped (idealParam={}): len {} < min {}",
                        i, String.format("%.2f", idealParam), totalLen, minLen);
                continue;
            }
            state.skeleton.addCrossStreet(cs.get());
            state.events.add(PhaseEvent.proactiveInsertedAt(cs.get(), idealParam, totalLen));
            LOGGER.info("cross-street {} inserted: junction=({},{}) length={} (idealParam={})",
                    i, cs.get().spineJunction().getX(), cs.get().spineJunction().getZ(),
                    totalLen, String.format("%.2f", idealParam));
        }
    }

    /** Building's footprint dimension along the road. The building can
     *  rotate to put either {@code width} or {@code length} along the
     *  road, so we use the smaller — the most-favourable estimate. */
    private static int estimatedFpAlongRoad(State state, BuildingType type) {
        StructureSizeCache.FootprintInfo info = state.sizes.get(state.culture, Style.RURAL,
                type,
                tterrag1112.life_in_the_village.Village.Decoration.Variants
                        .BuildingVariant.defaultVariantId(type),
                LEVEL, Rotation.NONE);
        return Math.min(info.width(), info.length());
    }

    /** Worst-case footprint dimension along the spine — used by the
     *  cross-street spacing floor where two adjacent cross-streets
     *  must permit any rotation of the largest remaining building
     *  between them. */
    private static int estimatedFpMaxAlongRoad(State state, BuildingType type) {
        StructureSizeCache.FootprintInfo info = state.sizes.get(state.culture, Style.RURAL,
                type,
                tterrag1112.life_in_the_village.Village.Decoration.Variants
                        .BuildingVariant.defaultVariantId(type),
                LEVEL, Rotation.NONE);
        return Math.max(info.width(), info.length());
    }

    /** Sample {@link #JUNCTION_SAMPLES} positions across
     *  {@code [idealParam - windowFrac, idealParam + windowFrac]} on
     *  the spine path's chord (start→end), score each by total
     *  perpendicular extension along {@code primaryAxis.perpUnit()},
     *  and pick weighted-random by score (Cycle 2 A2). The terrain
     *  weighting keeps positioning sensible; the random draw adds
     *  per-seed variation.
     *
     *  <p>Cross-streets are always perpendicular to {@code primaryAxis}
     *  regardless of where they meet the spine — this keeps them
     *  cardinal even when the spine has a small arc deflection. */
    private static BlockPos findBestJunctionInWindow(State state,
                                                     double idealParam, double windowFrac) {
        SpinePath spinePath = state.skeleton.spinePath();
        BlockPos chordStart = spinePath.start();
        BlockPos chordEnd = spinePath.end();
        Vec3 perpUnit = state.ctx.primaryAxis().perpUnit();
        double perpX = perpUnit.x;
        double perpZ = perpUnit.z;

        double tMin = Math.max(0.05, idealParam - windowFrac);
        double tMax = Math.min(0.95, idealParam + windowFrac);
        BlockPos[] cands = new BlockPos[JUNCTION_SAMPLES];
        int[] extensions = new int[JUNCTION_SAMPLES];
        long totalWeight = 0;
        int bestExtension = -1;
        BlockPos best = pointAlong(chordStart, chordEnd, idealParam);
        for (int s = 0; s < JUNCTION_SAMPLES; s++) {
            double t = JUNCTION_SAMPLES <= 1
                    ? idealParam
                    : tMin + (tMax - tMin) * s / (double) (JUNCTION_SAMPLES - 1);
            BlockPos cand = pointAlong(chordStart, chordEnd, t);
            int sideA = walkPerp(state, cand, perpX, perpZ, +1, state.villageRadius);
            int sideB = walkPerp(state, cand, perpX, perpZ, -1, state.villageRadius);
            int total = sideA + sideB;
            cands[s] = cand;
            extensions[s] = total;
            // Weight on a curve so good candidates dominate but we
            // still occasionally pick a slightly-worse spot.
            totalWeight += Math.max(1, total) * (long) Math.max(1, total);
            if (total > bestExtension) {
                bestExtension = total;
                best = cand;
            }
        }
        // Weighted-random pick. If everything has zero extension we
        // fall back to the geometric ideal.
        if (bestExtension <= 0 || totalWeight <= 0) return best;
        long pick = (long) (state.rng.nextDouble() * totalWeight);
        long cum = 0;
        for (int s = 0; s < JUNCTION_SAMPLES; s++) {
            int e = extensions[s];
            cum += Math.max(1, e) * (long) Math.max(1, e);
            if (pick < cum) return cands[s];
        }
        return best;
    }

    /** Build a perpendicular cross street at {@code junction}. Walks
     *  both sides up to {@code villageRadius} blocks; returns
     *  {@code Optional.empty} only if the total extension is zero.
     *  Tier-min check is the caller's responsibility (Phase 4a uses
     *  {@link #minUsefulLength}). */
    private static Optional<CrossStreet> generateCrossStreetAtJunction(State state,
                                                                       BlockPos junction) {
        // Cardinal perpendicular: independent of the spine's local
        // tangent at the junction. A cross-street's geometry is
        // axis-aligned to {@code primaryAxis.perpUnit()}.
        Vec3 perpUnit = state.ctx.primaryAxis().perpUnit();
        double perpX = perpUnit.x;
        double perpZ = perpUnit.z;
        int sideA = walkPerp(state, junction, perpX, perpZ, +1, state.villageRadius);
        int sideB = walkPerp(state, junction, perpX, perpZ, -1, state.villageRadius);
        if (sideA + sideB <= 0) return Optional.empty();
        BlockPos endA = endpoint(junction, perpX, perpZ, +1, sideA);
        BlockPos endB = endpoint(junction, perpX, perpZ, -1, sideB);
        return Optional.of(new CrossStreet(endA, endB, SPINE_WIDTH, junction));
    }

    /** Tier-scaled minimum useful cross-street total length.
     *  HAMLET still admits 12-block streets (~6 per side) since each
     *  one adds frontage for 1-2 buildings. */
    private static int minUsefulLength(ViabilityTier tier) {
        return switch (tier) {
            case CITY -> 30;
            case TOWN -> 20;
            case HAMLET -> 12;
            case OUTPOST -> 8;
            case UNVIABLE -> 12;  // shouldn't reach Phase 4 but a safe default
        };
    }

    private static boolean junctionTooClose(BlockPos candidate,
                                            List<CrossStreet> existing, int threshold) {
        for (CrossStreet cs : existing) {
            if (distance(candidate, cs.spineJunction()) <= threshold) return true;
        }
        return false;
    }

    /** Tries to place one instance of {@code type}; returns true on
     *  success. On failure, appends to {@code state.dropped}. */
    private static boolean placeOne(State state, BuildingType type, boolean foundation) {
        PlacementProfile profile = PlacementDefaults.get(type);
        if (profile == null) {
            state.dropped.add(new DroppedBuilding(type, DropReason.NOT_SELECTED,
                    "no PlacementProfile authored"));
            return false;
        }

        // Dependency check.
        EnumSet<BuildingType> placedTypes = EnumSet.noneOf(BuildingType.class);
        for (PlacedBuilding pb : state.placed) placedTypes.add(pb.type());
        List<BuildingType> missing = new ArrayList<>();
        for (BuildingType dep : profile.requiresPresent()) {
            if (!placedTypes.contains(dep)) missing.add(dep);
        }
        // B2.8 — ReconciliationEngine may have already accepted that
        // this building's category requirement is fulfilled via
        // trade. When reconciliation flagged it, skip the
        // DEPENDENCY_MISSING drop — the building places fine, and
        // its supply chain runs over the trade-route system.
        if (!missing.isEmpty() && state.tradeFulfilledTypes.contains(type)) {
            LOGGER.info("placed {} despite missing dependency {}: trade-fulfilled "
                    + "by ReconciliationEngine", type, missing);
            missing.clear();
        }
        if (!missing.isEmpty()) {
            state.dropped.add(new DroppedBuilding(type, DropReason.DEPENDENCY_MISSING,
                    "missing: " + missing));
            if (profile.required()) state.viable = false;
            LOGGER.info("dropped {}: DEPENDENCY_MISSING missing={} required={}",
                    type, missing, profile.required());
            return false;
        }

        // Track E1 prompt 5 — strict-with-small-fallback primary
        // bindings. If a binding declared this {@code type} belongs
        // at anchor X, the placer first restricts the candidate
        // search to cells within {@code BINDING_AFFINITY_RADIUS}
        // (20 blocks) of X. If no admissible cell falls in that
        // window, the binding is "dropped" (recorded for the dump)
        // and the search re-runs unrestricted — the general selector
        // decides instead.
        //
        // Pre-prompt-5 bindings were a soft scoring incentive only;
        // a MARKET bound to anchor a1 could land 130 blocks away if
        // its non-binding scoring components outweighed the
        // affinity bonus. The strict cutoff makes the binding's
        // intent real.
        //
        // Footnote on large footprints: the largest authored
        // buildings (MARKET at 21×42, big TOWN_HALL variants) have
        // fp.length/2 ≈ 21 — already past the 20-block cutoff before
        // the geometric setback even matters. Their bindings will
        // legitimately drop more often than small-footprint ones,
        // and the general selector picks a placement by frontage
        // scoring. Acceptable behaviour — the strategy still
        // produces a coherent village without those specific
        // bindings honored; scaling the radius by footprint is more
        // knob than benefit.
        BlockPos boundPos = findPrimaryBindingPosition(state, type);
        Best best = findBestCandidate(state, type, profile, foundation, boundPos);
        if (best == null && boundPos != null) {
            state.droppedBindings.add(type);
            LOGGER.info("dropped binding for {}: no admissible cell within {} blocks of {};"
                    + " retrying unrestricted",
                    type, (int) BINDING_AFFINITY_RADIUS, boundPos);
            best = findBestCandidate(state, type, profile, foundation, null);
        }
        if (best == null) {
            // Track E1 prompt 3 fix-up 3 — drop-reason wording
            // honesty. Pre-fix-up the iterative path's message
            // claimed "no positive-scoring cell" — score.total()
            // is structurally non-negative for un-penalty types,
            // so the score check is almost never the real killer.
            // The actual failure is admissibility (pos slope /
            // category, centre slope / category, reservation
            // overlap, corridor intersection). Both foundation
            // and iterative paths share the same admissibility
            // gates; only the segment selection differs (primary
            // vs primary+cross-street).
            String detail = foundation
                    ? "no admissible candidate position on any primary network edge"
                    : "no admissible candidate position on any network edge";
            state.dropped.add(new DroppedBuilding(type, DropReason.NO_VIABLE_CANDIDATE, detail));
            if (profile.required()) state.viable = false;
            LOGGER.info("dropped {}: NO_VIABLE_CANDIDATE phase={} required={} ({})",
                    type, foundation ? "3" : "4b", profile.required(), detail);
            return false;
        }

        // Stage 2a — reserve an interior complex parcel for farm/market
        // lead buildings (graceful fallback: shrink, then skip). The
        // parcel AABB folds into this building's reservation so later
        // buildings avoid it.
        ComplexParcel cp = reserveComplexParcel(state, type, best);
        Parcel parcel = cp != null ? cp.parcel() : null;
        Aabb parcelAabb = cp != null ? cp.aabb() : null;

        // Materialise the placement.
        PlacedBuilding pb = new PlacedBuilding(type, best.pos, best.footprint,
                best.rotation, profile.priority(), best.variantId,
                best.frontage, best.facingRoad, parcel);
        state.placed.add(pb);
        state.reservations.add(new Reservation(best.footprintAabb,
                best.frontageAabb, parcelAabb, type));
        // Track E1 prompt-4 — capture the dominant nucleus context
        // for the dump's per-building attribution. Recomputes
        // SpatialFit at the chosen cell so the visualizer can show
        // "this HOUSE was pulled by farmhouse@a3" / "this BLACKSMITH
        // was pulled by the RESOURCE primary anchor." Only stored
        // when a nucleus pulled the placement; un-affined buildings
        // have no map entry (rather than a null placeholder).
        NucleusContext nucCtx = computeSpatialFit(
                best.pos, type, profile, state).context();
        if (nucCtx != null) state.nucleusContexts.put(pb, nucCtx);
        state.events.add(PhaseEvent.placed(type, foundation, best.score));
        LOGGER.info("placed {}: phase={} centre=({},{},{}) variant={} fp={}x{} rot={}"
                + " score={} (terrain={} adjacency={} centrality={})",
                type, foundation ? "3" : "4b",
                best.pos.getX(), best.pos.getY(), best.pos.getZ(),
                best.variantId, best.footprint.width(), best.footprint.length(),
                best.rotation,
                String.format("%.2f", best.score.total()),
                String.format("%.2f", best.score.terrain()),
                String.format("%.2f", best.score.adjacency()),
                String.format("%.2f", best.score.centrality()));
        return true;
    }

    /** Finds the highest-scoring cell that:
     *  <ul>
     *    <li>has admissible terrain (OPEN/SHORE, slope ≤ MAX_SLOPE)</li>
     *    <li>is within {@code frontage_distance} of an admissible road segment
     *        (foundation: spine only; iterative: any segment in the skeleton)</li>
     *    <li>placing the rotated footprint + frontage strip doesn't overlap
     *        any existing reservation</li>
     *  </ul>
     */

    /** Track E1 prompt 5 — looks up a primary binding for {@code type}
     *  on the network and returns its anchor position, or null if no
     *  binding matches. Multi-instance binding handling is unchanged
     *  from prompt 3: the first matching binding wins, so multiple
     *  buildings of the same type would all aim at the same anchor.
     *  That's the existing semantics; fixing per-instance binding is
     *  a separate prompt. */
    private static BlockPos findPrimaryBindingPosition(State state, BuildingType type) {
        if (state.ctx.network() == null) return null;
        for (var pb : state.ctx.network().primaryBindings()) {
            if (pb.type() == type) return pb.position();
        }
        return null;
    }

    private static Best findBestCandidate(State state, BuildingType type,
                                          PlacementProfile profile, boolean foundation,
                                          BlockPos boundPos) {
        // Layout Rework Stage 3c (the roads-last flip): place into the
        // building's target ZONE, position-only. No roads exist yet, so
        // there is no frontage gate and no nearest-road geometry — the
        // candidate cell IS the building centre. Rotation is provisional,
        // fixed anchor-ward (the router brings the road to the anchor-
        // facing side; the post-routing orientation pass fills frontage /
        // facingRoad). The returned Best has null frontage / facingRoad.
        final double bindRadiusSq = BINDING_AFFINITY_RADIUS * BINDING_AFFINITY_RADIUS;
        final boolean debugCands = DEBUG_CANDIDATES;
        int cellsScanned = 0, rejZone = 0, rejReservation = 0, rejScore = 0, accepted = 0;

        ZonePartition zp = state.ctx.zonePartition();
        // The zone role this building wants (its nucleus-affinity kind);
        // null when un-affined, bound, or when no zone of that kind
        // exists — in which case the gate is "any zoned (in-village) cell".
        NucleusKind targetKind = (boundPos == null) ? targetZoneKind(state, type, zp) : null;

        Best best = null;
        for (int i = 0; i < state.fmap.gridSize(); i++) {
            for (int j = 0; j < state.fmap.gridSize(); j++) {
                cellsScanned++;
                Cell cell = state.fmap.cell(i, j);
                if (!ZonePartition.isBuildable(cell)) continue;

                BlockPos pos = state.fmap.cellWorldPos(i, j);

                // Primary-binding strict cutoff (unchanged): bound types
                // restrict to within the binding radius; the binding —
                // not the zone — dictates location, so the zone gate is
                // skipped when boundPos != null.
                if (boundPos != null) {
                    double bdx = pos.getX() - boundPos.getX();
                    double bdz = pos.getZ() - boundPos.getZ();
                    if (bdx * bdx + bdz * bdz > bindRadiusSq) continue;
                } else if (zp != null) {
                    // Zone gate — the heart of the flip. The cell must
                    // lie in a village zone, and (when the building has a
                    // resolvable target kind) a zone of that kind.
                    int zid = zp.zoneIdAt(i, j);
                    if (zid < 0) { rejZone++; continue; }
                    if (targetKind != null
                            && zp.zones().get(zid).kind() != targetKind) {
                        rejZone++;
                        continue;
                    }
                }

                // Variant + footprint (unchanged resolution).
                String variantId = state.variantResolver.pickVariantIdForV2(
                        type, pos, state.ctx.anchor(), state.villageRadius,
                        state.culture, Style.RURAL, state.rng,
                        state.availability);
                StructureSizeCache.FootprintInfo info = state.sizes.get(state.culture,
                        Style.RURAL, type, variantId, LEVEL, Rotation.NONE);
                Footprint fp = new Footprint(info.width(), info.length());

                // Provisional rotation: face the anchor (anchor-ward).
                // Chosen over the zone centroid so it aligns with
                // BlockServingRouter.frontCell, which steps toward the
                // anchor — keeping the routed road on the building front.
                Rotation rotation = chooseFacing(pos, state.ctx.anchor());

                // The candidate cell IS the building centre (no road
                // setback). Snap Y to the cell surface.
                BlockPos centre = new BlockPos(pos.getX(), cell.elevationY(), pos.getZ());
                Aabb fpAabb = footprintAabb(centre, fp, rotation);

                // Overlap against prior reservations. No frontage strip
                // exists yet, so the footprint AABB fills both slots.
                if (overlapsAnyReservation(fpAabb, fpAabb, state.reservations)) {
                    rejReservation++;
                    continue;
                }
                // No corridor check — there are no roads at placement.

                ScoreBreakdown score = scorePosition(pos, type, profile, state);
                if (score.total() <= 0) {
                    rejScore++;
                    continue;
                }

                accepted++;
                if (best == null || score.total() > best.score.total()) {
                    best = new Best(centre, fp, rotation, variantId,
                            /*frontage*/ null, /*facingRoad*/ null,
                            fpAabb, /*frontageAabb*/ fpAabb, score);
                }
            }
        }
        if (debugCands) {
            LOGGER.info("candidates type={} foundation={} cellsScanned={} "
                    + "rejected[zone={} reservation={} score={}] "
                    + "accepted={} reservations={}",
                    type, foundation, cellsScanned,
                    rejZone, rejReservation, rejScore,
                    accepted, state.reservations.size());
        }
        return best;
    }

    /** Layout Rework Stage 3c — the zone role a building should be placed
     *  into: its nucleus-affinity preferred kind (CIVIC for TOWN_HALL,
     *  RURAL for HOUSE in AGRICULTURAL, …). Returns null when the building
     *  has no affinity, or when the partition has no zone of the preferred
     *  (or fallback) kind — in which case the caller gates on "any
     *  in-village zone" instead, so the building still places rather than
     *  being dropped wholesale. */
    private static NucleusKind targetZoneKind(State state, BuildingType type,
                                              ZonePartition zp) {
        NucleusRules rules = nucleusRulesOf(state);
        if (rules == null) return null;
        NucleusAffinity aff = rules.affinities().get(type);
        if (aff == null) return null;
        if (zp == null) return aff.preferred();
        for (Zone z : zp.zones()) if (z.kind() == aff.preferred()) return aff.preferred();
        if (aff.fallback() != null) {
            NucleusKind fk = aff.fallback().preferred();
            for (Zone z : zp.zones()) if (z.kind() == fk) return fk;
        }
        return null;
    }

    // =========================================================================
    // Cross-street walk helpers
    // =========================================================================

    /** Walk in {@code (perpX, perpZ) * side} from {@code start}, stepping
     *  {@link #CROSS_STREET_STEP} blocks at a time. Terminates on:
     *  <ul>
     *    <li>{@code length >= maxLength}</li>
     *    <li>next sample is outside the {@link V2FeatureMap}'s scan
     *        grid (use {@link V2FeatureMap#inBounds} — cellAt would
     *        clamp to the edge cell and falsely report "admissible")</li>
     *    <li>cell is not OPEN/SHORE or {@code localSlope > MAX_SLOPE}</li>
     *  </ul>
     *  Returns the walked length. */
    private static int walkPerp(State state, BlockPos start,
                                double perpX, double perpZ, int side, int maxLength) {
        int len = 0;
        while (len + CROSS_STREET_STEP <= maxLength) {
            int nextLen = len + CROSS_STREET_STEP;
            BlockPos sample = endpoint(start, perpX, perpZ, side, nextLen);
            if (!state.fmap.inBounds(sample.getX(), sample.getZ())) break;
            Cell cell = state.fmap.cellAt(sample.getX(), sample.getZ());
            BlockCategory cat = cell.category();
            if ((cat != BlockCategory.OPEN && cat != BlockCategory.SHORE)
                    || cell.localSlope() > MAX_SLOPE) break;
            len = nextLen;
        }
        return len;
    }

    private static BlockPos endpoint(BlockPos from, double perpX, double perpZ,
                                     int side, int length) {
        return new BlockPos(
                from.getX() + (int) Math.round(perpX * side * length),
                from.getY(),
                from.getZ() + (int) Math.round(perpZ * side * length));
    }

    // =========================================================================
    // Phase 5 — reassessment
    // =========================================================================

    /** Designate two hubs at spine path endpoints, ranked by
     *  openness (average admissibility in a forward fan). The hub
     *  with higher openness is the village's "main" hub; trade-road
     *  graph extension (future cycle) attaches there. */
    private static void designateHubs(State state) {
        SpinePath path = state.skeleton.spinePath();
        BlockPos startPos = path.start();
        BlockPos endPos = path.end();
        if (path.segments().isEmpty()) return;

        // Local tangent at the path's "start" endpoint = direction of
        // the FIRST segment, pointing OUTWARD (away from anchor).
        Vec3 startDir = unsignedTangentAtStart(path.segments().get(0));
        // Tangent at "end" endpoint = direction of the LAST segment,
        // outward. The path's segments are ordered start → end, so the
        // last segment's natural direction (from→to) IS outward.
        Vec3 endDir = unsignedTangentAtEnd(
                path.segments().get(path.segments().size() - 1));

        double startOpenness = fanOpenness(state, startPos, startDir);
        double endOpenness = fanOpenness(state, endPos, endDir);

        Hub startHub = new Hub(startPos, startDir, startOpenness);
        Hub endHub = new Hub(endPos, endDir, endOpenness);
        // Higher-openness hub first (the village's "main" hub).
        if (startOpenness >= endOpenness) {
            state.ctx.hubs().add(startHub);
            state.ctx.hubs().add(endHub);
        } else {
            state.ctx.hubs().add(endHub);
            state.ctx.hubs().add(startHub);
        }
    }

    /** Outward tangent from a primitive at its starting endpoint
     *  (pointing AWAY from the anchor side of the spine path).
     *  For V2's planner output, segments[0]'s "start" IS the path's
     *  starting endpoint, and the natural from→to direction points
     *  INTO the path (toward anchor) — so the outward tangent is
     *  the negative. */
    private static Vec3 unsignedTangentAtStart(
            tterrag1112.life_in_the_village.Village.Planning.Primitives
                    .RoadPrimitive prim) {
        BlockPos a, b;
        if (prim instanceof tterrag1112.life_in_the_village.Village
                .Planning.Primitives.RoadPrimitive.StraightRoad sr) {
            a = sr.from(); b = sr.to();
        } else {
            // Arc / CurvedRoad: approximate via chord endpoints.
            // SpineSegment's chord endpoints are stored in skeleton,
            // but this path uses primitives directly; use a chord
            // approximation off the primitive's metadata.
            a = chordStart(prim); b = chordEnd(prim);
        }
        // segments[0] points from path.start INTO the path; outward
        // tangent at start = (a - b) normalised.
        return unitOf(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /** Outward tangent from a primitive at its ending endpoint
     *  (pointing AWAY from the anchor side). For segments[N-1],
     *  the natural from→to direction IS outward. */
    private static Vec3 unsignedTangentAtEnd(
            tterrag1112.life_in_the_village.Village.Planning.Primitives
                    .RoadPrimitive prim) {
        BlockPos a, b;
        if (prim instanceof tterrag1112.life_in_the_village.Village
                .Planning.Primitives.RoadPrimitive.StraightRoad sr) {
            a = sr.from(); b = sr.to();
        } else {
            a = chordStart(prim); b = chordEnd(prim);
        }
        return unitOf(b.getX() - a.getX(), b.getZ() - a.getZ());
    }

    private static BlockPos chordStart(
            tterrag1112.life_in_the_village.Village.Planning.Primitives
                    .RoadPrimitive prim) {
        if (prim instanceof tterrag1112.life_in_the_village.Village
                .Planning.Primitives.RoadPrimitive.CurvedRoad cr) {
            return cr.from();
        }
        if (prim instanceof tterrag1112.life_in_the_village.Village
                .Planning.Primitives.RoadPrimitive.Arc arc) {
            int sx = arc.centre().getX()
                    + (int) Math.round(Math.cos(arc.startAngle()) * arc.radius());
            int sz = arc.centre().getZ()
                    + (int) Math.round(Math.sin(arc.startAngle()) * arc.radius());
            return new BlockPos(sx, arc.centre().getY(), sz);
        }
        return BlockPos.ZERO;
    }

    private static BlockPos chordEnd(
            tterrag1112.life_in_the_village.Village.Planning.Primitives
                    .RoadPrimitive prim) {
        if (prim instanceof tterrag1112.life_in_the_village.Village
                .Planning.Primitives.RoadPrimitive.CurvedRoad cr) {
            return cr.to();
        }
        if (prim instanceof tterrag1112.life_in_the_village.Village
                .Planning.Primitives.RoadPrimitive.Arc arc) {
            int ex = arc.centre().getX()
                    + (int) Math.round(Math.cos(arc.startAngle() + arc.arcSpan())
                    * arc.radius());
            int ez = arc.centre().getZ()
                    + (int) Math.round(Math.sin(arc.startAngle() + arc.arcSpan())
                    * arc.radius());
            return new BlockPos(ex, arc.centre().getY(), ez);
        }
        return BlockPos.ZERO;
    }

    private static Vec3 unitOf(double dx, double dz) {
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-9) return new Vec3(1, 0, 0);
        return new Vec3(dx / len, 0, dz / len);
    }

    /** Average admissibility (0..1) in a {@link #HUB_FAN_LENGTH}-long
     *  fan with half-angle {@link #HUB_FAN_HALF_ANGLE_DEG} extending
     *  from {@code origin} along {@code direction}. Sampled at
     *  {@link #HUB_FAN_SAMPLE_STEP}-block intervals. Higher = more
     *  open / better trade-road extension target. */
    private static double fanOpenness(State state, BlockPos origin, Vec3 direction) {
        double halfAngle = Math.toRadians(HUB_FAN_HALF_ANGLE_DEG);
        // Three rays: -halfAngle, 0, +halfAngle.
        double[] rayOffsets = {-halfAngle, 0, +halfAngle};
        int totalSamples = 0;
        int admissibleSamples = 0;
        double dirAngle = Math.atan2(direction.z, direction.x);
        for (double offset : rayOffsets) {
            double rayAngle = dirAngle + offset;
            double rx = Math.cos(rayAngle), rz = Math.sin(rayAngle);
            for (int d = HUB_FAN_SAMPLE_STEP; d <= HUB_FAN_LENGTH;
                    d += HUB_FAN_SAMPLE_STEP) {
                int x = origin.getX() + (int) Math.round(rx * d);
                int z = origin.getZ() + (int) Math.round(rz * d);
                totalSamples++;
                if (!state.fmap.inBounds(x, z)) continue;
                Cell c = state.fmap.cellAt(x, z);
                BlockCategory cat = c.category();
                if ((cat == BlockCategory.OPEN || cat == BlockCategory.SHORE)
                        && c.localSlope() <= MAX_SLOPE) {
                    admissibleSamples++;
                }
            }
        }
        if (totalSamples == 0) return 0;
        return (double) admissibleSamples / totalSamples;
    }

    private static void reassess(State state) {
        connectivityAudit(state);
        trimUnusedSegments(state);
        markJunctions(state);
    }

    /**
     * Layout Rework Stage 3c — post-routing orientation pass. For each
     * placed building, attach it to the nearest routed road by filling
     * {@code frontage} + {@code facingRoad}; the placement {@code rotation}
     * is preserved (changing it would move the footprint AABB and risk
     * overlaps — re-rotating to the true nearest road, with footprint
     * re-validation, is a deferred polish). The frontage strip is built
     * from the existing rotation's cardinal front direction (the router
     * brought the road to that anchor-facing side). Buildings with no
     * routed road nearby still get a non-null frontage (anchor-ward) so
     * downstream frontage readers are null-safe; their {@code facingRoad}
     * stays null. The wither mints new {@link PlacedBuilding} instances,
     * so {@code nucleusContexts} is re-keyed onto the new instances.
     */
    private static void orientToRoads(State state) {
        List<RoadSegment> segs = state.skeleton != null
                ? state.skeleton.allSegments() : List.of();
        List<PlacedBuilding> reoriented = new ArrayList<>(state.placed.size());
        java.util.Map<PlacedBuilding, NucleusContext> rekeyed =
                new java.util.LinkedHashMap<>();
        for (PlacedBuilding pb : state.placed) {
            NucleusContext nc = state.nucleusContexts.get(pb);
            Vec3 frontDir = cardinalFrontDir(pb.rotation());
            RoadSegment facing = null;
            int roadWidth = SPINE_WIDTH;
            if (!segs.isEmpty()) {
                NearestRoad nr = nearestRoadOf(pb.centre(), segs);
                if (nr != null) {
                    facing = nr.segment;
                    roadWidth = nr.segment.width();
                }
            }
            FrontageStrip strip = computeFrontageStrip(
                    pb.centre(), pb.footprint(), pb.rotation(), frontDir, roadWidth);
            PlacedBuilding oriented = pb.withOrientation(strip, facing);
            reoriented.add(oriented);
            if (nc != null) rekeyed.put(oriented, nc);
        }
        state.placed.clear();
        state.placed.addAll(reoriented);
        state.nucleusContexts.clear();
        state.nucleusContexts.putAll(rekeyed);
    }

    /** Drop placed buildings whose frontage strip doesn't overlap any
     *  road segment in the connected component of the anchor. Try a
     *  short straight-connector rescue first. */
    private static void connectivityAudit(State state) {
        // For V1: every CrossStreet meets the spine, and the spine is
        // the anchor's road, so connectivity = "frontage facingRoad
        // exists in the skeleton". A facingRoad pulled from the
        // skeleton at placement time is always connected. Genuinely-
        // isolated buildings don't normally exist in this model; this
        // audit is the safety belt.
        List<RoadSegment> segments = state.skeleton.allSegments();
        Set<RoadSegment> connectedSet = new HashSet<>(segments);
        List<PlacedBuilding> isolated = new ArrayList<>();
        for (PlacedBuilding pb : state.placed) {
            // Stage 3c — a null facingRoad means the orientation pass
            // found no routed road near this building (degenerate site);
            // that is NOT an isolation failure, so don't drop it. Only a
            // non-null facingRoad that isn't in the skeleton is isolated.
            if (pb.facingRoad() != null && !connectedSet.contains(pb.facingRoad())) {
                isolated.add(pb);
            }
        }
        if (isolated.isEmpty()) return;
        for (PlacedBuilding pb : isolated) {
            // Try to rescue with a road_width-cap straight connector.
            // A successful rescue inserts a degenerate CrossStreet.
            // (V1: this branch is rarely exercised; left as defensive.)
            state.placed.remove(pb);
            state.nucleusContexts.remove(pb);
            state.dropped.add(new DroppedBuilding(pb.type(),
                    DropReason.ISOLATED_AFTER_REASSESS,
                    "frontage road no longer in connected skeleton"));
            state.events.add(PhaseEvent.isolated(pb.type()));
            LOGGER.info("phase 5 isolated: dropped {} (frontage road disconnected)",
                    pb.type());
        }
    }

    /** Trim cross streets that no buildings face. Spine path trimming
     *  is deferred — the network grower produces edges sized to the
     *  selected topology + tier, and segment-level frontage span
     *  arithmetic for finer trimming is out of scope for this cycle. */
    private static void trimUnusedSegments(State state) {
        List<CrossStreet> toRemove = new ArrayList<>();
        for (CrossStreet cs : state.skeleton.crossStreets()) {
            Span span = frontageSpanAlong(state.placed, cs);
            if (span == null) {
                toRemove.add(cs);
                state.events.add(PhaseEvent.removedCrossStreet(cs));
                LOGGER.info("phase 5 removed cross-street: junction=({},{}) (no frontage)",
                        cs.spineJunction().getX(), cs.spineJunction().getZ());
            }
        }
        for (CrossStreet cs : toRemove) state.skeleton.removeCrossStreet(cs);
    }

    private static void markJunctions(State state) {
        Skeleton sk = state.skeleton;
        // Anchor "village centre" junction — record without enumerating
        // segments. The anchor sits at the seam between the
        // backward-walk and forward-walk spine pieces; any spine
        // segment containing it is reachable via skeleton.primarySegments()
        // (chord-decomposed network edges).
        sk.addJunction(new Junction(state.ctx.anchor(), List.of()));
        for (CrossStreet cs : sk.crossStreets()) {
            // Cross-street junctions list the cross-street and the
            // first spine segment as a stand-in (the precise spine
            // segment that the cross-street meets isn't tracked in
            // V1 — junction membership is mostly for downstream
            // plaza/decoration code).
            List<RoadSegment> meeting = new ArrayList<>();
            if (!sk.primarySegments().isEmpty()) {
                meeting.add(sk.primarySegments().get(0));
            }
            meeting.add(cs);
            sk.addJunction(new Junction(cs.spineJunction(), meeting));
        }
    }

    private static Span frontageSpanAlong(List<PlacedBuilding> placed, RoadSegment segment) {
        double tMin = Double.POSITIVE_INFINITY;
        double tMax = Double.NEGATIVE_INFINITY;
        for (PlacedBuilding pb : placed) {
            if (pb.facingRoad() != segment) continue;
            BlockPos f = pb.frontage().buildingFront();
            double t = parameterAlong(segment.start(), segment.end(), f);
            if (t < tMin) tMin = t;
            if (t > tMax) tMax = t;
        }
        if (tMin > tMax) return null;
        return new Span(Math.max(0, tMin - 0.05), Math.min(1, tMax + 0.05));
    }

    // =========================================================================
    // Scoring (terrain + adjacency + centrality)
    // =========================================================================

    private static ScoreBreakdown scorePosition(BlockPos pos, BuildingType type,
                                                PlacementProfile profile, State state) {
        double terrain = 0;
        for (Map.Entry<TerrainFactor, Double> e : profile.terrainWeights().entrySet()) {
            terrain += e.getValue() * Scoring.terrainFactor(e.getKey(), pos,
                    state.fmap, state.villageRadius);
        }
        double adjacency = 0;
        for (Map.Entry<AdjacencyFactor, Double> e : profile.adjacencyWeights().entrySet()) {
            // Stage 3c — NEAR_MAIN_ROAD is dropped: roads don't exist
            // during placement (roads-last), so a road-proximity term
            // would NPE on the null skeleton and is meaningless here.
            // The remaining adjacency factors are anchor/same-type based.
            if (e.getKey() == AdjacencyFactor.NEAR_MAIN_ROAD) continue;
            adjacency += e.getValue() * Scoring.adjacencyFactor(e.getKey(), pos, type,
                    state.ctx, state.placed, java.util.List.of(),
                    state.villageRadius);
        }
        // Track E1 prompt-4 — replace the old radial-centrality with
        // nucleus proximity + road frontage bonus - proximity penalty.
        // The combined value still lives in the {@code centrality}
        // field so the existing ScoreBreakdown shape stays compatible;
        // its semantic is now "spatial fit." Per-building nucleus
        // attribution lives on State.nucleusContexts for the dump.
        double centrality = computeSpatialFit(pos, type, profile, state).score;

        // Track E1 prompt-3 — primary-binding affinity. Lead types
        // bound to a strategy anchor get a strong pull toward that
        // position; the binding wins ties even when the building's
        // nucleus affinity disagrees.
        if (state.ctx.network() != null) {
            for (var pb : state.ctx.network().primaryBindings()) {
                if (pb.type() != type) continue;
                double d = distance(pos, pb.position());
                if (d < BINDING_AFFINITY_RADIUS) {
                    double affinity = 1.0 - d / BINDING_AFFINITY_RADIUS;
                    centrality += affinity * BINDING_AFFINITY_WEIGHT;
                }
                break;
            }
        }
        return new ScoreBreakdown(terrain, adjacency, centrality);
    }

    /** Track E1 prompt-3 + prompt 5 — dual-role radius for primary
     *  bindings. Two uses, one number:
     *  <ul>
     *    <li><b>Soft affinity falloff (prompt 3).</b> Bound cells
     *        inside this radius get an affinity boost in the
     *        centrality term; the boost falls linearly to zero at
     *        the edge.</li>
     *    <li><b>Hard placement cutoff (prompt 5).</b> The strict
     *        bound search restricts candidate cells to within this
     *        radius of the binding anchor; cells past it are
     *        skipped. The unrestricted retry fires only when the
     *        strict pass finds nothing.</li>
     *  </ul>
     *  ~villageRadius/2 so the cutoff is meaningful inside HAMLET/
     *  TOWN villages but doesn't pull buildings across long
     *  distances. (c-i decision: single constant rather than two
     *  separate radii; the strict cutoff and the soft falloff share
     *  the same physical meaning of "near the anchor.") */
    private static final double BINDING_AFFINITY_RADIUS = 20.0;
    /** Scaling factor for the binding affinity centrality bonus.
     *  Calibrated against existing centrality magnitudes (0..1) —
     *  2.0 means a bound cell can outscore a non-bound cell by up
     *  to +2.0 in centrality, dominating the term. */
    private static final double BINDING_AFFINITY_WEIGHT = 2.0;

    // =========================================================================
    // Track E1 prompt-4 — nucleus-proximity scoring
    // =========================================================================

    /** Magnitude scalars for the spatial-fit composition. base
     *  terrain remains its existing 0..1; nucleus / road / penalty
     *  weights here govern how much each pulls relative to the others.
     *  Calibration matches the prompt's sketch: nucleus dominates at
     *  ~0..0.7, road bonus contributes 0..0.15, penalty 0..-0.3. */
    private static final double NUCLEUS_SCORE_WEIGHT  = 0.70;
    private static final double ROAD_BONUS_WEIGHT     = 0.15;
    private static final double PENALTY_WEIGHT        = 0.30;
    /** Width of the road-frontage bonus falloff (blocks). */
    private static final double ROAD_BONUS_RADIUS     = 6.0;
    /** Track E1 prompt 3 fix-up 3 — width of the frontage-zone
     *  tolerance band (blocks) past {@code road_half_width + 1}.
     *  {@code pos} must satisfy
     *  {@code nr.distance ∈ [road_half + 1, road_half + 1 +
     *  FRONTAGE_BAND_WIDTH]} to be eligible. A 2-block band gives
     *  the placer ~3 perpendicular cell rows per side per segment
     *  to choose from — enough flexibility to dodge terrain
     *  irregularities, tight enough that {@code pos} is genuinely
     *  the building's front-edge cell. */
    private static final int FRONTAGE_BAND_WIDTH      = 2;

    /** Track E1 Phase A — widened frontage band for phase-4b
     *  (foundation=false). Phase-3 foundation types (TOWN_HALL,
     *  FARMHOUSE, civic core...) place under low reservation pressure
     *  with the conservative 2-block band; phase-4b types (HOUSE,
     *  STOCKPILE, WELL, STABLE...) run after the foundation pass has
     *  saturated the ~620-cell admissible pool and need a deeper band
     *  to keep candidate generation above zero. Conservative 5-block
     *  band so buildings sit a little deeper from the road, not float
     *  off it. With SPINE_WIDTH=3 the phase-4b admissible perpendicular
     *  distance becomes [2, 7] instead of [2, 4] — ~2.3× cells per
     *  road-block, pool grows from ~620 to ~1500. */
    private static final int FRONTAGE_BAND_WIDTH_4B   = 5;

    /** Spatial-fit summary for one (cell, type) pair: combined score
     *  plus the dominant nucleus context (for the dump). */
    private record SpatialFit(double score, NucleusContext context) {}

    /** Nucleus attribution for a placed building — which kind of
     *  nucleus pulled the placement, and (when resolvable) which
     *  specific anchor or building instance was the strongest pull. */
    public record NucleusContext(NucleusKind kind, String anchorId,
                                  BuildingType buildingType, double distance) {}

    /** Compute spatial fit for the cell+type. Inspects the strategy's
     *  nucleus rules, evaluates each affinity (with single-level
     *  fallback), adds a small road-frontage bonus, subtracts any
     *  proximity penalty, and returns the dominant nucleus context
     *  for debug attribution. */
    private static SpatialFit computeSpatialFit(BlockPos pos, BuildingType type,
                                                PlacementProfile profile, State state) {
        NucleusRules rules = nucleusRulesOf(state);
        double nucleus = 0;
        NucleusContext context = null;

        if (rules != null && rules.affinities().containsKey(type)) {
            NucleusAffinity aff = rules.affinities().get(type);
            NucleusEval primary = evalAffinity(pos, type, aff, rules, state);
            if (primary != null) {
                nucleus += primary.score;
                context = primary.context;
            } else if (aff.fallback() != null) {
                NucleusEval fb = evalAffinity(pos, type, aff.fallback(), rules, state);
                if (fb != null) {
                    nucleus += fb.score;
                    context = fb.context;
                }
            }
        }
        if (context == null) {
            // Residual centrality from the pre-prompt-4 model so
            // un-affined buildings still place reasonably.
            double dist = distance(pos, state.ctx.anchor());
            double radial = 1.0 - Math.min(1.0, dist / Math.max(1, state.villageRadius));
            nucleus = Math.max(0, 1 - Math.abs(profile.centrality() - radial)) * 0.3;
        }

        // Stage 3c — the road-frontage bonus is dropped: no roads exist
        // during placement. Spatial fit is now nucleus/zone + terrain
        // (added by the caller) − proximity penalty (+ binding affinity,
        // also caller-side).
        double penalty = computePenalty(pos, type, state);

        double score = nucleus * NUCLEUS_SCORE_WEIGHT
                - penalty * PENALTY_WEIGHT;
        return new SpatialFit(score, context);
    }

    /** Strategy's nucleusRules, or null when no strategy is selected. */
    private static NucleusRules nucleusRulesOf(State state) {
        if (state.ctx.strategy() == null
                || state.ctx.strategy().strategy() == null) return null;
        return state.ctx.strategy().strategy().nucleusRules();
    }

    /** Walks nuclei of the affinity's preferred kind, picks the one
     *  yielding the highest triangle-curve score. Returns null when
     *  no nucleus of that kind exists. */
    private static NucleusEval evalAffinity(BlockPos pos, BuildingType type,
                                            NucleusAffinity aff, NucleusRules rules,
                                            State state) {
        List<NucleusInstance> nuclei = enumerateNuclei(aff.preferred(), rules, state);
        if (nuclei.isEmpty()) return null;
        double bestScore = 0;
        NucleusContext bestContext = null;
        for (NucleusInstance n : nuclei) {
            double d = distance(pos, n.pos);
            if (d >= aff.maxDistance()) continue;
            double curve = triangleScore(d, aff.idealDistance(), aff.maxDistance());
            double s = aff.weight() * curve;
            if (s > bestScore) {
                bestScore = s;
                bestContext = new NucleusContext(aff.preferred(),
                        n.anchorId, n.buildingType, d);
            }
        }
        return bestContext == null ? null : new NucleusEval(bestScore, bestContext);
    }

    /** Triangle curve: 1 at d=idealDistance, linear ramp to 0 at d=0
     *  and d=maxDistance. Degenerate when idealDistance == 0 to a
     *  single descending ramp from 1 (at d=0) to 0 (at d=maxDistance). */
    private static double triangleScore(double d, double ideal, double max) {
        if (max <= 0) return 0;
        if (ideal <= 0) return Math.max(0, 1 - d / max);
        if (d <= ideal) return d / ideal;
        return Math.max(0, 1 - (d - ideal) / Math.max(1, max - ideal));
    }

    /** Enumerate every nucleus of the given kind currently in scope.
     *  CIVIC / SACRED / RESOURCE: read from {@code rules.*Nucleus}
     *  refs against the strategy's anchors; RURAL: every placed
     *  building whose type is in {@code rules.ruralNucleusTypes};
     *  GATEWAY: every GATEWAY {@link NetworkNode} from the network. */
    private static List<NucleusInstance> enumerateNuclei(NucleusKind kind,
                                                         NucleusRules rules,
                                                         State state) {
        List<NucleusInstance> out = new ArrayList<>();
        switch (kind) {
            case CIVIC -> addRefAsNucleus(out, rules.civicNucleus(), state);
            case SACRED -> {
                NucleusRef r = rules.sacredNucleus();
                if (r != null) addRefAsNucleus(out, r, state);
                else addRefAsNucleus(out, rules.civicNucleus(), state);
            }
            case RESOURCE -> {
                NucleusRef r = rules.resourceNucleus();
                if (r != null) addRefAsNucleus(out, r, state);
            }
            case RURAL -> {
                for (PlacedBuilding pb : state.placed) {
                    if (rules.ruralNucleusTypes().contains(pb.type())) {
                        out.add(new NucleusInstance(pb.centre(),
                                /*anchorId*/ null, pb.type()));
                    }
                }
            }
            case GATEWAY -> {
                if (state.ctx.network() == null) break;
                for (NetworkNode n : state.ctx.network().nodes()) {
                    if (n.kind() == NodeKind.GATEWAY) {
                        out.add(new NucleusInstance(n.pos(), n.id(), null));
                    }
                }
            }
        }
        return out;
    }

    /** Resolve a {@link NucleusRef} into one or more nucleus positions. */
    private static void addRefAsNucleus(List<NucleusInstance> out,
                                        NucleusRef ref, State state) {
        if (ref == null) return;
        if (ref instanceof NucleusRef.PrimaryAnchorRef) {
            Anchor a = state.ctx.strategy() != null
                    ? state.ctx.strategy().primaryAnchor() : null;
            if (a != null) {
                out.add(new NucleusInstance(a.centre(), a.id(), null));
            } else {
                // Fall back to ctx.anchor() — the analyzed anchor —
                // when the strategy's primary anchor is null
                // (cluster-fallback selection path).
                out.add(new NucleusInstance(state.ctx.anchor(), null, null));
            }
        } else if (ref instanceof NucleusRef.AnchorRef ar) {
            for (Anchor a : state.ctx.anchors()) {
                if (a.id().equals(ar.anchorId())) {
                    out.add(new NucleusInstance(a.centre(), a.id(), null));
                    break;
                }
            }
        } else if (ref instanceof NucleusRef.BuildingRef br) {
            for (PlacedBuilding pb : state.placed) {
                if (pb.type() == br.type()) {
                    out.add(new NucleusInstance(pb.centre(), null, pb.type()));
                }
            }
        }
    }

    // Stage 3c — computeRoadBonus removed (roads-last; no road at
    // placement to score frontage against).

    /** Sum of penalty contributions from already-placed buildings
     *  that fall under a {@link ProximityPenalty} rule with {@code type}.
     *  Each violating pair contributes
     *  {@code penaltyWeight * (1 - dist/minDistance)}. */
    private static double computePenalty(BlockPos pos, BuildingType type, State state) {
        NucleusRules rules = nucleusRulesOf(state);
        if (rules == null || rules.penalties().isEmpty()) return 0;
        double total = 0;
        for (ProximityPenalty p : rules.penalties()) {
            for (PlacedBuilding pb : state.placed) {
                if (!p.matches(type, pb.type())) continue;
                double d = distance(pos, pb.centre());
                if (d >= p.minDistance()) continue;
                total += p.penaltyWeight() * (1 - d / Math.max(1, p.minDistance()));
            }
        }
        return total;
    }

    /** A nucleus's position and (optionally) the source anchor /
     *  building-instance metadata used for dump attribution. */
    private record NucleusInstance(BlockPos pos, String anchorId,
                                   BuildingType buildingType) {}

    /** Affinity-evaluation tuple: score contribution + which nucleus
     *  yielded it. */
    private record NucleusEval(double score, NucleusContext context) {}

    // =========================================================================
    // Geometry helpers (footprint, frontage, rotation, segments)
    // =========================================================================

    private static Set<BuildingType> computeFoundationTypes(List<BuildingType> selection) {
        Set<BuildingType> out = EnumSet.noneOf(BuildingType.class);
        for (BuildingType t : selection) {
            PlacementProfile pp = PlacementDefaults.get(t);
            if (pp == null) continue;
            if (pp.required() || !pp.requiresAggregates().isEmpty()) out.add(t);
        }
        return out;
    }

    /** Track E1 prompt-4 — placement-batch classifier. Returns the
     *  batch (1..7) the building type belongs to in the seven-pass
     *  distribution. The classifier reads the strategy's nucleus
     *  rules so a building's batch depends on the village's
     *  inclination (HOUSE is batch 5 everywhere; FARMHOUSE is
     *  batch 2 in AGRICULTURAL strategies but batch 5 otherwise). */
    private static int getBatch(SiteContext ctx, BuildingType type) {
        NucleusRules rules = ctx.network() != null
                && ctx.strategy() != null
                && ctx.strategy().strategy() != null
                ? ctx.strategy().strategy().nucleusRules()
                : null;

        // Batch 1 — primary-bound lead types.
        if (ctx.network() != null) {
            for (var pb : ctx.network().primaryBindings()) {
                if (pb.type() == type) return 1;
            }
        }
        // Batch 2 — rural nucleus types per strategy (typically
        // FARMHOUSE for AGRICULTURAL).
        if (rules != null && rules.ruralNucleusTypes().contains(type)) return 2;
        // Bulk-distributed HOUSE goes to batch 5 regardless of any
        // CIVIC pull (HOUSE is "the rest of the village," not a
        // core lead).
        if (type == BuildingType.HOUSE) return 5;
        // Decorative / small.
        if (type == BuildingType.STOCKPILE
                || type == BuildingType.WELL
                || type == BuildingType.WAREHOUSE) return 6;
        // Batch 3 vs 4: rules.affinities determines which.
        // RESOURCE-preferring types go in batch 4; CIVIC / SACRED /
        // GATEWAY-preferring types go in batch 3.
        if (rules != null && rules.affinities().containsKey(type)) {
            NucleusKind k = rules.affinities().get(type).preferred();
            if (k == NucleusKind.RESOURCE) return 4;
            return 3;
        }
        // Fallback: anything else goes in batch 5 with HOUSE
        // (treats unknown types as bulk-distributed). Keeps every
        // building eligible for placement even when the strategy's
        // nucleusRules doesn't enumerate it.
        return 5;
    }

    /** Mirrors V1 PlanContext.chooseFacing: the building at {@code pos}
     *  faces {@code target}. dx/dz mapping unchanged. */
    private static Rotation chooseFacing(BlockPos pos, BlockPos target) {
        int dx = target.getX() - pos.getX();
        int dz = target.getZ() - pos.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0 ? Rotation.COUNTERCLOCKWISE_90 : Rotation.CLOCKWISE_90;
        }
        return dz > 0 ? Rotation.NONE : Rotation.CLOCKWISE_180;
    }

    /** Cardinal unit vector in the direction the building's "front"
     *  faces, given its rotation. */
    private static Vec3 cardinalFrontDir(Rotation r) {
        return switch (r) {
            case NONE -> new Vec3(0, 0, 1);
            case CLOCKWISE_90 -> new Vec3(-1, 0, 0);
            case CLOCKWISE_180 -> new Vec3(0, 0, -1);
            case COUNTERCLOCKWISE_90 -> new Vec3(1, 0, 0);
        };
    }

    /** Footprint AABB at {@code centre} after applying {@code rotation}.
     *  90/270 swap X and Z. */
    private static Aabb footprintAabb(BlockPos centre, Footprint fp, Rotation rotation) {
        boolean swap = rotation == Rotation.CLOCKWISE_90
                || rotation == Rotation.COUNTERCLOCKWISE_90;
        int rotW = swap ? fp.length() : fp.width();
        int rotL = swap ? fp.width() : fp.length();
        int halfW = rotW / 2;
        int halfL = rotL / 2;
        return new Aabb(centre.getX() - halfW, centre.getZ() - halfL,
                centre.getX() + halfW, centre.getZ() + halfL);
    }

    /** Frontage strip per the {@link FrontageStrip} contract:
     *  length = fp.width (the rotation-invariant front-edge length),
     *  width = roadWidth, sitting just outside the building's front
     *  edge in {@code frontDir}. */
    private static FrontageStrip computeFrontageStrip(BlockPos centre, Footprint fp,
                                                      Rotation rotation, Vec3 frontDir,
                                                      int roadWidth) {
        // Distance from centre to front edge = fp.length / 2 in all
        // rotations (see PhasedPlanner comments — fp.length is the
        // building's depth perpendicular to its facing direction).
        int frontExtent = fp.length() / 2;
        BlockPos buildingFront = new BlockPos(
                centre.getX() + (int) Math.round(frontDir.x * frontExtent),
                centre.getY(),
                centre.getZ() + (int) Math.round(frontDir.z * frontExtent));
        return new FrontageStrip(buildingFront, frontDir, roadWidth, fp.width());
    }

    private static Aabb frontageAabb(FrontageStrip strip) {
        // Strip extends from buildingFront outward in frontDir by `width`
        // (= roadWidth). Length runs perpendicular to frontDir.
        Vec3 d = strip.frontDirection();
        int outwardX = (int) Math.round(d.x * strip.width());
        int outwardZ = (int) Math.round(d.z * strip.width());
        // Perpendicular to frontDir (XZ rotation by 90°).
        int perpX = (int) Math.round(-d.z);
        int perpZ = (int) Math.round(d.x);
        int halfLen = strip.length() / 2;
        // Strip rectangle corners.
        int aX = strip.buildingFront().getX() + perpX * halfLen;
        int aZ = strip.buildingFront().getZ() + perpZ * halfLen;
        int bX = strip.buildingFront().getX() - perpX * halfLen;
        int bZ = strip.buildingFront().getZ() - perpZ * halfLen;
        int cX = aX + outwardX;
        int cZ = aZ + outwardZ;
        int dX = bX + outwardX;
        int dZ = bZ + outwardZ;
        int minX = Math.min(Math.min(aX, bX), Math.min(cX, dX));
        int maxX = Math.max(Math.max(aX, bX), Math.max(cX, dX));
        int minZ = Math.min(Math.min(aZ, bZ), Math.min(cZ, dZ));
        int maxZ = Math.max(Math.max(aZ, bZ), Math.max(cZ, dZ));
        return new Aabb(minX, minZ, maxX, maxZ);
    }

    private static boolean overlapsAnyReservation(Aabb fpAabb, Aabb stripAabb,
                                                  List<Reservation> reservations) {
        for (Reservation r : reservations) {
            if (fpAabb.overlaps(r.footprint) || fpAabb.overlaps(r.frontage)) return true;
            if (stripAabb.overlaps(r.footprint) || stripAabb.overlaps(r.frontage)) return true;
            if (r.parcel != null
                    && (fpAabb.overlaps(r.parcel) || stripAabb.overlaps(r.parcel))) {
                return true;
            }
        }
        return false;
    }

    /** Generic AABB collision check against every prior reservation's
     *  footprint, frontage, and parcel box. Used by the Stage 2a
     *  complex-parcel reservation. */
    private static boolean aabbOverlapsAnyReservation(Aabb box,
                                                      List<Reservation> reservations) {
        for (Reservation r : reservations) {
            if (box.overlaps(r.footprint)) return true;
            if (box.overlaps(r.frontage))  return true;
            if (r.parcel != null && box.overlaps(r.parcel)) return true;
        }
        return false;
    }

    /** Sample the {@link V2FeatureMap} at each cell-aligned grid
     *  position covered by {@code aabb}. Reject if any cell is out
     *  of bounds, not OPEN/SHORE, or if the max-min elevation drop
     *  exceeds {@code slopeTolerance}. */
    private static boolean aabbTerrainOk(V2FeatureMap fmap, Aabb aabb,
                                         int slopeTolerance) {
        int step = Math.max(1, fmap.cellSize());
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        // Sample on a coarse grid plus the four corners (to catch
        // edges that fall between sample steps for small adjuncts).
        int[] xs = sampleAxis(aabb.minX(), aabb.maxX(), step);
        int[] zs = sampleAxis(aabb.minZ(), aabb.maxZ(), step);
        for (int x : xs) {
            for (int z : zs) {
                if (!fmap.inBounds(x, z)) return false;
                Cell c = fmap.cellAt(x, z);
                BlockCategory cat = c.category();
                if (cat != BlockCategory.OPEN && cat != BlockCategory.SHORE) return false;
                int y = c.elevationY();
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        return minY != Integer.MAX_VALUE && (maxY - minY) <= slopeTolerance;
    }

    /** Returns {min, ..., max} stepping by {@code step}, always
     *  including the endpoints. */
    private static int[] sampleAxis(int min, int max, int step) {
        if (max <= min) return new int[]{min, max};
        int n = ((max - min) / step) + 1;
        // +1 to ensure max is included if it doesn't divide evenly.
        int[] out = new int[n + 1];
        int k = 0;
        for (int v = min; v < max && k < n; v += step) out[k++] = v;
        out[k++] = max;
        // Trim trailing duplicates.
        if (k < out.length) {
            int[] trimmed = new int[k];
            System.arraycopy(out, 0, trimmed, 0, k);
            return trimmed;
        }
        return out;
    }

    // =========================================================================
    // Stage 2a — interior complex parcel reservation
    // =========================================================================

    /** Buffer between the lead building footprint and the parcel box. */
    private static final int COMPLEX_PARCEL_BUFFER = 1;
    /** Max-min elevation drop tolerated across a reserved parcel box. */
    private static final int COMPLEX_SLOPE_TOLERANCE = 12;

    /** Result of a successful parcel reservation: the {@link Parcel} to
     *  attach to the {@link PlacedBuilding} plus its AABB for the
     *  reservation set. */
    private record ComplexParcel(Parcel parcel, Aabb aabb) {}

    /**
     * Reserves an interior complex parcel for a FARMHOUSE / MARKET lead
     * building, growing away from the building's road frontage. Sized
     * from the complex spec; validated like an adjunct (overlap /
     * corridor / terrain). Shrinks toward a minimum and, failing that,
     * returns {@code null} (graceful fallback — the building still
     * places, just without a parcel). Returns {@code null} for any
     * non-lead building type.
     */
    private static ComplexParcel reserveComplexParcel(State state, BuildingType type,
                                                      Best best) {
        Parcel.Kind kind;
        if (type == BuildingType.FARMHOUSE) kind = Parcel.Kind.FARM;
        else if (type == BuildingType.MARKET) kind = Parcel.Kind.MARKET;
        else return null;

        // Stage 3c — no frontage exists at placement (roads-last), so
        // grow the parcel away from the ANCHOR (the building's front is
        // rotated anchor-ward, so "away from anchor" is the building's
        // back = the interior the complex fills).
        Direction grow = growthDirectionAwayFromAnchor(best.pos, state.ctx.anchor());
        int[] ext = complexBudgetHalfExtents(kind, best.footprintAabb, grow,
                state.culture, type);
        int fullPerp = ext[0], fullDepth = ext[1], minPerp = ext[2], minDepth = ext[3];

        // Try the full box, shrinking toward the minimum; the first box
        // that clears overlap + corridor + terrain wins.
        final int steps = 4;
        for (int i = 0; i <= steps; i++) {
            double t = 1.0 - (double) i / steps;   // 1.0 (full) → 0.0 (min)
            int hp = (int) Math.round(minPerp + (fullPerp - minPerp) * t);
            int hd = (int) Math.round(minDepth + (fullDepth - minDepth) * t);
            Aabb box = budgetBox(best.pos, best.footprintAabb, grow, hp, hd);
            if (aabbOverlapsAnyReservation(box, state.reservations)) continue;
            // Stage 3c — no corridor check: roads don't exist at
            // placement (roads-last). The router routes around reserved
            // parcels' buildings; the building footprint reservation
            // already keeps the parcel off other buildings.
            if (!aabbTerrainOk(state.fmap, box, COMPLEX_SLOPE_TOLERANCE)) continue;
            Polygon budget = aabbToPolygon(box, best.pos.getY());
            Polygon bounds = aabbToPolygon(best.footprintAabb, best.pos.getY());
            Parcel parcel = new Parcel(kind, budget, best.pos, grow, bounds);
            return new ComplexParcel(parcel, box);
        }
        return null;
    }

    /** Layout Rework Stage 3c — cardinal direction the complex grows.
     *  No frontage exists at placement (roads-last), so grow away from
     *  the anchor: the building is rotated anchor-ward, so the anchor-
     *  facing side is its front and the opposite side (away from anchor)
     *  is the interior the complex fills. Defaults to SOUTH when the
     *  building sits on the anchor. */
    private static Direction growthDirectionAwayFromAnchor(BlockPos centre,
                                                           BlockPos anchor) {
        int dx = centre.getX() - anchor.getX();   // points away from anchor
        int dz = centre.getZ() - anchor.getZ();
        if (dx == 0 && dz == 0) return Direction.SOUTH;
        return Direction.getNearest(
                (int) Math.signum(dx), 0, (int) Math.signum(dz), Direction.SOUTH);
    }

    /** Full + minimum parcel half-extents {@code [fullPerp, fullDepth,
     *  minPerp, minDepth]}, where "perp" is perpendicular to the growth
     *  direction and "depth" is along it. FARM sizes from the
     *  flood-fill {@code blockBudget}; MARKET from footprint + the pad
     *  margin. */
    private static int[] complexBudgetHalfExtents(Parcel.Kind kind, Aabb fp,
                                                  Direction grow, String culture,
                                                  BuildingType type) {
        boolean growZ = grow.getAxis() == Direction.Axis.Z;
        int halfAlong = (growZ ? (fp.maxZ - fp.minZ) : (fp.maxX - fp.minX)) / 2;
        int halfPerp  = (growZ ? (fp.maxX - fp.minX) : (fp.maxZ - fp.minZ)) / 2;

        if (kind == Parcel.Kind.MARKET) {
            int padMargin = MarketComplexRegistry.get(culture, type)
                    .map(MarketComplexSpec::padMargin).orElse(10);
            int minMargin = MarketComplexRegistry.get(culture, type)
                    .map(MarketComplexSpec::minPadMargin).orElse(Math.min(padMargin, 4));
            return new int[]{halfPerp + padMargin, halfAlong + padMargin,
                    halfPerp + minMargin, halfAlong + minMargin};
        }
        // FARM — size a square-ish box from the cell budget with margin.
        int budget = BuildingComplexRegistry.get(culture, type)
                .map(BuildingComplexSpec::blockBudget).orElse(600);
        int side = (int) Math.ceil(Math.sqrt(Math.max(64, budget) * 1.4));
        int fullPerp = Math.max(8, side / 2);
        int fullDepth = Math.max(10, side / 2 + 2);
        return new int[]{fullPerp, fullDepth, 6, 8};
    }

    /** Budget box offset from the building centre in {@code grow},
     *  starting just past the building's back edge. */
    private static Aabb budgetBox(BlockPos centre, Aabb fp, Direction grow,
                                  int halfPerp, int halfDepth) {
        boolean growZ = grow.getAxis() == Direction.Axis.Z;
        int parentHalfAlong = (growZ ? (fp.maxZ - fp.minZ) : (fp.maxX - fp.minX)) / 2;
        int outward = parentHalfAlong + COMPLEX_PARCEL_BUFFER + halfDepth;
        int cx = centre.getX() + grow.getStepX() * outward;
        int cz = centre.getZ() + grow.getStepZ() * outward;
        int worldHalfX = growZ ? halfPerp : halfDepth;
        int worldHalfZ = growZ ? halfDepth : halfPerp;
        return new Aabb(cx - worldHalfX, cz - worldHalfZ, cx + worldHalfX, cz + worldHalfZ);
    }

    /** Rectangular {@link Polygon} from an {@link Aabb} (Y decorative —
     *  the polygon CONTAINS test is XZ-only). */
    private static Polygon aabbToPolygon(Aabb a, int y) {
        return new Polygon(List.of(
                new BlockPos(a.minX, y, a.minZ),
                new BlockPos(a.maxX, y, a.minZ),
                new BlockPos(a.maxX, y, a.maxZ),
                new BlockPos(a.minX, y, a.maxZ)));
    }

    /** True iff {@code fpAabb} intersects any road segment's corridor.
     *  Shared with {@code OverlapAuditor} via {@link RoadCorridors}. */
    private static boolean intersectsAnyCorridor(Aabb fpAabb, List<RoadSegment> segments) {
        for (RoadSegment seg : segments) {
            int corridorHalf = (seg.width() + 1) / 2;
            if (RoadCorridors.intersects(seg.start(), seg.end(), corridorHalf,
                    fpAabb.minX(), fpAabb.minZ(), fpAabb.maxX(), fpAabb.maxZ())) {
                return true;
            }
        }
        return false;
    }

    /** Returns the closest road segment to {@code pos} along with the
     *  closest point on it and the (geometric) distance. */
    private static NearestRoad nearestRoadOf(BlockPos pos, List<RoadSegment> roads) {
        NearestRoad best = null;
        for (RoadSegment seg : roads) {
            BlockPos cp = projectOntoSegment(pos, seg.start(), seg.end());
            double d = distance(pos, cp);
            if (best == null || d < best.distance
                    || (d == best.distance && seg instanceof SpineSegment)) {
                best = new NearestRoad(seg, cp, d);
            }
        }
        return best;
    }

    private static BlockPos projectOntoSegment(BlockPos p, BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double lenSq = dx * dx + dz * dz;
        if (lenSq < 1e-9) return a;
        double t = ((p.getX() - a.getX()) * dx + (p.getZ() - a.getZ()) * dz) / lenSq;
        t = Math.max(0, Math.min(1, t));
        return pointAlong(a, b, t);
    }

    private static BlockPos pointAlong(BlockPos a, BlockPos b, double t) {
        int x = (int) Math.round(a.getX() + (b.getX() - a.getX()) * t);
        int z = (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t);
        int y = (t < 0.5) ? a.getY() : b.getY();
        return new BlockPos(x, y, z);
    }

    /** Returns the parameter t∈[0,1] of the projection of {@code p} onto
     *  segment a→b. */
    private static double parameterAlong(BlockPos a, BlockPos b, BlockPos p) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double lenSq = dx * dx + dz * dz;
        if (lenSq < 1e-9) return 0;
        double t = ((p.getX() - a.getX()) * dx + (p.getZ() - a.getZ()) * dz) / lenSq;
        return Math.max(0, Math.min(1, t));
    }

    private static double distance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static Vec3 unit(BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-9) return new Vec3(1, 0, 0);
        return new Vec3(dx / len, 0, dz / len);
    }

    private static BlockPos average(List<BlockPos> points) {
        long sx = 0, sz = 0;
        for (BlockPos p : points) { sx += p.getX(); sz += p.getZ(); }
        int n = Math.max(1, points.size());
        return new BlockPos((int) (sx / n), points.get(0).getY(), (int) (sz / n));
    }

    // =========================================================================
    // Inner state + records
    // =========================================================================

    private static final class State {
        final SiteContext ctx;
        final V2FeatureMap fmap;
        final tterrag1112.life_in_the_village.Village.Planning.FootprintProvider sizes;
        /** Track E1 — variant-id seam. Live path passes
         *  {@code StructureAvailabilityRegistry.INSTANCE}; harness path
         *  passes a synthetic {@code BuildingAvailability}. Was a
         *  hardcoded singleton at the call site pre-seam; now travels
         *  on State so the same call site works for both paths. */
        final tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.BuildingAvailability availability;
        final int villageRadius;
        final String culture;
        /** Layout Rework Stage 3c — the skeleton is no longer built up
         *  front from {@code ctx.network()}. It is built AFTER placement
         *  from {@link BlockServingRouter}'s routed network (roads-last),
         *  so it is null throughout the placement loop and assigned by
         *  {@code run} before the orientation/reassess passes. */
        Skeleton skeleton;
        final java.util.Random rng;
        final VariantResolver variantResolver = new VariantResolver();
        final List<PlacedBuilding> placed = new ArrayList<>();
        /** Track E1 prompt-4 — per-placement nucleus attribution.
         *  Keyed by the placed building so absence is honest (buildings
         *  without a matching nucleus rule simply have no entry rather
         *  than a null placeholder in a parallel list — the old List
         *  representation NPE'd at {@code List.copyOf} when nulls were
         *  inserted for un-affined types). Exposed via Result for the
         *  dump serializer. */
        final java.util.Map<PlacedBuilding, NucleusContext> nucleusContexts
                = new java.util.LinkedHashMap<>();
        final List<DroppedBuilding> dropped = new ArrayList<>();
        final List<Reservation> reservations = new ArrayList<>();
        final List<PhaseEvent> events = new ArrayList<>();
        /** Track E1 prompt 5 — primary bindings whose strict near-
         *  anchor placement failed and fell back to the general
         *  selector. Surfaced on {@link Result#droppedBindings()} for
         *  the dump's per-binding {@code bindingDropped} field. */
        final java.util.Set<BuildingType> droppedBindings =
                new java.util.HashSet<>();
        boolean viable = true;
        /** B2.8 — building types whose missing dependencies were
         *  resolved by trade in ReconciliationEngine; placeOne
         *  skips DEPENDENCY_MISSING drops for these. Set by
         *  {@code run} after construction. */
        Set<BuildingType> tradeFulfilledTypes = Set.of();

        State(SiteContext ctx, V2FeatureMap fmap,
              tterrag1112.life_in_the_village.Village.Planning.FootprintProvider sizes,
              tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.BuildingAvailability availability) {
            this.ctx = ctx;
            this.fmap = fmap;
            this.sizes = sizes;
            this.availability = availability;
            this.villageRadius = villageRadiusFor(ctx.tier());
            this.culture = ctx.culture().id();
            // Layout Rework Stage 3c — skeleton is built post-placement
            // from the routed network (see run); left null here.
            this.skeleton = null;
            // Salted with PHASED_PLANNER_SALT so cross-street decisions
            // don't share Random state with SiteAnalyzer's inclination
            // sampler (which uses a different salt off the same seed).
            this.rng = new java.util.Random(ctx.seed() ^ PHASED_PLANNER_SALT);
        }
    }

    private static final long PHASED_PLANNER_SALT = 0x504C_414E_4E45_5200L;

    public static int villageRadiusFor(ViabilityTier tier) {
        // Stage 3 fix-up #3 — hoisted to Layer 2's VillageExtent so the
        // ZonePartition can bound the zoned region to the village radius
        // without a Layer4 dependency. Delegates to keep existing callers.
        return tterrag1112.life_in_the_village.Village.Planning.V2.Layer2
                .VillageExtent.radiusFor(tier);
    }

    // ----------------------------------- Diagnostics + result types -----------

    public record Result(PlacementResult placement, RoadNetwork network,
                         List<PhaseEvent> events,
                         java.util.Map<PlacedBuilding, NucleusContext> nucleusContexts,
                         java.util.Set<BuildingType> droppedBindings) {
        /** Backwards-compat 3-arg constructor for callers that don't
         *  care about the nucleus attribution. */
        public Result(PlacementResult placement, RoadNetwork network,
                      List<PhaseEvent> events) {
            this(placement, network, events, java.util.Map.of(),
                    java.util.Set.of());
        }

        /** Back-compat 4-arg constructor (pre-prompt-5 form). */
        public Result(PlacementResult placement, RoadNetwork network,
                      List<PhaseEvent> events,
                      java.util.Map<PlacedBuilding, NucleusContext> nucleusContexts) {
            this(placement, network, events, nucleusContexts,
                    java.util.Set.of());
        }
    }

    public record PhaseEvent(Kind kind, BuildingType type, String detail,
                             ScoreBreakdown score) {
        public enum Kind { PLACED_FOUNDATION, PLACED_ITERATIVE,
                           CAPACITY_PLAN, PROACTIVE_CROSS_STREET, PROACTIVE_SKIPPED,
                           ISOLATED, TRIM, REMOVED_CROSS_STREET }

        static PhaseEvent placed(BuildingType type, boolean foundation, ScoreBreakdown s) {
            return new PhaseEvent(
                    foundation ? Kind.PLACED_FOUNDATION : Kind.PLACED_ITERATIVE,
                    type, null, s);
        }

        /** Multi-line capacity-math summary; LayoutCommand splits on
         *  '\n' to print each line as its own row under "phase 4a". */
        static PhaseEvent capacityPlan(int remaining, int spineCapacity,
                                       int spineConsumed, int spineAvailable,
                                       int totalRemainingFrontage, int overflow,
                                       int crossStreetsNeeded, int cap,
                                       int maxFpAlongSpine, int minCrossStreetSpacing,
                                       int maxCrossStreetsThatFit, int actualCount) {
            String detail = "remaining buildings: " + remaining
                    + "\nspine capacity: " + spineCapacity
                    + " (already used: " + spineConsumed
                    + ", available: " + spineAvailable + ")"
                    + "\nremaining frontage needed: " + totalRemainingFrontage
                    + "\noverflow: " + overflow
                    + "\ncross-streets needed (capacity): " + crossStreetsNeeded
                    + " (capped at " + cap + ")"
                    + "\ncross-streets allowed (spacing floor=" + minCrossStreetSpacing
                    + " from maxFp=" + maxFpAlongSpine + "): " + maxCrossStreetsThatFit
                    + "\ncross-streets using: " + actualCount;
            return new PhaseEvent(Kind.CAPACITY_PLAN, null, detail, null);
        }

        static PhaseEvent proactiveInsertedAt(CrossStreet cs, double idealParam,
                                              int totalLen) {
            int pct = (int) Math.round(idealParam * 100);
            return new PhaseEvent(Kind.PROACTIVE_CROSS_STREET, null,
                    "junction=(" + cs.spineJunction().getX() + ","
                            + cs.spineJunction().getZ() + ")"
                            + " length=" + totalLen
                            + " (terrain window " + pct + "%)",
                    null);
        }

        static PhaseEvent proactiveSkippedAtParam(double idealParam, String reason) {
            int pct = (int) Math.round(idealParam * 100);
            return new PhaseEvent(Kind.PROACTIVE_SKIPPED, null,
                    "window " + pct + "% — " + reason, null);
        }

        static PhaseEvent isolated(BuildingType type) {
            return new PhaseEvent(Kind.ISOLATED, type,
                    "frontage road no longer connected", null);
        }

        static PhaseEvent trimmed(String which, int newLength) {
            return new PhaseEvent(Kind.TRIM, null,
                    which + " trimmed to length " + newLength, null);
        }

        static PhaseEvent removedCrossStreet(CrossStreet cs) {
            return new PhaseEvent(Kind.REMOVED_CROSS_STREET, null,
                    "no frontage at junction "
                            + cs.spineJunction().getX() + "," + cs.spineJunction().getZ(), null);
        }
    }

    public record ScoreBreakdown(double terrain, double adjacency, double centrality) {
        public double total() { return terrain + adjacency + centrality; }
    }

    private record NearestRoad(RoadSegment segment, BlockPos point, double distance) {}

    private record Best(BlockPos pos, Footprint footprint, Rotation rotation,
                        String variantId, FrontageStrip frontage,
                        RoadSegment facingRoad, Aabb footprintAabb, Aabb frontageAabb,
                        ScoreBreakdown score) {}

    /** Stage 2a — parcel AABB is nullable (only farm/market lead
     *  buildings reserve a complex parcel). */
    private record Reservation(Aabb footprint, Aabb frontage,
                               Aabb parcel, BuildingType type) {
        Reservation(Aabb footprint, Aabb frontage, BuildingType type) {
            this(footprint, frontage, null, type);
        }
    }

    private record Aabb(int minX, int minZ, int maxX, int maxZ) {
        boolean overlaps(Aabb o) {
            return minX <= o.maxX && maxX >= o.minX
                    && minZ <= o.maxZ && maxZ >= o.minZ;
        }
    }

    private record Span(double tMin, double tMax) {}

    /** Inline replacement for the old PlacementSolver's score
     *  helpers. Used by the candidate-scoring loop in {@code placeOne}. */
    private static final class Scoring {
        private Scoring() {}

        static double terrainFactor(TerrainFactor f, BlockPos pos,
                                    V2FeatureMap fmap, int villageRadius) {
            Cell cell = fmap.cellAt(pos.getX(), pos.getZ());
            int cellSize = fmap.cellSize();
            return switch (f) {
                case FLAT -> 1.0 - Math.min(1.0, cell.localSlope() / 4.0);
                case NEAR_WATER -> distFactor(cell.distToWater(), cellSize, villageRadius);
                case NEAR_FOREST -> distFactor(cell.distToForest(), cellSize, villageRadius);
                case NEAR_STONE -> distFactor(cell.distToStone(), cellSize, villageRadius);
                case NEAR_COAST -> coastFactor(pos, fmap, villageRadius);
            };
        }

        static double adjacencyFactor(AdjacencyFactor f, BlockPos pos, BuildingType type,
                                      SiteContext ctx, List<PlacedBuilding> placed,
                                      List<SpineSegment> spineSegments, int villageRadius) {
            return switch (f) {
                case NEAR_ANCHOR, NEAR_CIVIC_CENTRE -> 1.0
                        / (1.0 + euclidean(pos, ctx.anchor()) / Math.max(1, villageRadius));
                case NEAR_MAIN_ROAD -> {
                    // Multi-segment spine: take the closest segment's
                    // closest-point distance. Project onto each, pick
                    // the minimum.
                    double bestDist = Double.MAX_VALUE;
                    for (SpineSegment seg : spineSegments) {
                        BlockPos cp = projectOntoSegmentStatic(pos, seg.start(), seg.end());
                        double d = euclidean(pos, cp);
                        if (d < bestDist) bestDist = d;
                    }
                    if (bestDist == Double.MAX_VALUE) bestDist = 0;
                    yield 1.0 / (1.0 + bestDist / Math.max(1, villageRadius));
                }
                case FAR_FROM_ANCHOR, FAR_FROM_CIVIC_CENTRE -> {
                    double d = euclidean(pos, ctx.anchor());
                    yield d / (d + villageRadius);
                }
                case FAR_FROM_SAME_TYPE -> {
                    double nearest = Double.MAX_VALUE;
                    for (PlacedBuilding pb : placed) {
                        if (pb.type() != type) continue;
                        double d = euclidean(pos, pb.centre());
                        if (d < nearest) nearest = d;
                    }
                    if (nearest == Double.MAX_VALUE) yield 1.0;
                    yield nearest / (nearest + villageRadius);
                }
            };
        }

        private static double distFactor(int distCells, int cellSize, int villageRadius) {
            if (distCells == Integer.MAX_VALUE) return 0.0;
            double db = distCells * (double) cellSize;
            return 1.0 / (1.0 + db / Math.max(1, villageRadius));
        }

        private static double coastFactor(BlockPos pos, V2FeatureMap fmap, int villageRadius) {
            Optional<List<BlockPos>> coast = fmap.coastline();
            if (coast.isEmpty() || coast.get().isEmpty()) return 0.0;
            int minDist = Integer.MAX_VALUE;
            for (BlockPos c : coast.get()) {
                int d = Math.max(Math.abs(pos.getX() - c.getX()),
                        Math.abs(pos.getZ() - c.getZ()));
                if (d < minDist) minDist = d;
            }
            return 1.0 / (1.0 + minDist / (double) Math.max(1, villageRadius));
        }

        private static double euclidean(BlockPos a, BlockPos b) {
            double dx = a.getX() - b.getX();
            double dz = a.getZ() - b.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        }

        private static BlockPos projectOntoSegmentStatic(BlockPos p, BlockPos a, BlockPos b) {
            double dx = b.getX() - a.getX();
            double dz = b.getZ() - a.getZ();
            double lenSq = dx * dx + dz * dz;
            if (lenSq < 1e-9) return a;
            double t = ((p.getX() - a.getX()) * dx + (p.getZ() - a.getZ()) * dz) / lenSq;
            t = Math.max(0, Math.min(1, t));
            int x = (int) Math.round(a.getX() + dx * t);
            int z = (int) Math.round(a.getZ() + dz * t);
            return new BlockPos(x, a.getY(), z);
        }
    }
}
