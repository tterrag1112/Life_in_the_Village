package tterrag1112.life_in_the_village.Entities.Goals.Homestead.Handlers;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.AbstractHomesteadGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.HomesteadHandler;

/**
 * B2.6 — fallback handler used when a household has no rolled
 * homestead plot (HOUSE didn't win the probability gate). The
 * NPC walks toward the parent house and idles — visible "I live
 * here, I'm doing chores" presence without any item production.
 */
public final class GenericChoresHandler implements HomesteadHandler {

    /** Idle dwell time before the goal cycles. */
    private static final int IDLE_TICKS = 80;

    @Override
    public boolean tick(Context ctx) {
        // Walk to the house origin once and stay there until the
        // dwell timer expires.
        BlockPos target = AbstractHomesteadGoal.navTarget(ctx);
        if (ctx.tickInGoal() < 20) {
            ctx.npc().getNavigation().moveTo(
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                    ctx.walkSpeed());
        }
        return ctx.tickInGoal() >= IDLE_TICKS;
    }
}
