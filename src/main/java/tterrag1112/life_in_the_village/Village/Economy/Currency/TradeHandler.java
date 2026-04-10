package tterrag1112.life_in_the_village.Village.Economy.Currency;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.OpenTradeScreenPacket;
import tterrag1112.life_in_the_village.Networking.TradeActionPacket;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.ProfessionEvents;
import tterrag1112.life_in_the_village.Profession.ProfessionPerkManager;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;
import tterrag1112.life_in_the_village.Village.Reputation.ReputationManager;
import tterrag1112.life_in_the_village.Village.Reputation.VillageReputation;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;
import java.util.stream.Collectors;

public class TradeHandler {

    // =========================================================================
    // Open trade screen
    // =========================================================================

    public static void openTradeScreen(ServerPlayer player,
                                       TownspersonMob merchant) {
        if (!(player.level() instanceof ServerLevel level)) return;

        VillageSavedData data = VillageSavedData.get(level);
        Building market = merchant.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .orElse(null);
        if (market == null) return;

        Optional<Village> village = merchant.getAssignedVillageName()
                .flatMap(name -> data.getVillageByName(name));

        // Reputation gate
        if (village.isPresent()) {
            VillageReputation.Tier tier = ReputationManager.getTier(
                    player, village.get().getId(), level);
            if (tier.isMerchantHostile()) {
                player.displayClientMessage(
                        Component.literal("[" + merchant.getNpcName()
                                + "] We don't do business with your kind here."),
                        false);
                return;
            }
        }

        // Collect stall chest positions so stock counts exclude other stalls
        Set<BlockPos> allStallChests = allStallChestPositions(data, market);

        List<TradeOffer> offers = new ArrayList<>();

        MarketPriceHelper.getAllExplicitPrices().forEach((item, basePrice) -> {
            long dynSell = village.map(v ->
                    DynamicPriceCalculator.getSellPrice(
                            level, v, data, item, basePrice.sellPrice())
            ).orElse(basePrice.sellPrice());

            long dynBuy = village.map(v ->
                    DynamicPriceCalculator.getBuyPrice(
                            level, v, data, item, basePrice.buyPrice())
            ).orElse(basePrice.buyPrice());

            long discountedSell = village.map(v ->
                    ReputationManager.applyDiscount(dynSell, player, v.getId(), level)
            ).orElse(dynSell);

            long boostedBuy = ProfessionPerkManager
                    .applyMerchantBuyPricePerk(player, dynBuy);

            // Kingdom law: TRADE_TARIFFS — reflect surcharge in displayed prices
            java.util.UUID vidForTariff = village.map(Village::getId).orElse(null);
            discountedSell = Math.round(discountedSell
                    * tterrag1112.life_in_the_village.Kingdom.KingdomLawEffects
                    .tradeBuyMultiplier(player, data, vidForTariff));
            boostedBuy = Math.round(boostedBuy
                    * tterrag1112.life_in_the_village.Kingdom.KingdomLawEffects
                    .tradeSellMultiplier(player, data, vidForTariff));

            // Stock = main chests + merchant's own stall (not other stalls)
            int stock = countMerchantStock(
                    level, market, item, merchant.getUUID(), data, allStallChests);
            boolean canBuy  = stock > 0;
            boolean canSell = player.getInventory()
                    .hasAnyMatching(s -> s.is(item));

            if (canBuy || canSell) {
                offers.add(new TradeOffer(item, discountedSell, boostedBuy,
                        canBuy, canSell, stock));
            }
        });

        // Use CoinHelper.getPlayerWealth — no snapshot needed
        long playerWealth = CoinHelper.getPlayerWealth(player).toBronze();

        PacketDistributor.sendToPlayer(player,
                new OpenTradeScreenPacket(merchant.getUUID(),
                        merchant.getNpcName(), offers, playerWealth));
    }

    // =========================================================================
    // Handle trade
    // =========================================================================

