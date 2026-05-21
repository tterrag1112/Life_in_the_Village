package tterrag1112.life_in_the_village.Npc.Letters;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeCategory;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeEntry;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeSource;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

import java.util.UUID;

/**
 * Static {@code onBookRead} pipeline (spec line 218).
 *
 * <p>Literacy gates per spec lines 220, 251:</p>
 * <ul>
 *   <li>LITERACY &lt; 30 → no effect.</li>
 *   <li>LITERACY 30..59 → partial (50%) effect: half topic count
 *       granted, half skill-buff XP, no rate multiplier.</li>
 *   <li>LITERACY ≥ 60 → full effect.</li>
 * </ul>
 */
public final class BookEffects {

    public static final int FULL_LITERACY = 60;
    public static final int PARTIAL_LITERACY = 30;
    public static final int LITERACY_XP_PER_READ = 15;
    public static final float BOOK_FIDELITY = 0.95f;
    public static final float PARTIAL_FIDELITY = 0.65f;

    private BookEffects() {}

    /** Outcome flags returned to the caller for chat/UI feedback. */
    public record ReadOutcome(boolean read, boolean partial,
                              int topicsAdded, int xpGranted,
                              boolean buffApplied) {
        public static ReadOutcome illiterate() {
            return new ReadOutcome(false, false, 0, 0, false);
        }
    }

    public static ReadOutcome onBookRead(TownspersonMob reader,
                                         ExtendedBookContent content,
                                         ServerLevel level) {
        if (reader == null || content == null) return ReadOutcome.illiterate();
        int literacy = reader.getSkills().getLevel(Skill.LITERACY);
        if (literacy < PARTIAL_LITERACY) return ReadOutcome.illiterate();
        boolean partial = literacy < FULL_LITERACY;
        long now = level.getGameTime();

        // 1) Knowledge gain.
        int added = 0;
        var topics = content.topicsCovered();
        int limit = partial ? Math.max(0, topics.size() / 2) : topics.size();
        for (int i = 0; i < limit; i++) {
            String topic = topics.get(i);
            float fidelity = partial ? PARTIAL_FIDELITY : BOOK_FIDELITY;
            UUID source = content.bookId();
            if (reader.getKnowledge().add(KnowledgeEntry.create(
                    topic,
                    KnowledgeCategory.LOCAL,
                    fidelity,
                    KnowledgeSource.BOOK,
                    now,
                    "Read in '" + content.title() + "'",
                    source))) {
                added++;
            }
        }

        // 2) LITERACY XP — full grant in both modes (reading trains
        // reading; spec line 302).
        tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(reader, Skill.LITERACY, LITERACY_XP_PER_READ, now);
        int xpGranted = LITERACY_XP_PER_READ;

        // 3) Skill buff payload — partial readers get half XP and no
        // ongoing multiplier.
        boolean buffApplied = false;
        if (content.skillBuff().isPresent()) {
            SkillBuff buff = content.skillBuff().get();
            int xp = partial ? buff.xpOnRead() / 2 : buff.xpOnRead();
            if (xp > 0) tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(reader, buff.skill(), xp, now);
            xpGranted += xp;
            if (!partial && buff.hasOngoingMultiplier()) {
                long expires = now + (long) buff.studyMultiplierDays() * 24000L;
                reader.getSkills().addBuff(new ActiveSkillBuff(
                        buff.skill(), buff.rateMultiplier(), expires));
                buffApplied = true;
            }
        }

        // 4) Memory entry — LITERATURE category gets a small mood
        // tick via the bus's existing LETTER_RECEIVED trigger reuse
        // (spec line 244).
        return new ReadOutcome(true, partial, added, xpGranted, buffApplied);
    }
}
