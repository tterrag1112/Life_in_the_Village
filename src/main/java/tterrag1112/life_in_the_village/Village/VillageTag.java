// src/main/java/tterrag1112/life_in_the_village/Village/VillageTag.java
package tterrag1112.life_in_the_village.Village;

/**
 * Tags describe what kind of environment a village type prefers.
 * The kingdom seeder uses these to filter and score claimed cells
 * when deciding where to place each village.
 *
 * <p>Tags are a flat set. "Does this cell match tag X?" is a hard
 * filter; per-type scoring (how well it matches) happens separately
 * in the site selector. This keeps the tag system simple while
 * letting individual village types express soft preferences through
 * scoring functions later.
 *
 * <p>Tags come from two sources:
 * <ul>
 *   <li><b>Manual</b> — declared in village type JSON via a
 *       {@code "tags": [...]} array.</li>
 *   <li><b>Derived</b> — auto-added based on other properties the
 *       type already declares (terrain strategy, shape type, starter
 *       buildings). See {@link VillageTagDeriver}.</li>
 * </ul>
 *
 * <p>The two sources are unioned; duplicates are harmless.
 */
public enum VillageTag {
    AGRICULTURAL,
    COASTAL,
    RIVERSIDE,
    MOUNTAIN,
    FOREST,
    DESERT,
    SWAMP,
    TRADE,
    DEFENSIVE,
    RELIGIOUS,
    INDUSTRIAL,
    REMOTE;

    /** Parses a tag name case-insensitively. Returns null for unknown names. */
    public static VillageTag fromName(String name) {
        if (name == null) return null;
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}