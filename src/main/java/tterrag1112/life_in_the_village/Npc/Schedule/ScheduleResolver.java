package tterrag1112.life_in_the_village.Npc.Schedule;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

/**
 * Layered phase lookup for an NPC at a given tick.
 *
 * <p>Resolution order per spec
 * {@code 13-weekly-schedule.md} (line 18):
 * {@code personalOverride > eventOverride > weeklyVariant >
 * professionDefault}. Phase 1's existing event-override surface
 * (setEventOverride / hasEventOverride on TownspersonMob) is consulted
 * mid-stack; if no event is forcing a phase, the resolver falls back
 * through weekly schedule + personal overrides.</p>
 *
 * <p>Day-off handling: when the resolved weekly schedule's day-off
 * set contains the current day-of-week, {@link DailySchedule#asDayOff}
 * collapses WORK_* windows into LEISURE before phase lookup
 * (spec line 191).</p>
 */
public final class ScheduleResolver {

    private ScheduleResolver() {}

    /**
     * Returns the active {@link DayPhase} for {@code npc} at
     * {@code gameTick}. Never throws — returns
     * {@link DayPhase#HOME} as a safe default if anything is missing.
     */
    public static DayPhase phaseAt(TownspersonMob npc, long gameTick) {
        if (npc == null) return DayPhase.HOME;
        try {
            DailySchedule schedule = resolveDaily(npc, gameTick);
            long dayTime = ((gameTick % 24000L) + 24000L) % 24000L;
            return schedule.phaseAt(dayTime);
        } catch (Throwable t) {
            return DayPhase.HOME;
        }
    }

    /** True if {@code gameTick} falls on the NPC's day-off. */
    public static boolean isDayOff(TownspersonMob npc, long gameTick) {
        if (npc == null) return false;
        WeeklySchedule weekly = weeklyFor(npc);
        int dow = WeeklySchedule.computeDayOfWeek(gameTick);
        PersonalScheduleOverride override = npc.getScheduleOverride();
        if (override != null) {
            if (override.overrideDayOffs().contains(dow)) return false;
            if (override.extraDayOffs().contains(dow)) return true;
        }
        return weekly.isDayOff(gameTick);
    }

    /**
     * Back-compat shim for {@code TownspersonMob.isWorkTime()}.
     * Returns {@code true} for any WORK_* phase. Day-off collapses
     * WORK_* into LEISURE so this method correctly returns
     * {@code false} during off-days.
     */
    public static boolean isWorkTime(TownspersonMob npc, long gameTick) {
        return phaseAt(npc, gameTick).isWork();
    }

    public static boolean isMealTime(TownspersonMob npc, long gameTick) {
        return phaseAt(npc, gameTick) == DayPhase.MEAL;
    }

    public static boolean isSocialTime(TownspersonMob npc, long gameTick) {
        return phaseAt(npc, gameTick) == DayPhase.SOCIAL;
    }

    public static boolean isSleepTime(TownspersonMob npc, long gameTick) {
        DayPhase p = phaseAt(npc, gameTick);
        return p == DayPhase.HOME || p == DayPhase.HOME_PREP;
    }

    public static boolean isLeisureTime(TownspersonMob npc, long gameTick) {
        return phaseAt(npc, gameTick) == DayPhase.LEISURE;
    }

    // ── Layered lookup ─────────────────────────────────────────────────────

    /**
     * Pulls the right {@link DailySchedule} for this NPC + tick:
     * starts with the profession-default weekly schedule (with shift
     * rotation if applicable), applies day-off collapse, applies the
     * NPC's personal phase shifts. Event overrides — when present —
     * take precedence and are baked into the schedule before personal
     * overrides apply.
     */
    private static DailySchedule resolveDaily(TownspersonMob npc, long gameTick) {
        WeeklySchedule weekly = weeklyFor(npc);
        DailySchedule daily = weekly.getForDay(gameTick);

        // Day-off collapse: profession default + extra day-offs in,
        // override day-offs out.
        boolean dayOff = isDayOff(npc, gameTick);
        if (dayOff) daily = daily.asDayOff();

        // Personal phase-shift overlay.
        PersonalScheduleOverride override = npc.getScheduleOverride();
        if (override != null && !override.phaseShifts().isEmpty()) {
            daily = applyPhaseShifts(daily, override);
        }
        return daily;
    }

    private static WeeklySchedule weeklyFor(TownspersonMob npc) {
        PersonalScheduleOverride override = npc.getScheduleOverride();
        int shift = override == null ? 0 : override.shiftIndex();
        return WeeklyScheduleLibrary.forProfessionWithShift(npc.getProfession(), shift);
    }

    /**
     * Applies per-phase window overrides on a daily schedule. Each
     * field of the input is replaced when the override has a window
     * for the matching {@link DayPhase}; otherwise it passes through.
     */
    private static DailySchedule applyPhaseShifts(DailySchedule base,
                                                  PersonalScheduleOverride override) {
        return new DailySchedule(
                pick(base.wakeUp(),         override.shiftFor(DayPhase.WAKE_UP)),
                pick(base.commute(),        override.shiftFor(DayPhase.COMMUTE)),
                pick(base.workPrimary(),    override.shiftFor(DayPhase.WORK_PRIMARY)),
                pick(base.workErrand(),     override.shiftFor(DayPhase.WORK_ERRAND)),
                pick(base.meal(),           override.shiftFor(DayPhase.MEAL)),
                pick(base.workSecondary(),  override.shiftFor(DayPhase.WORK_SECONDARY)),
                pick(base.marketRun(),      override.shiftFor(DayPhase.MARKET_RUN)),
                pick(base.social(),         override.shiftFor(DayPhase.SOCIAL)),
                pick(base.leisure(),        override.shiftFor(DayPhase.LEISURE)),
                pick(base.homePrep(),       override.shiftFor(DayPhase.HOME_PREP)),
                pick(base.home(),           override.shiftFor(DayPhase.HOME)));
    }

    private static TimeWindow pick(TimeWindow base, TimeWindow shift) {
        return shift == null ? base : shift;
    }
}
