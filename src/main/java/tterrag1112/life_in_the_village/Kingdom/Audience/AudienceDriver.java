package tterrag1112.life_in_the_village.Kingdom.Audience;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Kingdom.Charters.Charter;
import tterrag1112.life_in_the_village.Kingdom.Charters.GranteeRef;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.Treaties.Treaty;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.Optional;
import java.util.UUID;

/**
 * Track D3.5A — server-side resolver for audience-chamber
 * petitions.
 *
 * <p>Per the user-confirmed design "kingdom-side standing
 * authoritative": petition outcomes adjust the petitioner's
 * standing on the resolving kingdom. Standing affects no other
 * kingdom directly (foreign kingdoms learn through events).
 *
 * <h3>Standing deltas</h3>
 * <ul>
 *   <li>Approve: +5 (capped at {@link PlayerStanding#MAX_SCORE}).
 *       Approving a CHARTER_REQUEST gives +10 instead since the
 *       grant has more weight.</li>
 *   <li>Deny: −3. Denying a WAR_DECLARATION or BREAK_TREATY is
 *       −1 (the petition isn't a personal slight; it's a
 *       diplomatic ask).</li>
 *   <li>Expire: 0 (no penalty; the player just moved on).</li>
 *   <li>Withdraw: 0.</li>
 * </ul>
 *
 * <h3>Auto-resolution</h3>
 * Petitions from players whose standing is at
 * {@link PlayerStanding#TRUSTED_THRESHOLD} or above auto-approve
 * AUDIENCE_GRIEVANCE on submit; petitions from
 * {@link PlayerStanding#HOSTILE_THRESHOLD} or below auto-deny.
 * Other kinds always require explicit ruler resolution (or NPC
 * ruler decision).
 */
public final class AudienceDriver {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int APPROVE_DELTA = 5;
    public static final int APPROVE_CHARTER_DELTA = 10;
    public static final int DENY_DELTA = -3;
    public static final int DENY_DIPLOMATIC_DELTA = -1;

    private AudienceDriver() {}

    /**
     * Result of a resolution attempt. Lets callers (debug commands,
     * packet handlers) surface success / failure without re-reading
     * the petition list.
     */
    public record ResolutionResult(boolean applied, String reason,
                                   int standingDelta, int newStanding) {
        public static ResolutionResult denyApply(String reason) {
            return new ResolutionResult(false, reason, 0, 0);
        }
    }

    /**
     * Submits a petition. Validates capability (where applicable),
     * appends to the kingdom's queue, fires
     * {@link KingdomEvent.PetitionSubmitted}. Auto-resolves trusted
     * / hostile AUDIENCE_GRIEVANCE petitions immediately.
     */
    public static UUID submit(ServerLevel level, VillageSavedData data,
                              Kingdom kingdom, UUID playerUuid,
                              PetitionPayload payload, long tick) {
        // Track D3.5D — per-petitioner rate limit. Trusted halved,
        // hostile doubled, neutral baseline. Returning null UUID
        // signals "rate-limited"; callers (debug commands, packet
        // handlers) surface the cooldown to the player.
        PlayerStanding ratePs = kingdom.getPlayerStanding(playerUuid, tick);
        if (!ratePs.canSubmitAt(tick)) {
            return null;
        }
        // Stamp the submit tick on the standing record.
        kingdom.stampPlayerSubmit(playerUuid, tick);

        UUID petitionId = UUID.randomUUID();
        Petition petition = Petition.freshSubmission(
                petitionId, playerUuid, kingdom.getId(), payload, tick);
        kingdom.submitPetition(petition);

        KingdomEventBus.fire(new KingdomEvent.PetitionSubmitted(
                kingdom.getId(), petitionId, playerUuid,
                payload.kind().name(), tick));

        // Auto-resolution path for grievances by trust band.
        if (payload.kind() == PetitionKind.AUDIENCE_GRIEVANCE) {
            PlayerStanding ps = kingdom.getPlayerStanding(playerUuid, tick);
            if (ps.isTrusted()) {
                approve(level, data, kingdom, petitionId, /*by*/ null, tick,
                        "auto-approved (trusted)");
            } else if (ps.isHostile()) {
                deny(level, data, kingdom, petitionId, /*by*/ null, tick,
                        "auto-denied (hostile standing)");
            }
        }

        return petitionId;
    }

