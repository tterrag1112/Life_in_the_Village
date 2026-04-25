package tterrag1112.life_in_the_village.Npc.Events;

/**
 * Categorisation of an incoming gift relative to the receiver. Maps to
 * the spec's gift table in {@code 10-phase1-integration.md} (Trade and
 * economic, lines ~56–59). Producers branch on this to pick the
 * memory's initial value and mood trigger.
 */
public enum GiftAppropriateness {
    /** Receiver's favourite category — strongest reaction. */
    FAVORITE,
    /** Sensible / neutral gift (default). */
    APPROPRIATE,
    /** Off-base — contextually wrong for the receiver. */
    OFF_BASE,
    /** Insulting — actively negative; produces an insult-like response. */
    INSULTING
}
