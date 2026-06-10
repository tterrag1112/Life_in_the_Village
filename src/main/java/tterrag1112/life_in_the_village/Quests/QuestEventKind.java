package tterrag1112.life_in_the_village.Quests;

/**
 * F2a-1 — the unified quest-event vocabulary. When a player does something
 * quest-relevant, the source calls {@link QuestEvents#notify} with one of these kinds,
 * and every matching {@link Objective} on the player's active quests advances. This is
 * the ONE completion path that replaces the per-type hardcoded listeners.
 *
 * <p>F2a-1 ships only {@link #OFFERING} (the proving kind). F2a-2 adds the rest
 * (pilgrimage / relic / rite), and F2b folds in the work/guild vocabulary.</p>
 */
public enum QuestEventKind {
    /** The player made an offering to a faith (F2a-1 proving kind). */
    OFFERING,
    /** F2a-2 — the player prayed at a god's sacred site (pilgrimage). */
    VISIT_SITE,
    /** F2a-2 — the player enshrined a relic of a god's faith. */
    ENSHRINE,
    /** F2a-2 — the player attended or commissioned a rite of a faith. */
    RITE,
    /** F2b-1 — the player killed a mob (the guild Hunt objective; event-mode). */
    MOB_DEATH,
    /** F2b-1 — an escorted target reached its destination (event-mode; source F2b-2). */
    ESCORT_ARRIVED
}
