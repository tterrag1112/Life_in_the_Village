package tterrag1112.life_in_the_village.Entities;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Profession.Profession;

/**
 * Defines the daily rhythm for NPC professions.
 *
 * <h3>Minecraft day cycle</h3>
 * <pre>
 *   0     = dawn (6:00 AM)
 *   6000  = noon (12:00 PM)
 *   12000 = dusk (6:00 PM)
 *   13000 = nightfall (7:00 PM)
 *   18000 = midnight (12:00 AM)
 *   23000 = pre-dawn (5:00 AM)
 * </pre>
 *
 * <h3>Daily rhythm (default profession)</h3>
 * <pre>
 *   0-500     WAKE_UP     NPC wakes, leaves house, walks to workplace area
 *   500-5500  WORK        Morning work shift
 *   5500-6500 MEAL        Midday meal — return home or eat at inn
 *   6500-11000 WORK       Afternoon work shift
 *   11000-13000 SOCIAL    Free time — socialize at town square, inn, or walk
 *   13000-23500 HOME      Return home, sleep
 *   23500-24000 WAKE_UP   Pre-dawn stirring
 * </pre>
 *
 * <h3>Design intent</h3>
 * Players should observe a clear daily pattern: NPCs leaving homes in
 * the morning, working at their stations, breaking for a meal, socializing
 * in the evening, then heading home to sleep. This makes the village feel
 * alive and gives players a predictable schedule to work around.
 *
 * <h3>Event overrides</h3>
 * During village events (festivals, market days), the schedule is modified
 * by {@code TownspersonMob.isWorkTime()} which checks for event overrides
 * before consulting this class.
 */
public class WorkSchedule {

    /**
     * The phases of an NPC's day. Used by goals to decide what to do.
     */
    public enum DayPhase {
        /** Just woke up, walking to workplace */
        WAKE_UP,
        /** At workplace performing profession tasks */
        WORK,
        /** Midday meal break — heading home or to inn to eat */
        MEAL,
        /** Free time — socializing, walking, greeting */
        SOCIAL,
        /** Heading home, going to bed */
        HOME
    }

    /**
     * A time window within the 24000-tick Minecraft day cycle.
     * Supports wrapping across midnight (e.g. 22000 → 2000).
     */
    public record TimeWindow(int startTick, int endTick) {
        public boolean isActive(long dayTime) {
            long t = dayTime % 24000;
            if (startTick <= endTick) {
                return t >= startTick && t < endTick;
            } else {
                return t >= startTick || t < endTick;
            }
        }
    }

    // =========================================================================
    // Schedule definitions per profession
    // =========================================================================

    /**
     * Complete daily schedule for a profession.
     * Each field is a TimeWindow defining when that phase is active.
     */
    public record DailySchedule(
            TimeWindow wakeUp,
            TimeWindow morningWork,
            TimeWindow meal,
            TimeWindow afternoonWork,
            TimeWindow social,
            TimeWindow home
    ) {
        /** Is any work window active? */
        public boolean isWorkTime(long dayTime) {
            return morningWork.isActive(dayTime)
                    || afternoonWork.isActive(dayTime);
        }

        public boolean isMealTime(long dayTime) {
            return meal.isActive(dayTime);
        }

        public boolean isSocialTime(long dayTime) {
            return social.isActive(dayTime);
        }

        public boolean isHomeTime(long dayTime) {
            return home.isActive(dayTime);
        }

        public DayPhase getCurrentPhase(long dayTime) {
            if (wakeUp.isActive(dayTime))          return DayPhase.WAKE_UP;
            if (morningWork.isActive(dayTime))      return DayPhase.WORK;
            if (meal.isActive(dayTime))             return DayPhase.MEAL;
            if (afternoonWork.isActive(dayTime))    return DayPhase.WORK;
            if (social.isActive(dayTime))           return DayPhase.SOCIAL;
            return DayPhase.HOME;
        }
    }

    // ── Schedule presets ─────────────────────────────────────────────────────

    private static final DailySchedule EARLY_RISER = new DailySchedule(
            new TimeWindow(23500, 500),    // wake 5:30-6:30 AM
            new TimeWindow(500, 5500),     // morning work 6:30 AM - 11:30 AM
            new TimeWindow(5500, 6500),    // meal 11:30 AM - 12:30 PM
            new TimeWindow(6500, 11000),   // afternoon work 12:30 PM - 5:00 PM
            new TimeWindow(11000, 13000),  // social 5:00 PM - 7:00 PM
            new TimeWindow(13000, 23500)   // home 7:00 PM - 5:30 AM
    );

    private static final DailySchedule LATE_STARTER = new DailySchedule(
            new TimeWindow(1000, 2000),    // wake 7:00-8:00 AM
            new TimeWindow(2000, 5500),    // morning work 8:00 AM - 11:30 AM
            new TimeWindow(5500, 6500),    // meal 11:30 AM - 12:30 PM
            new TimeWindow(6500, 13000),   // afternoon work 12:30 PM - 7:00 PM
            new TimeWindow(13000, 15000),  // social 7:00 PM - 9:00 PM
            new TimeWindow(15000, 1000)    // home 9:00 PM - 7:00 AM
    );

