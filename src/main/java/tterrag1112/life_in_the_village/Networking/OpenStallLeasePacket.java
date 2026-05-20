package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.UUID;

/** Server → client: open the StallLease screen for {@code merchantId}. */
public record OpenStallLeasePacket(UUID merchantId, String merchantName,
                                   UUID marketBuildingId)
        implements CustomPacketPayload {

    public static final Type<OpenStallLeasePacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "open_stall_lease"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenStallLeasePacket> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeUUID(p.merchantId);
                        buf.writeUtf(p.merchantName);
                        buf.writeUUID(p.marketBuildingId);
                    },
                    buf -> new OpenStallLeasePacket(
                            buf.readUUID(), buf.readUtf(), buf.readUUID()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(OpenStallLeasePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> tterrag1112.life_in_the_village.Gui.StallLeaseScreen
                .openOnClient(pkt));
    }
}