    /**
     * Approves a petition. Applies kind-specific effects, then
     * adjusts standing + fires events.
     */
    public static ResolutionResult approve(ServerLevel level, VillageSavedData data,
                                           Kingdom kingdom, UUID petitionId,
                                           UUID resolvedBy, long tick, String note) {
        Petition p = kingdom.findPetition(petitionId).orElse(null);
        if (p == null) return ResolutionResult.denyApply("no such petition");
        if (!p.isPending()) return ResolutionResult.denyApply("already resolved");

        // Apply kind-specific effects.
        String effectsNote = applyApproval(level, data, kingdom, p, tick);
        if (effectsNote.startsWith("FAIL:")) {
            return ResolutionResult.denyApply(effectsNote);
        }

        int delta = (p.kind() == PetitionKind.CHARTER_REQUEST)
                ? APPROVE_CHARTER_DELTA : APPROVE_DELTA;
        PlayerStanding ps = kingdom.adjustPlayerStanding(p.playerUuid(), delta, tick);

        kingdom.replacePetition(p.asApproved(resolvedBy, tick,
                note == null ? effectsNote : note + "; " + effectsNote));

        KingdomEventBus.fire(new KingdomEvent.PetitionResolved(
                kingdom.getId(), petitionId, p.playerUuid(),
                p.kind().name(), "APPROVED", delta, tick));
        KingdomEventBus.fire(new KingdomEvent.StandingChanged(
                kingdom.getId(), p.playerUuid(), delta, ps.score(), tick));
        KingdomNewsfeed.append(kingdom, "petition.approved",
                "Approved " + p.kind().name() + ": " + effectsNote, tick);

        LOGGER.info("[AudienceDriver] {} approved {} from {} (+{} standing → {})",
                kingdom.getName(), p.kind(), p.playerUuid(), delta, ps.score());
        return new ResolutionResult(true, "approved: " + effectsNote, delta, ps.score());
    }

    /**
     * Denies a petition. Standing dip (smaller for diplomatic
     * petitions); no effects applied.
     */
    public static ResolutionResult deny(ServerLevel level, VillageSavedData data,
                                        Kingdom kingdom, UUID petitionId,
                                        UUID resolvedBy, long tick, String note) {
        Petition p = kingdom.findPetition(petitionId).orElse(null);
        if (p == null) return ResolutionResult.denyApply("no such petition");
        if (!p.isPending()) return ResolutionResult.denyApply("already resolved");

        int delta = (p.kind() == PetitionKind.WAR_DECLARATION
                || p.kind() == PetitionKind.BREAK_TREATY)
                ? DENY_DIPLOMATIC_DELTA : DENY_DELTA;
        PlayerStanding ps = kingdom.adjustPlayerStanding(p.playerUuid(), delta, tick);

        kingdom.replacePetition(p.asDenied(resolvedBy, tick,
                note == null ? "denied" : note));

        KingdomEventBus.fire(new KingdomEvent.PetitionResolved(
                kingdom.getId(), petitionId, p.playerUuid(),
                p.kind().name(), "DENIED", delta, tick));
        KingdomEventBus.fire(new KingdomEvent.StandingChanged(
                kingdom.getId(), p.playerUuid(), delta, ps.score(), tick));
        KingdomNewsfeed.append(kingdom, "petition.denied",
                "Denied " + p.kind().name(), tick);

        LOGGER.info("[AudienceDriver] {} denied {} from {} ({} standing → {})",
                kingdom.getName(), p.kind(), p.playerUuid(), delta, ps.score());
        return new ResolutionResult(true, "denied", delta, ps.score());
    }

    /** Petitioner-initiated withdrawal. No standing change. */
    public static ResolutionResult withdraw(Kingdom kingdom, UUID petitionId,
                                            UUID requesterPlayerUuid, long tick) {
        Petition p = kingdom.findPetition(petitionId).orElse(null);
        if (p == null) return ResolutionResult.denyApply("no such petition");
        if (!p.isPending()) return ResolutionResult.denyApply("already resolved");
        if (!p.playerUuid().equals(requesterPlayerUuid)) {
            return ResolutionResult.denyApply("only the petitioner can withdraw");
        }
        kingdom.replacePetition(p.asWithdrawn(tick));
        KingdomEventBus.fire(new KingdomEvent.PetitionResolved(
                kingdom.getId(), petitionId, p.playerUuid(),
                p.kind().name(), "WITHDRAWN", 0, tick));
        return new ResolutionResult(true, "withdrawn", 0,
                kingdom.getPlayerStanding(requesterPlayerUuid, tick).score());
    }

