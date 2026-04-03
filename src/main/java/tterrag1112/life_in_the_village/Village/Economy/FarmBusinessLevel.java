// New file: src/main/java/tterrag1112/life_in_the_village/Village/Economy/FarmBusinessLevel.java

package tterrag1112.life_in_the_village.Village.Economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks the business level and performance of individual farms.
 *
 * Business level affects:
 * - Player farmhand wages
 * - Quality of goods produced
 * - Reputation with village
 * - Unlock new plot types/expansions
 */
public class FarmBusinessLevel {

    // =========================================================================
    // Codec for persistence
    // =========================================================================

    public static final Codec<FarmBusinessLevel> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("farmhouseId").forGetter(f -> f.farmhouseId),
                    Codec.INT.fieldOf("businessLevel").forGetter(f -> f.businessLevel),
                    Codec.LONG.fieldOf("totalRevenue").forGetter(f -> f.totalRevenue),
                    Codec.LONG.fieldOf("weeklyRevenue").forGetter(f -> f.weeklyRevenue),
                    Codec.INT.fieldOf("plotCount").forGetter(f -> f.plotCount),
                    Codec.INT.fieldOf("farmhandCount").forGetter(f -> f.farmhandCount),
                    Codec.FLOAT.fieldOf("qualityRating").forGetter(f -> f.qualityRating),
                    Codec.LONG.fieldOf("lastUpdateTick").forGetter(f -> f.lastUpdateTick)
            ).apply(instance, FarmBusinessLevel::new)
    );

    // =========================================================================
    // Fields
    // =========================================================================

    private final UUID farmhouseId;
    private int businessLevel;          // 1-5, like profession levels
    private long totalRevenue;          // All-time revenue
    private long weeklyRevenue;         // Revenue this week
    private int plotCount;              // Number of plots owned
    private int farmhandCount;          // Number of workers
    private float qualityRating;        // 0.0-1.0, affects prices
    private long lastUpdateTick;        // For weekly reset

    // =========================================================================
    // Business level thresholds
    // =========================================================================

    private static final long[] LEVEL_THRESHOLDS = {
            0L,      // Level 1: startup
            500L,    // Level 2: established
            2000L,   // Level 3: prosperous
            5000L,   // Level 4: enterprise
            10000L   // Level 5: agricultural empire
    };

    // =========================================================================
    // Constructor
    // =========================================================================

    public FarmBusinessLevel(UUID farmhouseId, int businessLevel, long totalRevenue,
                             long weeklyRevenue, int plotCount, int farmhandCount,
                             float qualityRating, long lastUpdateTick) {
        this.farmhouseId = farmhouseId;
        this.businessLevel = businessLevel;
        this.totalRevenue = totalRevenue;
        this.weeklyRevenue = weeklyRevenue;
        this.plotCount = plotCount;
        this.farmhandCount = farmhandCount;
        this.qualityRating = qualityRating;
        this.lastUpdateTick = lastUpdateTick;
    }

    public FarmBusinessLevel(UUID farmhouseId) {
        this(farmhouseId, 1, 0L, 0L, 0, 0, 0.5f, 0L);
    }

    // =========================================================================
    // Business operations
    // =========================================================================

    /**
     * Record a sale and update business metrics
     */
    public void recordSale(long revenue, ServerLevel level) {
        this.totalRevenue += revenue;
        this.weeklyRevenue += revenue;

        // Check for level up
        int newLevel = calculateLevel();
        if (newLevel > businessLevel) {
            businessLevel = newLevel;
            System.out.println("Farm business leveled up to " + businessLevel);
            // TODO: Broadcast notification to owner
        }

        // Quality improves with consistent sales
        qualityRating = Math.min(1.0f, qualityRating + 0.001f);

        lastUpdateTick = level.getGameTime();
    }

    /**
     * Update plot and farmhand counts
     */
    public void updateMetrics(ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);

        // Count plots
        List<FarmPlot> plots = data.getFarmPlotsForFarmhouse(farmhouseId);
        this.plotCount = plots.size();

        // Count farmhands (NPCs assigned to this farmhouse)
        this.farmhandCount = (int) level.getEntitiesOfClass(
                tterrag1112.life_in_the_village.Entities.custom.TownspersonMob.class,
                new net.minecraft.world.phys.AABB(
                        data.getBuildingById(farmhouseId)
                                .map(b -> b.getShape().getOrigin())
                                .orElse(net.minecraft.core.BlockPos.ZERO)
                ).inflate(100),
                npc -> npc.getAssignedBuildingId()
                        .map(id -> id.equals(farmhouseId))
                        .orElse(false)
        ).size();

        // Weekly reset
        long currentTick = level.getGameTime();
        if (currentTick - lastUpdateTick > 168000L) { // 7 in-game days
            weeklyRevenue = 0;
            lastUpdateTick = currentTick;
        }
    }

    /**
     * Calculate player farmhand wages based on business level
     */
    public long calculatePlayerWage(int playerLevel) {
        // Base wage by player level
        long baseWage = WorkplaceAssignmentManager.WEEKLY_PAY[
                Math.min(playerLevel, WorkplaceAssignmentManager.WEEKLY_PAY.length - 1)];

        // Multiply by business level bonus
        float businessBonus = 1.0f + (businessLevel * 0.2f); // +20% per level

        return (long) (baseWage * businessBonus);
    }

    /**
     * Calculate task payment for player
     */
    public long calculateTaskPayment(long basePayment, int playerLevel) {
        // Similar to wage calculation
        float businessBonus = 1.0f + (businessLevel * 0.15f); // +15% per level
        return (long) (basePayment * businessBonus);
    }

    // =========================================================================
    // Level calculation
    // =========================================================================

    private int calculateLevel() {
        for (int i = LEVEL_THRESHOLDS.length - 1; i >= 0; i--) {
            if (totalRevenue >= LEVEL_THRESHOLDS[i]) {
                return i + 1;
            }
        }
        return 1;
    }

    // =========================================================================
    // Getters
    // =========================================================================

    public UUID getFarmhouseId() { return farmhouseId; }
    public int getBusinessLevel() { return businessLevel; }
    public long getTotalRevenue() { return totalRevenue; }
    public long getWeeklyRevenue() { return weeklyRevenue; }
    public int getPlotCount() { return plotCount; }
    public int getFarmhandCount() { return farmhandCount; }
    public float getQualityRating() { return qualityRating; }

    public String getBusinessLevelName() {
        return switch (businessLevel) {
            case 1 -> "Startup Farm";
            case 2 -> "Established Farm";
            case 3 -> "Prosperous Farm";
            case 4 -> "Farm Enterprise";
            case 5 -> "Agricultural Empire";
            default -> "Unknown";
        };
    }
}