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
    /** The player made an offering to a faith (the proving kind). */
    OFFERING
}
