package tterrag1112.life_in_the_village.Npc.Economy.Channels;

/**
 * Outcome of {@link EconomicChannel#execute}. Spec line 68.
 *
 * <p>Partial-fill semantics (spec "Things to flag" #2): when
 * {@code quantityTraded} is less than the requested quantity, the actor
 * decides whether to retry or accept the partial. Channels never throw
 * for partial fills — they return the partial result so the actor's
 * goal can react.</p>
 */
public record TradeResult(
        boolean success,
        int quantityTraded,
        long totalBronze,
        String failureReason
) {
    public TradeResult {
        if (failureReason == null) failureReason = "";
        if (quantityTraded < 0) quantityTraded = 0;
        if (totalBronze < 0) totalBronze = 0;
    }

    public static TradeResult ok(int quantity, long bronze) {
        return new TradeResult(true, quantity, bronze, "");
    }

    public static TradeResult fail(String reason) {
        return new TradeResult(false, 0, 0L, reason);
    }

    public boolean partial(TradeIntent intent) {
        return success && quantityTraded < intent.quantity();
    }
}
