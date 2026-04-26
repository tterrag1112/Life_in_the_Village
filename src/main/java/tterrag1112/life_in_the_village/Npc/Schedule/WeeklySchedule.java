package tterrag1112.life_in_the_village.Npc.Schedule;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Seven daily schedules + a set of day-of-week indices marking days
 * off. Spec {@code 13-weekly-schedule.md} (section "WeeklySchedule").
 *
 * <p>Day-of-week is computed as {@code (gameTick / 24000) % 7} (spec
 * line 71). Index 0 is the first day of the in-game week; the
 * absolute mapping to "Monday/Tuesday/..." is presentational only.</p>
 */
public record WeeklySchedule(List<DailySchedule> days, Set<Integer> dayOffs) {

    public static final int DAYS_IN_WEEK = 7;

    public WeeklySchedule {
        if (days == null || days.size() != DAYS_IN_WEEK) {
            throw new IllegalArgumentException("days must have exactly 7 entries");
        }
        days = List.copyOf(days);
        dayOffs = dayOffs == null ? Set.of() : Set.copyOf(dayOffs);
    }

    /** Returns the schedule for the day containing {@code gameTick}. */
    public DailySchedule getForDay(long gameTick) {
        int idx = computeDayOfWeek(gameTick);
        return days.get(idx);
    }

    public boolean isDayOff(long gameTick) {
        return dayOffs.contains(computeDayOfWeek(gameTick));
    }

    public static int computeDayOfWeek(long gameTick) {
        long days = Math.floorDiv(gameTick, 24000L);
        return (int) Math.floorMod(days, (long) DAYS_IN_WEEK);
    }

    /**
     * Convenience: returns a uniform weekly schedule where every day
     * uses {@code dailySchedule}. {@code dayOffIndex} is set as the
     * single day-off; pass {@code -1} for no day-off.
     */
    public static WeeklySchedule uniform(DailySchedule dailySchedule, int dayOffIndex) {
        DailySchedule[] arr = new DailySchedule[DAYS_IN_WEEK];
        Arrays.fill(arr, dailySchedule);
        Set<Integer> offs = dayOffIndex < 0 ? Set.of() : Set.of(dayOffIndex);
        return new WeeklySchedule(Arrays.asList(arr), offs);
    }

    /** Returns a copy with each day's schedule shifted by {@code deltaTicks}. */
    public WeeklySchedule shifted(int deltaTicks) {
        DailySchedule[] arr = new DailySchedule[DAYS_IN_WEEK];
        for (int i = 0; i < DAYS_IN_WEEK; i++) arr[i] = days.get(i).shifted(deltaTicks);
        return new WeeklySchedule(Arrays.asList(arr), new HashSet<>(dayOffs));
    }

    public static final Codec<WeeklySchedule> CODEC = RecordCodecBuilder.create(i -> i.group(
            DailySchedule.CODEC.listOf().fieldOf("days").forGetter(WeeklySchedule::days),
            Codec.INT.listOf().optionalFieldOf("dayOffs", List.of())
                    .forGetter(s -> List.copyOf(s.dayOffs))
    ).apply(i, (days, offs) -> new WeeklySchedule(days, new HashSet<>(offs))));
}
