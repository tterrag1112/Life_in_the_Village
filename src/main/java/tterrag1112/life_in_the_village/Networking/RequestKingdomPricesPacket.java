package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.Economy.Currency.KingdomPriceAggregator;

import java.util.List;
import java.util.UUID;

/**
 * Phase 5d — client requests the cross-village price board for a kingdom
 * (sent when the kingdom book's PRICES section opens or the price-board
 * item is used). The server aggregates and replies with {@link
 * KingdomPricesSyncPacket}. Request/response (not push) because the board
 * is only needed while on-screen.
 */
public record RequestKingdomPricesPacket(UUID kingdomId)
        implements CustomPacketPayload {

    public static final Type<RequestKingdomPricesPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "request_kingdom_prices"));

    public static final StreamCodec<RegistryFriendlyByteBuf,
            RequestKingdomPricesPacket> CODEC = StreamCodec.of(
            (buf, pkt) -> buf.writeUUID(pkt.kingdomId()),
            buf -> new RequestKingdomPricesPacket(buf.readUUID())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(RequestKingdomPricesPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!(sp.level() instanceof ServerLevel level)) return;

            VillageSavedData data = VillageSavedData.get(level);
            Kingdom kingdom = data.getKingdomById(pkt.kingdomId()).orElse(null);
            List<KingdomPriceAggregator.ItemSpread> spreads = kingdom == null
                    ? List.of()
                    : KingdomPriceAggregator.aggregate(level, kingdom, data);

            PacketDistributor.sendToPlayer(sp,
                    new KingdomPricesSyncPacket(pkt.kingdomId(), spreads));
        });
    }
}
