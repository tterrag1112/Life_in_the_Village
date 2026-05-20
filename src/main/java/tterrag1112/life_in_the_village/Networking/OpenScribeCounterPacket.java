package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.UUID;

/**
 * Server → client: open the ScribeCounter composer screen.
 * Fired when the BusinessFrontScreen primary is pressed for a SCRIBE.
 */
public record OpenScribeCounterPacket(UUID scribeId, String scribeName,
                                      UUID workshopBuildingId,
                                      String villageName)
        implements CustomPacketPayload {

    public static final Type<OpenScribeCounterPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "open_scribe_counter"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenScribeCounterPacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeUUID(pkt.scribeId);
                        buf.writeUtf(pkt.scribeName);
                        buf.writeUUID(pkt.workshopBuildingId);
                        buf.writeUtf(pkt.villageName);
                    },
                    buf -> new OpenScribeCounterPacket(
                            buf.readUUID(), buf.readUtf(),
                            buf.readUUID(), buf.readUtf()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(OpenScribeCounterPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> tterrag1112.life_in_the_village.Gui.ScribeCounterScreen
                .openOnClient(pkt));
    }
}
