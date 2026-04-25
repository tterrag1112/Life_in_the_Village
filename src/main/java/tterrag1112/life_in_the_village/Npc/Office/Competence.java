package tterrag1112.life_in_the_village.Npc.Office;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

/**
 * Effect curve for an office: how the holder's skill level translates
 * into the multiplier the org receives. Phase 0 stores the curve;
 * Phase 3 reads {@link #computeMultiplier} into the office's effect hook
 * (tax collection, request success rate, etc.).
 *
 * <p>Vacant offices return {@code 1 + vacancyPenalty} (typically a
 * negative number).</p>
 */
public record Competence(
        Skill primarySkill,
        int minLevel,
        int effectiveAtLevel,
        float maxBonus,
        float vacancyPenalty
) {
    /**
     * Spec formula (06-office-framework.md "Competence curve"). Vacancy
     * penalty applies when {@code skillLevel < minLevel}; otherwise the
     * multiplier ramps linearly from 1.0 at {@code minLevel} to
     * {@code maxBonus} at {@code effectiveAtLevel}.
     */
    public float computeMultiplier(int skillLevel) {
        if (skillLevel < minLevel) return 1f + vacancyPenalty;
        if (effectiveAtLevel <= minLevel) return maxBonus;
        float t = (float) (skillLevel - minLevel) / (float) (effectiveAtLevel - minLevel);
        if (t > 1f) t = 1f;
        return 1f + (maxBonus - 1f) * t;
    }

    public static final Codec<Competence> CODEC = RecordCodecBuilder.create(i -> i.group(
            Skill.CODEC.fieldOf("primarySkill").forGetter(Competence::primarySkill),
            Codec.INT.fieldOf("minLevel").forGetter(Competence::minLevel),
            Codec.INT.fieldOf("effectiveAtLevel").forGetter(Competence::effectiveAtLevel),
            Codec.FLOAT.fieldOf("maxBonus").forGetter(Competence::maxBonus),
            Codec.FLOAT.fieldOf("vacancyPenalty").forGetter(Competence::vacancyPenalty)
    ).apply(i, Competence::new));
}
