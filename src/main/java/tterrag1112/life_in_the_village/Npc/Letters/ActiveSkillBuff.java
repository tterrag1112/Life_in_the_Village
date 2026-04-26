package tterrag1112.life_in_the_village.Npc.Letters;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

/**
 * One live skill-rate buff currently applied to an NPC's
 * {@link tterrag1112.life_in_the_village.Npc.Skills.SkillComponent}.
 * Created when the NPC reads a textbook with a non-trivial
 * {@link SkillBuff}; expires at {@link #expiresTick}.
 *
 * <p>Spec line 234-241. The {@link tterrag1112.life_in_the_village.Npc.Skills.SkillComponent}
 * applies the multiplier in {@code addXp} for the matching
 * {@link Skill}.</p>
 */
public record ActiveSkillBuff(
        Skill skill,
        float rateMultiplier,
        long expiresTick
) {
    public ActiveSkillBuff {
        if (skill == null) skill = Skill.LITERACY;
        if (rateMultiplier < 1f) rateMultiplier = 1f;
    }

    public boolean expired(long currentTick) { return currentTick >= expiresTick; }

    public static final Codec<ActiveSkillBuff> CODEC = RecordCodecBuilder.create(i -> i.group(
            Skill.CODEC.fieldOf("skill").forGetter(ActiveSkillBuff::skill),
            Codec.FLOAT.fieldOf("rateMultiplier").forGetter(ActiveSkillBuff::rateMultiplier),
            Codec.LONG.fieldOf("expiresTick").forGetter(ActiveSkillBuff::expiresTick)
    ).apply(i, ActiveSkillBuff::new));
}
