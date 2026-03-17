package tterrag1112.life_in_the_village.Entities.Goals.Profession.Adventurer;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers.AdventurerGroup;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers.AdventurerReputationManager;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers.AdventurerReputationTier;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers.AdventurerSavedData;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Quest;

import java.util.EnumSet;
import java.util.UUID;

public class AdventurerInteractGoal extends Goal {

    private static final double INTERACT_RANGE  = 4.0D;
    private static final int    COOLDOWN_TICKS  = 200; // 10 seconds

    private final TownspersonMob entity;
    private Player targetPlayer;
    private int cooldown = 0;

    public AdventurerInteractGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        if (entity.getGroupId().isEmpty()) return false;

        // Look for a nearby player
        targetPlayer = entity.level().getNearestPlayer(
                entity, INTERACT_RANGE);
        return targetPlayer != null;
    }

    @Override
    public boolean canContinueToUse() {
        return targetPlayer != null
                && targetPlayer.isAlive()
                && entity.distanceTo(targetPlayer) <= INTERACT_RANGE * 1.5;
    }

    @Override
    public void start() {
        if (targetPlayer == null) return;
        entity.getLookControl().setLookAt(targetPlayer,
                30f, entity.getMaxHeadXRot());
        sendGreeting();
    }

    @Override
    public void stop() {
        cooldown = COOLDOWN_TICKS;
        targetPlayer = null;
    }

    @Override
    public void tick() {
        if (targetPlayer == null) return;
        entity.getLookControl().setLookAt(targetPlayer,
                30f, entity.getMaxHeadXRot());
    }

    private void sendGreeting() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        if (!(targetPlayer instanceof ServerPlayer serverPlayer)) return;

        UUID groupId = entity.getGroupId().orElse(null);
        if (groupId == null) return;

        AdventurerSavedData data = AdventurerSavedData.get(level);
        AdventurerGroup group = data.getGroup(groupId).orElse(null);
        if (group == null) return;

        // Check tier — wary and unpopular groups ignore the player
        AdventurerReputationTier tier = AdventurerReputationManager
                .getEffectiveTier(serverPlayer, group, level);

        if (tier.willIgnorePlayer()) return;

        String message = AdventurerReputationManager
                .buildTipMessage(serverPlayer, group, level);

        serverPlayer.displayClientMessage(
                Component.literal("[" + entity.getNpcName() + "] "
                        + message), false);
    }

    private String buildMessage(AdventurerGroup group) {
        Quest quest = group.getActiveQuest();
        String questInfo = quest != null
                ? " (" + quest.getTitle() + ")"
                : "";

        return switch (group.getState()) {
            case TRAVELLING -> "We're heading out on a contract"
                    + questInfo + ". Safe travels!";
            case CAMPING    -> quest != null
                    ? "Taking a rest before we tackle: "
                    + quest.getTitle() + "."
                    : "We're making camp for the night.";
            case HUNTING    -> quest != null
                    ? "We're hunting " + quest.getTargetMob()
                    + ". " + quest.getCurrentCount()
                    + "/" + quest.getTargetCount()
                    + " down. Watch yourself out here!"
                    : "Clearing out monsters nearby. Stay alert!";
            case EXPLORING  -> quest != null
                    ? "Scouting the " + formatBiome(quest.getTargetBiome())
                    + " for the guild."
                    : "Exploring the area.";
            case ESCORTING  -> "We're escorting a traveller to safety. "
                    + "No time to chat!";
            case RETURNING  -> quest != null
                    && quest.getStatus() == Quest.QuestStatus.COMPLETED
                    ? "Contract complete — " + quest.getTitle()
                    + ". Heading back to the guild hall!"
                    : "Heading back to base.";
        };
    }

    private String formatBiome(String biome) {
        if (biome == null) return "unknown region";
        String name = biome.contains(":")
                ? biome.split(":")[1] : biome;
        return name.replace("_", " ");
    }
}