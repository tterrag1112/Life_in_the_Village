package tterrag1112.life_in_the_village.Kingdom.Events;

import java.util.List;
import java.util.UUID;

/**
 * Track D1 (Phase 0) — sealed taxonomy of events fired through the
 * {@link KingdomEventBus}. Mirrors the {@code NpcLifeEvent} pattern
 * (sealed interface + record subtypes) so future kingdom-tier
 * subsystems can register dispatchers via the same idiom NPC code
 * uses.
 *
 * <h3>D1 scope</h3>
 * The bus is live but has no subscribers in this phase. The taxonomy
 * is wired up so D2 / D3 can post events without re-shaping the bus.
 * Eight initial event types cover founding, dissolution, succession,
 * office filling / vacating, membership changes, and scalar shifts
 * (stability + legitimacy folded into one event). Diplomatic events
 * (treaties, war, vassalage) land in D3 once relations are interactive.
 */
public sealed interface KingdomEvent {

    /** A new kingdom record is registered. */
    record KingdomFounded(UUID kingdomId, String culture, long tick)
            implements KingdomEvent {}

    /** A kingdom is being removed (e.g., last village leaves, or admin-deleted). */
    record KingdomDissolved(UUID kingdomId, long tick)
            implements KingdomEvent {}

    /**
     * Office holder changed for the {@code kingdom_king} office.
     * D3 expands this to fire for all kingdom offices on transition.
     */
    record RulerSucceeded(UUID kingdomId, UUID predecessorHolderId,
                          UUID successorHolderId, long tick)
            implements KingdomEvent {}

    /**
     * A kingdom office was filled. {@code officeId} is the registry
     * key (e.g. {@code "kingdom_chancellor"}).
     */
    record OfficeFilled(UUID kingdomId, String officeId, UUID holderId, long tick)
            implements KingdomEvent {}

    /** A kingdom office was vacated (resignation, death, removal). */
    record OfficeVacated(UUID kingdomId, String officeId, UUID priorHolderId, long tick)
            implements KingdomEvent {}

    /** A village joined the kingdom. */
    record VillageJoined(UUID kingdomId, UUID villageId, long tick)
            implements KingdomEvent {}

    /** A village left the kingdom. */
    record VillageLeft(UUID kingdomId, UUID villageId, long tick)
            implements KingdomEvent {}

    /**
     * A kingdom-tier scalar (stability or legitimacy) crossed a band
     * boundary or shifted by a notable delta. {@code which} is the
     * scalar name, {@code priorBand} / {@code newBand} are the
     * {@link tterrag1112.life_in_the_village.Kingdom.Kingdom.ScalarBand}
     * names. D1 doesn't fire this; D3 wires the driver loops.
     */
    record ScalarShifted(UUID kingdomId, String which,
                         String priorBand, String newBand, long tick)
            implements KingdomEvent {}

    // ── Track D3.4 — law lifecycle events ──────────────────────────────────

    /** A law transitioned to {@code DRAFT} state (Scholar started drafting). */
    record LawDrafted(UUID kingdomId, String lawId,
                      UUID drafterUuid, long tick) implements KingdomEvent {}

    /** A law transitioned to {@code PROPOSED} state (committed for ratification). */
    record LawProposed(UUID kingdomId, String lawId,
                       UUID proposerUuid, long tick) implements KingdomEvent {}

    /** A law transitioned to {@code ACTIVE} state (effects live). */
    record LawEnacted(UUID kingdomId, String lawId,
                      UUID enactorUuid, long tick) implements KingdomEvent {}

    /**
     * A law was repealed (any state → removed). {@code priorState}
     * = "DRAFT" / "PROPOSED" / "ACTIVE" lets subscribers
     * differentiate "cancel a draft" from "tear down active law".
     */
    record LawRepealed(UUID kingdomId, String lawId,
                       String priorState, UUID actorUuid, long tick)
            implements KingdomEvent {}

    // ── Track D3.4b — charter / treaty / intrigue events ──────────────────

    /** A charter was issued. {@code type} matches CharterType.name(). */
    record CharterGranted(UUID kingdomId, UUID charterId, String type,
                          UUID granteeId, String granteeKind, long tick)
            implements KingdomEvent {}

