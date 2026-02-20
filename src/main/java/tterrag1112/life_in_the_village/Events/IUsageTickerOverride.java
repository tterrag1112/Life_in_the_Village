package tterrag1112.life_in_the_village.Events;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

public interface IUsageTickerOverride {

    default int getUsageTickerCountForItem(ItemStack stack, Predicate<ItemStack> target) {
        return 0;
    }

    default boolean shouldUsageTickerCheckMatchSize(ItemStack stack) {
        return false;
    }

    ItemStack getUsageTickerItem(ItemStack stack, RegistryAccess access);

}
