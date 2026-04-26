package tterrag1112.life_in_the_village.Npc.Events.Producers;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Entities.FamilyRole;
import tterrag1112.life_in_the_village.Npc.Events.EventDispatcher;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;
import tterrag1112.life_in_the_village.Npc.Events.RelationshipType;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Npc.Traits.TraitDriftLog;
import tterrag1112.life_in_the_village.Npc.Traits.TraitVector;

/**
 * Phase 1 dispatcher that drives slow, bounded trait drift from
 * accumulated life experience. Each application is small (per-event
 * delta &le; 0.05) and the {@link TraitDriftLog} caps cumulative drift
 * on any axis at &plusmn;0.40 per spec. Once-per-NPC effects use
 * lifetime keys so repeated firings of the same event (e.g. saving
 * someone) are idempotent on second-and-later occurrences.
 *
 * <p>Spec: {@code docs/npc_redesign/10-phase1-integration.md}, sections
 * "Combat and rescue" / "Trait drift" columns of every event table, and
 * "Trait drift rules".</p>
 */
public final class TraitDriftProducer implements EventDispatcher {

    @Override public String name() { return "trait_drift"; }

    @Override
    public void onEvent(NpcLifeEvent event) {
        switch (event) {
            // ── Combat / rescue ────────────────────────────────────────────
            case NpcLifeEvent.Rescued e ->
                    driftOnceInLifetime(e.subject(), TraitAxis.COMPASSION, +0.05f,
                            "rescued_by_someone");

            case NpcLifeEvent.SavedSomeone e ->
                    // Spec: "+0.03 Courage (once per rescue, max 3 times)".
                    // Use a counter via three lifetime keys.
                    driftSavedSomeone(e.subject());

            case NpcLifeEvent.WitnessedDeath e -> {
                if (e.relation() == RelationshipType.KIN
                        || e.relation() == RelationshipType.SPOUSE) {
                    drift(e.subject(), TraitAxis.COMPASSION, +0.02f);
                    drift(e.subject(), TraitAxis.SOCIABILITY, -0.02f);
                }
            }

            case NpcLifeEvent.SurvivedBattle e ->
                    driftSurvivedBattle(e.subject());

            // ── Trade / economic ───────────────────────────────────────────
            case NpcLifeEvent.GoalFailed e ->
                    // Spec: "−0.02 Industry (if repeated)" for missed
                    // commissions; here we route every GoalFailed through
                    // the same axis. Bounded by the lifetime cap.
                    drift(e.subject(), TraitAxis.INDUSTRY, -0.02f);

            case NpcLifeEvent.Fired e ->
                    drift(e.subject(), TraitAxis.AMBITION, -0.03f);

            case NpcLifeEvent.Promoted e ->
                    drift(e.subject(), TraitAxis.AMBITION, +0.02f);

            case NpcLifeEvent.GoalCompleted e -> {
                // Spec: "+0.01 Ambition" for importance ≥ 7.
                if (e.goal().importance() >= 7) drift(e.subject(), TraitAxis.AMBITION, +0.01f);
            }

            // ── Social ─────────────────────────────────────────────────────
            case NpcLifeEvent.Defended e ->
                    // Spec: "+0.02 Compassion (rarely)". Apply the small
                    // drift; the lifetime cap naturally rate-limits.
                    drift(e.subject(), TraitAxis.COMPASSION, +0.02f);

            // ── Family ─────────────────────────────────────────────────────
            case NpcLifeEvent.Married e ->
                    drift(e.subject(), TraitAxis.SOCIABILITY, +0.02f);

            case NpcLifeEvent.BirthInFamily e ->
                    drift(e.subject(), TraitAxis.COMPASSION, +0.01f);

            case NpcLifeEvent.FamilyDeath e -> {
                if (e.relation() == FamilyRole.CHILD) {
                    drift(e.subject(), TraitAxis.COMPASSION, +0.03f);
                    drift(e.subject(), TraitAxis.SOCIABILITY, -0.03f);
                } else if (e.relation() == FamilyRole.SPOUSE) {
                    drift(e.subject(), TraitAxis.COMPASSION, +0.02f);
                    drift(e.subject(), TraitAxis.SOCIABILITY, -0.02f);
                } else {
                    // HEAD / ELDERLY / UNASSIGNED — treat as parent / kin
                    drift(e.subject(), TraitAxis.COMPASSION, +0.02f);
                }
            }

            // No-drift events — explicit cases keep the switch exhaustive.
            case NpcLifeEvent.Attacked ignored -> {}
            case NpcLifeEvent.Trade ignored -> {}
            case NpcLifeEvent.GiftReceived ignored -> {}
            case NpcLifeEvent.GaveGift ignored -> {}
            case NpcLifeEvent.Hired ignored -> {}
            case NpcLifeEvent.Demoted ignored -> {}
            case NpcLifeEvent.Insulted ignored -> {}
            case NpcLifeEvent.Complimented ignored -> {}
            case NpcLifeEvent.SharedHardship ignored -> {}
            case NpcLifeEvent.SharedFestival ignored -> {}
            case NpcLifeEvent.TaughtSkill ignored -> {}
            case NpcLifeEvent.LearnedSkill ignored -> {}
            case NpcLifeEvent.LifeStageAdvanced ignored -> {}
            case NpcLifeEvent.GoalAbandoned ignored -> {}

            // Relationship boundaries don't drive trait drift directly —
            // memory + mood handle the moment.
            case NpcLifeEvent.RelationshipBoundaryCrossed ignored -> {}

            // Letters and books don't drive trait drift in v1.
            // (Phase 5 polish could nudge LITERACY-adjacent traits on
            // sustained reading; out of scope here.)
            case NpcLifeEvent.LetterReceived ignored -> {}
        }
    }

