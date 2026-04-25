package tterrag1112.life_in_the_village.Npc.Skills;

import com.mojang.serialization.Codec;

/**
 * The eight cross-profession proficiencies. Persist when an NPC switches
 * professions; drive office competence, apprenticeship matching, and
 * production speed/quality multipliers (see
 * {@code docs/npc_redesign/05-skill-system.md}).
 */
public enum Skill {
    FARMING,
    CRAFTING,
    COMBAT,
    COMMERCE,
    SOCIAL,
    LITERACY,
    SURVIVAL,
    MEDICINE;

    public static final Codec<Skill> CODEC =
            Codec.STRING.xmap(Skill::valueOf, Skill::name);
}
