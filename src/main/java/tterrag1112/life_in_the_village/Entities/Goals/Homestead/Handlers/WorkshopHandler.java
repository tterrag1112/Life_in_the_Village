package tterrag1112.life_in_the_village.Entities.Goals.Homestead.Handlers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.AbstractHomesteadGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.HomesteadHandler;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;

/**
 * B2.6 — household workshop. Spouse stands at the workbench and
 * "tinkers"; produces a single stick (small repair scrap) per
 * cycle.
 *
 * <p>Phase 6.3.3.j.2: awards +1 CRAFTING per cycle through
 * {@link SkillXp#award}. No farmer specialty multiplier applies to
 * CRAFTING (workshop is the family-scale crafting parallel of the
 * agriculture handlers); a future BlacksmithSpecialtyMultiplier can
 * compose here if a workshop worker has a smithing focus.</p>
 */
public final class WorkshopHandler implements HomesteadHandler {

    private static final int WORK_TICKS = 90;
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
            ctx.npc().getPersonalInventory().addItem(new ItemStack(Items.STICK, 1));
            SkillXp.award(ctx.npc(), Skill.CRAFTING,
                    BASE_XP_PER_CYCLE, ctx.level().getGameTime());
        }
        return ctx.tickInGoal() >= WORK_TICKS;
    }
}
