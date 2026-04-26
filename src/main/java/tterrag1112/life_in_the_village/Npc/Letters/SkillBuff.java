package tterrag1112.life_in_the_village.Npc.Letters;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

/**
 * Static skill-buff payload baked into a textbook-style book. Spec
 * line 87.
 *
 * <p>{@code xpOnRead} fires once when the reader picks up the book.
 * If {@code rateMultiplier &gt; 1.0f}, an {@link ActiveSkillBuff} is
 * stacked onto the reader's {@link tterrag1112.life_in_the_village.Npc.Skills.SkillComponent}
 * for {@code studyMultiplierDays} in-game days.</p>
 */
public record SkillBuff(
        Skill skill,
        int xpOnRead,
        float rateMultiplier,
        int studyMultiplierDays
) {
    public SkillBuff {
        if (skill == null) skill = Skill.LITERACY;
        if (xpOnRead < 0) xpOnRead = 0;
        if (rateMultiplier < 1f) rateMultiplier = 1f;
        if (studyMultiplierDays < 0) studyMultiplierDays = 0;
    }

    /** True when this buff carries a multiplier the SkillComponent should track. */
    public boolean hasOngoingMultiplier() {
        return rateMultiplier > 1f && studyMultiplierDays > 0;
    }

    public static final Codec<SkillBuff> CODEC = RecordCodecBuilder.create(i -> i.group(
            Skill.CODEC.fieldOf("skill").forGetter(SkillBuff::skill),
            Codec.INT.optionalFieldOf("xpOnRead", 0).forGetter(SkillBuff::xpOnRead),
            Codec.FLOAT.optionalFieldOf("rateMultiplier", 1f).forGetter(SkillBuff::rateMultiplier),
            Codec.INT.optionalFieldOf("studyMultiplierDays", 0).forGetter(SkillBuff::studyMultiplierDays)
    ).apply(i, SkillBuff::new));
}