    /**
     * A charter was revoked. Subscribers can read the legitimacy /
     * stability cost off the kingdom's modifier list (tagged with
     * {@code charter.revocation.<TYPE>}).
     */
    record CharterRevoked(UUID kingdomId, UUID charterId, String type,
                          UUID granteeId, long ageInDays, long tick)
            implements KingdomEvent {}

    /** A treaty was drafted by a Scholar. Awaits ratification. */
    record TreatyDrafted(UUID treatyId, String type,
                         List<UUID> parties, UUID drafterUuid, long tick)
            implements KingdomEvent {}

    /**
     * A treaty was ratified by all parties. {@code ratifiers} is a
     * snapshot of the per-party ruler ids that signed.
     */
    record TreatyRatified(UUID treatyId, String type,
                          List<UUID> parties, List<UUID> ratifiers, long tick)
            implements KingdomEvent {}

    /**
     * A treaty was broken by {@code breakingPartyId}. {@code reason}
     * is a one-line tag ("declared war on ally", "unilateral",
     * "vassalage rebellion"). Cascade-break sets reason to
     * "cascade.<original_reason>" so subscribers can chain.
     */
    record TreatyBroken(UUID treatyId, String type, UUID breakingPartyId,
                        String reason, long tick) implements KingdomEvent {}

    /**
     * Spymaster launched a sow-discontent attempt. The outcome is
     * stochastic but seeded by {@code (kingdomId, "intrigue",
     * targetKingdomId, gameDay)} per the user-confirmed
     * determinism rule.
     */
    record IntrigueLaunched(UUID kingdomId, UUID targetKingdomId,
                            UUID targetProvinceId, UUID spymasterUuid,
                            boolean success, long tick) implements KingdomEvent {}

    /**
     * A target kingdom's counter-intelligence discovered an
     * incoming intrigue attempt. Subscribers can surface the
     * discovery in the target's audience-loop feed (Phase 5).
     */
    record IntrigueDiscovered(UUID targetKingdomId, UUID sourceKingdomId,
                              UUID targetProvinceId, long tick)
            implements KingdomEvent {}

    // ── Track D3.5A — audience-loop + standing events ────────────────────

    /** A player submitted a petition. {@code kind} = PetitionKind.name(). */
    record PetitionSubmitted(UUID kingdomId, UUID petitionId, UUID playerUuid,
                             String kind, long tick) implements KingdomEvent {}

    /**
     * A petition was resolved. {@code outcome} =
     * "APPROVED" / "DENIED" / "EXPIRED" / "WITHDRAWN".
     * {@code standingDelta} is the standing change applied to the
     * petitioner; 0 for EXPIRED / WITHDRAWN.
     */
    record PetitionResolved(UUID kingdomId, UUID petitionId, UUID playerUuid,
                            String kind, String outcome, int standingDelta,
                            long tick) implements KingdomEvent {}

    /**
     * A player's standing with a kingdom changed. Subscribers can
     * surface trust-band transitions ({@code crossedTrusted} /
     * {@code crossedHostile}) by comparing
     * {@code newScore - delta} against the thresholds.
     */
    record StandingChanged(UUID kingdomId, UUID playerUuid,
                           int delta, int newScore, long tick)
            implements KingdomEvent {}

    // ── Track D3.5C — titled-grants events ────────────────────────────────

    /**
     * A player was ennobled by the kingdom via an approved
     * TITLE_GRANT charter. {@code rankIndex} is in the kingdom
     * culture's rank table (0 = lowest noble).
     */
    record PlayerEnnobled(UUID kingdomId, UUID playerUuid, int rankIndex,
                          UUID titleCharterId, long tick) implements KingdomEvent {}

    /**
     * A LAND_GRANT charter was approved for a player; manor
     * coordinates attached to the kingdom-side
     * {@code PlayerNobility} record.
     */
    record PlayerLandGranted(UUID kingdomId, UUID playerUuid,
                             int blockX, int blockZ, int sizeCells,
                             UUID landCharterId, long tick)
            implements KingdomEvent {}

