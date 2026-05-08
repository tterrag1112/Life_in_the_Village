package tterrag1112.life_in_the_village.Entities.Goals.Homestead.Handlers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.AbstractHomesteadGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.HomesteadHandler;

/**
 * B2.6 — chicken coop handler. Walks to the plot, "tends" for
 * 100 ticks, then deposits a single egg into the NPC's inventory.
 * Production rate is intentionally lower than profession goals —
 * homesteads are family-scale, not commercial.
 *
 * <p>NBT-authoring will eventually add a feed trough and visible
 * coop block for visual polish; the handler ships the functional
 * loop now.</p>
 */
public final class ChickenCoopHandler implements HomesteadHandler {

    private static final int WORK_TICKS = 100;

    @Override
    public boolean tick(Context ctx) {
        BlockPos target = AbstractHomesteadGoal.navTarget(ctx);
        if (ctx.tickInGoal() < 20) {
            ctx.npc().getNavigation().moveTo(
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                    ctx.walkSpeed());
        }
        if (ctx.tickInGoal() == WORK_TICKS) {
            // Produce one egg per cycle into the NPC's personal
            // inventory; profession-economy logic deposits to
            // household storage on the regular schedule.
            ctx.npc().getPersonalInventory().addItem(new ItemStack(Items.EGG, 1));
        }
        return ctx.tickInGoal() >= WORK_TICKS;
    }
}
