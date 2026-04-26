package tterrag1112.life_in_the_village.Npc.Schedule;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One day's worth of phase windows. Spec
 * {@code 13-weekly-schedule.md} (section "DailySchedule") — extended
 * with {@code commute / workErrand / workSecondary / marketRun /
 * leisure / homePrep} on top of the legacy
 * {@code wakeUp / work / meal / social / home}.
 *
 * <p>Phase resolution priority is the field declaration order, with
 * one exception: {@link DayPhase#HOME} is the catch-all when no other
 * window matches.</p>
 */
public record DailySchedule(
        TimeWindow wakeUp,
        TimeWindow commute,
        TimeWindow workPrimary,
        TimeWindow workErrand,
        TimeWindow meal,
        TimeWindow workSecondary,
        TimeWindow marketRun,
        TimeWindow social,
        TimeWindow leisure,
        TimeWindow homePrep,
        TimeWindow home
) {

    /** Returns the phase whose window contains {@code dayTime}, or HOME by default. */
    public DayPhase phaseAt(long dayTime) {
        if (wakeUp.isActive(dayTime))         return DayPhase.WAKE_UP;
        if (commute.isActive(dayTime))        return DayPhase.COMMUTE;
        if (workPrimary.isActive(dayTime))    return DayPhase.WORK_PRIMARY;
        if (workErrand.isActive(dayTime))     return DayPhase.WORK_ERRAND;
        if (meal.isActive(dayTime))           return DayPhase.MEAL;
        if (workSecondary.isActive(dayTime))  return DayPhase.WORK_SECONDARY;
        if (marketRun.isActive(dayTime))      return DayPhase.MARKET_RUN;
        if (social.isActive(dayTime))         return DayPhase.SOCIAL;
        if (leisure.isActive(dayTime))        return DayPhase.LEISURE;
        if (homePrep.isActive(dayTime))       return DayPhase.HOME_PREP;
        if (home.isActive(dayTime))           return DayPhase.HOME;
        return DayPhase.HOME;
    }

    /**
     * Returns a copy with every {@code WORK_*} window collapsed into
     * the same time-range under {@link DayPhase#LEISURE}. Used by
     * {@link ScheduleResolver} on day-off days (spec line 191).
     */
    public DailySchedule asDayOff() {
        // Merge WORK_PRIMARY + MEAL + WORK_SECONDARY into a single
        // leisure span so the NPC behaves smoothly across the workday.
        // Keep marketRun / social / home as-is.
        TimeWindow leisureSpan = mergeWorkSpan();
        return new DailySchedule(
                wakeUp,
                commute.isEmpty() ? TimeWindow.EMPTY : TimeWindow.EMPTY,
                TimeWindow.EMPTY,            // workPrimary collapsed
                TimeWindow.EMPTY,            // workErrand collapsed
                meal,                        // keep meal so NPC eats
                TimeWindow.EMPTY,            // workSecondary collapsed
                marketRun,                   // errands continue per spec line 192
                social,
                leisureSpan,                 // covers the freed work span
                homePrep,
                home);
    }

    /** Computes the smallest single window covering all WORK_* slots. */
    private TimeWindow mergeWorkSpan() {
        int start = -1, end = -1;
        for (TimeWindow w : new TimeWindow[]{workPrimary, workErrand, workSecondary}) {
            if (w.isEmpty()) continue;
            if (start < 0 || w.startTick() < start) start = w.startTick();
            if (end < 0 || w.endTick() > end) end = w.endTick();
        }
        if (start < 0) return leisure;
        return new TimeWindow(start, end);
    }

    /** Returns a copy with every window shifted by {@code deltaTicks}. */
    public DailySchedule shifted(int deltaTicks) {
        return new DailySchedule(
                wakeUp.shifted(deltaTicks),
                commute.shifted(deltaTicks),
                workPrimary.shifted(deltaTicks),
                workErrand.shifted(deltaTicks),
                meal.shifted(deltaTicks),
                workSecondary.shifted(deltaTicks),
                marketRun.shifted(deltaTicks),
                social.shifted(deltaTicks),
                leisure.shifted(deltaTicks),
                homePrep.shifted(deltaTicks),
                home.shifted(deltaTicks));
    }

    public static final Codec<DailySchedule> CODEC = RecordCodecBuilder.create(i -> i.group(
            TimeWindow.CODEC.fieldOf("wakeUp").forGetter(DailySchedule::wakeUp),
            TimeWindow.CODEC.optionalFieldOf("commute", TimeWindow.EMPTY).forGetter(DailySchedule::commute),
            TimeWindow.CODEC.fieldOf("workPrimary").forGetter(DailySchedule::workPrimary),
            TimeWindow.CODEC.optionalFieldOf("workErrand", TimeWindow.EMPTY).forGetter(DailySchedule::workErrand),
            TimeWindow.CODEC.fieldOf("meal").forGetter(DailySchedule::meal),
            TimeWindow.CODEC.fieldOf("workSecondary").forGetter(DailySchedule::workSecondary),
            TimeWindow.CODEC.optionalFieldOf("marketRun", TimeWindow.EMPTY).forGetter(DailySchedule::marketRun),
            TimeWindow.CODEC.fieldOf("social").forGetter(DailySchedule::social),
            TimeWindow.CODEC.optionalFieldOf("leisure", TimeWindow.EMPTY).forGetter(DailySchedule::leisure),
            TimeWindow.CODEC.optionalFieldOf("homePrep", TimeWindow.EMPTY).forGetter(DailySchedule::homePrep),
            TimeWindow.CODEC.fieldOf("home").forGetter(DailySchedule::home)
    ).apply(i, DailySchedule::new));

    /**
     * Convenience factory mirroring the legacy 6-field shape: the
     * existing {@code morningWork} maps to {@code workPrimary}, the
     * existing {@code afternoonWork} maps to {@code workSecondary},
     * and the new fields default to {@link TimeWindow#EMPTY}.
     */
    public static DailySchedule legacy(TimeWindow wakeUp, TimeWindow morningWork,
                                       TimeWindow meal, TimeWindow afternoonWork,
                                       TimeWindow social, TimeWindow home) {
        return new DailySchedule(
                wakeUp,
                TimeWindow.EMPTY,
                morningWork,
                TimeWindow.EMPTY,
                meal,
                afternoonWork,
                TimeWindow.EMPTY,
                social,
                TimeWindow.EMPTY,
                TimeWindow.EMPTY,
                home);
    }
}
