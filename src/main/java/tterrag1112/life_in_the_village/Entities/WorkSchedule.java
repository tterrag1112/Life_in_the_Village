package tterrag1112.life_in_the_village.Entities;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Schedule.DayPhase;
import tterrag1112.life_in_the_village.Npc.Schedule.ScheduleResolver;
import tterrag1112.life_in_the_village.Npc.Schedule.TimeWindow;
import tterrag1112.life_in_the_village.Npc.Schedule.WeeklyScheduleLibrary;
import tterrag1112.life_in_the_village.Profession.Profession;

/**
 * Phase 2 thin façade over {@link ScheduleResolver}. The legacy
 * presets (EARLY_RISER, MERCHANT_SCHEDULE, etc.) and the legacy
 * {@code DayPhase} / {@code DailySchedule} / {@code TimeWindow}
 * inner types have moved into the
 * {@code tterrag1112.life_in_the_village.Npc.Schedule} package per
 * {@code 13-weekly-schedule.md}.
 *
 * <p>This class survives only as a backward-compatible call surface
 * for existing goals that use {@code WorkSchedule.isWorkTime(mob)}
 * etc. New code should call {@link ScheduleResolver} directly.</p>
 *
 * @deprecated Use {@link ScheduleResolver}. Kept during the migration
 *             window so existing goals don't all need to be touched
 *             in this session.
 */
@Deprecated
public final class WorkSchedule {

    private WorkSchedule() {}

    // ── Phase predicates ───────────────────────────────────────────────────

    public static boolean isWorkTime(TownspersonMob mob) {
        return ScheduleResolver.isWorkTime(mob, mob.level().getDayTime());
    }

    public static boolean isMealTime(TownspersonMob mob) {
        return ScheduleResolver.isMealTime(mob, mob.level().getDayTime());
    }

    public static boolean isSleepTime(TownspersonMob mob) {
        return ScheduleResolver.isSleepTime(mob, mob.level().getDayTime());
    }

    public static boolean isSocialTime(TownspersonMob mob) {
        return ScheduleResolver.isSocialTime(mob, mob.level().getDayTime());
    }

    /**
     * Guards never "should be home" — they patrol through the
     * traditional sleep window. For everyone else, sleep-time means
     * head home. Preserved verbatim from the legacy implementation.
     */
    public static boolean shouldBeHome(TownspersonMob mob) {
        if (mob.getProfession() == Profession.GUARD) return false;
        return isSleepTime(mob);
    }

    public static DayPhase getCurrentPhase(TownspersonMob mob) {
        return ScheduleResolver.phaseAt(mob, mob.level().getDayTime());
    }

    // ── Legacy single-window getters (kept for the Phase 0 trade UI etc.) ──

    /**
     * Combined work-window in 24000-tick day units. Spans the
     * profession's primary + secondary work, ignoring meal in between.
     * Prefer {@link ScheduleResolver#isWorkTime} for new code.
     */
    @Deprecated
    public static TimeWindow getWorkWindow(Profession profession) {
        var daily = WeeklyScheduleLibrary.forProfession(profession).getForDay(0L);
        return new TimeWindow(daily.workPrimary().startTick(),
                daily.workSecondary().endTick());
    }

    @Deprecated
    public static TimeWindow getSleepWindow(Profession profession) {
        return WeeklyScheduleLibrary.forProfession(profession).getForDay(0L).home();
    }

    @Deprecated
    public static TimeWindow getSocialWindow(Profession profession) {
        return WeeklyScheduleLibrary.forProfession(profession).getForDay(0L).social();
    }
}
