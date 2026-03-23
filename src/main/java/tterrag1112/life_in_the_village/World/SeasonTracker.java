// src/main/java/tterrag1112/life_in_the_village/World/SeasonTracker.java
package tterrag1112.life_in_the_village.World;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;

/**
 * Tracks the current in-game season, derived purely from the world's game tick.
 *
 * <h3>Calendar</h3>
 * One full year = 96 in-game days (each day = 24 000 ticks).
 * <pre>
 *   SPRING  days  0–23   (ticks      0 –  575 999)
 *   SUMMER  days 24–47   (ticks 576 000 – 1 151 999)
 *   AUTUMN  days 48–71   (ticks 1 152 000 – 1 727 999)
 *   WINTER  days 72–95   (ticks 1 728 000 – 2 303 999)
 * </pre>
 * No saved state is required — season is fully deterministic from {@code gameTick}.
 */
public final class SeasonTracker {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    public static final int DAYS_PER_SEASON = 24;
    public static final int DAYS_PER_YEAR   = DAYS_PER_SEASON * 4; // 96
    public static final long TICKS_PER_DAY  = 24_000L;
    public static final long TICKS_PER_SEASON = TICKS_PER_DAY * DAYS_PER_SEASON;
    public static final long TICKS_PER_YEAR   = TICKS_PER_DAY * DAYS_PER_YEAR;

    private SeasonTracker() {}

    // -------------------------------------------------------------------------
    // Season enum
    // -------------------------------------------------------------------------

    public enum Season {
        SPRING(0, "Spring", 0xAAFF88),
        SUMMER(1, "Summer", 0xFFFF44),
        AUTUMN(2, "Autumn", 0xFF8833),
        WINTER(3, "Winter", 0xAADDFF);

        public final int index;
        public final String displayName;
        public final int colour;

        Season(int index, String displayName, int colour) {
            this.index       = index;
            this.displayName = displayName;
            this.colour      = colour;
        }

        /** True if crops can be planted and grow this season. */
        public boolean isCropSeason() {
            return this != WINTER;
        }

        /**
         * Multiplier applied to NPC harvest yield and FarmPlot produce quantity.
         * <ul>
         *   <li>SPRING – 0.8 (early growth, partial yields)</li>
         *   <li>SUMMER – 1.3 (peak growth)</li>
         *   <li>AUTUMN – 1.0 (solid harvest)</li>
         *   <li>WINTER – 0.0 (nothing grows)</li>
         * </ul>
         */
        public float getYieldMultiplier() {
            return switch (this) {
                case SPRING -> 0.8f;
                case SUMMER -> 1.3f;
                case AUTUMN -> 1.0f;
                case WINTER -> 0.0f;
            };
        }

        /**
         * Multiplier applied to village FOOD need urgency.
         * Winter consumption outpaces supply, raising need pressure.
         */
        public float getFoodNeedMultiplier() {
            return switch (this) {
                case SPRING -> 1.1f;   // reserves still being rebuilt
                case SUMMER -> 0.9f;   // food plentiful
                case AUTUMN -> 0.85f;  // harvest time
                case WINTER -> 1.4f;   // nothing grows, stocks drain
            };
        }

        /**
         * Bonus XP multiplier for relevant gathering professions this season.
         */
        public float getProfessionXpMultiplier() {
            return switch (this) {
                case SUMMER, AUTUMN -> 1.25f;   // peak work season
                case SPRING         -> 1.0f;
                case WINTER         -> 0.85f;   // reduced outdoor work
            };
        }

        /** Returns the next season in cycle order. */
        public Season next() {
            return values()[(index + 1) % 4];
        }
    }

    // -------------------------------------------------------------------------
    // Core query methods
    // -------------------------------------------------------------------------

    /**
     * Returns the current season for the given game tick.
     *
     * @param gameTick {@code level.getGameTime()}
     */
    public static Season currentSeason(long gameTick) {
        long dayOfYear = (gameTick / TICKS_PER_DAY) % DAYS_PER_YEAR;
        int seasonIndex = (int)(dayOfYear / DAYS_PER_SEASON);
        return Season.values()[seasonIndex];
    }

    /** Convenience overload that reads the tick directly from the level. */
    public static Season currentSeason(ServerLevel level) {
        return currentSeason(level.getGameTime());
    }

    /**
     * Returns how many ticks remain until the next season transition.
     *
     * @param gameTick {@code level.getGameTime()}
     */
    public static long ticksUntilNextSeason(long gameTick) {
        long posInYear    = gameTick % TICKS_PER_YEAR;
        long posInSeason  = posInYear % TICKS_PER_SEASON;
        return TICKS_PER_SEASON - posInSeason;
    }

    /**
     * Returns the current day-of-year (0-based, 0 = first day of spring).
     *
     * @param gameTick {@code level.getGameTime()}
     */
    public static int dayOfYear(long gameTick) {
        return (int)((gameTick / TICKS_PER_DAY) % DAYS_PER_YEAR);
    }

    /**
     * Returns the current year number (starting at 1 on world creation).
     *
     * @param gameTick {@code level.getGameTime()}
     */
    public static long yearNumber(long gameTick) {
        return (gameTick / TICKS_PER_YEAR) + 1L;
    }

    /**
     * Returns true if the given game tick is the first tick of a new season
     * (i.e. a season boundary just crossed). Used for one-shot season
     * transition events.
     *
     * @param gameTick current tick
     */
    public static boolean isSeasonTransitionTick(long gameTick) {
        long posInYear   = gameTick % TICKS_PER_YEAR;
        long posInSeason = posInYear % TICKS_PER_SEASON;
        return posInSeason == 0;
    }

    /**
     * Returns a formatted display string for use in player messages,
     * e.g. "Autumn, Year 3 (Day 51)".
     *
     * @param gameTick {@code level.getGameTime()}
     */
    public static String formatDate(long gameTick) {
        Season season = currentSeason(gameTick);
        long   year   = yearNumber(gameTick);
        int    day    = dayOfYear(gameTick);
        int    dayInSeason = day - (season.index * DAYS_PER_SEASON) + 1;
        return season.displayName + ", Year " + year
                + " (Day " + dayInSeason + " of " + season.displayName + ")";
    }

    /**
     * Returns a Component suitable for sending to a player, coloured
     * to match the season.
     */
    public static Component formatDateComponent(long gameTick) {
        Season season = currentSeason(gameTick);
        return Component.literal(formatDate(gameTick))
                .withColor(season.colour);
    }

    // -------------------------------------------------------------------------
    // Utility checks
    // -------------------------------------------------------------------------

    /** True if the current season allows outdoor crop farming. */
    public static boolean isFarmingSeason(ServerLevel level) {
        return currentSeason(level).isCropSeason();
    }

    /** True if crops should not be planted right now. */
    public static boolean isWinter(ServerLevel level) {
        return currentSeason(level) == Season.WINTER;
    }

    /**
     * Returns the yield multiplier for the current season.
     * Use this in FarmerGoal / FarmhandGoal to scale harvest drop counts.
     */
    public static float getYieldMultiplier(ServerLevel level) {
        return currentSeason(level).getYieldMultiplier();
    }


}