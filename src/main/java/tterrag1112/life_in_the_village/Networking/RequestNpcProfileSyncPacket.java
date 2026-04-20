package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Entities.NpcProfileHub;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.UUID;

/**
 * Sent client→server to request a fresh snapshot while the profile is open.
 * The server rebuilds the snapshot and replies with {@link NpcProfileSyncPacket}.
 */
public record RequestNpcProfileSyncPacket(UUID npcId)
        implements CustomPacketPayload {

    public static final Type<RequestNpcProfileSyncPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "request_npc_profile_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestNpcProfileSyncPacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> buf.writeUUID(pkt.npcId()),
                    buf -> new RequestNpcProfileSyncPacket(buf.readUUID()));

    @Override
    public Type<?> type() { return TYPE; }

    public static void handle(RequestNpcProfileSyncPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!(sp.level() instanceof ServerLevel level)) return;
            NpcProfileHub.handleSyncRequest(pkt.npcId(), sp, level);
        });
    }
}
