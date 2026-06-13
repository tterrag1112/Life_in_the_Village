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
 * scoped to a viewport CENTRED ON THE PLAYER (player-centered maps).
 *
 * <p>The map renders a fixed radius around the requesting player so it
 * is usable anywhere — including far from any kingdom — for visualising
 * village placement. {@code kingdomId} is retained only so the sync
 * reply can be matched to the screen/book that requested it. The
 * viewport is derived server-side from the player position carried here.
 */
public record RequestKingdomMapSyncPacket(UUID kingdomId, int playerX, int playerZ)
        implements CustomPacketPayload {

    public static final Type<RequestKingdomMapSyncPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "request_kingdom_map_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf,
            RequestKingdomMapSyncPacket> CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.kingdomId());
                buf.writeInt(pkt.playerX());
                buf.writeInt(pkt.playerZ());
            },
            buf -> new RequestKingdomMapSyncPacket(
                    buf.readUUID(), buf.readInt(), buf.readInt())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /**
     * Client-side factory: build a request centred on the local player.
     * Returns {@code null} if no local player exists yet (caller skips send).
     */
    public static RequestKingdomMapSyncPacket forLocalPlayer(UUID kingdomId) {
        var p = net.minecraft.client.Minecraft.getInstance().player;
        if (p == null) return null;
        return new RequestKingdomMapSyncPacket(
                kingdomId, (int) Math.floor(p.getX()), (int) Math.floor(p.getZ()));
    }

    public static void handle(RequestKingdomMapSyncPacket pkt,
                              IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!(sp.level() instanceof ServerLevel level)) return;

            KingdomMapScope.Result result =
                    KingdomMapScope.gather(level, pkt.playerX(), pkt.playerZ());

            PacketDistributor.sendToPlayer(sp, new KingdomMapSyncPacket(
                    pkt.kingdomId(),
                    result.cells(),
                    result.roads(),
                    result.seaRoutes(),
                    result.travellers()));
        });
    }
}