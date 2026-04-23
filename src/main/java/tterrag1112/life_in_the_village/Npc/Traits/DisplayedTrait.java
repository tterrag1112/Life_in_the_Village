package tterrag1112.life_in_the_village.Npc.Traits;

/**
 * One displayable trait label derived from a {@link TraitVector}.
 *
 * <p>{@code positivePole} is {@code true} when the underlying axis value
 * is &gt; 0, meaning the positive pole's name is surfaced.</p>
 */
public record DisplayedTrait(
        TraitAxis axis,
        boolean positivePole,
        TraitIntensity intensity
) {
    public String label() {
        String base = axis.poleLabel(positivePole);
        return switch (intensity) {
            case EMPHATIC -> "Very " + base;
            case NORMAL -> base;
        };
    }
}
