package tterrag1112.life_in_the_village.Kingdom.Events;

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
}
