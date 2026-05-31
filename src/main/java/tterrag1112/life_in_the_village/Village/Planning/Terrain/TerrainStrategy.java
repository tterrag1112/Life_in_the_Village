// src/main/java/tterrag1112/life_in_the_village/Village/Planning/Terrain/TerrainStrategy.java
package tterrag1112.life_in_the_village.Village.Planning.Terrain;

/**
 * The intended terrain-preparation character of a village type.
 *
 * <h3>Strategies</h3>
 * <ul>
 *   <li><b>FLAT</b> — minimal work, assumes buildable terrain. Best for
 *       plains and farming villages where the site selector has already
 *       found flat ground.</li>
 *   <li><b>SLOPE_AWARE</b> — the default for forest and mixed-terrain
 *       villages. Building pads are cut into slopes; natural terrain
 *       between buildings is preserved.</li>
 *   <li><b>MOUNTAIN</b> — for villages intentionally built on steep
 *       terrain (hilltop, mountain types). The steep character of the
 *       site is preserved.</li>
 *   <li><b>WATERFRONT</b> — for coastal and river villages. Per-building
 *       prep is slope-aware.</li>
 * </ul>
 *
 * <p>Persisted on {@code VillageTypeData} and surfaced through derived
 * tags / debug commands. The execution machinery that once turned these
 * into world edits was removed in the layout rework; the constants remain
 * as an inert classification until the codec/datagen migration retires
 * them.
 */
public enum TerrainStrategy {

    FLAT,
    SLOPE_AWARE,
    MOUNTAIN,
    WATERFRONT;

    /**
     * Parses a JSON string into a strategy, falling back to FLAT.
     */
    public static TerrainStrategy fromName(String name) {
        if (name == null) return FLAT;
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            System.out.println("TerrainStrategy: unknown strategy '"
                    + name + "', falling back to FLAT");
            return FLAT;
        }
    }
}
