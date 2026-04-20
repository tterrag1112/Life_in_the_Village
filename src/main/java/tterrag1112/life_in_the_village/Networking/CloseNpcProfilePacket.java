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
 * Sent client→server when the player closes the NPC profile screen. Triggers
 * {@link tterrag1112.life_in_the_village.Entities.custom.TownspersonMob#unlockConversation}
 * so the NPC resumes its normal AI.
 */
public record CloseNpcProfilePacket(UUID npcId)
        implements CustomPacketPayload {

    public static final Type<CloseNpcProfilePacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "close_npc_profile"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CloseNpcProfilePacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> buf.writeUUID(pkt.npcId()),
                    buf -> new CloseNpcProfilePacket(buf.readUUID()));

    @Override
    public Type<?> type() { return TYPE; }

    public static void handle(CloseNpcProfilePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!(sp.level() instanceof ServerLevel level)) return;
            NpcProfileHub.handleClose(pkt.npcId(), sp, level);
        });
    }
}
