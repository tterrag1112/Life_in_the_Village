package tterrag1112.life_in_the_village.Npc.Letters;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeCategory;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeEntry;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeSource;
import tterrag1112.life_in_the_village.Npc.Relations.RelationshipOrigin;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

/**
 * Static {@code onLetterReceived} pipeline (spec line 150). Called
 * from the NPC right-click delivery path and from the
 * {@code PostalGoal} once the letter actually reaches the recipient.
 *
 * <p>The pipeline is split into three responsibilities:</p>
 * <ol>
 *   <li>Fire the {@link NpcLifeEvent.LetterReceived} bus event so the
 *       memory / mood producers do their thing.</li>
 *   <li>Refresh memory of the sender (spec line 165).</li>
 *   <li>Bump the relationship by +2 (spec line 176).</li>
 *   <li>Optionally seed knowledge entries from a scholar-tagged
 *       letter — currently a no-op since v1 letters have no
 *       topic-tags field; spec line 169 marks this Phase 5.</li>
 * </ol>
 */
public final class LetterEffects {

    /** Memory-refresh strength on receiving a letter from a known sender. */
    public static final float MEMORY_REFRESH = 8f;
    /** Spec line 176: +2 relationship adjust per letter. */
    public static final int RELATIONSHIP_BUMP = 2;
    /** LITERACY XP gained for the act of reading. */
    public static final int LITERACY_XP_PER_READ = 3;

    private LetterEffects() {}

    /**
     * Runs the receive pipeline for a literate recipient. Caller is
     * responsible for the LITERACY ≥ 30 check.
     */
    public static void onLetterReceived(TownspersonMob recipient,
                                        LetterContent content,
                                        ServerLevel level) {
        if (recipient == null || content == null) return;
        long now = level.getGameTime();

        // 1) Bus event — drives the memory and mood producers.
        NpcLifeEventBus.fire(new NpcLifeEvent.LetterReceived(recipient, content));

        // 2) Refresh memory of the sender if any.
        recipient.getMemory().all().stream()
                .filter(m -> m.participantIds().contains(content.authorId()))
                .findFirst()
                .ifPresent(m -> recipient.getMemory().refresh(m.memoryId(), MEMORY_REFRESH));

        // 3) Relationship bump if sender is another NPC.
        if (!content.authorId().equals(LetterContent.NIL_UUID)
                && !content.authorId().equals(recipient.getUUID())) {
            recipient.getNpcRelationships().adjust(
                    content.authorId(), RELATIONSHIP_BUMP, now,
                    RelationshipOrigin.MET_SOCIALLY);
        }

        // 4) LITERACY trickle for the reading act.
        recipient.getSkills().addXp(Skill.LITERACY, LITERACY_XP_PER_READ, now);

        // 5) Knowledge from scholar-tagged letters — Phase 5 expands.
        // For now, the only knowledge that can be attached is via the
        // optional letter-special tag CONTRACT, which seeds an entry
        // with the contract body so receivers can ask_about it.
        if (content.special().filter(s -> s == LetterSpecial.CONTRACT).isPresent()) {
            String topic = "contract." + content.letterId();
            recipient.getKnowledge().add(KnowledgeEntry.create(
                    topic,
                    KnowledgeCategory.PERSONAL,
                    0.9f,
                    KnowledgeSource.LETTER,
                    now,
                    content.fullText(),
                    content.authorId()));
        }
    }
}
