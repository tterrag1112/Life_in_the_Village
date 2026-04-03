// New file: src/main/java/tterrag1112/life_in_the_village/Tutorial/FarmingTutorial.java

package tterrag1112.life_in_the_village.Tutorial;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Contextual tutorial system for farming.
 *
 * Shows helpful tips at appropriate moments.
 */
public class FarmingTutorial {

    public enum TutorialStage {
        JOINED_FARM,
        FIRST_TASK,
        FIRST_HARVEST,
        FIRST_SALE,
        BUSINESS_LEVEL_UP,
        PURCHASE_FARM,
        HIRE_FARMHAND,
        MARKET_STALL,
        QUALITY_CROPS
    }

    private static final Map<UUID, TutorialStage> playerProgress = new HashMap<>();
    private static final Map<UUID, Long> lastTipTime = new HashMap<>();
    private static final long TIP_COOLDOWN = 6000L; // 5 minutes

    /**
     * Show tutorial tip if player hasn't seen this stage
     */
    public static void showTip(ServerPlayer player, TutorialStage stage) {
        UUID playerId = player.getUUID();

        // Check if already completed this stage
        if (hasCompletedStage(playerId, stage)) {
            return;
        }

        // Check cooldown
        long currentTime = player.level().getGameTime();
        if (currentTime - lastTipTime.getOrDefault(playerId, 0L) < TIP_COOLDOWN) {
            return;
        }

        // Show the tip
        String message = getTipMessage(stage);
        player.displayClientMessage(
                Component.literal("§e[Farming Tip] §f" + message),
                false
        );

        // Mark as completed
        playerProgress.put(playerId, stage);
        lastTipTime.put(playerId, currentTime);
    }

    private static String getTipMessage(TutorialStage stage) {
        return switch (stage) {
            case JOINED_FARM ->
                    "Welcome to the farm! Talk to the head farmer to receive work assignments.";

            case FIRST_TASK ->
                    "Complete tasks to earn wages and farming experience. Your pay increases with your level and the farm's business level.";

            case FIRST_HARVEST ->
                    "Good harvest! Crops yield more during certain seasons. Summer is the best time for farming.";

            case FIRST_SALE ->
                    "Well done! The farm's revenue contributes to its business level, which increases your wages.";

            case BUSINESS_LEVEL_UP ->
                    "The farm has grown! Higher business levels mean better pay and more opportunities.";

            case PURCHASE_FARM ->
                    "You can now own this farm! At Farmer level 3, you can purchase farms or inherit them from retiring farmers.";

            case HIRE_FARMHAND ->
                    "As a farm owner, you can hire NPC farmhands to help with work. More hands mean faster production!";

            case MARKET_STALL ->
                    "Rent a market stall to sell your goods directly. Set your own prices and build a reputation for quality!";

            case QUALITY_CROPS ->
                    "Crop rotation and fertilization improve crop quality. Higher quality crops sell for better prices!";
        };
    }

    private static boolean hasCompletedStage(UUID playerId, TutorialStage stage) {
        TutorialStage current = playerProgress.get(playerId);
        return current != null && current.ordinal() >= stage.ordinal();
    }

    /**
     * Reset tutorial progress for a player
     */
    public static void resetProgress(UUID playerId) {
        playerProgress.remove(playerId);
        lastTipTime.remove(playerId);
    }

    /**
     * Get player's current tutorial stage
     */
    public static TutorialStage getProgress(UUID playerId) {
        return playerProgress.getOrDefault(playerId, TutorialStage.JOINED_FARM);
    }
}