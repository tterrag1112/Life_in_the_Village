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
import tterrag1112.life_in_the_village.Village.Economy.Trade.SeaRoute;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.ClientAtlasCache;

import java.util.List;
import java.util.UUID;

/**
 * Client→server request: scan the continent seeded by the given
 * kingdom and send back everything needed to render it.
 */
public record RequestContinentMapSyncPacket(UUID seedKingdomId)
        implements CustomPacketPayload {

    public static final Type<RequestContinentMapSyncPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "request_continent_map_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf,
            RequestContinentMapSyncPacket> CODEC = StreamCodec.of(
            (buf, pkt) -> buf.writeUUID(pkt.seedKingdomId()),
            buf -> new RequestContinentMapSyncPacket(buf.readUUID())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(RequestContinentMapSyncPacket pkt,
                              IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!(sp.level() instanceof ServerLevel level)) return;

            ContinentMapScope.Result r =
                    ContinentMapScope.gather(level, pkt.seedKingdomId());

            PacketDistributor.sendToPlayer(sp, new ContinentMapSyncPacket(
                    r.seedKingdomId(),
                    r.cells(),
                    r.continentCells(),
                    r.claims(),
                    r.roads(),
                    r.seaRoutes(),
                    r.prefillComplete()));
        });
    }
}