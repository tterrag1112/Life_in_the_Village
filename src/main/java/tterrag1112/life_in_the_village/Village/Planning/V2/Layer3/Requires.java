package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

/**
 * One categorical demand a building places on the village. The
 * reconciliation engine subtracts {@code amount} from the matching
 * {@link Category}'s {@link Provides} capacity pool; if no local
 * provider exists and {@code tradeable} is {@code true} (and the
 * tier supports trade for the category), the demand is satisfied
 * by trade instead of a local building.
 */
public record Requires(Category category, int amount, boolean tradeable) {
    public Requires {
        if (amount < 0) throw new IllegalArgumentException(
                "Requires amount must be >= 0 (got " + amount + ")");
    }
}
