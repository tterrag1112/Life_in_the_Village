package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/**
 * Client-side snapshot of a single crafting commission.
 * Built server-side by {@link #fromOrder} and transmitted inside
 * {@link OpenVillageBookPacket}.
 */
public record CommissionEntry(
        UUID    orderId,
        String  itemId,              // e.g. "minecraft:iron_sword"
        int     quantity,
        int     deliveredCount,
        long    bronzeReward,
        String  status,              // CraftingOrder.OrderStatus name
        String  urgency,             // CraftingOrder.Urgency name
        String  relevantProfession,  // PlayerProfession name, "" if none
        boolean claimedBySelf,
        boolean claimedByOther,
        String  claimingPlayerName,  // "" if unclaimed
        String  requestingNpcName    // "" if economy-driven
) {
    public static final StreamCodec<RegistryFriendlyByteBuf, CommissionEntry> STREAM_CODEC =
            StreamCodec.of(
                    (buf, e) -> {
                        buf.writeUUID(e.orderId());
                        buf.writeUtf(e.itemId());
                        buf.writeVarInt(e.quantity());
                        buf.writeVarInt(e.deliveredCount());
                        buf.writeVarLong(e.bronzeReward());
                        buf.writeUtf(e.status());
                        buf.writeUtf(e.urgency());
                        buf.writeUtf(e.relevantProfession());
                        buf.writeBoolean(e.claimedBySelf());
                        buf.writeBoolean(e.claimedByOther());
                        buf.writeUtf(e.claimingPlayerName());
                        buf.writeUtf(e.requestingNpcName());
                    },
                    buf -> new CommissionEntry(
                            buf.readUUID(), buf.readUtf(),
                            buf.readVarInt(), buf.readVarInt(),
                            buf.readVarLong(),
                            buf.readUtf(), buf.readUtf(), buf.readUtf(),
                            buf.readBoolean(), buf.readBoolean(),
                            buf.readUtf(), buf.readUtf()
                    )
            );

    /** Server-side factory — must NOT be called on the client. */
    public static CommissionEntry fromOrder(
            tterrag1112.life_in_the_village.Village.Economy.CraftingOrder order,
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.server.level.ServerPlayer player) {

        VillageSavedData vdata = VillageSavedData.get(level);

        String urgency       = order.getUrgency(level.getGameTime()).name();
        boolean claimedBySelf  = order.isClaimedBy(player.getUUID());
        boolean claimedByOther = order.isClaimed() && !claimedBySelf;

        String claimingPlayerName = "";
        if (order.isClaimed()) {
            net.minecraft.server.level.ServerPlayer claimer =
                    level.getServer().getPlayerList().getPlayer(order.getClaimedByPlayerId());
            if (claimer != null) claimingPlayerName = claimer.getName().getString();
        }

        String requestingNpcName = "";
        java.util.UUID npcId = order.getRequestingNpcId();
        if (!npcId.equals(new java.util.UUID(0, 0))) {
            requestingNpcName = tterrag1112.life_in_the_village.Entities.custom.TownspersonMob
                    .findByUUID(level, npcId)
                    .map(n -> n.getNpcName()).orElse("");
        }

        String relevantProfession = "";
        net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(net.minecraft.resources.Identifier.parse(order.getItemId()))
                .map(h -> h.value()).orElse(null);
        if (item != null) {
            tterrag1112.life_in_the_village.Profession.PlayerProfession prof =
                    tterrag1112.life_in_the_village.Profession.PlayerProfessionRelevance.forItem(item);
            if (prof != null) relevantProfession = prof.name();
        }

        return new CommissionEntry(
                order.getOrderId(), order.getItemId(),
                order.getQuantity(), order.getDeliveredCount(),
                order.getBronzeReward(),
                order.getStatus().name(), urgency, relevantProfession,
                claimedBySelf, claimedByOther,
                claimingPlayerName, requestingNpcName
        );
    }
}
