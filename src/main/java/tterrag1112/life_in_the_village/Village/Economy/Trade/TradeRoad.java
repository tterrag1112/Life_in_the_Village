// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Trade/TradeRoad.java
package tterrag1112.life_in_the_village.Village.Economy.Trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A physical road in the world. Owned by zero or more {@link TradeRoute}s
 * which reference it by ID.
 *
 * <h3>Phase 7a additions</h3>
 * <ul>
 *   <li><b>cellPath</b> — the sequence of atlas cell keys this road
 *       traverses. Populated by the cell-level router. Used to detect
 *       overlap when a new route's cell-path overlaps an existing road,
 *       enabling road sharing without re-placing blocks.</li>
 *   <li><b>routeIds</b> — the routes that reference this road. Allows
 *       multiple routes to share one set of blocks (the "trunk"
 *       behaviour). Reference counting is informational only —
 *       roads are not deleted when their last route is gone, per
 *       the "never delete roads" Phase 7a policy.</li>
 *   <li><b>realised</b> — false for routes planned by worldgen but not
 *       yet block-placed. The {@link RouteRealisationSystem} flips
 *       this to true when a player approaches the road's corridor
 *       and realises the segments near them.</li>
 * </ul>
 *
 * <h3>Backwards compatibility</h3>
 * The new fields all have optional codec defaults. Roads loaded from
 * pre-Phase-7a saves will have an empty cellPath, an empty routeIds
 * list, and realised=true. The route manager treats an empty cellPath
 * as "this is a legacy road, don't try to share it" so old saves
 * continue to work without migration.
 */
public class TradeRoad implements TradeConnection{

