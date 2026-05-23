package tterrag1112.life_in_the_village.Npc.Office;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.GuildData;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus;
import tterrag1112.life_in_the_village.Npc.Office.Selection.OfficeSelectionContext;
import tterrag1112.life_in_the_village.Npc.Office.Selection.OfficeSelectionEngine;
import tterrag1112.life_in_the_village.Npc.Office.Selection.SelectionEngines;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Driver that runs office elections, processes term-end vacancies, and
 * notifies the rest of the system when holders change.
 *
 * <p>Spec: {@code 06-office-framework.md} → "Selection methods", "Term
 * end detection", and "Office holder change events".</p>
 *
 * <h3>Cycle</h3>
 * <ol>
 *   <li>Daily: walk every org's {@link OfficeState}; for any vacant
 *   office, attempt to fill via the spec'd selection method. Term-ended
 *   offices are vacated first.</li>
 *   <li>On NPC death / migration: {@link #vacateAllHeldBy} clears every
 *   office the NPC held, then schedules the daily pass (or the caller
 *   re-runs immediately).</li>
 *   <li>Player-driven: appointment / resignation paths reuse
 *   {@link #seatPlayer} / {@link #seatNpc} directly, bypassing the
 *   selection engines.</li>
 * </ol>
 *
 * <p>Phase 3 explicit non-goal: elections do NOT fire as scheduled
 * "events" in this session. Phase 5 (events expanded) may schedule a
 * formal election event UI; v1 silently re-fills on the daily tick.</p>
 */
public final class OfficeElection {

    private static final Logger LOGGER = LogUtils.getLogger();

    private OfficeElection() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Forces an election for one office on one org. Used by village
     * creation, debug commands, and the holder-vacated lifecycle hook.
     *
     * @return the resulting holding (which may be vacant if no
     *         eligible candidate exists)
     */
    public static OfficeHolding runElection(OrgType orgType, UUID orgId,
                                            String officeId, ServerLevel level) {
        OfficeDefinition def = OfficeRegistry.get(officeId);
        if (def == null) {
            LOGGER.warn("[OfficeElection] Unknown office id '{}'", officeId);
            return OfficeHolding.vacant(officeId, orgId, SelectionMethod.MERITOCRATIC);
        }
        VillageSavedData vdata = VillageSavedData.get(level);
        OfficeState state = stateOf(orgType, orgId, level, vdata);
        if (state == null) {
            LOGGER.debug("[OfficeElection] No org for {}:{} — skipping election", orgType, orgId);
            return OfficeHolding.vacant(officeId, orgId, def.defaultSelection());
        }

        SelectionMethod method = resolveMethod(orgType, orgId, def, level, vdata);
        OfficeSelectionEngine engine = SelectionEngines.forMethod(method);
        OfficeSelectionContext ctx = new OfficeSelectionContext(level, vdata,
                orgType, orgId, state, def, level.getGameTime());

        Optional<OfficeHolding> chosen = engine.select(ctx);
        // Phase 6.3.2.d — eligibility predicate consultation. If the
        // engine chose an NPC candidate, ask the registered predicate
        // whether the culture/content rules permit the seating. Failure
        // is treated the same as engine-declined: office stays in its
        // current state for this cycle.
        if (chosen.isPresent()) {
            chosen = applyPredicate(chosen, orgType, orgId, def, method, level, vdata);
        }
        if (chosen.isEmpty()) {
            // Engine declined — leave the office in its current state
            // (vacant if it was vacant). Persist the actualSelection so
            // the next pass uses the resolved method, not the default.
            OfficeHolding existing = state.get(officeId).orElse(null);
            if (existing != null && existing.isVacant() && existing.actualSelection() != method) {
                state.set(officeId, OfficeHolding.vacant(officeId, orgId, method));
                markDirty(orgType, orgId, level, vdata);
            }
            return existing != null ? existing : OfficeHolding.vacant(officeId, orgId, method);
        }

        OfficeHolding previous = state.get(officeId).orElse(null);
        OfficeHolding result = chosen.get();
        state.set(officeId, result);
        markDirty(orgType, orgId, level, vdata);
        emitChangeEvents(level, orgType, orgId, def, previous, result);
        // Track D3.3b — ennoble commoner appointees on kingdom seats.
        result.holderNpcId().ifPresent(npcId ->
                tterrag1112.life_in_the_village.Npc.Nobility.OfficeAppointmentEnnoblement
                        .onSeat(orgType, orgId, officeId, npcId, level));
        return result;
    }

    /**
     * Runs elections for every {@link SelectionMethod} on the org that
     * doesn't require explicit appointment. Used at village founding so
     * VILLAGE_LEADER, VILLAGE_PRIEST, etc. populate immediately while
     * APPOINTED slots wait for the leader to seat.
     */
    public static void runFoundingElections(OrgType orgType, UUID orgId, ServerLevel level) {
        for (OfficeDefinition def : OfficeRegistry.forOrgType(orgType)) {
            // APPOINTED slots wait for an appointer; running them here
            // would just be a no-op since the appointer also isn't seated
            // at founding. The driver picks them up on the daily sweep.
            if (def.defaultSelection() == SelectionMethod.APPOINTED) continue;
            runElection(orgType, orgId, def.id(), level);
        }
        // After leaders are seated, give APPOINTED slots one immediate
        // pass so the village isn't down a constable / treasurer for an
        // entire in-game day after founding.
        for (OfficeDefinition def : OfficeRegistry.forOrgType(orgType)) {
            if (def.defaultSelection() != SelectionMethod.APPOINTED) continue;
            runElection(orgType, orgId, def.id(), level);
        }
    }

    /** Daily sweep: term-end + vacancy fill across every loaded org. */
    public static void dailyTick(ServerLevel level) {
        VillageSavedData vdata = VillageSavedData.get(level);
        long now = level.getGameTime();

        // Process newly-founded villages first (drained queue), then the
        // routine vacancy / term-end sweep handles everything that
        // remained vacant — those two passes together satisfy "elections
        // populate per default selection methods" at founding plus the
        // ongoing "term-end vacates and re-runs election" rules.
        for (UUID villageId : vdata.drainPendingFoundingElections()) {
            if (vdata.getVillageById(villageId).isPresent()) {
                runFoundingElections(OrgType.VILLAGE, villageId, level);
            }
        }

        for (Village v : vdata.getAllVillages()) {
            sweepState(v.getOffices(), OrgType.VILLAGE, v.getId(), level, now);
        }
        for (GuildData g : vdata.getAllGuilds()) {
            sweepState(g.offices(), OrgType.GUILD, g.guildId(), level, now);
        }
        for (Kingdom k : vdata.getAllKingdoms()) {
            sweepState(k.getOffices(), OrgType.KINGDOM, k.getId(), level, now);
        }
        BusinessSavedData cdata = BusinessSavedData.get(level);
        for (Business c : cdata.getAllBusinesses()) {
            sweepState(c.getOffices(), OrgType.BUSINESS, c.getBusinessId(), level, now);
        }
    }

    private static void sweepState(OfficeState state, OrgType orgType, UUID orgId,
                                    ServerLevel level, long now) {
        if (state == null) return;
        // Snapshot so vacate-and-reseat doesn't ConcurrentModification.
        List<String> officeIds = new ArrayList<>(state.allOffices());
        for (String officeId : officeIds) {
            OfficeHolding h = state.get(officeId).orElse(null);
            if (h == null) continue;
            // Term expired → vacate, then attempt to refill.
            if (!h.isVacant() && h.termEndTick() > 0L && h.termEndTick() <= now) {
                state.vacate(officeId);
                emitVacate(level, orgType, orgId, officeId, h, "term_ended");
            }
            // Refill if vacant (either previously or just now).
            if (state.isVacant(officeId)) {
                runElection(orgType, orgId, officeId, level);
            }
        }
    }

    // ── Player-driven seats ─────────────────────────────────────────────────

    /**
     * Replaces the office's current holder with {@code playerId}, used by
     * the appointment verb. Fires {@link NpcLifeEvent.Promoted} if the
     * incoming holder is an NPC (player promotions don't fire NPC events).
     *
     * <p>Phase 6.3.2.d — player seating bypasses the eligibility
     * predicate. Player-driven appointment is the sovereign action; if
     * a culture rule wants to block a player appointment it must do so
     * at the verb / dialogue layer.
     */
    public static void seatPlayer(OrgType orgType, UUID orgId, String officeId,
                                  UUID playerId, ServerLevel level) {
        OfficeDefinition def = OfficeRegistry.get(officeId);
        if (def == null) return;
        VillageSavedData vdata = VillageSavedData.get(level);
        OfficeState state = stateOf(orgType, orgId, level, vdata);
        if (state == null) return;
        long now = level.getGameTime();
        long termEnd = def.isIndefiniteTerm() ? 0L : now + (long) def.termDays() * 24000L;
        OfficeHolding previous = state.get(officeId).orElse(null);
        OfficeHolding holding = OfficeHolding.heldByPlayer(officeId, orgId, playerId,
                now, termEnd, def.defaultSelection());
        state.set(officeId, holding);
        markDirty(orgType, orgId, level, vdata);
        emitChangeEvents(level, orgType, orgId, def, previous, holding);
    }

    /**
     * Gated NPC seating path — consults the
     * {@link tterrag1112.life_in_the_village.Npc.Office.OfficeEligibilityPredicate
     * eligibility predicate} registered for {@code (culture, officeId)}.
     * Returns true if the seating took effect, false when the predicate
     * rejected the candidate (office unchanged).
     *
     * @see #seatNpcForced for the admin/debug bypass.
     */
    public static boolean seatNpc(OrgType orgType, UUID orgId, String officeId,
                                  UUID npcId, SelectionMethod recordedAs,
                                  ServerLevel level) {
        return seatNpcInternal(orgType, orgId, officeId, npcId, recordedAs, level, false);
    }

    /**
     * Forced NPC seating — skips the eligibility predicate. Reserved
     * for debug commands, save migration, and other administrative
     * paths that must succeed.
     */
    public static void seatNpcForced(OrgType orgType, UUID orgId, String officeId,
                                     UUID npcId, SelectionMethod recordedAs,
                                     ServerLevel level) {
        seatNpcInternal(orgType, orgId, officeId, npcId, recordedAs, level, true);
    }

    private static boolean seatNpcInternal(OrgType orgType, UUID orgId, String officeId,
                                           UUID npcId, SelectionMethod recordedAs,
                                           ServerLevel level, boolean force) {
        OfficeDefinition def = OfficeRegistry.get(officeId);
        if (def == null) return false;
        VillageSavedData vdata = VillageSavedData.get(level);
        OfficeState state = stateOf(orgType, orgId, level, vdata);
        if (state == null) return false;
        if (!force) {
            TownspersonMob candidate = TownspersonMob.findByUUID(level, npcId).orElse(null);
            if (candidate != null) {
                OfficeEligibilityPredicate predicate = OfficeEligibilityRegistry.resolve(
                        cultureOf(orgType, orgId, level, vdata), officeId);
                if (!predicate.qualifies(candidate, new OfficeContext(
                        def, orgType, orgId, recordedAs,
                        cultureOf(orgType, orgId, level, vdata), level, vdata))) {
                    return false;
                }
            }
        }
        long now = level.getGameTime();
        long termEnd = def.isIndefiniteTerm() ? 0L : now + (long) def.termDays() * 24000L;
        OfficeHolding previous = state.get(officeId).orElse(null);
        OfficeHolding holding = OfficeHolding.heldByNpc(officeId, orgId, npcId,
                now, termEnd, recordedAs);
        state.set(officeId, holding);
        markDirty(orgType, orgId, level, vdata);
        emitChangeEvents(level, orgType, orgId, def, previous, holding);
        // Track D3.3b — kingdom-tier office grants ennoblement to
        // commoner appointees. No-op for non-kingdom seats and for
        // already-ranked NPCs.
        tterrag1112.life_in_the_village.Npc.Nobility.OfficeAppointmentEnnoblement
                .onSeat(orgType, orgId, officeId, npcId, level);
        return true;
    }

    // ── Vacancy hooks (death / migration / resignation) ────────────────────

    /**
     * Vacates every office on every loaded org currently held by
     * {@code uuid}, then runs an immediate election to refill. Used by
     * {@link tterrag1112.life_in_the_village.Entities.custom.TownspersonMob}'s
     * death hook and the resign verb.
     */
    public static void vacateAllHeldBy(UUID uuid, ServerLevel level, String reason) {
        if (uuid == null) return;
        List<OfficeRegistry.Match> matches = OfficeRegistry.findOfficesHeldBy(uuid, level);
        if (matches.isEmpty()) return;
        VillageSavedData vdata = VillageSavedData.get(level);
        for (OfficeRegistry.Match m : matches) {
            OfficeState state = stateOf(m.orgType(), m.orgId(), level, vdata);
            if (state == null) continue;
            OfficeHolding previous = state.get(m.holding().officeId()).orElse(null);
            state.vacate(m.holding().officeId());
            markDirty(m.orgType(), m.orgId(), level, vdata);
            emitVacate(level, m.orgType(), m.orgId(), m.holding().officeId(), previous, reason);
            // Re-run immediately so the office isn't down for a full day.
            runElection(m.orgType(), m.orgId(), m.holding().officeId(), level);
        }
    }

    // =========================================================================
    // Internals
    // =========================================================================

    private static OfficeState stateOf(OrgType orgType, UUID orgId,
                                      ServerLevel level, VillageSavedData vdata) {
        return switch (orgType) {
            case VILLAGE -> vdata.getVillageById(orgId).map(Village::getOffices).orElse(null);
            case GUILD   -> vdata.getGuildById(orgId).map(GuildData::offices).orElse(null);
            case KINGDOM -> vdata.getKingdomById(orgId).map(Kingdom::getOffices).orElse(null);
            case COMPANY -> BusinessSavedData.get(level).getAllBusinesses().stream()
                    .filter(c -> c.getBusinessId().equals(orgId))
                    .findFirst().map(Business::getOffices).orElse(null);
            case TEMPLE  -> null; // stubbed in v1
        };
    }

    private static void markDirty(OrgType orgType, UUID orgId,
                                  ServerLevel level, VillageSavedData vdata) {
        switch (orgType) {
            case COMPANY -> BusinessSavedData.get(level).markDirty();
            default -> vdata.markDirty();
        }
    }

    /**
     * Resolution order matches the Phase 5 wiring contract: culture
     * override (currently always empty per stub) → office definition's
     * default. The lookup is split out so Phase 5 only needs to populate
     * {@link CultureSelectionResolver}.
     */
    private static SelectionMethod resolveMethod(OrgType orgType, UUID orgId,
                                                  OfficeDefinition def, ServerLevel level,
                                                  VillageSavedData vdata) {
        String culture = cultureOf(orgType, orgId, level, vdata);
        return CultureSelectionResolver.cultureSelectionFor(culture, def.id())
                .orElse(def.defaultSelection());
    }

    private static String cultureOf(OrgType orgType, UUID orgId, ServerLevel level,
                                    VillageSavedData vdata) {
        return switch (orgType) {
            case KINGDOM -> vdata.getKingdomById(orgId).map(Kingdom::getCulture).orElse(null);
            case VILLAGE -> vdata.getKingdomForVillage(orgId).map(Kingdom::getCulture).orElse(null);
            default -> null;
        };
    }

    /**
     * Phase 6.3.2.d — checks the registered
     * {@link OfficeEligibilityPredicate} against the engine's chosen
     * candidate. Player-held offices and vacant holdings pass through
     * unchanged (predicates only gate NPC seating). Returns
     * {@link Optional#empty()} when the predicate rejects, so the
     * caller can treat it as engine-declined.
     */
    private static Optional<OfficeHolding> applyPredicate(Optional<OfficeHolding> chosen,
                                                          OrgType orgType, UUID orgId,
                                                          OfficeDefinition def,
                                                          SelectionMethod method,
                                                          ServerLevel level,
                                                          VillageSavedData vdata) {
        if (chosen.isEmpty()) return chosen;
        OfficeHolding holding = chosen.get();
        if (!holding.holderIsNpc()) return chosen; // vacant or player-held — predicates only gate NPC seating
        UUID npcId = holding.holderNpcId().orElse(null);
        if (npcId == null) return chosen;
        TownspersonMob candidate = TownspersonMob.findByUUID(level, npcId).orElse(null);
        if (candidate == null) return chosen; // candidate offline; allow seating, predicate will re-check next cycle
        String cultureId = cultureOf(orgType, orgId, level, vdata);
        OfficeEligibilityPredicate predicate =
                OfficeEligibilityRegistry.resolve(cultureId, def.id());
        OfficeContext ctx = new OfficeContext(def, orgType, orgId, method, cultureId, level, vdata);
        return predicate.qualifies(candidate, ctx) ? chosen : Optional.empty();
    }

    // ── Event emission ─────────────────────────────────────────────────────

    private static void emitChangeEvents(ServerLevel level,
                                         OrgType orgType, UUID orgId,
                                         OfficeDefinition def,
                                         OfficeHolding previous, OfficeHolding next) {
        // Demoted: previous NPC holder loses the seat to a different one.
        if (previous != null && !previous.isVacant() && previous.holderIsNpc()) {
            UUID prevId = previous.holderUuid().orElseThrow();
            boolean stillHolding = next != null && next.isHeldBy(prevId);
            if (!stillHolding) {
                TownspersonMob.findByUUID(level, prevId).ifPresent(npc ->
                        NpcLifeEventBus.fire(new NpcLifeEvent.Demoted(npc, def.id())));
            }
        }
        // Promoted + OfficeChange: new NPC holder.
        if (next != null && !next.isVacant() && next.holderIsNpc()) {
            UUID nextId = next.holderUuid().orElseThrow();
            boolean wasHolding = previous != null && previous.isHeldBy(nextId);
            if (!wasHolding) {
                UUID prevUuid = (previous != null && !previous.isVacant())
                        ? previous.holderUuid().orElse(null)
                        : null;
                TownspersonMob.findByUUID(level, nextId).ifPresent(npc -> {
                    NpcLifeEventBus.fire(new NpcLifeEvent.Promoted(npc, def.id()));
                    // Phase 3 doc 06 — fire OfficeChange so downstream
                    // subscribers (history log, appearance rebuilder,
                    // EventLifeEventProducer inauguration scheduling)
                    // see the transition. Previously omitted, breaking
                    // each of those silently.
                    NpcLifeEventBus.fire(
                            new NpcLifeEvent.OfficeChange(npc, def.id(), prevUuid));
                });
            }
        }
    }

    private static void emitVacate(ServerLevel level, OrgType orgType, UUID orgId,
                                   String officeId, OfficeHolding previous, String reason) {
        if (previous == null || previous.isVacant() || !previous.holderIsNpc()) return;
        UUID id = previous.holderUuid().orElseThrow();
        TownspersonMob.findByUUID(level, id).ifPresent(npc ->
                NpcLifeEventBus.fire(new NpcLifeEvent.Demoted(npc, officeId + ":" + reason)));
    }
}
