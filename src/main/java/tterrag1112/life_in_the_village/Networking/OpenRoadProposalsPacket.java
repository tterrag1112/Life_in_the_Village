package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Gui.RoadProposalsScreen;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.ArrayList;
import java.util.List;

/**
 * Track C3.1 — open the {@link RoadProposalsScreen} on the client and
 * supply it with a snapshot of the player's outstanding proposals.
 *
 * <p>Each {@link Entry} is a flat, render-ready summary of a
 * {@code RoadProposal}: the underlying record never crosses the wire,
 * just the user-visible fields. This avoids exposing UUIDs the client
 * doesn't need (cellPath, proposal id) and keeps the screen logic
 * read-only.</p>
 */
public record OpenRoadProposalsPacket(List<Entry> entries) implements CustomPacketPayload {

    public record Entry(String fromVillage,
                         String toVillage,
                         String tier,
                         String status,
                         int totalBlocks,
                         int progressBlocks,
                         long currencyFeeBronze) {}

    public static final Type<OpenRoadProposalsPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "open_road_proposals"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenRoadProposalsPacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeVarInt(pkt.entries().size());
                        for (Entry e : pkt.entries()) {
                            buf.writeUtf(e.fromVillage());
                            buf.writeUtf(e.toVillage());
                            buf.writeUtf(e.tier());
                            buf.writeUtf(e.status());
                            buf.writeVarInt(e.totalBlocks());
                            buf.writeVarInt(e.progressBlocks());
                            buf.writeVarLong(e.currencyFeeBronze());
                        }
                    },
                    buf -> {
                        int n = buf.readVarInt();
                        List<Entry> list = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            list.add(new Entry(
                                    buf.readUtf(), buf.readUtf(),
                                    buf.readUtf(), buf.readUtf(),
                                    buf.readVarInt(), buf.readVarInt(),
                                    buf.readVarLong()));
                        }
                        return new OpenRoadProposalsPacket(list);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(OpenRoadProposalsPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null) return;
            mc.setScreen(new RoadProposalsScreen(pkt.entries()));
        });
    }
}
