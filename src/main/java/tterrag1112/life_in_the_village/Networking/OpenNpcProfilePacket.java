package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Life_in_the_village;

/**
 * Sent server→client to open the NPC profile screen for the first time.
 * Carries the full initial {@link NpcProfileSnapshot}; subsequent refreshes
 * use {@link NpcProfileSyncPacket}.
 */
public record OpenNpcProfilePacket(NpcProfileSnapshot snapshot)
        implements CustomPacketPayload {

    public static final Type<OpenNpcProfilePacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "open_npc_profile"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenNpcProfilePacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> NpcProfileSnapshot.CODEC.encode(buf, pkt.snapshot()),
                    buf -> new OpenNpcProfilePacket(NpcProfileSnapshot.CODEC.decode(buf)));

    @Override
    public Type<?> type() { return TYPE; }

    public static void handle(OpenNpcProfilePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                net.minecraft.client.Minecraft.getInstance()
                        .setScreen(new tterrag1112.life_in_the_village.Gui.NpcProfileScreen(
                                pkt.snapshot())));
    }
}
