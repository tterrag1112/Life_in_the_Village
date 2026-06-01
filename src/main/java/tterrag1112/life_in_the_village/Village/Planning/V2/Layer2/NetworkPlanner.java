package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Track E1 prompt-3 — village road-network grower.
 *
 * <p>Reads the strategy decision from {@link SiteContext#strategy()}
 * and produces a {@link NetworkSpec} according to the strategy's
 * {@link LayoutTopology}. Replaces the linear-spine
 * {@code SpinePathPlanner} that V2 used through prompt 2.
 *
 * <h3>Recipes</h3>
 * One method per topology. Each returns a {@link NetworkSpec}
 * built with the small {@link Builder} helper at the bottom of
 * this class. Tier-scaled numbers (Ring radius, spine length, etc.)
 * are drawn from the recipe RNG so two same-topology villages on
 * different sites read as different.
 *
 * <h3>Primitive constraint</h3>
 * Each recipe respects {@code strategy.intendedPrimitives}: if a
 * recipe would emit a primitive type the strategy hasn't declared,
 * it degrades to an emitted-allowed alternative (typically
 * {@code StraightRoad}). For example, an HAUFENDORF strategy that
 * doesn't list {@code Ring} produces a short spine + spurs.
 *
 * <h3>Determinism</h3>
 * The recipe RNG is salted from {@link SiteContext#seed()} via
 * {@link #NETWORK_PLANNER_SALT}; two spawns on the same site with
 * the same seed produce identical specs.
 *
 * <h3>Degenerate inputs</h3>
 * No primary anchor → falls back to the site context's main
 * anchor. No secondaries / gateways → recipe shrinks to "anchor +
 * minimal road" (single node, zero edges in the worst case). All
 * recipes always return a non-null spec.
 */
public final class NetworkPlanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkPlanner.class);

    /** Seed salt — keeps the planner RNG independent of any other
     *  per-seed sampler (inclination, spine length, etc.). */
    public static final long NETWORK_PLANNER_SALT = 0x4E45_5457_4F52_4B30L;

    /** Default painted-strip width per edge. Matches PhasedPlanner.SPINE_WIDTH. */
    public static final int DEFAULT_EDGE_WIDTH = 3;

    /** Subtle drift on freshly-generated centerlines so straight
     *  recipe edges read as natural-walked rather than ruler-drawn. */
    private static final double EDGE_DRIFT = 4.0;

    /** Quality bar a secondary anchor must clear to attach via Spur. */
    private static final double SECONDARY_QUALITY_FLOOR = 0.20;

    /** Spurs whose attachment-to-anchor distance is below this are
     *  redundant — the Ring or central road already reaches the
     *  secondary's footprint. */
    private static final int MIN_SPUR_LENGTH = 6;

    /** Spurs longer than this are too long for a village-internal
     *  road; the secondary is probably outside the village radius
     *  and shouldn't attach. */
    private static final int MAX_SPUR_LENGTH = 48;

    private NetworkPlanner() {}

    // =========================================================================
    // Public entry
    // =========================================================================

    public static NetworkSpec plan(SiteContext ctx, V2FeatureMap fmap, long seed) {
        Random rng = new Random(seed ^ NETWORK_PLANNER_SALT);
        StrategySelectionResult selection = ctx.strategy();
        LayoutStrategy strategy = selection != null ? selection.strategy() : null;
        LayoutTopology topology = strategy != null
                ? strategy.topology() : LayoutTopology.CLUSTER;
        Set<String> allowed = strategy != null
                ? strategy.primitives()
                : Set.of("StraightRoad", "Spur");

        BlockPos primaryPos = selection != null && selection.primaryAnchor() != null
                ? selection.primaryAnchor().centre() : ctx.anchor();
        Anchor primaryAnchor = selection != null ? selection.primaryAnchor() : null;
        List<Anchor> secondaries = selection != null
                ? selection.secondaryAnchors() : List.of();

        // Gateways: consume the gateways-first derivation (Stage 3b —
        // GatewayPlanner) carried on the SiteContext. Recipes that
        // don't need gateways simply ignore them. The pair is symmetric
        // so both axis-aligned and feature-aligned spines have well-
        // defined endpoints to attach trade roads to. The fallback
        // (derive inline) keeps any caller that didn't pre-populate the
        // gateways working with byte-identical positions.
        Gateways gateways = ctx.gateways() != null
                ? ctx.gateways()
                : GatewayPlanner.derive(primaryPos, ctx.primaryAxis(), ctx.tier());
        BlockPos primaryGateway = gateways.primary();
        BlockPos secondaryGateway = gateways.secondary();

        Builder b = new Builder(topology);
        switch (topology) {
            case HAUFENDORF -> recipeHaufendorf(b, ctx, primaryPos, primaryAnchor,
                    secondaries, primaryGateway, secondaryGateway, allowed, rng);
            case REIHENDORF -> recipeReihendorf(b, ctx, primaryPos, primaryAnchor,
                    secondaries, primaryGateway, secondaryGateway, allowed, rng);
            case ANGERDORF -> recipeAngerdorf(b, ctx, primaryPos, primaryAnchor,
                    secondaries, primaryGateway, secondaryGateway, allowed, rng);
            case RUNDLING -> recipeRundling(b, ctx, primaryPos, primaryAnchor,
                    secondaries, primaryGateway, secondaryGateway, allowed, rng);
            case EINZELHOF -> recipeEinzelhof(b, ctx, primaryPos, primaryAnchor,
                    secondaries, primaryGateway, secondaryGateway, allowed, rng);
            case CLUSTER -> recipeCluster(b, ctx, primaryPos, primaryAnchor,
                    secondaries, primaryGateway, secondaryGateway, allowed, rng);
        }

        // Primary bindings — lead types from strategy.bindings only.
        if (strategy != null) addPrimaryBindings(b, ctx, strategy);

        NetworkSpec spec = b.build();
        LOGGER.info("network: {}, {} nodes, {} edges, {} primary bindings",
                spec.topology(), spec.nodes().size(), spec.edges().size(),
                spec.primaryBindings().size());
        return spec;
    }

    /** Derive a {@link SpinePath} from a {@link NetworkSpec} for
     *  backwards-compat with consumers that read the legacy spine.
     *  The path's segments are the network's edges in insertion
     *  order; start/end are the first and last node positions. */
    public static SpinePath deriveSpinePath(NetworkSpec spec, CardinalAxis axis,
                                            BlockPos fallbackAnchor) {
        if (spec.edges().isEmpty()) {
            BlockPos seed = spec.nodes().isEmpty()
                    ? fallbackAnchor : spec.nodes().get(0).pos();
            return new SpinePath(axis, List.of(), seed, seed, 0);
        }
        List<RoadPrimitive> prims = new ArrayList<>(spec.edges().size());
        int totalLength = 0;
        for (NetworkEdge e : spec.edges()) {
            prims.add(e.primitive());
            try {
                totalLength += e.primitive().intendedLength();
            } catch (UnsupportedOperationException ignored) {
                // Some primitives (Spur, ArmApproach, etc.) implement
                // intendedLength; others may not. Defensive fallback
                // adds a small constant so totalLength remains
                // monotone-increasing.
                totalLength += 10;
            }
        }
        BlockPos start = endpointStart(spec.edges().get(0));
        BlockPos end = endpointEnd(spec.edges().get(spec.edges().size() - 1));
        if (start == null) start = fallbackAnchor;
        if (end == null) end = fallbackAnchor;
        return new SpinePath(axis, prims, start, end, Math.max(0, totalLength));
    }

    // =========================================================================
    // Recipes
    // =========================================================================

    /**
     * HAUFENDORF — organic heap nucleus.
     * Ring around the primary if Ring is allowed; otherwise a short
     * straight road through the primary. Spurs reach out to each
     * secondary; ArmApproach edges (or short Straights) reach to
     * the two gateways.
     */
    private static void recipeHaufendorf(Builder b, SiteContext ctx,
            BlockPos primaryPos, Anchor primary, List<Anchor> secondaries,
            BlockPos primaryGateway, BlockPos secondaryGateway,
            Set<String> allowed, Random rng) {
        String primaryNodeId = b.addAnchor(primaryPos, primary);
        boolean useRing = allowed.contains("Ring");

        if (useRing) {
            int[] r = ringRadiusRange(ctx.tier(), false);
            int radius = sampleInt(r[0], r[1], rng);
            // Ring edge connects the centre anchor to itself
            // (loop edge). The from/to node ids point at the same
            // anchor — downstream consumers treat this as a closed
            // loop centred on the anchor.
            b.addEdge(new RoadPrimitive.Ring(primaryPos, radius,
                    EDGE_DRIFT, RoadShape.RoadTier.VILLAGE_ROAD),
                    primaryNodeId, primaryNodeId);

            // Spurs to secondaries: attach at the nearest point on
            // the Ring perimeter to each secondary's centre.
            for (Anchor sec : filterSecondaries(secondaries)) {
                BlockPos secPos = sec.centre();
                BlockPos onRing = projectOntoRing(primaryPos, radius, secPos);
                int spurLen = chebDist(onRing, secPos);
                if (spurLen < MIN_SPUR_LENGTH || spurLen > MAX_SPUR_LENGTH) continue;
                if (!allowed.contains("Spur")) continue;
                String synId = b.addSynthetic(onRing);
                String anchorId = b.addAnchor(secPos, sec);
                double dirRad = bearingRad(onRing, secPos);
                // Centerline param: synthesise parent centerline for
                // Spur from the Ring's sample at (synthetic) point.
                List<BlockPos> parentCenterline = ringSamplePoints(
                        primaryPos, radius, 16);
                b.addEdge(new RoadPrimitive.Spur(parentCenterline, onRing,
                        dirRad, spurLen, EDGE_DRIFT,
                        RoadShape.RoadTier.VILLAGE_ROAD),
                        synId, anchorId);
            }

            // Track E1 prompt 6 — arterial radials radiating outward
            // from the Ring perimeter. TOWN = 3, CITY = 5, HAMLET =
            // 0 (per-secondary Spurs above stay the only secondary
            // edges at HAMLET, preserving the small-village geometry).
            // Each radial: StraightRoad from Ring perimeter to a new
            // JUNCTION node at (radialEnd = ringPerim + outwardLen),
            // plus optionally a short outer Spur perpendicular at the
            // endpoint representing an outer cluster of houses. The
            // 5-radial starburst at CITY hits ~380 blocks of arterial
            // frontage, enough to host the new CITY composition's
            // 60-65 building budget.
            emitRadialsFromRing(b, ctx, primaryPos, radius, secondaries,
                    LayoutTopology.HAUFENDORF, /*emitOuterSpurs*/ true,
                    allowed, rng);

            // Gateways via ArmApproach / Straight.
            addRingGateway(b, primaryPos, radius, primaryGateway,
                    "PRIMARY", ctx, allowed);
            addRingGateway(b, primaryPos, radius, secondaryGateway,
                    "SECONDARY", ctx, allowed);
            return;
        }

        // Fallback: no Ring → short spine through the anchor.
        recipeShortSpine(b, ctx, primaryPos, primaryNodeId,
                secondaries, primaryGateway, secondaryGateway, allowed, rng);
    }

    /**
     * REIHENDORF — linear along a feature.
     * Spine along the primary anchor's {@code dir} if linear;
     * otherwise along {@code ctx.primaryAxis()}. Length scales with
     * tier. Spurs perpendicular to the spine for each secondary.
     */
    private static void recipeReihendorf(Builder b, SiteContext ctx,
            BlockPos primaryPos, Anchor primary, List<Anchor> secondaries,
            BlockPos primaryGateway, BlockPos secondaryGateway,
            Set<String> allowed, Random rng) {
        // Spine direction: prefer the primary anchor's dir when it's
        // a linear anchor (RIDGE_LINE / VALLEY_FLOOR / WATER_EDGE).
        double dirX, dirZ;
        if (primary != null && primary.hasOrientation()) {
            dirX = primary.dirX(); dirZ = primary.dirZ();
        } else {
            dirX = ctx.primaryAxis() == CardinalAxis.X ? 1 : 0;
            dirZ = ctx.primaryAxis() == CardinalAxis.Z ? 1 : 0;
        }
        int[] lenRange = spineLengthRange(ctx.tier());
        int total = sampleInt(lenRange[0], lenRange[1], rng);
        int half = total / 2;
        BlockPos start = step(primaryPos, -dirX, -dirZ, half);
        BlockPos end = step(primaryPos, dirX, dirZ, total - half);

        String startNode = b.addNode("synthetic:spine_start", start, NodeKind.SYNTHETIC);
        String anchorNode = b.addAnchor(primaryPos, primary);
        String endNode = b.addNode("synthetic:spine_end", end, NodeKind.SYNTHETIC);

        // Spine: two segments meeting at the anchor so secondaries
        // can attach at the anchor cleanly. Use CurvedRoad if
        // allowed and a curve would help; otherwise StraightRoad.
        String spineType = allowed.contains("CurvedRoad")
                ? "CurvedRoad" : "StraightRoad";
        addLinearEdge(b, spineType, start, primaryPos, startNode, anchorNode, rng);
        addLinearEdge(b, spineType, primaryPos, end, anchorNode, endNode, rng);

        // Spurs perpendicular to the spine for each secondary.
        if (allowed.contains("Spur")) {
            List<BlockPos> spineCenterline = List.of(start, primaryPos, end);
            for (Anchor sec : filterSecondaries(secondaries)) {
                BlockPos secPos = sec.centre();
                BlockPos onSpine = projectOntoSegment(start, end, secPos);
                int spurLen = chebDist(onSpine, secPos);
                if (spurLen < MIN_SPUR_LENGTH || spurLen > MAX_SPUR_LENGTH) continue;
                String synId = b.addSynthetic(onSpine);
                String anchorId = b.addAnchor(secPos, sec);
                b.addEdge(new RoadPrimitive.Spur(spineCenterline, onSpine,
                        bearingRad(onSpine, secPos), spurLen, EDGE_DRIFT,
                        RoadShape.RoadTier.VILLAGE_ROAD),
                        synId, anchorId);
            }
        }

        // Track E1 prompt 6 — arterial cross-street branches
        // alternating sides of the spine at evenly-spaced fractions.
        // TOWN = 3 cross-streets at fractions {1/4, 2/4, 3/4}, CITY
        // = 5 at {1/6, 2/6, ..., 5/6}. The cross at fraction = 1/2
        // attaches at the primary anchor itself; others land at
        // synthetic spine points. Side alternates so the resulting
        // shape stays balanced — a star of branches off the spine
        // rather than all on one side. Length comes from
        // crossStreetLengthRange and is capped by Spur's
        // MAX_SPUR_LENGTH = 48.
        if (allowed.contains("Spur")) {
            int crossCount = radialCountFor(LayoutTopology.REIHENDORF, ctx.tier());
            if (crossCount > 0) {
                int[] crossLen = crossStreetLengthRange(ctx.tier());
                List<BlockPos> spineCl = List.of(start, primaryPos, end);
                double spineLen = Math.sqrt(
                        (double) horizDistSq(start, end));
                double spineDX = (end.getX() - start.getX()) / Math.max(1.0, spineLen);
                double spineDZ = (end.getZ() - start.getZ()) / Math.max(1.0, spineLen);
                // Perpendicular: rotate spine direction by +90°.
                double perpDX = -spineDZ;
                double perpDZ = spineDX;
                for (int i = 0; i < crossCount; i++) {
                    double frac = (i + 1) / (double) (crossCount + 1);
                    BlockPos attach = new BlockPos(
                            start.getX() + (int) Math.round(spineDX * frac * spineLen),
                            primaryPos.getY(),
                            start.getZ() + (int) Math.round(spineDZ * frac * spineLen));
                    int sideSign = (i % 2 == 0) ? +1 : -1;
                    int len = sampleInt(crossLen[0], crossLen[1], rng);
                    if (len < MIN_SPUR_LENGTH) continue;
                    if (len > MAX_SPUR_LENGTH) len = MAX_SPUR_LENGTH;
                    BlockPos crossEnd = new BlockPos(
                            attach.getX() + (int) Math.round(perpDX * sideSign * len),
                            attach.getY(),
                            attach.getZ() + (int) Math.round(perpDZ * sideSign * len));
                    // Mid-spine cross-street attaches at primary; the
                    // off-mid ones get synthetic anchor points.
                    String attachId = (chebDist(attach, primaryPos)
                            < MIN_SPUR_LENGTH)
                            ? anchorNode
                            : b.addSynthetic(attach);
                    String endId = b.addJunction(crossEnd);
                    b.addEdge(new RoadPrimitive.Spur(spineCl, attach,
                            bearingRad(attach, crossEnd), len, EDGE_DRIFT,
                            RoadShape.RoadTier.VILLAGE_ROAD),
                            attachId, endId);
                }
            }
        }

        // Gateways live at the spine endpoints; no extra edges needed.
        // Mark them as GATEWAY nodes for the visualizer.
        b.relabel(startNode, "gateway:SECONDARY", NodeKind.GATEWAY);
        b.relabel(endNode, "gateway:PRIMARY", NodeKind.GATEWAY);
    }

    /**
     * ANGERDORF — Ring around a focal plaza, with optional Arc inside.
     */
    private static void recipeAngerdorf(Builder b, SiteContext ctx,
            BlockPos primaryPos, Anchor primary, List<Anchor> secondaries,
            BlockPos primaryGateway, BlockPos secondaryGateway,
            Set<String> allowed, Random rng) {
        String primaryNodeId = b.addAnchor(primaryPos, primary);
        if (!allowed.contains("Ring")) {
            recipeShortSpine(b, ctx, primaryPos, primaryNodeId,
                    secondaries, primaryGateway, secondaryGateway, allowed, rng);
            return;
        }
        int[] r = ringRadiusRange(ctx.tier(), true);  // larger ring
        int radius = sampleInt(r[0], r[1], rng);
        b.addEdge(new RoadPrimitive.Ring(primaryPos, radius, EDGE_DRIFT,
                RoadShape.RoadTier.VILLAGE_ROAD),
                primaryNodeId, primaryNodeId);
        // Inner Arc — quarter-arc decorative element when allowed.
        if (allowed.contains("Arc") && radius >= 14) {
            int innerR = Math.max(4, radius / 3);
            double startAngle = rng.nextDouble() * 2 * Math.PI;
            b.addEdge(new RoadPrimitive.Arc(primaryPos, innerR, startAngle,
                    Math.PI / 2, EDGE_DRIFT, RoadShape.RoadTier.VILLAGE_ROAD),
                    primaryNodeId, primaryNodeId);
        }
        // Spurs from Ring to each secondary outside it.
        if (allowed.contains("Spur")) {
            List<BlockPos> ringCenterline = ringSamplePoints(primaryPos, radius, 16);
            for (Anchor sec : filterSecondaries(secondaries)) {
                BlockPos secPos = sec.centre();
                int distSq = horizDistSq(primaryPos, secPos);
                if (distSq < (long) radius * radius) continue;  // inside ring
                BlockPos onRing = projectOntoRing(primaryPos, radius, secPos);
                int spurLen = chebDist(onRing, secPos);
                if (spurLen < MIN_SPUR_LENGTH || spurLen > MAX_SPUR_LENGTH) continue;
                String synId = b.addSynthetic(onRing);
                String anchorId = b.addAnchor(secPos, sec);
                b.addEdge(new RoadPrimitive.Spur(ringCenterline, onRing,
                        bearingRad(onRing, secPos), spurLen, EDGE_DRIFT,
                        RoadShape.RoadTier.VILLAGE_ROAD),
                        synId, anchorId);
            }
        }

        // Track E1 prompt 6 — arterial radials outside the Ring.
        // ANGERDORF gets the same radial starburst as HAUFENDORF
        // but WITHOUT the outer-Spur tassels: the inner Arc already
        // handles ANGERDORF's decorative role and outer Spurs would
        // overload the visual. TOWN = 3, CITY = 5.
        emitRadialsFromRing(b, ctx, primaryPos, radius, secondaries,
                LayoutTopology.ANGERDORF, /*emitOuterSpurs*/ false,
                allowed, rng);

        addRingGateway(b, primaryPos, radius, primaryGateway, "PRIMARY",
                ctx, allowed);
        addRingGateway(b, primaryPos, radius, secondaryGateway, "SECONDARY",
                ctx, allowed);
    }

    /**
     * RUNDLING — concentric rings with gateway approaches.
     */
    private static void recipeRundling(Builder b, SiteContext ctx,
            BlockPos primaryPos, Anchor primary, List<Anchor> secondaries,
            BlockPos primaryGateway, BlockPos secondaryGateway,
            Set<String> allowed, Random rng) {
        String primaryNodeId = b.addAnchor(primaryPos, primary);
        if (!allowed.contains("Ring")) {
            recipeShortSpine(b, ctx, primaryPos, primaryNodeId,
                    secondaries, primaryGateway, secondaryGateway, allowed, rng);
            return;
        }
        // Inner ring (keep perimeter).
        int[] innerR = innerRingRange(ctx.tier());
        int inner = sampleInt(innerR[0], innerR[1], rng);
        b.addEdge(new RoadPrimitive.Ring(primaryPos, inner, EDGE_DRIFT / 2,
                RoadShape.RoadTier.VILLAGE_ROAD),
                primaryNodeId, primaryNodeId);

        // Outer ring (wall perimeter) — skip at HAMLET / OUTPOST.
        int outer = 0;
        if (ctx.tier() == ViabilityTier.TOWN || ctx.tier() == ViabilityTier.CITY) {
            int[] outerR = outerRingRange(ctx.tier());
            outer = sampleInt(outerR[0], outerR[1], rng);
            b.addEdge(new RoadPrimitive.Ring(primaryPos, outer, EDGE_DRIFT,
                    RoadShape.RoadTier.VILLAGE_ROAD),
                    primaryNodeId, primaryNodeId);
        }

        // Gateways — ArmApproach from outer ring (or inner ring if
        // no outer) to gateway position.
        int gatewayRing = outer > 0 ? outer : inner;
        addRingGateway(b, primaryPos, gatewayRing, primaryGateway,
                "PRIMARY", ctx, allowed);
        addRingGateway(b, primaryPos, gatewayRing, secondaryGateway,
                "SECONDARY", ctx, allowed);

        // Spurs to secondaries outside the outer (or inner) ring.
        if (allowed.contains("Spur")) {
            int spurRing = gatewayRing;
            List<BlockPos> ringCenterline = ringSamplePoints(
                    primaryPos, spurRing, 16);
            for (Anchor sec : filterSecondaries(secondaries)) {
                BlockPos secPos = sec.centre();
                int distSq = horizDistSq(primaryPos, secPos);
                if (distSq < (long) spurRing * spurRing) continue;
                BlockPos onRing = projectOntoRing(primaryPos, spurRing, secPos);
                int spurLen = chebDist(onRing, secPos);
                if (spurLen < MIN_SPUR_LENGTH || spurLen > MAX_SPUR_LENGTH) continue;
                String synId = b.addSynthetic(onRing);
                String anchorId = b.addAnchor(secPos, sec);
                b.addEdge(new RoadPrimitive.Spur(ringCenterline, onRing,
                        bearingRad(onRing, secPos), spurLen, EDGE_DRIFT,
                        RoadShape.RoadTier.VILLAGE_ROAD),
                        synId, anchorId);
            }
        }

        // Track E1 prompt 6 — inter-Ring radials connecting the
        // inner and outer Rings (TOWN+: outer Ring is non-zero).
        // TOWN = 2 inter-Ring radials, CITY = 5. Length = (outer −
        // inner), short enough to never trip MAX_SPUR_LENGTH but
        // still adds substantial frontage at CITY (5 × ~14 = 70
        // blocks across the inter-Ring band, where buildings fit
        // tightly perpendicular to a short straight). RUNDLING's
        // signature is the concentric rings + ArmApproach
        // compass-rose; inter-Ring radials reinforce the defensive-
        // settlement feel without breaking it.
        if (outer > 0 && allowed.contains("StraightRoad")) {
            int interCount = radialCountFor(LayoutTopology.RUNDLING, ctx.tier());
            if (interCount > 0) {
                double[] angles = distributeRadialAngles(interCount,
                        secondaries, primaryPos, rng);
                for (double a : angles) {
                    BlockPos innerAttach = polarPoint(primaryPos, inner, a);
                    BlockPos outerAttach = polarPoint(primaryPos, outer, a);
                    int len = chebDist(innerAttach, outerAttach);
                    if (len < MIN_SPUR_LENGTH) continue;
                    String innerSyn = b.addSynthetic(innerAttach);
                    String outerSyn = b.addSynthetic(outerAttach);
                    b.addEdge(new RoadPrimitive.StraightRoad(innerAttach,
                            outerAttach, EDGE_DRIFT / 2.0,
                            RoadShape.RoadTier.VILLAGE_ROAD),
                            innerSyn, outerSyn);
                }
            }

            // Outer Spurs past the outer Ring (CITY only): 3 short
            // Spurs at angles independent of the inter-Ring radials
            // (consume the same RNG sequence for determinism but
            // the gaps re-resolve naturally). Represents "settlement
            // outside the wall" — gates-of-the-city growth that
            // typical medieval cities accumulated.
            int outerSpurCount = crossLinkCountFor(LayoutTopology.RUNDLING,
                    ctx.tier());
            int outerLen = outerSpurLengthFor(ctx.tier());
            if (outerSpurCount > 0 && outerLen >= MIN_SPUR_LENGTH
                    && allowed.contains("Spur")) {
                double[] angles = distributeRadialAngles(outerSpurCount,
                        secondaries, primaryPos, rng);
                List<BlockPos> outerRingCl = ringSamplePoints(
                        primaryPos, outer, 16);
                for (double a : angles) {
                    BlockPos onOuter = polarPoint(primaryPos, outer, a);
                    BlockPos spurEnd = polarPoint(primaryPos,
                            outer + outerLen, a);
                    String onOuterSyn = b.addSynthetic(onOuter);
                    String endSyn = b.addJunction(spurEnd);
                    b.addEdge(new RoadPrimitive.Spur(outerRingCl, onOuter,
                            a, outerLen, EDGE_DRIFT,
                            RoadShape.RoadTier.VILLAGE_ROAD),
                            onOuterSyn, endSyn);
                }
            }
        }
    }

    /**
     * EINZELHOF — single compound: short straight from keep to
     * gateway + up to 3 short spurs to support buildings.
     */
    private static void recipeEinzelhof(Builder b, SiteContext ctx,
            BlockPos primaryPos, Anchor primary, List<Anchor> secondaries,
            BlockPos primaryGateway, BlockPos secondaryGateway,
            Set<String> allowed, Random rng) {
        String primaryNodeId = b.addAnchor(primaryPos, primary);

        // Short access road from keep toward primary gateway.
        int len = 15 + rng.nextInt(11);  // 15-25 blocks
        BlockPos target = projectAlongVector(primaryPos, primaryGateway, len);
        String tgtNodeId = b.addNode("gateway:PRIMARY", target, NodeKind.GATEWAY);
        addLinearEdge(b,
                allowed.contains("StraightRoad") ? "StraightRoad" : "CurvedRoad",
                primaryPos, target, primaryNodeId, tgtNodeId, rng);

        // Up to 3 spurs to nearest secondaries (or scratch synthetic
        // points if no secondaries).
        if (allowed.contains("Spur")) {
            List<BlockPos> mainCenterline = List.of(primaryPos, target);
            int spurCount = 0;
            for (Anchor sec : filterSecondaries(secondaries)) {
                if (spurCount >= 3) break;
                BlockPos secPos = sec.centre();
                int spurLen = chebDist(primaryPos, secPos);
                if (spurLen < MIN_SPUR_LENGTH || spurLen > MAX_SPUR_LENGTH) continue;
                BlockPos branch = midpoint(primaryPos, target);
                String anchorId = b.addAnchor(secPos, sec);
                b.addEdge(new RoadPrimitive.Spur(mainCenterline, branch,
                        bearingRad(branch, secPos), spurLen, EDGE_DRIFT,
                        RoadShape.RoadTier.VILLAGE_ROAD),
                        primaryNodeId, anchorId);
                spurCount++;
            }
        }
    }

    /**
     * CLUSTER — generic fallback: straight edges connecting the
     * primary to each secondary; gateway connects to nearest of
     * primary or any secondary.
     */
    private static void recipeCluster(Builder b, SiteContext ctx,
            BlockPos primaryPos, Anchor primary, List<Anchor> secondaries,
            BlockPos primaryGateway, BlockPos secondaryGateway,
            Set<String> allowed, Random rng) {
        String primaryNodeId = b.addAnchor(primaryPos, primary);
        List<Anchor> filtered = filterSecondaries(secondaries);

        // Connect primary → each secondary with Straight or Curved.
        String type = allowed.contains("StraightRoad") ? "StraightRoad"
                : allowed.contains("CurvedRoad") ? "CurvedRoad" : null;
        if (type != null) {
            for (Anchor sec : filtered) {
                BlockPos secPos = sec.centre();
                int d = chebDist(primaryPos, secPos);
                if (d < MIN_SPUR_LENGTH || d > MAX_SPUR_LENGTH * 2) continue;
                String anchorId = b.addAnchor(secPos, sec);
                addLinearEdge(b, type, primaryPos, secPos, primaryNodeId,
                        anchorId, rng);
            }
            // Gateways → straight to primary.
            String gpId = b.addNode("gateway:PRIMARY", primaryGateway,
                    NodeKind.GATEWAY);
            addLinearEdge(b, type, primaryPos, primaryGateway, primaryNodeId,
                    gpId, rng);

            // Track E1 prompt 6 — arterial spokes radiating from the
            // primary anchor, plus cross-streets connecting pairs of
            // spoke endpoints. CLUSTER is the inclination-fallback
            // topology (industrial_mining, agricultural_cluster_
            // fallback) so the spokes are organised "compass-rose"
            // rather than around a Ring — there's no ring to attach
            // to. TOWN = 4 spokes + 1 cross-street, CITY = 5 spokes
            // + 2 cross-streets. Spokes use secondary-anchor
            // directions when available so industrial_mining's
            // CLIFF_FACE bindings actually map to a spoke endpoint
            // near the mineable wall.
            //
            // NOTE on future refinement: a real medieval mining
            // village is a two-node graph (mine site + residential
            // cluster) connected by an arterial — a DUMBBELL or
            // RESOURCE_LINK topology. CLUSTER's compass-rose shape
            // is a rough fit; introducing the proper topology is a
            // future prompt. For now, the frontage is what matters.
            int spokeCount = radialCountFor(LayoutTopology.CLUSTER,
                    ctx.tier());
            if (spokeCount > 0) {
                int[] spokeLen = radialLengthRange(ctx.tier());
                double[] angles = distributeRadialAngles(spokeCount,
                        secondaries, primaryPos, rng);
                List<BlockPos> spokeEnds = new ArrayList<>(spokeCount);
                List<String> spokeEndNodes = new ArrayList<>(spokeCount);
                for (double a : angles) {
                    int len = sampleInt(spokeLen[0], spokeLen[1], rng);
                    BlockPos end = polarPoint(primaryPos, len, a);
                    String endNode = b.addJunction(end);
                    spokeEnds.add(end);
                    spokeEndNodes.add(endNode);
                    addLinearEdge(b, type, primaryPos, end, primaryNodeId,
                            endNode, rng);
                }
                // Cross-streets: connect pairs of adjacent spoke
                // endpoints. CITY = 2 connects spoke 0-1 and 2-3;
                // TOWN = 1 connects 0-1. Skip if the chord length
                // exceeds MAX_SPUR_LENGTH * 2 (degenerate large gap).
                int crossCount = crossLinkCountFor(LayoutTopology.CLUSTER,
                        ctx.tier());
                for (int i = 0; i < crossCount; i++) {
                    int idx1 = (2 * i) % spokeEnds.size();
                    int idx2 = (2 * i + 1) % spokeEnds.size();
                    if (idx1 == idx2) continue;
                    BlockPos p1 = spokeEnds.get(idx1);
                    BlockPos p2 = spokeEnds.get(idx2);
                    int d = chebDist(p1, p2);
                    if (d < MIN_SPUR_LENGTH || d > MAX_SPUR_LENGTH * 2) continue;
                    addLinearEdge(b, type, p1, p2,
                            spokeEndNodes.get(idx1), spokeEndNodes.get(idx2),
                            rng);
                }
            }
        }
    }

    // =========================================================================
    // Recipe helpers
    // =========================================================================

    /** Fallback / degenerate-Ring path: short straight road through the
     *  primary along the site's cardinal axis + spurs to secondaries. */
    private static void recipeShortSpine(Builder b, SiteContext ctx,
            BlockPos primaryPos, String primaryNodeId, List<Anchor> secondaries,
            BlockPos primaryGateway, BlockPos secondaryGateway,
            Set<String> allowed, Random rng) {
        CardinalAxis axis = ctx.primaryAxis();
        int half = 12 + rng.nextInt(9);
        BlockPos start = step(primaryPos,
                axis == CardinalAxis.X ? -1 : 0,
                axis == CardinalAxis.X ? 0 : -1, half);
        BlockPos end = step(primaryPos,
                axis == CardinalAxis.X ? +1 : 0,
                axis == CardinalAxis.X ? 0 : +1, half);
        String sNode = b.addNode("gateway:SECONDARY", start, NodeKind.GATEWAY);
        String eNode = b.addNode("gateway:PRIMARY", end, NodeKind.GATEWAY);
        String type = allowed.contains("StraightRoad")
                ? "StraightRoad"
                : (allowed.contains("CurvedRoad") ? "CurvedRoad" : "StraightRoad");
        addLinearEdge(b, type, start, primaryPos, sNode, primaryNodeId, rng);
        addLinearEdge(b, type, primaryPos, end, primaryNodeId, eNode, rng);

        if (allowed.contains("Spur")) {
            List<BlockPos> spineCenterline = List.of(start, primaryPos, end);
            for (Anchor sec : filterSecondaries(secondaries)) {
                BlockPos secPos = sec.centre();
                BlockPos onSpine = projectOntoSegment(start, end, secPos);
                int spurLen = chebDist(onSpine, secPos);
                if (spurLen < MIN_SPUR_LENGTH || spurLen > MAX_SPUR_LENGTH) continue;
                String synId = b.addSynthetic(onSpine);
                String anchorId = b.addAnchor(secPos, sec);
                b.addEdge(new RoadPrimitive.Spur(spineCenterline, onSpine,
                        bearingRad(onSpine, secPos), spurLen, EDGE_DRIFT,
                        RoadShape.RoadTier.VILLAGE_ROAD),
                        synId, anchorId);
            }
        }
    }

    /** Track E1 prompt 6 — emit arterial radial edges from a Ring
     *  perimeter outward, with optional short perpendicular outer
     *  Spurs at the radial endpoints. Shared by HAUFENDORF (uses
     *  outer Spurs) and ANGERDORF (no outer Spurs — Arc fills that
     *  decorative role).
     *
     *  Angles come from {@link #distributeRadialAngles} (secondary-
     *  biased + even-spaced fallback + min-30°-separation + phase
     *  jitter). No emission at HAMLET / OUTPOST / UNVIABLE
     *  (radialCountFor returns 0). Requires StraightRoad in allowed
     *  primitives; Spur required for the outer-Spur emission. */
    private static void emitRadialsFromRing(Builder b, SiteContext ctx,
            BlockPos ringCentre, int ringRadius, List<Anchor> secondaries,
            LayoutTopology topology, boolean emitOuterSpurs,
            Set<String> allowed, Random rng) {
        int M = radialCountFor(topology, ctx.tier());
        if (M <= 0) return;
        if (!allowed.contains("StraightRoad")) return;

        int[] lenRange = radialLengthRange(ctx.tier());
        int outerSpurLen = emitOuterSpurs ? outerSpurLengthFor(ctx.tier()) : 0;
        double[] angles = distributeRadialAngles(M, secondaries, ringCentre, rng);

        for (double angle : angles) {
            int radialLen = sampleInt(lenRange[0], lenRange[1], rng);
            BlockPos ringAttach = polarPoint(ringCentre, ringRadius, angle);
            BlockPos radialEnd = polarPoint(ringCentre, ringRadius + radialLen, angle);
            String ringSyn = b.addSynthetic(ringAttach);
            String endNode = b.addJunction(radialEnd);
            b.addEdge(new RoadPrimitive.StraightRoad(ringAttach, radialEnd,
                    EDGE_DRIFT, RoadShape.RoadTier.VILLAGE_ROAD),
                    ringSyn, endNode);

            if (emitOuterSpurs && outerSpurLen >= MIN_SPUR_LENGTH
                    && allowed.contains("Spur")) {
                // Perpendicular Spur at the radial terminus. Side
                // alternates per radial via the angle's index parity
                // (encoded in the angle's sign of sin(angle*M)).
                double perp = angle + Math.PI / 2;
                BlockPos spurEnd = polarPoint(radialEnd, outerSpurLen, perp);
                List<BlockPos> radialCenterline = List.of(ringAttach, radialEnd);
                String spurEndSyn = b.addJunction(spurEnd);
                b.addEdge(new RoadPrimitive.Spur(radialCenterline, radialEnd,
                        perp, outerSpurLen, EDGE_DRIFT,
                        RoadShape.RoadTier.VILLAGE_ROAD),
                        endNode, spurEndSyn);
            }
        }
    }

    /** Emit an ArmApproach (preferred) or StraightRoad from the
     *  nearest Ring point to the gateway position. */
    private static void addRingGateway(Builder b, BlockPos ringCentre, int radius,
            BlockPos gateway, String gatewaySlot, SiteContext ctx,
            Set<String> allowed) {
        BlockPos onRing = projectOntoRing(ringCentre, radius, gateway);
        String synId = b.addSynthetic(onRing);
        String gateId = b.addNode("gateway:" + gatewaySlot, gateway,
                NodeKind.GATEWAY);
        int dist = chebDist(onRing, gateway);
        if (dist < 3) return;  // gateway already lands on the ring
        if (allowed.contains("ArmApproach")) {
            // ArmApproach takes a villageId UUID — synthesise a stable
            // UUID from the site context so two runs of the same seed
            // produce the same UUID.
            java.util.UUID villageId = new java.util.UUID(ctx.seed(),
                    ctx.anchor().asLong());
            b.addEdge(new RoadPrimitive.ArmApproach(onRing, gateway, villageId,
                    RoadShape.RoadTier.VILLAGE_ROAD), synId, gateId);
        } else if (allowed.contains("StraightRoad")) {
            b.addEdge(new RoadPrimitive.StraightRoad(onRing, gateway, EDGE_DRIFT,
                    RoadShape.RoadTier.VILLAGE_ROAD), synId, gateId);
        }
    }

    /** Add either a StraightRoad or CurvedRoad edge between two
     *  positions, honouring the primitive-allowed set. */
    private static void addLinearEdge(Builder b, String preferredType,
            BlockPos from, BlockPos to, String fromNode, String toNode,
            Random rng) {
        RoadPrimitive prim;
        if ("CurvedRoad".equals(preferredType)) {
            double curvature = 0.10 + rng.nextDouble() * 0.30;
            prim = new RoadPrimitive.CurvedRoad(from, to, curvature, EDGE_DRIFT,
                    RoadShape.RoadTier.VILLAGE_ROAD);
        } else {
            prim = new RoadPrimitive.StraightRoad(from, to, EDGE_DRIFT,
                    RoadShape.RoadTier.VILLAGE_ROAD);
        }
        b.addEdge(prim, fromNode, toNode);
    }

    /** Lead-binding resolution. Walks {@code strategy.bindings} —
     *  for each lead-eligible building type, picks the best-quality
     *  anchor of the preferred types and emits a PrimaryBinding. */
    private static void addPrimaryBindings(Builder b, SiteContext ctx,
                                           LayoutStrategy strategy) {
        Set<BlockPos> taken = new HashSet<>();
        Map<BuildingType, List<AnchorType>> prefs = strategy.bindings().preferences();
        if (prefs.isEmpty()) return;
        for (var entry : prefs.entrySet()) {
            BuildingType type = entry.getKey();
            if (!LeadBuildingTypes.isLead(type)) continue;
            List<AnchorType> preferred = entry.getValue();
            Anchor pick = pickAnchorForBinding(ctx, preferred, taken);
            if (pick == null) continue;
            taken.add(pick.centre());
            b.addBinding(new PrimaryBinding(type, pick.centre(), pick.id(),
                    "strategy primary binding"));
        }
    }

    /** Highest-quality anchor whose type appears in {@code preferred}
     *  and whose centre isn't already claimed by a prior binding. */
    private static Anchor pickAnchorForBinding(SiteContext ctx,
            List<AnchorType> preferred, Set<BlockPos> taken) {
        if (preferred == null || preferred.isEmpty()) return null;
        Set<AnchorType> wanted = new LinkedHashSet<>(preferred);
        return ctx.anchors().stream()
                .filter(a -> wanted.contains(a.type()))
                .filter(a -> !taken.contains(a.centre()))
                .max(Comparator.comparingDouble(Anchor::quality))
                .orElse(null);
    }

    // =========================================================================
    // Tier-scaled parameters
    // =========================================================================

    /** Ring radius range (min, max) for a HAUFENDORF-style nucleus.
     *  When {@code wider} is true (ANGERDORF), bumps by ~6 blocks. */
    private static int[] ringRadiusRange(ViabilityTier tier, boolean wider) {
        int base = switch (tier) {
            case CITY    -> 18;
            case TOWN    -> 14;
            case HAMLET  -> 8;
            case OUTPOST, UNVIABLE -> 6;
        };
        int top = switch (tier) {
            case CITY    -> 24;
            case TOWN    -> 18;
            case HAMLET  -> 12;
            case OUTPOST, UNVIABLE -> 8;
        };
        return wider
                ? new int[]{base + 4, top + 6}
                : new int[]{base, top};
    }

    private static int[] innerRingRange(ViabilityTier tier) {
        return switch (tier) {
            case CITY    -> new int[]{10, 14};
            case TOWN    -> new int[]{10, 14};
            case HAMLET, OUTPOST, UNVIABLE -> new int[]{6, 10};
        };
    }

    private static int[] outerRingRange(ViabilityTier tier) {
        return switch (tier) {
            case CITY -> new int[]{24, 32};
            case TOWN -> new int[]{18, 24};
            case HAMLET, OUTPOST, UNVIABLE -> new int[]{0, 0};
        };
    }

    private static int[] spineLengthRange(ViabilityTier tier) {
        return switch (tier) {
            case CITY    -> new int[]{100, 140};
            case TOWN    -> new int[]{60, 80};
            case HAMLET  -> new int[]{22, 36};
            case OUTPOST, UNVIABLE -> new int[]{16, 22};
        };
    }

    // gatewayDistance moved to GatewayPlanner (Stage 3b extraction).

    // =========================================================================
    // Track E1 prompt 6 — tier-keyed secondary edge counts.
    // =========================================================================
    //
    // Prompt 3 delivered tier-keyed primary feature SIZE (Ring radius,
    // spine length) but the COUNT of secondary edges per recipe stayed
    // at "one per filtered secondary anchor." A CITY HAUFENDORF on a
    // 2-secondary site emitted 2 Spurs — same as a HAMLET on the same
    // site. That capped CITY-tier frontage at ~250 blocks regardless
    // of how much composition the inclination profile (prompt 5) asks
    // for. CITY AGRICULTURAL wants ~72 buildings; 250 blocks of
    // frontage at the post-prompt-3-fix-up-3 ~15-block slot spacing
    // hosts ~16 cleanly. The math doesn't reach without expanding the
    // network.
    //
    // These tables add tier-keyed secondary edge counts per topology
    // (radials for HAUFENDORF / ANGERDORF / CLUSTER, cross-streets
    // for REIHENDORF, inter-Ring radials for RUNDLING, outer Spurs
    // for RUNDLING CITY only). HAMLET stays at zero so existing
    // small-village geometry doesn't regress.

    /** Number of arterial radial / cross-street / spoke edges a
     *  topology emits per tier. These are emitted IN ADDITION to the
     *  existing per-secondary-anchor Spurs (which stay unchanged) so
     *  composition + network capacity track together. */
    private static int radialCountFor(LayoutTopology topology, ViabilityTier tier) {
        return switch (topology) {
            case HAUFENDORF -> switch (tier) {
                case CITY -> 5;
                case TOWN -> 3;
                case HAMLET, OUTPOST, UNVIABLE -> 0;
            };
            case REIHENDORF -> switch (tier) {
                case CITY -> 5;
                case TOWN -> 3;
                case HAMLET, OUTPOST, UNVIABLE -> 0;
            };
            case ANGERDORF -> switch (tier) {
                case CITY -> 5;
                case TOWN -> 3;
                case HAMLET, OUTPOST, UNVIABLE -> 0;
            };
            case RUNDLING -> switch (tier) {
                case CITY -> 5;
                case TOWN -> 2;
                case HAMLET, OUTPOST, UNVIABLE -> 0;
            };
            case CLUSTER -> switch (tier) {
                case CITY -> 5;
                case TOWN -> 4;
                case HAMLET, OUTPOST, UNVIABLE -> 0;
            };
            case EINZELHOF -> 0;  // EINZELHOF uses its own hardcoded cap of 3.
        };
    }

    /** Extra cross-link edges connecting non-adjacent radial
     *  endpoints — currently used by CLUSTER (between spoke
     *  endpoints) and RUNDLING (outer Spurs past the outer Ring).
     *  Zero for other topologies. */
    private static int crossLinkCountFor(LayoutTopology topology, ViabilityTier tier) {
        return switch (topology) {
            case CLUSTER -> switch (tier) {
                case CITY -> 2;
                case TOWN -> 1;
                default -> 0;
            };
            case RUNDLING -> switch (tier) {
                case CITY -> 3;
                default -> 0;
            };
            default -> 0;
        };
    }

    /** Radial / spoke length range per tier (blocks). Used for the
     *  arterial radials that radiate outward from the primary
     *  nucleus in HAUFENDORF / ANGERDORF / CLUSTER. CITY radials
     *  extend roughly half the village radius past the Ring (or
     *  primary anchor for CLUSTER); TOWN scales down ~⅔. */
    private static int[] radialLengthRange(ViabilityTier tier) {
        return switch (tier) {
            case CITY    -> new int[]{30, 50};
            case TOWN    -> new int[]{20, 35};
            case HAMLET, OUTPOST, UNVIABLE -> new int[]{0, 0};
        };
    }

    /** Short Spur length emitted perpendicular at the end of a
     *  HAUFENDORF / ANGERDORF radial (the "outer cluster" — a
     *  hamlet of houses where the arterial reaches its terminus).
     *  RUNDLING CITY also emits outer Spurs beyond the outer Ring
     *  at this length. */
    private static int outerSpurLengthFor(ViabilityTier tier) {
        return switch (tier) {
            case CITY -> 16;
            case TOWN -> 12;
            case HAMLET, OUTPOST, UNVIABLE -> 0;
        };
    }

    /** REIHENDORF cross-street length range per tier — branches
     *  perpendicular to the spine, alternating sides so the resulting
     *  shape stays balanced. Length is bounded by Spur's hard cap
     *  ({@link #MAX_SPUR_LENGTH} = 48). */
    private static int[] crossStreetLengthRange(ViabilityTier tier) {
        return switch (tier) {
            case CITY    -> new int[]{30, 45};
            case TOWN    -> new int[]{20, 30};
            case HAMLET, OUTPOST, UNVIABLE -> new int[]{0, 0};
        };
    }

    /** Track E1 prompt 6 — minimum angular separation between any
     *  two radials in the distribute-radial-angles algorithm. The
     *  base spacing for M=5 radials is 72° even before snapping to
     *  secondary anchors; only secondary snapping can push two
     *  radials within 30° of each other, in which case the relaxer
     *  pushes the closer pair apart symmetrically. */
    private static final double MIN_RADIAL_SEPARATION_RAD = Math.toRadians(30);

    /** Distribute M radial bearings around {@code primary}, biased
     *  toward the K closest secondary anchors when {@code K = min(M,
     *  secondaries.size()) > 0} and filling the rest at evenly-spaced
     *  angles in the largest remaining gaps. A deterministic phase
     *  offset from {@code rng} rotates the unconstrained fill so two
     *  similar sites produce visibly different geometry. After
     *  placement, any pair within {@link #MIN_RADIAL_SEPARATION_RAD}
     *  is symmetrically pushed apart. Returns angles in radians
     *  (0..2π), sorted ascending. */
    private static double[] distributeRadialAngles(int M, List<Anchor> secondaries,
                                                    BlockPos primary, Random rng) {
        if (M <= 0) return new double[0];

        // K = min(M, N) closest filtered secondaries get a radial each.
        List<Anchor> filtered = new ArrayList<>(filterSecondaries(secondaries));
        filtered.sort(Comparator.comparingDouble(a -> {
            long dx = a.centre().getX() - primary.getX();
            long dz = a.centre().getZ() - primary.getZ();
            return (double) (dx * dx + dz * dz);
        }));
        int K = Math.min(M, filtered.size());

        // Phase offset is consumed even when K == 0 so the RNG
        // sequence stays stable independent of anchor count for the
        // same site. (Otherwise a site that loses or gains a
        // secondary anchor due to terrain changes would re-roll
        // every downstream sampleInt.)
        double phase = rng.nextDouble() * 2 * Math.PI;

        List<Double> placed = new ArrayList<>(M);
        for (int i = 0; i < K; i++) {
            placed.add(normalizeAngle(bearingRad(primary, filtered.get(i).centre())));
        }

        int remaining = M - K;
        if (remaining > 0 && K == 0) {
            // No anchors — pure even distribution with phase jitter.
            for (int i = 0; i < remaining; i++) {
                placed.add(normalizeAngle(phase + i * (2 * Math.PI / remaining)));
            }
        } else if (remaining > 0) {
            // Place each remaining angle at the midpoint of the
            // largest current gap. Each pass adds one angle, so the
            // gap structure evolves; recompute every iteration.
            for (int rem = 0; rem < remaining; rem++) {
                Collections.sort(placed);
                double bestGap = -1;
                double bestMid = phase;
                int n = placed.size();
                for (int i = 0; i < n; i++) {
                    double a = placed.get(i);
                    double bAng = placed.get((i + 1) % n);
                    double gap = (bAng - a + 2 * Math.PI) % (2 * Math.PI);
                    if (n == 1) gap = 2 * Math.PI;  // single anchor: full sweep
                    if (gap > bestGap) {
                        bestGap = gap;
                        bestMid = normalizeAngle(a + gap / 2);
                    }
                }
                placed.add(bestMid);
            }
        }

        double[] arr = new double[M];
        for (int i = 0; i < M; i++) arr[i] = placed.get(i);
        java.util.Arrays.sort(arr);

        // Min-separation relaxation. Iterates up to 4 passes; the
        // base even-spacing for M >= 4 is already > 30°, so only
        // anchor-snap collisions need adjustment and one pass
        // typically converges.
        for (int iter = 0; iter < 4; iter++) {
            boolean adjusted = false;
            for (int i = 0; i < M; i++) {
                int j = (i + 1) % M;
                double a = arr[i];
                double bAng = arr[j];
                double gap = (bAng - a + 2 * Math.PI) % (2 * Math.PI);
                if (gap > 0 && gap < MIN_RADIAL_SEPARATION_RAD) {
                    double push = (MIN_RADIAL_SEPARATION_RAD - gap) / 2;
                    arr[i] = normalizeAngle(a - push);
                    arr[j] = normalizeAngle(bAng + push);
                    adjusted = true;
                }
            }
            if (!adjusted) break;
            java.util.Arrays.sort(arr);
        }

        return arr;
    }

    private static double normalizeAngle(double rad) {
        double r = rad % (2 * Math.PI);
        if (r < 0) r += 2 * Math.PI;
        return r;
    }

    /** Compute a point at {@code (centre + radius * (cos(angle),
     *  sin(angle)))}. Used to find radial attach points on the
     *  Ring perimeter without going through the
     *  {@link #projectOntoRing} secondary-snap path. */
    private static BlockPos polarPoint(BlockPos centre, int radius, double angle) {
        return new BlockPos(
                centre.getX() + (int) Math.round(Math.cos(angle) * radius),
                centre.getY(),
                centre.getZ() + (int) Math.round(Math.sin(angle) * radius));
    }

    // =========================================================================
    // Geometry helpers
    // =========================================================================

    private static int sampleInt(int lo, int hi, Random rng) {
        if (hi <= lo) return lo;
        return lo + rng.nextInt(hi - lo + 1);
    }

    private static BlockPos step(BlockPos from, double dirX, double dirZ, int n) {
        return new BlockPos(
                from.getX() + (int) Math.round(dirX * n),
                from.getY(),
                from.getZ() + (int) Math.round(dirZ * n));
    }

    private static BlockPos projectAlongVector(BlockPos from, BlockPos toward,
                                               int distance) {
        double dx = toward.getX() - from.getX();
        double dz = toward.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-9) return from;
        return new BlockPos(
                from.getX() + (int) Math.round(dx / len * distance),
                from.getY(),
                from.getZ() + (int) Math.round(dz / len * distance));
    }

    /** Nearest point on the perimeter of a circle to {@code p}. */
    private static BlockPos projectOntoRing(BlockPos centre, int radius,
                                            BlockPos p) {
        double dx = p.getX() - centre.getX();
        double dz = p.getZ() - centre.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-9) {
            return new BlockPos(centre.getX() + radius, centre.getY(),
                    centre.getZ());
        }
        return new BlockPos(
                centre.getX() + (int) Math.round(dx / len * radius),
                centre.getY(),
                centre.getZ() + (int) Math.round(dz / len * radius));
    }

    private static BlockPos projectOntoSegment(BlockPos a, BlockPos b,
                                               BlockPos p) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double lenSq = dx * dx + dz * dz;
        if (lenSq < 1) return a;
        double t = ((p.getX() - a.getX()) * dx + (p.getZ() - a.getZ()) * dz) / lenSq;
        t = Math.max(0, Math.min(1, t));
        return new BlockPos(
                a.getX() + (int) Math.round(dx * t),
                a.getY(),
                a.getZ() + (int) Math.round(dz * t));
    }

    private static BlockPos midpoint(BlockPos a, BlockPos b) {
        return new BlockPos(
                (a.getX() + b.getX()) / 2,
                a.getY(),
                (a.getZ() + b.getZ()) / 2);
    }

    private static List<BlockPos> ringSamplePoints(BlockPos centre, int radius,
                                                   int samples) {
        List<BlockPos> out = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
            double a = (i / (double) samples) * 2 * Math.PI;
            out.add(new BlockPos(
                    centre.getX() + (int) Math.round(Math.cos(a) * radius),
                    centre.getY(),
                    centre.getZ() + (int) Math.round(Math.sin(a) * radius)));
        }
        return out;
    }

    private static double bearingRad(BlockPos from, BlockPos to) {
        return Math.atan2(to.getZ() - from.getZ(), to.getX() - from.getX());
    }

    private static int chebDist(BlockPos a, BlockPos b) {
        return Math.max(Math.abs(a.getX() - b.getX()),
                Math.abs(a.getZ() - b.getZ()));
    }

    private static int horizDistSq(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static List<Anchor> filterSecondaries(List<Anchor> in) {
        List<Anchor> out = new ArrayList<>();
        for (Anchor a : in) {
            if (a.quality() >= SECONDARY_QUALITY_FLOOR) out.add(a);
        }
        return out;
    }

    private static BlockPos endpointStart(NetworkEdge e) {
        RoadPrimitive p = e.primitive();
        if (p instanceof RoadPrimitive.StraightRoad sr) return sr.from();
        if (p instanceof RoadPrimitive.CurvedRoad cr) return cr.from();
        if (p instanceof RoadPrimitive.Spur s)
            return s.parentCenterline().isEmpty()
                    ? s.branchPointHint()
                    : s.parentCenterline().get(0);
        if (p instanceof RoadPrimitive.ArmApproach aa) return aa.dockingAnchor();
        if (p instanceof RoadPrimitive.Bridge br) return br.from();
        if (p instanceof RoadPrimitive.Stairway st) return st.from();
        if (p instanceof RoadPrimitive.Ring r)
            return new BlockPos(r.centre().getX() + r.radius(), r.centre().getY(),
                    r.centre().getZ());
        if (p instanceof RoadPrimitive.Arc a)
            return new BlockPos(
                    a.centre().getX() + (int) Math.round(Math.cos(a.startAngle()) * a.radius()),
                    a.centre().getY(),
                    a.centre().getZ() + (int) Math.round(Math.sin(a.startAngle()) * a.radius()));
        if (p instanceof RoadPrimitive.SmoothedPath sp)
            return sp.waypoints().isEmpty() ? null : sp.waypoints().get(0);
        return null;
    }

    private static BlockPos endpointEnd(NetworkEdge e) {
        RoadPrimitive p = e.primitive();
        if (p instanceof RoadPrimitive.StraightRoad sr) return sr.to();
        if (p instanceof RoadPrimitive.CurvedRoad cr) return cr.to();
        if (p instanceof RoadPrimitive.Spur s) return s.branchPointHint();  // approximate
        if (p instanceof RoadPrimitive.ArmApproach aa) return aa.armEndpoint();
        if (p instanceof RoadPrimitive.Bridge br) return br.to();
        if (p instanceof RoadPrimitive.Stairway st) return st.to();
        if (p instanceof RoadPrimitive.Ring r)
            return new BlockPos(r.centre().getX() + r.radius(), r.centre().getY(),
                    r.centre().getZ());
        if (p instanceof RoadPrimitive.Arc a) {
            double endAng = a.startAngle() + a.arcSpan();
            return new BlockPos(
                    a.centre().getX() + (int) Math.round(Math.cos(endAng) * a.radius()),
                    a.centre().getY(),
                    a.centre().getZ() + (int) Math.round(Math.sin(endAng) * a.radius()));
        }
        if (p instanceof RoadPrimitive.SmoothedPath sp)
            return sp.waypoints().isEmpty() ? null
                    : sp.waypoints().get(sp.waypoints().size() - 1);
        return null;
    }

    // =========================================================================
    // Builder
    // =========================================================================

    private static final class Builder {
        private final LayoutTopology topology;
        private final List<NetworkNode> nodes = new ArrayList<>();
        private final List<NetworkEdge> edges = new ArrayList<>();
        private final List<PrimaryBinding> bindings = new ArrayList<>();
        private final Map<String, Anchor> anchorIds = new HashMap<>();
        private int syntheticCounter = 0;
        private int junctionCounter = 0;
        private int edgeCounter = 0;

        Builder(LayoutTopology topology) {
            this.topology = topology;
        }

        String addAnchor(BlockPos pos, Anchor anchor) {
            String id = "anchor:" + (anchor != null ? anchor.id() : "primary");
            if (anchorIds.containsKey(id)) return id;
            anchorIds.put(id, anchor);
            nodes.add(new NetworkNode(id, pos, NodeKind.ANCHOR));
            return id;
        }

        String addSynthetic(BlockPos pos) {
            String id = "synthetic:" + syntheticCounter++;
            nodes.add(new NetworkNode(id, pos, NodeKind.SYNTHETIC));
            return id;
        }

        /** Track E1 prompt 6 — JUNCTION nodes for radial / spoke
         *  endpoints. Semantically these are "real intersections
         *  in the road network at synthesised positions" rather
         *  than pure SYNTHETIC scaffolding. The painter doesn't
         *  treat them differently; the kind just makes dumps + the
         *  visualizer more honest. */
        String addJunction(BlockPos pos) {
            String id = "junction:" + junctionCounter++;
            nodes.add(new NetworkNode(id, pos, NodeKind.JUNCTION));
            return id;
        }

        String addNode(String id, BlockPos pos, NodeKind kind) {
            nodes.add(new NetworkNode(id, pos, kind));
            return id;
        }

        /** Re-label an already-added node. Used by REIHENDORF to turn
         *  "synthetic:spine_start" into "gateway:SECONDARY" after the
         *  spine geometry is known. */
        void relabel(String oldId, String newId, NodeKind newKind) {
            for (int i = 0; i < nodes.size(); i++) {
                NetworkNode n = nodes.get(i);
                if (n.id().equals(oldId)) {
                    nodes.set(i, new NetworkNode(newId, n.pos(), newKind));
                    // Update edge references.
                    for (int j = 0; j < edges.size(); j++) {
                        NetworkEdge e = edges.get(j);
                        if (e.fromNodeId().equals(oldId) || e.toNodeId().equals(oldId)) {
                            edges.set(j, new NetworkEdge(e.id(),
                                    e.fromNodeId().equals(oldId) ? newId : e.fromNodeId(),
                                    e.toNodeId().equals(oldId) ? newId : e.toNodeId(),
                                    e.primitive(), e.width()));
                        }
                    }
                    return;
                }
            }
        }

        void addEdge(RoadPrimitive prim, String from, String to) {
            edges.add(new NetworkEdge("e" + edgeCounter++, from, to, prim,
                    DEFAULT_EDGE_WIDTH));
        }

        void addBinding(PrimaryBinding pb) {
            bindings.add(pb);
        }

        NetworkSpec build() {
            // Always emit at least one node so consumers don't blow
            // up on "empty network" — degenerate sites still produce
            // a placement seed.
            if (nodes.isEmpty()) {
                nodes.add(new NetworkNode("anchor:degenerate",
                        new BlockPos(0, 0, 0), NodeKind.ANCHOR));
            }
            return new NetworkSpec(topology, nodes, edges, bindings);
        }
    }
}
