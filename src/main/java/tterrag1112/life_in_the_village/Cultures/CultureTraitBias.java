package tterrag1112.life_in_the_village.Cultures;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 5 doc 31 — bias delta applied to a fresh NPC's
 * {@code TraitVector} when they're spawned with this culture. Each
 * map entry is an additive nudge in {@code [-0.6, +0.6]}; missing
 * entries leave the trait at the random baseline.
 *
 * <p>Per-culture biases per spec lines 145-227:</p>
 * <ul>
 *   <li>Plainfolk — light +Industry only.</li>
 *   <li>Highmarch — +Courage / +Ambition / -Sociability.</li>
 *   <li>Silkwood — +Temperance / +Compassion / +Sociability.</li>
 *   <li>Tidereach — +Sociability / +Generosity / +Honesty / -Temperance.</li>
 * </ul>
 */
public record CultureTraitBias(Map<TraitAxis, Float> biases) {

    public CultureTraitBias {
        if (biases == null || biases.isEmpty()) {
            biases = Map.of();
        } else {
            EnumMap<TraitAxis, Float> copy = new EnumMap<>(TraitAxis.class);
            biases.forEach((axis, value) -> {
                if (axis != null && value != null) copy.put(axis, value);
            });
            biases = Map.copyOf(copy);
        }
    }

    public static final CultureTraitBias NEUTRAL = new CultureTraitBias(Map.of());

    public float bias(TraitAxis axis) {
        if (axis == null) return 0f;
        return biases.getOrDefault(axis, 0f);
    }

    public static final Codec<CultureTraitBias> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(
                    Codec.STRING.xmap(s -> TraitAxis.valueOf(s.toUpperCase(Locale.ROOT)), TraitAxis::name),
                    Codec.FLOAT
            ).optionalFieldOf("biases", Map.of()).forGetter(CultureTraitBias::biases)
    ).apply(i, CultureTraitBias::new));
}
