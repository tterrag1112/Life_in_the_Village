// src/main/java/tterrag1112/life_in_the_village/Networking/GuildActionPacket.java
package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Gui.GuildScreen;
import tterrag1112.life_in_the_village.Guilds.Adventurer.*;
import tterrag1112.life_in_the_village.Guilds.PlayerPartySavedData;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Quests.Quest;
import tterrag1112.life_in_the_village.Quests.QuestGiver;
import tterrag1112.life_in_the_village.Quests.QuestSavedData;
import tterrag1112.life_in_the_village.Quests.QuestStatus;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;

import java.util.UUID;

public record GuildActionPacket(
        ActionType action,
        UUID       guildId,
        UUID       playerId,
        @Nullable UUID questId,
        String     strParam
) implements CustomPacketPayload {

    public enum ActionType {
        JOIN_GUILD,
        ACCEPT_QUEST,
        TURN_IN_QUEST,
        REFRESH_SCREEN
    }

    public static final Type<GuildActionPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "guild_action")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf,
            GuildActionPacket> CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeVarInt(pkt.action().ordinal());
                buf.writeUUID(pkt.guildId());
                buf.writeUUID(pkt.playerId());
                buf.writeBoolean(pkt.questId() != null);
                if (pkt.questId() != null) buf.writeUUID(pkt.questId());
                buf.writeUtf(pkt.strParam());
            },
            buf -> {
                ActionType action = ActionType.values()[buf.readVarInt()];
                UUID guildId      = buf.readUUID();
                UUID playerId     = buf.readUUID();
                UUID questId      = buf.readBoolean() ? buf.readUUID() : null;
                String strParam   = buf.readUtf();
                return new GuildActionPacket(action, guildId,
                        playerId, questId, strParam);
            }
    );

    @Override
    public Type<?> type() { return TYPE; }

    // =========================================================================
    // Server-side handler
    // =========================================================================

    public static void handle(GuildActionPacket pkt,
                              IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            ServerLevel level = (ServerLevel) player.level();

            PlayerGuildData guildData = PlayerGuildData.get(level);
            tterrag1112.life_in_the_village.Networking.VillageSavedData
                    vdata = tterrag1112.life_in_the_village.Networking
                    .VillageSavedData.get(level);

            switch (pkt.action()) {
                case JOIN_GUILD    -> handleJoin(player, pkt.guildId(),
                        guildData, vdata, level);
                case ACCEPT_QUEST  -> handleAccept(player, pkt.guildId(),
                        pkt.questId(), guildData, level);
                case TURN_IN_QUEST -> handleTurnIn(player, pkt.guildId(),
                        pkt.questId(), guildData, level);
                case REFRESH_SCREEN -> GuildScreen.sendOpenPacket(
                        player, pkt.guildId(), level, guildData, vdata,
                        PlayerPartySavedData.get(level));
            }
        });
    }

    // ── JOIN ──────────────────────────────────────────────────────────────────

    private static void handleJoin(ServerPlayer player, UUID guildId,
                                   PlayerGuildData guildData,
                                   tterrag1112.life_in_the_village
                                           .Networking.VillageSavedData vdata,
                                   ServerLevel level) {
        if (guildData.isRegistered(player.getUUID())) return;

        guildData.registerPlayer(player.getUUID(),
                player.getName().getString(), guildId);

        // Post the guild's opening offer set (F2 quest pool).
        vdata.getGuildById(guildId).ifPresent(guild ->
                vdata.getVillageById(guild.villageId())
                        .ifPresent(village ->
                                GuildQuests.refreshOffers(level, guild, village, vdata)));

        player.displayClientMessage(
                net.minecraft.network.chat.Component.literal(
                        "Welcome to the guild! Your rank is Bronze."),
                false);

        GuildScreen.sendOpenPacket(player, guildId, level,
                guildData, vdata, PlayerPartySavedData.get(level));
    }

    // ── ACCEPT ────────────────────────────────────────────────────────────────

    private static void handleAccept(ServerPlayer player, UUID guildId,
                                     @Nullable UUID questId,
                                     PlayerGuildData guildData,
                                     ServerLevel level) {
        if (questId == null) return;

        tterrag1112.life_in_the_village.Networking.VillageSavedData
                vdata = tterrag1112.life_in_the_village.Networking
                .VillageSavedData.get(level);
        GuildMember member = guildData.getMember(player.getUUID()).orElse(null);

        // Look up the title before accepting (accept removes the offer from the pool).
        String title = QuestSavedData.get(level).guildOffer(guildId, questId)
                .map(Quest::title).orElse("");

        // The GUI path allows a single active guild quest at a time (legacy parity).
        GuildQuests.AcceptResult result =
                GuildQuests.accept(level, player, guildId, questId, member, 1);
        switch (result) {
            case TOO_MANY_ACTIVE -> player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                            "Complete your current quest first."), true);
            case RANK_TOO_LOW -> player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                            "Your rank is too low for this quest."), true);
            case OK -> player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                            "Quest accepted: " + title), false);
            default -> { /* NOT_FOUND / NOT_AVAILABLE — silently re-render (legacy parity) */ }
        }

        GuildScreen.sendOpenPacket(player, guildId, level,
                guildData, vdata, PlayerPartySavedData.get(level));
    }

    // ── TURN IN ───────────────────────────────────────────────────────────────

    private static void handleTurnIn(ServerPlayer player, UUID guildId,
                                     @Nullable UUID questId,
                                     PlayerGuildData guildData,
                                     ServerLevel level) {
        if (questId == null) return;

        tterrag1112.life_in_the_village.Networking.VillageSavedData
                vdata = tterrag1112.life_in_the_village.Networking
                .VillageSavedData.get(level);

        Quest quest = QuestSavedData.get(level).quest(player.getUUID(), questId).orElse(null);
        if (quest != null && quest.giver().type() == QuestGiver.Type.GUILD
                && quest.status() == QuestStatus.ACTIVE) {

            GuildQuests.TurnInResult result = GuildQuests.turnIn(level, player, quest, vdata);
            if (!result.completed()) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "Quest not yet complete."), true);
            } else {
                if (result.rankedUp()) {
                    player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(
                                            "Rank up! You are now "
                                                    + result.newRank().getDisplayName() + "!")
                                    .withStyle(net.minecraft.ChatFormatting.GOLD), false);
                }
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "Quest complete! +" + CurrencyValue.of(result.coins())
                                        + " and +" + result.xpGranted() + " XP"
                                        + (result.multiplier() > 1.0f
                                        ? " (party bonus x"
                                        + String.format("%.2f", result.multiplier()) + ")"
                                        : "")), false);
            }
        }

        GuildScreen.sendOpenPacket(player, guildId, level,
                guildData, vdata, PlayerPartySavedData.get(level));
    }
}