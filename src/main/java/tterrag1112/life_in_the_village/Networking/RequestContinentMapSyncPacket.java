package tterrag1112.life_in_the_village.Networking;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.ContinentMapScope;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.ContinentMapScreen;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.Economy.Trade.ClientTradeConnectionCache;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.ClientAtlasCache;

import java.util.List;
import java.util.UUID;

/**
 * Client→server request for a PLAYER-CENTERED continent map. The
 * server samples terrain and gathers all kingdom claims, villages, and
 * settlement-charter pins within a fixed radius of the player position
 * carried here — no continent flood-fill, no kingdom seed. {@code
 * seedKingdomId} is retained only to match the sync reply to the
 * requesting screen (the screen identity key).
 */
public record RequestContinentMapSyncPacket(UUID seedKingdomId, int playerX, int playerZ)
        implements CustomPacketPayload {

    public static final Type<RequestContinentMapSyncPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "request_continent_map_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf,
            RequestContinentMapSyncPacket> CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.seedKingdomId());
                buf.writeInt(pkt.playerX());
                buf.writeInt(pkt.playerZ());
            },
            buf -> new RequestContinentMapSyncPacket(
                    buf.readUUID(), buf.readInt(), buf.readInt())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /**
     * Client-side factory: build a request centred on the local player.
     * Returns {@code null} if no local player exists yet (caller skips send).
     */
    public static RequestContinentMapSyncPacket forLocalPlayer(UUID seedKingdomId) {
        var p = net.minecraft.client.Minecraft.getInstance().player;
        if (p == null) return null;
        return new RequestContinentMapSyncPacket(
                seedKingdomId, (int) Math.floor(p.getX()), (int) Math.floor(p.getZ()));
    }

    public static void handle(RequestContinentMapSyncPacket pkt,
                              IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!(sp.level() instanceof ServerLevel level)) return;

            // Map builders read settlement charters + village→kingdom from the
            // client-side ClientKingdomCache, which is only populated on join /
            // book-open and goes stale as charters are created at claim time.
            // Refresh it with the live kingdom state (codec carries
            // settlementCharters) BEFORE the map reply. Both payloads enqueue on
            // the client main thread in send order, so the cache is fresh when
            // the map builder runs. Without this, charter pins never appear.
            VillageSavedData data = VillageSavedData.get(level);
            PacketDistributor.sendToPlayer(sp, new SyncKingdomPacket(data.getAllKingdoms()));

            ContinentMapScope.Result r =
                    ContinentMapScope.gather(level, pkt.playerX(), pkt.playerZ());

            // Echo back the request's screen-identity UUID (the Result no
            // longer carries a seed kingdom) so the client can match the
            // reply to the screen that requested it.
            PacketDistributor.sendToPlayer(sp, new ContinentMapSyncPacket(
                    pkt.seedKingdomId(),
                    r.cells(),
                    r.continentCells(),
                    r.claims(),
                    r.roads(),
                    r.seaRoutes(),
                    r.prefillComplete()));
        });
    }
}