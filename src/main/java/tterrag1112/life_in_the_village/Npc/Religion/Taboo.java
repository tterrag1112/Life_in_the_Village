package tterrag1112.life_in_the_village.Npc.Religion;

/**
 * A {@link God}'s forbidden act — a {@link FaithConcept} (behaviour keys on it) +
 * authored text. (F1a cleanup — relocated from {@code ReligionIdentity}.)
 */
public record Taboo(FaithConcept concept, String text) {}
