// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Orders/CraftingOrderManager.java
package tterrag1112.life_in_the_village.Village.Economy;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.DataAttachments.ModData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.PlayerProfession;
import tterrag1112.life_in_the_village.Profession.PlayerProfessionData;
import tterrag1112.life_in_the_village.Profession.ProfessionEvents;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.VillageEconomy;
import tterrag1112.life_in_the_village.Village.Needs.NeedCategory;
import tterrag1112.life_in_the_village.Village.Needs.NeedLevel;
import tterrag1112.life_in_the_village.Village.Reputation.ReputationManager;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages the full lifecycle of {@link CraftingOrder} objects:
 * <ul>
 *   <li>Economy-driven posting when the village stockpile is short</li>
 *   <li>Player claiming via town hall interaction</li>
 *   <li>Item delivery detection when the player deposits to town hall</li>
 *   <li>Reward payment and profession XP on fulfilment</li>
 *   <li>Expiry tick sweep</li>
 * </ul>
 *
 * <h3>Integration points</h3>
 * <ul>
 *   <li>{@code ServerTickDispatcher} — call {@link #tick} every 20 ticks</li>
 *   <li>Town hall NPC interaction — call {@link #claimOrder}</li>
 *   <li>Town hall chest deposit event — call {@link #onItemDeposited}</li>
 *   <li>{@code VillageEconomy} / NPC building goals — call
 *       {@link #postOrderIfNeeded} when a building runs short</li>
 * </ul>
 */
public final class CraftingOrderManager {

    // How long (in ticks) an order stays on the board before expiring
    private static final long ORDER_DURATION = 24000L * 5; // 5 in-game days
    // How often the economy generates new orders (once per day)
    private static final long ECONOMY_POST_INTERVAL = 24000L;
    // Maximum number of open+claimed orders per village at any one time
    private static final int  MAX_ORDERS_PER_VILLAGE = 8;
    // Bronze reward per item — scaled by item type
    private static final long BASE_REWARD_PER_ITEM = 6L;

    private CraftingOrderManager() {}

    // =========================================================================
    // Server tick — called from ServerTickDispatcher every 20 ticks
    // =========================================================================

    public static void tick(ServerLevel level,
                            VillageSavedData data,
                            long currentTick) {
        // Expire overdue orders
        tickExpiry(data, currentTick);

        // Once per day, post new economy-driven orders for each village
        if (currentTick % ECONOMY_POST_INTERVAL == 0) {
            data.getAllVillages().forEach(village ->
                    tickEconomyOrders(level, village, data, currentTick));
        }
    }

    // =========================================================================
    // Economy-driven order generation
    // =========================================================================

    /**
     * Examines the village's current needs and posts orders for any items
     * that are short, up to {@link #MAX_ORDERS_PER_VILLAGE}.
     */
    public static void tickEconomyOrders(ServerLevel level,
                                         Village village,
                                         VillageSavedData data,
                                         long currentTick) {
        long activeCount = data.getOrdersForVillage(village.getId())
                .stream().filter(CraftingOrder::isActive).count();
        if (activeCount >= MAX_ORDERS_PER_VILLAGE) return;

        // Compute stockpile targets — this tells us what's short
        Map<Item, Integer> targets =
                VillageEconomy.computeStockpileTargets(level, village, data);
        if (targets.isEmpty()) return;

        // Find the stockpile to check actual stock
        var stockpileOpt = village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() == BuildingType.STOCKPILE)
                .findFirst();

        targets.forEach((item, targetQty) -> {
            if (activeCount >= MAX_ORDERS_PER_VILLAGE) return;

            // Check actual stock — only post if we're below half the target
            int actualStock = stockpileOpt
                    .map(stockpile -> BuildingStorageAccess
                            .countItem(level, stockpile, item))
                    .orElse(0);

            if (actualStock >= targetQty / 2) return;

            // Don't post a duplicate for the same item
            String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
            boolean alreadyPosted = data.getOrdersForVillage(village.getId())
                    .stream()
                    .anyMatch(o -> o.isActive() && o.getItemId().equals(itemId));
            if (alreadyPosted) return;

            int needed   = Math.max(4, (targetQty - actualStock) / 2);
            long reward  = calculateReward(item, needed, village, data);

            CraftingOrder order = CraftingOrder.create(
                    village.getId(),
                    new UUID(0, 0),   // economy-driven
                    itemId,
                    needed,
                    reward,
                    currentTick,
                    ORDER_DURATION);

            data.addCraftingOrder(order);

            System.out.println("CraftingOrderManager: posted order for "
                    + needed + "x " + itemId + " in " + village.getName()
                    + " (reward: " + reward + "b)");
        });
    }

    /**
     * Called by NPC building goals (blacksmith, stockpile keeper, etc.)
     * when their building runs short of a specific item.
     *
     * @param requestingNpcId  UUID of the NPC posting the order
     * @param villageId        village the order belongs to
     * @param itemId           namespaced item id
     * @param quantity         how many are needed
     */
    public static void postOrderIfNeeded(UUID requestingNpcId,
                                         UUID villageId,
                                         String itemId,
                                         int quantity,
                                         long currentTick,
                                         VillageSavedData data) {
        // Don't flood the board — one NPC posts one order per item type
        boolean alreadyPosted = data.getOrdersForVillage(villageId)
                .stream()
                .anyMatch(o -> o.isActive()
                        && o.getItemId().equals(itemId)
                        && o.getRequestingNpcId().equals(requestingNpcId));
        if (alreadyPosted) return;

        Item item = BuiltInRegistries.ITEM
                .get(Identifier.parse(itemId))
                .map(h -> h.value())
                .orElse(null);
        if (item == null || item == Items.AIR) return;

        Village village = data.getVillageById(villageId).orElse(null);
        long reward = village != null
                ? calculateReward(item, quantity, village, data)
                : quantity * BASE_REWARD_PER_ITEM;

        CraftingOrder order = CraftingOrder.create(
                villageId, requestingNpcId, itemId,
                quantity, reward, currentTick, ORDER_DURATION);

        data.addCraftingOrder(order);
    }

    // =========================================================================
    // Player claiming — called from town hall NPC interaction
    // =========================================================================

    /**
     * Claims an order for the given player. Returns true on success.
     * A player can only hold one claimed order per village at a time.
     */
    public static boolean claimOrder(ServerPlayer player,
                                     UUID orderId,
                                     ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        CraftingOrder order   = data.getCraftingOrderById(orderId).orElse(null);

        if (order == null || !order.isOpen()) {
            player.displayClientMessage(
                    Component.literal("This order is no longer available."), false);
            return false;
        }

        // One claimed order per village at a time
        boolean alreadyClaiming = data.getOrdersForVillage(order.getVillageId())
                .stream()
                .anyMatch(o -> o.isClaimedBy(player.getUUID()));
        if (alreadyClaiming) {
            player.displayClientMessage(
                    Component.literal("You already have an active order here. "
                            + "Deliver the items first."), false);
            return false;
        }

        order.claim(player.getUUID());
        data.markDirty();

        Item item = BuiltInRegistries.ITEM
                .get(Identifier.parse(order.getItemId()))
                .map(h -> h.value())
                .orElse(null);
        String itemName = item != null ? item.getName().getString() : order.getItemId();

        player.displayClientMessage(
                Component.literal("Order claimed: deliver " + order.getQuantity()
                        + "x " + itemName + " to the town hall chest.\n"
                        + "Reward: " + CurrencyValue.of(order.getBronzeReward())
                        + " + profession XP"),
                false);
        return true;
    }

    // =========================================================================
    // Item delivery — called when player deposits items into town hall chest
    // =========================================================================

    /**
     * Should be called whenever a player places items into a building's storage
     * via {@link BuildingStorageAccess}. Checks all claimed orders for this
     * player and village and advances matching ones.
     *
     * @param player     the depositing player
     * @param villageId  village the chest belongs to
     * @param itemId     namespaced id of the deposited item
     * @param count      how many were deposited
     * @param level      server level
     */
    public static void onItemDeposited(ServerPlayer player,
                                       UUID villageId,
                                       String itemId,
                                       int count,
                                       ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);

        data.getOrdersForVillage(villageId).stream()
                .filter(o -> o.isClaimedBy(player.getUUID())
                        && o.getItemId().equals(itemId))
                .forEach(order -> {
                    boolean fulfilled = order.deliver(count);
                    data.markDirty();

                    if (fulfilled) {
                        fulfillOrder(player, order, data, level);
                    } else {
                        player.displayClientMessage(
                                Component.literal("Order progress: "
                                        + order.getDeliveredCount()
                                        + "/" + order.getQuantity()
                                        + " " + itemId.replace("minecraft:", "")
                                        .replace("_", " ")),
                                true);
                    }
                });
    }

    // =========================================================================
    // Fulfilment — reward payment + XP
    // =========================================================================

    private static void fulfillOrder(ServerPlayer player,
                                     CraftingOrder order,
                                     VillageSavedData data,
                                     ServerLevel level) {
        // Pay the player
        CurrencyValue reward = CurrencyValue.of(order.getBronzeReward());
        var container = new net.minecraft.world.SimpleContainer(
                player.getInventory().getContainerSize());
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            container.setItem(i, player.getInventory().getItem(i).copy());
        }
        CoinHelper.giveCoins(container, reward);
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            player.getInventory().setItem(i, container.getItem(i));
        }
        player.inventoryMenu.broadcastChanges();

        // Award profession XP for the relevant profession
        Item item = BuiltInRegistries.ITEM
                .get(Identifier.parse(order.getItemId()))
                .map(h -> h.value())
                .orElse(null);
        if (item != null) {
            ItemStack stack = new ItemStack(item);
            PlayerProfessionData profData = player.getData(ModData.PROFESSION_DATA);
            for (PlayerProfession prof : PlayerProfession.values()) {
                if (prof.isRelevantCraft(stack)) {
                    ProfessionEvents.onJobPostingCompleted(
                            player, prof, order.getQuantity() * 2,
                            order.getVillageId());
                }
            }
        }

        // Reputation gain
        ReputationManager.onCraftingOrderFilled(player, order.getVillageId(), level);

        String itemName = item != null ? item.getName().getString()
                : order.getItemId().replace("minecraft:", "").replace("_", " ");

        player.displayClientMessage(
                Component.literal("✦ Order fulfilled: " + order.getQuantity()
                                + "x " + itemName + "\nReward: " + reward)
                        .withStyle(net.minecraft.ChatFormatting.GREEN),
                false);

        data.markDirty();
    }

    // =========================================================================
    // Expiry tick sweep
    // =========================================================================

    private static void tickExpiry(VillageSavedData data, long currentTick) {
        data.getAllCraftingOrders().stream()
                .filter(o -> o.isActive() && o.isExpired(currentTick))
                .forEach(o -> {
                    o.expire();
                    System.out.println("CraftingOrderManager: expired " + o);
                });

        if (data.getAllCraftingOrders().stream().anyMatch(o ->
                o.getStatus() == CraftingOrder.OrderStatus.EXPIRED
                        || o.getStatus() == CraftingOrder.OrderStatus.FULFILLED
                        || o.getStatus() == CraftingOrder.OrderStatus.CANCELLED)) {
            data.pruneCraftingOrders();
            data.markDirty();
        }
    }

    // =========================================================================
    // Reward calculation
    // =========================================================================

    /**
     * Calculates the coin reward for an order. Base rate scales with:
     * <ul>
     *   <li>Item rarity (diamond > iron > wood/food)</li>
     *   <li>Village need urgency — CRITICAL items pay more</li>
     *   <li>Quantity</li>
     * </ul>
     */
    private static long calculateReward(Item item,
                                        int quantity,
                                        Village village,
                                        VillageSavedData data) {
        long basePerItem = BASE_REWARD_PER_ITEM;

        // Item-based scaling
        String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
        if (itemId.contains("diamond") || itemId.contains("netherite")) {
            basePerItem = 32L;
        } else if (itemId.contains("gold") || itemId.contains("iron_sword")
                || itemId.contains("iron_pickaxe") || itemId.contains("iron_axe")) {
            basePerItem = 14L;
        } else if (itemId.contains("iron")) {
            basePerItem = 10L;
        } else if (itemId.contains("stone_brick") || itemId.contains("plank")) {
            basePerItem = 4L;
        }

        // Village need urgency multiplier
        double urgencyMult = 1.0;
        village.getNeeds().forEach((cat, need) -> {
            // This is a forEach so we accumulate; in practice food/material urgency drives it
        });

        NeedLevel foodNeed = village.getNeeds().containsKey(NeedCategory.FOOD)
                ? village.getNeeds().get(NeedCategory.FOOD).getLevel()
                : NeedLevel.SATISFIED;
        if (foodNeed == NeedLevel.CRITICAL
                && (itemId.contains("wheat") || itemId.contains("bread")
                || itemId.contains("carrot") || itemId.contains("potato"))) {
            basePerItem = (long)(basePerItem * 2.0);
        }

        return basePerItem * quantity;
    }
}