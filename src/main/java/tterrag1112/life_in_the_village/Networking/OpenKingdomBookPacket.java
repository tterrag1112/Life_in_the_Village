package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Gui.KingdomBookScreen;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.UUID;

public record OpenKingdomBookPacket(
        UUID kingdomId
) implements CustomPacketPayload {

    public static final Type<OpenKingdomBookPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "open_kingdom_book")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf,
            OpenKingdomBookPacket> CODEC = StreamCodec.of(
            (buf, pkt) -> buf.writeUUID(pkt.kingdomId()),
            buf -> new OpenKingdomBookPacket(buf.readUUID())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenKingdomBookPacket pkt,
                              IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null) return;
            mc.setScreen(new KingdomBookScreen(pkt.kingdomId()));
        });
    }
}