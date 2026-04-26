package tterrag1112.life_in_the_village.Npc.Aging;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

/**
 * One of a child's 2-3 "interested skills" — a future-profession
 * hint expressed as a (skill, interest 1..10) pair. Spec line 49.
 */
public record ChildhoodSkill(Skill skill, int interest) {

    public ChildhoodSkill {
        if (skill == null) skill = Skill.LITERACY;
        if (interest < 1) interest = 1;
        if (interest > 10) interest = 10;
    }

    public static final Codec<ChildhoodSkill> CODEC = RecordCodecBuilder.create(i -> i.group(
            Skill.CODEC.fieldOf("skill").forGetter(ChildhoodSkill::skill),
            Codec.INT.optionalFieldOf("interest", 5).forGetter(ChildhoodSkill::interest)
    ).apply(i, ChildhoodSkill::new));
}
