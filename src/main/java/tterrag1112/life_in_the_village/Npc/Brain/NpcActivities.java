package tterrag1112.life_in_the_village.Npc.Brain;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.schedule.Activity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import tterrag1112.life_in_the_village.Life_in_the_village;

/**
 * Mod activities for the Brain stack (Phase 6.0). {@link Activity#CORE}
 * and {@link Activity#IDLE} are reused from vanilla — they already
 * cover the semantics we want and keep cross-mod compatibility wide.
 */
public final class NpcActivities {

    private NpcActivities() {}

    public static final DeferredRegister<Activity> ACTIVITIES =
            DeferredRegister.create(BuiltInRegistries.ACTIVITY,
                    Life_in_the_village.MODID);

    public static final DeferredHolder<Activity, Activity> WORK      = reg("work");
    public static final DeferredHolder<Activity, Activity> SOCIAL    = reg("social");
    public static final DeferredHolder<Activity, Activity> REST      = reg("rest");
    public static final DeferredHolder<Activity, Activity> MEET      = reg("meet");
    public static final DeferredHolder<Activity, Activity> PANIC     = reg("panic");
    public static final DeferredHolder<Activity, Activity> FIGHT     = reg("fight");
    public static final DeferredHolder<Activity, Activity> DUTY      = reg("duty");
    public static final DeferredHolder<Activity, Activity> EMERGENCY = reg("emergency");

    private static DeferredHolder<Activity, Activity> reg(String name) {
        return ACTIVITIES.register(name, () -> new Activity(name));
    }

    public static void register(IEventBus bus) {
        ACTIVITIES.register(bus);
    }
}
