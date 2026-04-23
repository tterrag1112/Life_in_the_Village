package tterrag1112.life_in_the_village.Village.Roads.Graph;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Stable character record for a GREAT_ROAD edge. Assigned once at commit time
 * and never modified. Governs visual meander and records the routing biases
 * that shaped the road.
 */
public record GreatRoadCharacter(
        CharacterTag tag,
        float meanderAmplitude,
        float meanderFrequency,
        long characterSeed,
        BiasHints hints
) {

    public enum CharacterTag {
        MOUNTAIN_HUGGING,
        PLAINS_STRAIGHT,
        RIVER_FOLLOWING,
        FOREST_WANDERING,
        COASTAL,
        UPLAND_PASS,
        DEFAULT;

        public static final Codec<CharacterTag> CODEC =
                Codec.STRING.xmap(CharacterTag::valueOf, CharacterTag::name);
    }

    /**
     * Records the cost-function biases that were used (or would have been used)
     * during routing. Informational — not consumed during re-realization.
     */
    public record BiasHints(
            float steepCostBias,
            float riverCostBias,
            float forestCostBias,
            float meanderAmplitudeBias
    ) {
        public static final Codec<BiasHints> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("steepCostBias").forGetter(BiasHints::steepCostBias),
                Codec.FLOAT.fieldOf("riverCostBias").forGetter(BiasHints::riverCostBias),
                Codec.FLOAT.fieldOf("forestCostBias").forGetter(BiasHints::forestCostBias),
                Codec.FLOAT.fieldOf("meanderAmplitudeBias").forGetter(BiasHints::meanderAmplitudeBias)
        ).apply(i, BiasHints::new));
    }

    public static final Codec<GreatRoadCharacter> CODEC = RecordCodecBuilder.create(i -> i.group(
            CharacterTag.CODEC.fieldOf("tag").forGetter(GreatRoadCharacter::tag),
            Codec.FLOAT.fieldOf("meanderAmplitude").forGetter(GreatRoadCharacter::meanderAmplitude),
            Codec.FLOAT.fieldOf("meanderFrequency").forGetter(GreatRoadCharacter::meanderFrequency),
            Codec.LONG.fieldOf("characterSeed").forGetter(GreatRoadCharacter::characterSeed),
            BiasHints.CODEC.fieldOf("hints").forGetter(GreatRoadCharacter::hints)
    ).apply(i, GreatRoadCharacter::new));
}
