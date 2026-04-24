package tterrag1112.life_in_the_village.Village.Roads.Lighting;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Describes how lighting is placed along a road edge.
 *
 * <p>Decomposed into a {@link Frequency} (how often) and a {@link Strategy}
 * (which side / which cells). Culture palettes pick one combination as the
 * default for their tier of roads; {@code /liv road debug force_lighting}
 * lets operators override a specific edge at runtime.
 *
 * <h3>Invariants</h3>
 * <ul>
 *   <li>{@link Frequency#NONE} is only meaningful paired with {@link Strategy#NONE}.</li>
 *   <li>{@link Strategy#JUNCTIONS_ONLY} produces no per-edge lights —
 *       the placer short-circuits and defers to junction decorators.</li>
 * </ul>
 */
public record RoadLightingProfile(
        Frequency frequency,
        Strategy strategy
) {

    /** How often lights appear along the edge. */
    public enum Frequency {
        /** No lights. */
        NONE,
        /** Every 24 blocks. */
        SPARSE,
        /** Every 16 blocks. */
        MODERATE,
        /** Every 8 blocks. */
        DENSE;

        public static final Codec<Frequency> CODEC =
                Codec.STRING.xmap(Frequency::valueOf, Frequency::name);
    }

    /** Which cells get lit and on which side of the road. */
    public enum Strategy {
        /** No lights placed. Pair with {@link Frequency#NONE}. */
        NONE,
        /** Lights on both sides of the road at each interval. */
        BOTH_SIDES,
        /** Lights alternate left/right along the road. */
        ALTERNATING_SIDES,
        /** All lights on left (relative to road direction). */
        SINGLE_SIDE_LEFT,
        /** All lights on right (relative to road direction). */
        SINGLE_SIDE_RIGHT,
        /** Lights only where the atlas cell category is FOREST; both sides. */
        FOREST_ONLY_BOTH,
        /** Lights only where the atlas cell category is FOREST; alternating. */
        FOREST_ONLY_ALTERNATING,
        /** Lights down road centerline (rare; may block walkway — placer skips if overlaps road). */
        CENTERLINE,
        /** Lights only at junctions; path placer is a no-op. */
        JUNCTIONS_ONLY;

        public static final Codec<Strategy> CODEC =
                Codec.STRING.xmap(Strategy::valueOf, Strategy::name);
    }

    // =========================================================================
    // Derived helpers
    // =========================================================================

    /** Block spacing implied by this profile's frequency. */
    public int spacing() {
        return switch (frequency) {
            case NONE     -> Integer.MAX_VALUE;
            case SPARSE   -> 24;
            case MODERATE -> 16;
            case DENSE    -> 8;
        };
    }

    /**
     * Returns {@code true} if this strategy needs an atlas-cell biome lookup
     * to decide whether to place a light at a given position.
     */
    public boolean requiresBiomeCheck() {
        return strategy == Strategy.FOREST_ONLY_BOTH
                || strategy == Strategy.FOREST_ONLY_ALTERNATING;
    }

    /** Convenience factory for "no lighting" profiles. */
    public static RoadLightingProfile none() {
        return new RoadLightingProfile(Frequency.NONE, Strategy.NONE);
    }

    /** {@code true} if the profile places no lights at all. */
    public boolean isNone() {
        return frequency == Frequency.NONE || strategy == Strategy.NONE;
    }

    // =========================================================================
    // Codec
    // =========================================================================

    public static final Codec<RoadLightingProfile> CODEC = RecordCodecBuilder.create(i -> i.group(
            Frequency.CODEC.fieldOf("frequency").forGetter(RoadLightingProfile::frequency),
            Strategy.CODEC.fieldOf("strategy").forGetter(RoadLightingProfile::strategy)
    ).apply(i, RoadLightingProfile::new));
}
