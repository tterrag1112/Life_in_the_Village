package tterrag1112.life_in_the_village.Npc.Religion;

/**
 * Religion Deepening D1 — the controlled vocabulary a faith's <b>virtues</b> and
 * <b>taboos</b> are tagged with, so D2 can judge NPC actions against them (a
 * concept the behaviour system keys on, paired with authored display text in
 * {@link ReligionIdentity}).
 *
 * <p>Deliberately small + concrete: every value is anchored to one of the four
 * faiths' actual values (no speculative concepts). D2 is the forward consumer —
 * it maps an observable NPC act (working / idling, honest dealing / theft,
 * sharing / hoarding, defending kin / fleeing, ancestor veneration, desecration,
 * …) to a concept and approves/disapproves per the officiating faith's lists.</p>
 */
public enum FaithConcept {

    // ── Virtue-leaning ───────────────────────────────────────────────────────
    HONEST_LABOUR("Honest labour"),          // Sunstead — diligent work
    GENEROSITY("Generosity"),                // Sunstead — sharing the harvest / alms
    TRUTHFULNESS("Truthfulness"),            // The Loom — speak truly to the cloth
    HARMONY("Harmony"),                      // The Loom — keep the pattern whole
    RESPECT_THE_SEA("Respect for the sea"),  // Tidecall — humility before the deep
    REMEMBRANCE("Remembrance"),              // Tidecall — salt remembers the lost
    HONOUR_THE_ANCESTORS("Honour the ancestors"), // Forge Creed — venerate the line
    LOYALTY("Loyalty"),                      // Forge Creed — stand for those behind you
    VALOUR("Valour"),                        // Forge Creed — courage in defence

    // ── Taboo-leaning ────────────────────────────────────────────────────────
    IDLENESS("Idleness"),                    // Sunstead — shirking the day's work
    GREED("Greed"),                          // Sunstead — hoarding what should be shared
    DECEIT("Deceit"),                        // The Loom — lying, cheating, theft
    DISCORD("Discord"),                      // The Loom — sowing conflict
    RECKLESSNESS("Recklessness"),            // Tidecall — defying the sea / heedless danger
    COWARDICE("Cowardice"),                  // Forge Creed — abandoning kin / fleeing
    SACRILEGE("Sacrilege");                  // universal — desecrating the sacred

    private final String displayName;

    FaithConcept(String displayName) { this.displayName = displayName; }

    public String displayName() { return displayName; }
}
