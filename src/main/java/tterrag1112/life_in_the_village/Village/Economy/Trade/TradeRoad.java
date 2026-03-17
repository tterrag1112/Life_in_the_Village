package tterrag1112.life_in_the_village.Village.Economy.Trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

public class TradeRoad {

    public static final Codec<TradeRoad> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.STRING.xmap(UUID::fromString,
                                    UUID::toString)
                            .fieldOf("roadId")
                            .forGetter(TradeRoad::getRoadId),
                    Codec.STRING.xmap(UUID::fromString,
                                    UUID::toString)
                            .fieldOf("villageA")
                            .forGetter(TradeRoad::getVillageA),
                    Codec.STRING.xmap(UUID::fromString,
                                    UUID::toString)
                            .fieldOf("villageB")
                            .forGetter(TradeRoad::getVillageB),
                    BlockPos.CODEC.listOf()
                            .fieldOf("blocks")
                            .forGetter(TradeRoad::getBlocks),
                    Codec.INT.fieldOf("quality")
                            .forGetter(TradeRoad::getQuality),
                    Codec.LONG.fieldOf("lastMaintenanceTick")
                            .forGetter(TradeRoad::getLastMaintenanceTick),
                    Codec.BOOL.fieldOf("active")
                            .forGetter(TradeRoad::isActive)
            ).apply(instance, TradeRoad::new));

    private final UUID roadId;
    private final UUID villageA;
    private final UUID villageB;
    private final List<BlockPos> blocks;
    private int quality;
    private long lastMaintenanceTick;
    private boolean active;

    // Quality degrades after this many ticks without maintenance
    private static final long MAINTENANCE_INTERVAL = 24000L * 7; // 1 week
    private static final int  QUALITY_DECAY        = 5;
    private static final int  MIN_QUALITY          = 0;
    private static final int  MAX_QUALITY          = 100;

    public static final int BLOCKED_THRESHOLD  = 20;  // below this = no trade
    public static final int MINIMUM_THRESHOLD  = 21;  // usable but no bonus
    public static final int GOOD_THRESHOLD     = 60;  // moderate speed bonus
    public static final int EXCELLENT_THRESHOLD = 80; // full speed bonus


    private static final int UPKEEP_COST_PER_100_BLOCKS = 2; // silver coins
    private static final int REPAIR_THRESHOLD = 50; // repair if below this

    public TradeRoad(UUID roadId, UUID villageA, UUID villageB,
                     List<BlockPos> blocks, int quality,
                     long lastMaintenanceTick, boolean active) {
        this.roadId              = roadId;
        this.villageA            = villageA;
        this.villageB            = villageB;
        this.blocks              = blocks;
        this.quality             = quality;
        this.lastMaintenanceTick = lastMaintenanceTick;
        this.active              = active;
    }

    public static TradeRoad create(UUID villageA, UUID villageB,
                                   List<BlockPos> blocks) {
        return new TradeRoad(
                UUID.randomUUID(),
                villageA, villageB,
                blocks,
                100,  // starts at full quality
                0L,
                true
        );
    }

    // -------------------------------------------------------------------------
    // Tick — called from VillageSavedData tick
    // -------------------------------------------------------------------------

    public boolean tick(long currentTick,
                        net.minecraft.server.level.ServerLevel level) {
        if (!active) return false;
        if (currentTick - lastMaintenanceTick
                < MAINTENANCE_INTERVAL) return false;

        // Degrade quality
        quality = Math.max(MIN_QUALITY,
                quality - QUALITY_DECAY);

        // Visually degrade road blocks
        RoadRouter.degradeRoad(level, blocks, quality);

        lastMaintenanceTick = currentTick;
        return true; // dirty
    }

    public void performMaintenance(long currentTick,
                                   net.minecraft.server.level.ServerLevel level) {
        quality = MAX_QUALITY;
        lastMaintenanceTick = currentTick;
        RoadRouter.repairRoad(level, blocks);
    }

    // -------------------------------------------------------------------------
    // Trade calculations
    // -------------------------------------------------------------------------

    /**
     * Returns the travel time multiplier for this road.
     * Higher quality = faster travel.
     * Lower quality = slower, less reliable trade.
     */

    public double getSpeedMultiplier() {
        if (quality <= BLOCKED_THRESHOLD) return 0.0;
        if (quality <= MINIMUM_THRESHOLD) return 1.0;

        // Scale from 1.0 to 2.0 between minimum and 100
        double normalized = (quality - MINIMUM_THRESHOLD)
                / (100.0 - MINIMUM_THRESHOLD);
        return 1.0 + normalized;
    }
    public double getTravelTimeMultiplier() {
        return getSpeedMultiplier();
    }

    /**
     * Returns the daily chance of a caravan being dispatched.
     * Based on road quality and length.
     */
    public float getDailyCaravanChance() {
        float qualityFactor  = quality / 100.0f;
        float distanceFactor = Math.max(0.1f,
                1.0f - (blocks.size() / 5000.0f));
        return qualityFactor * distanceFactor * 0.8f;
    }

    /**
     * Returns true if this road connects the two given villages.
     */
    public boolean connects(UUID a, UUID b) {
        return (villageA.equals(a) && villageB.equals(b))
                || (villageA.equals(b) && villageB.equals(a));
    }

    /**
     * Returns the upkeep cost in silver coins based on road length.
     */
    public int getUpkeepCost() {
        return Math.max(1,
                (blocks.size() / 100) * UPKEEP_COST_PER_100_BLOCKS);
    }

    /**
     * Returns true if the road needs repair.
     */
    public boolean needsRepair() {
        return quality < REPAIR_THRESHOLD;
    }

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public UUID getRoadId()                { return roadId; }
    public UUID getVillageA()              { return villageA; }
    public UUID getVillageB()              { return villageB; }
    public List<BlockPos> getBlocks()      { return blocks; }
    public int getQuality()                { return quality; }
    public long getLastMaintenanceTick()   { return lastMaintenanceTick; }
    public void setLastMaintenanceTick(long tick) {
        this.lastMaintenanceTick = tick;
    }
    public boolean isActive()              { return active; }
    public void setActive(boolean active)  { this.active = active; }
    public void setQuality(int q)          { this.quality = Math.max(MIN_QUALITY, Math.min(MAX_QUALITY, q)); }
    public int getRoadLength()             { return blocks.size(); }


    public boolean isRoadUsable(TradeRoad road) {
        return road.getQuality() > TradeRoad.BLOCKED_THRESHOLD;
    }
}