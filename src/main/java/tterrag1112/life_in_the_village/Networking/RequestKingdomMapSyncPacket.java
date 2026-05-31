package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.KingdomMapScope;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.UUID;

/**
 * Sent by the client when a kingdom map GUI opens (or its refresh
 * button is pressed). Server replies with a {@link KingdomMapSyncPacket}
 * scoped to the requested kingdom's viewport.
 *
 * <p>Using request/response rather than push because map data is bulky
 * (atlas cells can number in the hundreds) and only needed while a
 * map is actually on-screen.
 */
public record RequestKingdomMapSyncPacket(UUID kingdomId)
        implements CustomPacketPayload {

    public static final Type<RequestKingdomMapSyncPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "request_kingdom_map_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf,
            RequestKingdomMapSyncPacket> CODEC = StreamCodec.of(
            (buf, pkt) -> buf.writeUUID(pkt.kingdomId()),
            buf -> new RequestKingdomMapSyncPacket(buf.readUUID())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(RequestKingdomMapSyncPacket pkt,
                              IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!(sp.level() instanceof ServerLevel level)) return;

            KingdomMapScope.Result result =
                    KingdomMapScope.gather(level, pkt.kingdomId());

            PacketDistributor.sendToPlayer(sp, new KingdomMapSyncPacket(
                    pkt.kingdomId(),
                    result.cells(),
                    result.roads(),
                    result.seaRoutes(),
                    result.travellers()));
        });
    }
}