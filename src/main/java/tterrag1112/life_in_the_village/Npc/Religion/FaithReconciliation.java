package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * Religion Rework R3e-1 — the single reconciliation point between an NPC's
 * <em>personal</em> faith and the <em>local / officiating</em> faith.
 *
 * <p>Two canonical tests live here so neither is scattered:</p>
 * <ul>
 *   <li>{@link #faithBenefit(String, String)} / {@link #applyCommunalBenefit}
 *       — how a village-wide ceremony of one faith affects an attendee who may
 *       not share it. A co-religionist gets the full mood + deepens their own
 *       faith; everyone else gets a <em>reduced</em> mood and, instead of
 *       (wrongly) deepening their own faith, a small <em>syncretic drift</em>
 *       toward the officiating faith (the migrant slowly acculturates). This is
 *       the ONE site every cross-faith effect routes through — the R3d-1
 *       festival crowd pulse and the {@code RiteExecutor} communal handlers —
 *       so the "attending another faith's festival deepens MY faith" bug is
 *       fixed in exactly one place.</li>
 *   <li>{@link #isUnservedLocally} — the served/unserved predicate: an NPC is
 *       an unserved minority when their primary faith differs from the village's
 *       officiating faith, OR no priest staffs the village. Gates solo private
 *       devotion ({@code SoloDevotionBehavior}).</li>
 * </ul>
 *
 * <p>Magnitudes stay modest (anti-farm) and the per-rite SCALING still comes
 * from {@link RiteProfile} via {@link ReligionContent}; this class only layers
 * the cross-faith reconciliation on top — no parallel scaling system.</p>
 */
public final class FaithReconciliation {

    private FaithReconciliation() {}

    /** Mood multiplier applied to a ceremony's benefit for a non-co-religionist
     *  attendee (a fraction of the co-religionist's mood lift). */
    public static final float CROSS_FAITH_MOOD_MULT = 0.4f;

    /** Tiny belief gain a cross-faith attendee accrues in the OFFICIATING faith
     *  (slow acculturation), in place of any own-faith credit. */
    public static final float SYNCRETIC_DRIFT = 0.004f;

    /** The reconciled benefit for an attendee of {@code riteFaith} whose own
     *  faith is {@code attendeeFaith}. */
    public record FaithBenefit(float moodMultiplier, boolean sameFaith) {}

    /** Same faith → full benefit + own-faith credit; different (or none) →
     *  reduced mood + no own-faith credit (syncretic drift instead). */
    public static FaithBenefit faithBenefit(String attendeeFaith, String riteFaith) {
        boolean same = attendeeFaith != null && attendeeFaith.equals(riteFaith);
        return new FaithBenefit(same ? 1f : CROSS_FAITH_MOOD_MULT, same);
    }

    /**
     * Applies a village-wide ceremony's per-attendee benefit, reconciled across
     * faiths. {@code scaledMood} / {@code scaledPiety} are the rite's already-
     * {@link RiteProfile}-scaled magnitudes for a full co-religionist; this
     * method reduces the mood and reroutes the piety for everyone else.
     *
     * <ul>
     *   <li>Co-religionist → full {@code scaledMood}; their own faith deepens by
     *       {@code scaledPiety}.</li>
     *   <li>Different / no faith → {@code scaledMood × CROSS_FAITH_MOOD_MULT};
     *       NO own-faith credit. When the rite carries piety ({@code scaledPiety
     *       > 0}) they instead drift {@link #SYNCRETIC_DRIFT} toward
     *       {@code riteFaith}.</li>
     * </ul>
     *
     * <p>Does not touch attendance bookkeeping — callers still
     * {@code recordRiteAttendance} as before.</p>
     */
    public static void applyCommunalBenefit(TownspersonMob npc, String riteFaith,
                                            MoodTrigger trigger, int scaledMood,
                                            float scaledPiety, long now) {
        String attendeeFaith = npc.getPiety().primaryReligion().orElse(null);
        FaithBenefit fb = faithBenefit(attendeeFaith, riteFaith);

        int mood = Math.max(0, Math.round(scaledMood * fb.moodMultiplier()));
        if (mood > 0) {
            npc.getMood().applyWithRawMagnitude(trigger, mood, now);
        }

        if (fb.sameFaith()) {
            if (scaledPiety != 0f) npc.getPiety().adjustBelief(attendeeFaith, scaledPiety);
        } else if (scaledPiety > 0f) {
            // No own-faith credit from another faith's rite — a small drift
            // toward the officiating faith instead (acculturation). F1b-2 —
            // modulate the drift by the stance between the attendee's faith and the
            // officiating one: KINDRED (shared gods) syncretizes more readily,
            // NEUTRAL keeps today's baseline, RIVAL/HERETICAL resist/block (only once
            // an override sets them — none auto-derived this stage).
            float drift = SYNCRETIC_DRIFT * driftMultiplier(stanceFor(npc, attendeeFaith, riteFaith));
            if (drift > 0f) npc.getPiety().adjustBelief(riteFaith, drift);
        }
    }

    /** The stance between an attendee's faith and the officiating faith, resolved
     *  per-world (the NPC carries the server level). NEUTRAL when either is null or
     *  off-server (preserving today's behaviour). */
    private static RelationStance stanceFor(TownspersonMob npc, String attendeeFaith, String riteFaith) {
        if (attendeeFaith == null || riteFaith == null) return RelationStance.NEUTRAL;
        if (!(npc.level() instanceof ServerLevel level)) return RelationStance.NEUTRAL;
        return Relations.relation(level, attendeeFaith, riteFaith);
    }

    /** Drift multiplier by stance — NEUTRAL is 1.0 so today's numbers are unchanged;
     *  only KINDRED visibly differs this stage (the hostile arms need an override). */
    private static float driftMultiplier(RelationStance stance) {
        return switch (stance) {
            case KINDRED   -> 1.5f;   // shared gods → syncretism comes easily
            case NEUTRAL   -> 1.0f;   // baseline (unchanged)
            case RIVAL     -> 0.25f;  // resisted (override-only, later)
            case HERETICAL -> 0f;     // blocked (override-only, later)
        };
    }

    // ── Served / unserved ────────────────────────────────────────────────────

    /**
     * The served/unserved test (one place). Generalized in R3e-2: an NPC is
     * SERVED when the village has a religious building of THEIR faith with a
     * seated same-faith priest — a dominant-faith NPC by the temple/chapel, a
     * minority by their own shrine (R3e-2). Otherwise they are unserved and
     * fall back to solo private devotion. Atheists (no primary faith) are not
     * "unserved" — they have nothing to privately practice.
     */
    public static boolean isUnservedLocally(ServerLevel level, Village village, TownspersonMob npc) {
        if (village == null) return false;
        String mine = npc.getPiety().primaryReligion().orElse(null);
        if (mine == null) return false;                       // no faith → nothing to practice
        return !BuildingFaith.hasSeatedPriestOfFaith(level, village, mine);
    }
}
