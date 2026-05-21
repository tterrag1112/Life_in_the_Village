package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

import java.util.function.Predicate;

/**
 * Phase 6.3.3.h.5 — generic tool-use helper extracted from the
 * MinerBehavior pattern. Equips a matching tool from the entity's
 * personal inventory, applies vanilla durability damage, and clears
 * the slot when the tool breaks. Reusable by any profession that
 * consumes tool durability (farmer hoe, miner pickaxe, lumberjack
 * axe, blacksmith hammer — all forthcoming).
 *
 * <p>Tools that the entity doesn't carry simply produce a false
 * return: callers decide whether to fall through to a hand action,
 * goIdle, or route to a tool-acquisition phase.
 */
public final class ToolUseSupport {

    private ToolUseSupport() {}

    /**
     * Equips the first item in personal inventory matching
     * {@code predicate} to {@code hand} and damages it by 1.
     * If the tool breaks, the slot is cleared. Returns true when a
     * tool was used (regardless of whether it broke this call).
     */
    public static boolean useToolFromInventory(TownspersonMob entity,
                                                Predicate<ItemStack> predicate,
                                                ServerLevel level,
                                                InteractionHand hand) {
        SimpleContainer inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !predicate.test(stack)) continue;
            entity.setItemInHand(hand, stack);
            final int slot = i;
            stack.hurtAndBreak(1, level, null,
                    item -> inv.setItem(slot, ItemStack.EMPTY));
            return true;
        }
        return false;
    }

    /** Convenience overload — matches by tag. */
    public static boolean useToolFromInventory(TownspersonMob entity,
                                                TagKey<Item> tag,
                                                ServerLevel level,
                                                InteractionHand hand) {
        return useToolFromInventory(entity, s -> s.is(tag), level, hand);
    }

    /** Read-only probe — does the entity carry a matching tool right now? */
    public static boolean hasUsableTool(TownspersonMob entity,
                                         Predicate<ItemStack> predicate) {
        SimpleContainer inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && predicate.test(stack)) return true;
        }
        return false;
    }
}
