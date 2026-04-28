package tterrag1112.life_in_the_village.Networking;

import net.minecraft.client.Minecraft;
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
 * Doc 15 §"Player-requested repaint" — server → client. Carries the
 * snapshot the {@code RepaintScreen} renders: the builder's id +
 * name plus the list of repaint-allowed buildings in the village,
 * each with its variant id, current colours, and footprint.
 */
public record OpenRepaintScreenPacket(UUID npcId,
                                      String npcName,
                                      String villageName,
                                      List<BuildingEntry> buildings)
        implements CustomPacketPayload {

    /**
     * Single building row sent to the screen. Colour bytes are
     * {@code DyeColor.ordinal()} or {@code -1} for null (no current
     * tint). {@code colorSlotsMask} is a bitmask over
     * {@link tterrag1112.life_in_the_village.Village.Decoration
     * .Variants.ColorSlot#ordinal()} values.
     */
    public record BuildingEntry(UUID buildingId,
                                String name,
                                String typeName,
                                String variantId,
                                int colorSlotsMask,
                                byte primaryColor,
                                byte accentColor,
                                byte roofColor,
                                int footprintWidth,
                                int footprintLength,
                                boolean hasJob) {}

    public static final Type<OpenRepaintScreenPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "open_repaint_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenRepaintScreenPacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeUUID(pkt.npcId());
                        buf.writeUtf(pkt.npcName());
                        buf.writeUtf(pkt.villageName());
                        buf.writeVarInt(pkt.buildings().size());
                        for (BuildingEntry e : pkt.buildings()) {
                            buf.writeUUID(e.buildingId());
                            buf.writeUtf(e.name());
                            buf.writeUtf(e.typeName());
                            buf.writeUtf(e.variantId());
                            buf.writeVarInt(e.colorSlotsMask());
                            buf.writeByte(e.primaryColor());
                            buf.writeByte(e.accentColor());
                            buf.writeByte(e.roofColor());
                            buf.writeVarInt(e.footprintWidth());
                            buf.writeVarInt(e.footprintLength());
                            buf.writeBoolean(e.hasJob());
                        }
                    },
                    buf -> {
                        UUID npcId = buf.readUUID();
                        String npcName = buf.readUtf();
                        String villageName = buf.readUtf();
                        int n = buf.readVarInt();
                        List<BuildingEntry> list = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            list.add(new BuildingEntry(
                                    buf.readUUID(),
                                    buf.readUtf(),
                                    buf.readUtf(),
                                    buf.readUtf(),
                                    buf.readVarInt(),
                                    buf.readByte(),
                                    buf.readByte(),
                                    buf.readByte(),
                                    buf.readVarInt(),
                                    buf.readVarInt(),
                                    buf.readBoolean()));
                        }
                        return new OpenRepaintScreenPacket(npcId, npcName, villageName, list);
                    });

    @Override
    public Type<?> type() { return TYPE; }

    public static void handle(OpenRepaintScreenPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                Minecraft.getInstance().setScreen(
                        new tterrag1112.life_in_the_village.Gui.RepaintScreen(pkt)));
    }
}
