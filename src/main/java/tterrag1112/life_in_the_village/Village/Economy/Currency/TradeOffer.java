package tterrag1112.life_in_the_village.Village.Economy.Currency;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
                    },
                    buf -> new TradeOffer(
                            BuiltInRegistries.ITEM.get(buf.readIdentifier())
                                    .map(h -> h.value())
                                    .orElse(Items.AIR),
                            buf.readVarLong(),
                            buf.readVarLong(),
                            buf.readBoolean(),
                            buf.readBoolean(),
                            buf.readInt()
                    )
            );

    private final Item item;
    private final long buyPrice;  // what player pays to buy (bronze)
    private final long sellPrice; // what player receives when selling (bronze)
    private final boolean canBuy;
    private final boolean canSell;
    private final int stockCount;


    public TradeOffer(Item item, long buyPrice, long sellPrice,
                      boolean canBuy, boolean canSell, int stockCount) {
        this.item = item;
        this.buyPrice = buyPrice;
        this.sellPrice = sellPrice;
        this.canBuy = canBuy;
        this.canSell = canSell;
        this.stockCount = stockCount;

    }

    public Item item()        { return item; }
    public long buyPrice()    { return buyPrice; }
    public long sellPrice()   { return sellPrice; }
    public boolean canBuy()   { return canBuy; }
    public boolean canSell()  { return canSell; }
    public int stockCount() { return stockCount; }


    public ItemStack getIcon() { return new ItemStack(item, 1); }
}
