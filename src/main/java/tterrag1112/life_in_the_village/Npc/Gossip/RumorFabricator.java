package tterrag1112.life_in_the_village.Npc.Gossip;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeCategory;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeEntry;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeSource;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;

import java.util.Optional;
import java.util.UUID;

/**
 * Spec line 187:
 *
 * <blockquote>Low-Honesty NPCs may create knowledge entries from
 * nothing. Trigger: when asked about a topic they don't know, with
 * Honesty &lt; -0.4 and Sociability &gt; +0.4. Fabricated entries have
 * source = FABRICATED and fidelity = 0.3, but the creator treats them
 * as reliable.</blockquote>
 *
 * <p>Caller is {@code AskAboutVerb} (and the future dialogue
 * ask-about handler when one lands). The verb passes the topic the
 * player asked about; if the NPC doesn't know it and the trait gates
 * pass, a synthetic entry is added to the NPC's ledger and reused on
 * the next gossip exchange.</p>
 */
public final class RumorFabricator {

    public static final float HONESTY_THRESHOLD     = -0.4f;
    public static final float SOCIABILITY_THRESHOLD = +0.4f;
    public static final float FABRICATED_FIDELITY   = 0.3f;

    private RumorFabricator() {}

    /**
     * Conditionally fabricates a {@link KnowledgeEntry} on the NPC's
     * ledger. No-op when the NPC already knows the topic, or when the
     * trait gates fail.
     *
     * @param npc          the NPC being asked
     * @param topic        the topic the player asked about
     * @param subjectId    the subject UUID (player or another NPC); may be null
     * @param currentTick  current game tick
     * @return the new entry if fabrication happened; empty otherwise.
     */
    public static Optional<KnowledgeEntry> maybeFabricate(TownspersonMob npc,
                                                          String topic,
                                                          UUID subjectId,
                                                          long currentTick) {
        if (npc == null || topic == null || topic.isEmpty()) return Optional.empty();
        if (npc.getKnowledge().knows(topic)) return Optional.empty();

        float honesty = npc.getTraitVector().get(TraitAxis.HONESTY);
        float sociability = npc.getTraitVector().get(TraitAxis.SOCIABILITY);
        if (honesty >= HONESTY_THRESHOLD) return Optional.empty();
        if (sociability <= SOCIABILITY_THRESHOLD) return Optional.empty();

        KnowledgeEntry entry = KnowledgeEntry.create(
                topic,
                KnowledgeCategory.PERSONAL,
                FABRICATED_FIDELITY,
                KnowledgeSource.FABRICATED,
                currentTick,
                "Well, between you and me — I heard some things.",
                subjectId);
        return npc.getKnowledge().add(entry) ? Optional.of(entry) : Optional.empty();
    }
}
