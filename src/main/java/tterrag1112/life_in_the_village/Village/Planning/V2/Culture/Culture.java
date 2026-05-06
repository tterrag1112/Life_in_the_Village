package tterrag1112.life_in_the_village.Village.Planning.V2.Culture;

import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;

import java.util.Map;

/**
 * V2 culture — a thin overlay carrying biases and preferences that
 * skin a village without reshaping its layout fundamentals.
 *
 * @param id                    culture string id (e.g. "default",
 *                              future "stone_folk", etc.)
 * @param roadMaterial          block id used for road surface
 *                              (e.g. "minecraft:dirt_path")
 * @param preferredCurvature    Layer 4 hint for road primitive
 *                              selection
 * @param preferredPlazaShape   Layer 3 hint for plaza geometry
 * @param inclinationBias       weight multipliers applied to each
 *                              inclination's terrain-affinity score
 *                              before the seeded sample. Missing
 *                              keys default to 1.0
 */
public record Culture(
        String id,
        String roadMaterial,
        Curvature preferredCurvature,
        PlazaShape preferredPlazaShape,
        Map<Inclination, Double> inclinationBias) {

    /** Returns the inclination bias for {@code inc}, defaulting to 1.0. */
    public double biasFor(Inclination inc) {
        Double v = inclinationBias.get(inc);
        return v != null ? v : 1.0;
    }
}
