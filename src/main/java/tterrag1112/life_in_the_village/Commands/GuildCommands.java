package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Events.ReputationEvents;
import tterrag1112.life_in_the_village.Guild.*;
import tterrag1112.life_in_the_village.Guild.Adventurers.AdventurerGroup;
import tterrag1112.life_in_the_village.Guild.Adventurers.AdventurerReputationManager;
import tterrag1112.life_in_the_village.Guild.Adventurers.AdventurerReputationTier;
import tterrag1112.life_in_the_village.Guild.Adventurers.AdventurerSavedData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Trade.Caravan;
import tterrag1112.life_in_the_village.Village.Economy.Trade.CaravanSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoad;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoute;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.VillageWarningSystem;

import java.util.*;
import java.util.stream.Collectors;

public class GuildCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("guild")
                        .then(Commands.literal("spawncaravan")
                                .executes(ctx -> spawnTestCaravan(ctx.getSource()))
                                .then(Commands.literal("caravanspeed")
                                        .then(Commands.argument("multiplier",
                                                        IntegerArgumentType.integer(1, 1000))
                                                .executes(ctx -> setCaravanSpeed(
                                                        ctx.getSource(),
                                                        IntegerArgumentType
                                                                .getInteger(ctx, "multiplier"))))))

                        // /guild setrank <player> <rank>
                        .then(Commands.literal("setrank")
                                .then(Commands.argument("player",
                                                net.minecraft.commands.arguments.EntityArgument.player())
                                        .then(Commands.argument("rank",
                                                        StringArgumentType.word())
                                                .suggests((ctx, builder) -> {
                                                    for (GuildRank rank : GuildRank.values()) {
                                                        builder.suggest(rank.name());
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .executes(ctx -> setGuildRank(
                                                        ctx.getSource(),
                                                        net.minecraft.commands.arguments.EntityArgument
                                                                .getPlayer(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "rank")
                                                ))
                                        )
                                )
                        )
                        .then(Commands.literal("setrep")
                                .then(Commands.argument("village",
                                                StringArgumentType.word())
                                        .then(Commands.argument("amount",
                                                        IntegerArgumentType.integer(-999, 999))
                                                .executes(ctx -> setVillageRep(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "village"),
                                                        IntegerArgumentType
                                                                .getInteger(ctx, "amount")
                                                ))
                                        )
                                )
                        )

                        // /guild status
                        .then(Commands.literal("status")
                                .executes(ctx -> guildStatus(ctx.getSource())))
                        .then(Commands.literal("warnings")
                                .executes(ctx -> listWarnings(ctx.getSource())))

                        // /guild accept <questId>
                        .then(Commands.literal("accept")
                                .then(Commands.argument("questId",
                                                StringArgumentType.word())
                                        .executes(ctx -> acceptQuest(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "questId")
                                        ))
                                )
                        )

                        // /guild complete
                        .then(Commands.literal("complete")
                                .executes(ctx -> completeQuests(ctx.getSource())))

                        // /guild quests
                        .then(Commands.literal("quests")
                                .executes(ctx -> listQuests(ctx.getSource())))
                        .then(Commands.literal("adventurer")
                                        // /adventurer join — join the nearest eligible group
                                        .then(Commands.literal("join")
                                                .executes(ctx -> joinNearestGroup(ctx.getSource())))

// /adventurer leave — leave all joined groups
                                        .then(Commands.literal("leave")
                                                .executes(ctx -> leaveAllGroups(ctx.getSource())))

// /adventurer lead — take leadership of nearest joined group
                                        .then(Commands.literal("lead")
                                                .executes(ctx -> leadNearestGroup(ctx.getSource())))

// /adventurer follow — relinquish leadership, go back to following
                                        .then(Commands.literal("follow")
                                                .executes(ctx -> followNearestGroup(ctx.getSource())))

// /adventurer status — show info about groups the player has joined
                                        .then(Commands.literal("status")
                                                .executes(ctx -> joinedGroupStatus(ctx.getSource())))

                        // /adventurer spawn
                        .then(Commands.literal("spawn")
                                .executes(ctx -> spawnTestGroup(ctx.getSource())))

                        // /adventurer list
                        .then(Commands.literal("list")
                                .executes(ctx -> listGroups(ctx.getSource())))

                        // /adventurer find
                        .then(Commands.literal("find")
                                .executes(ctx -> findNearestGroup(ctx.getSource())))

                        // /adventurer clear
                        .then(Commands.literal("clear")
                                .executes(ctx -> clearAllGroups(ctx.getSource())))
                )
        );
    }

    private static int guildStatus(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = src.getLevel();
        PlayerGuildData guildData = PlayerGuildData.get(level);

        if (!guildData.isRegistered(player.getUUID())) {
            src.sendFailure(Component.literal(
                    "You are not registered with any guild. "
                            + "Visit a Guild Hall to register."));
            return 0;
        }

        guildData.getMember(player.getUUID()).ifPresent(member -> {
            src.sendSuccess(() -> Component.literal(
                    "=== Guild Status ===\n"
                            + "Rank: " + member.currentRank().getDisplayName() + "\n"
                            + "XP: " + member.xp() + "\n"
                            + "To next rank: " + member.xpToNextRank() + " XP\n"
                            + "Quests completed: "
                            + member.completedQuestIds().size()), false);

            // Active quests
            guildData.getActiveQuestsForPlayer(player.getUUID())
                    .forEach(q -> src.sendSuccess(() -> Component.literal(
                                    "  [ACTIVE] " + q.getTitle()
                                            + " (" + (int)(q.getCompletionFraction() * 100) + "%)"),
                            false));
        });
        return 1;
    }

    private static int acceptQuest(CommandSourceStack src,
                                   String questIdPrefix) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = src.getLevel();
        PlayerGuildData guildData = PlayerGuildData.get(level);

        if (!guildData.isRegistered(player.getUUID())) {
            src.sendFailure(Component.literal(
                    "You are not registered with any guild."));
            return 0;
        }

        // Find quest by ID prefix
        Quest quest = guildData.getAllQuestsForGuild(
                        guildData.getMember(player.getUUID())
                                .map(GuildMember::guildId)
                                .orElse(UUID.randomUUID())
                ).stream()
                .filter(q -> q.getId().toString()
                        .startsWith(questIdPrefix)
                        && q.getStatus() == Quest.QuestStatus.AVAILABLE)
                .findFirst()
                .orElse(null);

        if (quest == null) {
            src.sendFailure(Component.literal(
                    "Quest not found or not available."));
            return 0;
        }

        // Check rank
        GuildRank rank = guildData.getMember(player.getUUID())
                .map(GuildMember::currentRank)
                .orElse(GuildRank.BRONZE);

        if (!rank.canAcceptQuest(quest.getDifficulty())) {
            src.sendFailure(Component.literal(
                    "Your rank is too low for this quest. "
                            + "Required: "
                            + quest.getDifficulty().minRank().getDisplayName()));
            return 0;
        }

        // Check not already on a quest of same type
        boolean hasActive = guildData
                .getActiveQuestsForPlayer(player.getUUID())
                .size() >= 3;
        if (hasActive) {
            src.sendFailure(Component.literal(
                    "You already have 3 active quests. "
                            + "Complete some first."));
            return 0;
        }

        quest.setStatus(Quest.QuestStatus.ACTIVE);
        quest.setAssignedPlayer(player.getUUID());
        quest.setAcceptedTick(level.getGameTime());
        guildData.setDirty();

        src.sendSuccess(() -> Component.literal(
                "Quest accepted: " + quest.getTitle() + "\n"
                        + quest.getDescription()), false);
        return 1;
    }

    private static int completeQuests(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = src.getLevel();
        PlayerGuildData guildData = PlayerGuildData.get(level);
        VillageSavedData data = VillageSavedData.get(level);

        var active = guildData.getActiveQuestsForPlayer(player.getUUID());
        if (active.isEmpty()) {
            src.sendFailure(Component.literal(
                    "You have no active quests."));
            return 0;
        }

        int completed = 0;
        for (Quest quest : active) {
            if (!isQuestComplete(player, quest, level, data)) continue;

            // Award rewards
            CurrencyValue reward = CurrencyValue.of(quest.getCoinReward());
            var playerContainer = new net.minecraft.world.SimpleContainer(
                    player.getInventory().getContainerSize());
            for (int i = 0; i < player.getInventory()
                    .getContainerSize(); i++) {
                playerContainer.setItem(i,
                        player.getInventory().getItem(i).copy());
            }
            CoinHelper.giveCoins(playerContainer, reward);
            for (int i = 0; i < player.getInventory()
                    .getContainerSize(); i++) {
                player.getInventory().setItem(i,
                        playerContainer.getItem(i));
            }

            // XP reward
            guildData.addXp(player.getUUID(), quest.getXpReward());

            // Check rank up
            guildData.getMember(player.getUUID()).ifPresent(member -> {
                GuildRank oldRank = member.rank();
                GuildRank newRank = member.currentRank();
                if (oldRank != newRank) {
                    player.displayClientMessage(
                            Component.literal("You have ranked up to "
                                            + newRank.getDisplayName() + "!")
                                    .withStyle(net.minecraft.ChatFormatting.GOLD),
                            false);
                }
            });

            // Reputation gain
            ReputationEvents
                    .modifyReputationForAllVillages(player, level, data, 10);

            // Special reward for high ranks
            if (quest.hasSpecialReward()) {
                giveSpecialReward(player, quest);
            }

            // Consume items for gather/deliver quests
            if (quest.getType() == Quest.QuestType.GATHER
                    || quest.getType() == Quest.QuestType.DELIVER) {
                consumeQuestItems(player, quest);
            }

            quest.setStatus(Quest.QuestStatus.COMPLETED);
            guildData.setDirty();
            completed++;

            src.sendSuccess(() -> Component.literal(
                    "Quest complete: " + quest.getTitle()
                            + "\nReward: " + reward
                            + " + " + quest.getXpReward() + " XP"), false);
        }

        if (completed == 0) {
            src.sendFailure(Component.literal(
                    "No quests are ready to complete yet. "
                            + "Check your progress with /guild status."));
        }

        return completed;
    }

    private static int listQuests(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = src.getLevel();
        PlayerGuildData guildData = PlayerGuildData.get(level);

        if (!guildData.isRegistered(player.getUUID())) {
            src.sendFailure(Component.literal(
                    "You are not registered with any guild."));
            return 0;
        }

        GuildMember member = guildData.getMember(player.getUUID())
                .orElse(null);
        if (member == null) return 0;

        var available = guildData.getAvailableQuestsForGuild(
                member.guildId(), member.currentRank());

        if (available.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    "No quests available at your rank right now."), false);
            return 1;
        }

        src.sendSuccess(() -> Component.literal(
                "=== Available Quests ==="), false);
        available.forEach(q ->
                src.sendSuccess(() -> Component.literal(
                                "[" + q.getDifficulty().name() + "] "
                                        + q.getTitle()
                                        + " | Reward: " + CurrencyValue.of(q.getCoinReward())
                                        + " + " + q.getXpReward() + " XP"
                                        + " | ID: " + q.getId().toString().substring(0, 8)),
                        false)
        );
        return 1;
    }

    // --- Helpers ---

    private static boolean isQuestComplete(ServerPlayer player,
                                           Quest quest, ServerLevel level, VillageSavedData data) {
        return switch (quest.getType()) {
            case GATHER -> playerHasItems(player, quest.getTargetItem(),
                    quest.getTargetCount());
            case HUNT   -> quest.getCurrentCount() >= quest.getTargetCount();
            case EXPLORE -> quest.getCurrentCount() >= 1;
            case DELIVER -> playerHasItems(player, quest.getTargetItem(), 1)
                    && isNearTargetVillage(player, quest, data);
            case ESCORT -> quest.getCurrentCount() >= 1;
        };
    }

    private static boolean playerHasItems(ServerPlayer player,
                                          net.minecraft.world.item.Item item,
                                          int count) {
        if (item == null) return true;
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total >= count;
    }

    private static boolean isNearTargetVillage(ServerPlayer player,
                                               Quest quest, VillageSavedData data) {
        if (quest.getTargetVillageId() == null) return false;
        return data.getVillageById(quest.getTargetVillageId())
                .flatMap(v -> v.getBounds(data))
                .map(b -> b.inflate(32).contains(
                        player.getX(), player.getY(), player.getZ()))
                .orElse(false);
    }

    private static void consumeQuestItems(ServerPlayer player,
                                          Quest quest) {
        if (quest.getTargetItem() == null) return;
        int remaining = quest.getTargetCount();
        for (int i = 0; i < player.getInventory().getContainerSize()
                && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(quest.getTargetItem())) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
    }

    private static void giveSpecialReward(ServerPlayer player,
                                          Quest quest) {
        // High rank special rewards
        ItemStack reward = switch (quest.getDifficulty()) {
            case ELITE     -> new ItemStack(Items.ENCHANTED_GOLDEN_APPLE);
            case LEGENDARY -> new ItemStack(Items.NETHER_STAR);
            default        -> ItemStack.EMPTY;
        };
        if (!reward.isEmpty()) {
            player.addItem(reward);
        }
    }

    private static int spawnTestGroup(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = src.getLevel();

        AdventurerSavedData data = AdventurerSavedData.get(level);
        VillageSavedData villageData = VillageSavedData.get(level);

        // Find nearest village to use as home, or use a dummy UUID
        BlockPos playerPos = player.blockPosition();
        UUID homeVillageId = villageData.getAllVillages().stream()
                .min(Comparator.comparingDouble(v ->
                        v.getBounds(villageData)
                                .map(b -> BlockPos.containing(b.getCenter())
                                        .distSqr(playerPos))
                                .orElse(Double.MAX_VALUE)))
                .map(v -> v.getId())
                .orElse(UUID.randomUUID()); // no village nearby, use dummy

        // Target 100 blocks away from player
        BlockPos target = playerPos.offset(100, 0, 0);

        AdventurerGroup group = AdventurerGroup.create(
                homeVillageId,
                3,           // 3 members
                playerPos,   // start at player
                target,
                level.getGameTime()
        );

        data.addGroup(group);

        // Force immediate spawn since player is right here
        data.tick(level, villageData);

        src.sendSuccess(() -> Component.literal(
                        "Spawned test adventurer group "
                                + group.getGroupId().toString().substring(0, 8)
                                + " at your position with 3 members."
                                + "\nState: " + group.getState()
                                + "\nTarget: " + target.toShortString()),
                false);
        return 1;
    }

    private static int listGroups(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = src.getLevel();

        AdventurerSavedData data = AdventurerSavedData.get(level);
        List<AdventurerGroup> groups = data.getAllGroups();

        if (groups.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    "No adventurer groups exist."), false);
            return 0;
        }

        src.sendSuccess(() -> Component.literal(
                        "=== Adventurer Groups (" + groups.size() + ") ==="),
                false);

        groups.forEach(g -> src.sendSuccess(() -> Component.literal(
                        "ID: " + g.getGroupId().toString().substring(0, 8)
                                + " | Members: " + g.getMemberCount()
                                + " | State: " + g.getState()
                                + " | Pos: " + g.getCurrentPos().toShortString()
                                + " | Spawned: " + g.isSpawned()
                                + (g.getActiveQuest() != null
                                ? " | Quest: " + g.getActiveQuest().getTitle()
                                : " | Quest: none")),
                false));

        return groups.size();
    }

    private static int findNearestGroup(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = src.getLevel();

        AdventurerSavedData data = AdventurerSavedData.get(level);
        BlockPos playerPos = player.blockPosition();

        data.getAllGroups().stream()
                .min(Comparator.comparingDouble(g ->
                        g.getCurrentPos().distSqr(playerPos)))
                .ifPresentOrElse(
                        g -> {
                            double dist = Math.sqrt(
                                    g.getCurrentPos().distSqr(playerPos));
                            src.sendSuccess(() -> Component.literal(
                                            "Nearest group: "
                                                    + g.getGroupId().toString().substring(0, 8)
                                                    + "\nDistance: " + (int) dist + " blocks"
                                                    + "\nPosition: " + g.getCurrentPos().toShortString()
                                                    + "\nState: " + g.getState()
                                                    + "\nMembers: " + g.getMemberCount()
                                                    + "\nSpawned: " + g.isSpawned()
                                                    + (g.getActiveQuest() != null
                                                    ? "\nQuest: " + g.getActiveQuest().getTitle()
                                                    + " (" + (int)(g.getActiveQuest()
                                                    .getCompletionFraction() * 100) + "%)"
                                                    : "\nQuest: none")),
                                    false);
                        },
                        () -> src.sendSuccess(() -> Component.literal(
                                "No adventurer groups exist."), false)
                );
        return 1;
    }

    private static int clearAllGroups(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        AdventurerSavedData data = AdventurerSavedData.get(level);

        int count = data.getAllGroups().size();

        // Despawn any live entities first
        data.getAllGroups().forEach(g ->
                g.getSpawnedEntityIds().stream()
                        .map(level::getEntity)
                        .filter(Objects::nonNull)
                        .forEach(net.minecraft.world.entity.Entity::discard));

        data.getAllGroups().forEach(g ->
                data.removeGroup(g.getGroupId()));

        src.sendSuccess(() -> Component.literal(
                "Cleared " + count + " adventurer groups."), false);
        return count;
    }

    // -------------------------------------------------------------------------
