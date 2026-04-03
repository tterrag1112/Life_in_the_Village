// New file: src/main/java/tterrag1112/life_in_the_village/Client/FarmingVisualEffects.java

package tterrag1112.life_in_the_village.Client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Visual and audio feedback for farming activities.
 */
public class FarmingVisualEffects {

    // =========================================================================
    // Particle effects
    // =========================================================================

    /**
     * Show planting particles
     */
    public static void showPlantingEffect(Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            // Green sparkles
            for (int i = 0; i < 5; i++) {
                double offsetX = level.random.nextGaussian() * 0.2;
                double offsetZ = level.random.nextGaussian() * 0.2;
                serverLevel.sendParticles(
                        ParticleTypes.HAPPY_VILLAGER,
                        pos.getX() + 0.5 + offsetX,
                        pos.getY() + 0.5,
                        pos.getZ() + 0.5 + offsetZ,
                        1, 0, 0.1, 0, 0.05
                );
            }
        }
    }

    /**
     * Show harvesting particles
     */
    public static void showHarvestEffect(Level level, BlockPos pos, BlockState cropState) {
        if (level instanceof ServerLevel serverLevel) {
            // Crop-specific particles
            for (int i = 0; i < 8; i++) {
                double offsetX = level.random.nextGaussian() * 0.3;
                double offsetY = level.random.nextDouble() * 0.5;
                double offsetZ = level.random.nextGaussian() * 0.3;
                serverLevel.sendParticles(
                        ParticleTypes.ITEM_SNOWBALL, // Crop particles would be better
                        pos.getX() + 0.5 + offsetX,
                        pos.getY() + 0.5 + offsetY,
                        pos.getZ() + 0.5 + offsetZ,
                        1, 0, 0.1, 0, 0.05
                );
            }
        }
    }

    /**
     * Show fertilization particles
     */
    public static void showFertilizationEffect(Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            // Bone meal particles (already handled by vanilla)
            serverLevel.levelEvent(2005, pos, 0);
        }
    }

    /**
     * Show quality indicator particles
     */
    public static void showQualityIndicator(Level level, BlockPos pos, float quality) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        // Different particles based on quality
        var particleType = quality >= 1.5f ? ParticleTypes.ENCHANT :
                quality >= 1.0f ? ParticleTypes.HAPPY_VILLAGER :
                        ParticleTypes.SMOKE;

        for (int i = 0; i < 3; i++) {
            serverLevel.sendParticles(
                    particleType,
                    pos.getX() + 0.5,
                    pos.getY() + 1.0,
                    pos.getZ() + 0.5,
                    1, 0.2, 0.1, 0.2, 0.02
            );
        }
    }

    // =========================================================================
    // Sound effects
    // =========================================================================

    /**
     * Play planting sound
     */
    public static void playPlantingSound(Level level, BlockPos pos) {
        level.playSound(null, pos,
                SoundEvents.CROP_PLANTED,
                SoundSource.BLOCKS, 1.0f, 1.0f);
    }

    /**
     * Play harvesting sound
     */
    public static void playHarvestSound(Level level, BlockPos pos) {
        level.playSound(null, pos,
                SoundEvents.CROP_BREAK,
                SoundSource.BLOCKS, 1.0f, 1.0f);
    }

    /**
     * Play fertilization sound
     */
    public static void playFertilizationSound(Level level, BlockPos pos) {
        level.playSound(null, pos,
                SoundEvents.BONE_MEAL_USE,
                SoundSource.BLOCKS, 1.0f, 1.0f);
    }

    /**
     * Play animal feeding sound
     */
    public static void playFeedingSound(Level level, BlockPos pos) {
        level.playSound(null, pos,
                SoundEvents.GENERIC_EAT.value(),
                SoundSource.NEUTRAL, 1.0f, 1.0f);
    }

    /**
     * Play sale success sound
     */
    public static void playSaleSound(Level level, BlockPos pos) {
        level.playSound(null, pos,
                SoundEvents.EXPERIENCE_ORB_PICKUP,
                SoundSource.PLAYERS, 1.0f, 1.2f);
    }

    // =========================================================================
    // Visual indicators (for inspecting crops/animals)
    // =========================================================================

    /**
     * Show crop growth stage when player looks at crop
     */
    public static Component getCropGrowthMessage(BlockState state, float quality) {
        String qualityColor = quality >= 1.5f ? "§a" :  // Green
                quality >= 1.0f ? "§e" :  // Yellow
                        quality >= 0.6f ? "§6" :  // Orange
                                "§c";                     // Red

        return Component.literal(
                qualityColor + "Quality: " + String.format("%.0f%%", quality * 100)
        );
    }

    /**
     * Show animal health when player looks at animal
     */
    public static Component getAnimalHealthMessage(float health, float maxHealth) {
        float percentage = (health / maxHealth) * 100;
        String healthColor = percentage >= 80 ? "§a" :
                percentage >= 50 ? "§e" :
                        percentage >= 20 ? "§6" :
                                "§c";

        return Component.literal(
                healthColor + "Health: " + String.format("%.0f/%.0f", health, maxHealth)
        );
    }
}