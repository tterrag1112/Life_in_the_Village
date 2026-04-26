package tterrag1112.life_in_the_village.Npc.Hobby;

import com.mojang.serialization.Codec;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Npc.Traits.TraitVector;

import java.util.Map;

/**
 * Trait-axis weight bundle used by {@link HobbyDefinition} to score how
 * well a hobby fits an NPC's personality. {@code score(traits)} returns
 * the dot product of each axis's NPC value (in {@code [-1, +1]}) with
 * its configured weight.
 *
 * <p>Spec: {@code docs/npc_redesign/14-hobby-activities.md} (line 80).</p>
 */
public record TraitWeight(Map<TraitAxis, Float> weights) {

    public TraitWeight {
        weights = Map.copyOf(weights);
    }

    public static TraitWeight of(Map<TraitAxis, Float> weights) {
        return new TraitWeight(weights);
    }

    public float score(TraitVector traits) {
        float sum = 0f;
        for (var entry : weights.entrySet()) {
            sum += traits.get(entry.getKey()) * entry.getValue();
        }
        return sum;
    }

    public static final Codec<TraitWeight> CODEC =
            Codec.unboundedMap(TraitAxis.CODEC, Codec.FLOAT)
                    .xmap(TraitWeight::of, TraitWeight::weights);
}
