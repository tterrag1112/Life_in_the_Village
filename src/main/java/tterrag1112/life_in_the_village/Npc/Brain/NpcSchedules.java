package tterrag1112.life_in_the_village.Npc.Brain;

import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.schedule.Activity;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Schedule.DayPhase;
import tterrag1112.life_in_the_village.Npc.Schedule.ScheduleResolver;

/**
 * Townsperson daily time-table driving {@link Brain} activity
 * selection.
 *
 * <p>Vanilla {@code Schedule} / {@code BuiltInRegistries.SCHEDULE} was
 * replaced in 1.21.11 by {@code EnvironmentAttribute&lt;Activity&gt;}, which
 * needs datapack files we don't have. We walk the table ourselves and
 * call {@link Brain#setActiveActivityIfPossible}.</p>
 *
 * <h3>Phase 6.8.1 — derive from DayPhase</h3>
 * <p>Earlier passes used a hard-coded 7-bucket tick→Activity table.
 * That broke per-profession scheduling: a {@code DayPhase.MEAL}
 * window (5500-6500 for EARLY_RISER) would straddle the table's
 * fixed WORK→SOCIAL transition at tick 6000, so
 * {@link tterrag1112.life_in_the_village.Npc.Brain.Behaviors.EatMealBehavior}
 * — registered only in {@code SOCIAL} — couldn't fire for the
 * tick-5500-to-5999 slice of the meal. Same mechanism could starve
 * any phase-gated behavior whose phase window didn't align with the
 * hard-coded buckets.</p>
 *
 * <p>{@link #activityAt(TownspersonMob, long)} now derives Activity
 * from {@link ScheduleResolver#phaseAt} so the per-profession phase
 * windows in {@code WeeklyScheduleLibrary} drive Activity directly.
 * Phase becomes canonical; Activity is a projection of phase via
 * {@link #activityFor(DayPhase)}.</p>
 */
public final class NpcSchedules {

    private NpcSchedules() {}

    /**
     * Returns the activity that {@code npc} should be in at
     * {@code gameTick}. Resolves the NPC's current {@link DayPhase}
     * via {@link ScheduleResolver#phaseAt} (which honors per-profession
     * weekly schedules, day-off collapse, personal overrides, and law
     * hooks like curfew), then projects to Activity via
     * {@link #activityFor}. Falls back to {@link #defaultActivityFor}
     * — the legacy hard-coded table — when {@code npc} is null
     * (defensive; callers always pass a real NPC today).
     */
    public static Activity activityAt(TownspersonMob npc, long gameTick) {
        if (npc == null) return defaultActivityFor(gameTick);
        DayPhase phase = ScheduleResolver.phaseAt(npc, gameTick);
        return activityFor(phase);
    }

    /**
     * Maps a {@link DayPhase} to its host {@link Activity}.
     *
     * <p>{@code LEISURE} → {@code IDLE} (not SOCIAL) so that
     * homestead-skill behaviors registered in IDLE (HomeBaking,
     * HomeMilling, HomeWeaving, HomeCandlemaking) can fire when their
     * LEISURE-phase motive triggers. {@code COMMUTE} / {@code WAKE_UP}
     * → {@code IDLE} so transitional movement uses the IDLE wander /
     * shelter / personal-space behaviors rather than firing WORK
     * behaviors before the NPC has arrived. {@code MARKET_RUN} →
     * {@code SOCIAL} so the NPC engages with shop / market behaviors
     * already living in SOCIAL.</p>
     */
    public static Activity activityFor(DayPhase phase) {
        return switch (phase) {
            case WAKE_UP, COMMUTE, LEISURE             -> Activity.IDLE;
            case WORK_PRIMARY, WORK_ERRAND, WORK_SECONDARY
                                                       -> NpcActivities.WORK.get();
            case MEAL, MARKET_RUN, SOCIAL              -> NpcActivities.SOCIAL.get();
            case HOME_PREP, HOME                       -> NpcActivities.REST.get();
        };
    }

    /**
     * Legacy hard-coded tick-bucket table. Kept for the
     * {@code (long)}-only path (debug callers without an NPC in
     * scope) and as documentation of the pre-6.8.1 default.
     */
    public static Activity defaultActivityFor(long dayTime) {
        long t = ((dayTime % 24000L) + 24000L) % 24000L;
        if (t < 2000)  return Activity.IDLE;
        if (t < 6000)  return NpcActivities.WORK.get();
        if (t < 7000)  return NpcActivities.SOCIAL.get();
        if (t < 11000) return NpcActivities.WORK.get();
        if (t < 13000) return NpcActivities.SOCIAL.get();
        if (t < 23000) return NpcActivities.REST.get();
        return Activity.IDLE;
    }

    /** Drives the Brain's active activity from the NPC's current
     *  phase. Called from {@link TownspersonMob#customServerAiStep}.
     *  Cheap — only flips the active activity when the derived
     *  activity changes. */
    public static void tick(TownspersonMob npc, long dayTime) {
        // Liveliness L3 — same per-NPC jitter as ScheduleResolver so the brain
        // Activity transitions (WORK/SOCIAL/REST/IDLE) stagger across the
        // village instead of flipping at the same tick for everyone.
        long jittered = dayTime + tterrag1112.life_in_the_village.Npc.Schedule
                .ScheduleResolver.phaseJitter(npc);
        Activity expected = activityAt(npc, jittered);
        Brain<TownspersonMob> brain = npc.getBrain();
        if (!brain.isActive(expected)) {
            brain.setActiveActivityIfPossible(expected);
        }
    }
}
