package tterrag1112.life_in_the_village.Npc.Brain;

import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.schedule.Activity;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

/**
 * Townsperson daily time-table mapping day-time → {@link Activity}.
 *
 * <p>Vanilla {@code Schedule} / {@code BuiltInRegistries.SCHEDULE} was
 * replaced in 1.21.11 by {@code EnvironmentAttribute&lt;Activity&gt;}, which
 * needs datapack files we don't have. For Phase 6.0 we just walk the
 * table ourselves and call {@link Brain#setActiveActivityIfPossible}.
 * Per-profession deltas land in Phase 6.2/6.3.
 *
 * <pre>
 *   0-2000   IDLE
 *   2000-6000 WORK
 *   6000-7000 SOCIAL
 *   7000-11000 WORK
 *   11000-13000 SOCIAL
 *   13000-23000 REST
 *   23000-24000 IDLE
 * </pre>
 */
public final class NpcSchedules {

    private NpcSchedules() {}

    /** Returns the activity that {@code TOWNSPERSON_DEFAULT} expects at
     *  the given day-time (will be normalized to [0, 24000)). */
    public static Activity activityAt(long dayTime) {
        long t = ((dayTime % 24000L) + 24000L) % 24000L;
        if (t < 2000)  return Activity.IDLE;
        if (t < 6000)  return NpcActivities.WORK.get();
        if (t < 7000)  return NpcActivities.SOCIAL.get();
        if (t < 11000) return NpcActivities.WORK.get();
        if (t < 13000) return NpcActivities.SOCIAL.get();
        if (t < 23000) return NpcActivities.REST.get();
        return Activity.IDLE;
    }

    /** Drives the Brain's active activity from day-time. Called from
     *  {@link TownspersonMob#customServerAiStep}. Cheap — only flips the
     *  active activity when the table-derived activity changes. */
    public static void tick(TownspersonMob npc, long dayTime) {
        // Liveliness L3 — same per-NPC jitter as ScheduleResolver so the brain
        // Activity transitions (WORK/SOCIAL/REST/IDLE) stagger across the
        // village instead of flipping at the same tick for everyone.
        long jittered = dayTime + tterrag1112.life_in_the_village.Npc.Schedule
                .ScheduleResolver.phaseJitter(npc);
        Activity expected = activityAt(jittered);
        Brain<TownspersonMob> brain = npc.getBrain();
        if (!brain.isActive(expected)) {
            brain.setActiveActivityIfPossible(expected);
        }
    }
}
