package tterrag1112.life_in_the_village.Village.Planning.V2;

/**
 * V2 inclination — a small bias set that nudges building selection
 * (Layer 3) and contributes to anchor / spine choices (Layer 2).
 *
 * <p>Six values, deliberately. Capping the count avoids recreating
 * the {@code ShapeType} problem the V2 redesign is replacing.
 *
 * <p>Layer 2 only treats inclinations as labels with terrain-affinity
 * scores. The InclinationProfile (per-building weighting tables)
 * lives with Layer 3.
 */
public enum Inclination {
    AGRICULTURAL,
    INDUSTRIAL,
    DEFENSIVE,
    CIVIC,
    RESIDENTIAL,
    SACRED
}
