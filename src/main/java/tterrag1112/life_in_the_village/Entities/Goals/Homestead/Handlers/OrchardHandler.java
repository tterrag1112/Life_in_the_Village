package tterrag1112.life_in_the_village.Entities.Goals.Homestead.Handlers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.AbstractHomesteadGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.HomesteadHandler;

/**
 * B2.6 — household orchard. Walks among the trees, "harvests"
 * fruit per cycle. Apples ship as the default fruit (vanilla has
 * no other tree-fruit item without modded crops); a mixed orchard
 * with cherries / sweet berries requires per-block detection
 * which is future work.
 */
public final class OrchardHandler implements HomesteadHandler {

    private static final int WORK_TICKS = 120;

    @Override
    public boolean tick(Context ctx) {
        BlockPos target = AbstractHomesteadGoal.navTarget(ctx);
        if (ctx.tickInGoal() < 20) {
            ctx.npc().getNavigation().moveTo(
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                    ctx.walkSpeed());
        }
        if (ctx.tickInGoal() == WORK_TICKS) {
            ctx.npc().getPersonalInventory().addItem(new ItemStack(Items.APPLE, 1));
        }
        return ctx.tickInGoal() >= WORK_TICKS;
    }
}
