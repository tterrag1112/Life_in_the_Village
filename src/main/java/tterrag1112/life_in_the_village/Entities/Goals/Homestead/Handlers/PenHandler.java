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
 * B2.6 — pig / sheep pen. Walk, tend, drop a single feather or
 * leather scrap. Distinct from B2.6's full ANIMAL_KEEPER profession
 * (out of scope) — this is the family-scale presence.
 *
 * <p>Phase 6.3.3.j.2: awards +1 ANIMAL_HUSBANDRY per cycle through
 * {@link SkillXp#award} with farmer specialty + mentorship multipliers
 * composing automatically.</p>
 */
public final class PenHandler implements HomesteadHandler {

    private static final int WORK_TICKS = 130;
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
            ctx.npc().getPersonalInventory().addItem(new ItemStack(
                    (ctx.npc().tickCount % 3) == 0 ? Items.LEATHER : Items.WHITE_WOOL, 1));
            float boosted = BASE_XP_PER_CYCLE
                    * FarmerSpecialtyMultiplier.of(ctx.npc(), Skill.ANIMAL_HUSBANDRY);
            SkillXp.award(ctx.npc(), Skill.ANIMAL_HUSBANDRY,
                    boosted, ctx.level().getGameTime());
        }
        return ctx.tickInGoal() >= WORK_TICKS;
    }
}
