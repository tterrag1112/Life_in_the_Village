package tterrag1112.life_in_the_village.Npc.Gossip;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeEntry;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeSource;
import tterrag1112.life_in_the_village.Npc.Knowledge.NpcKnowledgeLedger;
import tterrag1112.life_in_the_village.Npc.Knowledge.RumorMutator;
import tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger;
import tterrag1112.life_in_the_village.Npc.Relations.NpcRelationshipLedger;
import tterrag1112.life_in_the_village.Npc.Relations.RelationshipMode;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements one round of the gossip exchange protocol per spec line 92.
 * Stateless static class; called by {@link GossipScheduler} for each
 * channel tick and by the debug {@code /gossip force-exchange} command.
 *
 * <p>Per-call this picks a topic from the speaker's knowledge, applies
 * fidelity drop + content mutation + slant, then adds the entry to the
 * listener's ledger and bumps the village topic-heat counter.</p>
 */
public final class GossipExchange {

    /** Speaker won't gossip an entry below this fidelity (spec line 114). */
    public static final float MIN_SHAREABLE_FIDELITY = 0.3f;
    /** Spec line 188: low-honesty fabrication thresholds. */
    public static final float HONESTY_LIE_THRESHOLD = -0.4f;
    /** Spec line 188: fidelity threshold below which a low-honesty teller embellishes. */
    public static final float EMBELLISH_FIDELITY_THRESHOLD = 0.5f;
    /** Self-fidelity claimed when a low-honesty teller embellishes. */
    public static final float EMBELLISH_FAKE_FIDELITY = 0.7f;

    private GossipExchange() {}

    /** Outcome of a single {@link #run} call. */
    public record ExchangeResult(boolean transferred,
                                 String topic,
                                 String content,
                                 float fidelity,
                                 boolean slantedNegative,
                                 boolean slantedPositive,
                                 boolean embellished) {
        public static ExchangeResult skipped() {
            return new ExchangeResult(false, "", "", 0f, false, false, false);
        }
    }

    /**
     * Performs one knowledge-transfer step from {@code speaker} to
     * {@code listener}. Returns {@link ExchangeResult#skipped} when the
     * speaker has nothing shareable or the listener already has the same
     * (or higher-fidelity) entry.
     *
     * @param level   server level
     * @param speaker the NPC sharing
     * @param listener the NPC receiving (may be the same as the player
     *                in the future "tell rumor" flow; here both are
     *                NPCs)
     * @param tick    current game tick
     */
    public static ExchangeResult run(ServerLevel level,
                                     TownspersonMob speaker,
                                     TownspersonMob listener,
                                     long tick) {
        NpcKnowledgeLedger speakerLedger = speaker.getKnowledge();
        if (speakerLedger.isEmpty()) return ExchangeResult.skipped();

        // ── Pick a topic per spec weighted distribution ─────────────────
        RandomSource rng = level.getRandom();
        Optional<KnowledgeEntry> picked = pickTopic(speakerLedger, listener, rng, tick);
        if (picked.isEmpty()) return ExchangeResult.skipped();
        KnowledgeEntry source = picked.get();

        // ── Listener already knows it as well or better? skip ───────────
        var existing = listener.getKnowledge().get(source.topic()).orElse(null);
        float incomingFidelity = Math.max(KnowledgeEntry.FIDELITY_FLOOR,
                source.fidelity() - KnowledgeEntry.RETELLING_DECAY);
        if (existing != null && existing.fidelity() >= incomingFidelity) {
            return ExchangeResult.skipped();
        }

        // ── Determine slant from speaker's relationship to subject ──────
        UUID subjectId = source.sourceId().orElse(null);
        RelationshipMode subjectRelation = RelationshipMode.NEUTRAL;
        if (subjectId != null && !subjectId.equals(speaker.getUUID())) {
            subjectRelation = speaker.getNpcRelationships().getMode(subjectId);
        }

        // ── Honesty-driven embellishment (low-honesty + low-fidelity) ──
        float honesty = speaker.getTraitVector().get(TraitAxis.HONESTY);
        boolean embellish = honesty < HONESTY_LIE_THRESHOLD
                && source.fidelity() < EMBELLISH_FIDELITY_THRESHOLD;
        float reportedFidelity = embellish ? EMBELLISH_FAKE_FIDELITY : incomingFidelity;

        // ── Mutate content via RumorMutator (deterministic per spec) ───
        long seed = RumorMutator.deriveSeed(source.topic(), source.acquiredTick(), speaker.getUUID());
        String mutated = RumorMutator.mutate(source.content(), incomingFidelity, seed);

        // ── Apply slant on top of the mutation ─────────────────────────
        boolean negSlant = false;
        boolean posSlant = false;
        if (subjectRelation == RelationshipMode.GRUDGE
                || subjectRelation == RelationshipMode.FEUD) {
            mutated = SlantTemplates.slantNegative(mutated, RandomSource.create(seed ^ 0x5AL));
            negSlant = true;
        } else if (subjectRelation == RelationshipMode.FRIEND
                || subjectRelation == RelationshipMode.CLOSE_FRIEND) {
            mutated = SlantTemplates.slantPositive(mutated, RandomSource.create(seed ^ 0xA5L));
            posSlant = true;
        }

        // ── Write the new entry to the listener's ledger ───────────────
        KnowledgeEntry retold = KnowledgeEntry.create(
                source.topic(),
                source.category(),
                reportedFidelity,
                KnowledgeSource.RUMOR_RETOLD,
                tick,
                mutated,
                source.sourceId().orElse(null),
                source.sensitive());
        listener.getKnowledge().add(retold);

        // ── Topic-heat bump (per village) ──────────────────────────────
        bumpVillageHeat(level, speaker, source.topic());

        // ── Mood trigger if the topic is about the listener ────────────
        if (subjectId != null && subjectId.equals(listener.getUUID())) {
            MoodTrigger trig = (negSlant || incomingFidelity < 0.5f)
                    ? MoodTrigger.RUMOR_NEGATIVE_ABOUT_SELF
                    : MoodTrigger.RUMOR_POSITIVE_ABOUT_SELF;
            listener.getMood().apply(trig, listener.getTraitVector(), tick);
        }

        return new ExchangeResult(true, source.topic(), mutated,
                reportedFidelity, negSlant, posSlant, embellish);
    }

