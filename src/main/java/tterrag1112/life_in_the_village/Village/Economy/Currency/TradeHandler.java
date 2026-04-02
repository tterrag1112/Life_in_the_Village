// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Currency/TradeHandler.java
package tterrag1112.life_in_the_village.Village.Economy.Currency;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.OpenTradeScreenPacket;
import tterrag1112.life_in_the_village.Networking.TradeActionPacket;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.ProfessionEvents;
import tterrag1112.life_in_the_village.Profession.ProfessionPerkManager;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Reputation.ReputationManager;
import tterrag1112.life_in_the_village.Village.Reputation.VillageReputation;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

        // ── Reputation: OUTCAST players cannot trade ──────────────────────────
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

        MarketPriceData priceData = MarketPriceRegistry.INSTANCE.getDefault();
        List<TradeOffer> offers   = new ArrayList<>();

        priceData.getAllPrices().forEach((item, basePrice) -> {
            // Dynamic price from supply/demand
            long dynSell = village.map(v ->
                    DynamicPriceCalculator.getSellPrice(
                            level, v, data, item, basePrice.sellPrice())
            ).orElse(basePrice.sellPrice());

            long dynBuy = village.map(v ->
                    DynamicPriceCalculator.getBuyPrice(
                            level, v, data, item, basePrice.buyPrice())
            ).orElse(basePrice.buyPrice());

            // Apply reputation discount to the sell price shown on screen
            long discountedSell = village.map(v ->
                    ReputationManager.applyDiscount(dynSell, player, v.getId(), level)
            ).orElse(dynSell);

            // Apply MERCHANT_BETTER_PRICES perk to the buy price shown on screen
            long boostedBuy = ProfessionPerkManager
                    .applyMerchantBuyPricePerk(player, dynBuy);

            boolean canBuy  = BuildingStorageAccess.hasItem(level, market, item, 1);
            boolean canSell = player.getInventory().hasAnyMatching(s -> s.is(item));
            int     stock   = BuildingStorageAccess.countItem(level, market, item);

            if (canBuy || canSell) {
                offers.add(new TradeOffer(item, discountedSell, boostedBuy,
                        canBuy, canSell, stock));
            }
        });

        long playerWealth = CoinHelper.getWealth(
                new net.minecraft.world.SimpleContainer(
                        player.getInventory().getContainerSize()
                ) {{
                    for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                        setItem(i, player.getInventory().getItem(i));
                    }
                }}
        ).toBronze();

        PacketDistributor.sendToPlayer(player,
                new OpenTradeScreenPacket(merchant.getUUID(),
                        merchant.getNpcName(), offers, playerWealth));
    }

    // =========================================================================
    // Handle trade (server-side, called from TradeActionPacket)
    // =========================================================================

    public static void handleTrade(ServerPlayer player,
                                   TradeActionPacket packet) {
        if (!(player.level() instanceof ServerLevel level)) return;

        Item item = BuiltInRegistries.ITEM
                .get(packet.itemId())
                .map(h -> h.value())
                .orElse(null);
        if (item == null) return;

        // Find merchant
        TownspersonMob merchant = level.getEntitiesOfClass(
                TownspersonMob.class,
                player.getBoundingBox().inflate(32),
                mob -> mob.getUUID().equals(packet.merchantId())
        ).stream().findFirst().orElse(null);

        if (merchant == null) {
            player.displayClientMessage(
                    Component.literal("Merchant not found."), true);
            return;
        }

        VillageSavedData data = VillageSavedData.get(level);
        Building market = merchant.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .orElse(null);
        if (market == null) return;

        Optional<Village> village = merchant.getAssignedVillageName()
                .flatMap(name -> data.getVillageByName(name));

        // Resolve village ID once for reputation hooks below
        UUID villageId = village.map(Village::getId).orElse(null);

        // ── Second reputation gate (in case screen was already open) ─────────
        if (villageId != null) {
            VillageReputation.Tier tier = ReputationManager.getTier(
                    player, villageId, level);
            if (tier.isMerchantHostile()) {
                player.displayClientMessage(
                        Component.literal("This merchant won't trade with you."), true);
                return;
            }
        }

        MarketPriceData priceData = MarketPriceRegistry.INSTANCE.getDefault();
        MarketPriceData.ItemPrice basePrice = priceData.getPrice(item).orElse(null);
        if (basePrice == null) return;

        int quantity = packet.quantity();

        // =========================================================================
        // PLAYER BUYING from market
        // =========================================================================
        if (packet.isBuying()) {
            int stock = BuildingStorageAccess.countItem(level, market, item);
            quantity = Math.min(quantity, stock);

            // Dynamic price
            long rawPrice = village.map(v ->
                    DynamicPriceCalculator.getSellPrice(
                            level, v, data, item, basePrice.sellPrice())
            ).orElse(basePrice.sellPrice());

            // Apply reputation discount on top of dynamic price
            long pricePerItem = villageId != null
                    ? ReputationManager.applyDiscount(rawPrice, player, villageId, level)
                    : rawPrice;

            var playerContainer = buildPlayerContainer(player);
            long playerWealth   = CoinHelper.getWealth(playerContainer).toBronze();
            int  canAfford      = (int)(playerWealth / pricePerItem);
            quantity = Math.min(quantity, canAfford);

            if (quantity <= 0) {
                player.displayClientMessage(
                        Component.literal("Not enough coins."), true);
                return;
            }

            CurrencyValue totalCost = CurrencyValue.of(pricePerItem * quantity);

            // 1. Pay merchant
            CoinHelper.pay(playerContainer, merchant.getPersonalInventory(), totalCost);
            // 2. Sync coins
            syncPlayerCoins(player, playerContainer);
            // 3. Take from market
            boolean taken = BuildingStorageAccess.takeItem(level, market, item, quantity);
            if (!taken) {
                player.displayClientMessage(
                        Component.literal("Failed to retrieve item from market."), true);
                return;
            }
            // 4. Give item to player
            ItemStack toGive = new ItemStack(item, quantity);
            if (!player.addItem(toGive)) {
                player.drop(toGive, false);
            }

            player.displayClientMessage(
                    Component.literal("Bought " + quantity + "x "
                            + item.getName().getString()
                            + " for " + formatPrice(totalCost)), true);

            // ── Reputation: buying counts as trading ──────────────────────────
            if (villageId != null) {
                ReputationManager.onTradeCompleted(player, villageId, level);
                // MERCHANT_REPUTATION_TRADER perk doubles the rep gain
                if (ProfessionPerkManager.hasDoubleReputationTrade(player)) {
                    ReputationManager.onTradeCompleted(player, villageId, level);
                }
            }

            // =========================================================================
            // PLAYER SELLING to market
            // =========================================================================
        } else {
            // Cap to what player has
            int playerHas = 0;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack s = player.getInventory().getItem(i);
                if (s.is(item)) playerHas += s.getCount();
            }
            quantity = Math.min(quantity, playerHas);

            // Dynamic buy price (what merchant pays player)
            long rawBuyPrice = village.map(v ->
                    DynamicPriceCalculator.getBuyPrice(
                            level, v, data, item, basePrice.buyPrice())
            ).orElse(basePrice.buyPrice());

            // MERCHANT_BETTER_PRICES perk: +10% buy price
            long pricePerItem = ProfessionPerkManager
                    .applyMerchantBuyPricePerk(player, rawBuyPrice);

            // Cap to what merchant can afford
            long merchantWealth    = merchant.getTotalWealth(level).toBronze();
            int  merchantCanAfford = (int)(merchantWealth / pricePerItem);
            quantity = Math.min(quantity, merchantCanAfford);

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
            }

            // 2. Deposit item into market storage
            BuildingStorageAccess.storeItem(level, market,
                    new ItemStack(item, quantity));

            // 3. Pay player from merchant's personal inventory
            var playerContainer = buildPlayerContainer(player);
            CoinHelper.payWithBuilding(playerContainer, totalEarned, level, market);
            syncPlayerCoins(player, playerContainer);

            player.displayClientMessage(
                    Component.literal("Sold " + quantity + "x "
                            + item.getName().getString()
                            + " for " + formatPrice(totalEarned)), true);

            // ── XP + reputation on sell ────────────────────────────────────────
            ProfessionEvents.onSellToNpc(
                    player, new ItemStack(item), quantity, villageId);
        }

        // Refresh the trade screen with updated stock/prices
        openTradeScreen(player, merchant);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static net.minecraft.world.SimpleContainer buildPlayerContainer(
            ServerPlayer player) {
        return CoinHelper.snapshotInventory(player);
    }

    private static void syncPlayerCoins(ServerPlayer player,
                                        net.minecraft.world.SimpleContainer container) {
        CoinHelper.syncInventory(player, container);
    }

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
}