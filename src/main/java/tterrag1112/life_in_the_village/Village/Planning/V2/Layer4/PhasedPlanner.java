package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.BuildingVariant;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;
import tterrag1112.life_in_the_village.Village.Planning.StructureSizeCache;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.BlockCategory;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.Cell;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.Anchor;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.CardinalAxis;
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
        return run(ctx, fmap, sortedSelection, unavailable, level,
                tradeFulfilledTypes, null);
    }

    /**
     * Residential-variant tooling — overload carrying an optional FORCED
     * residential variant (from {@code /litv district}); null → auto-select.
     * Dev-only; production spawns pass null.
     */
    public static Result run(SiteContext ctx, V2FeatureMap fmap,
                             List<BuildingType> sortedSelection,
                             List<UnavailableBuilding> unavailable,
                             ServerLevel level,
                             Set<BuildingType> tradeFulfilledTypes,
                             ResidentialVariant forcedResidentialVariant) {
        return run(ctx, fmap, sortedSelection, unavailable,
                new StructureSizeCache(level),
                StructureAvailabilityRegistry.INSTANCE,
                tradeFulfilledTypes, forcedResidentialVariant);
    }
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
        return run(ctx, fmap, sortedSelection, unavailable, footprints,
                availability, tradeFulfilledTypes, null);
    }

    /** Headless overload + the forced residential variant (null → auto-select). */
    public static Result run(SiteContext ctx, V2FeatureMap fmap,
                             List<BuildingType> sortedSelection,
                             List<UnavailableBuilding> unavailable,
                             tterrag1112.life_in_the_village.Village.Planning.FootprintProvider footprints,
                             tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.BuildingAvailability availability,
                             Set<BuildingType> tradeFulfilledTypes,
                             ResidentialVariant forcedResidentialVariant) {
        // Spine path is now planned by SiteAnalyzer (Layer 2) and
        // arrives on the SiteContext. Skeleton wraps it as a list of
        // SpineSegments (one per primitive in the path).
        State state = new State(ctx, fmap, footprints, availability);
        state.tradeFulfilledTypes = tradeFulfilledTypes != null
                ? Set.copyOf(tradeFulfilledTypes) : Set.of();
        state.forcedResidentialVariant = forcedResidentialVariant;

        // Stage 4b fix-up — temporary district-only dev mode. When on, filter
        // placement to district-member types only (civic core + market +
        // residential) so Garrett can read the districted work without the
        // rural + loose buildings crowding the view. FARMHOUSE is filtered
        // out, so the rural pass (and the still-rough required-farm gate)
        // never runs. Reversible: with the flag off, `selection` == the
        // untouched `sortedSelection` and behaviour is byte-for-byte today's.
        // The roster/reconciliation upstream is untouched (this filters their
        // already-reconciled output). NOT a permanent roster change.
        List<BuildingType> selection = DISTRICT_ONLY_MODE
                ? sortedSelection.stream().filter(DISTRICT_TYPES::contains).toList()
                : sortedSelection;
        if (DISTRICT_ONLY_MODE) {
            LOGGER.info("DISTRICT_ONLY_MODE on: {} of {} selected types kept "
                    + "(district members only; rural + loose skipped)",
                    selection.size(), sortedSelection.size());
        }

        Set<BuildingType> foundationTypes = computeFoundationTypes(selection);
        LOGGER.info("PhasedPlanner.run: tier={} axis={} anchor=({},{},{})"
                + " selection={} foundation={} unavailable={}",
                ctx.tier(), ctx.primaryAxis(), ctx.anchor().getX(),
                ctx.anchor().getY(), ctx.anchor().getZ(),
                selection.size(), foundationTypes.size(), unavailable.size());
        EnumMap<BuildingType, Integer> selectionCounts = new EnumMap<>(BuildingType.class);
        for (BuildingType t : selection) selectionCounts.merge(t, 1, Integer::sum);
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
        // Layout Rework Stage 4 redesign — reserve the designed central
        // civic district (footprint-sized town square + adjacent
        // footprint-sized market square) as building-less voids BEFORE any
        // building places, so the civic ring members (TOWN_HALL, CHAPEL,
        // INN) ring and front the square rather than landing on the anchor.
        // Sized from the buildings the district must hold (see
        // reserveCivicSquare), not from guessed per-tier baselines.
        reserveCivicSquare(state, selection);

        // Layout Rework Stage 4 redesign — CORE-FIRST batch order. The civic
        // core (batch 3) now places BEFORE the rural nucleus (batch 2) so
        // farmhouses can't wall the civic district off from its zone. After
        // the core is down, addCivicPrecinct fences the precinct (void ∪
        // core footprints ∪ market) off from the rural pass that follows.
        // Topo order WITHIN each batch is unchanged; only the inter-batch
        // sequence of 2 and 3 swaps. Verified no civic-batch building has a
        // requiresPresent dependency on a rural-batch building (all civic
        // PlacementProfiles declare requiresPresent=[]; CASTLE→TOWN_HALL is
        // intra-batch-3), so the swap drops no dependencies.
        final int[] batchOrder = {1, 3, 2, 4, 5, 6};
        int[] perBatchCounts = new int[8];
        for (int batch : batchOrder) {
            for (BuildingType type : selection) {
                if (getBatch(state.ctx, type) != batch) continue;
                // Residential HOUSEs are placed EXPLICITLY by the variant
                // arranger (in reserveResidentialDistricts, at the batch-3 hook
                // below) when districts exist — skip the emergent batch-5 pass
                // so they aren't double-placed.
                if (type == BuildingType.HOUSE && !state.residentialGates.isEmpty()) {
                    continue;
                }
                boolean foundation = (batch == 1 || batch == 2)
                        || foundationTypes.contains(type);
                if (placeOne(state, type, foundation)) perBatchCounts[batch]++;
            }
            // Core-first: once the civic core is placed, derive the precinct
            // AABB, then reserve the residential districts in the ring beyond
            // it. Both fence the rural nucleus (placed next) off, so farms go
            // beyond residential; HOUSE (batch 5) then fills the districts.
            if (batch == 3) {
                addCivicPrecinct(state);
                reserveResidentialDistricts(state, selection);
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
        // Courtyard blocks suppress the emergent per-house branching: their
        // houses are served by the deliberate ring path, so the router skips
        // their terminals (they still connect through the district node).
        List<Polygon.AABB> courtyardBlocks = state.courtyardDecor.stream()
                .map(CourtyardDecor::block).toList();
        NetworkSpec routed = BlockServingRouter.route(
                state.placed, ctx.gateways(), fmap, ctx.anchor(), state.voids(),
                districtConnectionNodes(state), courtyardBlocks);
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
                java.util.Set.copyOf(state.droppedBindings),
                state.civicSquare, state.marketSquare,
                List.copyOf(state.internalLanes),
                List.copyOf(state.courtyardDecor));
    }

    // =========================================================================
    // Phase 3 + 4 — placement
    // =========================================================================

    /** Tries to place one instance of {@code type}; returns true on
     *  success. On failure, appends to {@code state.dropped}. */
    private static boolean placeOne(State state, BuildingType type, boolean foundation) {
        PlacementProfile profile = PlacementDefaults.get(type);
        if (profile == null) {
            state.dropped.add(new DroppedBuilding(type, DropReason.NOT_SELECTED,
                    "no PlacementProfile authored"));
            return false;
        }

        // Stage 4b — single central market (cap 1). CITY rosters select
        // MARKET=2; the fix-up-#7 binding would bind BOTH halls to the same
        // sub-district centre → fatal footprint overlap → spawn aborts. Drop
        // every MARKET past the first (MARKET isn't required, so the village
        // stays viable; multi-market districts are a deferred feature).
        if (type == BuildingType.MARKET) {
            for (PlacedBuilding pb : state.placed) {
                if (pb.type() == BuildingType.MARKET) {
                    state.dropped.add(new DroppedBuilding(type,
                            DropReason.NO_VIABLE_CANDIDATE,
                            "single central market — extra MARKET dropped (cap 1)"));
                    LOGGER.info("dropped extra MARKET: cap 1 (one central market)");
                    return false;
                }
            }
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
            // Fix-up #5 — honest, zone-era wording. Post-3c placement is
            // ZONE-based (candidate cells are grid cells gated by buildable +
            // zone/square membership + reservation overlap), NOT network-edge
            // based. The pre-3c "no admissible candidate position on any
            // network edge" wording mis-led a diagnosis; corrected here.
            String detail = "no admissible candidate cell in "
                    + (foundation ? "foundation zone/ring" : "target zone/ring")
                    + " (clear of reservations)";
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

        // Stage 4b — required-farm gate (no stray farmhouses). A FARMHOUSE is
        // its homestead-with-fields; if no viable field parcel could be
        // reserved (even shrunk to the minimum box, clear of districts/other
        // reservations + low-slope), DROP the farmhouse rather than shipping a
        // fieldless stray. Non-fatal (FARMHOUSE isn't required); also trims
        // CITY's farm over-supply. Every SHIPPED farm now has a bounded
        // parcel, so the post-spawn FarmComplexPlanner flood-fill stays inside
        // a district-clear box (kills the SEED_NOT_ADMISSIBLE strays).
        if (type == BuildingType.FARMHOUSE && cp == null) {
            state.dropped.add(new DroppedBuilding(type,
                    DropReason.NO_VIABLE_COMPLEX_PARCEL,
                    "no viable farm-field parcel (clear of districts/terrain)"));
            LOGGER.info("dropped FARMHOUSE: NO_VIABLE_COMPLEX_PARCEL (no field box fits)");
            return false;
        }

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

        // Fix-up #7 — the MARKET hall is BOUND to the centre of its reserved
        // sub-district (sized to hold the hall + stall-pad apron), not
        // scattered by the generic civic scorer. This is what lets the stall
        // pad grade clear (no NO_REGION): the hall and the pad share one
        // reserved void. Falls through to the generic scan only if the
        // square centre is unusable terrain.
        if (type == BuildingType.MARKET && state.marketSquare != null) {
            Best bound = boundMarketBest(state);
            if (bound != null) {
                if (debugCands) {
                    LOGGER.info("candidates type=MARKET BOUND to sub-district centre "
                            + "({},{})", bound.pos().getX(), bound.pos().getZ());
                }
                return bound;
            }
        }

        ZonePartition zp = state.ctx.zonePartition();
        // The zone role this building wants (its nucleus-affinity kind);
        // null when un-affined, or when no zone of that kind exists — in
        // which case the gate is "any zoned (in-village) cell". Computed
        // even when bound, so a bound civic building still ring-gates.
        NucleusKind targetKind = targetZoneKind(state, type, zp);

        // Layout Rework Stage 4 redesign — designed-district ring gate.
        // The plaza RING MEMBERS (TOWN_HALL, CHAPEL, INN) ring the civic void:
        // the gate is a placement DISC around the square whose radius was
        // sized from the member footprints in reserveCivicSquare
        // (state.civicRingRadius) — wide enough for the largest member to seat
        // AROUND the void. overlapsAnyReservation still keeps the footprint OUT
        // of the void, so they front the square; the nucleus score pulls them
        // to the void edge (hugging the perimeter). This supersedes both the
        // zone gate and the binding cutoff for them. Other CIVIC-affinity
        // buildings (BLACKSMITH, BAKERY, GUILD_HALL, …) are NOT ring-gated —
        // they fall through to the normal zone gate. (MARKET is handled above:
        // bound to its sub-district centre, not ring-gated — fix-up #7.)
        BlockPos squareCentre = null;
        int ringRadius = 0;
        if (RING_MEMBERS.contains(type) && state.civicSquare != null) {
            squareCentre = squareCentreOf(state.civicSquare);
            ringRadius = state.civicRingRadius;
        }
        final long ringRsq = squareCentre != null
                ? (long) ringRadius * ringRadius
                : 0;

        // Layout Rework Stage 4 redesign — rural exclusion. Rural-nucleus
        // types (batch 2 — typically FARMHOUSE) place AFTER the civic core
        // (core-first reorder); the civic precinct is off-limits to them so
        // farmhouses don't intrude. Stage 4b — the residential district gates
        // are also off-limits to rural, so farms place BEYOND residential.
        final boolean ruralType = getBatch(state.ctx, type) == 2;
        final Polygon.AABB precinct = state.civicPrecinct;

        // Stage 4b — HOUSE inclusion. When residential districts exist, HOUSE
        // is gated INTO them (cell must lie in some district gate AABB),
        // superseding its RURAL zone gate. overlapsAnyReservation keeps the
        // house off the central yard void, so houses RING the yard; once a
        // district's ring fills, the scorer's best admissible cell moves to
        // the next district (capacity ≈ CAP/district, total ≥ houseCount).
        final boolean houseGated = type == BuildingType.HOUSE
                && !state.residentialGates.isEmpty();

        Best best = null;
        for (int i = 0; i < state.fmap.gridSize(); i++) {
            for (int j = 0; j < state.fmap.gridSize(); j++) {
                cellsScanned++;
                Cell cell = state.fmap.cell(i, j);
                if (!ZonePartition.isBuildable(cell)) continue;

                BlockPos pos = state.fmap.cellWorldPos(i, j);

                // Rural exclusion: keep the rural nucleus out of the civic
                // precinct AND the residential district gates (Stage 4b) so
                // farms place beyond every district.
                if (ruralType
                        && ((precinct != null
                                && insideAabb(pos.getX(), pos.getZ(), precinct))
                            || insideAny(pos.getX(), pos.getZ(), state.residentialGates))) {
                    rejZone++;
                    continue;
                }

                if (houseGated) {
                    // HOUSE inclusion gate (supersedes the RURAL zone gate):
                    // the cell must lie inside a residential district gate.
                    if (!insideAny(pos.getX(), pos.getZ(), state.residentialGates)) {
                        rejZone++;
                        continue;
                    }
                } else if (squareCentre != null) {
                    // Square-ring gate (supersedes zone + binding): centre
                    // must lie within the placement disc around the square.
                    long ddx = pos.getX() - squareCentre.getX();
                    long ddz = pos.getZ() - squareCentre.getZ();
                    if (ddx * ddx + ddz * ddz > ringRsq) { rejZone++; continue; }
                } else if (boundPos != null) {
                    // Primary-binding strict cutoff: bound types restrict to
                    // within the binding radius; the binding — not the zone —
                    // dictates location, so the zone gate is skipped.
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

    // =========================================================================
    // Stage 4 redesign — footprint-driven civic district
    // =========================================================================

    /** Gap (blocks) between the civic square and the adjacent market square. */
    private static final int CIVIC_SQUARE_GAP = 4;
    /** Slack (blocks per side) added to the market sub-district rectangle
     *  beyond the full-margin complex pad, so the pad fits strictly inside the
     *  reservation (no edge-clip → no NO_REGION). */
    private static final int MARKET_PAD_SLACK = 2;

    /** Stage 4 redesign — the plaza RING MEMBERS: the civic buildings that
     *  ring and front the central town square. The square is sized so all
     *  of these (that the village actually selects) can seat around its
     *  perimeter. Other CIVIC-affinity buildings are not ring members; they
     *  distribute across the CIVIC zone via the normal zone gate. */
    private static final EnumSet<BuildingType> RING_MEMBERS = EnumSet.of(
            BuildingType.TOWN_HALL, BuildingType.CHAPEL, BuildingType.INN);

    /** Stage 4 redesign — gap (blocks) reserved along the plaza perimeter
     *  between adjacent ring members, so they don't abut. */
    private static final int PLAZA_GAP = 3;

    /** Stage 4 redesign — minimum plaza half-extent (blocks). Floors the
     *  footprint-derived size so the void is always large enough to read as
     *  a plaza (and never degenerate — see the paves-0 note in
     *  {@link #reserveCivicSquare}). */
    private static final int MIN_PLAZA_HALF = 5;

    /** Stage 4 redesign — extra ring width (blocks) added beyond the deepest
     *  member footprint when sizing the placement disc, so a member centred
     *  just outside the void still has slack to seat without clipping. */
    private static final int RING_SLACK = 4;

    /** Stage 4 redesign — padding (blocks) added per member when sizing the
     *  district. The DOCUMENTED FALLBACK for unresolvable max-variant
     *  footprints: the planner's only sanctioned access to building
     *  dimensions is {@link
     *  tterrag1112.life_in_the_village.Village.Planning.FootprintProvider},
     *  which resolves ONE {@code variantId} at a time and exposes no variant
     *  enumeration — so the MAX-variant footprint is not resolvable
     *  pre-placement. We size from the DEFAULT-variant footprint
     *  ({@link BuildingVariant#defaultVariantId}) plus this pad to absorb
     *  larger-than-default variants the resolver may pick at placement. */
    private static final int LARGE_VARIANT_PAD = 4;

    /** Stage 4 redesign — the footprint-derived size of a district: the void
     *  half-extent and the placement-ring radius members are admitted in. */
    private record Sized(int half, int ring) {}

    /** Stage 4 redesign — default-variant footprint for {@code type} through
     *  the sanctioned {@link
     *  tterrag1112.life_in_the_village.Village.Planning.FootprintProvider}
     *  seam (works identically on the live + headless paths). */
    private static StructureSizeCache.FootprintInfo defaultFootprint(
            State state, BuildingType type) {
        return state.sizes.get(state.culture, Style.RURAL, type,
                BuildingVariant.defaultVariantId(type), LEVEL, Rotation.NONE);
    }

    /**
     * Stage 4 redesign — size a designed district to the buildings it must
     * hold. The members ring a central square; the square's perimeter
     * ({@code 8·half}) must hold the sum of each member's frontage (its
     * footprint width) plus a {@link #PLAZA_GAP} and a {@link
     * #LARGE_VARIANT_PAD}. The void side must also be at least the widest
     * member. The placement ring extends out by the deepest member footprint
     * plus slack so members can centre just outside the void.
     *
     * <p>Clamped to {@code [MIN_PLAZA_HALF, villageRadius/2]} so a
     * footprint-large district never overflows the village radius at small
     * tiers (where the radius, not the footprints, is the binding limit).
     */
    private static Sized sizeDistrictToMembers(State state,
                                               EnumSet<BuildingType> members) {
        double perim = 0;
        int maxWidth = 0;
        int maxDepth = 0;
        for (BuildingType m : members) {
            StructureSizeCache.FootprintInfo fp = defaultFootprint(state, m);
            int w = fp.width();
            int l = fp.length();
            perim += w + PLAZA_GAP + LARGE_VARIANT_PAD;
            maxWidth = Math.max(maxWidth, w);
            maxDepth = Math.max(maxDepth, l);
        }
        int half = (int) Math.ceil(perim / 8.0);
        half = Math.max(half, (int) Math.ceil(maxWidth / 2.0));
        half = Math.max(MIN_PLAZA_HALF, half);
        int cap = Math.max(MIN_PLAZA_HALF, state.villageRadius / 2);
        half = Math.min(half, cap);
        int ring = half + maxDepth + LARGE_VARIANT_PAD + RING_SLACK;
        return new Sized(half, ring);
    }

    /**
     * Fix-up — the MARKET sub-district as a RECTANGLE matching the real
     * market-complex footprint, NOT a bloated square. The complex is the hall
     * (≈21×42) plus the concentric stall-pad apron ({@code footprint +
     * padMargin} per side, the way {@code MarketComplexPlanner} grades it). A
     * square (half = maxDim/2 + padMargin) over-reserved the SHORT axis into a
     * giant empty paved lot; matching the complex's real width AND length
     * removes that waste. The hall faces the anchor, so its LENGTH runs ALONG
     * the offset axis (toward the core) and its WIDTH ACROSS it. Returns
     * {@code {alongHalf, acrossHalf}} (+{@link #MARKET_PAD_SLACK} so the
     * full-margin pad fits strictly inside → no {@code NO_REGION}). {@code
     * padMargin} is read from the authored {@code MarketComplexSpec} — the same
     * value the realiser pads with — so the reservation IS the real complex,
     * not a square that either wastes space or clips the complex.
     *
     * <p>Deliberately NOT clamped to {@code villageRadius/2}: the hall is a
     * fixed-size building, so the rectangle must be ≥ the complex regardless of
     * tier. If the centre isn't buildable the caller skips it (hall falls back
     * to the generic placement).
     */
    private static int[] marketDistrictHalves(State state) {
        StructureSizeCache.FootprintInfo fp =
                defaultFootprint(state, BuildingType.MARKET);
        int padMargin = MarketComplexRegistry.get(state.culture, BuildingType.MARKET)
                .map(MarketComplexSpec::padMargin).orElse(10);
        int alongHalf = (int) Math.ceil(fp.length() / 2.0) + padMargin + MARKET_PAD_SLACK;
        int acrossHalf = (int) Math.ceil(fp.width() / 2.0) + padMargin + MARKET_PAD_SLACK;
        return new int[]{Math.max(MIN_PLAZA_HALF, alongHalf),
                         Math.max(MIN_PLAZA_HALF, acrossHalf)};
    }

    /**
     * Fix-up #7 — the MARKET hall, bound to the centre of its reserved
     * sub-district (rather than scattered by the generic civic scorer).
     * The void was reserved clear and sized to hold the hall + pad apron,
     * so the hall lands in the middle of its stall plaza and the pad grades
     * clear around it. Returns null if the square centre isn't usable (the
     * caller then falls back to the generic candidate scan). The returned
     * {@link Best} has null frontage/facingRoad (filled by the post-routing
     * orientation pass, like every other building).
     */
    private static Best boundMarketBest(State state) {
        BlockPos c = squareCentreOf(state.marketSquare);   // y == 0
        int mx = c.getX();
        int mz = c.getZ();
        if (!state.fmap.inBounds(mx, mz)) return null;
        Cell cell = state.fmap.cellAt(mx, mz);
        BlockPos centre = new BlockPos(mx, cell.elevationY(), mz);
        String variantId = state.variantResolver.pickVariantIdForV2(
                BuildingType.MARKET, centre, state.ctx.anchor(), state.villageRadius,
                state.culture, Style.RURAL, state.rng, state.availability);
        StructureSizeCache.FootprintInfo info = state.sizes.get(state.culture,
                Style.RURAL, BuildingType.MARKET, variantId, LEVEL, Rotation.NONE);
        Footprint fp = new Footprint(info.width(), info.length());
        Rotation rotation = chooseFacing(centre, state.ctx.anchor());
        Aabb fpAabb = footprintAabb(centre, fp, rotation);
        // Nominal score — the hall is bound, not scored against alternatives.
        ScoreBreakdown score = new ScoreBreakdown(1.0, 0.0, 0.0);
        return new Best(centre, fp, rotation, variantId,
                /*frontage*/ null, /*facingRoad*/ null,
                fpAabb, /*frontageAabb*/ fpAabb, score);
    }

    /**
     * Stage 4 redesign — reserve the central civic district as building-less
     * voids BEFORE any building places. The civic square is sized to the
     * plaza {@link #RING_MEMBERS} the village actually selects; the adjacent
     * market square is sized to the MARKET footprint. The reservation gate
     * ({@code overlapsAnyReservation}) keeps building footprints out of the
     * voids, so the ring members front the square (TOWN_HALL pushed off the
     * centre — authentic market-square layout). The squares are stored on
     * {@code state} for the router (obstacle mask) and for emission as CIVIC
     * / MARKET plazas; their ring radii drive the placement-disc gate in
     * {@link #findBestCandidate}.
     *
     * <p>Paves-0 note: the void is sized via {@link #sizeDistrictToMembers},
     * which floors {@code half} at {@link #MIN_PLAZA_HALF}, so the emitted
     * CIVIC polygon is always non-degenerate (≥ 11×11) — the prior
     * zone-fraction / merge sizing could collapse the void on thin-CIVIC
     * sites, which the paver then paved 0 blocks for.
     *
     * <p>The market square is offset along the axis perpendicular to the
     * primary axis (so it sits beside the civic square, off the gateway
     * line), and is only reserved when MARKET is selected and its centre is
     * admissible terrain (else the market falls back to its prior seeding).
     */
    private static void reserveCivicSquare(State state, List<BuildingType> selection) {
        BlockPos anchor = state.ctx.anchor();

        EnumSet<BuildingType> present = EnumSet.noneOf(BuildingType.class);
        present.addAll(selection);

        // Civic square — sized to the ring members the village selects.
        // Always keep at least TOWN_HALL so the district has a real centre
        // even on rosters that drop the other two.
        EnumSet<BuildingType> ring = EnumSet.noneOf(BuildingType.class);
        for (BuildingType t : RING_MEMBERS) if (present.contains(t)) ring.add(t);
        if (ring.isEmpty()) ring.add(BuildingType.TOWN_HALL);
        Sized civic = sizeDistrictToMembers(state, ring);
        Polygon.AABB civicAabb = squareAt(anchor.getX(), anchor.getZ(), civic.half());
        state.civicSquare = civicAabb;
        state.civicRingRadius = civic.ring();
        // Void reservation: footprint == frontage == the square; type null
        // (a void has no building — type is never dereferenced).
        state.reservations.add(new Reservation(toAabb(civicAabb), toAabb(civicAabb), null));
        LOGGER.info("civic square: centre=({},{}) half={} ring={} members={}",
                anchor.getX(), anchor.getZ(), civic.half(), civic.ring(), ring);

        // Market sub-district — only when MARKET is selected. Fix-up: a
        // RECTANGLE matching the real complex (hall + stall-pad apron), seated
        // beside the civic square on the axis perpendicular to the primary
        // axis. The hall faces the anchor, so its LENGTH runs along the offset
        // axis (alongHalf) and its WIDTH across it (acrossHalf) — see
        // marketDistrictHalves + boundMarketBest's chooseFacing.
        if (!present.contains(BuildingType.MARKET)) {
            LOGGER.info("market square: skipped (MARKET not selected)");
            return;
        }
        int[] mh = marketDistrictHalves(state);
        int alongHalf = mh[0], acrossHalf = mh[1];
        boolean axisX = state.ctx.primaryAxis() == CardinalAxis.X;
        // Offset is on the axis perpendicular to the primary axis; the hall's
        // LENGTH lies along that offset axis.
        int halfX = axisX ? acrossHalf : alongHalf;
        int halfZ = axisX ? alongHalf : acrossHalf;
        int offset = civic.half() + CIVIC_SQUARE_GAP + alongHalf;
        int mx = anchor.getX() + (axisX ? 0 : offset);  // perpendicular to primary axis
        int mz = anchor.getZ() + (axisX ? offset : 0);
        boolean reserved = false;
        if (state.fmap.inBounds(mx, mz)) {
            Cell c = state.fmap.cellAt(mx, mz);
            BlockCategory cat = c.category();
            boolean buildable = (cat == BlockCategory.OPEN || cat == BlockCategory.SHORE)
                    && c.localSlope() <= MAX_SLOPE;
            if (buildable) {
                Polygon.AABB marketAabb = new Polygon.AABB(
                        mx - halfX, mz - halfZ, mx + halfX, mz + halfZ);
                state.marketSquare = marketAabb;
                state.reservations.add(
                        new Reservation(toAabb(marketAabb), toAabb(marketAabb), null));
                reserved = true;
            }
        }
        LOGGER.info("market sub-district: {} ({}x{})",
                reserved ? "reserved at (" + mx + "," + mz + ")" : "skipped (terrain)",
                2 * halfX, 2 * halfZ);
    }

    /**
     * Stage 4 redesign — derive the civic precinct AABB after the civic core
     * places (core-first reorder). The precinct is the union of the civic
     * void and the batch-3 (civic) building footprints; it fences the rural
     * nucleus (batch 2, placed next) out of the district so farmhouses don't
     * intrude. Stored on {@code state}; consulted only by rural-type
     * placement in {@link #findBestCandidate}.
     *
     * <p>Fix-up #7 — the MARKET void and the MARKET hall are deliberately
     * EXCLUDED. The market is now a footprint-sized satellite sub-district
     * that can sit well off the anchor (offset ≈ civicHalf + gap +
     * marketHalf); folding it into the precinct would balloon the rural
     * exclusion into a giant civic-to-market rectangle and push every
     * farmhouse to the fringe. The market void is still a {@link
     * Reservation}, so buildings (incl. farmhouses) already avoid it — it
     * just doesn't widen the rural-exclusion box.
     */
    private static void addCivicPrecinct(State state) {
        boolean any = false;
        int minX = 0, minZ = 0, maxX = 0, maxZ = 0;
        if (state.civicSquare != null) {
            Polygon.AABB v = state.civicSquare;
            minX = v.minX(); minZ = v.minZ(); maxX = v.maxX(); maxZ = v.maxZ();
            any = true;
        }
        for (PlacedBuilding pb : state.placed) {
            if (getBatch(state.ctx, pb.type()) != 3) continue;
            if (pb.type() == BuildingType.MARKET) continue;   // satellite — see javadoc
            Aabb fp = footprintAabb(pb.centre(), pb.footprint(), pb.rotation());
            if (!any) { minX = fp.minX(); minZ = fp.minZ(); maxX = fp.maxX(); maxZ = fp.maxZ(); any = true; }
            else {
                minX = Math.min(minX, fp.minX()); minZ = Math.min(minZ, fp.minZ());
                maxX = Math.max(maxX, fp.maxX()); maxZ = Math.max(maxZ, fp.maxZ());
            }
        }
        if (!any) return;
        state.civicPrecinct = new Polygon.AABB(minX, minZ, maxX, maxZ);
        LOGGER.info("civic precinct: ({},{})..({},{})", minX, minZ, maxX, maxZ);
    }

    // =========================================================================
    // Stage 4b — residential districts (footprint-sized HOUSE blocks)
    // =========================================================================

    // =========================================================================
    // Stage 4b — residential districts (footprint-sized HOUSE blocks)
    // =========================================================================

    /** Stage 4b fix-up — temporary district-only dev mode. When true, only the
     *  district-member types ({@link #DISTRICT_TYPES}) place; the rural pass +
     *  loose buildings are filtered out so the districted work is legible in
     *  isolation. Default on for now (Garrett flips it off to restore the full
     *  village). Reversible: off ⇒ today's behaviour exactly.
     *
     *  <p>Public so the spawner adapter can RELAX the Layer-5 viability abort
     *  while it's on: CITY's diversity minimum (6 distinct types) exceeds the
     *  {@link #DISTRICT_TYPES} count (5), so a district-only CITY is
     *  legitimately "not viable" by the full-village rule — the adapter logs
     *  and proceeds instead of aborting (it's an intentional partial village). */
    public static final boolean DISTRICT_ONLY_MODE = true;
    /** The district-member types kept under {@link #DISTRICT_ONLY_MODE}: civic
     *  core (TOWN_HALL, CHAPEL, INN), market (MARKET), residential (HOUSE). */
    private static final EnumSet<BuildingType> DISTRICT_TYPES = EnumSet.of(
            BuildingType.TOWN_HALL, BuildingType.CHAPEL, BuildingType.INN,
            BuildingType.MARKET, BuildingType.HOUSE);

    /** Houses per residential district. Kept SMALL (4) so a block holding the
     *  big HOUSE footprint (≈20×11) stays a compact ~44×44 — small enough to
     *  seat in the band between the footprint-sized core and the grid edge
     *  (the CAP-5 ring block was ~64×64 and reserved nothing). More houses ⇒
     *  more small blocks, fanned around the core. 4c's designed block (BSP
     *  plots + shared yard + well + fenced borders) replaces this. */
    private static final int RESIDENTIAL_BLOCK_TARGET = 4;
    /** Phase 1 — minimum houses needed to seat an OVERFLOW district (the first
     *  district always seats). A sub-minimum remainder after at least one
     *  district is an acceptable drop, rather than a runt district of 1. */
    private static final int MIN_DISTRICT_HOUSES = 3;
    /** Gap (blocks) between packed houses within a block, and the block's outer
     *  margin. */
    private static final int HOUSE_GAP = 2;
    /** Clearance (blocks) between a residential district and other districts
     *  when seating it center-out. */
    private static final int DISTRICT_GAP = 4;

    /**
     * Stage 4b (fix-up) — reserve the residential districts BEFORE the rural
     * nucleus places, so houses group into COMPACT blocks instead of
     * scattering, and farms place beyond them.
     *
     * <p>Fix-up: the original ring arrangement wrapped the houses around a big
     * central yard, inflating a 3–7 house block to ~52–64 blocks square — too
     * big to clear the footprint-sized core, so it reserved NOTHING ({@code 0
     * reserved}) and houses fell back to scatter. The block is now PACKED IN
     * ROWS (a tight grid sized to the house footprints + small gaps), so a
     * 3-house block is ~one short row and a 7-house block ~two short rows — small
     * enough to seat. No yard void this pass (the shared yard + well + the
     * pretty block is 4c). The block's gate AABB is the HOUSE placement region
     * (inclusion, supersedes the RURAL zone gate) AND a rural/farm EXCLUSION.
     * Houses fill the block via the scorer + overlap-packing; leftover open
     * cells are discovered as a green by {@link ParkCandidateFinder}. Districts
     * seat center-out on a ray fan, scanning past the civic core's reservations
     * (they may sit in the civic precinct's empty corners — residential isn't
     * rural). 4c builds the designed interior.
     */
    private static void reserveResidentialDistricts(State state,
                                                    List<BuildingType> selection) {
        int houseCount = 0;
        for (BuildingType t : selection) if (t == BuildingType.HOUSE) houseCount++;
        if (houseCount == 0) {
            LOGGER.info("residential districts: none (no HOUSE selected)");
            return;
        }
        // Phase 1 — size the cell from the LARGEST footprint in the HOUSE
        // variant pool, not one default. The actual house variant is resolved
        // per-house later (pickVariantIdForV2), and the pool has real size
        // spread; a cell sized to the biggest house can hold any resolved house
        // without overstepping → materializeHouse no longer drops on size.
        StructureSizeCache.FootprintInfo hf = largestHouseFootprint(state);
        int cellPitch = Math.max(hf.width(), hf.length()) + HOUSE_GAP;
        int houseDepth = hf.length();

        BlockPos anchor = state.ctx.anchor();
        // Nominal district count for fanning the seating bearings around the
        // core (overflow may add more; seatDistrict sweeps all bearings anyway).
        int nEstimate = Math.max(1,
                (houseCount + RESIDENTIAL_BLOCK_TARGET - 1) / RESIDENTIAL_BLOCK_TARGET);

        // Fill-to-capacity with an overflow queue: size each district for
        // min(TARGET, remaining), subtract the ACTUAL placed (so a house lost to
        // terrain stays in remaining and re-homes into the next district), and
        // loop until done / out of space / a district places nothing.
        int remaining = houseCount;
        int reserved = 0;
        int placedHouses = 0;
        boolean noSpace = false;
        for (int k = 0; remaining > 0; k++) {
            // The first district always seats; overflow districts only when the
            // remainder is worth a district (avoids a runt of 1–2).
            if (reserved > 0 && remaining < MIN_DISTRICT_HOUSES) break;
            int target = Math.min(RESIDENTIAL_BLOCK_TARGET, remaining);
            int[] hd = districtHalfDims(target, cellPitch);
            int halfX = hd[0], halfZ = hd[1];
            int reach = Math.max(halfX, halfZ);
            int innerR = reach + DISTRICT_GAP;
            int outerR = Math.max(innerR, state.fmap.radius() - reach);
            double startAngle = (2 * Math.PI * k) / nEstimate + Math.PI / 4.0;
            Polygon.AABB gate = seatDistrict(
                    state, anchor, startAngle, innerR, outerR, halfX, halfZ);
            if (gate == null) { noSpace = true; break; }  // no open space left
            reserved++;
            int placed = placeArrangedBlock(state, gate, target,
                    cellPitch, houseDepth, k);
            if (placed == 0) { noSpace = true; break; }   // guard infinite loop
            placedHouses += placed;
            remaining -= placed;
        }
        LOGGER.info("residential districts: {} assigned / {} districts / {} placed"
                + " / {} dropped (cellPitch={}, target={})",
                houseCount, reserved, placedHouses, remaining, cellPitch,
                RESIDENTIAL_BLOCK_TARGET);
        if (noSpace && remaining > 0) {
            LOGGER.warn("residential: dropped {} house(s) — no open space for an"
                    + " overflow district (assigned={}, placed={})",
                    remaining, houseCount, placedHouses);
        } else if (remaining > 0) {
            LOGGER.info("residential: dropped {} house(s) — sub-minimum remainder"
                    + " (< {})", remaining, MIN_DISTRICT_HOUSES);
        }
    }

    /** Phase 1 — the LARGEST footprint across the HOUSE variant pool for the
     *  village culture/style, so a residential cell sized to it holds any
     *  resolved house without overlap. Falls back to the default footprint if
     *  the pool can't be enumerated. */
    private static StructureSizeCache.FootprintInfo largestHouseFootprint(State state) {
        java.util.Set<String> pool = state.availability.availableVariants(
                state.culture, Style.RURAL, BuildingType.HOUSE, LEVEL);
        StructureSizeCache.FootprintInfo max = null;
        int maxDim = -1;
        for (String variantId : pool) {
            StructureSizeCache.FootprintInfo fp = state.sizes.get(state.culture,
                    Style.RURAL, BuildingType.HOUSE, variantId, LEVEL, Rotation.NONE);
            int dim = Math.max(fp.width(), fp.length());
            if (dim > maxDim) { maxDim = dim; max = fp; }
        }
        return max != null ? max : defaultFootprint(state, BuildingType.HOUSE);
    }

    /** Near-square block half-dims (+ cols/rows) holding {@code houses} cells of
     *  {@code cellPitch}, floored at {@link #MIN_PLAZA_HALF}. Returns
     *  {@code {halfX, halfZ, cols, rows}}. */
    private static int[] districtHalfDims(int houses, int cellPitch) {
        int cols = (int) Math.ceil(Math.sqrt(Math.max(1, houses)));
        int rows = (houses + cols - 1) / cols;
        int halfX = Math.max(MIN_PLAZA_HALF, (cols * cellPitch + HOUSE_GAP) / 2);
        int halfZ = Math.max(MIN_PLAZA_HALF, (rows * cellPitch + HOUSE_GAP) / 2);
        return new int[]{halfX, halfZ, cols, rows};
    }

    /**
     * Layout Rework — arranges one residential block's houses by its variant
     * (forced via {@code /litv district}, else auto-selected by shape + seed)
     * and places them EXPLICITLY (position + facing), bypassing the emergent
     * {@code findBestCandidate} scorer. Returns the count actually placed (an
     * arranged house that collides a prior reservation or hits bad terrain is
     * skipped). The decorative render (lane / tofts / well / borders) is a
     * staged follow-up — this pass produces house positions + facings only.
     */
    private static int placeArrangedBlock(State state, Polygon.AABB gate,
                                          int houses, int cellPitch,
                                          int houseDepth, int blockIndex) {
        if (houses <= 0) return 0;
        long seed = state.ctx.seed()
                ^ ((long) gate.minX() * 31L + gate.minZ())
                ^ ((long) blockIndex << 20);
        ResidentialVariant variant = state.forcedResidentialVariant != null
                ? state.forcedResidentialVariant
                : ResidentialArranger.autoSelect(gate, seed);
        // The lane connects to the block's road-facing edge node so the footpath
        // flows out to the main street (same node the router branches to).
        BlockPos edgeNode = edgePointToward(gate, state.ctx.anchor());
        ResidentialArranger.Arrangement arr = ResidentialArranger.arrange(
                gate, houses, cellPitch, houseDepth, edgeNode, variant);
        int placed = 0;
        List<Polygon.AABB> footprints = new ArrayList<>(arr.houses().size());
        for (ResidentialArranger.HousePlacement p : arr.houses()) {
            Polygon.AABB fp = materializeHouse(state, p.centre(), p.faceTarget());
            if (fp != null) { placed++; footprints.add(fp); }
        }
        // Carry the variant's internal lanes to the render pass: truncate at the
        // placed footprints (so the courtyard entry never crosses a house — a
        // no-op for the street-row lane, which runs the open central gap), snap
        // to the surface (floor-Y) and tag the FOOTPATH tier.
        List<List<BlockPos>> blockLanes = new ArrayList<>();
        for (List<BlockPos> lane : arr.lanes()) {
            // A closed loop (the courtyard ring) is placed deliberately inside
            // the house fronts — don't truncate it (that would break the loop);
            // only open paths (entry/lane) clip at the house ring.
            boolean closed = lane.size() > 2
                    && lane.get(0).equals(lane.get(lane.size() - 1));
            List<BlockPos> trimmed = closed
                    ? lane : truncateAtFootprints(lane, footprints);
            List<BlockPos> snapped = snapPathToSurface(state, trimmed);
            if (snapped.size() >= 2) {
                state.internalLanes.add(new InternalPath(snapped,
                        RoadShape.RoadTier.FOOTPATH));
                blockLanes.add(snapped);
            }
        }
        // COURTYARD decoration (well + border enclosure) carried to render. The
        // well sits on the surface (floor-Y +1, matching the civic plaza
        // fountain); the borders gap wherever a path crosses (blockLanes).
        if (arr.yardCentre() != null) {
            BlockPos yard = arr.yardCentre();
            int floorY = (state.fmap.inBounds(yard.getX(), yard.getZ())
                    ? state.fmap.cellAt(yard.getX(), yard.getZ()).elevationY()
                    : yard.getY()) + 1;
            state.courtyardDecor.add(new CourtyardDecor(
                    new BlockPos(yard.getX(), floorY, yard.getZ()), gate,
                    List.copyOf(footprints), List.copyOf(blockLanes), seed));
        }
        LOGGER.info("residential block #{} variant={} houses={}/{} lanes={} yard={} centre=({},{})",
                blockIndex, variant, placed, houses, arr.lanes().size(),
                arr.yardCentre() != null, (gate.minX() + gate.maxX()) / 2,
                (gate.minZ() + gate.maxZ()) / 2);
        return placed;
    }

    /** Truncates a raw (y=0) centerline before the first point that enters any
     *  footprint AABB, so an internal path never crosses a house. Returns the
     *  clear prefix (the edge-node end is preserved; the inner end is clipped at
     *  the house ring). A no-op when no segment hits a footprint. */
    private static List<BlockPos> truncateAtFootprints(List<BlockPos> pts,
                                                       List<Polygon.AABB> fps) {
        if (fps.isEmpty() || pts.size() < 2) return pts;
        List<BlockPos> kept = new ArrayList<>();
        kept.add(pts.get(0));
        for (int i = 1; i < pts.size(); i++) {
            BlockPos a = pts.get(i - 1), b = pts.get(i);
            int steps = (int) Math.ceil(Math.sqrt(a.distSqr(b)));
            BlockPos prev = a;
            for (int s = 1; s <= Math.max(1, steps); s++) {
                int x = a.getX() + (b.getX() - a.getX()) * s / Math.max(1, steps);
                int z = a.getZ() + (b.getZ() - a.getZ()) * s / Math.max(1, steps);
                if (insideAnyFootprint(x, z, fps)) {
                    if (!prev.equals(kept.get(kept.size() - 1))) kept.add(prev);
                    return kept;
                }
                prev = new BlockPos(x, 0, z);
            }
            kept.add(b);
        }
        return kept;
    }

    private static boolean insideAnyFootprint(int x, int z, List<Polygon.AABB> fps) {
        for (Polygon.AABB fp : fps) {
            if (x >= fp.minX() && x <= fp.maxX() && z >= fp.minZ() && z <= fp.maxZ()) {
                return true;
            }
        }
        return false;
    }

    /** Snaps a raw (y=0) internal-path centerline to the cell surface so the
     *  footpath sits on the ground (floor-Y convention), not buried. */
    private static List<BlockPos> snapPathToSurface(State state, List<BlockPos> pts) {
        List<BlockPos> out = new ArrayList<>(pts.size());
        for (BlockPos p : pts) {
            if (state.fmap.inBounds(p.getX(), p.getZ())) {
                int y = state.fmap.cellAt(p.getX(), p.getZ()).elevationY();
                out.add(new BlockPos(p.getX(), y, p.getZ()));
            } else {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * Places a single arranged HOUSE at {@code centre0} (XZ; Y snapped to the
     * cell surface) facing {@code faceTarget}. Resolves the variant + footprint
     * through the same seam {@code findBestCandidate} uses; skips (returns
     * {@code null}) on non-buildable terrain or a reservation collision. Returns
     * the placed footprint AABB (for border skip-tests). Frontage / facingRoad
     * are filled later by the orientation pass, like every building.
     */
    private static Polygon.AABB materializeHouse(State state, BlockPos centre0,
                                                 BlockPos faceTarget) {
        int x = centre0.getX(), z = centre0.getZ();
        if (!state.fmap.inBounds(x, z)) return null;
        Cell cell = state.fmap.cellAt(x, z);
        BlockCategory cat = cell.category();
        if (!(cat == BlockCategory.OPEN || cat == BlockCategory.SHORE)
                || cell.localSlope() > MAX_SLOPE) return null;
        PlacementProfile profile = PlacementDefaults.get(BuildingType.HOUSE);
        if (profile == null) return null;

        BlockPos centre = new BlockPos(x, cell.elevationY(), z);
        String variantId = state.variantResolver.pickVariantIdForV2(
                BuildingType.HOUSE, centre, state.ctx.anchor(), state.villageRadius,
                state.culture, Style.RURAL, state.rng, state.availability);
        StructureSizeCache.FootprintInfo info = state.sizes.get(state.culture,
                Style.RURAL, BuildingType.HOUSE, variantId, LEVEL, Rotation.NONE);
        Footprint fp = new Footprint(info.width(), info.length());
        Rotation rotation = chooseFacing(centre, faceTarget);
        Aabb fpAabb = footprintAabb(centre, fp, rotation);
        if (overlapsAnyReservation(fpAabb, fpAabb, state.reservations)) return null;

        PlacedBuilding pb = new PlacedBuilding(BuildingType.HOUSE, centre, fp,
                rotation, profile.priority(), variantId, null, null);
        state.placed.add(pb);
        state.reservations.add(new Reservation(fpAabb, fpAabb, BuildingType.HOUSE));
        state.events.add(PhaseEvent.placed(BuildingType.HOUSE, false,
                new ScoreBreakdown(1.0, 0.0, 0.0)));
        return new Polygon.AABB(fpAabb.minX(), fpAabb.minZ(),
                fpAabb.maxX(), fpAabb.maxZ());
    }

    /** Number of bearings swept when seating a residential block. */
    private static final int DISTRICT_ANGLE_STEPS = 24;

    /** Seats one residential district. Fix-up: SWEEPS all bearings (preferring
     *  {@code startAngle}, then rotating around) × radii outward, taking the
     *  first buildable centre whose block AABB clears every prior reservation
     *  (civic + market voids, placed footprints) and other districts. The
     *  earlier one-bearing-per-block search left later blocks unseated when
     *  their single bearing was obstructed (e.g. by the market satellite or
     *  the civic ring) — the sweep seats all N as long as any clear spot
     *  exists. The civic PRECINCT is NOT a barrier (a block may sit in its
     *  empty corners). On success records the gate (no void reserved — houses
     *  FILL the block this pass) and returns it; null if nothing fits. */
    private static Polygon.AABB seatDistrict(State state, BlockPos anchor,
            double startAngle, int innerR, int outerR, int halfX, int halfZ) {
        for (int a = 0; a < DISTRICT_ANGLE_STEPS; a++) {
            double angle = startAngle + (2 * Math.PI * a) / DISTRICT_ANGLE_STEPS;
            double cos = Math.cos(angle), sin = Math.sin(angle);
            for (int r = innerR; r <= outerR; r += 4) {
                int cx = anchor.getX() + (int) Math.round(cos * r);
                int cz = anchor.getZ() + (int) Math.round(sin * r);
                if (!state.fmap.inBounds(cx, cz)) continue;
                Cell c = state.fmap.cellAt(cx, cz);
                BlockCategory cat = c.category();
                if (!(cat == BlockCategory.OPEN || cat == BlockCategory.SHORE)
                        || c.localSlope() > MAX_SLOPE) continue;
                Polygon.AABB gate = new Polygon.AABB(cx - halfX, cz - halfZ,
                        cx + halfX, cz + halfZ);
                if (aabbOverlapsAnyReservation(toAabb(gate), state.reservations)) continue;
                if (aabbOverlapsAny(gate, state.residentialGates)) continue;
                // Record the gate for HOUSE inclusion + rural/farm exclusion. No
                // void reservation: houses fill the block (the gate is NOT a
                // Reservation, so houses can place inside it).
                state.residentialGates.add(gate);
                LOGGER.info("residential district seated: centre=({},{}) block={}x{} r={}",
                        cx, cz, 2 * halfX, 2 * halfZ, r);
                return gate;
            }
        }
        return null;
    }

    /** XZ overlap of two {@link Polygon.AABB}s. */
    private static boolean aabbsOverlapXZ(Polygon.AABB a, Polygon.AABB b) {
        return a.minX() <= b.maxX() && a.maxX() >= b.minX()
                && a.minZ() <= b.maxZ() && a.maxZ() >= b.minZ();
    }

    /** True iff {@code box} overlaps any AABB in {@code others}. */
    private static boolean aabbOverlapsAny(Polygon.AABB box, List<Polygon.AABB> others) {
        for (Polygon.AABB o : others) if (aabbsOverlapXZ(box, o)) return true;
        return false;
    }

    /** True iff {@code (x,z)} lies inside {@code a} (inclusive). */
    private static boolean insideAabb(int x, int z, Polygon.AABB a) {
        return x >= a.minX() && x <= a.maxX() && z >= a.minZ() && z <= a.maxZ();
    }

    /** True iff {@code (x,z)} lies inside any AABB in {@code regions}. */
    private static boolean insideAny(int x, int z, List<Polygon.AABB> regions) {
        for (Polygon.AABB a : regions) if (insideAabb(x, z, a)) return true;
        return false;
    }

    /** True iff the parcel {@code box} overlaps any district (civic precinct
     *  or a residential gate) — neither is a full {@link Reservation}, so
     *  parcel reservation checks them separately. */
    private static boolean overlapsAnyDistrict(State state, Aabb box) {
        if (state.civicPrecinct != null && aabbOverlapsPoly(box, state.civicPrecinct)) {
            return true;
        }
        for (Polygon.AABB g : state.residentialGates) {
            if (aabbOverlapsPoly(box, g)) return true;
        }
        return false;
    }

    private static boolean aabbOverlapsPoly(Aabb a, Polygon.AABB b) {
        return a.minX() <= b.maxX() && a.maxX() >= b.minX()
                && a.minZ() <= b.maxZ() && a.maxZ() >= b.minZ();
    }

    /** Roads fix-up — each district's road-facing connection node (the point on
     *  its AABB boundary nearest the anchor / main street). The router latches a
     *  JUNCTION terminal onto each so every district connects to the network and
     *  to its neighbours. The civic precinct is omitted — it surrounds the
     *  anchor (the trunk hub), so it's connected by construction. */
    private static List<BlockPos> districtConnectionNodes(State state) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos anchor = state.ctx.anchor();
        if (state.marketSquare != null) out.add(edgePointToward(state.marketSquare, anchor));
        for (Polygon.AABB gate : state.residentialGates) {
            out.add(edgePointToward(gate, anchor));
        }
        return out;
    }

    /** The point on {@code aabb}'s boundary closest to {@code target} (clamp);
     *  when {@code target} is outside the box this lies on the road-facing edge. */
    private static BlockPos edgePointToward(Polygon.AABB aabb, BlockPos target) {
        int x = Math.max(aabb.minX(), Math.min(aabb.maxX(), target.getX()));
        int z = Math.max(aabb.minZ(), Math.min(aabb.maxZ(), target.getZ()));
        return new BlockPos(x, target.getY(), z);
    }

    private static Polygon.AABB squareAt(int cx, int cz, int half) {
        return new Polygon.AABB(cx - half, cz - half, cx + half, cz + half);
    }

    private static Aabb toAabb(Polygon.AABB a) {
        return new Aabb(a.minX(), a.minZ(), a.maxX(), a.maxZ());
    }

    /** Centre (XZ) of a square void AABB; Y is irrelevant (used only for
     *  the planar ring-distance test). */
    private static BlockPos squareCentreOf(Polygon.AABB a) {
        return new BlockPos((a.minX() + a.maxX()) / 2, 0, (a.minZ() + a.maxZ()) / 2);
    }

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
            // Stage 4b — the FARM field parcel must also clear the districts
            // (civic precinct + residential gates), which aren't full
            // Reservations. The post-spawn FarmComplexPlanner bounds the
            // flood-fill to this parcel, so a district-clear parcel keeps the
            // field off houses/plazas (kills SEED_NOT_ADMISSIBLE strays).
            if (kind == Parcel.Kind.FARM && overlapsAnyDistrict(state, box)) continue;
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
        /** Stage 4a — the designed central town square + adjacent market
         *  square (nullable). Reserved as building-less voids before
         *  placement; passed to the router as obstacles and emitted as
         *  CIVIC / MARKET plazas. */
        Polygon.AABB civicSquare;
        Polygon.AABB marketSquare;
        /** Stage 4 redesign — placement-disc radius (blocks) the civic ring
         *  members are admitted within, sized from member footprints in
         *  {@code reserveCivicSquare}. Consulted by {@code findBestCandidate}.
         *  (Fix-up #7 — the market hall binds to its sub-district centre, so
         *  it no longer needs a ring radius.) */
        int civicRingRadius;
        /** Stage 4 redesign — civic precinct AABB (void ∪ core footprints ∪
         *  market), derived once the civic core places (core-first reorder).
         *  Off-limits to the rural nucleus that places next; null until then. */
        Polygon.AABB civicPrecinct;
        /** Stage 4b — residential district block AABBs: the HOUSE placement
         *  regions (inclusion, supersedes the RURAL zone gate) AND rural/farm
         *  EXCLUSIONs (so farms go beyond), mirroring the civic precinct.
         *  Houses FILL these (no yard void this pass — 4c adds the designed
         *  interior); leftover open cells become a green via
         *  ParkCandidateFinder. Reserved after the civic core, before rural. */
        final List<Polygon.AABB> residentialGates = new ArrayList<>();
        /** Residential-variant tooling — forced variant from /litv district
         *  (null → auto-select per block). */
        ResidentialVariant forcedResidentialVariant;
        /** Internal-path lanes emitted by residential variants (street-row lane
         *  + courtyard entry path) — rendered at FOOTPATH tier. */
        final List<InternalPath> internalLanes = new ArrayList<>();
        /** COURTYARD decoration (well + border enclosure) — rendered by the
         *  adapter via the plaza well stamp + farm border generators. */
        final List<CourtyardDecor> courtyardDecor = new ArrayList<>();

        /** The reserved square voids, for the router's obstacle mask. */
        List<Polygon.AABB> voids() {
            List<Polygon.AABB> out = new ArrayList<>(2);
            if (civicSquare != null) out.add(civicSquare);
            if (marketSquare != null) out.add(marketSquare);
            return out;
        }

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
                         java.util.Set<BuildingType> droppedBindings,
                         /* Stage 4a — designed-core squares (nullable); the
                          * adapter turns these into CIVIC / MARKET PlazaRegions. */
                         Polygon.AABB civicSquare,
                         Polygon.AABB marketSquare,
                         /* Layout Rework — residential variant internal lanes
                          * (footpath tier); the adapter renders these through
                          * VillageRoadRealizer.realizePaths. */
                         List<InternalPath> internalLanes,
                         /* Layout Rework — COURTYARD decoration (well + borders);
                          * the adapter stamps the well + paints borders. */
                         List<CourtyardDecor> courtyardDecor) {
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

        /** Pre-Stage-4a 5-arg constructor (no designed-core squares). */
        public Result(PlacementResult placement, RoadNetwork network,
                      List<PhaseEvent> events,
                      java.util.Map<PlacedBuilding, NucleusContext> nucleusContexts,
                      java.util.Set<BuildingType> droppedBindings) {
            this(placement, network, events, nucleusContexts, droppedBindings,
                    null, null, List.of(), List.of());
        }

        /** Pre-Layout-Rework 7-arg constructor (no internal lanes). */
        public Result(PlacementResult placement, RoadNetwork network,
                      List<PhaseEvent> events,
                      java.util.Map<PlacedBuilding, NucleusContext> nucleusContexts,
                      java.util.Set<BuildingType> droppedBindings,
                      Polygon.AABB civicSquare, Polygon.AABB marketSquare) {
            this(placement, network, events, nucleusContexts, droppedBindings,
                    civicSquare, marketSquare, List.of(), List.of());
        }

        /** Pre-courtyard-decor 8-arg constructor (lanes but no courtyard decor). */
        public Result(PlacementResult placement, RoadNetwork network,
                      List<PhaseEvent> events,
                      java.util.Map<PlacedBuilding, NucleusContext> nucleusContexts,
                      java.util.Set<BuildingType> droppedBindings,
                      Polygon.AABB civicSquare, Polygon.AABB marketSquare,
                      List<InternalPath> internalLanes) {
            this(placement, network, events, nucleusContexts, droppedBindings,
                    civicSquare, marketSquare, internalLanes, List.of());
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

        // Stage 3e — capacityPlan / proactiveInsertedAt / proactiveSkippedAtParam
        // factories removed with the dead cross-street pre-pass. The Kind
        // values (CAPACITY_PLAN / PROACTIVE_CROSS_STREET / PROACTIVE_SKIPPED)
        // are retained — LayoutCommand still switches over them — they simply
        // have no producer now.

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
