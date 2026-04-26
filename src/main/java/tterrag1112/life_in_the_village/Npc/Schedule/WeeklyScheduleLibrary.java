package tterrag1112.life_in_the_village.Npc.Schedule;

import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Static catalogue of weekly schedules per profession. Each profession
 * gets a 7-day weekly schedule with one or more day-offs per spec
 * {@code 13-weekly-schedule.md} (section "WeeklyScheduleLibrary").
 *
 * <p>Phase 2 implementation: every profession's daily template is
 * the same legacy single-day schedule for all 7 days, but each
 * profession gets a profession-appropriate day-off pattern (Sunday
 * for most; rotated for guards / innkeepers; full weekend for
 * craftsmen). Phase 5 culture pass replaces with per-culture
 * variations.</p>
 *
 * <p>Day indices: {@code 0=Mon, 1=Tue, 2=Wed, 3=Thu, 4=Fri,
 * 5=Sat, 6=Sun}.</p>
 */
public final class WeeklyScheduleLibrary {

    private WeeklyScheduleLibrary() {}

    // ── Daily templates (mirror the legacy single-day shapes) ──────────────

    /** Farmer / forester / builder — early up, full days. */
    private static final DailySchedule EARLY_RISER = DailySchedule.legacy(
            new TimeWindow(23500, 500),
            new TimeWindow(500, 5500),
            new TimeWindow(5500, 6500),
            new TimeWindow(6500, 11000),
            new TimeWindow(11000, 13000),
            new TimeWindow(13000, 23500));

    /** Blacksmith / miner / late-shift craftsmen. */
    private static final DailySchedule LATE_STARTER = DailySchedule.legacy(
            new TimeWindow(1000, 2000),
            new TimeWindow(2000, 5500),
            new TimeWindow(5500, 6500),
            new TimeWindow(6500, 13000),
            new TimeWindow(13000, 15000),
            new TimeWindow(15000, 1000));

    /** Merchant — opens late, stays late. */
    private static final DailySchedule MERCHANT_DAY = DailySchedule.legacy(
            new TimeWindow(1500, 2000),
            new TimeWindow(2000, 5500),
            new TimeWindow(5500, 6500),
            new TimeWindow(6500, 14000),
            new TimeWindow(14000, 16000),
            new TimeWindow(16000, 1500));

    /** Guard — extended patrol. */
    private static final DailySchedule GUARD_DAY = DailySchedule.legacy(
            new TimeWindow(23000, 0),
            new TimeWindow(0, 5000),
            new TimeWindow(5000, 6000),
            new TimeWindow(6000, 18000),
            new TimeWindow(18000, 19000),
            new TimeWindow(19000, 23000));

    /** Innkeeper — late opener, late closer. */
    private static final DailySchedule INNKEEPER_DAY = DailySchedule.legacy(
            new TimeWindow(2000, 3000),
            new TimeWindow(3000, 5500),
            new TimeWindow(5500, 6500),
            new TimeWindow(6500, 16000),
            new TimeWindow(16000, 17000),
            new TimeWindow(17000, 2000));

    /** Generic default. */
    private static final DailySchedule DEFAULT_DAY = DailySchedule.legacy(
            new TimeWindow(0, 1000),
            new TimeWindow(1000, 5500),
            new TimeWindow(5500, 6500),
            new TimeWindow(6500, 12000),
            new TimeWindow(12000, 14000),
            new TimeWindow(14000, 0));

    /**
     * Scribal day (scribe / librarian / scholar) — late riser, full
     * indoor day with social dusk hours and an extended evening to
     * wind down with reading.
     */
    private static final DailySchedule SCHOLAR_DAY = DailySchedule.legacy(
            new TimeWindow(1000, 2000),
            new TimeWindow(2000, 5500),
            new TimeWindow(5500, 6500),
            new TimeWindow(6500, 13000),
            new TimeWindow(13000, 15000),
            new TimeWindow(15000, 1000));

    // ── Day-off patterns (sets of weekday indices) ─────────────────────────

    private static final Set<Integer> SUNDAY_OFF = Set.of(6);
    private static final Set<Integer> WEEKEND_OFF = Set.of(5, 6);
    private static final Set<Integer> TUESDAY_OFF = Set.of(1);   // innkeeper
    private static final Set<Integer> MONDAY_OFF = Set.of(0);    // priest (sunday busy)
    private static final Set<Integer> NO_OFF = Set.of();         // farmer harvest

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Default weekly schedule for {@code profession}. Returns a
     * uniformly-tiled week using the profession's daily template,
     * with profession-appropriate day-off set.
     */
    public static WeeklySchedule forProfession(Profession profession) {
        DailySchedule daily = dailyFor(profession);
        Set<Integer> offs = dayOffsFor(profession);
        return uniformWeek(daily, offs);
    }

    /**
     * Shift-rotated variant: guards / innkeepers each get a
     * {@code shiftIndex} that shifts every time window by ~6000 ticks
     * per shift. Spec line 217.
     */
    public static WeeklySchedule forProfessionWithShift(Profession profession, int shiftIndex) {
        WeeklySchedule base = forProfession(profession);
        if (shiftIndex <= 0) return base;
        int delta = shiftIndex * 6000;
        return base.shifted(delta);
    }

    private static DailySchedule dailyFor(Profession p) {
        return switch (p) {
            case FARMER, FARMHAND, BUILDER, CARPENTER, MILLER, BAKER -> EARLY_RISER;
            case BLACKSMITH, MINER -> LATE_STARTER;
            case MERCHANT, WANDERING_TRADER -> MERCHANT_DAY;
            case GUARD, ADVENTURER -> GUARD_DAY;
            case INNKEEPER -> INNKEEPER_DAY;
            // Scribal trio: late-rising indoor day (Phase 2 task 17).
            case SCRIBE, LIBRARIAN, SCHOLAR -> SCHOLAR_DAY;
            default -> DEFAULT_DAY;
        };
    }

    private static Set<Integer> dayOffsFor(Profession p) {
        return switch (p) {
            // Farmer: no day off (harvest season approximation; spec line 121).
            case FARMER, FARMHAND -> NO_OFF;
            // Innkeeper: midweek off (spec line 122).
            case INNKEEPER -> TUESDAY_OFF;
            // Priest: monday off (spec line 124 — Sunday busy with services).
            case PRIEST -> MONDAY_OFF;
            // Blacksmith: full weekend (spec line 123).
            case BLACKSMITH, CARPENTER, STONEMASON, WEAVER, CANDLEMAKER -> WEEKEND_OFF;
            // Guards: shift rotation handles coverage; each guard still
            // takes Sunday off in the default case.
            case GUARD, ADVENTURER -> SUNDAY_OFF;
            // Scribal trio: weekend off — civic-rhythm (closed Sat/Sun).
            case SCRIBE, LIBRARIAN, SCHOLAR -> WEEKEND_OFF;
            default -> SUNDAY_OFF;
        };
    }

    private static WeeklySchedule uniformWeek(DailySchedule daily, Set<Integer> offs) {
        DailySchedule[] arr = new DailySchedule[WeeklySchedule.DAYS_IN_WEEK];
        Arrays.fill(arr, daily);
        return new WeeklySchedule(Arrays.asList(arr), new HashSet<>(offs));
    }
}