    // ── Lifetime-key helpers for "first time" / "max N times" rules ────────

    /**
     * Spec: "+0.05 Courage (first time), +0.01 (subsequent)".
     * Lifetime key {@code battle_first} dedupes the first-time bonus;
     * subsequent battles fall through to the small drift.
     */
    private static void driftSurvivedBattle(TownspersonMob npc) {
        TraitDriftLog log = npc.getTraitDrift();
        if (!log.hasFired("battle_first")) {
            if (driftAndRecord(npc, TraitAxis.COURAGE, +0.05f)) {
                log.markFired("battle_first");
            }
        } else {
            drift(npc, TraitAxis.COURAGE, +0.01f);
        }
    }

    /**
     * Spec: "+0.03 Courage (once per rescue, max 3 times)". Three lifetime
     * keys cap the total at +0.09 across an NPC's life.
     */
    private static void driftSavedSomeone(TownspersonMob npc) {
        TraitDriftLog log = npc.getTraitDrift();
        for (int i = 1; i <= 3; i++) {
            String key = "saved_someone_" + i;
            if (!log.hasFired(key)) {
                if (driftAndRecord(npc, TraitAxis.COURAGE, +0.03f)) {
                    log.markFired(key);
                }
                return;
            }
        }
        // After 3 firings, no further drift from saving (spec cap).
    }

    // ── Producer API (mirrors the spec section "Producer API") ─────────────

    /**
     * Applies a clamped drift to {@code axis} on {@code npc}. No-op if
     * the lifetime cap is reached or the NPC is unloaded.
     */
    public static void drift(TownspersonMob npc, TraitAxis axis, float delta) {
        driftAndRecord(npc, axis, delta);
    }

    /**
     * Applies drift only if {@code lifetimeKey} hasn't fired yet on this
     * NPC, then marks it fired.
     */
    public static void driftOnceInLifetime(TownspersonMob npc, TraitAxis axis,
                                           float delta, String lifetimeKey) {
        if (npc == null || lifetimeKey == null) return;
        TraitDriftLog log = npc.getTraitDrift();
        if (log.hasFired(lifetimeKey)) return;
        if (driftAndRecord(npc, axis, delta)) {
            log.markFired(lifetimeKey);
        }
    }

    /**
     * Common path: clamp, write to TraitVector, write to TraitDriftLog.
     * Returns {@code true} if any drift was actually applied.
     */
    static boolean driftAndRecord(TownspersonMob npc, TraitAxis axis, float delta) {
        if (npc == null || axis == null) return false;
        if (!(npc.level() instanceof net.minecraft.server.level.ServerLevel sl)) return false;
        TraitDriftLog log = npc.getTraitDrift();
        float clamped = log.clampToCap(axis, delta);
        if (clamped == 0f) return false;
        TraitVector vec = npc.getTraitVector();
        vec.adjust(axis, clamped);
        log.recordDrift(axis, clamped, sl.getGameTime());
        return true;
    }
}
