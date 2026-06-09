package tterrag1112.life_in_the_village.Npc.Religion;

/**
 * A {@link God}'s esteemed value — a {@link FaithConcept} (behaviour keys on it) +
 * authored text. (F1a cleanup — relocated from {@code ReligionIdentity}; the demand
 * belongs to the god.)
 */
public record Virtue(FaithConcept concept, String text) {}