    /**
     * Spec line 92 selection weights: 30% hot, 30% listener-relevant,
     * 20% shared-acquaintance, 20% random (anything ≥ shareable
     * fidelity). Filtered to entries with fidelity ≥ {@link
     * #MIN_SHAREABLE_FIDELITY} before weighting; "hot" within last 7
     * in-game days.
     */
    private static Optional<KnowledgeEntry> pickTopic(NpcKnowledgeLedger ledger,
                                                      TownspersonMob listener,
                                                      RandomSource rng,
                                                      long tick) {
        List<KnowledgeEntry> shareable = new ArrayList<>();
        for (KnowledgeEntry e : ledger.all()) {
            if (e.fidelity() >= MIN_SHAREABLE_FIDELITY) shareable.add(e);
        }
        if (shareable.isEmpty()) return Optional.empty();

        long sevenDaysAgo = tick - 7L * 24000L;
        UUID listenerId = listener.getUUID();
        UUID listenerSpouse = listener.getFamily().getSpouseId().orElse(null);

        List<KnowledgeEntry> hot = new ArrayList<>();
        List<KnowledgeEntry> listenerRelevant = new ArrayList<>();
        List<KnowledgeEntry> sharedAcquaintance = new ArrayList<>();
        for (KnowledgeEntry e : shareable) {
            if (e.acquiredTick() >= sevenDaysAgo) hot.add(e);
            UUID subj = e.sourceId().orElse(null);
            if (subj != null) {
                if (subj.equals(listenerId)
                        || (listenerSpouse != null && subj.equals(listenerSpouse))) {
                    listenerRelevant.add(e);
                } else if (listener.getNpcRelationships().get(subj).isPresent()) {
                    sharedAcquaintance.add(e);
                }
            }
        }

        float roll = rng.nextFloat();
        if (roll < 0.30f && !hot.isEmpty()) {
            return Optional.of(hot.get(rng.nextInt(hot.size())));
        }
        if (roll < 0.60f && !listenerRelevant.isEmpty()) {
            return Optional.of(listenerRelevant.get(rng.nextInt(listenerRelevant.size())));
        }
        if (roll < 0.80f && !sharedAcquaintance.isEmpty()) {
            return Optional.of(sharedAcquaintance.get(rng.nextInt(sharedAcquaintance.size())));
        }
        return Optional.of(shareable.get(rng.nextInt(shareable.size())));
    }

    private static void bumpVillageHeat(ServerLevel level, TownspersonMob speaker, String topic) {
        VillageSavedData data = VillageSavedData.get(level);
        speaker.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .ifPresent(v -> {
                    data.getOrCreateGossipHeat(v.getId()).bump(topic);
                    data.markDirty();
                });
    }
}
