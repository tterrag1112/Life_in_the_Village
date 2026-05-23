package tterrag1112.life_in_the_village.Entities.Goals.Homestead.Handlers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.AbstractHomesteadGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.HomesteadHandler;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;
import tterrag1112.life_in_the_village.Npc.Specialization.FarmerSpecialtyMultiplier;

/**
 * B2.6 — household orchard. Walks among the trees, "harvests"
 * fruit per cycle.
 *
 * <p>Phase 6.3.3.j.2: awards +1 ORCHARDING per cycle (cascades to
 * CROP_FARMING then FARMING via the hierarchical Skill tree).
 * FARMER_CROP_FOCUS spec composes +50% on the ORCHARDING grant.</p>
 */
public final class OrchardHandler implements HomesteadHandler {

    private static final int WORK_TICKS = 120;
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
            ctx.npc().getPersonalInventory().addItem(new ItemStack(Items.APPLE, 1));
            float boosted = BASE_XP_PER_CYCLE
                    * FarmerSpecialtyMultiplier.of(ctx.npc(), Skill.ORCHARDING);
            SkillXp.award(ctx.npc(), Skill.ORCHARDING,
                    boosted, ctx.level().getGameTime());
        }
        return ctx.tickInGoal() >= WORK_TICKS;
    }
}
