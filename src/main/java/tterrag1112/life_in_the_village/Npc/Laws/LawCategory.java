package tterrag1112.life_in_the_village.Npc.Laws;

/**
 * Coarse classification of a {@link VillageLaw}. Used by the
 * decision engine to balance enactments across categories (e.g. don't
 * stack four economy laws while crime is unaddressed) and by the
 * debug UI to group listings.
 *
 * <p>Spec line 44.</p>
 */
public enum LawCategory {
    ECONOMY,
    CRIME,
    SOCIAL,
    ECONOMIC_RESTRICTION
}
