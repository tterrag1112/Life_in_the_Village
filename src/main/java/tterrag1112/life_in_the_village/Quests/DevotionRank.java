package tterrag1112.life_in_the_village.Quests;

/**
 * F2a-3 — the player's <b>devotion rank</b> with a quest giver: the derived tier of the
 * giver-standing (count of quests completed for that giver). The spine of the player
 * religious career, distinct from favour/piety and the SR1 living-saint status (your
 * history with a god is the quests you've done for them). Rank-gated perks are a future
 * enrichment; this is the rank + its surfacing only.
 */
public enum DevotionRank {
    SUPPLICANT(0), DEVOTEE(1), DISCIPLE(3), CHAMPION(6);

    private final int atLeast;

    DevotionRank(int atLeast) { this.atLeast = atLeast; }

    /** The rank for a completed-quest {@code count} (the highest threshold met). */
    public static DevotionRank fromCount(int count) {
        DevotionRank rank = SUPPLICANT;
        for (DevotionRank r : values()) if (count >= r.atLeast) rank = r;
        return rank;
    }

    public String displayName() {
        return switch (this) {
            case SUPPLICANT -> "Supplicant";
            case DEVOTEE    -> "Devotee";
            case DISCIPLE   -> "Disciple";
            case CHAMPION   -> "Champion";
        };
    }
}
