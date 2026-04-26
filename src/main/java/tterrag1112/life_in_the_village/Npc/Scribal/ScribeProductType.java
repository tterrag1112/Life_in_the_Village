package tterrag1112.life_in_the_village.Npc.Scribal;

import com.mojang.serialization.Codec;

/**
 * Categories of scribal output. Spec line 130. Determines which
 * physical item is produced and the base fee in
 * {@link ScribeCommission#feeFor}.
 */
public enum ScribeProductType {
    /** Personal correspondence — short or long. */
    LETTER,
    /** Apprenticeship / property / marriage contract. */
    CONTRACT,
    /** Faithful copy of an existing book. */
    BOOK_COPY,
    /** Official decree authored on behalf of an office holder. */
    DECREE;

    public static final Codec<ScribeProductType> CODEC =
            Codec.STRING.xmap(ScribeProductType::valueOf, ScribeProductType::name);
}
