package tterrag1112.life_in_the_village.Npc.Gossip;

import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeCategory;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeEntry;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeSource;

import java.util.UUID;

/**
 * Transient seed used by {@link RumorSeeder} to mint a new
 * {@link KnowledgeEntry} on the originating NPC's ledger. Not
 * persisted — once {@link #toEntry} runs, the resulting entry lives in
 * the regular knowledge ledger and the seed is discarded.
 *
 * <p>Spec: {@code docs/npc_redesign/12-gossip-rumor.md} (line 50).</p>
 */
public record RumorSeed(
        String topic,
        String initialContent,
        KnowledgeCategory category,
        UUID originId,
        long seedTick,
        float startFidelity
) {
    /** Builds a {@link KnowledgeEntry} for the seed's origin NPC. */
    public KnowledgeEntry toEntry() {
        return KnowledgeEntry.create(
                topic,
                category,
                startFidelity,
                KnowledgeSource.RUMOR_HEARD,
                seedTick,
                initialContent,
                originId);
    }
}
