package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Gui.VillageBookScreen;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.Buildings.HousePurchaseManager;

import java.util.UUID;

public record VillageActionPacket(
        ActionType action,
        UUID villageId,
        UUID targetId,   // buildingId for house purchase, etc.
        String strParam,
        int intParam
) implements CustomPacketPayload {

    public enum ActionType {
        BUY_HOUSE,
        // Future: APPLY_FOR_LEADERSHIP, FORM_COMPANY, COMPLETE_JOB, etc.
    }

    public static final Type<VillageActionPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "village_action")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf,
            VillageActionPacket> CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUtf(pkt.action().name());
                buf.writeUUID(pkt.villageId());
                buf.writeUUID(pkt.targetId());
                buf.writeUtf(pkt.strParam());
                buf.writeVarInt(pkt.intParam());
            },
            buf -> new VillageActionPacket(
                    ActionType.valueOf(buf.readUtf()),
                    buf.readUUID(), buf.readUUID(),
                    buf.readUtf(), buf.readVarInt())
    );

    @Override
    public Type<?> type() { return TYPE; }

    public static void handle(VillageActionPacket pkt,
                              IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            ServerLevel level = (ServerLevel) player.level();
            VillageSavedData data = VillageSavedData.get(level);

            switch (pkt.action()) {
                case BUY_HOUSE -> {
                    // Find the nearest village leader as the
                    // authority — same rule as the command
                    var leader = level.getEntitiesOfClass(
                                    tterrag1112.life_in_the_village.Entities
                                            .custom.TownspersonMob.class,
                                    player.getBoundingBox().inflate(256),
                                    npc -> npc.getProfession()
                                            == tterrag1112.life_in_the_village
                                            .Profession.Profession.VILLAGE_LEADER)
                            .stream()
                            .min(java.util.Comparator
                                    .comparingDouble(npc -> npc.distanceTo(player)))
                            .orElse(null);

                    if (leader == null) {
                        player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(
                                        "No village leader found nearby."), false);
                        return;
                    }

                    HousePurchaseManager.handlePurchaseRequest(
                            player, leader, pkt.targetId(), level);

                    // Re-send updated screen data
                    VillageBookScreen.sendOpenPacket(
                            player, pkt.villageId(), level, data);
                }
            }
        });
    }
}