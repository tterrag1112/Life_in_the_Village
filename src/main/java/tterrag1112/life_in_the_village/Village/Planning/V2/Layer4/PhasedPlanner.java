package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;
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
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.Hub;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteContext;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SpinePath;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.AdjacencyFactor;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DropReason;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DroppedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.Footprint;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.FrontageStrip;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacementDefaults;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacementProfile;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacementResult;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.TerrainFactor;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.UnavailableBuilding;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.VariantResolver;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.StructureAvailabilityRegistry;

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
 * <p>Phase 2 (spine path) is produced by {@code SpinePathPlanner}
 * inside {@link tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteAnalyzer}
 * and arrives on the {@code SiteContext} before this orchestrator
 * is invoked. Phase 3 places foundation buildings
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
        // Spine path is now planned by SiteAnalyzer (Layer 2) and
        // arrives on the SiteContext. Skeleton wraps it as a list of
        // SpineSegments (one per primitive in the path).
        State state = new State(ctx, fmap, level);
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

        // Phase 4a — proactive cross-street planning.
        // Runs BEFORE Phase 3 so foundation buildings (TOWN_HALL etc.)
        // can avoid junctions during candidate scoring. Spine_consumed
        // is estimated from foundation types' frontage rather than read
        // from state.placed (Phase 3 hasn't run yet).
        planCrossStreetsProactively(state, sortedSelection, foundationTypes);

        // Phase 3 — foundation placement. Skeleton already has the
        // spine + cross-streets, so corridor-rejection in
        // findBestCandidate keeps foundation buildings clear of
        // junctions.
        for (BuildingType type : sortedSelection) {
            if (foundationTypes.contains(type)) placeOne(state, type, /*foundation*/ true);
        }

        // Phase 4b — iterative placement. Single-shot, no insertion,
        // no retry. Buildings either fit on the planned skeleton or
        // drop.
        for (BuildingType type : sortedSelection) {
            if (foundationTypes.contains(type)) continue;
            placeOne(state, type, /*foundation*/ false);
        }

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
            frontageOwners.put(pb.frontage().buildingFront(), pb.type());
        }
        RoadNetwork network = new RoadNetwork(state.skeleton, Map.copyOf(frontageOwners));

        LOGGER.info("PhasedPlanner.run done: placed={} dropped={} crossStreets={} viable={}",
                state.placed.size(), state.dropped.size(),
                state.skeleton.crossStreets().size(), state.viable);

        return new Result(placement, network, List.copyOf(state.events));
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
        if (!missing.isEmpty()) {
            state.dropped.add(new DroppedBuilding(type, DropReason.DEPENDENCY_MISSING,
                    "missing: " + missing));
            if (profile.required()) state.viable = false;
            LOGGER.info("dropped {}: DEPENDENCY_MISSING missing={} required={}",
                    type, missing, profile.required());
            return false;
        }

        // Find the best frontage-eligible candidate cell.
        Best best = findBestCandidate(state, type, profile, foundation);
        if (best == null) {
            String detail = foundation
                    ? "no terrain-admissible cell within frontage distance of spine"
                    : "no positive-scoring cell within frontage distance of any segment";
            state.dropped.add(new DroppedBuilding(type, DropReason.NO_VIABLE_CANDIDATE, detail));
            if (profile.required()) state.viable = false;
            LOGGER.info("dropped {}: NO_VIABLE_CANDIDATE phase={} required={} ({})",
                    type, foundation ? "3" : "4b", profile.required(), detail);
            return false;
        }

        // Materialise the placement.
        PlacedBuilding pb = new PlacedBuilding(type, best.pos, best.footprint,
                best.rotation, profile.priority(), best.variantId,
                best.frontage, best.facingRoad);
        state.placed.add(pb);
        state.reservations.add(new Reservation(best.footprintAabb, best.frontageAabb, type));
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
    private static Best findBestCandidate(State state, BuildingType type,
                                          PlacementProfile profile, boolean foundation) {
        List<RoadSegment> roads = foundation
                ? new java.util.ArrayList<>(state.skeleton.spineSegments())
                : state.skeleton.allSegments();
        Best best = null;
        for (int i = 0; i < state.fmap.gridSize(); i++) {
            for (int j = 0; j < state.fmap.gridSize(); j++) {
                Cell cell = state.fmap.cell(i, j);
                BlockCategory cat = cell.category();
                if (cat != BlockCategory.OPEN && cat != BlockCategory.SHORE) continue;
                if (cell.localSlope() > MAX_SLOPE) continue;

                BlockPos pos = state.fmap.cellWorldPos(i, j);

                // Variant + footprint + rotation against the nearest road.
                String variantId = state.variantResolver.pickVariantIdForV2(
                        type, pos, state.ctx.anchor(), state.villageRadius,
                        state.culture, Style.RURAL, state.rng,
                        StructureAvailabilityRegistry.INSTANCE);
                StructureSizeCache.FootprintInfo info = state.sizes.get(state.culture,
                        Style.RURAL, type, variantId, LEVEL, Rotation.NONE);
                Footprint fp = new Footprint(info.width(), info.length());

                NearestRoad nr = nearestRoadOf(pos, roads);
                if (nr == null) continue;
                // Cells exactly on the road centerline can't determine
                // a side reliably; skip.
                if (nr.distance < 1) continue;

                // Frontage distance check: does the cell sit within the
                // building's frontage_distance of the road segment?
                int fpPerp = fp.length();
                int frontageDistance = (nr.segment.width() + 1) / 2 + (fpPerp + 1) / 2;
                if (nr.distance > frontageDistance) continue;

                Rotation rotation = chooseFacing(pos, nr.point);
                Vec3 frontDir = cardinalFrontDir(rotation);

                // Compute the building's geometric centre offset
                // perpendicular FROM the road. Cardinal-snapped frontDir
                // (driving NBT rotation) is NOT a valid basis for
                // geometric offset on diagonal spines — it can decompose
                // mostly along-spine. Use the segment's TRUE
                // perpendicular instead, with the AABB extent PROJECTED
                // onto that perpendicular so the clearance is correct
                // regardless of spine angle.
                double sdx = nr.segment.end().getX() - nr.segment.start().getX();
                double sdz = nr.segment.end().getZ() - nr.segment.start().getZ();
                double slen = Math.sqrt(sdx * sdx + sdz * sdz);
                if (slen < 1e-9) continue;  // degenerate segment
                double pX = -sdz / slen;
                double pZ = sdx / slen;

                // Side: which sign of perpendicular is the cell on?
                double cellPerp = (pos.getX() - nr.point.getX()) * pX
                        + (pos.getZ() - nr.point.getZ()) * pZ;
                int side = cellPerp >= 0 ? +1 : -1;

                // Post-rotation AABB half-extents.
                boolean swap = rotation == Rotation.CLOCKWISE_90
                        || rotation == Rotation.COUNTERCLOCKWISE_90;
                double halfX = (swap ? fp.length() : fp.width()) / 2.0;
                double halfZ = (swap ? fp.width() : fp.length()) / 2.0;

                // AABB projection extent along the true perpendicular.
                // Conservative for diagonal spines (the building's
                // axis-aligned AABB pokes farther into the perpendicular
                // direction than its half-side suggests).
                double extentPerp = Math.abs(halfX * pX) + Math.abs(halfZ * pZ);

                double frontageDepth = nr.segment.width();
                double requiredOffset = nr.segment.width() / 2.0
                        + frontageDepth
                        + extentPerp
                        + 0.5;  // round-up safety margin

                int centreX = nr.point.getX()
                        + (int) Math.round(pX * side * requiredOffset);
                int centreZ = nr.point.getZ()
                        + (int) Math.round(pZ * side * requiredOffset);

                // Verify the computed centre is in scan bounds and on
                // admissible terrain. The cell passed the OPEN/SHORE +
                // slope filter; the centre may be ~25 blocks away on a
                // diagonal spine and could land in water / forest /
                // off-grid.
                if (!state.fmap.inBounds(centreX, centreZ)) continue;
                Cell centreCell = state.fmap.cellAt(centreX, centreZ);
                BlockCategory centreCat = centreCell.category();
                if (centreCat != BlockCategory.OPEN
                        && centreCat != BlockCategory.SHORE) continue;
                if (centreCell.localSlope() > MAX_SLOPE) continue;

                BlockPos centre = new BlockPos(centreX,
                        centreCell.elevationY(), centreZ);

                Aabb fpAabb = footprintAabb(centre, fp, rotation);
                FrontageStrip strip = computeFrontageStrip(centre, fp, rotation,
                        frontDir, nr.segment.width());
                Aabb stripAabb = frontageAabb(strip);

                if (overlapsAnyReservation(fpAabb, stripAabb, state.reservations)) continue;

                // Reject candidates whose AABB intersects ANY road
                // corridor in the skeleton (not just the segment the
                // building is facing). Prevents foundation buildings
                // at the anchor when the anchor is also a junction
                // — TOWN_HALL gets pushed slightly along the spine
                // away from the cross-street. Uses the same shared
                // RoadCorridors utility as OverlapAuditor.
                if (intersectsAnyCorridor(fpAabb, state.skeleton.allSegments())) continue;

                // Score uses the cell's position (the original sampling
                // point). Cell and computed centre are within the same
                // scoring band so the difference is negligible at V1
                // thresholds.
                ScoreBreakdown score = scorePosition(pos, type, profile, state);
                if (score.total() <= 0) continue;

                if (best == null || score.total() > best.score.total()) {
                    best = new Best(centre, fp, rotation, variantId, strip,
                            nr.segment, fpAabb, stripAabb, score);
                }
            }
        }
        return best;
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
            if (!connectedSet.contains(pb.facingRoad())) isolated.add(pb);
        }
        if (isolated.isEmpty()) return;
        for (PlacedBuilding pb : isolated) {
            // Try to rescue with a road_width-cap straight connector.
            // A successful rescue inserts a degenerate CrossStreet.
            // (V1: this branch is rarely exercised; left as defensive.)
            state.placed.remove(pb);
            state.dropped.add(new DroppedBuilding(pb.type(),
                    DropReason.ISOLATED_AFTER_REASSESS,
                    "frontage road no longer in connected skeleton"));
            state.events.add(PhaseEvent.isolated(pb.type()));
            LOGGER.info("phase 5 isolated: dropped {} (frontage road disconnected)",
                    pb.type());
        }
    }

    /** Trim cross streets that no buildings face. Spine path trimming
     *  is deferred — the multi-segment spine path produced by
     *  SpinePathPlanner already truncates on terrain failure, and
     *  finer trimming would require segment-level frontage span
     *  arithmetic that's out of scope for this cycle. */
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
        // segment containing it is reachable via skeleton.spineSegments().
        sk.addJunction(new Junction(state.ctx.anchor(), List.of()));
        for (CrossStreet cs : sk.crossStreets()) {
            // Cross-street junctions list the cross-street and the
            // first spine segment as a stand-in (the precise spine
            // segment that the cross-street meets isn't tracked in
            // V1 — junction membership is mostly for downstream
            // plaza/decoration code).
            List<RoadSegment> meeting = new ArrayList<>();
            if (!sk.spineSegments().isEmpty()) {
                meeting.add(sk.spineSegments().get(0));
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
            adjacency += e.getValue() * Scoring.adjacencyFactor(e.getKey(), pos, type,
                    state.ctx, state.placed, state.skeleton.spineSegments(),
                    state.villageRadius);
        }
        double dist = distance(pos, state.ctx.anchor());
        double radial = 1.0 - Math.min(1.0, dist / Math.max(1, state.villageRadius));
        double centrality = Math.max(0, 1 - Math.abs(profile.centrality() - radial));
        return new ScoreBreakdown(terrain, adjacency, centrality);
    }

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
        }
        return false;
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
        final ServerLevel level;
        final StructureSizeCache sizes;
        final int villageRadius;
        final String culture;
        final Skeleton skeleton;
        final java.util.Random rng;
        final VariantResolver variantResolver = new VariantResolver();
        final List<PlacedBuilding> placed = new ArrayList<>();
        final List<DroppedBuilding> dropped = new ArrayList<>();
        final List<Reservation> reservations = new ArrayList<>();
        final List<PhaseEvent> events = new ArrayList<>();
        boolean viable = true;

        State(SiteContext ctx, V2FeatureMap fmap, ServerLevel level) {
            this.ctx = ctx;
            this.fmap = fmap;
            this.level = level;
            this.sizes = new StructureSizeCache(level);
            this.villageRadius = villageRadiusFor(ctx.tier());
            this.culture = ctx.culture().id();
            this.skeleton = new Skeleton(ctx.spinePath(), SPINE_WIDTH);
            // Salted with PHASED_PLANNER_SALT so cross-street decisions
            // don't share Random state with SiteAnalyzer's inclination
            // sampler (which uses a different salt off the same seed).
            this.rng = new java.util.Random(ctx.seed() ^ PHASED_PLANNER_SALT);
        }
    }

    private static final long PHASED_PLANNER_SALT = 0x504C_414E_4E45_5200L;

    public static int villageRadiusFor(ViabilityTier tier) {
        return switch (tier) {
            case CITY -> 80;
            case TOWN -> 40;
            case HAMLET -> 20;
            case OUTPOST, UNVIABLE -> 10;
        };
    }

    // ----------------------------------- Diagnostics + result types -----------

    public record Result(PlacementResult placement, RoadNetwork network,
                         List<PhaseEvent> events) {}

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

    private record Reservation(Aabb footprint, Aabb frontage, BuildingType type) {}

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
