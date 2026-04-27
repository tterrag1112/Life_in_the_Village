package tterrag1112.life_in_the_village.Npc.Economy.Channels.impl;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelQuote;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelType;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.EconomicChannel;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeDirection;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeIntent;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeResult;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceHelper;
import tterrag1112.life_in_the_village.Village.Economy.Trade.Caravan;
import tterrag1112.life_in_the_village.Village.Economy.Trade.CaravanSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Trades against the goods carried by a caravan currently
 * {@link Caravan.CaravanState#DELIVERING delivering} to the village.
 * Spec line 126.
 *
 * <p>Availability is the caravan's presence window: {@link #isAvailable}
 * returns true iff {@link CaravanSavedData} contains at least one
 * caravan with {@code destVillageId == village.id} in DELIVERING state.
 * The window closes when the caravan transitions to {@code RETURNING}
 * (departure), which the existing caravan tick handles. No persistent
 * state lives in the channel itself — the caravan saved data is the
 * source of truth.</p>
 *
 * <p>Pricing: 10% discount vs. market baseline per spec "Open
 * decisions" (line 301). When IMMEDIATE urgency is set, the router's
 * {@code +20} bonus pushes this channel above DIRECT_BUSINESS — matching
 * the "caravan with rare item beats market for that item" outcome.</p>
 */
public final class CaravanChannel implements EconomicChannel {

    /** Spec "Open decisions" #4: 10% discount on caravan items vs market. */
    private static final double CARAVAN_DISCOUNT = 0.90;

    @Override public ChannelType type() { return ChannelType.CARAVAN; }

    @Override public int basePriority() { return 50; }

    @Override
    public boolean isAvailable(Village village, VillageSavedData data, ServerLevel level, long tick) {
        if (village == null || level == null) return false;
        // Doc 22 wired: FOREIGN_TRADER_BAN closes the channel.
        if (tterrag1112.life_in_the_village.Npc.Laws.LawPriceHooks.caravanBlocked(village)) return false;
        return !caravansAt(village, CaravanSavedData.get(level)).isEmpty();
    }

    @Override
    public Optional<ChannelQuote> quote(TradeIntent intent, Village village,
                                        VillageSavedData data, ServerLevel level) {
        CaravanSavedData cdata = CaravanSavedData.get(level);
        List<Caravan> here = caravansAt(village, cdata);
        if (here.isEmpty()) return Optional.empty();

        // Find a caravan carrying the target item (BUY) or one accepting
        // it (SELL). v1 only handles BUY against carried goods; SELL is
        // a future expansion that needs a "demand list" on Caravan.
        if (intent.direction() != TradeDirection.BUY) return Optional.empty();

        Caravan match = null;
        int matchStock = 0;
        for (Caravan c : here) {
            int stock = stackCountFor(c.getGoods(), intent.item());
            if (stock > matchStock) {
                matchStock = stock;
                match = c;
            }
        }
        if (match == null) return Optional.empty();

        long base = MarketPriceHelper.getDynamicSellPrice(level, village, intent.item());
        long price = Math.round(base * CARAVAN_DISCOUNT);
        if (price > intent.maxPrice()) return Optional.empty();

        int qty = Math.min(intent.quantity(), matchStock);
        BlockPos location = caravanPosition(match, level);
        // Quote validity: caravan presence window. Conservative 6000-tick
        // window; daily tick refreshes — and execute revalidates by
        // re-querying the carriers list.
        long validUntil = level.getGameTime() + 6000L;
        return Optional.of(new ChannelQuote(ChannelType.CARAVAN, intent,
                price, qty, 0, validUntil, location));
    }

    @Override
    public TradeResult execute(ChannelQuote quote, TradeIntent intent, ServerLevel level) {
        if (intent.direction() != TradeDirection.BUY) return TradeResult.fail("CARAVAN sell not implemented");
        VillageSavedData data = VillageSavedData.get(level);
        Village village = data.getVillageById(intent.villageId()).orElse(null);
        if (village == null) return TradeResult.fail("village missing");
        CaravanSavedData cdata = CaravanSavedData.get(level);
        Caravan caravan = caravansAt(village, cdata).stream()
                .filter(c -> stackCountFor(c.getGoods(), intent.item()) > 0)
                .findFirst().orElse(null);
        if (caravan == null) return TradeResult.fail("caravan departed");

        int qty = Math.min(quote.availableQuantity(), intent.quantity());
        qty = Math.min(qty, stackCountFor(caravan.getGoods(), intent.item()));
        if (qty <= 0) return TradeResult.fail("caravan sold out");
        long total = quote.pricePerUnit() * qty;

        // Buyer pays.
        TownspersonMob buyer = TownspersonMob.findByUUID(level, intent.actorId()).orElse(null);
        if (buyer != null) {
            CurrencyValue cost = CurrencyValue.of(total);
            if (!buyer.getWallet().canAfford(cost)) return TradeResult.fail("buyer cannot pay");
            buyer.getWallet().spend(cost);
        }

        // Decrement caravan stock + credit caravan principal.
        decrementGoods(caravan.getGoods(), intent.item(), qty);
        UUID principalId = caravan.getRoster().getPrincipalId();
        if (principalId != null) {
            TownspersonMob principal = TownspersonMob.findByUUID(level, principalId).orElse(null);
            if (principal != null) principal.getWallet().receive(CurrencyValue.of(total));
        }
        cdata.setDirty();
        return TradeResult.ok(qty, total);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static List<Caravan> caravansAt(Village village, CaravanSavedData cdata) {
        if (cdata == null || village == null) return List.of();
        return cdata.getAllCaravans().stream()
                .filter(c -> c.getDestVillageId().equals(village.getId()))
                .filter(c -> c.getState() == Caravan.CaravanState.DELIVERING)
                .toList();
    }

    private static BlockPos caravanPosition(Caravan caravan, ServerLevel level) {
        if (caravan.getRoster().getPrincipalId() == null) return null;
        var entity = level.getEntity(caravan.getRoster().getPrincipalId());
        return entity == null ? null : entity.blockPosition();
    }

    private static int stackCountFor(List<ItemStack> goods, Item item) {
        int sum = 0;
        for (ItemStack s : goods) {
            if (s.is(item)) sum += s.getCount();
        }
        return sum;
    }

    private static void decrementGoods(List<ItemStack> goods, Item item, int qty) {
        int remaining = qty;
        for (int i = 0; i < goods.size() && remaining > 0; i++) {
            ItemStack s = goods.get(i);
            if (!s.is(item)) continue;
            int take = Math.min(remaining, s.getCount());
            s.shrink(take);
            remaining -= take;
            if (s.isEmpty()) goods.set(i, ItemStack.EMPTY);
        }
        goods.removeIf(ItemStack::isEmpty);
    }
}
