package tterrag1112.life_in_the_village.Npc.Career;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.LifeStage;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipContract;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.Optional;

/**
 * Phase 6.3.3.a — central facade for NPC career transitions.
 *
 * <p>Three observation surfaces:
 * <ul>
 *   <li>{@link #changeProfession} — gated profession-change entry. Resolves
 *       listeners via {@link CareerListenerRegistry}, returns the first
 *       {@link ProfessionChangeResult.Rejected} encountered, otherwise
 *       delegates to {@link TownspersonMob#setProfession} (the leaf
 *       setter that still does the actual entityData / goal-registry /
 *       starter-pouch / guild-bootstrap / appearance-rebuild work).</li>
 *   <li>{@link #fireLifeStageTransition} — pure-observation broadcast on
 *       lifestage change. Listeners can react but not veto.</li>
 *   <li>{@link #fireApprenticeshipGraduation} — pure-observation
 *       broadcast after a graduation has completed.</li>
 * </ul>
 *
 * <p>{@link #changeProfessionForced} bypasses listeners — reserved for
 * debug, save migration, and worldgen entity construction paths where
 * the profession is part of identity rather than a career step.
 *
 * <h3>Listener registration</h3>
 * <p>Listeners are registered at mod init by content packs / culture
 * rules / system modules. The registry is in-memory only; nothing
 * persists. See {@link CareerListenerRegistry}.
 *
 * <h3>Foundation only</h3>
 * <p>Phase 6.3.3.a ships infrastructure; no built-in listeners are
 * registered. Behavioral impact today is zero — every prior
 * {@code setProfession} call reaches the same leaf with identical
 * effects via either the gated or forced path.
 */
public final class CareerTransitions {

    private static final Logger LOGGER = LogUtils.getLogger();

    private CareerTransitions() {}

    // ── Profession changes ─────────────────────────────────────────────────

    /**
     * Gated entry point for profession changes. Resolves listeners for
     * the target profession (specific + ANY_PROFESSION wildcard) in
     * registration order; the first {@code Rejected} short-circuits and
     * is returned. If all listeners accept (or defer), delegates to
     * {@link TownspersonMob#setProfession}.
     */
    public static ProfessionChangeResult changeProfession(TownspersonMob npc,
                                                          Profession to,
                                                          ProfessionChangeRequest.Reason reason,
                                                          ProfessionChangeRequest.Source source) {
        if (npc == null || to == null) return ProfessionChangeResult.Accepted.INSTANCE;
        Profession from = npc.getProfession();
        if (from == to) return ProfessionChangeResult.Accepted.INSTANCE; // no-op
        ProfessionChangeRequest request = new ProfessionChangeRequest(npc, from, to, reason, source);

        for (ProfessionChangeListener listener : CareerListenerRegistry.resolveProfession(to)) {
            ProfessionChangeResult result;
            try {
                result = listener.onProfessionChangeRequested(request);
            } catch (RuntimeException e) {
                LOGGER.warn("[CareerTransitions] listener threw on {} → {}: {}",
                        from, to, e.toString());
                continue;
            }
            if (result == null) continue;
            if (!result.shouldProceed()) return result;
        }

        npc.setProfession(to);
        return ProfessionChangeResult.Accepted.INSTANCE;
    }

    /**
     * Forced profession change — bypasses every listener. Reserved for
     * debug / save-migration / worldgen entity construction. Use
     * {@link #changeProfession} from ordinary system / player / content
     * paths.
     */
    public static void changeProfessionForced(TownspersonMob npc,
                                              Profession to,
                                              ProfessionChangeRequest.Reason reason,
                                              ProfessionChangeRequest.Source source) {
        if (npc == null || to == null) return;
        npc.setProfession(to);
    }

    // ── Lifestage broadcasts ───────────────────────────────────────────────

    /**
     * Notifies registered {@link LifeStageTransitionListener}s. Additive
     * to the existing {@code NpcLifeEvent.LifeStageAdvanced} event-bus
     * signal — the bus continues to drive its existing producers and
     * dispatchers untouched.
     */
    public static void fireLifeStageTransition(TownspersonMob npc, LifeStage from, LifeStage to) {
        if (npc == null || to == null) return;
        for (LifeStageTransitionListener listener : CareerListenerRegistry.resolveLifeStage(to)) {
            try {
                listener.onLifeStageTransition(npc, from, to);
            } catch (RuntimeException e) {
                LOGGER.warn("[CareerTransitions] lifestage listener threw on {} → {}: {}",
                        from, to, e.toString());
            }
        }
    }

    // ── Apprenticeship graduation broadcast ────────────────────────────────

    /**
     * Notifies registered {@link ApprenticeshipGraduationListener}s.
     * Called from {@link tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipManager#graduate
     * graduate()} after the contract has been marked complete and the
     * role projections cleared.
     */
    public static void fireApprenticeshipGraduation(ApprenticeshipContract contract,
                                                    TownspersonMob apprentice,
                                                    Optional<TownspersonMob> master) {
        if (contract == null || apprentice == null) return;
        for (ApprenticeshipGraduationListener listener : CareerListenerRegistry.resolveGraduation()) {
            try {
                listener.onApprenticeshipGraduation(contract, apprentice, master);
            } catch (RuntimeException e) {
                LOGGER.warn("[CareerTransitions] graduation listener threw: {}", e.toString());
            }
        }
    }
}
