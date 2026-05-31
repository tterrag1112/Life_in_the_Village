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

/**
 * A buy/sell action from the trade screen. {@code merchantId} keys the
 * NPC-manned path (the entity to resolve). Phase 5b: {@code stallId} (the
 * zero UUID when unused) keys a <b>player-owned stall with no NPC</b> — the
 * handler routes to the stall-keyed trade instead of resolving an entity.
 */
public record TradeActionPacket(
        UUID merchantId,
        UUID stallId,
        Identifier itemId,
        boolean isBuying, // true = player buying, false = player selling
        int quantity
) implements CustomPacketPayload {

    private static final UUID NO_STALL = new UUID(0L, 0L);

    /** NPC-manned trade action (no stall key). */
    public TradeActionPacket(UUID merchantId, Identifier itemId,
                             boolean isBuying, int quantity) {
        this(merchantId, NO_STALL, itemId, isBuying, quantity);
    }

    public boolean hasStall() { return !NO_STALL.equals(stallId); }

    public static final Type<TradeActionPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "trade_action")
    );

    public static final StreamCodec<FriendlyByteBuf, TradeActionPacket> CODEC =
            StreamCodec.of(
                    (buf, packet) -> {
                        buf.writeUUID(packet.merchantId());
                        buf.writeUUID(packet.stallId());
                        buf.writeIdentifier(packet.itemId());
                        buf.writeBoolean(packet.isBuying());
                        buf.writeVarInt(packet.quantity());
                    },
                    buf -> new TradeActionPacket(
                            buf.readUUID(),
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
            if (packet.hasStall()) {
                TradeHandler.handleStallTrade(player, packet);
            } else {
                TradeHandler.handleTrade(player, packet);
            }
        });
    }
}
