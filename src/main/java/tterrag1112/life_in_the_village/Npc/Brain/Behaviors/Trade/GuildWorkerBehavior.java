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

        // Find guild for this village
        GuildData guild = entity.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .flatMap(v -> data.getGuildForVillage(v.getId()))
                .orElse(null);
        if (guild == null) return;

        // Refresh the guild's offer pool if due
        if (level.getGameTime() - guild.lastQuestRefresh()
                >= QUEST_REFRESH_INTERVAL) {
            refreshQuests(level, guild, data);
        }

        // F2b-2 — escort objective completion source: fire ESCORT_ARRIVED for any player
        // standing in the destination village of one of their active escort quests.
        GuildQuests.notifyEscortArrivals(level);
    }

    private void refreshQuests(ServerLevel level, GuildData guild,
                               VillageSavedData data) {
        var village = data.getVillageByName(
                entity.getAssignedVillageName().orElse("")).orElse(null);
        if (village == null) return;

        GuildQuests.refreshOffers(level, guild, village, data);
        data.updateGuild(guild.withRefresh(level.getGameTime()));

        org.slf4j.LoggerFactory.getLogger(GuildWorkerBehavior.class).debug(
                "Guild refreshed its quest pool");
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

    private void openGuildScreen(){

    }



    /** Bridge helper — Goal-side used entity.getNavigation().moveTo(x,y,z,speed);
     *  Behavior-side writes WALK_TARGET memory and lets CORE MoveToTargetSink steer. */
    private static WalkTarget navWalkTarget(double x, double y, double z, double speed) {
        return new WalkTarget(net.minecraft.core.BlockPos.containing(x, y, z), (float) speed, 1);
    }

}