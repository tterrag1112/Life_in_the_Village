package tterrag1112.life_in_the_village.Cultures.Planning;

/**
 * Culture plaza-shape preference. Distinct from the V1 renderer enum
 * {@code Village.Decoration.Plaza.PlazaShape} — that one enumerates
 * realised plaza geometries (CIRCLE / SQUARE / LINEAR / IRREGULAR);
 * this is a culture-level bias hint that the planner consults.
 *
 * <p>Relocated from the deleted {@code V2.Culture.PlazaShape} as part
 * of A2 — culture unification onto {@code Cultures.Culture}.
 */
public enum PlazaShape {
    CIRCLE,
    SQUARE,
    IRREGULAR
}
