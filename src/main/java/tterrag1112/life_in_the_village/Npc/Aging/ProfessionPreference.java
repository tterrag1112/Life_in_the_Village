package tterrag1112.life_in_the_village.Npc.Aging;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Profession.Profession;

/**
 * Child's preferred profession — read at coming-of-age to drive
 * apprenticeship handoff. Spec line 59.
 */
public record ProfessionPreference(Profession preferred, int strength) {

    public ProfessionPreference {
        if (preferred == null) preferred = Profession.NONE;
        if (strength < 0) strength = 0;
        if (strength > 10) strength = 10;
    }

    private static final Codec<Profession> PROFESSION_CODEC =
            Codec.STRING.xmap(Profession::valueOf, Profession::name);

    public static final Codec<ProfessionPreference> CODEC = RecordCodecBuilder.create(i -> i.group(
            PROFESSION_CODEC.optionalFieldOf("preferred", Profession.NONE)
                    .forGetter(ProfessionPreference::preferred),
            Codec.INT.optionalFieldOf("strength", 0)
                    .forGetter(ProfessionPreference::strength)
    ).apply(i, ProfessionPreference::new));
}
