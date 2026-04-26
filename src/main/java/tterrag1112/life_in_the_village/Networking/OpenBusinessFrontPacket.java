package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server→client. Tells the client to open
 * {@link tterrag1112.life_in_the_village.Gui.BusinessFrontScreen} for
 * the targeted NPC inside a business-front building. Spec line 178.
 *
 * <p>Carries the per-profession verb list so the screen can render
 * profession-aware buttons (priest gets confess / make_offering;
 * healer gets request_treatment / donate_herbs / buy_remedy; smith
 * gets the trade primary). The screen still routes to
 * {@code NpcProfileHub.open} via the corner profile button for the
 * full-profile view.</p>
 */
public record OpenBusinessFrontPacket(
        UUID npcId,
        String npcName,
        String professionName,
        String buildingTypeName,
        String villageName,
        List<String> verbIds,
        List<String> verbLabels
) implements CustomPacketPayload {

    public OpenBusinessFrontPacket {
        verbIds    = List.copyOf(verbIds);
        verbLabels = List.copyOf(verbLabels);
    }

    public static final Type<OpenBusinessFrontPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "open_business_front"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenBusinessFrontPacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeUUID(pkt.npcId());
                        buf.writeUtf(pkt.npcName());
                        buf.writeUtf(pkt.professionName());
                        buf.writeUtf(pkt.buildingTypeName());
                        buf.writeUtf(pkt.villageName());
                        buf.writeVarInt(pkt.verbIds().size());
                        for (String id : pkt.verbIds()) buf.writeUtf(id);
                        buf.writeVarInt(pkt.verbLabels().size());
                        for (String label : pkt.verbLabels()) buf.writeUtf(label);
                    },
                    buf -> {
                        UUID npcId = buf.readUUID();
                        String npcName = buf.readUtf();
                        String prof = buf.readUtf();
                        String building = buf.readUtf();
                        String village = buf.readUtf();
                        int idCount = buf.readVarInt();
                        List<String> ids = new ArrayList<>(idCount);
                        for (int i = 0; i < idCount; i++) ids.add(buf.readUtf());
                        int labelCount = buf.readVarInt();
                        List<String> labels = new ArrayList<>(labelCount);
                        for (int i = 0; i < labelCount; i++) labels.add(buf.readUtf());
                        return new OpenBusinessFrontPacket(npcId, npcName, prof, building, village,
                                ids, labels);
                    });

    @Override
    public Type<?> type() { return TYPE; }

    public static void handle(OpenBusinessFrontPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                net.minecraft.client.Minecraft.getInstance()
                        .setScreen(new tterrag1112.life_in_the_village.Gui.BusinessFrontScreen(pkt)));
    }
}
