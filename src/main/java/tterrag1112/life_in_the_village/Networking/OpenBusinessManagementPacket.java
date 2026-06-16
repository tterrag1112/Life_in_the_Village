package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Gui.BusinessManagementScreen;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.*;

public record OpenBusinessManagementPacket(
        UUID businessId,
        String businessName,
        long treasuryBronze,
        long playerWealthBronze,
        int workStartHour,
        int workEndHour,
        long effectiveMinWage,
        List<WorkerEntry> workers,
        List<PriceEntry> priceOverrides,
        List<BuildingEntry> buildings,
        String activeSection,          // section name to restore, empty = default
        List<ProductionEntry> productionEntries  // PB3 — one per PRODUCER worker with a goal

) implements CustomPacketPayload {

    public record WorkerEntry(UUID npcId, String npcName,
                              String role, long wagePerDay,
                              String assignedItemId,
                              int dailyTargetCount) {}

    public record PriceEntry(String itemId, String itemName,
                             long pricePerUnit) {}

    public record BuildingEntry(UUID buildingId, String buildingName,
                                String buildingType) {}

    /**
     * PB3 — server-authoritative snapshot of one PRODUCER worker's production goal.
     *
     * @param npcId          the worker's UUID (links back to WorkerEntry)
     * @param npcName        display name (pre-resolved server-side)
     * @param itemId         registry key of the goal item
     * @param itemName       human-readable item name
     * @param dailyTarget    the assigned daily target count
     * @param currentStock   total of the goal item across all business buildings
     * @param boardStatus    task board assignment status: "NONE"/"OPEN"/"CLAIMED"/"IN_PROGRESS"
     * @param diagnostic     see ProductionDiagnostic enum name
     * @param requiredSkill  e.g. "BLACKSMITHING ≥50", empty if no gate
     */
    public record ProductionEntry(
            UUID   npcId,
            String npcName,
            String itemId,
            String itemName,
            int    dailyTarget,
            int    currentStock,
            String boardStatus,
            String diagnostic,
            String requiredSkill
    ) {}

    /**
     * PB3 — why a production goal is or isn't making progress.
     *
     * PRODUCING           — task is claimed/in-progress, or stock already at/above target.
     * IDLE_NO_TASK        — goal + recipe exist but no board task generated yet.
     * IDLE_OPEN           — board task present but unclaimed (no eligible worker).
     * BLOCKED_NO_RECIPE   — assignedItemId has no SkillRecipes entry.
     * BLOCKED_MISSING_INPUTS — recipe found but required inputs absent in building storage.
     */
    public enum ProductionDiagnostic {
        PRODUCING,
        IDLE_NO_TASK,
        IDLE_OPEN,
        BLOCKED_NO_RECIPE,
        BLOCKED_MISSING_INPUTS
    }

    // =========================================================================
    // Packet identity
    // =========================================================================

    public static final Type<OpenBusinessManagementPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "open_company_management")
    );

    // =========================================================================
    // Stream codec
    // =========================================================================

    public static final StreamCodec<RegistryFriendlyByteBuf,
            OpenBusinessManagementPacket> CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.businessId());
                buf.writeUtf(pkt.businessName());
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

                buf.writeUtf(pkt.activeSection());

                // PB3 production entries
                buf.writeVarInt(pkt.productionEntries().size());
                for (ProductionEntry e : pkt.productionEntries()) {
                    buf.writeUUID(e.npcId());
                    buf.writeUtf(e.npcName());
                    buf.writeUtf(e.itemId());
                    buf.writeUtf(e.itemName());
                    buf.writeVarInt(e.dailyTarget());
                    buf.writeVarInt(e.currentStock());
                    buf.writeUtf(e.boardStatus());
                    buf.writeUtf(e.diagnostic());
                    buf.writeUtf(e.requiredSkill());
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

                String activeSection = buf.readUtf();

                int eCount = buf.readVarInt();
                List<ProductionEntry> prodEntries = new ArrayList<>();
                for (int i = 0; i < eCount; i++)
                    prodEntries.add(new ProductionEntry(
                            buf.readUUID(), buf.readUtf(), buf.readUtf(),
                            buf.readUtf(), buf.readVarInt(), buf.readVarInt(),
                            buf.readUtf(), buf.readUtf(), buf.readUtf()));

                return new OpenBusinessManagementPacket(id, name, treasury,
                        wealth, startH, endH, minWage, workers, prices,
                        buildings, activeSection, prodEntries);
            }
    );

    @Override
    public Type<?> type() { return TYPE; }

    public static void handle(OpenBusinessManagementPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            BusinessManagementScreen screen = new BusinessManagementScreen(pkt);
            if (!pkt.activeSection().isEmpty()) {
                try {
                    screen.currentSection = BusinessManagementScreen.Section.valueOf(pkt.activeSection());
                } catch (IllegalArgumentException ignored) {}
            }
            mc.setScreen(screen);
        });
    }
}