// /adventurer join
// -------------------------------------------------------------------------

    private static int joinNearestGroup(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = src.getLevel();

        AdventurerSavedData data = AdventurerSavedData.get(level);
        VillageSavedData villageData = VillageSavedData.get(level);

        // Find nearest spawned group that allows joining
        AdventurerGroup nearest = data.getAllGroups().stream()
                .filter(AdventurerGroup::isSpawned)
                .filter(g -> g.getCurrentPos()
                        .closerThan(player.blockPosition(), 32))
                .filter(g -> {
                    AdventurerReputationTier tier =
                            AdventurerReputationManager.getTier(
                                    player, g, level);
                    return tier.canJoinGroup();
                })
                .min(Comparator.comparingDouble(g ->
                        g.getCurrentPos().distSqr(player.blockPosition())))
                .orElse(null);

        if (nearest == null) {
            src.sendFailure(Component.literal(
                    "No eligible group nearby. You need "
                            + AdventurerReputationTier.HONORED.getDisplayName()
                            + " or higher reputation with a group to join them."));
            return 0;
        }

        // Check not already joined
        if (nearest.getJoinedPlayerId()
                .map(id -> id.equals(player.getUUID()))
                .orElse(false)) {
            src.sendFailure(Component.literal(
                    "You are already part of this group."));
            return 0;
        }

        // Join the group
        nearest.setJoinedPlayerId(player.getUUID());
        nearest.setAlliedPlayerId(player.getUUID());
        data.addPlayerToGroup(player.getUUID(), nearest.getGroupId());
        data.setDirty();

        // Notify the player
        String leaderName = nearest.getSpawnedEntityIds().stream()
                .map(level::getEntity)
                .filter(e -> e instanceof TownspersonMob)
                .map(e -> (TownspersonMob) e)
                .filter(TownspersonMob::isGroupLeader)
                .findFirst()
                .map(TownspersonMob::getNpcName)
                .orElse("the group leader");

        src.sendSuccess(() -> Component.literal(
                        "You have joined the group led by " + leaderName + "!\n"
                                + "Members: " + (nearest.getMemberCount() + 1) + "\n"
                                + "Current objective: "
                                + (nearest.getActiveQuest() != null
                                ? nearest.getActiveQuest().getTitle()
                                : "None")
                                + "\nUse /adventurer lead to take command, "
                                + "or /adventurer leave to part ways."),
                false);

        // Leader acknowledges the player joining
        nearest.getSpawnedEntityIds().stream()
                .map(level::getEntity)
                .filter(e -> e instanceof TownspersonMob)
                .map(e -> (TownspersonMob) e)
                .filter(TownspersonMob::isGroupLeader)
                .findFirst()
                .ifPresent(leader -> player.displayClientMessage(
                        Component.literal("[" + leader.getNpcName() + "] "
                                + "Welcome to the group, "
                                + player.getName().getString()
                                + "! Stay close and watch each other's backs."),
                        false));

        return 1;
    }

