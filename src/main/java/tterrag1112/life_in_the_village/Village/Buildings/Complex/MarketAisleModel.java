package tterrag1112.life_in_the_village.Village.Buildings.Complex;

/**
 * How a market complex arranges its stall aisles (merchant arc Phase 2).
 *
 * <p>2a ships only {@link #PERIMETER} — stalls (2b) line the inner edge
 * of the prepared pad around the nucleus MARKET building. The enum exists
 * now so the {@link MarketComplexSpec} field is typed; additional models
 * (e.g. central rows) can be added when a concrete consumer needs them.
 *
 * <p>No exhaustive {@code switch} over this enum exists in 2a — the pad
 * render is aisle-model-agnostic; the model only matters once 2b places
 * stalls.
 */
public enum MarketAisleModel {
    /** Stalls line the perimeter of the prepared pad. */
    PERIMETER
}
