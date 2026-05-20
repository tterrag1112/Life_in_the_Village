package tterrag1112.life_in_the_village.Npc.Brain;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.entity.schedule.Schedule;
import net.minecraft.world.entity.schedule.ScheduleBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import tterrag1112.life_in_the_village.Life_in_the_village;

/**
 * Base townsperson daily schedule (Phase 6.0). One entry only — per-
 * profession deltas land in Phase 6.2/6.3 once the work activity has
 * actual behaviors driving it.
 *
 * <p>Schedule activities are switched by the vanilla {@code Brain}
 * machinery during {@code tick()}; the Schedule is the time → Activity
 * mapping and is independent from any movement.
 */
public final class NpcSchedules {

    private NpcSchedules() {}

    public static final DeferredRegister<Schedule> SCHEDULES =
            DeferredRegister.create(BuiltInRegistries.SCHEDULE,
                    Life_in_the_village.MODID);

    public static final DeferredHolder<Schedule, Schedule> TOWNSPERSON_DEFAULT =
            SCHEDULES.register("townsperson_default", NpcSchedules::buildDefault);

    private static Schedule buildDefault() {
        return new ScheduleBuilder(new Schedule())
                .changeActivityAt(0,     Activity.IDLE)
                .changeActivityAt(2000,  NpcActivities.WORK.get())
                .changeActivityAt(6000,  NpcActivities.SOCIAL.get())
                .changeActivityAt(7000,  NpcActivities.WORK.get())
                .changeActivityAt(11000, NpcActivities.SOCIAL.get())
                .changeActivityAt(13000, NpcActivities.REST.get())
                .changeActivityAt(23000, Activity.IDLE)
                .build();
    }

    public static void register(IEventBus bus) {
        SCHEDULES.register(bus);
    }
}
