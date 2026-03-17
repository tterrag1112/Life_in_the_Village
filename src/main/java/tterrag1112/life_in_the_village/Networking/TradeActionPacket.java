package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Village.Economy.Currency.TradeHandler;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.UUID;

public record TradeActionPacket(
        UUID merchantId,
        Identifier itemId,
        boolean isBuying, // true = player buying, false = player selling
        int quantity
) implements CustomPacketPayload {

    public static final Type<TradeActionPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "trade_action")
    );

    public static final StreamCodec<FriendlyByteBuf, TradeActionPacket> CODEC =
            StreamCodec.of(
                    (buf, packet) -> {
                        buf.writeUUID(packet.merchantId());
                        buf.writeIdentifier(packet.itemId());
                        buf.writeBoolean(packet.isBuying());
                        buf.writeVarInt(packet.quantity());
                    },
                    buf -> new TradeActionPacket(
                            buf.readUUID(),
                            buf.readIdentifier(),
                            buf.readBoolean(),
                            buf.readVarInt()
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(TradeActionPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            TradeHandler.handleTrade(player, packet);
        });
    }
}
