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

public record OpenTradeScreenPacket(
        UUID merchantId,
        String merchantName,
        List<TradeOffer> offers,
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
                        buf.writeVarInt(packet.offers().size());
                        for (TradeOffer offer : packet.offers()) {
                            TradeOffer.STREAM_CODEC.encode(buf, offer);
                        }
                        buf.writeVarLong(packet.playerWealth());
                    },
                    buf -> {
                        UUID id = buf.readUUID();
                        String name = buf.readUtf();
                        int count = buf.readVarInt();
                        List<TradeOffer> offers = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            offers.add(TradeOffer.STREAM_CODEC.decode(buf));
                        }
                        long wealth = buf.readVarLong();
                        return new OpenTradeScreenPacket(id, name, offers, wealth);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(OpenTradeScreenPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                net.minecraft.client.Minecraft.getInstance().setScreen(
                        new TradeScreen(packet.merchantId(), packet.merchantName(),
                                packet.offers(), packet.playerWealth())
                )
        );
    }
}
