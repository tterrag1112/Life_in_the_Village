package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

/**
 * One categorical supply offered by a building. Capacity is the
 * "head count" the supply can satisfy — a HOUSE with
 * {@code Provides(HOUSING, 4)} can answer up to four
 * {@code Requires(HOUSING, 1)} demands.
 */
public record Provides(Category category, int capacity) {
    public Provides {
        if (capacity < 0) throw new IllegalArgumentException(
                "Provides capacity must be >= 0 (got " + capacity + ")");
    }
}
