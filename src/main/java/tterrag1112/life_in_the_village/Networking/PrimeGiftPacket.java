package tterrag1112.life_in_the_village.Networking;

import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Entities.GiftPrimer;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.UUID;

/**
 * Client→server: "I'm about to gift this NPC — treat my next
 * right-click on them as a gift no matter what item I'm holding."
 * Fired from the {@code SocialVerbsPanel} gift primer button.
 *
 * <p>Server activates the {@link GiftPrimer} entry (expires after
 * 60s) and chats the player a hint. The dispatcher in
 * {@code NpcInteractionHandler} consumes the primer before any
 * other physical-item route.</p>
 */
public record PrimeGiftPacket(UUID npcId) implements CustomPacketPayload {

    public static final Type<PrimeGiftPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "prime_gift"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PrimeGiftPacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> buf.writeUUID(pkt.npcId),
                    buf -> new PrimeGiftPacket(buf.readUUID()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(PrimeGiftPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!(player.level() instanceof ServerLevel level)) return;
            TownspersonMob npc = TownspersonMob.findByUUID(level, pkt.npcId).orElse(null);
            if (npc == null) return;
            GiftPrimer.prime(player.getUUID(), npc.getUUID(), level.getGameTime());
            player.displayClientMessage(
                    Component.literal("[" + npc.getNpcName()
                                    + "] expects a gift — right-click me with the item.")
                            .withStyle(ChatFormatting.GOLD),
                    /* actionBar */ true);
        });
    }
}