// -------------------------------------------------------------------------
// /adventurer leave
// -------------------------------------------------------------------------

    private static int leaveAllGroups(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = src.getLevel();

        AdventurerSavedData data = AdventurerSavedData.get(level);
        List<AdventurerGroup> joined = data.getGroupsForPlayer(
                player.getUUID());

        if (joined.isEmpty()) {
            src.sendFailure(Component.literal(
                    "You are not part of any adventurer group."));
            return 0;
        }

        int count = joined.size();
        joined.forEach(group -> {
            // If player was leading, transfer back to original leader
            if (group.isPlayerLeading(player.getUUID())) {
                transferLeadershipBack(group, player, level);
            }

            group.setJoinedPlayerId(null);
            group.setAlliedPlayerId(null);
            group.setPlayerLeading(false);
            data.removePlayerFromGroup(player.getUUID(), group.getGroupId());

            // Farewell message from leader
            group.getSpawnedEntityIds().stream()
                    .map(level::getEntity)
                    .filter(e -> e instanceof TownspersonMob)
                    .map(e -> (TownspersonMob) e)
                    .filter(TownspersonMob::isGroupLeader)
                    .findFirst()
                    .ifPresent(leader -> player.displayClientMessage(
                            Component.literal("[" + leader.getNpcName()
                                    + "] Safe travels, "
                                    + player.getName().getString()
                                    + ". It was an honor."),
                            false));
        });

        data.setDirty();

        int finalCount = count;
        src.sendSuccess(() -> Component.literal(
                        "You have parted ways with "
                                + finalCount + " group"
                                + (finalCount > 1 ? "s" : "") + "."),
                false);

        return count;
    }

