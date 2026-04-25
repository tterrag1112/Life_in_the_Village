package tterrag1112.life_in_the_village.Npc.Events;

/**
 * Coarse relationship category used by event payloads such as
 * {@link NpcLifeEvent.WitnessedDeath#relation} to distinguish kin-loss
 * from friend-loss from rival-loss. Phase 2's full
 * {@code 11-npc-relationship-ledger.md} ledger will own a richer enum
 * (with strengths and modes); this placeholder lets Phase 1 dispatchers
 * branch correctly without that doc shipping first.
 */
public enum RelationshipType {
    KIN,
    SPOUSE,
    CLOSE_FRIEND,
    FRIEND,
    RIVAL,
    NEUTRAL,
    STRANGER
}
