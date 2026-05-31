package tterrag1112.life_in_the_village.Village.Economy.Currency;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * One row in the trade screen (merchant arc Phase 5a). A given item is a
 * BUY row (the NPC stocks it, the player can buy) OR a SELL row (the NPC
 * wants it and the player holds some) — the two lists are built
 * independently server-side, so a row carries only the price relevant to
 * its side (buy rows show {@code buyPrice} + {@code stockCount}; sell rows
 * show {@code sellPrice}). {@code customPriced} flags a row whose price
 * came from a stall owner's custom override (vs the village dynamic price).
 * Network-only; not persisted.
 */
public class TradeOffer {

    public static final StreamCodec<RegistryFriendlyByteBuf, TradeOffer> STREAM_CODEC =
            StreamCodec.of(
                    (buf, offer) -> {
                        buf.writeIdentifier(
                                BuiltInRegistries.ITEM.getKey(offer.item()));
                        buf.writeVarLong(offer.buyPrice());
                        buf.writeVarLong(offer.sellPrice());
                        buf.writeBoolean(offer.canBuy());
                        buf.writeBoolean(offer.canSell());
                        buf.writeInt(offer.stockCount());
                        buf.writeBoolean(offer.customPriced());
                    },
                    buf -> new TradeOffer(
                            BuiltInRegistries.ITEM.get(buf.readIdentifier())
                                    .map(h -> h.value())
                                    .orElse(Items.AIR),
                            buf.readVarLong(),
                            buf.readVarLong(),
                            buf.readBoolean(),
                            buf.readBoolean(),
                            buf.readInt(),
                            buf.readBoolean()
                    )
            );

    private final Item item;
    private final long buyPrice;  // what player pays to buy (bronze)
    private final long sellPrice; // what player receives when selling (bronze)
    private final boolean canBuy;
    private final boolean canSell;
    private final int stockCount;
    private final boolean customPriced; // stall custom price in effect (vs village price)

    public TradeOffer(Item item, long buyPrice, long sellPrice,
                      boolean canBuy, boolean canSell, int stockCount) {
        this(item, buyPrice, sellPrice, canBuy, canSell, stockCount, false);
    }

    public TradeOffer(Item item, long buyPrice, long sellPrice,
                      boolean canBuy, boolean canSell, int stockCount,
                      boolean customPriced) {
        this.item = item;
        this.buyPrice = buyPrice;
        this.sellPrice = sellPrice;
        this.canBuy = canBuy;
        this.canSell = canSell;
        this.stockCount = stockCount;
        this.customPriced = customPriced;
    }

    public Item item()        { return item; }
    public long buyPrice()    { return buyPrice; }
    public long sellPrice()   { return sellPrice; }
    public boolean canBuy()   { return canBuy; }
    public boolean canSell()  { return canSell; }
    public int stockCount()   { return stockCount; }
    public boolean customPriced() { return customPriced; }

    public ItemStack getIcon() { return new ItemStack(item, 1); }
}