// -------------------------------------------------------------------------
// /adventurer lead
// -------------------------------------------------------------------------

    private static int leadNearestGroup(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = src.getLevel();

        AdventurerSavedData data = AdventurerSavedData.get(level);

        // Find nearest joined group where player can lead
        AdventurerGroup group = data.getGroupsForPlayer(player.getUUID())
                .stream()
                .filter(AdventurerGroup::isSpawned)
                .filter(g -> {
                    AdventurerReputationTier tier =
                            AdventurerReputationManager.getTier(
                                    player, g, level);
                    return tier.playerCanLead();
                })
                .min(Comparator.comparingDouble(g ->
                        g.getCurrentPos().distSqr(player.blockPosition())))
                .orElse(null);

        if (group == null) {
            // Check if they're joined but not LEGEND tier
            boolean isJoined = !data.getGroupsForPlayer(
                    player.getUUID()).isEmpty();
            if (isJoined) {
                src.sendFailure(Component.literal(
                        "You need "
                                + AdventurerReputationTier.LEGEND.getDisplayName()
                                + " reputation to lead a group."));
            } else {
                src.sendFailure(Component.literal(
                        "You are not part of any nearby group. "
                                + "Use /adventurer join first."));
            }
            return 0;
        }

        if (group.isPlayerLeading(player.getUUID())) {
            src.sendFailure(Component.literal(
                    "You are already leading this group."));
            return 0;
        }

        // Transfer leadership to player

        group.setPlayerLeading(true);
        AdventurerSavedData.get(level).updateLeaderVisuals(group, level);

        // NPCs acknowledge the new leader
        group.setAlliedPlayerId(player.getUUID());
        group.getSpawnedEntityIds().stream()
                .map(level::getEntity)
                .filter(e -> e instanceof TownspersonMob)
                .map(e -> (TownspersonMob) e)
                .forEach(npc -> npc.setCurrentActivity(
                        "Following " + player.getName().getString()));

        // Leader hands over command
        group.getSpawnedEntityIds().stream()
                .map(level::getEntity)
                .filter(e -> e instanceof TownspersonMob)
                .map(e -> (TownspersonMob) e)
                .filter(TownspersonMob::isGroupLeader)
                .findFirst()
                .ifPresent(leader -> player.displayClientMessage(
                        Component.literal("[" + leader.getNpcName()
                                + "] Command is yours, "
                                + player.getName().getString()
                                + ". We follow your lead."),
                        false));

        data.setDirty();

        src.sendSuccess(() -> Component.literal(
                        "You are now leading the group.\n"
                                + "The adventurers will follow your movement.\n"
                                + "Use /adventurer follow to return command to the group leader."),
                false);

        return 1;
    }