    public static void handleTrade(ServerPlayer player,
                                   TradeActionPacket packet) {
        if (!(player.level() instanceof ServerLevel level)) return;

        Item item = BuiltInRegistries.ITEM
                .get(packet.itemId())
                .map(h -> h.value())
                .orElse(null);
        if (item == null) return;

        // Locate merchant
        var entity = level.getEntity(packet.merchantId());
        if (!(entity instanceof TownspersonMob merchant)) {
            player.displayClientMessage(
                    Component.literal("Merchant not found."), true);
            return;
        }

        // Wandering trader fast-path
        if (merchant.getProfession()
                == tterrag1112.life_in_the_village.Profession.Profession.WANDERING_TRADER) {
            handleWanderingTrade(player, merchant, packet, level);
            return;
        }

        VillageSavedData data = VillageSavedData.get(level);
        Building market = merchant.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .orElse(null);
        if (market == null) return;

        Optional<Village> village = merchant.getAssignedVillageName()
                .flatMap(name -> data.getVillageByName(name));
        UUID villageId = village.map(Village::getId).orElse(null);

        // Reputation gate
        if (villageId != null) {
            VillageReputation.Tier tier = ReputationManager.getTier(
                    player, villageId, level);
            if (tier.isMerchantHostile()) {
                player.displayClientMessage(
                        Component.literal("This merchant won't trade with you."), true);
                return;
            }
        }

        MarketPriceData.ItemPrice basePrice = MarketPriceHelper.getOrDefaultPrice(item);


        int quantity = packet.quantity();

        // =====================================================================
        // PLAYER BUYING
        // =====================================================================
        if (packet.isBuying()) {

            Set<BlockPos> allStallChests = allStallChestPositions(data, market);

            // Prefer merchant's own stall as source, then main chest
            MarketStall sourceStall = findOwnedStallWithItem(
                    data, level, market.getId(), item, merchant.getUUID());

            int stock = sourceStall != null
                    ? countInStallChest(level, sourceStall, item)
                    : countMerchantStock(level, market, item,
                    merchant.getUUID(), data, allStallChests);

            quantity = Math.min(quantity, stock);
            if (quantity <= 0) {
                player.displayClientMessage(
                        Component.literal("Out of stock."), true);
                return;
            }

            // Price
            long rawPrice = village.map(v ->
                    DynamicPriceCalculator.getSellPrice(
                            level, v, data, item, basePrice.sellPrice())
            ).orElse(basePrice.sellPrice());

            long pricePerItem = villageId != null
                    ? ReputationManager.applyDiscount(rawPrice, player, villageId, level)
                    : rawPrice;

            // Kingdom law: TRADE_TARIFFS — outsiders pay a +20% surcharge
            double tariffBuy = tterrag1112.life_in_the_village.Kingdom.KingdomLawEffects
                    .tradeBuyMultiplier(player, data, villageId);
            pricePerItem = Math.round(pricePerItem * tariffBuy);

            // Afford check — uses getPlayerWealth, no snapshot
            quantity = Math.min(quantity,
                    (int)(CoinHelper.getPlayerWealth(player).toBronze()
                            / Math.max(1, pricePerItem)));
            if (quantity <= 0) {
                player.displayClientMessage(
                        Component.literal("Not enough coins."), true);
                return;
            }

            CurrencyValue totalCost = CurrencyValue.of(pricePerItem * quantity);

            // 1. Player pays — uses playerPay, handles denomination breaking
            if (!CoinHelper.playerPay(player, totalCost)) {
                player.displayClientMessage(
                        Component.literal("Payment failed."), true);
                return;
            }

            // 2. Take item from stall or main chest
            boolean taken;
            if (sourceStall != null) {
                taken = takeFromStallChest(level, sourceStall, item, quantity);
                if (taken) forwardPaymentToStallOwner(level, sourceStall,
                        totalCost, data);
                else merchant.getWallet().receive(totalCost); // refund if take failed
            } else {
                taken = BuildingStorageAccess.takeItem(
                        level, market, item, quantity);
                if (taken) {
                    merchant.getWallet().receive(totalCost);
                } else {
                    // Refund player if take failed
                    CoinHelper.playerReceive(player, totalCost);
                    player.displayClientMessage(
                            Component.literal("Failed to retrieve item."), true);
                    return;
                }
            }

            // 3. Give item to player
            ItemStack toGive = new ItemStack(item, quantity);
            if (!player.addItem(toGive)) player.drop(toGive, false);

            player.displayClientMessage(
                    Component.literal("Bought " + quantity + "x "
                            + item.getName().getString()
                            + " for " + formatPrice(totalCost)), true);

            // Reputation
            if (villageId != null) {
                ReputationManager.onTradeCompleted(player, villageId, level);
                if (ProfessionPerkManager.hasDoubleReputationTrade(player))
                    ReputationManager.onTradeCompleted(player, villageId, level);
            }

            // =====================================================================
            // PLAYER SELLING
            // =====================================================================
        } else {

            // Cap to what player actually has
            int playerHas = 0;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack s = player.getInventory().getItem(i);
                if (s.is(item)) playerHas += s.getCount();
            }
            quantity = Math.min(quantity, playerHas);
            if (quantity <= 0) return;

            // Price merchant pays player
            long rawBuyPrice = village.map(v ->
                    DynamicPriceCalculator.getBuyPrice(
                            level, v, data, item, basePrice.buyPrice())
            ).orElse(basePrice.buyPrice());

            long pricePerItem = ProfessionPerkManager
                    .applyMerchantBuyPricePerk(player, rawBuyPrice);

            // Kingdom law: TRADE_TARIFFS — outsiders receive 20% less
            double tariffSell = tterrag1112.life_in_the_village.Kingdom.KingdomLawEffects
                    .tradeSellMultiplier(player, data, villageId);
            pricePerItem = Math.round(pricePerItem * tariffSell);

            // Cap to merchant wealth
            long merchantWealth = merchant.getTotalWealth(level).toBronze();
            quantity = Math.min(quantity,
                    (int)(merchantWealth / Math.max(1, pricePerItem)));
            if (quantity <= 0) {
                player.displayClientMessage(
                        Component.literal("Merchant can't afford that."), true);
                return;
            }

            CurrencyValue totalEarned = CurrencyValue.of(pricePerItem * quantity);

            // 1. Take items from player
            int remaining = quantity;
            for (int i = 0; i < player.getInventory().getContainerSize()
                    && remaining > 0; i++) {
                ItemStack s = player.getInventory().getItem(i);
                if (!s.is(item)) continue;
                int take = Math.min(remaining, s.getCount());
                s.shrink(take);
                remaining -= take;
                if (s.isEmpty())
                    player.getInventory().setItem(i, ItemStack.EMPTY);
            }

            // 2. Items go into main market chest (never stall chests)
            storeExcludingStalls(level, market,
                    new ItemStack(item, quantity), data);

            // 3. Merchant spends, player receives — uses playerReceive
            merchant.getWallet().spend(totalEarned);
            CoinHelper.playerReceive(player, totalEarned);

            player.displayClientMessage(
                    Component.literal("Sold " + quantity + "x "
                            + item.getName().getString()
                            + " for " + formatPrice(totalEarned)), true);

            ProfessionEvents.onSellToNpc(
                    player, new ItemStack(item), quantity, villageId);
        }

