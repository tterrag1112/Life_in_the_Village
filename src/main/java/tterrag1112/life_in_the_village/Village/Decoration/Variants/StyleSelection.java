package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import java.util.Random;

/**
 * Doc 15 — the resolved style decision for a village placement run.
 *
 * <p>Most villages declare {@code style: "rural"} or
 * {@code style: "urban"} explicitly and the matcher uses that value
 * unchanged ({@link Fixed}). Some auto-derived layouts (HAMLET /
 * VILLAGE on RADIAL/PLAZA/CROSSROADS) call for an "URBAN-leaning"
 * mix where the dice rolls per building rather than per village —
 * those return {@link UrbanLeaning}. Variant selection invokes
 * {@link #pickStyle(Random)} once per slot.</p>
 */
public sealed interface StyleSelection
        permits StyleSelection.Fixed, StyleSelection.UrbanLeaning {

    Style pickStyle(Random rng);

    static StyleSelection fixed(Style style) { return new Fixed(style); }

    static StyleSelection urbanLeaning(float urbanProbability) {
        return new UrbanLeaning(urbanProbability);
    }

    /** Always returns the same style; no rolls required. */
    record Fixed(Style style) implements StyleSelection {
        public Fixed {
            if (style == null) throw new IllegalArgumentException("style");
        }
        @Override public Style pickStyle(Random rng) { return style; }
    }

    /** Rolls per call: {@link Style#URBAN} with {@code urbanProbability},
     *  otherwise {@link Style#RURAL}. */
    record UrbanLeaning(float urbanProbability) implements StyleSelection {
        public UrbanLeaning {
            if (urbanProbability < 0f) urbanProbability = 0f;
            if (urbanProbability > 1f) urbanProbability = 1f;
        }
        @Override public Style pickStyle(Random rng) {
            return rng.nextFloat() < urbanProbability ? Style.URBAN : Style.RURAL;
        }
    }
}
