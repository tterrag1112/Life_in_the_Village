package tterrag1112.life_in_the_village.Npc.Knowledge;

import com.mojang.serialization.Codec;

/**
 * Scope bucket for a {@link KnowledgeEntry}. Determines how knowledge
 * propagates (local facts are broadly shared; foreign facts are rare and
 * vague) and how fidelity defaults are assigned at spawn.
 *
 * <p>See {@code docs/npc_redesign/03-knowledge-system.md}.</p>
 */
public enum KnowledgeCategory {
    /** Facts about specific NPCs or places learned via interaction. */
    PERSONAL,
    /** Facts about the home village — everyone born there knows. */
    LOCAL,
    /** Facts about nearby villages / the kingdom. */
    REGIONAL,
    /** Facts about distant kingdoms. */
    FOREIGN;

    public static final Codec<KnowledgeCategory> CODEC =
            Codec.STRING.xmap(KnowledgeCategory::valueOf, KnowledgeCategory::name);
}
