package tterrag1112.life_in_the_village.Entities.Goals.Homestead.Handlers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.AbstractHomesteadGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.HomesteadHandler;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;

/**
 * B2.6 — woodshed. Walks to the stockpile, "splits logs," yields
 * a stack of oak planks per cycle.
 *
 * <p>Phase 6.3.3.j.2: awards +1 CRAFTING per cycle through
 * {@link SkillXp#award}.</p>
 */
public final class WoodshedHandler implements HomesteadHandler {

    private static final int WORK_TICKS = 140;
    private static final float BASE_XP_PER_CYCLE = 1f;

    @Override
    public boolean tick(Context ctx) {
        BlockPos target = AbstractHomesteadGoal.navTarget(ctx);
        if (ctx.tickInGoal() < 20) {
            ctx.npc().getNavigation().moveTo(
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                    ctx.walkSpeed());
        }
        if (ctx.tickInGoal() == WORK_TICKS) {
            // Two planks per cycle — a single log split.
            ctx.npc().getPersonalInventory().addItem(new ItemStack(Items.OAK_PLANKS, 2));
            SkillXp.award(ctx.npc(), Skill.CRAFTING,
                    BASE_XP_PER_CYCLE, ctx.level().getGameTime());
        }
        return ctx.tickInGoal() >= WORK_TICKS;
    }
}