// -------------------------------------------------------------------------
// /adventurer follow
// -------------------------------------------------------------------------

    private static int followNearestGroup(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = src.getLevel();

        AdventurerSavedData data = AdventurerSavedData.get(level);

        AdventurerGroup group = data.getGroupsForPlayer(player.getUUID())
                .stream()
                .filter(g -> g.isPlayerLeading(player.getUUID()))
                .findFirst()
                .orElse(null);

        if (group == null) {
            src.sendFailure(Component.literal(
                    "You are not currently leading any group."));
            return 0;
        }

        transferLeadershipBack(group, player, level);
        data.setDirty();

        src.sendSuccess(() -> Component.literal(
                        "You have returned command to the group leader."),
                false);

        return 1;
    }

// -------------------------------------------------------------------------
// /adventurer status
// -------------------------------------------------------------------------

    private static int joinedGroupStatus(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = src.getLevel();

        AdventurerSavedData data = AdventurerSavedData.get(level);
        List<AdventurerGroup> joined = data.getGroupsForPlayer(
                player.getUUID());

        if (joined.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                            "You are not part of any adventurer group.\n"
                                    + "Find a group with high enough reputation "
                                    + "and use /adventurer join."),
                    false);
            return 0;
        }

        src.sendSuccess(() -> Component.literal(
                        "=== Your Adventurer Groups ==="),
                false);

        joined.forEach(g -> {
            AdventurerReputationTier tier =
                    AdventurerReputationManager.getTier(player, g, level);

            src.sendSuccess(() -> Component.literal(
                            "Group: " + g.getGroupId().toString().substring(0, 8)
                                    + "\nMembers: " + g.getMemberCount()
                                    + "\nState: " + g.getState()
                                    + "\nQuest: " + (g.getActiveQuest() != null
                                    ? g.getActiveQuest().getTitle()
                                    : "None")
                                    + "\nYour reputation: " + tier.getDisplayName()
                                    + "\nLeading: " + g.isPlayerLeading(player.getUUID())
                                    + "\nPosition: " + g.getCurrentPos().toShortString()),
                    false);
        });

        return joined.size();
    }

