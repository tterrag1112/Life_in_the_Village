package tterrag1112.life_in_the_village.Npc.Letters;

import com.mojang.serialization.Codec;

/**
 * Top-level shelf category for an {@link ExtendedBookContent}. Spec
 * line 76. Used by {@link tterrag1112.life_in_the_village.Npc.Scribal.LibraryCatalogue}
 * sorting and by {@link BookEffects} to pick the read-time mood
 * trigger.
 */
public enum BookCategory {
    /** Kingdom / village history excerpts. */
    HISTORY,
    /** Skill-buff "textbook". */
    GUIDE,
    /** Authored fiction or poetry. */
    LITERATURE,
    /** Scripture, rites. */
    RELIGIOUS,
    /** Healing / herbal lore. */
    MEDICAL,
    /** Foreign knowledge from travel. */
    TRAVELOGUE,
    /** Village or guild records. */
    LEDGER,
    /** Published correspondence. */
    LETTERS;

    public static final Codec<BookCategory> CODEC =
            Codec.STRING.xmap(BookCategory::valueOf, BookCategory::name);
}
