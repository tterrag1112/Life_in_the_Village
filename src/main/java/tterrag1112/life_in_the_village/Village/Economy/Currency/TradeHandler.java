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
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;
import tterrag1112.life_in_the_village.Village.Reputation.ReputationManager;
import tterrag1112.life_in_the_village.Village.Reputation.VillageReputation;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

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

        Village pricingVillage = village.orElse(null);
        String villageName = pricingVillage != null ? pricingVillage.getName() : "";

        // Header provenance — stall owner + reputation tier/discount (5a).
        MarketStall headerStall = firstOwnedStall(data, market, merchant.getUUID());
        String stallOwner = headerStall != null ? headerStall.getOwnerDisplayName() : "";
        String repTierName = "";
        int repDiscountPct = 0;
        if (pricingVillage != null) {
            VillageReputation.Tier tier = ReputationManager.getTier(
                    player, pricingVillage.getId(), level);
            repTierName = tier.displayName;
            repDiscountPct = (int) Math.round(tier.getPriceDiscount() * 100);
        }

        // Two INDEPENDENT lists (5a — fixes the "same item in both columns"
        // bug). BUY = what the NPC stocks to sell; SELL = what the NPC will
        // buy that the player actually holds. An item can be in one, both,
        // or neither — each list is populated by its own predicate.
        List<TradeOffer> buyOffers = new ArrayList<>();
        List<TradeOffer> sellOffers = new ArrayList<>();

        MarketPriceHelper.getAllExplicitPrices().keySet().forEach(item -> {
            // Pricing stall mirrors the buy-path source (merchant's own stall
            // only) so the displayed price equals the charged price.
            MarketStall pricingStall = findOwnedStallWithItem(
                    data, level, market.getId(), item, merchant.getUUID());

            // ── BUY list: only items the market actually stocks (across its
            //    stall chests — the only merchant inventory). Matches the
            //    buy-path source so the displayed stock equals what's sellable.
            int stock = tterrag1112.life_in_the_village.Village.Markets.Complex.MarketInventory.countItem(level, market, item);
            if (stock > 0) {
                long sell = MarketPricing.sellPrice(PricingContext.forPlayer(
                        item, level, pricingVillage, data, player, pricingStall));
                boolean customPriced = pricingStall != null
                        && pricingStall.getCustomPricesRaw().containsKey(
                                BuiltInRegistries.ITEM.getKey(item).toString());
                buyOffers.add(new TradeOffer(
                        item, sell, 0, true, false, stock, customPriced));
            }

            // ── SELL list: only items the NPC will buy AND the player holds ─
            boolean playerHas = player.getInventory()
                    .hasAnyMatching(s -> s.is(item));
            if (playerHas) {
                long buy = MarketPricing.buyPrice(PricingContext.forPlayer(
                        item, level, pricingVillage, data, player, null));
                sellOffers.add(new TradeOffer(
                        item, 0, buy, false, true, 0, false));
            }
        });

        // Use CoinHelper.getPlayerWealth — no snapshot needed
        long playerWealth = CoinHelper.getPlayerWealth(player).toBronze();

        String role = merchant.getProfession().name().charAt(0)
                + merchant.getProfession().name().substring(1).toLowerCase()
                        .replace('_', ' ');

        PacketDistributor.sendToPlayer(player,
                new OpenTradeScreenPacket(merchant.getUUID(),
                        OpenTradeScreenPacket.NO_STALL,
                        merchant.getNpcName(), role, villageName, stallOwner,
                        repTierName, repDiscountPct, buyOffers, sellOffers,
                        playerWealth));
    }

    // =========================================================================
    // Phase 5b — stall-keyed trade (player-owned stall, no NPC manning it)
    // =========================================================================

    /**
     * Opens the trade screen keyed to a player-owned {@code stall} that has
     * no NPC. The BUY list = the stall chest's stock at the owner's
     * effective (custom or village) price. The SELL list is empty: a
     * player stall has no wallet to pay the seller (the owner isn't there),
     * so visitors can buy from it but not sell to it.
     */
    public static void openTradeScreenForStall(ServerPlayer player,
                                               MarketStall stall) {
        if (!(player.level() instanceof ServerLevel level)) return;
        VillageSavedData data = VillageSavedData.get(level);
        Building market = data.getBuildingById(stall.getMarketBuildingId()).orElse(null);
        if (market == null) return;

        Optional<Village> village = data.getVillageAt(stall.getStallOrigin());
        Village pricingVillage = village.orElse(null);
        String villageName = pricingVillage != null ? pricingVillage.getName() : "";

        List<TradeOffer> buyOffers = new ArrayList<>();
        MarketPriceHelper.getAllExplicitPrices().keySet().forEach(item -> {
            int stock = countInStallChest(level, stall, item);
            if (stock <= 0) return;
            long sell = MarketPricing.sellPrice(PricingContext.forPlayer(
                    item, level, pricingVillage, data, player, stall));
            boolean customPriced = stall.getCustomPricesRaw().containsKey(
                    BuiltInRegistries.ITEM.getKey(item).toString());
            buyOffers.add(new TradeOffer(item, sell, 0, true, false, stock, customPriced));
        });

        long playerWealth = CoinHelper.getPlayerWealth(player).toBronze();
        PacketDistributor.sendToPlayer(player,
                new OpenTradeScreenPacket(stall.getOwnerUUID(), stall.getStallId(),
                        stall.getOwnerDisplayName() + "'s Stall", "Stall",
                        villageName, stall.getOwnerDisplayName(),
                        stall.getReputationTier().displayName, 0,
                        buyOffers, List.of(), playerWealth));
    }

    /**
     * Handles a buy against a player-owned stall (no NPC). Draws from the
     * stall chest at the owner's effective price; the player pays into the
     * stall chest (the owner collects it) via 1b {@code settlePurchase} with
     * the stall chest as endpoint. Sell-to-stall is not offered (no wallet).
     */
    public static void handleStallTrade(ServerPlayer player,
                                        TradeActionPacket packet) {
        if (!(player.level() instanceof ServerLevel level)) return;
        if (!packet.isBuying()) return; // player stalls don't buy from visitors

        Item item = BuiltInRegistries.ITEM.get(packet.itemId())
                .map(h -> h.value()).orElse(null);
        if (item == null) return;

        VillageSavedData data = VillageSavedData.get(level);
        MarketStall stall = data.getStallById(packet.stallId()).orElse(null);
        if (stall == null || !stall.isActive()
                || stall.getOwnerType() != MarketStall.OwnerType.PLAYER) {
            return;
        }
        Building market = data.getBuildingById(stall.getMarketBuildingId()).orElse(null);
        if (market == null) return;
        Optional<Village> village = data.getVillageAt(stall.getStallOrigin());

        int stock = countInStallChest(level, stall, item);
        int quantity = Math.min(packet.quantity(), stock);
        if (quantity <= 0) {
            player.displayClientMessage(Component.literal("Out of stock."), true);
            return;
        }

        long pricePerItem = MarketPricing.sellPrice(PricingContext.forPlayer(
                item, level, village.orElse(null), data, player, stall));
        quantity = Math.min(quantity,
                (int) (CoinHelper.getPlayerWealth(player).toBronze()
                        / Math.max(1, pricePerItem)));
        if (quantity <= 0) {
            player.displayClientMessage(Component.literal("Not enough coins."), true);
            return;
        }

        CurrencyValue totalCost = CurrencyValue.of(pricePerItem * quantity);
        if (!takeFromStallChest(level, stall, item, quantity)) {
            player.displayClientMessage(Component.literal("Failed to retrieve item."), true);
            return;
        }
        // Player pays the stall owner — coins land in the stall chest (1b).
        if (!NpcEconomy.settlePurchase(SettlementParty.player(player),
                SettlementParty.stallChest(stall),
                totalCost, village.orElse(null), level, data)) {
            returnToStallChest(level, stall, item, quantity);
            player.displayClientMessage(Component.literal("Payment failed."), true);
            return;
        }
        stall.recordSale(totalCost.toBronze());
        data.setDirty();

        ItemStack toGive = new ItemStack(item, quantity);
        if (!player.addItem(toGive)) player.drop(toGive, false);
        player.displayClientMessage(Component.literal("Bought " + quantity + "x "
                + item.getName().getString() + " for " + formatPrice(totalCost)), true);

        openTradeScreenForStall(player, stall); // refresh
    }

    /**
     * Phase 5b — builds + sends the owner's stall-management snapshot: stats
     * (sales, reputation, rent) + a per-item row (effective price, ±20%
     * band, custom flag, stock) for every explicit-price item, so the owner
     * can set custom prices and view stock.
     */
    public static void openStallManagement(ServerPlayer player, MarketStall stall) {
        if (!(player.level() instanceof ServerLevel level)) return;
        VillageSavedData data = VillageSavedData.get(level);
        Optional<Village> village = data.getVillageAt(stall.getStallOrigin());
        Village v = village.orElse(null);

        List<tterrag1112.life_in_the_village.Networking.OpenStallManagementPacket.ItemRow> rows =
                new ArrayList<>();
        MarketPriceHelper.getAllExplicitPrices().keySet().forEach(item -> {
            long base = MarketPriceHelper.getDynamicSellPrice(level, v, item);
            if (base <= 0) return;
            long min = (long) Math.floor(base * (1.0 - MarketStall.PRICE_BAND));
            long max = (long) Math.ceil(base * (1.0 + MarketStall.PRICE_BAND));
            long eff = stall.getEffectivePrice(item, base);
            boolean custom = stall.getCustomPricesRaw().containsKey(
                    BuiltInRegistries.ITEM.getKey(item).toString());
            int stock = countInStallChest(level, stall, item);
            // Show items that are stocked OR already custom-priced (so the
            // owner can manage a price even before stocking).
            if (stock > 0 || custom) {
                rows.add(new tterrag1112.life_in_the_village.Networking
                        .OpenStallManagementPacket.ItemRow(
                        BuiltInRegistries.ITEM.getKey(item).toString(),
                        item.getName().getString(), base, eff, min, max, custom, stock));
            }
        });

        String label = stall.getOwnerDisplayName().isEmpty()
                ? "Your Stall" : stall.getOwnerDisplayName() + "'s Stall";
        PacketDistributor.sendToPlayer(player,
                new tterrag1112.life_in_the_village.Networking.OpenStallManagementPacket(
                        stall.getStallId(), label, stall.getTotalSales(),
                        stall.getReputationTier().displayName, stall.isPurchased(),
                        stall.getRentPaidUntilTick(), level.getGameTime(), rows));
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

        int quantity = packet.quantity();

        // =====================================================================
        // PLAYER BUYING
        // =====================================================================
        if (packet.isBuying()) {

            // Prefer merchant's own stall as source, then the market's stalls
            MarketStall sourceStall = findOwnedStallWithItem(
                    data, level, market.getId(), item, merchant.getUUID());

            int stock = sourceStall != null
                    ? countInStallChest(level, sourceStall, item)
                    : tterrag1112.life_in_the_village.Village.Markets.Complex.MarketInventory.countItem(level, market, item);

            quantity = Math.min(quantity, stock);
            if (quantity <= 0) {
                player.displayClientMessage(
                        Component.literal("Out of stock."), true);
                return;
            }

            // Price — canonical pipeline. Source stall (if any) lets the
            // stall's custom price apply, matching the displayed offer.
            long pricePerItem = MarketPricing.sellPrice(PricingContext.forPlayer(
                    item, level, village.orElse(null), data, player, sourceStall));

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

            // 1. Take item from stall or main chest (goods movement stays
            //    in the caller for 1b). The source decides the pay endpoint.
            boolean taken;
            if (sourceStall != null) {
                // A failed stall-take still proceeds, crediting the merchant
                // fallback — matches the pre-1b behaviour exactly.
                taken = takeFromStallChest(level, sourceStall, item, quantity);
            } else {
                taken = tterrag1112.life_in_the_village.Village.Markets.Complex.MarketInventory.takeItem(
                        level, market, item, quantity);
                if (!taken) {
                    // Player not yet charged — abort (net-identical to the
                    // old pay-then-refund on this branch).
                    player.displayClientMessage(
                            Component.literal("Failed to retrieve item."), true);
                    return;
                }
            }

            // 2. Settle money via the unified helper: player pays the stall
            //    owner / merchant, village collects the market tax once.
            SettlementParty endpoint = (sourceStall != null && taken)
                    ? NpcEconomy.resolveStallEndpoint(level, sourceStall, merchant)
                    : SettlementParty.npc(merchant);
            if (!NpcEconomy.settlePurchase(SettlementParty.player(player), endpoint,
                    totalCost, village.orElse(null), level, data)) {
                // Payment failed (quantity was pre-capped to wealth, so this
                // is effectively unreachable) — restore the taken goods.
                if (taken) {
                    if (sourceStall != null)
                        returnToStallChest(level, sourceStall, item, quantity);
                    else tterrag1112.life_in_the_village.Village.Markets.Complex.MarketInventory.store(
                            level, market, new ItemStack(item, quantity));
                }
                player.displayClientMessage(
                        Component.literal("Payment failed."), true);
                return;
            }

            // 3. Give item to player
            ItemStack toGive = new ItemStack(item, quantity);
            if (!player.addItem(toGive)) player.drop(toGive, false);

            // Phase 1: fire Trade event so memory/mood producers see it.
            tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus.fire(
                    new tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent.Trade(
                            merchant, player.getUUID(), true,
                            new ItemStack(item, quantity),
                            totalCost.toBronze(),
                            quantity >= 4 || totalCost.toBronze() >= 200L));

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

            // Price merchant pays player — canonical pipeline (perk + tariff
            // + village law applied inside). Selling to the market has no
            // stall override.
            long pricePerItem = MarketPricing.buyPrice(PricingContext.forPlayer(
                    item, level, village.orElse(null), data, player, null));

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

            // 1. Goods land in the market's stall chests (the only merchant
            //    inventory). Try the deposit FIRST and clean-fail when no
            //    stall chest can absorb: the player keeps the item, gets one
            //    in-HUD notice, and is NOT charged/paid. Pre-unification this
            //    wrote the building via storeExcludingStalls and silently
            //    dropped the goods when the building had no chest.
            ItemStack incoming = new ItemStack(item, quantity);
            if (!tterrag1112.life_in_the_village.Village.Markets.Complex.MarketInventory.store(level, market, incoming)) {
                int stored = quantity - incoming.getCount();
                if (stored <= 0) {
                    player.displayClientMessage(Component.literal(
                            "This market has no stall space to buy that right now."), true);
                    return;
                }
                quantity = stored; // partial: only buy what the stalls absorbed
                totalEarned = CurrencyValue.of(pricePerItem * quantity);
            }

            // 2. Take the (accepted) items from the player.
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

            // 3. Settle money via the unified helper: merchant pays the
            //    player. No sell-side tax (preserves pre-1b behaviour).
            NpcEconomy.settleSale(SettlementParty.npc(merchant),
                    SettlementParty.player(player), totalEarned, level);

            // Phase 1: fire Trade event for the sell-side path too.
            tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus.fire(
                    new tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent.Trade(
                            merchant, player.getUUID(), true,
                            new ItemStack(item, quantity),
                            totalEarned.toBronze(),
                            quantity >= 4 || totalEarned.toBronze() >= 200L));

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

    /** First active stall this merchant owns at the market (for the trade
     *  header's stall-owner line), or null if it owns none. */
    private static MarketStall firstOwnedStall(VillageSavedData data,
                                               Building market, UUID ownerUUID) {
        for (MarketStall stall : data.getStallsForMarket(market.getId())) {
            if (stall.isActive() && stall.getOwnerUUID().equals(ownerUUID)) {
                return stall;
            }
        }
        return null;
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

    /** Restores goods into a stall chest — used to roll back a failed settle. */
    private static void returnToStallChest(ServerLevel level, MarketStall stall,
                                           Item item, int qty) {
        BlockEntity be = level.getBlockEntity(stall.getChestPos());
        if (!(be instanceof net.minecraft.world.Container chest)) return;
        ItemStack stack = new ItemStack(item, qty);
        for (int i = 0; i < chest.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack ex = chest.getItem(i);
            if (ex.is(item) && ex.getCount() < ex.getMaxStackSize()) {
                int add = Math.min(ex.getMaxStackSize() - ex.getCount(), stack.getCount());
                ex.grow(add);
                stack.shrink(add);
            }
        }
        for (int i = 0; i < chest.getContainerSize() && !stack.isEmpty(); i++) {
            if (chest.getItem(i).isEmpty()) {
                chest.setItem(i, stack.copy());
                stack.setCount(0);
            }
        }
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