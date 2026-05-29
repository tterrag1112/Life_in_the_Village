package tterrag1112.life_in_the_village.Village.Economy.Currency;

/**
 * Who is on the buying / selling side of a {@link PricingContext}.
 *
 * <p>Introduced by economy Phase 1a so the single canonical pricing
 * pipeline ({@link MarketPricing}) can decide which modifiers apply.
 * Relationship modifiers (reputation discount, profession perk, kingdom
 * tariff) are player concepts and are gated on {@link #PLAYER}; village
 * law and stall custom prices apply to both.</p>
 *
 * <p>{@code WANDERING_TRADER} is intentionally absent — its pricing is a
 * deferred fast-path ({@code TradeHandler.handleWanderingTrade}) that
 * does not route through this pipeline this arc.</p>
 */
public enum BuyerType {
    /** A human player trading at a market merchant. */
    PLAYER,
    /** An NPC trading through an economic channel. */
    NPC
}
