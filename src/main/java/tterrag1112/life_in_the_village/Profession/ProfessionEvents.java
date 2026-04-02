// src/main/java/tterrag1112/life_in_the_village/Profession/ProfessionEvents.java
// This is a COMPLETE replacement of ProfessionEvents.java
package tterrag1112.life_in_the_village.Profession;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import tterrag1112.life_in_the_village.DataAttachments.ModData;
import tterrag1112.life_in_the_village.Village.Reputation.ReputationManager;
import tterrag1112.life_in_the_village.World.SeasonTracker;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.UUID;

@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class ProfessionEvents {

    // -------------------------------------------------------------------------
    // Block break XP + yield bonus + perk: bulk harvest
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;

        BlockState state = event.getState();
        PlayerProfessionData data = player.getData(ModData.PROFESSION_DATA);

        for (PlayerProfession prof : PlayerProfession.values()) {
            if (prof.isRelevantBlock(state)) {
                // Base XP — scaled by current season for outdoor professions
                int baseXp = prof.getXpReward(PlayerProfession.XpSource.BREAK_BLOCK);
                if (baseXp > 0) {
                    float seasonMult = 1.0f;
                    if (player.level() instanceof ServerLevel sl
                            && (prof == PlayerProfession.FARMER
                            || prof == PlayerProfession.CARPENTER)) {
                        seasonMult = SeasonTracker.currentSeason(sl)
                                .getProfessionXpMultiplier();
                    }
                    awardXp(player, data, prof, Math.round(baseXp * seasonMult));

                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Crop harvest XP — fires when crop is broken at full growth
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public static void onCropHarvest(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;

        BlockState state = event.getState();
        if (!state.is(net.minecraft.tags.BlockTags.CROPS)) return;

        // Check fully grown
        var ageProperty = state.getProperties().stream()
                .filter(p -> p.getName().equals("age"))
                .findFirst();
        if (ageProperty.isEmpty()) return;

        try {
            int age = (int) state.getValue(
                    (net.minecraft.world.level.block.state.properties.IntegerProperty)
                            ageProperty.get());
            int maxAge = ((net.minecraft.world.level.block.state.properties.IntegerProperty)
                    ageProperty.get()).getPossibleValues()
                    .stream().mapToInt(Integer::intValue).max().orElse(0);
            if (age < maxAge) return;
        } catch (Exception ignored) {
            return;
        }

        // Season scaling for farmer XP
        float seasonMult = 1.0f;
        if (player.level() instanceof ServerLevel sl) {
            seasonMult = SeasonTracker.currentSeason(sl)
                    .getProfessionXpMultiplier();
        }

        PlayerProfessionData data = player.getData(ModData.PROFESSION_DATA);
        int xp = Math.round(
                PlayerProfession.FARMER.getXpReward(PlayerProfession.XpSource.HARVEST_CROP)
                        * seasonMult);
        awardXp(player, data, PlayerProfession.FARMER, xp);

        // FARMER_BULK_HARVEST perk — sweep adjacent mature crops
        if (ProfessionPerkManager.shouldBulkHarvest(player)
                && player.level() instanceof ServerLevel sl) {
            net.minecraft.core.BlockPos origin = event.getPos();
            for (net.minecraft.core.BlockPos adj :
                    net.minecraft.core.BlockPos.betweenClosed(
                            origin.offset(-1, 0, -1),
                            origin.offset(1, 0, 1))) {
                if (adj.equals(origin)) continue;
                BlockState adjState = sl.getBlockState(adj);
                if (!adjState.is(net.minecraft.tags.BlockTags.CROPS)) continue;
                var adjAge = adjState.getProperties().stream()
                        .filter(p -> p.getName().equals("age"))
                        .findFirst();
                if (adjAge.isEmpty()) continue;
                try {
                    int a = (int) adjState.getValue(
                            (net.minecraft.world.level.block.state.properties.IntegerProperty)
                                    adjAge.get());
                    int m = ((net.minecraft.world.level.block.state.properties.IntegerProperty)
                            adjAge.get()).getPossibleValues()
                            .stream().mapToInt(Integer::intValue).max().orElse(0);
                    if (a < m) continue;
                    // Manually drop and clear
                    net.minecraft.world.level.block.Block.dropResources(
                            adjState, sl, adj.immutable(),
                            null, player, player.getMainHandItem());
                    sl.setBlock(adj.immutable(),
                            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                } catch (Exception ignored) {}
            }
        }
    }

    // -------------------------------------------------------------------------
    // Crafting XP
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public static void onCraft(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ItemStack result = event.getCrafting();
        if (result.isEmpty()) return;

        PlayerProfessionData data = player.getData(ModData.PROFESSION_DATA);

        for (PlayerProfession prof : PlayerProfession.values()) {
            if (prof.isRelevantCraft(result)) {
                int xp = prof.getXpReward(PlayerProfession.XpSource.CRAFT_RELEVANT)
                        * result.getCount();
                if (xp > 0) awardXp(player, data, prof, xp);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Sell to NPC XP + reputation — called from TownspersonMob.notifyTrade
    // -------------------------------------------------------------------------

    public static void onSellToNpc(ServerPlayer player,
                                   ItemStack soldItem,
                                   int amount,
                                   UUID villageId) {
        PlayerProfessionData data = player.getData(ModData.PROFESSION_DATA);

        for (PlayerProfession prof : PlayerProfession.values()) {
            if (prof.isRelevantCraft(soldItem)) {
                int xp = prof.getXpReward(PlayerProfession.XpSource.SELL_TO_NPC)
                        * amount;
                awardXp(player, data, prof, xp);
            }
        }

        // Reputation gain from trading
        if (villageId != null && player.level() instanceof ServerLevel level) {
            ReputationManager.onTradeCompleted(player, villageId, level);

            // MERCHANT_REPUTATION_TRADER perk doubles reputation gain
            if (ProfessionPerkManager.hasDoubleReputationTrade(player)) {
                ReputationManager.onTradeCompleted(player, villageId, level);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Job posting completion XP + reputation
    // -------------------------------------------------------------------------

    public static void onJobPostingCompleted(ServerPlayer player,
                                             PlayerProfession profession,
                                             int bonus) {
        onJobPostingCompleted(player, profession, bonus, null);
    }

    /**
     * Extended overload that also grants village reputation.
     * Use this from WorkplaceAssignmentManager when the player is assigned
     * to a known village.
     */
    public static void onJobPostingCompleted(ServerPlayer player,
                                             PlayerProfession profession,
                                             int bonus,
                                             UUID villageId) {
        PlayerProfessionData data = player.getData(ModData.PROFESSION_DATA);
        int baseXp = profession.getXpReward(PlayerProfession.XpSource.JOB_POSTING) + bonus;

        // Mentor proximity bonus — if mentor is within 16 blocks, +10% XP
        int finalXp = applyMentorBonus(player, data, profession, baseXp);
        awardXp(player, data, profession, finalXp);

        // Reputation gain
        if (villageId != null && player.level() instanceof ServerLevel level) {
            ReputationManager.onJobPostingCompleted(player, villageId, level);
        }
    }

    // -------------------------------------------------------------------------
    // Core XP award — now also triggers perk unlocks
    // -------------------------------------------------------------------------

    public static void awardXp(ServerPlayer player,
                               PlayerProfessionData data,
                               PlayerProfession profession,
                               int amount) {
        int oldLevel = data.getLevel(profession);
        data.addXp(profession, amount);
        int newLevel = data.getLevel(profession);

        // Persist
        player.setData(ModData.PROFESSION_DATA, data);

        // Level up notification + perk unlock
        if (newLevel > oldLevel) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component
                            .literal("Your "
                                    + profession.getDisplayName()
                                    + " skill has reached "
                                    + data.getLevelName(profession)
                                    + "!"),
                    false);

            // Check and grant any perk for the new level
            ProfessionPerkManager.checkUnlocks(player, profession, newLevel);
        }
    }

    // -------------------------------------------------------------------------
    // Mentor proximity bonus
    // -------------------------------------------------------------------------

    /**
     * Returns the XP amount after applying the +10% mentor proximity bonus,
     * if applicable.
     */
    private static int applyMentorBonus(ServerPlayer player,
                                        PlayerProfessionData data,
                                        PlayerProfession profession,
                                        int baseXp) {
        return data.getMentor(profession)
                .flatMap(npcId -> tterrag1112.life_in_the_village.Entities.custom
                        .TownspersonMob.findByUUID(
                                (ServerLevel) player.level(), npcId))
                .filter(npc -> npc.distanceTo(player) <= 16.0)
                .map(npc -> Math.round(baseXp * 1.1f))
                .orElse(baseXp);
    }
    @SubscribeEvent
    public static void onPlayerDamage(LivingDamageEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getSource().getEntity()
                instanceof net.minecraft.world.entity.LivingEntity attacker)) return;

        ProfessionPerkManager.onPlayerDamaged(player, attacker);
    }
}