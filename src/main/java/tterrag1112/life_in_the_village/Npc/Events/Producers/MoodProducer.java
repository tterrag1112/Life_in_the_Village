package tterrag1112.life_in_the_village.Npc.Events.Producers;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Events.EventDispatcher;
import tterrag1112.life_in_the_village.Npc.Events.GiftAppropriateness;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;
import tterrag1112.life_in_the_village.Npc.Events.RelationshipType;
import tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger;

/**
 * Phase 1 dispatcher that translates {@link NpcLifeEvent}s into mood
 * adjustments on the subject's
 * {@link tterrag1112.life_in_the_village.Npc.Mood.NpcMoodState}. Trigger
 * choice follows the spec's table in
 * {@code 10-phase1-integration.md} ("Event source inventory" Mood
 * column); per-trigger trait modulation is owned by
 * {@code MoodTrigger.traitMultiplier}, so this dispatcher just picks the
 * right trigger and lets the underlying state apply it.
 */
public final class MoodProducer implements EventDispatcher {

    @Override public String name() { return "mood"; }

    @Override
    public void onEvent(NpcLifeEvent event) {
        switch (event) {
            // ── Combat / rescue ────────────────────────────────────────────
            case NpcLifeEvent.Attacked e        -> apply(e.subject(), MoodTrigger.CRIME_VICTIM);
            case NpcLifeEvent.Rescued e         -> apply(e.subject(), MoodTrigger.RESCUED);
            case NpcLifeEvent.SavedSomeone e    -> apply(e.subject(), MoodTrigger.RESCUED_SOMEONE);

            case NpcLifeEvent.WitnessedDeath e -> apply(e.subject(),
                    switch (e.relation()) {
                        case KIN, SPOUSE -> MoodTrigger.FAMILY_DEATH;
                        case CLOSE_FRIEND -> MoodTrigger.CLOSE_FRIEND_DEATH;
                        case RIVAL -> MoodTrigger.RIVAL_DEATH;
                        default -> MoodTrigger.CLOSE_FRIEND_DEATH; // neutral default
                    });

            case NpcLifeEvent.SurvivedBattle ignored -> { /* mood unchanged */ }

            // ── Trade / economic ───────────────────────────────────────────
            case NpcLifeEvent.Trade ignored -> { /* spec mood column: "—" */ }

            case NpcLifeEvent.GiftReceived e -> {
                switch (e.appropriateness()) {
                    case FAVORITE -> apply(e.subject(), MoodTrigger.GIFT_FAVORITE);
                    case APPROPRIATE -> apply(e.subject(), MoodTrigger.GIFT_RECEIVED);
                    case OFF_BASE -> applyWithMagnitude(e.subject(),
                            MoodTrigger.GIFT_RECEIVED,
                            MoodTrigger.GIFT_RECEIVED.defaultMagnitude() / 2);
                    case INSULTING -> apply(e.subject(), MoodTrigger.INSULT_RECEIVED);
                }
            }

            case NpcLifeEvent.GaveGift e ->
                    // Spec: "(small positive, +3)"
                    applyWithMagnitude(e.subject(), MoodTrigger.COMPLIMENT_RECEIVED, 3);

            case NpcLifeEvent.Hired e -> {
                if (e.firstTime()) apply(e.subject(), MoodTrigger.HIRED);
            }
            case NpcLifeEvent.Fired e -> apply(e.subject(), MoodTrigger.FIRED_FROM_JOB);
            case NpcLifeEvent.Promoted e -> apply(e.subject(), MoodTrigger.PROMOTION);
            case NpcLifeEvent.Demoted e -> apply(e.subject(), MoodTrigger.DEMOTION);

            // ── Social ─────────────────────────────────────────────────────
            case NpcLifeEvent.Insulted e        -> apply(e.subject(), MoodTrigger.INSULT_RECEIVED);
            case NpcLifeEvent.Complimented e    -> apply(e.subject(), MoodTrigger.COMPLIMENT_RECEIVED);

            // Spec: "+15 flat" — use COMPLIMENT_RECEIVED type with magnitude override.
            case NpcLifeEvent.Defended e ->
                    applyWithMagnitude(e.subject(), MoodTrigger.COMPLIMENT_RECEIVED, 15);

            case NpcLifeEvent.SharedHardship ignored -> { /* spec mood column: "—" */ }
            case NpcLifeEvent.SharedFestival e ->
                    apply(e.subject(), MoodTrigger.FESTIVAL_ATTENDED);

            // Spec for taught/learned: "small positive". Use a +3 nudge.
            case NpcLifeEvent.TaughtSkill e ->
                    applyWithMagnitude(e.subject(), MoodTrigger.COMPLIMENT_RECEIVED, 3);
            case NpcLifeEvent.LearnedSkill e ->
                    applyWithMagnitude(e.subject(), MoodTrigger.COMPLIMENT_RECEIVED, 3);

            // ── Family / household ─────────────────────────────────────────
            case NpcLifeEvent.Married e         -> apply(e.subject(), MoodTrigger.MARRIAGE);
            case NpcLifeEvent.BirthInFamily e   -> apply(e.subject(), MoodTrigger.BIRTH_IN_FAMILY);

            case NpcLifeEvent.FamilyDeath e -> {
                // Spec: child death is heavier (×1.5). Use a magnitude override.
                if (isChildRelation(e.relation())) {
                    int mag = Math.round(MoodTrigger.FAMILY_DEATH.defaultMagnitude() * 1.5f);
                    applyWithMagnitude(e.subject(), MoodTrigger.FAMILY_DEATH, mag);
                } else {
                    apply(e.subject(), MoodTrigger.FAMILY_DEATH);
                }
            }

            case NpcLifeEvent.LifeStageAdvanced ignored -> { /* mood unchanged */ }

            // ── Goals ──────────────────────────────────────────────────────
            case NpcLifeEvent.GoalCompleted e -> {
                MoodTrigger trigger = MoodTrigger.GOAL_COMPLETED;
                if (e.importance() >= 7) {
                    // Spec: "GOAL_COMPLETED heavy" for importance ≥ 7.
                    int mag = Math.round(trigger.defaultMagnitude() * 1.5f);
                    applyWithMagnitude(e.subject(), trigger, mag);
                } else {
                    // Proportional to importance (1..6).
                    int mag = Math.max(1, Math.round(
                            trigger.defaultMagnitude() * (e.importance() / 7f)));
                    applyWithMagnitude(e.subject(), trigger, mag);
                }
            }

            case NpcLifeEvent.GoalFailed e -> apply(e.subject(), MoodTrigger.GOAL_FAILED);

            case NpcLifeEvent.GoalAbandoned e ->
                    // Spec: "small negative (−10 flat)".
                    applyWithMagnitude(e.subject(), MoodTrigger.GOAL_FAILED, -10);
        }
    }

    private static boolean isChildRelation(
            tterrag1112.life_in_the_village.Entities.FamilyRole relation) {
        return relation == tterrag1112.life_in_the_village.Entities.FamilyRole.CHILD;
    }

    // ── Producer API (mirrors the spec section "Producer API") ─────────────

    public static void apply(TownspersonMob npc, MoodTrigger trigger) {
        if (npc == null) return;
        if (!(npc.level() instanceof net.minecraft.server.level.ServerLevel sl)) return;
        npc.getMood().apply(trigger, npc.getTraitVector(), sl.getGameTime());
    }

    /**
     * Apply a trigger with an explicit base magnitude (still
     * trait-modulated). Used for spec rows that prescribe a flat
     * "+15" / "×1.5" override on top of the standard trigger.
     */
    public static void applyWithMagnitude(TownspersonMob npc, MoodTrigger trigger,
                                          int overrideMagnitude) {
        if (npc == null) return;
        if (!(npc.level() instanceof net.minecraft.server.level.ServerLevel sl)) return;
        float mult = trigger.traitMultiplier(npc.getTraitVector());
        int rawMag = Math.round(overrideMagnitude * mult);
        npc.getMood().applyWithRawMagnitude(trigger, rawMag, sl.getGameTime());
    }
}
