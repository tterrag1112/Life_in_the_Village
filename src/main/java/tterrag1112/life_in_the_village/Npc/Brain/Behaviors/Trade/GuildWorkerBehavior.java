package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Trade;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import com.google.common.collect.ImmutableMap;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.biome.Biome;

import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Gui.GuildScreen;
import tterrag1112.life_in_the_village.Guilds.Adventurer.*;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;

import java.util.*;

public class GuildWorkerBehavior extends Behavior<TownspersonMob> {

    private static final int CHECK_INTERVAL = 1200;
    private static final long QUEST_REFRESH_INTERVAL = 24000L * 2;

    private TownspersonMob entity;

    public GuildWorkerBehavior() {
        super(com.google.common.collect.ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), 24000);
    }
    private int timer = 0;

    

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) { this.entity = entity;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        return true; }
    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) { this.entity = entity;
        return true; }
        @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        timer++;
        if (timer < CHECK_INTERVAL) return;
        timer = 0;

        VillageSavedData data = VillageSavedData.get(level);
        PlayerGuildData guildData = PlayerGuildData.get(level);

        // Find guild for this village
        GuildData guild = entity.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .flatMap(v -> data.getGuildForVillage(v.getId()))
                .orElse(null);
        if (guild == null) return;

        // Refresh quests if needed
        if (level.getGameTime() - guild.lastQuestRefresh()
                >= QUEST_REFRESH_INTERVAL) {
            refreshQuests(level, guild, data, guildData);
        }

        // Check active quests for completion/failure
        checkQuestProgress(level, guild, guildData);
    }

    private void refreshQuests(ServerLevel level, GuildData guild,
                               VillageSavedData data,
                               PlayerGuildData guildData) {
        // Remove expired quests
        guildData.removeExpiredQuests(level.getGameTime());

        // Generate new quests
        var village = data.getVillageByName(
                entity.getAssignedVillageName().orElse("")).orElse(null);
        if (village == null) return;

        List<Quest> newQuests = QuestGenerator.generateQuestsForGuild(
                level, guild.guildId(), village, data, level.getGameTime());

        newQuests.forEach(guildData::addQuest);
        data.updateGuild(guild.withRefresh(level.getGameTime()));

        org.slf4j.LoggerFactory.getLogger(GuildWorkerBehavior.class).debug(
                "Guild refreshed " + newQuests.size() + " quests");
    }

    private void checkQuestProgress(ServerLevel level,
                                    GuildData guild,
                                    PlayerGuildData guildData) {
        // Check exploration quests — did player visit the biome?
        guildData.getAllQuestsForGuild(guild.guildId()).stream()
                .filter(Quest::isActive)
                .filter(q -> q.getType() == Quest.QuestType.EXPLORE)
                .forEach(quest -> {
                    UUID playerId = quest.getAssignedPlayerId();
                    if (playerId == null) return;

                    ServerPlayer player = level.getServer()
                            .getPlayerList().getPlayer(playerId);
                    if (player == null) return;

                    // Check if player is in target biome
                    Holder<Biome> biomeHolder = level.getBiome(player.blockPosition());
                    String biomeName = biomeHolder.unwrapKey()
                            .map(k -> k.registry().toString())
                            .orElse("");

                    if (biomeName.equals(quest.getTargetBiome())) {
                        quest.setCurrentCount(1);
                        quest.setTargetCount(1);
                    }
                });
    }

    // Called from mobInteract
    public void handlePlayerInteraction(ServerPlayer player,
                                        ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        PlayerGuildData guildData = PlayerGuildData.get(level);

        GuildData guild = entity.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .flatMap(v -> data.getGuildForVillage(v.getId()))
                .orElse(null);

        if (guild == null) {
            player.displayClientMessage(
                    Component.literal("This guild hall has no active guild."),
                    false);
            return;
        }

        // Register if not already
        if (!guildData.isRegistered(player.getUUID())) {
            guildData.registerPlayer(player.getUUID(),
                    player.getName().getString(), guild.guildId());
            guildData.setDirty();
            player.displayClientMessage(
                    Component.literal("Welcome to the Adventurers Guild! "
                            + "You are now registered as a "
                            + GuildRank.BRONZE.getDisplayName()
                            + " adventurer.").withStyle(
                            net.minecraft.ChatFormatting.GOLD),
                    false);
        }


    }

    private void giveQuestBook(ServerPlayer player, GuildData guild,
                               PlayerGuildData guildData,
                               ServerLevel level) {
        GuildMember member = guildData.getMember(player.getUUID()).orElse(null);
        if (member == null) return;

        GuildRank rank = member.currentRank();
        List<Quest> available = guildData.getAvailableQuestsForGuild(guild.guildId(), rank);
        List<Quest> active    = guildData.getActiveQuestsForPlayer(player.getUUID());

        // Build pages
        List<Component> pages = new ArrayList<>();

        // Cover page
        pages.add(Component.literal(
                "=== Adventurers Guild ===\n\n"
                        + "Rank: " + rank.getDisplayName() + "\n"
                        + "XP: " + member.xp() + "\n"
                        + "To next rank: " + member.xpToNextRank() + " XP\n"
                        + "Quests completed: " + member.completedQuestIds().size()));

        // Active quests page
        if (!active.isEmpty()) {
            StringBuilder sb = new StringBuilder("=== Active Quests ===\n\n");
            for (Quest q : active) {
                sb.append(q.getTitle()).append("\n");
                sb.append("Progress: ")
                        .append((int)(q.getCompletionFraction() * 100))
                        .append("%\n");
                sb.append("Reward: ")
                        .append(CurrencyValue.of(q.getCoinReward()))
                        .append("\n\n");
            }
            pages.add(Component.literal(sb.toString()));
        }

        // Available quests pages
        if (available.isEmpty()) {
            pages.add(Component.literal(
                    "=== Available Quests ===\n\nNo quests available "
                            + "at your rank right now.\nCheck back later."));
        } else {
            for (int i = 0; i < available.size(); i += 3) {
                StringBuilder sb = new StringBuilder("=== Available Quests ===\n\n");
                for (int j = i; j < Math.min(i + 3, available.size()); j++) {
                    Quest q = available.get(j);
                    sb.append("[").append(q.getDifficulty().name()).append("] ");
                    sb.append(q.getTitle()).append("\n");
                    sb.append(q.getDescription()).append("\n");
                    sb.append("Reward: ")
                            .append(CurrencyValue.of(q.getCoinReward()))
                            .append(" + ").append(q.getXpReward()).append(" XP\n");
                    sb.append("ID: ")
                            .append(q.getId().toString().substring(0, 8))
                            .append("\n\n");
                }
                pages.add(Component.literal(sb.toString()));
            }
        }

        // Instructions page
        pages.add(Component.literal(
                "=== How to Accept Quests ===\n\n"
                        + "Use /guild accept <quest-id> to accept a quest.\n\n"
                        + "Use /guild complete to turn in completed quests.\n\n"
                        + "Use /guild status to check your rank and active quests."));

        // Convert pages to the format DataComponents expects
        List<Filterable<Component>> filterablePages = pages.stream()
                .map(Filterable::passThrough)
                .toList();

        // Build the book ItemStack using DataComponents
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough(entity.getNpcName()),  // title
                entity.getNpcName(),                           // author
                0,                                             // generation
                filterablePages,
                true                                           // resolved
        ));

        // Replace existing guild book or give new one
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.WRITTEN_BOOK)) {
                WrittenBookContent existing = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
                if (existing != null
                        && existing.title().raw().equals("Guild Quest Board")) {
                    player.getInventory().setItem(i, book);
                    player.displayClientMessage(
                            Component.literal("Your quest book has been updated."), true);
                    return;
                }
            }
        }

        player.addItem(book);
        player.displayClientMessage(
                Component.literal("You received a quest book!"), true);
    }

    private void openGuildScreen(){

    }



    /** Bridge helper — Goal-side used entity.getNavigation().moveTo(x,y,z,speed);
     *  Behavior-side writes WALK_TARGET memory and lets CORE MoveToTargetSink steer. */
    private static WalkTarget navWalkTarget(double x, double y, double z, double speed) {
        return new WalkTarget(net.minecraft.core.BlockPos.containing(x, y, z), (float) speed, 1);
    }

}