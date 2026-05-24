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
 * B2.6 — chicken coop handler. Walks to the plot, "tends" for
 * 100 ticks, then deposits a single egg into the NPC's inventory.
 * Production rate is intentionally lower than profession goals —
 * homesteads are family-scale, not commercial.
 *
 * <p>Phase 6.3.3.j.2: awards +1 ANIMAL_HUSBANDRY per cycle through
 * {@link SkillXp#award} so the mentee mentorship multiplier composes;
 * specialty multiplier from {@link FarmerSpecialtyMultiplier} folds
 * in beforehand so a FARMER_ANIMAL_FOCUS spouse / elderly farmer at
 * the homestead earns the +50% bonus too.</p>
 */
public final class ChickenCoopHandler implements HomesteadHandler {

    private static final int WORK_TICKS = 100;
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
            // Phase 6.3.3.q.3 — try farmhouse storage first; fall back
            // to personal inventory when no chest is placed yet.
            BuildingStorageAccess.storeWithFallback(
                    ctx.level(), ctx.parentHouse(),
                    new ItemStack(Items.EGG, 1),
                    ctx.npc().getPersonalInventory());
            float boosted = BASE_XP_PER_CYCLE
                    * FarmerSpecialtyMultiplier.of(ctx.npc(), Skill.ANIMAL_HUSBANDRY);
            SkillXp.award(ctx.npc(), Skill.ANIMAL_HUSBANDRY,
                    boosted, ctx.level().getGameTime());
        }
        return ctx.tickInGoal() >= WORK_TICKS;
    }
}
