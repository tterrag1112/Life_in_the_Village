package tterrag1112.life_in_the_village.Npc.Schedule;

import com.mojang.serialization.Codec;

/**
 * Subdivided phase enum for Phase 2's
 * {@code 13-weekly-schedule.md}. Replaces the legacy 5-value
 * {@code WorkSchedule.DayPhase} (WAKE_UP / WORK / MEAL / SOCIAL /
 * HOME). Existing {@code isWorkTime()} callers are covered by
 * {@link #isWork} which returns true for all WORK_* phases.
 */
public enum DayPhase {
    /** Just woke up, leaving the house. */
    WAKE_UP,
    /** Travelling to workplace; ~200-tick buffer before work. */
    COMMUTE,
    /** Main morning production hours. */
    WORK_PRIMARY,
    /** Short away-from-workplace task (buy supplies, deliver). */
    WORK_ERRAND,
    /** Midday meal break. */
    MEAL,
    /** Afternoon production. */
    WORK_SECONDARY,
    /** Shopping for household or profession. */
    MARKET_RUN,
    /** Talking at town square / inn. */
    SOCIAL,
    /** Hobby time / day-off activity. */
    LEISURE,
    /** Returning home, preparing for night. */
    HOME_PREP,
    /** Sleeping. */
    HOME;

    /** True for WORK_PRIMARY / WORK_ERRAND / WORK_SECONDARY. */
    public boolean isWork() {
        return this == WORK_PRIMARY || this == WORK_ERRAND || this == WORK_SECONDARY;
    }

    public static final Codec<DayPhase> CODEC =
            Codec.STRING.xmap(DayPhase::valueOf, DayPhase::name);
}
