package tterrag1112.life_in_the_village.Npc.Gossip;

import net.minecraft.util.RandomSource;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeCategory;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeEntry;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeSource;
import tterrag1112.life_in_the_village.Npc.Memory.MemoryType;
import tterrag1112.life_in_the_village.Npc.Memory.NpcMemory;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;

import java.util.UUID;

/**
 * Promotes recent high-value memories into {@link RumorSeed}s on the
 * holder's own knowledge ledger. Spec line 156:
 *
 * <blockquote>Once per day, each NPC has a small chance (proportional
 * to Sociability + 1) to generate a new RumorSeed from a recent
 * high-value memory.</blockquote>
 *
 * <p>Phase 2 ships memory→seed promotion. Spec lines 174–183 list
 * world-event seeders (festival end, caravan arrival, building burn /
 * built, office change). Those are deferred — see 12 Revision Notes.
 * Once those events fire {@link tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent}
 * records, the seeder will hook them via the bus the same way as
 * memory promotion.</p>
 */
public final class RumorSeeder {

    /** Memory currentValue threshold above which it's "noteworthy". */
    public static final int NOTEWORTHY_VALUE = 60;
    /** Spec's "recent" window: last 7 in-game days. */
    public static final long RECENT_WINDOW_TICKS = 7L * 24000L;
    /** Base daily-roll probability before sociability scaling. */
    public static final float BASE_DAILY_PROB = 0.10f;
    /** Default fidelity stamped on rumor seeds (spec line 62: 0.7-0.9). */
    public static final float SEED_FIDELITY = 0.8f;

    private RumorSeeder() {}

    /**
     * Daily roll for one NPC. Returns the new {@link KnowledgeEntry}
     * stored on the NPC's ledger, or {@code null} if no seed promoted.
     * Idempotent on the same memory: an entry with the same topic
     * lands once (knowledge ledger upgrade rules suppress duplicates).
     */
    public static KnowledgeEntry rollDaily(TownspersonMob npc, long currentTick) {
        if (npc == null) return null;
        float sociability = npc.getTraitVector().get(TraitAxis.SOCIABILITY);
        float chance = BASE_DAILY_PROB * (1f + sociability);
        if (chance <= 0f) return null;
        RandomSource rng = npc.level().getRandom();
        if (rng.nextFloat() >= chance) return null;

        // Pick a recent noteworthy memory.
        long earliest = currentTick - RECENT_WINDOW_TICKS;
        NpcMemory chosen = null;
        for (NpcMemory m : npc.getMemory().all()) {
            if (m.tick() < earliest) continue;
            if (m.currentValue() < NOTEWORTHY_VALUE) continue;
            if (!isPromotable(m.type())) continue;
            if (chosen == null || m.currentValue() > chosen.currentValue()) chosen = m;
        }
        if (chosen == null) return null;

        UUID subject = chosen.participantIds().isEmpty() ? null : chosen.participantIds().get(0);
        String topic = topicFromMemory(chosen, npc.getUUID());
        if (npc.getKnowledge().knows(topic)) return null;

        String content = chosen.summary().isEmpty()
                ? defaultContentFor(chosen.type())
                : chosen.summary();

        KnowledgeEntry entry = KnowledgeEntry.create(
                topic,
                KnowledgeCategory.PERSONAL,
                SEED_FIDELITY,
                KnowledgeSource.WITNESS,
                currentTick,
                content,
                subject);
        npc.getKnowledge().add(entry);
        return entry;
    }

    /** Builds a stable topic key from a memory. */
    public static String topicFromMemory(NpcMemory m, UUID owner) {
        String subj = m.participantIds().isEmpty() ? "self" : m.participantIds().get(0).toString();
        return "memory." + m.type().name() + "." + subj + "." + Long.toHexString(m.tick());
    }

    /**
     * Memory types whose substance is shareable as gossip. Excludes
     * trade and contractual entries which read as private and don't
     * propagate naturally per spec's "types of memories that become
     * rumors" list.
     */
    private static boolean isPromotable(MemoryType t) {
        return switch (t) {
            case WITNESSED_CRIME_BY,
                 VICTIM_OF_CRIME_BY,
                 INSULTED_BY,
                 COMPLIMENTED_BY,
                 RECEIVED_GIFT,
                 GAVE_GIFT,
                 SAVED_BY,
                 SAVED,
                 DEFENDED_BY,
                 SHARED_FESTIVAL,
                 SHARED_HARDSHIP,
                 WITNESSED_DEATH_OF,
                 TAUGHT_BY,
                 TAUGHT,
                 OFFICIATED_BY,
                 OFFICIATED_FOR -> true;
            default -> false;
        };
    }

    private static String defaultContentFor(MemoryType t) {
        return switch (t) {
            case WITNESSED_CRIME_BY  -> "I saw it happen with my own eyes.";
            case VICTIM_OF_CRIME_BY  -> "It happened to me, plain as day.";
            case INSULTED_BY         -> "The things they said — unforgivable.";
            case COMPLIMENTED_BY     -> "They had kind words, truly.";
            case RECEIVED_GIFT       -> "A generous gift, given freely.";
            case GAVE_GIFT           -> "I gave what I could spare.";
            case SAVED_BY            -> "I owe them my life.";
            case SAVED               -> "I pulled them clear of harm.";
            case DEFENDED_BY         -> "They stood up for me when no one else did.";
            case SHARED_FESTIVAL     -> "We celebrated together.";
            case SHARED_HARDSHIP     -> "We weathered it together.";
            case WITNESSED_DEATH_OF  -> "I was there at the end.";
            case TAUGHT_BY           -> "They taught me what I needed to know.";
            case TAUGHT              -> "I shared what I knew with them.";
            case OFFICIATED_BY       -> "The rite was done by their hand.";
            case OFFICIATED_FOR      -> "I performed the rite for them.";
            default -> "Something to remember.";
        };
    }
}
