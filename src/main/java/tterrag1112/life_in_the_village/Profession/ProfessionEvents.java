package tterrag1112.life_in_the_village.Profession;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import tterrag1112.life_in_the_village.DataAttachments.ModData;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class ProfessionEvents {

    // -------------------------------------------------------------------------
    // Block break XP
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public static void onBlockBreak(
            BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player))
            return;

        BlockState state = event.getState();
        PlayerProfessionData data = player.getData(
                ModData.PROFESSION_DATA);

        for (PlayerProfession prof
                : PlayerProfession.values()) {
            if (prof.isRelevantBlock(state)) {
                int xp = prof.getXpReward(
                        PlayerProfession.XpSource.BREAK_BLOCK);
                if (xp > 0) {
                    awardXp(player, data, prof, xp);
                    double yieldBonus = data.getYieldBonus(prof);
                    if (yieldBonus > 0
                            && Math.random() < yieldBonus
                            && event.getLevel() instanceof
                            ServerLevel sl) {
                        ItemStack bonus =
                                new ItemStack(
                                        state.getBlock().asItem());
                        if (!bonus.isEmpty()) {
                            sl.addFreshEntity(
                                    new ItemEntity(
                                            sl,
                                            event.getPos().getX() + 0.5,
                                            event.getPos().getY() + 0.5,
                                            event.getPos().getZ() + 0.5,
                                            bonus));
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Harvest crop XP — fires when crop is broken at full growth
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public static void onCropHarvest(
            BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player))
            return;

        BlockState state = event.getState();
        if (!state.is(net.minecraft.tags.BlockTags.CROPS))
            return;

        // Check if fully grown
        var ageProperty = state.getProperties().stream()
                .filter(p -> p.getName().equals("age"))
                .findFirst();
        if (ageProperty.isEmpty()) return;

        // Only award if max age
        try {
            int age = (int) state.getValue(
                    (net.minecraft.world.level.block
                            .state.properties.IntegerProperty)
                            ageProperty.get());
            int maxAge = ((net.minecraft.world.level.block
                    .state.properties.IntegerProperty)
                    ageProperty.get()).getPossibleValues()
                    .stream()
                    .mapToInt(Integer::intValue)
                    .max().orElse(0);

            if (age < maxAge) return;
        } catch (Exception ignored) {
            return;
        }

        PlayerProfessionData data = player.getData(
                ModData.PROFESSION_DATA);
        int xp = PlayerProfession.FARMER.getXpReward(
                PlayerProfession.XpSource.HARVEST_CROP);
        awardXp(player, data, PlayerProfession.FARMER, xp);
    }

    // -------------------------------------------------------------------------
    // Crafting XP
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public static void onCraft(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;

        ItemStack result = event.getCrafting();
        if (result.isEmpty()) return;

        PlayerProfessionData data = player.getData(
                ModData.PROFESSION_DATA);

        for (PlayerProfession prof
                : PlayerProfession.values()) {
            if (prof.isRelevantCraft(result)) {
                int xp = prof.getXpReward(
                        PlayerProfession.XpSource
                                .CRAFT_RELEVANT)
                        * result.getCount();
                if (xp > 0) {
                    awardXp(player, data, prof, xp);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Sell to NPC XP — called from TownspersonMob.notifyTrade
    // -------------------------------------------------------------------------

    public static void onSellToNpc(ServerPlayer player,
                                   ItemStack soldItem,
                                   int amount) {
        PlayerProfessionData data = player.getData(
                ModData.PROFESSION_DATA);

        for (PlayerProfession prof
                : PlayerProfession.values()) {
            if (prof.isRelevantCraft(soldItem)) {
                int xp = prof.getXpReward(
                        PlayerProfession.XpSource.SELL_TO_NPC)
                        * amount;
                awardXp(player, data, prof, xp);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Job posting completion XP
    // -------------------------------------------------------------------------

    public static void onJobPostingCompleted(
            ServerPlayer player,
            PlayerProfession profession,
            int bonus) {
        PlayerProfessionData data = player.getData(
                ModData.PROFESSION_DATA);
        int xp = profession.getXpReward(
                PlayerProfession.XpSource.JOB_POSTING)
                + bonus;
        awardXp(player, data, profession, xp);
    }

    // -------------------------------------------------------------------------
    // XP award helper
    // -------------------------------------------------------------------------

    private static void awardXp(ServerPlayer player,
                                PlayerProfessionData data,
                                PlayerProfession profession,
                                int amount) {
        int oldLevel = data.getLevel(profession);
        data.addXp(profession, amount);
        int newLevel = data.getLevel(profession);

        // Persist
        player.setData(ModData.PROFESSION_DATA, data);

        // Level up notification
        if (newLevel > oldLevel) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component
                            .literal("Your "
                                    + profession.getDisplayName()
                                    + " skill has reached "
                                    + data.getLevelName(
                                    profession)
                                    + "!")
                            .withStyle(
                                    net.minecraft.ChatFormatting
                                            .GOLD),
                    false);

            // Notify nearby NPCs to update their respect
            if (player.level() instanceof
                    net.minecraft.server.level.ServerLevel sl) {
                VillageSavedData data2 = VillageSavedData.get(sl);
                int reputationBonus = data.getNpcRespectBonus(profession);

                // Apply reputation bonus in all villages the player
                // is currently standing in or near
                data2.getAllVillages().stream()
                        .filter(v -> v.getBounds(data2)
                                .map(b -> b.inflate(32).contains(
                                        player.getX(),
                                        player.getY(),
                                        player.getZ()))
                                .orElse(false))
                        .forEach(v -> {
                            v.modifyReputation(
                                    player.getUUID(),
                                    reputationBonus);
                            data2.setDirty();
                        });
            }
        }
    }
}