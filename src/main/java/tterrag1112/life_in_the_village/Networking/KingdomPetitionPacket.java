package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Kingdom.Audience.AudienceDriver;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.UUID;

/**
 * Track D3.5A — client → server packet for petition resolution +
 * withdrawal.
 *
 * <p>Submission isn't a packet verb in Slice A — pending complex
 * payload encoding (CHARTER_REQUEST carries CharterParams etc.)
 * the realistic surface is debug commands or future
 * payload-specific packets in Slice B+. Slice A's GUI surfaces
 * existing pending petitions for ruler resolution; submission via
 * GUI lands when the dedicated audience screen ships.
 *
 * @param action      APPROVE / DENY / WITHDRAW.
 * @param kingdomId   target kingdom whose audience chamber holds
 *                    the petition.
 * @param petitionId  petition to act on.
 * @param note        free-form one-line note attached to the
 *                    resolution (server clamps); empty for
 *                    WITHDRAW.
 */
public record KingdomPetitionPacket(
        Action action,
        UUID kingdomId,
        UUID petitionId,
        String note
) implements CustomPacketPayload {

    public enum Action { APPROVE, DENY, WITHDRAW }

    public static final Type<KingdomPetitionPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "kingdom_petition"));

    public static final StreamCodec<RegistryFriendlyByteBuf,
            KingdomPetitionPacket> CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUtf(pkt.action().name());
                buf.writeUUID(pkt.kingdomId());
                buf.writeUUID(pkt.petitionId());
                buf.writeUtf(pkt.note());
            },
            buf -> new KingdomPetitionPacket(
                    Action.valueOf(buf.readUtf()),
                    buf.readUUID(),
                    buf.readUUID(),
                    buf.readUtf()
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** Ruler-only check for APPROVE / DENY. WITHDRAW allowed for petitioner. */
    public static void handle(KingdomPetitionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) ctx.player();
            ServerLevel level   = (ServerLevel) player.level();
            VillageSavedData data = VillageSavedData.get(level);
            Kingdom kingdom = data.getKingdomById(pkt.kingdomId()).orElse(null);
            if (kingdom == null) return;

            UUID actor = player.getUUID();
            long tick  = level.getGameTime();
            String note = pkt.note() == null ? "" : pkt.note();
            if (note.length() > 256) note = note.substring(0, 256);

            switch (pkt.action()) {
                case APPROVE -> {
                    if (!isRuler(kingdom, actor)) {
                        player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(
                                        "Only the ruler can approve petitions."), false);
                        return;
                    }
                    var r = AudienceDriver.approve(level, data, kingdom,
                            pkt.petitionId(), actor, tick, note);
                    player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(
                                    r.applied() ? "Approved: " + r.reason()
                                            : "Approval failed: " + r.reason()),
                            false);
                }
                case DENY -> {
                    if (!isRuler(kingdom, actor)) {
                        player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(
                                        "Only the ruler can deny petitions."), false);
                        return;
                    }
                    var r = AudienceDriver.deny(level, data, kingdom,
                            pkt.petitionId(), actor, tick, note);
                    player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(
                                    r.applied() ? "Denied"
                                            : "Denial failed: " + r.reason()),
                            false);
                }
                case WITHDRAW -> {
                    var r = AudienceDriver.withdraw(kingdom, pkt.petitionId(),
                            actor, tick);
                    player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(
                                    r.applied() ? "Petition withdrawn"
                                            : "Withdraw failed: " + r.reason()),
                            false);
                }
            }
            data.setDirty();
        });
    }

    /**
     * Player-as-ruler check. v1: only the kingdom's ruler UUID
     * matches via {@code Kingdom.getRulerUuid}. NPC-ruler kingdoms
     * always reject player-side resolve verbs (NPC rulers
     * resolve via the AI tick path in Slice B+).
     */
    private static boolean isRuler(Kingdom kingdom, UUID playerUuid) {
        return kingdom.getRulerPlayerId().filter(playerUuid::equals).isPresent();
    }
}
