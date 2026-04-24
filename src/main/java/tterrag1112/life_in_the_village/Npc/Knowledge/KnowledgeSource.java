package tterrag1112.life_in_the_village.Npc.Knowledge;

import com.mojang.serialization.Codec;

/**
 * How a {@link KnowledgeEntry} was acquired. Drives the default fidelity
 * (see {@code docs/npc_redesign/03-knowledge-system.md}, "Fidelity rules"),
 * and later phases branch behaviour on it — e.g. {@code FABRICATED} rumors
 * read as known to the fabricator but see their real fidelity when retold.
 */
public enum KnowledgeSource {
    /** Direct observation (fidelity 1.0). */
    WITNESS,
    /** Formal teaching (fidelity 0.9). */
    EDUCATION,
    /** Read in a book (0.95 literate / 0.65 partial / 0 illiterate). */
    BOOK,
    /** First-hand rumor from an eyewitness / education / book source. */
    RUMOR_HEARD,
    /** Second-hand-or-later rumor, further degraded. */
    RUMOR_RETOLD,
    /** Received in a letter (fidelity 0.9). */
    LETTER,
    /** Intrinsic to the NPC — knew from being born there. */
    BIRTH_LOCAL,
    /** NPC made it up (stored fidelity 0.3; NPC treats as higher). */
    FABRICATED;

    public static final Codec<KnowledgeSource> CODEC =
            Codec.STRING.xmap(KnowledgeSource::valueOf, KnowledgeSource::name);
}
