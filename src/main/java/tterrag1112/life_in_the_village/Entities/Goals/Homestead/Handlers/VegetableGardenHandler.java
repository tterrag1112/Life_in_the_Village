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
 * B2.6 — household vegetable garden. Walk + tend + drop a single
 * carrot or potato into the NPC's personal inventory per cycle.
 *
 * <p>Phase 6.3.3.j.2: awards +1 CROP_FARMING per cycle (cascades to
 * FARMING via the hierarchical Skill tree). FARMER_CROP_FOCUS spec
 * composes +50% on the CROP_FARMING grant.</p>
 */
public final class VegetableGardenHandler implements HomesteadHandler {

    private static final int WORK_TICKS = 110;
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
                            (ctx.npc().tickCount & 1) == 0 ? Items.CARROT : Items.POTATO, 1),
                    ctx.npc().getPersonalInventory());
            float boosted = BASE_XP_PER_CYCLE
                    * FarmerSpecialtyMultiplier.of(ctx.npc(), Skill.CROP_FARMING);
            SkillXp.award(ctx.npc(), Skill.CROP_FARMING,
                    boosted, ctx.level().getGameTime());
        }
        return ctx.tickInGoal() >= WORK_TICKS;
    }
}
