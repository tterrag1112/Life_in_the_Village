package tterrag1112.life_in_the_village.Entities.Goals.Homestead;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import tterrag1112.life_in_the_village.Entities.HouseholdData;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Decoration.Adjunct.AdjunctPlot;

/**
 * B2.6 — per-{@code AdjunctPlotType} task strategy invoked by
 * {@link AbstractHomesteadGoal} once it has located the household's
 * homestead plot, identified the assigned NPC, and the schedule
 * approves the goal.
 *
 * <p>Handlers are stateless singletons registered in
 * {@link HomesteadHandlerRegistry}. The goal owns per-tick state
 * (timers, current target block); handlers do the small work
 * cycle: pick a target, walk, animate, produce. Implementations
 * should be tight (~30-80 lines) — animations / sounds match the
 * existing profession-goal idiom.</p>
 */
public interface HomesteadHandler {

    /** One tick of work. The goal calls this every tick the NPC
     *  is "doing the homestead." Return {@code true} when the
     *  current task cycle is finished and the goal can stop /
     *  reset; {@code false} to keep ticking. */
    boolean tick(Context ctx);

    /** Optional one-shot when the NPC arrives at the plot. Default
     *  no-op so handlers needn't implement it. */
    default void onArrive(Context ctx) {}

    /** Optional cleanup when the goal stops. */
    default void onStop(Context ctx) {}

    /** Bundle the goal hands the handler each tick. Keeps the
     *  handler signature short while exposing the entity, world,
     *  household, and the rolled plot record. */
    record Context(
            TownspersonMob npc,
            ServerLevel level,
            VillageSavedData data,
            Building parentHouse,
            HouseholdData household,
            AdjunctPlot plot,
            int tickInGoal
    ) {
        /** Convenience: NPC's walk speed, scaled. */
        public double walkSpeed() {
            double base = npc.getAttribute(Attributes.MOVEMENT_SPEED) != null
                    ? npc.getAttribute(Attributes.MOVEMENT_SPEED).getValue()
                    : 0.30;
            return base;
        }
    }
}
