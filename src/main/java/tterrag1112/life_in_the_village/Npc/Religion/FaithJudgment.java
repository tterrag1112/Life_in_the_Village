package tterrag1112.life_in_the_village.Npc.Religion;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Crime.CrimeType;
import tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger;
import tterrag1112.life_in_the_village.Npc.Relations.RelationshipOrigin;

import java.util.List;
import java.util.UUID;

/**
 * Religion Deepening D2 — the one place an NPC act (mapped to a {@link FaithConcept})
 * is judged <b>against the actor's own faith</b>, with co-religionist (or
 * same-value) witnesses judging the actor. The headline of the deepening: living a
 * faith's virtues rewards (piety + contentment + standing), breaking its taboos
 * costs (guilt + a small piety loss + the faithful think less of you).
 *
 * <p><b>Faith-relative throughout:</b> a Sunstead NPC is never judged by Forge
 * taboos; an atheist is never judged at all. A witness judges only if THE WITNESS'S
 * faith holds the concept (so a co-religionist always judges; a different faith that
 * shares the value also judges — "co-religionist or holds the same value").</p>
 *
 * <p><b>Bounded + anti-spiral:</b> per-act magnitudes are tiny (piety ±0.02,
 * clamped to [0,1] by {@code adjustBelief}; mood ±6/8 under {@code MoodTrigger}'s
 * daily cap; witness relationship ±3/4, clamped to [-100,100]). A single sin is a
 * dip, not ruin; a single good act is a nudge, not sanctification. All hooks call
 * this one helper.</p>
 */
public final class FaithJudgment {

    private FaithJudgment() {}

    private static final float VIRTUE_PIETY = 0.02f;   // adjustBelief clamps [0,1]
    private static final float TABOO_PIETY  = -0.02f;
    private static final int   CONTENT_MOOD = 6;        // virtue → contentment
    private static final int   GUILT_MOOD   = -8;       // taboo → guilt (own conscience)
    private static final int   WITNESS_VIRTUE_REL = 3;  // the faithful esteem the virtuous
    private static final int   WITNESS_TABOO_REL  = -4; // the faithful judge the sinner

    /**
     * Judge {@code actor}'s act (its {@link FaithConcept}) against the actor's own
     * faith, applying the reward (virtue) or guilt (taboo) to the actor, and the
     * witnesses who hold the concept judging the actor. Neutral concept (neither a
     * virtue nor a taboo of the actor's faith) ⇒ no effect. Atheist actor ⇒ no
     * effect. {@code witnesses} may be empty (solitary acts).
     */
    public static void judge(TownspersonMob actor, FaithConcept concept,
                             List<TownspersonMob> witnesses, long now) {
        if (actor == null || concept == null) return;
        String faith = actor.getPiety().primaryReligion().orElse(null);
        if (faith == null) return;                       // unaffiliated — no faith judges it
        Religion religion = ReligionRegistry.get(faith);
        if (religion == null) return;

        // F1a 4a — virtue/taboo recognition is the UNION across the religion's gods.
        boolean virtue = GodRegistry.anyGodHoldsVirtue(religion, concept);
        boolean taboo  = GodRegistry.anyGodHoldsTaboo(religion, concept);
        if (!virtue && !taboo) return;                   // neutral for THIS faith

        // Actor: reward (virtue) or guilt (taboo) on their own primary faith.
        actor.getPiety().adjustBelief(faith, virtue ? VIRTUE_PIETY : TABOO_PIETY);
        actor.getMood().applyWithRawMagnitude(
                virtue ? MoodTrigger.CONTENTMENT : MoodTrigger.GUILT,
                virtue ? CONTENT_MOOD : GUILT_MOOD, now);

        // Witnesses who hold the concept judge the actor (faith-relative).
        if (witnesses == null || witnesses.isEmpty()) return;
        UUID actorId = actor.getUUID();
        for (TownspersonMob w : witnesses) {
            if (w == null || w.getUUID().equals(actorId)) continue;
            String wFaith = w.getPiety().primaryReligion().orElse(null);
            if (wFaith == null) continue;                // atheist witness — indifferent
            Religion wReligion = ReligionRegistry.get(wFaith);
            if (wReligion == null) continue;
            if (virtue && GodRegistry.anyGodHoldsVirtue(wReligion, concept)) {
                w.getNpcRelationships().adjust(actorId, WITNESS_VIRTUE_REL, now,
                        RelationshipOrigin.MET_SOCIALLY);
            } else if (taboo && GodRegistry.anyGodHoldsTaboo(wReligion, concept)) {
                w.getNpcRelationships().adjust(actorId, WITNESS_TABOO_REL, now,
                        RelationshipOrigin.MET_IN_CONFLICT);
            }
        }
    }

    /**
     * The {@link FaithConcept} taboo a crime represents, or {@code null} when the
     * crime maps to no faith taboo (neutral — e.g. trespassing, or a future type).
     * A {@code default} arm keeps this safe as {@code CrimeType} grows.
     */
    public static FaithConcept conceptForCrime(CrimeType type) {
        if (type == null) return null;
        return switch (type) {
            case THEFT_MINOR, THEFT_MAJOR -> FaithConcept.GREED;
            case ASSAULT, MURDER          -> FaithConcept.DISCORD;
            case FRAUD, BRIBERY, CONTRACT_BREACH, PERJURY,
                 SMUGGLING, TAX_EVASION, SEAL_VIOLATION -> FaithConcept.DECEIT;
            case VANDALISM                -> FaithConcept.SACRILEGE;
            default                       -> null;       // TRESPASSING + future types
        };
    }

}
