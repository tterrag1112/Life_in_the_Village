package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Life_in_the_village;

/**
 * Sent server→client to push a refreshed {@link NpcProfileSnapshot} while
 * the profile screen is already open (e.g. after an action or gift).
 * The screen is assumed to already be open; if not, the packet is silently
 * discarded on the client.
 */
public record NpcProfileSyncPacket(NpcProfileSnapshot snapshot)
        implements CustomPacketPayload {

    public static final Type<NpcProfileSyncPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "npc_profile_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcProfileSyncPacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> NpcProfileSnapshot.CODEC.encode(buf, pkt.snapshot()),
                    buf -> new NpcProfileSyncPacket(NpcProfileSnapshot.CODEC.decode(buf)));

    @Override
    public Type<?> type() { return TYPE; }

    public static void handle(NpcProfileSyncPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            net.minecraft.client.gui.screens.Screen screen =
                    net.minecraft.client.Minecraft.getInstance().screen;
            if (screen instanceof tterrag1112.life_in_the_village.Gui.NpcProfileScreen nps) {
                nps.applySync(pkt.snapshot());
            }
        });
    }
}
