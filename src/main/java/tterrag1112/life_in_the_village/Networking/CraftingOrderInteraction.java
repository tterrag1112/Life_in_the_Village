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
 * Handles the player-facing side of the crafting order system.
 *
 * <h3>Interaction model</h3>
 * <ul>
 *   <li><b>VILLAGE_LEADER normal interact</b> — opens the village book as usual,
 *       then sends a one-line hint if open orders exist.</li>
 *   <li><b>VILLAGE_LEADER sneak interact</b> — shows the full order board in
 *       chat with clickable claim buttons.</li>
 *   <li><b>STOCKPILE_KEEPER normal interact</b> — opens the stockpile screen
 *       as usual, then lists orders this NPC has posted.</li>
 *   <li><b>Delivery</b> — {@link #onItemsDeposited} is called from
 *       {@code BuildingStorageAccess.storeItemFromPlayer} whenever a player
 *       puts items into any building chest that belongs to a village.</li>
 * </ul>
 *
 * <h3>Claiming</h3>
 * Clickable claim buttons in chat send the command
 * {@code /order claim <orderId>} — register this command in
 * {@code ModModEvents.onRegisterCommands} to call
 * {@link CraftingOrderManager#claimOrder}.
 * Alternatively, a sneak+interact on the village leader with an order
 * already visible claims the first unclaimed order automatically.
 */
public final class CraftingOrderInteraction {

    private CraftingOrderInteraction() {}

    // =========================================================================
    // Called from TownspersonMob VILLAGE_LEADER interaction
    // =========================================================================

    /**
     * Normal interact — show a one-line hint if open orders exist.
     * Called alongside the existing VillageBookScreen packet.
     */
    public static void showOrderHint(ServerPlayer player,
                                     TownspersonMob leader,
                                     ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        Village village = leader.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .orElse(null);
        if (village == null) return;

        long openCount = data.getOrdersForVillage(village.getId())
                .stream()
                .filter(CraftingOrder::isOpen)
                .count();

        if (openCount == 0) return;

        player.displayClientMessage(
                Component.literal("[" + leader.getNpcName() + "] ")
                        .withStyle(ChatFormatting.YELLOW)
                        .append(Component.literal(
                                        "We have " + openCount + " open commission"
                                                + (openCount > 1 ? "s" : "")
                                                + " on the notice board. ")
                                .withStyle(ChatFormatting.WHITE))
                        .append(Component.literal("[View Orders]")
                                .withStyle(style -> style
                                        .withColor(ChatFormatting.AQUA)
                                        .withUnderlined(true)
                                        .withClickEvent(new ClickEvent.RunCommand("/order list")))),
                false);
    }

    /**
     * Sneak interact — show the full order board in chat.
     * Each claimable order has a clickable [Claim] button.
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
    // Called from TownspersonMob STOCKPILE_KEEPER interaction
    // =========================================================================

    /**
     * Shows the orders that this stockpile keeper has posted.
     * Called alongside the existing openStockpileScreen flow.
     */
    public static void showStockpileOrders(ServerPlayer player,
                                           TownspersonMob keeper,
                                           ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        UUID keeperUUID = keeper.getUUID();

        Village village = keeper.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .orElse(null);
        if (village == null) return;

        List<CraftingOrder> myOrders = data.getOrdersForVillage(village.getId())
                .stream()
                .filter(o -> o.isActive()
                        && o.getRequestingNpcId().equals(keeperUUID))
                .toList();

        if (myOrders.isEmpty()) return;

        player.displayClientMessage(
                Component.literal("[" + keeper.getNpcName()
                                + "] I need the following — bring them here:")
                        .withStyle(ChatFormatting.YELLOW),
                false);

        for (CraftingOrder order : myOrders) {
            Item item = resolveItem(order.getItemId());
            String itemName = item != null
                    ? item.getName().getString()
                    : order.getItemId().replace("minecraft:", "").replace("_", " ");

            String progressStr = order.isClaimed()
                    ? " (" + order.getDeliveredCount() + "/" + order.getQuantity() + " delivered)"
                    : "";

            MutableComponent row = Component.literal("  • ")
                    .withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(order.getRemainingCount() + "x " + itemName)
                            .withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" — ")
                            .withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(
                                    CurrencyValue.of(order.getBronzeReward()).toString())
                            .withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(progressStr)
                            .withStyle(ChatFormatting.GRAY));

            if (order.isOpen()) {
                boolean alreadyClaiming = data.getOrdersForVillage(village.getId())
                        .stream()
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