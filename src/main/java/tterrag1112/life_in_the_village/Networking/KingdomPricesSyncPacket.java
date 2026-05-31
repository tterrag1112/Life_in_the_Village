package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.Economy.Currency.KingdomPriceAggregator;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 5d — server's reply to {@link RequestKingdomPricesPacket}: the
 * cross-village price board (per item: cheapest source + dearest market +
 * spread + per-village drill-in). The client {@code KingdomBookScreen}
 * caches this for its PRICES section. Read-only display data.
 */
public record KingdomPricesSyncPacket(
        UUID kingdomId,
        List<KingdomPriceAggregator.ItemSpread> spreads
) implements CustomPacketPayload {

    public static final Type<KingdomPricesSyncPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "kingdom_prices_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf,
            KingdomPricesSyncPacket> CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.kingdomId());
                buf.writeVarInt(pkt.spreads().size());
                for (var s : pkt.spreads()) {
                    buf.writeUtf(s.itemId());
                    buf.writeUtf(s.itemName());
                    buf.writeUtf(s.cheapestVillage());
                    buf.writeVarLong(s.cheapestPrice());
                    buf.writeUtf(s.dearestVillage());
                    buf.writeVarLong(s.dearestPrice());
                    buf.writeVarInt(s.perVillage().size());
                    for (var vp : s.perVillage()) {
                        buf.writeUtf(vp.villageName());
                        buf.writeVarLong(vp.sellPrice());
                    }
                }
            },
            buf -> {
                UUID kingdomId = buf.readUUID();
                int n = buf.readVarInt();
                List<KingdomPriceAggregator.ItemSpread> spreads = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    String itemId = buf.readUtf();
                    String itemName = buf.readUtf();
                    String cheapV = buf.readUtf();
                    long cheapP = buf.readVarLong();
                    String dearV = buf.readUtf();
                    long dearP = buf.readVarLong();
                    int vn = buf.readVarInt();
                    List<KingdomPriceAggregator.VillagePrice> pv = new ArrayList<>();
                    for (int j = 0; j < vn; j++) {
                        pv.add(new KingdomPriceAggregator.VillagePrice(
                                buf.readUtf(), buf.readVarLong()));
                    }
                    spreads.add(new KingdomPriceAggregator.ItemSpread(
                            itemId, itemName, cheapV, cheapP, dearV, dearP, pv));
                }
                return new KingdomPricesSyncPacket(kingdomId, spreads);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(KingdomPricesSyncPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen instanceof tterrag1112.life_in_the_village.Gui.KingdomBookScreen book
                    && book.getKingdomId().equals(pkt.kingdomId())) {
                book.applyPrices(pkt.spreads());
            }
        });
    }
}
