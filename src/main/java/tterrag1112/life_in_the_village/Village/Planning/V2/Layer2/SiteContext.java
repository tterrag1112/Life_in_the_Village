package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 Layer 2 → Layers 3+4 hand-off. Carries the cardinal-aligned
 * site decisions plus mutable hooks for hubs (populated at the end
 * of Layer 4).
 *
 * <p>{@code anchor} is the adjusted anchor (post-snap toward terrain
 * seams). {@code originalAnchor} is the pre-snap anchor preserved
 * for debug output.
 *
 * <p>{@code primaryAxis} replaces the old {@code primarySpineDirection}
 * Vec3. Spines now run along a cardinal — diagonal layouts are not
 * produced.
 *
 * <p>{@code spinePath} carries the planned spine: an ordered list
 * of road primitives with cardinal mean direction and reactive arc
 * deflections where terrain forced bending. Populated by
 * {@link SiteAnalyzer}.
 *
 * <p>{@code hubs} is mutated by Layer 4's hub designator after the
 * skeleton is finalised. Initially empty. Records aren't strictly
 * immutable here because hubs are a Layer 4 output that needs to be
 * accessible via the SiteContext per the design spec.
 *
 * <p>Track E1 anchor detection — {@code anchors} is the new permissive
 * list of feature anchors (flat zones, peaks, water edges, etc.)
 * scored by quality. Sorted by quality descending per type. Prompt-2+
 * strategies consume this; the existing spine planner ignores it.
 *
 * <p>If {@code tier == UNVIABLE}, the other fields are populated
 * for debug-output convenience but the planner should bail before
 * Layer 3.
 */
public record SiteContext(
        BlockPos anchor,
        BlockPos originalAnchor,
        CardinalAxis primaryAxis,
        SpinePath spinePath,
        ViabilityTier tier,
        Inclination inclination,
        Culture culture,
        long seed,
        List<Hub> hubs,
        List<Anchor> anchors,
        StrategySelectionResult strategy,
        NetworkSpec network) {

    public SiteContext {
        anchors = anchors == null ? List.of() : List.copyOf(anchors);
    }

    /** Convenience: create a context with an empty mutable hubs
     *  list + empty anchors list + null strategy + null network.
     *  Layer 4 populates hubs; Track E1 anchors are populated by
     *  {@link AnchorDetector}; Track E1B strategy by
     *  {@link StrategySelector}; Track E1 prompt-3 network by
     *  {@link NetworkPlanner}. */
    public static SiteContext withEmptyHubs(BlockPos anchor, BlockPos originalAnchor,
                                            CardinalAxis primaryAxis, SpinePath spinePath,
                                            ViabilityTier tier, Inclination inclination,
                                            Culture culture, long seed) {
        return new SiteContext(anchor, originalAnchor, primaryAxis, spinePath,
                tier, inclination, culture, seed, new ArrayList<>(), List.of(),
                null, null);
    }

    /** Track E1 — copy-with anchors. */
    public SiteContext withAnchors(List<Anchor> newAnchors) {
        return new SiteContext(anchor, originalAnchor, primaryAxis, spinePath,
                tier, inclination, culture, seed, hubs, newAnchors, strategy, network);
    }

    /** Track E1B — copy-with strategy selection result. */
    public SiteContext withStrategy(StrategySelectionResult newStrategy) {
        return new SiteContext(anchor, originalAnchor, primaryAxis, spinePath,
                tier, inclination, culture, seed, hubs, anchors, newStrategy, network);
    }

    /** Track E1 prompt-3 — copy-with the planned road network and
     *  the derived spine path. Network is the source of truth;
     *  the spine is a derived view kept for backwards-compat
     *  consumers (RoadPainter, debug commands). */
    public SiteContext withNetwork(NetworkSpec newNetwork, SpinePath derivedSpine) {
        return new SiteContext(anchor, originalAnchor, primaryAxis,
                derivedSpine != null ? derivedSpine : spinePath,
                tier, inclination, culture, seed, hubs, anchors, strategy, newNetwork);
    }

    /** B2.8 — copy-with overrides for /building village spawn's
     *  inclination + tier injection. Terrain analysis still ran;
     *  only the derived classification is replaced. Pass null for
     *  either field to keep the analyzed value. */
    public SiteContext withOverrides(Inclination newInclination, ViabilityTier newTier) {
        return new SiteContext(
                anchor, originalAnchor, primaryAxis, spinePath,
                newTier        != null ? newTier        : tier,
                newInclination != null ? newInclination : inclination,
                culture, seed, hubs, anchors, strategy, network);
    }
}