    /**
     * A newsfeed entry — emitted by
     * {@code KingdomNewsfeed.append} so subscribers can mirror
     * the kingdom's recent-events log without parsing every
     * per-type event subscription. {@code summary} is a one-line
     * human-readable description.
     */
    record NewsfeedAppended(UUID kingdomId, String tag, String summary,
                            long tick) implements KingdomEvent {}

    // ── Track D3.6.1 — rebellion + secession events ─────────────────────

    /** Province stability crossed GRUMBLE threshold (newsfeed-only). */
    record RebellionGrumble(UUID kingdomId, UUID provinceId,
                            int stability, long tick) implements KingdomEvent {}

    /** Province stability crossed SECESSION_THREAT threshold; ruler must resolve. */
    record SecessionThreat(UUID kingdomId, UUID provinceId,
                           int stability, long tick) implements KingdomEvent {}

    /** Ruler negotiated peace via treasury cost + stability floor. */
    record SecessionThreatNegotiated(UUID kingdomId, UUID provinceId,
                                     long treasuryCost, long tick)
            implements KingdomEvent {}

    /** Ruler crushed the rebellion via legitimacy + stability cost. */
    record SecessionThreatCrushed(UUID kingdomId, UUID provinceId, long tick)
            implements KingdomEvent {}

    /**
     * Province seceded — new kingdom created; old kingdom's claim
     * shrinks; villages transfer.
     */
    record Secession(UUID parentKingdomId, UUID newKingdomId, UUID provinceId,
                     UUID governorUuid, String reason, long tick)
            implements KingdomEvent {}

    /** Vassal kingdom rebelled against overlord — VASSALAGE treaty broken. */
    record VassalRebelled(UUID overlordKingdomId, UUID vassalKingdomId,
                          UUID treatyId, String reason, long tick)
            implements KingdomEvent {}

    // ── Track D3.6.2 — kingdom collapse ─────────────────────────────────

    /** Kingdom collapsed; provinces shattered into successor kingdoms. */
    record KingdomCollapsed(UUID kingdomId, List<UUID> successorKingdomIds,
                            UUID rumpKingdomId, String reason, long tick)
            implements KingdomEvent {}

    // ── Track D3.6.3 — kingdom merger ───────────────────────────────────

    /**
     * Two kingdoms merged. {@code mergerPath} = "marriage_union" /
     * "voluntary_union" / "conquest". {@code mergedAwayId} is the
     * kingdom that no longer exists; {@code survivorId} retains
     * its UUID + capital.
     */
    record KingdomMerged(UUID survivorId, UUID mergedAwayId,
                         String mergerPath, long tick) implements KingdomEvent {}

    /**
     * A voluntary union petition was created — weak kingdom asks
     * a strong neighbour for absorption.
     */
    record UnionRequest(UUID petitioningKingdomId, UUID targetKingdomId,
                        UUID petitionId, long tick) implements KingdomEvent {}

    // ── Track D3.6.4 — war events ───────────────────────────────────────

    /**
     * War declared by attacker on defender. Casus belli stamped at
     * declaration time.
     */
    record WarDeclared(UUID attackerKingdomId, UUID defenderKingdomId,
                       UUID warId, String casusBelli, long tick)
            implements KingdomEvent {}

    /** A battle resolved. {@code outcome} = Battle.Outcome.name(). */
    record BattleResolved(UUID warId, UUID battleId, String outcome,
                          int attackerScoreDelta, long tick)
            implements KingdomEvent {}

    /**
     * War ended. {@code finalStatus} = War.Status.name() ∈
     * {WON, LOST, WHITE_PEACE, STALEMATE}.
     */
    record WarConcluded(UUID warId, UUID attackerKingdomId, UUID defenderKingdomId,
                        String finalStatus, String summary, long tick)
            implements KingdomEvent {}

    /**
     * One combatant offered peace via the audience loop. Petition
     * UUID lets newsfeed surface a "peace offered, awaiting reply"
     * note.
     */
    record PeaceTreatyOffered(UUID warId, UUID offeringKingdomId,
                              UUID receivingKingdomId, UUID petitionId, long tick)
            implements KingdomEvent {}
}

