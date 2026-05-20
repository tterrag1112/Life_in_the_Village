package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Entities.NpcProfileHub;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.UUID;

/**
 * Sent client→server when the player presses an action button inside the
 * NPC profile screen (trade, assign work, give gift, etc.).
 */
public record NpcProfileActionPacket(UUID npcId, Action action)
        implements CustomPacketPayload {

    /**
     * Actions the player can trigger from the NPC profile screen.
     * Track 4 redesign: action-bar buttons that opened secondary GUIs
     * are gone (TRADE / OPEN_GUILD / ASSIGN_WORK / OPEN_COMPANY_WORKER /
     * SHOW_VILLAGE_BOOK / SHOW_CRAFTING_ORDERS / RENT_STALL / GIVE_GIFT
     * are now physical/contextual routes — see
     * {@code NpcInteractionHandler} dispatch priority). What remains:
     * <ul>
     *   <li>{@link #OPEN_PROFILE} — corner "View profile" on
     *       BusinessFrontScreen.</li>
     *   <li>{@link #OPEN_NAV_TARGET} — the single nav button on the
     *       informational profile, target resolved server-side per
     *       Step-5 priority rule.</li>
     * </ul>
     */
    public enum Action {
        OPEN_PROFILE,
        OPEN_NAV_TARGET,
        /** Used only by {@code BusinessFrontScreen} as the primary
         *  "open the profession's main UI" action — server-side
         *  resolves per profession/building-type into the right
         *  screen (trade, library borrow, scribe commission, etc.). */
        OPEN_PRIMARY
    }

    public static final Type<NpcProfileActionPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "npc_profile_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcProfileActionPacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeUUID(pkt.npcId());
                        buf.writeUtf(pkt.action().name());
                    },
                    buf -> {
                        UUID npcId = buf.readUUID();
                        Action action;
                        try {
                            action = Action.valueOf(buf.readUtf());
                        } catch (IllegalArgumentException e) {
                            action = Action.OPEN_PROFILE;
                        }
                        return new NpcProfileActionPacket(npcId, action);
                    });

    @Override
    public Type<?> type() { return TYPE; }

    public static void handle(NpcProfileActionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!(sp.level() instanceof ServerLevel level)) return;
            NpcProfileHub.handleAction(pkt.npcId(), pkt.action(), sp, level);
        });
    }
}
