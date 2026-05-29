package tterrag1112.life_in_the_village.Village.Buildings.Complex;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Declarative spec for a MARKET building's "market complex" envelope
 * (merchant arc Phase 2a). A deliberate <em>sibling</em> of the
 * farm-shaped {@link BuildingComplexSpec} — markets are bounded
 * rectangular plazas in the dense village core, not terrain-flood-filled
 * peripheral farms, so they need a different (and much smaller) field
 * set. See {@code MERCHANT_PROGRESS.md} Phase 2a for why this is a clean
 * sibling rather than an extension of {@code BuildingComplexSpec}.
 *
 * <p>2a uses {@link #padMargin} / {@link #minPadMargin} to size and
 * collision-shrink the bounded pad; {@link #aisleModel} and
 * {@link #stallPool} are typed scaffolding consumed by 2b (stall
 * placement). {@link #padBlockId} is the optional pad surface override —
 * {@code null} means "use the culture's path palette" (the v1 default;
 * exact block is not load-bearing).
 *
 * @param padMargin    blocks of prepared pad to claim around the market
 *                     footprint on every side, before collision shrink.
 * @param minPadMargin smallest pad margin to accept; below this the
 *                     planner skips the pad entirely (building still
 *                     works) rather than stomp a neighbour.
 * @param aisleModel   how 2b will arrange stalls. Only {@link
 *                     MarketAisleModel#PERIMETER} for now.
 * @param padBlockId   optional surface block id (e.g. {@code
 *                     "minecraft:stone_bricks"}); {@code null} ⇒ culture
 *                     path palette.
 * @param stallPool    2a stub — opaque placeholder, empty for now; 2b
 *                     replaces this with a typed stall-slot spec list.
 */
public record MarketComplexSpec(
        int padMargin,
        int minPadMargin,
        MarketAisleModel aisleModel,
        @Nullable String padBlockId,
        List<String> stallPool) {

    public MarketComplexSpec {
        if (padMargin < 0) {
            throw new IllegalArgumentException("padMargin must be non-negative: " + padMargin);
        }
        if (minPadMargin < 0) {
            throw new IllegalArgumentException("minPadMargin must be non-negative: " + minPadMargin);
        }
        if (minPadMargin > padMargin) {
            throw new IllegalArgumentException(
                    "minPadMargin (" + minPadMargin + ") must not exceed padMargin (" + padMargin + ")");
        }
        if (aisleModel == null) aisleModel = MarketAisleModel.PERIMETER;
        stallPool = stallPool == null ? List.of() : List.copyOf(stallPool);
    }
}