        openTradeScreen(player, merchant);
    }

    // =========================================================================
    // Wandering trader
    // =========================================================================

    private static void handleWanderingTrade(ServerPlayer player,
                                             TownspersonMob trader,
                                             TradeActionPacket packet,
                                             ServerLevel level) {
        Item item = BuiltInRegistries.ITEM.get(packet.itemId())
                .map(h -> h.value()).orElse(null);
        if (item == null) return;

        MarketPriceData.ItemPrice basePrice = MarketPriceHelper.getOrDefaultPrice(item);


        if (packet.isBuying()) {
            int inInv = countInPersonalInventory(trader, item);
            int qty = Math.min(packet.quantity(), inInv);
            if (qty <= 0) {
                player.displayClientMessage(
                        Component.literal("Not in stock."), true);
                return;
            }

            long pricePerItem = basePrice.sellPrice();
            qty = Math.min(qty,
                    (int)(CoinHelper.getPlayerWealth(player).toBronze()
                            / Math.max(1, pricePerItem)));
            if (qty <= 0) {
                player.displayClientMessage(
                        Component.literal("Not enough coins."), true);
                return;
            }

            CurrencyValue cost = CurrencyValue.of(pricePerItem * qty);

            // playerPay handles denomination breaking cleanly
            if (!CoinHelper.playerPay(player, cost)) {
                player.displayClientMessage(
                        Component.literal("Payment failed."), true);
                return;
            }

            removeFromPersonalInventory(trader, item, qty);
            trader.getWallet().receive(cost);

            ItemStack give = new ItemStack(item, qty);
            if (!player.addItem(give)) player.drop(give, false);

            player.displayClientMessage(
                    Component.literal("Bought " + qty + "x "
                            + item.getName().getString()
                            + " for " + formatPrice(cost)), true);

        } else {
            int playerHas = 0;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack s = player.getInventory().getItem(i);
                if (s.is(item)) playerHas += s.getCount();
            }
            int qty = Math.min(packet.quantity(), playerHas);
            if (qty <= 0) return;

            long pricePerItem = basePrice.buyPrice();
            CurrencyValue earned = CurrencyValue.of(pricePerItem * qty);

            if (!trader.canAfford(earned)) {
                player.displayClientMessage(
                        Component.literal("Trader can't afford that."), true);
                return;
            }

            int remaining = qty;
            for (int i = 0; i < player.getInventory().getContainerSize()
                    && remaining > 0; i++) {
                ItemStack s = player.getInventory().getItem(i);
                if (!s.is(item)) continue;
                int take = Math.min(remaining, s.getCount());
                s.shrink(take);
                remaining -= take;
            }

            trader.getPersonalInventory().addItem(new ItemStack(item, qty));
            trader.getWallet().spend(earned);
            CoinHelper.playerReceive(player, earned);

            player.displayClientMessage(
                    Component.literal("Sold " + qty + "x "
                            + item.getName().getString()
                            + " for " + formatPrice(earned)), true);
        }
    }

    // =========================================================================
    // Stall helpers
    // =========================================================================

    private static void forwardPaymentToStallOwner(ServerLevel level,
                                                   MarketStall stall,
                                                   CurrencyValue amount,
                                                   VillageSavedData data) {
        if (stall.getOwnerType() == MarketStall.OwnerType.NPC) {
            level.getEntitiesOfClass(
                            TownspersonMob.class,
                            new net.minecraft.world.phys.AABB(
                                    stall.getChestPos()).inflate(128),
                            mob -> mob.getUUID().equals(stall.getOwnerUUID()))
                    .stream().findFirst()
                    .ifPresent(owner -> owner.getWallet().receive(amount));
        } else {
            // Player-owned: deposit coins into stall chest
            BlockEntity be = level.getBlockEntity(stall.getChestPos());
            if (be instanceof net.minecraft.world.Container chest) {
                net.minecraft.world.SimpleContainer temp =
                        new net.minecraft.world.SimpleContainer(9);
                CoinHelper.giveCoins(temp, amount);
                for (int i = 0; i < temp.getContainerSize(); i++) {
                    ItemStack coin = temp.getItem(i);
                    if (coin.isEmpty()) continue;
                    for (int ci = 0; ci < chest.getContainerSize(); ci++) {
                        if (chest.getItem(ci).isEmpty()) {
                            chest.setItem(ci, coin.copy());
                            break;
                        }
                    }
                }
            }
        }
    }

    private static MarketStall findOwnedStallWithItem(VillageSavedData data,
                                                      ServerLevel level,
                                                      UUID marketId,
                                                      Item item,
                                                      UUID ownerUUID) {
        for (MarketStall stall : data.getStallsForMarket(marketId)) {
            if (!stall.isActive()) continue;
            if (!stall.getOwnerUUID().equals(ownerUUID)) continue;
            if (stall.getChestPos().equals(BlockPos.ZERO)) continue;
            BlockEntity be = level.getBlockEntity(stall.getChestPos());
            if (!(be instanceof net.minecraft.world.Container chest)) continue;
            for (int i = 0; i < chest.getContainerSize(); i++) {
                if (chest.getItem(i).is(item)) return stall;
            }
        }
        return null;
    }

    private static boolean takeFromStallChest(ServerLevel level,
                                              MarketStall stall,
                                              Item item, int qty) {
        BlockEntity be = level.getBlockEntity(stall.getChestPos());
        if (!(be instanceof net.minecraft.world.Container chest)) return false;
        int remaining = qty;
        for (int i = 0; i < chest.getContainerSize() && remaining > 0; i++) {
            ItemStack s = chest.getItem(i);
            if (!s.is(item)) continue;
            int take = Math.min(remaining, s.getCount());
            s.shrink(take);
            remaining -= take;
            if (s.isEmpty()) chest.setItem(i, ItemStack.EMPTY);
        }
        return remaining == 0;
    }

    private static int countInStallChest(ServerLevel level,
                                         MarketStall stall, Item item) {
        BlockEntity be = level.getBlockEntity(stall.getChestPos());
        if (!(be instanceof net.minecraft.world.Container chest)) return 0;
        int count = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack s = chest.getItem(i);
            if (s.is(item)) count += s.getCount();
        }
        return count;
    }

    private static int countMerchantStock(ServerLevel level,
                                          Building market,
                                          Item item,
                                          UUID merchantUUID,
                                          VillageSavedData data,
                                          Set<BlockPos> allStallChests) {
        int count = 0;
        for (net.minecraft.world.Container inv
                : BuildingStorageAccess.findInventories(level, market)) {
            if (inv instanceof BlockEntity be
                    && allStallChests.contains(be.getBlockPos())) continue;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (s.is(item)) count += s.getCount();
            }
        }
        // Add merchant's own stall
        for (MarketStall stall : data.getStallsForMarket(market.getId())) {
            if (!stall.isActive()) continue;
            if (!stall.getOwnerUUID().equals(merchantUUID)) continue;
            if (stall.getChestPos().equals(BlockPos.ZERO)) continue;
            count += countInStallChest(level, stall, item);
        }
        return count;
    }

    private static Set<BlockPos> allStallChestPositions(VillageSavedData data,
                                                        Building market) {
        return data.getStallsForMarket(market.getId()).stream()
                .filter(s -> s.isActive()
                        && !s.getChestPos().equals(BlockPos.ZERO))
                .map(MarketStall::getChestPos)
                .collect(Collectors.toSet());
    }

    private static void storeExcludingStalls(ServerLevel level,
                                             Building market,
                                             ItemStack stack,
                                             VillageSavedData data) {
        Set<BlockPos> stallChests = allStallChestPositions(data, market);
        for (net.minecraft.world.Container inv
                : BuildingStorageAccess.findInventories(level, market)) {
            if (stack.isEmpty()) break;
            if (inv instanceof BlockEntity be
                    && stallChests.contains(be.getBlockPos())) continue;
            for (int i = 0; i < inv.getContainerSize() && !stack.isEmpty(); i++) {
                ItemStack ex = inv.getItem(i);
                if (ex.is(stack.getItem())
                        && ex.getCount() < ex.getMaxStackSize()) {
                    int add = Math.min(ex.getMaxStackSize() - ex.getCount(),
                            stack.getCount());
                    ex.grow(add);
                    stack.shrink(add);
                }
            }
            for (int i = 0; i < inv.getContainerSize() && !stack.isEmpty(); i++) {
                if (inv.getItem(i).isEmpty()) {
                    inv.setItem(i, stack.copy());
                    stack.setCount(0);
                }
            }
        }
    }

    // =========================================================================
    // Personal inventory helpers (wandering trader)
    // =========================================================================

    private static int countInPersonalInventory(TownspersonMob npc, Item item) {
        int count = 0;
        var inv = npc.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) count += s.getCount();
        }
        return count;
    }

    private static void removeFromPersonalInventory(TownspersonMob npc,
                                                    Item item, int qty) {
        var inv = npc.getPersonalInventory();
        int remaining = qty;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(item)) continue;
            int take = Math.min(remaining, s.getCount());
            s.shrink(take);
            remaining -= take;
            if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
        }
    }

    // =========================================================================
    // Formatting
    // =========================================================================

    public static String formatPrice(CurrencyValue value) {
        long bronze = value.toBronze();
        long gold   = bronze / CurrencyValue.GOLD_VALUE;
        long silver = (bronze % CurrencyValue.GOLD_VALUE) / CurrencyValue.SILVER_VALUE;
        long b      = bronze % CurrencyValue.SILVER_VALUE;
        StringBuilder sb = new StringBuilder();
        if (gold   > 0) sb.append(gold).append("g ");
        if (silver > 0) sb.append(silver).append("s ");
        if (b > 0 || sb.isEmpty()) sb.append(b).append("b");
        return sb.toString().trim();
    }

    public static String formatPrice(long bronze) {
        return formatPrice(CurrencyValue.of(bronze));
    }
}