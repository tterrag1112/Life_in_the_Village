package tterrag1112.life_in_the_village.Entities.Goals.Homestead.Handlers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.AbstractHomesteadGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.HomesteadHandler;

/**
 * B2.6 — household workshop. Spouse stands at the workbench and
 * "tinkers"; produces a single stick (small repair scrap) per
 * cycle. Authoring polish (anvil clinks, hammer animations) is
 * future work; the framework loop ships now.
 */
public final class WorkshopHandler implements HomesteadHandler {

    private static final int WORK_TICKS = 90;

    @Override
    public boolean tick(Context ctx) {
        BlockPos target = AbstractHomesteadGoal.navTarget(ctx);
        if (ctx.tickInGoal() < 20) {
            ctx.npc().getNavigation().moveTo(
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                    ctx.walkSpeed());
        }
        if (ctx.tickInGoal() == WORK_TICKS) {
            ctx.npc().getPersonalInventory().addItem(new ItemStack(Items.STICK, 1));
        }
        return ctx.tickInGoal() >= WORK_TICKS;
    }
}
