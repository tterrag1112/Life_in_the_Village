package tterrag1112.life_in_the_village.Entities.Goals.Homestead;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import tterrag1112.life_in_the_village.Entities.HouseholdData;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;

/**
 * Per-tick task strategy invoked by {@link AbstractHomesteadGoal} once
 * it has located the household and the schedule approves the goal.
 *
 * <p>Layout Rework Stage 2.5 retired the per-adjunct-plot handler stack;
 * the only remaining implementation is the generic chores handler
 * (walk-to-house + idle). The interface is kept so the goal's dispatch
 * shape is unchanged and richer handlers can return later (Stage 4 /
 * NPC rework) as parcel-bound behaviours.</p>
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
     *  handler signature short while exposing the entity, world, and
     *  household. */
    record Context(
            TownspersonMob npc,
            ServerLevel level,
            VillageSavedData data,
            Building parentHouse,
            HouseholdData household,
            int tickInGoal
    ) {
        /** Track A2 — the household house's back-of-house TOFT plot AABB, or
         *  null when the dwelling has no toft. Exposed so richer homestead
         *  handlers (garden tending) can bound their work to the toft region;
         *  the generic chores handler only uses {@code navTarget}. */
        public tterrag1112.life_in_the_village.Utilities.Geometry.Polygon.AABB toft() {
            return parentHouse != null ? parentHouse.getToft() : null;
        }

        /** Convenience: NPC's walk speed, scaled. */
        public double walkSpeed() {
            double base = npc.getAttribute(Attributes.MOVEMENT_SPEED) != null
                    ? npc.getAttribute(Attributes.MOVEMENT_SPEED).getValue()
                    : 0.30;
            return base;
        }
    }
}