    public static final Codec<TradeRoad> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("roadId")
                            .forGetter(TradeRoad::getRoadId),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("villageA")
                            .forGetter(TradeRoad::getVillageA),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
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
                            .forGetter(TradeRoad::isActive),
                    // ── Phase 7a fields ─────────────────────────────────────
                    Codec.LONG.listOf()
                            .optionalFieldOf("cellPath", new ArrayList<>())
                            .forGetter(TradeRoad::getCellPath),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString).listOf()
                            .optionalFieldOf("routeIds", new ArrayList<>())
                            .forGetter(TradeRoad::getRouteIds),
                    Codec.BOOL.optionalFieldOf("realised", true)
                            .forGetter(TradeRoad::isRealised)
            ).apply(instance, TradeRoad::new));

    private final UUID roadId;
    private final UUID villageA;
    private final UUID villageB;
    private List<BlockPos> blocks;
    private int quality;
    private long lastMaintenanceTick;
    private boolean active;

    // ── Phase 7a state ───────────────────────────────────────────────────────
    private final List<Long> cellPath;
    private final List<UUID> routeIds;
    private boolean realised;

    private static final long MAINTENANCE_INTERVAL = 24000L * 7;
    private static final int  QUALITY_DECAY        = 5;
    private static final int  MIN_QUALITY          = 0;
    private static final int  MAX_QUALITY          = 100;

    public static final int BLOCKED_THRESHOLD   = 20;
    public static final int MINIMUM_THRESHOLD   = 21;
    public static final int GOOD_THRESHOLD      = 60;
    public static final int EXCELLENT_THRESHOLD = 80;

    private static final int UPKEEP_COST_PER_100_BLOCKS = 2;
    private static final int REPAIR_THRESHOLD = 50;

    // =========================================================================
    // Constructors
    // =========================================================================

    public TradeRoad(UUID roadId, UUID villageA, UUID villageB,
                     List<BlockPos> blocks, int quality,
                     long lastMaintenanceTick, boolean active,
                     List<Long> cellPath, List<UUID> routeIds,
                     boolean realised) {
        this.roadId              = roadId;
        this.villageA            = villageA;
        this.villageB            = villageB;
        this.blocks              = new ArrayList<>(blocks);
        this.quality             = quality;
        this.lastMaintenanceTick = lastMaintenanceTick;
        this.active              = active;
        this.cellPath            = new ArrayList<>(cellPath);
        this.routeIds            = new ArrayList<>(routeIds);
        this.realised            = realised;
    }

    /** Backwards-compat constructor for legacy code paths. */
    public TradeRoad(UUID roadId, UUID villageA, UUID villageB,
                     List<BlockPos> blocks, int quality,
                     long lastMaintenanceTick, boolean active) {
        this(roadId, villageA, villageB, blocks, quality,
                lastMaintenanceTick, active,
                new ArrayList<>(), new ArrayList<>(), true);
    }

    // =========================================================================
    // Factories
    // =========================================================================

    /** Legacy create — used by pre-7a code. Produces a realised road with no cell path. */
    public static TradeRoad create(UUID villageA, UUID villageB,
                                   List<BlockPos> blocks) {
        return new TradeRoad(
                UUID.randomUUID(),
                villageA, villageB,
                blocks,
                100,
                0L,
                true,
                new ArrayList<>(),
                new ArrayList<>(),
                true);
    }

    /** Phase 7a — creates a planned road with a cell path but no blocks yet. */
    public static TradeRoad createPlanned(UUID villageA, UUID villageB,
                                          List<Long> cellPath) {
        return new TradeRoad(
                UUID.randomUUID(),
                villageA, villageB,
                new ArrayList<>(),
                100,
                0L,
                true,
                cellPath,
                new ArrayList<>(),
                false);
    }

    /** Phase 7a — creates a realised road that already has blocks placed. */
    public static TradeRoad createRealised(UUID villageA, UUID villageB,
                                           List<BlockPos> blocks,
                                           List<Long> cellPath) {
        return new TradeRoad(
                UUID.randomUUID(),
                villageA, villageB,
                blocks,
                100,
                0L,
                true,
                cellPath,
                new ArrayList<>(),
                true);
    }

    // =========================================================================
    // Tick — unchanged from previous version
    // =========================================================================

    public boolean tick(long currentTick,
                        net.minecraft.server.level.ServerLevel level) {
        if (!active) return false;
        if (!realised) return false; // planned roads don't degrade
        if (currentTick - lastMaintenanceTick < MAINTENANCE_INTERVAL) return false;

        quality = Math.max(MIN_QUALITY, quality - QUALITY_DECAY);
        RoadRouter.degradeRoad(level, blocks, quality);

        lastMaintenanceTick = currentTick;
        return true;
    }

    public void performMaintenance(long currentTick,
                                   net.minecraft.server.level.ServerLevel level) {
        quality = MAX_QUALITY;
        lastMaintenanceTick = currentTick;
        if (realised) RoadRouter.repairRoad(level, blocks);
    }

    // =========================================================================
    // Phase 7a state changes
    // =========================================================================

    /**
     * Marks this road as realised and stores its block list. Called by
     * the realiser when a planned road's blocks are first placed.
     */
    public void markRealised(List<BlockPos> placedBlocks) {
        this.blocks = new ArrayList<>(placedBlocks);
        this.realised = true;
    }

    /** Adds a route reference. Idempotent. */
    public void addRouteReference(UUID routeId) {
        if (!routeIds.contains(routeId)) routeIds.add(routeId);
    }

    /** Removes a route reference. The road itself is not deleted. */
    public void removeRouteReference(UUID routeId) {
        routeIds.remove(routeId);
    }

    public int getReferenceCount() { return routeIds.size(); }

    // =========================================================================
    // Trade calculations — unchanged from previous version
    // =========================================================================

    public double getSpeedMultiplier() {
        if (quality <= BLOCKED_THRESHOLD) return 0.0;
        if (quality <= MINIMUM_THRESHOLD) return 1.0;
        double normalized = (quality - MINIMUM_THRESHOLD)
                / (100.0 - MINIMUM_THRESHOLD);
        return 1.0 + normalized;
    }

    public double getTravelTimeMultiplier() { return getSpeedMultiplier(); }

    public float getDailyCaravanChance() {
        float qualityFactor  = quality / 100.0f;
        float distanceFactor = Math.max(0.1f,
                1.0f - (blocks.size() / 5000.0f));
        return qualityFactor * distanceFactor * 0.8f;
    }

    public boolean connects(UUID a, UUID b) {
        return (villageA.equals(a) && villageB.equals(b))
                || (villageA.equals(b) && villageB.equals(a));
    }

    public int getUpkeepCost() {
        return Math.max(1,
                (blocks.size() / 100) * UPKEEP_COST_PER_100_BLOCKS);
    }

    public boolean needsRepair() {
        return realised && quality < REPAIR_THRESHOLD;
    }

    // =========================================================================
    // Getters / setters
    // =========================================================================

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
    public void setQuality(int q) {
        this.quality = Math.max(MIN_QUALITY, Math.min(MAX_QUALITY, q));
    }
    public int getRoadLength()             { return blocks.size(); }

    // ── Phase 7a getters ─────────────────────────────────────────────────────

    public List<Long> getCellPath()        { return cellPath; }
    public List<UUID> getRouteIds()        { return routeIds; }
    public boolean isRealised()            { return realised; }
    public boolean hasCellPath()           { return !cellPath.isEmpty(); }

    public boolean isRoadUsable(TradeRoad road) {
        return road.getQuality() > TradeRoad.BLOCKED_THRESHOLD;
    }
    @Override
    public UUID getConnectionId() { return roadId; }

    @Override
    public Mode getMode() { return Mode.LAND; }

    @Override
    public int getLength() { return blocks.size(); }
}