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
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class TradeHandler {

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

        MarketPriceData priceData = MarketPriceRegistry.INSTANCE.getDefault();
        List<TradeOffer> offers = new ArrayList<>();

        priceData.getAllPrices().forEach((item, basePrice) -> {
            long dynSell = village.map(v ->
                    DynamicPriceCalculator.getSellPrice(
                            level, v, data, item, basePrice.sellPrice())
            ).orElse(basePrice.sellPrice());

            long dynBuy = village.map(v ->
                    DynamicPriceCalculator.getBuyPrice(
                            level, v, data, item, basePrice.buyPrice())
            ).orElse(basePrice.buyPrice());

            // Can buy if merchant has stock
            boolean canBuy = BuildingStorageAccess.hasItem(
                    level, market, item, 1);

            // Can sell if player has item
            boolean canSell = player.getInventory().hasAnyMatching(
                    s -> s.is(item));

            int stock = BuildingStorageAccess.countItem(level, market, item);


            if (canBuy || canSell) {
                offers.add(new TradeOffer(item, dynSell, dynBuy,
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

        MarketPriceData priceData = MarketPriceRegistry.INSTANCE.getDefault();
        MarketPriceData.ItemPrice basePrice = priceData.getPrice(item)
                .orElse(null);
        if (basePrice == null) return;

        int quantity = packet.quantity();


        if (packet.isBuying()) {
            int stock = BuildingStorageAccess.countItem(level, market, item);
            quantity = Math.min(quantity, stock);

            long pricePerItem = village.map(v ->
                    DynamicPriceCalculator.getSellPrice(
                            level, v, data, item, basePrice.sellPrice())
            ).orElse(basePrice.sellPrice());

            var playerContainer = buildPlayerContainer(player);
            long playerWealth = CoinHelper.getWealth(playerContainer).toBronze();
            int canAfford = (int)(playerWealth / pricePerItem);
            quantity = Math.min(quantity, canAfford);

            if (quantity <= 0) {
                player.displayClientMessage(
                        Component.literal("Not enough coins."), true);
                return;
            }

            CurrencyValue totalCost = CurrencyValue.of(pricePerItem * quantity);

            // 1. Pay merchant first — modifies playerContainer
            CoinHelper.pay(playerContainer, merchant.getPersonalInventory(), totalCost);

            // 2. Sync coins back to player inventory
            syncPlayerCoins(player, playerContainer);

            // 3. Take from market
            boolean taken = BuildingStorageAccess.takeItem(level, market, item, quantity);
            if (!taken) {
                player.displayClientMessage(
                        Component.literal("Failed to retrieve item from market."), true);
                return;
            }

            // 4. Give item to player AFTER sync so it doesn't get overwritten
            ItemStack toGive = new ItemStack(item, quantity);
            if (!player.addItem(toGive)) {
                player.drop(toGive, false);
            }

            player.displayClientMessage(
                    Component.literal("Bought " + quantity + "x "
                            + item.getName().getString()
                            + " for " + formatPrice(totalCost)), true);

        } else {
            // Cap quantity to what player has
            int playerHas = 0;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack s = player.getInventory().getItem(i);
                if (s.is(item)) playerHas += s.getCount();
            }
            quantity = Math.min(quantity, playerHas);

            long pricePerItem = village.map(v ->
                    DynamicPriceCalculator.getBuyPrice(
                            level, v, data, item, basePrice.buyPrice())
            ).orElse(basePrice.buyPrice());

            // Cap to what merchant can afford
            long merchantWealth = merchant.getTotalWealth(level).toBronze();
            int merchantCanAfford = (int)(merchantWealth / pricePerItem);
            quantity = Math.min(quantity, merchantCanAfford);

            if (quantity <= 0) {
                player.displayClientMessage(
                        Component.literal("Merchant can't afford that."), true);
                return;
            }

            CurrencyValue totalPayment = CurrencyValue.of(pricePerItem * quantity);

            // Remove items from player
            int remaining = quantity;
            for (int i = 0; i < player.getInventory().getContainerSize()
                    && remaining > 0; i++) {
                ItemStack s = player.getInventory().getItem(i);
                if (s.is(item)) {
                    int take = Math.min(remaining, s.getCount());
                    s.shrink(take);
                    remaining -= take;
                    if (s.isEmpty()) player.getInventory().setItem(i, ItemStack.EMPTY);
                }
            }

            BuildingStorageAccess.storeItem(level, market,
                    new ItemStack(item, quantity));
            var playerContainer = buildPlayerContainer(player);

// Try personal inventory first
            if (merchant.canAfford(totalPayment)) {
                CoinHelper.pay(merchant.getPersonalInventory(), playerContainer, totalPayment);
            } else {
                // Pull from building storage
                long remainingL = totalPayment.toBronze();

                // Use whatever is in personal inventory first
                long personalWealth = merchant.getWealth().toBronze();
                if (personalWealth > 0) {
                    long fromPersonal = Math.min(personalWealth, remainingL);
                    CoinHelper.pay(merchant.getPersonalInventory(),
                            playerContainer, CurrencyValue.of(fromPersonal));
                    remainingL -= fromPersonal;
                }

                // Take remainingL from building storage
                if (remainingL > 0) {
                    Building building = merchant.getAssignedBuildingId()
                            .flatMap(data::getBuildingById)
                            .orElse(null);
                    if (building != null) {
                        // Build a temporary container from building storage
                        var buildingContainer = new net.minecraft.world.SimpleContainer(54);
                        int slot = 0;
                        for (var container : BuildingStorageAccess.findInventories(level, building)) {
                            for (int i = 0; i < container.getContainerSize() && slot < 54; i++) {
                                ItemStack stack = container.getItem(i);
                                int value = CurrencyValue.valuePerCoin(stack.getItem());
                                if (value > 0) {
                                    buildingContainer.setItem(slot++, stack.copy());
                                }
                            }
                        }

                        CoinHelper.pay(buildingContainer, playerContainer,
                                CurrencyValue.of(remainingL));

                        // Write modified coins back to building storage
                        int tempSlot = 0;
                        for (var container : BuildingStorageAccess.findInventories(level, building)) {
                            for (int i = 0; i < container.getContainerSize() && tempSlot < 54; i++) {
                                ItemStack stack = container.getItem(i);
                                int value = CurrencyValue.valuePerCoin(stack.getItem());
                                if (value > 0) {
                                    container.setItem(i, buildingContainer.getItem(tempSlot++));
                                }
                            }
                        }
                    }
                }
            }
            syncPlayerCoins(player, playerContainer);
        }

        // Refresh screen
        openTradeScreen(player, merchant);
    }

    private static net.minecraft.world.SimpleContainer buildPlayerContainer(
            ServerPlayer player) {

        return CoinHelper.snapshotInventory(player);
    }

    private static void syncPlayerCoins(ServerPlayer player,
                                        net.minecraft.world.SimpleContainer container) {
       CoinHelper.syncInventory(player,container);
    }

    public static String formatPrice(CurrencyValue value) {
        long bronze = value.toBronze();
        long gold   = bronze / CurrencyValue.GOLD_VALUE;
        long silver = (bronze % CurrencyValue.GOLD_VALUE) / CurrencyValue.SILVER_VALUE;
        long b      = bronze % CurrencyValue.SILVER_VALUE;

        StringBuilder sb = new StringBuilder();
        if (gold > 0)   sb.append(gold).append("g ");
        if (silver > 0) sb.append(silver).append("s ");
        if (b > 0 || sb.isEmpty()) sb.append(b).append("b");
        return sb.toString().trim();
    }
}
