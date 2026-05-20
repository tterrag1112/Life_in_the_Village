package tterrag1112.life_in_the_village.Items;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import tterrag1112.life_in_the_village.Components.ModDataComponents;

import java.util.function.Consumer;

/**
 * Bound market-stall lease. Right-click a merchant or a stall block to
 * redeem (consumes the item, claims a slot). Per Q1, the lease is
 * bound to its purchaser — other players cannot redeem it.
 */
public class StallLeaseItem extends Item {

    public StallLeaseItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack,
                                TooltipContext context,
                                TooltipDisplay tooltipDisplay,
                                Consumer<Component> tooltipAdder,
                                TooltipFlag flag) {
        StallLeaseTerms terms = stack.get(ModDataComponents.STALL_LEASE_TERMS.get());
        if (terms == null) {
            tooltipAdder.accept(Component.literal("Unbound lease")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        tooltipAdder.accept(Component.literal(
                terms.isPurchase() ? "Permanent purchase" : "Lease")
                .withStyle(ChatFormatting.GOLD));
        if (!terms.isPurchase()) {
            long days = terms.durationTicks() / 24000L;
            tooltipAdder.accept(Component.literal("Duration: " + days + " day(s)")
                    .withStyle(ChatFormatting.GRAY));
        }
        terms.marketBuildingId().ifPresent(uuid ->
                tooltipAdder.accept(Component.literal("Bound to a specific market")
                        .withStyle(ChatFormatting.AQUA)));
        tooltipAdder.accept(Component.literal("Bound to original purchaser")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