// -------------------------------------------------------------------------
// Helper
// -------------------------------------------------------------------------

    private static void transferLeadershipBack(AdventurerGroup group,
                                               ServerPlayer player,
                                               ServerLevel level) {
        group.setPlayerLeading(false);
        AdventurerSavedData.get(level).updateLeaderVisuals(group, level);

        // Restore NPC patrol behavior
        group.getSpawnedEntityIds().stream()
                .map(level::getEntity)
                .filter(e -> e instanceof TownspersonMob)
                .map(e -> (TownspersonMob) e)
                .forEach(npc -> npc.setCurrentActivity(
                        group.getActiveQuest() != null
                                ? group.getActiveQuest().getTitle()
                                : "On patrol"));

        // Original leader reclaims command
        group.getSpawnedEntityIds().stream()
                .map(level::getEntity)
                .filter(e -> e instanceof TownspersonMob)
                .map(e -> (TownspersonMob) e)
                .filter(TownspersonMob::isGroupLeader)
                .findFirst()
                .ifPresent(leader -> player.displayClientMessage(
                        Component.literal("[" + leader.getNpcName()
                                + "] I'll take it from here. "
                                + "Good leading, "
                                + player.getName().getString() + "."),
                        false));
    }


    private static int setGuildRank(CommandSourceStack src,
                                    ServerPlayer target,
                                    String rankName) {
        ServerLevel level = src.getLevel();
        PlayerGuildData guildData = PlayerGuildData.get(level);

        // Parse rank
        GuildRank rank;
        try {
            rank = GuildRank.valueOf(rankName.toUpperCase());
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal(
                    "Unknown rank: " + rankName + ". Valid ranks: "
                            + Arrays.stream(GuildRank.values())
                            .map(GuildRank::name)
                            .collect(Collectors.joining(", "))));
            return 0;
        }

        // Check registered
        if (!guildData.isRegistered(target.getUUID())) {
            src.sendFailure(Component.literal(
                    target.getName().getString()
                            + " is not registered with any guild."));
            return 0;
        }

        // Set rank
        guildData.setRank(target.getUUID(), rank);
        guildData.setDirty();

        src.sendSuccess(() -> Component.literal(
                        "Set " + target.getName().getString()
                                + "'s guild rank to "
                                + rank.getDisplayName() + "."),
                false);

        target.displayClientMessage(
                Component.literal("Your guild rank has been set to "
                                + rank.getDisplayName() + ".")
                        .withStyle(net.minecraft.ChatFormatting.GOLD),
                false);

        return 1;
    }

    private static int setVillageRep(CommandSourceStack src,
                                     String villageName,
                                     int amount) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = src.getLevel();
        VillageSavedData data = VillageSavedData.get(level);

        var village = data.getVillageByName(villageName).orElse(null);
        if (village == null) {
            src.sendFailure(Component.literal(
                    "No village found with name: " + villageName));
            return 0;
        }

        // Calculate delta needed to reach target amount
        int current = village.getReputation(player.getUUID());
        int delta = amount - current;
        village.modifyReputation(player.getUUID(), delta);
        data.setDirty();

        PlayerGuildData guildData = PlayerGuildData.get(level);
        GuildRank rank = guildData.getMember(player.getUUID())
                .map(m -> m.currentRank())
                .orElse(GuildRank.BRONZE);

        AdventurerReputationTier tier = AdventurerReputationTier
                .resolve(amount, rank);

        src.sendSuccess(() -> Component.literal(
                        "Set your reputation in " + villageName
                                + " to " + amount + " (was " + current + ").\n"
                                + "Adventurer tier with current guild rank ("
                                + rank.getDisplayName() + "): "
                                + tier.getDisplayName()),
                false);

        return 1;
    }

    private static int listWarnings(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = src.getLevel();
        VillageSavedData data = VillageSavedData.get(
                level.getServer().overworld());

        List<Village> warned = VillageWarningSystem
                .getWarnedVillages(player.getUUID(), data);

        if (warned.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    "You have no active warnings."), false);
            return 0;
        }

        src.sendSuccess(() -> Component.literal(
                "=== Active Warnings ==="), false);

        warned.forEach(v -> {
            int rep = v.getReputation(player.getUUID());
            src.sendSuccess(() -> Component.literal(
                            v.getName()
                                    + " | Reputation: " + rep
                                    + " | Clears above: " + 0
                                    + (rep > 0 ? " \u2713 Ready to clear" : "")),
                    false);
        });

        src.sendSuccess(() -> Component.literal(
                        "\nWarnings clear automatically when your reputation "
                                + "recovers above 0 in each village."),
                false);

        return warned.size();
    }
    private static int spawnTestCaravan(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = src.getLevel();

        VillageSavedData villageData = VillageSavedData.get(level);
        CaravanSavedData caravanData = CaravanSavedData.get(level);

        // Find the nearest trade route to the player
        BlockPos playerPos = player.blockPosition();

        TradeRoute nearestRoute = villageData.getAllTradeRoutes()
                .stream()
                .filter(TradeRoute::isTradeAllowed)
                .filter(r -> villageData.getRoadById(
                        r.getRoadId()).isPresent())
                .min(Comparator.comparingDouble(r ->
                        villageData.getRoadById(r.getRoadId())
                                .map(road -> road.getBlocks()
                                        .isEmpty() ? Double.MAX_VALUE
                                        : road.getBlocks().get(0)
                                        .distSqr(playerPos))
                                .orElse(Double.MAX_VALUE)))
                .orElse(null);

        if (nearestRoute == null) {
            src.sendFailure(Component.literal(
                    "No active trade routes found. "
                            + "Spawn a village first."));
            return 0;
        }

        TradeRoad road = villageData.getRoadById(
                nearestRoute.getRoadId()).orElse(null);
        if (road == null || road.getBlocks().isEmpty()) {
            src.sendFailure(Component.literal(
                    "Trade route has no road data."));
            return 0;
        }

        // Build test goods
        List<ItemStack> goods = List.of(
                new ItemStack(net.minecraft.world.item.Items.WHEAT, 32),
                new ItemStack(net.minecraft.world.item.Items.OAK_LOG, 16),
                new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 64)
        );

        // Create caravan at start of road
        Caravan caravan = Caravan.create(
                nearestRoute.getRouteId(),
                nearestRoute.getVillageA(),
                nearestRoute.getVillageB(),
                goods,
                2,
                level.getGameTime());

        caravanData.addCaravan(caravan);

        // Force immediate tick so entities spawn
        caravanData.tick(level, villageData);

        String originName = villageData
                .getVillageById(nearestRoute.getVillageA())
                .map(v -> v.getName()).orElse("unknown");
        String destName = villageData
                .getVillageById(nearestRoute.getVillageB())
                .map(v -> v.getName()).orElse("unknown");

        src.sendSuccess(() -> Component.literal(
                        "Spawned test caravan!\n"
                                + "Route: " + originName + " → " + destName + "\n"
                                + "Road length: " + road.getBlocks().size()
                                + " blocks\n"
                                + "Road quality: " + road.getQuality() + "%\n"
                                + "Guards: 2\n"
                                + "Caravan ID: "
                                + caravan.getCaravanId().toString()
                                .substring(0, 8)
                                + "\nUse /adventurer list to track it."),
                false);

        return 1;
    }
    private static int setCaravanSpeed(CommandSourceStack src,
                                       int multiplier) {
        ServerLevel level = src.getLevel();
        CaravanSavedData data = CaravanSavedData.get(level);

        // Manually advance all caravans
        data.getAllCaravans().forEach(c -> {
            double newProgress = Math.min(1.0,
                    c.getProgress() + 0.0001 * multiplier);
            c.setProgress(newProgress);
        });

        data.setDirty();
        src.sendSuccess(() -> Component.literal(
                        "Advanced " + data.getAllCaravans().size()
                                + " caravan(s) by "
                                + multiplier + "x speed.\n"
                                + data.getAllCaravans().stream()
                                .map(c -> "  "
                                        + c.getCaravanId().toString()
                                        .substring(0, 8)
                                        + " → " + String.format("%.1f",
                                        c.getProgress() * 100)
                                        + "% (" + c.getState() + ")")
                                .collect(java.util.stream.Collectors
                                        .joining("\n"))),
                false);
        return 1;
    }
}