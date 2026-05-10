package tterrag1112.life_in_the_village.Kingdom.Laws;

/**
 * Track D3.4 — high-level grouping for the Laws panel sidebar in
 * {@code KingdomBookScreen}. Independent from the village-tier
 * {@link tterrag1112.life_in_the_village.Npc.Laws.LawCategory}
 * enum because the kingdom-tier slate has different categories
 * (Education, Diplomacy, Religion, Military, Land, Family) that
 * the village tier doesn't surface.
 */
public enum KingdomLawCategory {
    ECONOMY,
    CRIME,
    EDUCATION,
    DIPLOMACY,
    RELIGION,
    MILITARY,
    LAND,
    FAMILY;
}
