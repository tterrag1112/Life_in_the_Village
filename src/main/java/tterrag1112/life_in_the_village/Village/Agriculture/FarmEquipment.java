// New file: src/main/java/tterrag1112/life_in_the_village/Village/Agriculture/FarmEquipment.java

package tterrag1112.life_in_the_village.Village.Agriculture;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks equipment upgrades for farms.
 *
 * Equipment provides bonuses:
 * - Better tools = faster harvesting
 * - Enchanted tools = bonus drops
 * - Sprinkler systems = auto-water crops
 * - Scarecrows = prevent crop trampling
 */
public class FarmEquipment {

    public enum EquipmentType {
        BASIC_HOE(0, 1.0f, 0.0f, 0L),
        IRON_HOE(1, 1.1f, 0.0f, 50L),
        DIAMOND_HOE(2, 1.2f, 0.05f, 200L),
        ENCHANTED_HOE(3, 1.3f, 0.15f, 500L),
        SPRINKLER_SYSTEM(4, 1.0f, 0.0f, 300L),
        SCARECROW(5, 1.0f, 0.0f, 100L);

        private final int tier;
        private final float speedMultiplier;
        private final float bonusDropChance;
        private final long purchaseCost;

        EquipmentType(int tier, float speedMultiplier, float bonusDropChance, long purchaseCost) {
            this.tier = tier;
            this.speedMultiplier = speedMultiplier;
            this.bonusDropChance = bonusDropChance;
            this.purchaseCost = purchaseCost;
        }

        public int getTier() { return tier; }
        public float getSpeedMultiplier() { return speedMultiplier; }
        public float getBonusDropChance() { return bonusDropChance; }
        public long getPurchaseCost() { return purchaseCost; }

        public String getDescription() {
            return switch (this) {
                case BASIC_HOE -> "Standard wooden/stone hoe";
                case IRON_HOE -> "+10% harvest speed";
                case DIAMOND_HOE -> "+20% harvest speed, +5% bonus drops";
                case ENCHANTED_HOE -> "+30% harvest speed, +15% bonus drops";
                case SPRINKLER_SYSTEM -> "Automatically waters all crops in plot";
                case SCARECROW -> "Prevents crop trampling damage";
            };
        }
    }

    // Per-farmhouse equipment tracking
    private final UUID farmhouseId;
    private final Map<EquipmentType, Integer> equipment; // type -> quantity

    public FarmEquipment(UUID farmhouseId) {
        this.farmhouseId = farmhouseId;
        this.equipment = new HashMap<>();
        equipment.put(EquipmentType.BASIC_HOE, 1); // Start with basic
    }

    /**
     * Purchase an equipment upgrade
     */
    public boolean purchaseEquipment(EquipmentType type) {
        // Can only have one of each type (except tools)
        if (equipment.containsKey(type) && !isToolType(type)) {
            return false;
        }

        equipment.put(type, equipment.getOrDefault(type, 0) + 1);
        return true;
    }

    /**
     * Get the best tool equipped
     */
    public EquipmentType getBestTool() {
        EquipmentType best = EquipmentType.BASIC_HOE;
        for (EquipmentType type : equipment.keySet()) {
            if (isToolType(type) && type.getTier() > best.getTier()) {
                best = type;
            }
        }
        return best;
    }

    /**
     * Check if farm has specific equipment
     */
    public boolean hasEquipment(EquipmentType type) {
        return equipment.containsKey(type) && equipment.get(type) > 0;
    }

    /**
     * Get total harvest speed multiplier from all equipment
     */
    public float getHarvestSpeedMultiplier() {
        return getBestTool().getSpeedMultiplier();
    }

    /**
     * Get total bonus drop chance from all equipment
     */
    public float getBonusDropChance() {
        return getBestTool().getBonusDropChance();
    }

    /**
     * Check if crops are auto-watered
     */
    public boolean hasAutoWater() {
        return hasEquipment(EquipmentType.SPRINKLER_SYSTEM);
    }

    /**
     * Check if crops are protected from trampling
     */
    public boolean hasCropProtection() {
        return hasEquipment(EquipmentType.SCARECROW);
    }

    private boolean isToolType(EquipmentType type) {
        return type == EquipmentType.BASIC_HOE ||
                type == EquipmentType.IRON_HOE ||
                type == EquipmentType.DIAMOND_HOE ||
                type == EquipmentType.ENCHANTED_HOE;
    }

    public UUID getFarmhouseId() { return farmhouseId; }
    public Map<EquipmentType, Integer> getEquipment() { return equipment; }
}