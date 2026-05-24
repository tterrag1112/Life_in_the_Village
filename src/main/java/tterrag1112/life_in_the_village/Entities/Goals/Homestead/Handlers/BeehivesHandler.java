package tterrag1112.life_in_the_village.Entities.Goals.Homestead.Handlers;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.AbstractHomesteadGoal;
import tterrag1112.life_in_the_village.Entities.Goals.Homestead.HomesteadHandler;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;
import tterrag1112.life_in_the_village.Npc.Specialization.FarmerSpecialtyMultiplier;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;

/**
 * B2.6 — beehives. Slow cycle (honeybees in real life don't
 * yield often) — produces a honey bottle every ~3× the work-tick
 * window. Honeycomb on alternating cycles.
 *
 * <p>Phase 6.3.3.j.2 + j.5: awards +1 BEEKEEPING per cycle through
 * {@link SkillXp#award}. BEEKEEPING propagates 25% to ANIMAL_HUSBANDRY
 * which propagates 25% again to FARMING (per the hierarchical Skill
 * tree from 6.3.2.b), so a single grant surfaces all three layers.
 * FARMER_ANIMAL_FOCUS spec composes +50% on the BEEKEEPING grant.</p>
 */
public final class BeehivesHandler implements HomesteadHandler {

    private static final int WORK_TICKS = 160;
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
            // Phase 6.3.3.q.3 — building storage first, personal inv fallback.
            BuildingStorageAccess.storeWithFallback(
                    ctx.level(), ctx.parentHouse(),
                    new ItemStack(
                            (ctx.npc().tickCount % 2) == 0
                                    ? Items.HONEY_BOTTLE : Items.HONEYCOMB, 1),
                    ctx.npc().getPersonalInventory());
            float boosted = BASE_XP_PER_CYCLE
                    * FarmerSpecialtyMultiplier.of(ctx.npc(), Skill.BEEKEEPING);
            SkillXp.award(ctx.npc(), Skill.BEEKEEPING,
                    boosted, ctx.level().getGameTime());
        }
        return ctx.tickInGoal() >= WORK_TICKS;
    }
}
