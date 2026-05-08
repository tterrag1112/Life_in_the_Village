package tterrag1112.life_in_the_village.Entities.Goals.Homestead.Handlers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.AbstractHomesteadGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.HomesteadHandler;

/**
 * B2.6 — beehives. Slow cycle (honeybees in real life don't
 * yield often) — produces a honey bottle every ~3× the work-tick
 * window. Honeycomb on alternating cycles.
 */
public final class BeehivesHandler implements HomesteadHandler {

    private static final int WORK_TICKS = 160;

    @Override
    public boolean tick(Context ctx) {
        BlockPos target = AbstractHomesteadGoal.navTarget(ctx);
        if (ctx.tickInGoal() < 20) {
            ctx.npc().getNavigation().moveTo(
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                    ctx.walkSpeed());
        }
        if (ctx.tickInGoal() == WORK_TICKS) {
            ctx.npc().getPersonalInventory().addItem(new ItemStack(
                    (ctx.npc().tickCount % 2) == 0
                            ? Items.HONEY_BOTTLE : Items.HONEYCOMB, 1));
        }
        return ctx.tickInGoal() >= WORK_TICKS;
    }
}
