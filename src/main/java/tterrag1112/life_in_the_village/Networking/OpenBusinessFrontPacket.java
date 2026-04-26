package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.UUID;

/**
 * Server→client. Tells the client to open
 * {@link tterrag1112.life_in_the_village.Gui.BusinessFrontScreen} for
 * the targeted NPC inside a business-front building. Spec line 178.
 *
 * <p>Carries the minimum the screen needs — full profile data sits
 * behind the "View Profile" button which routes back to
 * {@code NpcProfileHub.open}.</p>
 */
public record OpenBusinessFrontPacket(
        UUID npcId,
        String npcName,
        String professionName,
        String buildingTypeName,
        String villageName
) implements CustomPacketPayload {

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
                    },
                    buf -> new OpenBusinessFrontPacket(
                            buf.readUUID(),
                            buf.readUtf(),
                            buf.readUtf(),
                            buf.readUtf(),
                            buf.readUtf()));

    @Override
    public Type<?> type() { return TYPE; }

    public static void handle(OpenBusinessFrontPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                net.minecraft.client.Minecraft.getInstance()
                        .setScreen(new tterrag1112.life_in_the_village.Gui.BusinessFrontScreen(pkt)));
    }
}
