package tterrag1112.life_in_the_village.Village.Planning.Adaptive;

import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;

import java.util.Set;

/**
 * Declarative slot specification. A description of *requirements* —
 * "eight road-adjacent slots in the production sector, distributed
 * across all spurs, footprint budget 16" — not positions.
 *
 * <p>The SlotEmitter (Phase B) consumes intentions, runs the resolver
 * for the intention's {@link Anchor} subtype, and produces concrete
 * {@code PlacementSlot} positions. Every emitted slot satisfies the
 * validator's road-adjacency cap by construction; if a candidate
 * position would not satisfy the cap, the resolver does not emit it
 * and the intention's count is reduced rather than the slot being
 * placed somewhere unreachable.
 *
 * <p>{@code maxFootprint} is the largest building footprint the slot
 * should accommodate; it drives the perpendicular offset formula
 * {@code roadHalfWidth + max(halfW, halfL) + 1}.
 */
public record SlotIntention(
        SectorRef sector,
        Set<SlotTag> tags,
        int desiredCount,
        int maxFootprint,
        Anchor anchor
) {
    public SlotIntention {
        tags = Set.copyOf(tags);
    }
}
