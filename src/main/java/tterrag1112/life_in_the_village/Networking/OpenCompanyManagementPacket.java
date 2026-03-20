package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Gui.CompanyManagementScreen;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.*;

public record OpenCompanyManagementPacket(
        UUID companyId,
        String companyName,
        long treasuryBronze,
        long playerWealthBronze,
        int workStartHour,
        int workEndHour,
        long effectiveMinWage,
        List<WorkerEntry> workers,
        List<PriceEntry> priceOverrides,
        List<BuildingEntry> buildings
) implements CustomPacketPayload {

    public record WorkerEntry(UUID npcId, String npcName,
                              String role, long wagePerDay,
                              String assignedItemId,
                              int dailyTargetCount) {}

    public record PriceEntry(String itemId, String itemName,
                             long pricePerUnit) {}

    public record BuildingEntry(UUID buildingId, String buildingName,
                                String buildingType) {}

    public static final Type<OpenCompanyManagementPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "open_company_management")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf,
            OpenCompanyManagementPacket> CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.companyId());
                buf.writeUtf(pkt.companyName());
                buf.writeVarLong(pkt.treasuryBronze());
                buf.writeVarLong(pkt.playerWealthBronze());
                buf.writeVarInt(pkt.workStartHour());
                buf.writeVarInt(pkt.workEndHour());
                buf.writeVarLong(pkt.effectiveMinWage());

                buf.writeVarInt(pkt.workers().size());
                for (WorkerEntry w : pkt.workers()) {
                    buf.writeUUID(w.npcId());
                    buf.writeUtf(w.npcName());
                    buf.writeUtf(w.role());
                    buf.writeVarLong(w.wagePerDay());
                    buf.writeUtf(w.assignedItemId());
                    buf.writeVarInt(w.dailyTargetCount());
                }

                buf.writeVarInt(pkt.priceOverrides().size());
                for (PriceEntry p : pkt.priceOverrides()) {
                    buf.writeUtf(p.itemId());
                    buf.writeUtf(p.itemName());
                    buf.writeVarLong(p.pricePerUnit());
                }

                buf.writeVarInt(pkt.buildings().size());
                for (BuildingEntry b : pkt.buildings()) {
                    buf.writeUUID(b.buildingId());
                    buf.writeUtf(b.buildingName());
                    buf.writeUtf(b.buildingType());
                }
            },
            buf -> {
                UUID id          = buf.readUUID();
                String name      = buf.readUtf();
                long treasury    = buf.readVarLong();
                long wealth      = buf.readVarLong();
                int startH       = buf.readVarInt();
                int endH         = buf.readVarInt();
                long minWage     = buf.readVarLong();

                int wCount = buf.readVarInt();
                List<WorkerEntry> workers = new ArrayList<>();
                for (int i = 0; i < wCount; i++)
                    workers.add(new WorkerEntry(buf.readUUID(), buf.readUtf(),
                            buf.readUtf(), buf.readVarLong(),
                            buf.readUtf(), buf.readVarInt()));

                int pCount = buf.readVarInt();
                List<PriceEntry> prices = new ArrayList<>();
                for (int i = 0; i < pCount; i++)
                    prices.add(new PriceEntry(buf.readUtf(), buf.readUtf(),
                            buf.readVarLong()));

                int bCount = buf.readVarInt();
                List<BuildingEntry> buildings = new ArrayList<>();
                for (int i = 0; i < bCount; i++)
                    buildings.add(new BuildingEntry(buf.readUUID(),
                            buf.readUtf(), buf.readUtf()));

                return new OpenCompanyManagementPacket(id, name, treasury,
                        wealth, startH, endH, minWage, workers, prices, buildings);
            }
    );

    @Override
    public Type<?> type() { return TYPE; }

    public static void handle(OpenCompanyManagementPacket pkt,
                              IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                net.minecraft.client.Minecraft.getInstance()
                        .setScreen(new CompanyManagementScreen(pkt)));
    }
}