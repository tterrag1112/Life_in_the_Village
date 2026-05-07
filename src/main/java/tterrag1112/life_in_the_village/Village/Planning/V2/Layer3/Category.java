package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

/**
 * V2 Layer 3 categorical needs/supplies. Used by
 * {@link ReconciliationEngine} to determine whether a building's
 * {@link Requires} can be satisfied by {@link Provides} from other
 * buildings in the same village (or by trade at suitable
 * {@link tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier}s).
 *
 * <p>Categories are intentionally abstract — never a specific
 * {@link tterrag1112.life_in_the_village.Village.Buildings.BuildingType}.
 * Multiple building types can both provide and consume the same
 * category (e.g. HOUSE and INN both provide HOUSING; FARMHOUSE and
 * BLACKSMITH both consume HOUSING).
 */
public enum Category {
    // Functional
    HOUSING,
    FOOD,
    CIVIC_AUTHORITY,
    RELIGIOUS,
    DEFENSE,
    COMMERCE,
    METALWORKING,
    CRAFTING,
    MEDICAL,
    SCHOLARLY,

    // Production / raw materials
    METAL_ORE,
    WOOD,
    FUEL,
    FLOUR,
    LIVESTOCK,

    // Workers
    EMPLOYMENT
}
