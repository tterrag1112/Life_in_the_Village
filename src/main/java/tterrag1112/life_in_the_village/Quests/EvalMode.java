package tterrag1112.life_in_the_village.Quests;

/**
 * F2b-1 — an {@link Objective}'s evaluation mode (the one sanctioned engine addition
 * since F2a-1). An <b>EVENT</b> objective advances via {@link QuestEvents#notify} (live,
 * e.g. a kill count); a <b>POLL</b> objective is checked on demand via
 * {@link QuestEvents#evaluate} (e.g. at turn-in: holds N items, reached a biome). Both
 * funnel to the one completion routine — this is a general engine capability (any quest,
 * religious included, could use a poll objective), not a guild special-case.
 */
public enum EvalMode { EVENT, POLL }
