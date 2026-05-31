package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Village.Economy.Currency.TradeOffer;
import tterrag1112.life_in_the_village.Gui.TradeScreen;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Opens the redesigned trade screen (merchant arc Phase 5a). Carries two
 * <b>independent</b> lists — {@code buyOffers} (what the NPC stocks to
 * sell) and {@code sellOffers} (what the NPC will buy that the player
 * holds) — plus the adaptive-header provenance: the village whose
 * per-village price is shown, the stall owner (empty for a plain
 * producer/wandering trader), and the player's reputation tier + discount.
 */
public record OpenTradeScreenPacket(
        UUID merchantId,
        String merchantName,
        String npcRole,
        String villageName,
        String stallOwner,
        String repTier,
        int repDiscountPct,
        List<TradeOffer> buyOffers,
        List<TradeOffer> sellOffers,
        long playerWealth
) implements CustomPacketPayload {

    public static final Type<OpenTradeScreenPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "open_trade_screen")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenTradeScreenPacket> CODEC =
            StreamCodec.of(
                    (buf, packet) -> {
                        buf.writeUUID(packet.merchantId());
                        buf.writeUtf(packet.merchantName());
                        buf.writeUtf(packet.npcRole());
                        buf.writeUtf(packet.villageName());
                        buf.writeUtf(packet.stallOwner());
                        buf.writeUtf(packet.repTier());
                        buf.writeVarInt(packet.repDiscountPct());
                        writeOffers(buf, packet.buyOffers());
                        writeOffers(buf, packet.sellOffers());
                        buf.writeVarLong(packet.playerWealth());
                    },
                    buf -> {
                        UUID id = buf.readUUID();
                        String name = buf.readUtf();
                        String role = buf.readUtf();
                        String village = buf.readUtf();
                        String stallOwner = buf.readUtf();
                        String repTier = buf.readUtf();
                        int repPct = buf.readVarInt();
                        List<TradeOffer> buys = readOffers(buf);
                        List<TradeOffer> sells = readOffers(buf);
                        long wealth = buf.readVarLong();
                        return new OpenTradeScreenPacket(id, name, role, village,
                                stallOwner, repTier, repPct, buys, sells, wealth);
                    }
            );

    private static void writeOffers(RegistryFriendlyByteBuf buf, List<TradeOffer> offers) {
        buf.writeVarInt(offers.size());
        for (TradeOffer offer : offers) {
            TradeOffer.STREAM_CODEC.encode(buf, offer);
        }
    }

    private static List<TradeOffer> readOffers(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<TradeOffer> offers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            offers.add(TradeOffer.STREAM_CODEC.decode(buf));
        }
        return offers;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(OpenTradeScreenPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                net.minecraft.client.Minecraft.getInstance().setScreen(
                        new TradeScreen(packet)
                )
        );
    }
}
