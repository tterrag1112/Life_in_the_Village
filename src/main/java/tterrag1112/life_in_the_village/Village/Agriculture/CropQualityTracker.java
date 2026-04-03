// New file: src/main/java/tterrag1112/life_in_the_village/Village/Agriculture/CropQualityTracker.java

package tterrag1112.life_in_the_village.Village.Agriculture;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks soil quality and crop quality for individual farmland blocks.
 *
 * Quality factors:
 * - Soil fertility (degrades with repeated planting, restored by fertilizer)
 * - Crop rotation bonus (planting different crops in succession)
 * - Fertilization (temporary quality boost from bone meal)
 *
 * Higher quality = better market prices and occasional bonus yields
 */
public class CropQualityTracker {

    // =========================================================================
    // Quality data per farmland block
    // =========================================================================

    public static class QualityData {
        public static final Codec<QualityData> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.FLOAT.fieldOf("soilFertility").forGetter(q -> q.soilFertility),
                        Codec.STRING.fieldOf("lastCropType").forGetter(q -> q.lastCropType),
                        Codec.INT.fieldOf("fertilizationLevel").forGetter(q -> q.fertilizationLevel),
                        Codec.LONG.fieldOf("lastFertilized").forGetter(q -> q.lastFertilized)
                ).apply(instance, QualityData::new)
        );

        private float soilFertility;      // 0.0 - 1.0
        private String lastCropType;       // Last crop planted here
        private int fertilizationLevel;    // 0-3, temporary boost
        private long lastFertilized;       // Game tick when fertilized

        public QualityData(float soilFertility, String lastCropType,
                           int fertilizationLevel, long lastFertilized) {
            this.soilFertility = soilFertility;
            this.lastCropType = lastCropType;
            this.fertilizationLevel = fertilizationLevel;
            this.lastFertilized = lastFertilized;
        }

        public QualityData() {
            this(0.8f, "none", 0, 0L);
        }

        public float getSoilFertility() { return soilFertility; }
        public String getLastCropType() { return lastCropType; }
        public int getFertilizationLevel() { return fertilizationLevel; }
        public long getLastFertilized() { return lastFertilized; }

        public void setSoilFertility(float fertility) {
            this.soilFertility = Math.max(0.0f, Math.min(1.0f, fertility));
        }

        public void setLastCropType(String cropType) {
            this.lastCropType = cropType;
        }

        public void addFertilization(long currentTick) {
            this.fertilizationLevel = Math.min(3, fertilizationLevel + 1);
            this.lastFertilized = currentTick;
        }

        public void degradeFertilization(long currentTick) {
            // Fertilization decays over 2 in-game days (48000 ticks)
            if (currentTick - lastFertilized > 48000L && fertilizationLevel > 0) {
                fertilizationLevel--;
                lastFertilized = currentTick;
            }
        }
    }

    // =========================================================================
    // Per-plot quality tracking
    // =========================================================================

    private final UUID farmPlotId;
    private final Map<BlockPos, QualityData> qualityMap;

    public CropQualityTracker(UUID farmPlotId) {
        this.farmPlotId = farmPlotId;
        this.qualityMap = new HashMap<>();
    }

    // =========================================================================
    // Quality calculation
    // =========================================================================

    /**
     * Calculate overall quality multiplier for a farmland block
     */
    public float getQualityMultiplier(BlockPos pos, String currentCropType, long currentTick) {
        QualityData data = qualityMap.computeIfAbsent(pos, p -> new QualityData());

        // Update fertilization decay
        data.degradeFertilization(currentTick);

        // Base: soil fertility (0.0 - 1.0)
        float quality = data.soilFertility;

        // Bonus: crop rotation (+20% if different crop than last time)
        if (!data.lastCropType.equals("none") &&
                !data.lastCropType.equals(currentCropType)) {
            quality += 0.2f;
        }

        // Bonus: fertilization (+10% per level, max +30%)
        quality += data.fertilizationLevel * 0.1f;

        // Cap at 2.0 (200%)
        return Math.min(2.0f, quality);
    }

    /**
     * Called when a crop is planted
     */
    public void onCropPlanted(BlockPos pos, String cropType, long currentTick) {
        QualityData data = qualityMap.computeIfAbsent(pos, p -> new QualityData());

        // Slight fertility degradation from planting same crop
        if (data.lastCropType.equals(cropType)) {
            data.setSoilFertility(data.soilFertility - 0.05f);
        }

        data.setLastCropType(cropType);
    }

    /**
     * Called when a crop is harvested
     */
    public void onCropHarvested(BlockPos pos) {
        QualityData data = qualityMap.computeIfAbsent(pos, p -> new QualityData());

        // Small fertility degradation from harvest
        data.setSoilFertility(data.soilFertility - 0.02f);
    }

    /**
     * Called when fertilizer (bone meal) is applied
     */
    public void onFertilizerApplied(BlockPos pos, long currentTick) {
        QualityData data = qualityMap.computeIfAbsent(pos, p -> new QualityData());

        // Restore some soil fertility
        data.setSoilFertility(data.soilFertility + 0.1f);

        // Add fertilization boost
        data.addFertilization(currentTick);
    }

    /**
     * Get quality rating as percentage string for display
     */
    public String getQualityRating(BlockPos pos, String currentCropType, long currentTick) {
        float multiplier = getQualityMultiplier(pos, currentCropType, currentTick);

        if (multiplier >= 1.8f) return "Exceptional";
        if (multiplier >= 1.5f) return "Excellent";
        if (multiplier >= 1.2f) return "Good";
        if (multiplier >= 0.9f) return "Average";
        if (multiplier >= 0.6f) return "Poor";
        return "Depleted";
    }

    // =========================================================================
    // Getters
    // =========================================================================

    public UUID getFarmPlotId() { return farmPlotId; }
    public Map<BlockPos, QualityData> getQualityMap() { return qualityMap; }
}