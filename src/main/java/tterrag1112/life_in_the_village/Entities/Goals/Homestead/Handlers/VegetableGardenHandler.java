package tterrag1112.life_in_the_village.Entities.Goals.Homestead.Handlers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.AbstractHomesteadGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.HomesteadHandler;

/**
 * B2.6 — household vegetable garden. Walk + tend + drop a single
 * carrot or potato into the NPC's personal inventory per cycle.
 */
public final class VegetableGardenHandler implements HomesteadHandler {

    private static final int WORK_TICKS = 110;

    @Override
    public boolean tick(Context ctx) {
        BlockPos target = AbstractHomesteadGoal.navTarget(ctx);
        if (ctx.tickInGoal() < 20) {
            ctx.npc().getNavigation().moveTo(
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                    ctx.walkSpeed());
        }
        if (ctx.tickInGoal() == WORK_TICKS) {
            // Alternate between carrot and potato by tick parity for
            // determinism; ItemFrame-style mixed harvest.
            ctx.npc().getPersonalInventory().addItem(new ItemStack(
                    (ctx.npc().tickCount & 1) == 0 ? Items.CARROT : Items.POTATO, 1));
        }
        return ctx.tickInGoal() >= WORK_TICKS;
    }
}
