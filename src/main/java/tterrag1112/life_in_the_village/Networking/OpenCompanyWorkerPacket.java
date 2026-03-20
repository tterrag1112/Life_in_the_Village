package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Gui.CompanyWorkerScreen;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.*;

public record OpenCompanyWorkerPacket(
        UUID npcId,
        UUID companyId,
        String npcName,
        long wage,
        long minWage,
        String currentItemId,
        int currentTargetCount,
        String role,
        List<AvailableItem> availableItems
) implements CustomPacketPayload {

    public record AvailableItem(String itemId, String displayName,
                                int stockCount) {}

    public static final Type<OpenCompanyWorkerPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "open_company_worker")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf,
            OpenCompanyWorkerPacket> CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.npcId());
                buf.writeUUID(pkt.companyId());
                buf.writeUtf(pkt.npcName());
                buf.writeVarLong(pkt.wage());
                buf.writeVarLong(pkt.minWage());
                buf.writeUtf(pkt.currentItemId());
                buf.writeVarInt(pkt.currentTargetCount());
                buf.writeUtf(pkt.role());
                buf.writeVarInt(pkt.availableItems().size());
                for (AvailableItem item : pkt.availableItems()) {
                    buf.writeUtf(item.itemId());
                    buf.writeUtf(item.displayName());
                    buf.writeVarInt(item.stockCount());
                }
            },
            buf -> {
                UUID npcId    = buf.readUUID();
                UUID compId   = buf.readUUID();
                String name   = buf.readUtf();
                long wage     = buf.readVarLong();
                long minWage  = buf.readVarLong();
                String item   = buf.readUtf();
                int count     = buf.readVarInt();
                String role   = buf.readUtf();
                int iCount    = buf.readVarInt();
                List<AvailableItem> items = new ArrayList<>();
                for (int i = 0; i < iCount; i++)
                    items.add(new AvailableItem(buf.readUtf(),
                            buf.readUtf(), buf.readVarInt()));
                return new OpenCompanyWorkerPacket(npcId, compId, name,
                        wage, minWage, item, count, role, items);
            }
    );

    @Override
    public Type<?> type() { return TYPE; }

    public static void handle(OpenCompanyWorkerPacket pkt,
                              IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                net.minecraft.client.Minecraft.getInstance()
                        .setScreen(new CompanyWorkerScreen(pkt)));
    }
}