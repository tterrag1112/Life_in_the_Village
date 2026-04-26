package tterrag1112.life_in_the_village.Npc.Letters;

import com.mojang.serialization.Codec;

/**
 * Optional flavour tag on a {@link LetterContent}. Controls the
 * {@link tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger} chosen
 * for the recipient and surfaces in dialogue. Spec line 43.
 */
public enum LetterSpecial {
    LOVE_LETTER,
    THREAT,
    APOLOGY,
    /** Mass / official letter (kingdom decree). */
    ANNOUNCEMENT,
    /** Apprenticeship or other formal agreement. */
    CONTRACT,
    /** "I commend the bearer to you." */
    INTRODUCTION;

    public static final Codec<LetterSpecial> CODEC =
            Codec.STRING.xmap(LetterSpecial::valueOf, LetterSpecial::name);
}
