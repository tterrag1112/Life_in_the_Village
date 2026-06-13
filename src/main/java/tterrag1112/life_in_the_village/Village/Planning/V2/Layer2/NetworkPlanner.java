package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Track E1 prompt-3 — village road-network planner.
 *
 * <p>A6 teardown: the six topology recipe bodies (HAUFENDORF, REIHENDORF,
 * ANGERDORF, RUNDLING, EINZELHOF, CLUSTER) and all road-geometry helpers
 * were deleted. Those recipes produced {@link NetworkEdge} geometry that was
 * superseded by the Stage 3c {@link tterrag1112.life_in_the_village.Village
 * .Planning.V2.Layer4.BlockServingRouter}; their only remaining contribution
 * was GATEWAY-kind nodes (consumed by GATEWAY nucleus placement) and
 * {@link #primaryBindings} (lead-type anchor bindings). GATEWAY nucleus was
 * migrated to read {@code ctx.gateways()} directly (A6). Primary bindings
 * remain and are what {@link #plan} now produces.
 *
 * <p>{@code plan} emits one ANCHOR node (the primary anchor or site anchor)
 * plus primary bindings derived from the selected strategy.
 */
public final class NetworkPlanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkPlanner.class);

    /** Seed salt — kept for determinism if plan() ever needs sampling. */
    public static final long NETWORK_PLANNER_SALT = 0x4E45_5457_4F52_4B30L;

    private NetworkPlanner() {}

    // =========================================================================
    // Public entry
    // =========================================================================

    /**
     * Produces a minimal {@link NetworkSpec} carrying:
     * <ul>
     *   <li>One ANCHOR node at the strategy's primary anchor (or site anchor).</li>
     *   <li>Zero edges — road geometry is supplied by
     *       {@link tterrag1112.life_in_the_village.Village.Planning.V2.Layer4
     *       .BlockServingRouter} after placement.</li>
     *   <li>{@link NetworkSpec#primaryBindings()} — lead-type → anchor bindings
     *       consumed by PhasedPlanner for batching and position hints.</li>
     * </ul>
     */
    public static NetworkSpec plan(SiteContext ctx, V2FeatureMap fmap, long seed) {
        StrategySelectionResult selection = ctx.strategy();
        LayoutStrategy strategy = selection != null ? selection.strategy() : null;
        LayoutTopology topology = strategy != null
                ? strategy.topology() : LayoutTopology.CLUSTER;

        BlockPos primaryPos = selection != null && selection.primaryAnchor() != null
                ? selection.primaryAnchor().centre() : ctx.anchor();
        Anchor primaryAnchor = selection != null ? selection.primaryAnchor() : null;

        List<NetworkNode> nodes = new ArrayList<>();
        String primaryId = "anchor:" + (primaryAnchor != null ? primaryAnchor.id() : "primary");
        nodes.add(new NetworkNode(primaryId, primaryPos, NodeKind.ANCHOR));

        List<PrimaryBinding> bindings = new ArrayList<>();
        if (strategy != null) {
            addPrimaryBindings(bindings, ctx, strategy);
        }

        NetworkSpec spec = new NetworkSpec(topology, nodes, List.of(), bindings);
        LOGGER.info("network: {}, {} nodes, 0 edges, {} primary bindings",
                spec.topology(), spec.nodes().size(), spec.primaryBindings().size());
        return spec;
    }

    // =========================================================================
    // Primary bindings
    // =========================================================================

    /** Lead-binding resolution. Walks {@code strategy.bindings} —
     *  for each lead-eligible building type, picks the best-quality
     *  anchor of the preferred types and emits a PrimaryBinding. */
    private static void addPrimaryBindings(List<PrimaryBinding> out,
                                           SiteContext ctx,
                                           LayoutStrategy strategy) {
        Set<BlockPos> taken = new java.util.HashSet<>();
        Map<BuildingType, List<AnchorType>> prefs = strategy.bindings().preferences();
        if (prefs.isEmpty()) return;
        for (var entry : prefs.entrySet()) {
            BuildingType type = entry.getKey();
            if (!LeadBuildingTypes.isLead(type)) continue;
            List<AnchorType> preferred = entry.getValue();
            Anchor pick = pickAnchorForBinding(ctx, preferred, taken);
            if (pick == null) continue;
            taken.add(pick.centre());
            out.add(new PrimaryBinding(type, pick.centre(), pick.id(),
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
}