    /**
     * Applies the kind-specific effect of approval. Returns a
     * one-line summary; "FAIL:..." prefix means the effect couldn't
     * land and the caller should treat it as a denial.
     */
    private static String applyApproval(ServerLevel level, VillageSavedData data,
                                        Kingdom kingdom, Petition p, long tick) {
        switch (p.payload()) {
            case PetitionPayload.TreatyRatification tr -> {
                Treaty existing = kingdom.findTreaty(tr.treatyId()).orElse(null);
                if (existing == null) return "FAIL: no treaty " + tr.treatyId();
                kingdom.ratifyTreaty(tr.treatyId(), tick);
                // Mirror the ratification on every other party.
                for (UUID partyId : existing.parties()) {
                    if (partyId.equals(kingdom.getId())) continue;
                    data.getKingdomById(partyId).ifPresent(other ->
                            other.ratifyTreaty(tr.treatyId(), tick));
                }
                if (existing.parties().stream().allMatch(pid ->
                        data.getKingdomById(pid)
                                .flatMap(kk -> kk.findTreaty(tr.treatyId()))
                                .map(t -> t.ratifiedTicks().containsKey(pid))
                                .orElse(false))) {
                    KingdomEventBus.fire(new KingdomEvent.TreatyRatified(
                            tr.treatyId(), existing.type().name(),
                            existing.parties(), existing.parties(), tick));
                }
                return "treaty ratified";
            }
            case PetitionPayload.CharterRequest cr -> {
                GranteeRef.GranteeKind kind;
                try { kind = GranteeRef.GranteeKind.valueOf(cr.granteeKindName()); }
                catch (Exception e) { return "FAIL: bad granteeKind " + cr.granteeKindName(); }
                GranteeRef grantee = new GranteeRef(kind, cr.granteeId());
                Charter charter = kingdom.grantCharter(cr.name(), grantee, cr.params(),
                        Optional.empty(), tick);
                KingdomEventBus.fire(new KingdomEvent.CharterGranted(
                        kingdom.getId(), charter.id(), cr.params().type().name(),
                        cr.granteeId(), kind.name(), tick));

                // Track D3.5C — titled-grants flow. PLAYER grantees
                // with TITLE_GRANT or LAND_GRANT params get a
                // PlayerNobility record on the granting kingdom.
                if (kind == GranteeRef.GranteeKind.PLAYER) {
                    UUID playerUuid = cr.granteeId();
                    switch (cr.params()) {
                        case tterrag1112.life_in_the_village.Kingdom.Charters
                                .CharterParams.TitleGrant tg -> {
                            kingdom.ennoblePlayer(playerUuid, tg.rankIndex(),
                                    charter.id(), tick);
                            KingdomEventBus.fire(new KingdomEvent.PlayerEnnobled(
                                    kingdom.getId(), playerUuid, tg.rankIndex(),
                                    charter.id(), tick));
                        }
                        case tterrag1112.life_in_the_village.Kingdom.Charters
                                .CharterParams.LandGrant lg -> {
                            kingdom.grantPlayerLand(playerUuid, lg.blockX(),
                                    lg.blockZ(), lg.sizeCells(), tick);
                            KingdomEventBus.fire(new KingdomEvent.PlayerLandGranted(
                                    kingdom.getId(), playerUuid, lg.blockX(),
                                    lg.blockZ(), lg.sizeCells(), charter.id(), tick));
                        }
                        default -> {
                            // Other charter types to PLAYER grantees
                            // (TOLL_RIGHTS / TAX_EXEMPTION /
                            // MARKET_MONOPOLY / ORDINATION_RIGHTS) get
                            // the charter record but no nobility shim.
                        }
                    }
                }
                return "charter " + cr.params().type().name() + " granted";
            }
            case PetitionPayload.AudienceGrievance ag -> {
                // Narrative only — the standing boost IS the effect.
                return "audience granted";
            }
            case PetitionPayload.WarDeclaration wd -> {
                if (kingdom.isVassal()) return "FAIL: vassal cannot declare war";
                Kingdom target = data.getKingdomById(wd.targetKingdomId()).orElse(null);
                if (target == null) return "FAIL: no target kingdom";
                kingdom.setRelation(target.getId(),
                        tterrag1112.life_in_the_village.Kingdom.DiplomaticRelation.WAR);
                target.setRelation(kingdom.getId(),
                        tterrag1112.life_in_the_village.Kingdom.DiplomaticRelation.WAR);
                return "war declared on " + target.getName();
            }
            case PetitionPayload.RebellionThreat rt -> {
                // REBELLION_THREAT does not use approve/deny — the
                // three-way resolution is handled via
                // KingdomPetitionPacket.RESOLVE_NEGOTIATE/CRUSH/ACCEPT.
                // Reaching this case means the standard approve path
                // was called on a rebellion threat (shouldn't happen
                // via GUI); fall through with a benign note.
                return "rebellion threat (use the three-way resolve actions)";
            }
            case PetitionPayload.VoluntaryUnion vu -> {
                UUID surviving = tterrag1112.life_in_the_village.Kingdom.Merger
                        .MergerEngine.approveVoluntaryUnion(level, data,
                                kingdom, vu.petitioningKingdomId(), tick);
                if (surviving == null) {
                    return "FAIL: cannot merge (active war or merged-away kingdom missing)";
                }
                return "voluntary union approved";
            }
            case PetitionPayload.BreakTreaty bt -> {
                Treaty t = kingdom.findTreaty(bt.treatyId()).orElse(null);
                if (t == null) return "FAIL: no treaty";
                String reason = bt.reason().isEmpty() ? "ruler decision" : bt.reason();
                for (UUID partyId : t.parties()) {
                    data.getKingdomById(partyId).ifPresent(party ->
                            party.breakTreaty(bt.treatyId(), kingdom.getId(), tick, reason));
                }
                KingdomEventBus.fire(new KingdomEvent.TreatyBroken(
                        bt.treatyId(), t.type().name(), kingdom.getId(), reason, tick));
                return "treaty broken";
            }
        }
    }
}
