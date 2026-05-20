package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;

/**
 * Mirrors {@code CARRYING_DISPLAY_ITEM} into the entity's visible
 * carry slot (a synced display field, separate from the real
 * inventory). Runs in CORE so it stays consistent across activity
 * transitions.
 *
 * <p>Animation / display-state only — no inventory mutation, no
 * movement, no head rotation.
 */
public class CarryHoldAnimationBehavior extends Behavior<TownspersonMob> {

    public CarryHoldAnimationBehavior() {
        super(ImmutableMap.of(
                NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get(), MemoryStatus.REGISTERED
        ), Integer.MAX_VALUE);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        return true;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        applyDisplay(entity);
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        applyDisplay(entity);
    }

    private static void applyDisplay(TownspersonMob entity) {
        ItemStack stack = entity.getBrain()
                .getMemory(NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get())
                .orElse(ItemStack.EMPTY);
        entity.setCarryDisplayItem(stack);
    }
}
