package tterrag1112.life_in_the_village.Village.Planning.Adaptive;

import tterrag1112.life_in_the_village.Village.Planning.Features.FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.VillageLayout;

import java.util.List;
import java.util.Map;

/**
 * Phase A stub. {@code emit()} returns an empty report unconditionally
 * — Phase B implements the six resolvers (PlazaPerimeter, RoadAlong,
 * AllSpurs, RingValidated, NamedAnchor, RegionalGather) and wires
 * them into a switch on {@link Anchor} subtype.
 *
 * <p>The contract Phase B must satisfy: every {@code PlacementSlot}
 * returned by a resolver satisfies the validator's road-adjacency cap
 * by construction. If a candidate position cannot satisfy the cap,
 * the resolver does not emit it; the intention's count is reduced
 * rather than the slot being placed somewhere unreachable.
 *
 * <p>Phase A keeps this stub so VillagePlanner.plan() can wire the
 * orchestration call. With recipes throwing
 * {@link RecipeNotPortedException} before this code is reached, the
 * stub is unreachable in normal Phase A operation.
 */
public final class SlotEmitter {

    public EmitterReport emit(
            List<SlotIntention> intentions,
            VillageLayout layout,
            FeatureMap fmap,
            PlanContext pctx) {
        // Phase A: no resolvers implemented. Phase B fills this in.
        int requested = 0;
        for (SlotIntention si : intentions) requested += si.desiredCount();
        return new EmitterReport(requested, 0, Map.of(), List.of());
    }

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
