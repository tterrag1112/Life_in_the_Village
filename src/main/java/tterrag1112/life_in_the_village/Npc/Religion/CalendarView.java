package tterrag1112.life_in_the_village.Npc.Religion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Religion Rework R9c — the single source of religious-calendar "upcoming"
 * computation, on the {@code % 365} liturgical axis (NOT {@code SeasonTracker}'s
 * 96-day seasonal axis). Each faith's holy days, signature-rite day, and
 * grand-festival day are all named entries in its {@link ReligiousCalendar}
 * ({@code holyDaysByName}); the schedulers fire them via
 * {@link ReligiousCalendar#effectiveDayOfYear(String)}, so iterating the named
 * days captures every calendar event a faith celebrates.
 *
 * <p>Shared by the {@code /religion calendar} command, the R9b temple screen,
 * and the R9c player-religion screen so the day math lives in exactly one place.</p>
 */
public final class CalendarView {

    private CalendarView() {}

    private static final long DAY = 24000L;

    /** One upcoming calendar event for a faith, on the 365-day axis. */
    public record Entry(String faithId, String faithDisplay, String dayLabel,
                        int dayOfYear, int daysAway) {}

    /** The current day-of-year on the liturgical {@code % 365} axis. */
    public static int dayOfYear(long gameTime) {
        return (int) ((gameTime / DAY) % ReligiousCalendar.DAYS_PER_YEAR);
    }

    /**
     * Every named calendar day for one religion, soonest-first (today = 0 days
     * away). Empty for a null religion or an empty calendar.
     */
    public static List<Entry> upcomingFor(Religion religion, long gameTime) {
        List<Entry> out = new ArrayList<>();
        if (religion == null) return out;
        ReligiousCalendar cal = religion.calendar();
        int today = dayOfYear(gameTime);
        for (var e : cal.holyDaysByName().entrySet()) {
            Integer eff = cal.effectiveDayOfYear(e.getKey());
            if (eff == null) continue;
            int daysAway = (eff - today + ReligiousCalendar.DAYS_PER_YEAR)
                    % ReligiousCalendar.DAYS_PER_YEAR;
            out.add(new Entry(religion.id(), religion.displayName(),
                    e.getKey(), eff, daysAway));
        }
        out.sort((a, b) -> Integer.compare(a.daysAway(), b.daysAway()));
        return out;
    }

    /**
     * Merged upcoming entries across several religions, soonest-first, capped at
     * {@code max} (0 or negative = uncapped).
     */
    public static List<Entry> upcomingAcross(Collection<Religion> religions,
                                             long gameTime, int max) {
        List<Entry> all = new ArrayList<>();
        for (Religion r : religions) all.addAll(upcomingFor(r, gameTime));
        all.sort((a, b) -> Integer.compare(a.daysAway(), b.daysAway()));
        if (max > 0 && all.size() > max) return new ArrayList<>(all.subList(0, max));
        return all;
    }
}