    private static final DailySchedule MERCHANT_SCHEDULE = new DailySchedule(
            new TimeWindow(1500, 2000),    // wake 7:30-9:00 AM
            new TimeWindow(2000, 5500),    // morning 9:00 AM - 11:30 AM
            new TimeWindow(5500, 6500),    // meal 11:30 AM - 12:30 PM
            new TimeWindow(6500, 14000),   // afternoon 12:30 PM - 8:00 PM
            new TimeWindow(14000, 16000),  // social 8:00 PM - 10:00 PM
            new TimeWindow(16000, 1500)    // home 10:00 PM - 7:30 AM
    );

    private static final DailySchedule GUARD_SCHEDULE = new DailySchedule(
            new TimeWindow(23000, 0),      // wake 5:00-6:00 AM
            new TimeWindow(0, 5000),       // morning patrol 6:00 AM - 11:00 AM
            new TimeWindow(5000, 6000),    // meal 11:00 AM - 12:00 PM
            new TimeWindow(6000, 18000),   // afternoon patrol 12:00 PM - 12:00 AM
            new TimeWindow(18000, 19000),  // brief social 12:00 AM - 1:00 AM
            new TimeWindow(19000, 23000)   // sleep 1:00 AM - 5:00 AM (short)
    );

    private static final DailySchedule INNKEEPER_SCHEDULE = new DailySchedule(
            new TimeWindow(2000, 3000),    // wake 8:00-9:00 AM
            new TimeWindow(3000, 5500),    // morning prep 9:00 AM - 11:30 AM
            new TimeWindow(5500, 6500),    // meal 11:30 AM - 12:30 PM
            new TimeWindow(6500, 16000),   // afternoon+evening service
            new TimeWindow(16000, 17000),  // brief social
            new TimeWindow(17000, 2000)    // sleep (late closer)
    );

    private static final DailySchedule DEFAULT_SCHEDULE = new DailySchedule(
            new TimeWindow(0, 1000),       // wake 6:00-8:00 AM
            new TimeWindow(1000, 5500),    // morning work 8:00 AM - 11:30 AM
            new TimeWindow(5500, 6500),    // meal 11:30 AM - 12:30 PM
            new TimeWindow(6500, 12000),   // afternoon work 12:30 PM - 6:00 PM
            new TimeWindow(12000, 14000),  // social 6:00 PM - 8:00 PM
            new TimeWindow(14000, 0)       // home 8:00 PM - 6:00 AM
    );

    // =========================================================================
    // Schedule lookup
    // =========================================================================

    public static DailySchedule getSchedule(Profession profession) {
        return switch (profession) {
            case FARMER, FARMHAND  -> EARLY_RISER;
            case MERCHANT          -> MERCHANT_SCHEDULE;
            case GUARD             -> GUARD_SCHEDULE;
            case INNKEEPER         -> INNKEEPER_SCHEDULE;
            case BLACKSMITH        -> LATE_STARTER;
            case BUILDER           -> EARLY_RISER;
            case MINER             -> LATE_STARTER;
            case STOCKPILE_KEEPER  -> DEFAULT_SCHEDULE;
            case CARPENTER         -> EARLY_RISER;
            case MILLER, BAKER  -> EARLY_RISER;   // up early, done before noon heat
            case STONEMASON     -> DEFAULT_SCHEDULE;
            case WEAVER         -> DEFAULT_SCHEDULE;
            case CANDLEMAKER    -> DEFAULT_SCHEDULE;
            default                -> DEFAULT_SCHEDULE;
        };
    }

    // =========================================================================
    // Convenience methods (called from TownspersonMob)
    // =========================================================================

    public static boolean isWorkTime(TownspersonMob mob) {
        return getSchedule(mob.getProfession())
                .isWorkTime(mob.level().getDayTime());
    }

    public static boolean isMealTime(TownspersonMob mob) {
        return getSchedule(mob.getProfession())
                .isMealTime(mob.level().getDayTime());
    }

    public static boolean isSleepTime(TownspersonMob mob) {
        return getSchedule(mob.getProfession())
                .isHomeTime(mob.level().getDayTime());
    }

    public static boolean isSocialTime(TownspersonMob mob) {
        return getSchedule(mob.getProfession())
                .isSocialTime(mob.level().getDayTime());
    }

    public static boolean shouldBeHome(TownspersonMob mob) {
        if (mob.getProfession() == Profession.GUARD) return false;
        return isSleepTime(mob);
    }

    public static DayPhase getCurrentPhase(TownspersonMob mob) {
        return getSchedule(mob.getProfession())
                .getCurrentPhase(mob.level().getDayTime());
    }

    // =========================================================================
    // Legacy compatibility (old TimeWindow getters — keep until migration done)
    // =========================================================================

    /**
     * @deprecated Use {@link #getSchedule(Profession)} instead.
     */
    @Deprecated
    public static TimeWindow getWorkWindow(Profession profession) {
        DailySchedule s = getSchedule(profession);
        // Merge morning + afternoon into one window for backward compat
        return new TimeWindow(s.morningWork().startTick(),
                s.afternoonWork().endTick());
    }

    /**
     * @deprecated Use {@link #getSchedule(Profession)} instead.
     */
    @Deprecated
    public static TimeWindow getSleepWindow(Profession profession) {
        return getSchedule(profession).home();
    }

    /**
     * @deprecated Use {@link #getSchedule(Profession)} instead.
     */
    @Deprecated
    public static TimeWindow getSocialWindow(Profession profession) {
        return getSchedule(profession).social();
    }
}