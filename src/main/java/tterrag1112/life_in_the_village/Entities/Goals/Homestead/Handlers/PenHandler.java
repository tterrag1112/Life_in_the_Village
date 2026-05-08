package tterrag1112.life_in_the_village.Entities.Goals.Homestead.Handlers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.AbstractHomesteadGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.HomesteadHandler;

/**
 * B2.6 — pig / sheep pen. Walk, tend, drop a single feather or
 * leather scrap. Distinct from B2.6's full ANIMAL_KEEPER profession
 * (out of scope) — this is the family-scale presence.
 */
public final class PenHandler implements HomesteadHandler {

    private static final int WORK_TICKS = 130;

    @Override
    public boolean tick(Context ctx) {
        BlockPos target = AbstractHomesteadGoal.navTarget(ctx);
        if (ctx.tickInGoal() < 20) {
            ctx.npc().getNavigation().moveTo(
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                    ctx.walkSpeed());
        }
        if (ctx.tickInGoal() == WORK_TICKS) {
            // Cycle alternates wool / leather; matches sheep + pig.
            ctx.npc().getPersonalInventory().addItem(new ItemStack(
                    (ctx.npc().tickCount % 3) == 0 ? Items.LEATHER : Items.WHITE_WOOL, 1));
        }
        return ctx.tickInGoal() >= WORK_TICKS;
    }
}
