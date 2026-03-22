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

        vdata.getGuildById(guildId).ifPresent(guild ->
                vdata.getVillageById(guild.villageId())
                        .ifPresent(village -> {
                            var newQuests = QuestGenerator
                                    .generateQuestsForGuild(
                                            level, guildId, village,
                                            vdata, level.getGameTime());
                            newQuests.forEach(guildData::addQuest);
                            guildData.setDirty();
                        }));

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

        if (!guildData.getActiveQuestsForPlayer(
                player.getUUID()).isEmpty()) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                            "Complete your current quest first."),
                    true);
            return;
        }

        guildData.getQuestById(questId).ifPresent(q -> {
            if (q.getStatus() != Quest.QuestStatus.AVAILABLE) return;

            GuildMember member = guildData.getMember(player.getUUID())
                    .orElse(null);
            if (member == null) return;

            if (!member.currentRank().canAcceptQuest(q.getDifficulty())) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "Your rank is too low for this quest."),
                        true);
                return;
            }

            q.setStatus(Quest.QuestStatus.ACTIVE);
            q.setAssignedPlayer(player.getUUID());
            q.setAcceptedTick(level.getGameTime());
            guildData.setDirty();

            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                            "Quest accepted: " + q.getTitle()),
                    false);
        });

        tterrag1112.life_in_the_village.Networking.VillageSavedData
                vdata = tterrag1112.life_in_the_village.Networking
                .VillageSavedData.get(level);
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

        guildData.getQuestById(questId).ifPresent(q -> {
            if (!player.getUUID().equals(q.getAssignedPlayerId())) return;
            if (!q.isActive()) return;

            boolean complete =
                    q.getCurrentCount() >= q.getTargetCount()
                            || q.getType() == Quest.QuestType.DELIVER
                            || q.getType() == Quest.QuestType.EXPLORE
                            || q.getType() == Quest.QuestType.ESCORT;

            if (!complete) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "Quest not yet complete: "
                                        + q.getCurrentCount()
                                        + "/" + q.getTargetCount()),
                        true);
                return;
            }

            long coinReward = q.getCoinReward();
            int  xpReward   = q.getXpReward();

            PlayerPartySavedData partyData =
                    PlayerPartySavedData.get(level);
            float mult = partyData
                    .getPartyForPlayer(player.getUUID())
                    .map(party -> party.getPartyXpMultiplier())
                    .orElse(1.0f);
            int finalXp = (int)(xpReward * mult);

            // Give coins
            tterrag1112.life_in_the_village.Village.Economy.Currency
                    .CoinHelper.giveCoins(player,
                            tterrag1112.life_in_the_village.Village
                                    .Economy.Currency.CurrencyValue
                                    .of(coinReward));

            // Give XP and check rank up
            guildData.addXp(player.getUUID(), finalXp);
            guildData.getMember(player.getUUID()).ifPresent(m -> {
                GuildRank newRank = GuildRank.fromXp(m.xp() + finalXp);
                if (newRank.ordinal() > m.currentRank().ordinal()) {
                    guildData.setRank(player.getUUID(), newRank);
                    player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(
                                            "Rank up! You are now "
                                                    + newRank.getDisplayName() + "!")
                                    .withStyle(
                                            net.minecraft.ChatFormatting.GOLD),
                            false);

                    vdata.getGuildById(q.getGuildId()).ifPresent(guild ->
                            vdata.getVillageById(guild.villageId())
                                    .ifPresent(village -> {
                                        QuestGenerator
                                                .generateQuestsForGuild(
                                                        level, q.getGuildId(),
                                                        village, vdata,
                                                        level.getGameTime())
                                                .forEach(guildData::addQuest);
                                        guildData.setDirty();
                                    }));
                }
            });

            // Complete quest
            q.setStatus(Quest.QuestStatus.COMPLETED);
            guildData.getMember(player.getUUID()).ifPresent(m ->
                    guildData.updateMember(m.withCompletedQuest(q.getId())));
            guildData.setDirty();

            // Notify party
            partyData.getPartyForPlayer(player.getUUID())
                    .ifPresent(party -> {
                        party.completeQuest();
                        partyData.setDirty();
                    });

            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                            "Quest complete! +"
                                    + tterrag1112.life_in_the_village.Village
                                    .Economy.Currency.CurrencyValue
                                    .of(coinReward)
                                    + " and +" + finalXp + " XP"
                                    + (mult > 1.0f
                                    ? " (party bonus x"
                                    + String.format("%.2f", mult) + ")"
                                    : "")),
                    false);
        });

        GuildScreen.sendOpenPacket(player, guildId, level,
                guildData, vdata, PlayerPartySavedData.get(level));
    }
}