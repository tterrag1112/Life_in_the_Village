// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Orders/CraftingOrderInteraction.java
package tterrag1112.life_in_the_village.Networking;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.CraftingOrder;
import tterrag1112.life_in_the_village.Village.Economy.CraftingOrderManager;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Player-facing side of the crafting-order system.
 *
 * <p>Track 4 removed the right-click chat-hint paths
 * ({@code showOrderHint} on leader interact, {@code showStockpileOrders}
 * on stockpile interact). The VillageBookScreen COMMISSIONS tab is now
 * the only player-facing surface for orders. What survives here is
 * the chat-board (used by {@code /order list}) and the
 * delivery callback wired into {@code BuildingStorageAccess}.</p>
 */
public final class CraftingOrderInteraction {

    private CraftingOrderInteraction() {}

    // =========================================================================
    // Order board (chat) — invoked by /order list
    // =========================================================================

    /**
     * Shows the full order board in chat with clickable [Claim]
     * buttons. Each claimable order has a clickable [Claim] button.
     */
    public static void showOrderBoard(ServerPlayer player,
                                      TownspersonMob leader,
                                      ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        Village village = leader.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .orElse(null);
        if (village == null) return;

        List<CraftingOrder> orders = data.getOrdersForVillage(village.getId())
                .stream()
                .filter(CraftingOrder::isActive)
                .toList();

        // Header
        player.displayClientMessage(
                Component.literal("═══ " + village.getName()
                                + " Commission Board ═══")
                        .withStyle(ChatFormatting.GOLD),
                false);

        if (orders.isEmpty()) {
            player.displayClientMessage(
                    Component.literal("  No open commissions at this time.")
                            .withStyle(ChatFormatting.GRAY),
                    false);
            return;
        }

        for (CraftingOrder order : orders) {
            Item item = resolveItem(order.getItemId());
            String itemName = item != null
                    ? item.getName().getString()
                    : order.getItemId().replace("minecraft:", "").replace("_", " ");

            String statusStr = order.isClaimed()
                    ? (order.isClaimedBy(player.getUUID())
                    ? " [YOUR ORDER " + order.getDeliveredCount() + "/" + order.getQuantity() + "]"
                    : " [CLAIMED]")
                    : "";

            MutableComponent row = Component.literal("  • ")
                    .withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(order.getQuantity() + "x " + itemName)
                            .withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" → ")
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(
                                    CurrencyValue.of(order.getBronzeReward()).toString())
                            .withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(statusStr)
                            .withStyle(ChatFormatting.GRAY));

            // Only show [Claim] if open and player hasn't already claimed an order here
            if (order.isOpen()) {
                boolean alreadyClaiming = orders.stream()
                        .anyMatch(o -> o.isClaimedBy(player.getUUID()));
                if (!alreadyClaiming) {
                    row = row.append(Component.literal(" [Claim]")
                            .withStyle(style -> style
                                    .withColor(ChatFormatting.GREEN)
                                    .withUnderlined(true)
                                    .withClickEvent(new ClickEvent.RunCommand(
                                            "/order claim " + order.getOrderId()))));
                }
            }

            player.displayClientMessage(row, false);
        }

        // Footer hint
        player.displayClientMessage(
                Component.literal(
                                "  Deliver items to any building chest in "
                                        + village.getName() + " to fulfil your order.")
                        .withStyle(ChatFormatting.DARK_GRAY),
                false);
    }

    // =========================================================================
    // Item delivery callback
    // Called from BuildingStorageAccess.storeItemFromPlayer
    // =========================================================================

    /**
     * Checks if the deposited item advances any active crafting order.
     * Should be called every time a player deposits items into a building
     * that belongs to a village.
     */
    public static void onItemsDeposited(ServerPlayer player,
                                        UUID villageId,
                                        String itemId,
                                        int count,
                                        ServerLevel level) {
        CraftingOrderManager.onItemDeposited(player, villageId, itemId, count, level);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Item resolveItem(String itemId) {
        try {
            return BuiltInRegistries.ITEM
                    .get(Identifier.parse(itemId))
                    .map(h -> h.value())
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}