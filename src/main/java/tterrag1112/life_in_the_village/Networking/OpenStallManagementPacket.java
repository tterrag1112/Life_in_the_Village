package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Gui.StallManagementScreen;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Opens the player-owned-stall management screen (merchant arc Phase 5b).
 * Snapshot: stall identity + read-only stats (sales, reputation tier,
 * rent/lease) + a per-item row list carrying the current effective price,
 * the ±20% band [min,max], any custom-price override, and stock.
 */
public record OpenStallManagementPacket(
        UUID stallId,
        String stallLabel,
        long totalSales,
        String reputationTier,
        boolean purchased,
        long rentUntilTick,
        long currentTick,
        List<ItemRow> rows
) implements CustomPacketPayload {

    /**
     * @param itemId        registry id string
     * @param itemName      display name
     * @param basePrice     village dynamic sell price (the band centre)
     * @param effectivePrice custom override if set, else basePrice
     * @param minPrice      band floor (base × 0.8)
     * @param maxPrice      band ceiling (base × 1.2)
     * @param hasCustom     true if a custom override is in effect
     * @param stock         units in the stall chest
     */
    public record ItemRow(String itemId, String itemName, long basePrice,
                          long effectivePrice, long minPrice, long maxPrice,
                          boolean hasCustom, int stock) {}

    public static final Type<OpenStallManagementPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "open_stall_management")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenStallManagementPacket> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeUUID(p.stallId());
                        buf.writeUtf(p.stallLabel());
                        buf.writeVarLong(p.totalSales());
                        buf.writeUtf(p.reputationTier());
                        buf.writeBoolean(p.purchased());
                        buf.writeVarLong(p.rentUntilTick());
                        buf.writeVarLong(p.currentTick());
                        buf.writeVarInt(p.rows().size());
                        for (ItemRow r : p.rows()) {
                            buf.writeUtf(r.itemId());
                            buf.writeUtf(r.itemName());
                            buf.writeVarLong(r.basePrice());
                            buf.writeVarLong(r.effectivePrice());
                            buf.writeVarLong(r.minPrice());
                            buf.writeVarLong(r.maxPrice());
                            buf.writeBoolean(r.hasCustom());
                            buf.writeVarInt(r.stock());
                        }
                    },
                    buf -> {
                        UUID id = buf.readUUID();
                        String label = buf.readUtf();
                        long sales = buf.readVarLong();
                        String rep = buf.readUtf();
                        boolean purchased = buf.readBoolean();
                        long rentUntil = buf.readVarLong();
                        long tick = buf.readVarLong();
                        int n = buf.readVarInt();
                        List<ItemRow> rows = new ArrayList<>();
                        for (int i = 0; i < n; i++) {
                            rows.add(new ItemRow(
                                    buf.readUtf(), buf.readUtf(),
                                    buf.readVarLong(), buf.readVarLong(),
                                    buf.readVarLong(), buf.readVarLong(),
                                    buf.readBoolean(), buf.readVarInt()));
                        }
                        return new OpenStallManagementPacket(id, label, sales, rep,
                                purchased, rentUntil, tick, rows);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(OpenStallManagementPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                net.minecraft.client.Minecraft.getInstance()
                        .setScreen(new StallManagementScreen(pkt)));
    }
}
