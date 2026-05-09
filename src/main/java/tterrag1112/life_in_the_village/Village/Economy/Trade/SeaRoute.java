// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Trade/SeaRoute.java
package tterrag1112.life_in_the_village.Village.Economy.Trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A trade connection over open water. Land routes live on the road graph
 * and are addressed by edge IDs; SeaRoute remains a connection-by-UUID
 * record until C3 / Phase 13 folds it into the graph.
 *
 * <h3>No block-level realisation</h3>
 * Unlike land roads, sea routes don't place anything in the world.
 * The cell path exists in saved data; boat caravans navigate it
 * directly by interpolating between cell centres at sea level.
 * There are no road blocks, no degradation visuals, no bridge
 * placement. The "road" is the line on a future GUI map and the
 * waypoints a boat follows.
 *
 * <h3>Endpoints</h3>
 * Sea routes always connect two dock buildings. The {@link #dockA}
 * and {@link #dockB} fields store the dock building UUIDs so the
 * boat caravan can pull and return its boat at the right place.
 *
 * <h3>Quality and upkeep</h3>
 * Sea routes still have a quality score (representing piracy risk,
 * weather conditions, navigational hazards) and upkeep (representing
 * harbour maintenance and patrol costs at both docks). The numbers
 * are smaller than land roads because there's no physical road to
 * maintain — only the docks and the route knowledge.
 */
public class SeaRoute implements TradeConnection {

    // =========================================================================
    // Constants
    // =========================================================================

    private static final long MAINTENANCE_INTERVAL = 24000L * 14; // 2 weeks
    private static final int  QUALITY_DECAY        = 3;
    private static final int  MIN_QUALITY          = 0;
    private static final int  MAX_QUALITY          = 100;

    private static final int UPKEEP_COST_PER_CELL = 1; // silver per atlas cell
    private static final int REPAIR_THRESHOLD     = 50;

    public static final int BLOCKED_THRESHOLD   = 20;
    public static final int MINIMUM_THRESHOLD   = 21;
    public static final int GOOD_THRESHOLD      = 60;
    public static final int EXCELLENT_THRESHOLD = 80;

    // =========================================================================
    // Codec
    // =========================================================================

    public static final Codec<SeaRoute> CODEC = RecordCodecBuilder.create(i ->
            i.group(
                    UUIDUtil.CODEC.fieldOf("seaRouteId")
                            .forGetter(s -> s.seaRouteId),
                    UUIDUtil.CODEC.fieldOf("villageA")
                            .forGetter(s -> s.villageA),
                    UUIDUtil.CODEC.fieldOf("villageB")
                            .forGetter(s -> s.villageB),
                    UUIDUtil.CODEC.fieldOf("dockA")
                            .forGetter(s -> s.dockA),
                    UUIDUtil.CODEC.fieldOf("dockB")
                            .forGetter(s -> s.dockB),
                    Codec.LONG.listOf()
                            .fieldOf("cellPath")
                            .forGetter(s -> s.cellPath),
                    Codec.INT.fieldOf("quality")
                            .forGetter(s -> s.quality),
                    Codec.LONG.fieldOf("lastMaintenanceTick")
                            .forGetter(s -> s.lastMaintenanceTick),
                    Codec.BOOL.fieldOf("active")
                            .forGetter(s -> s.active),
                    UUIDUtil.CODEC.listOf()
                            .optionalFieldOf("routeIds", new ArrayList<>())
                            .forGetter(s -> s.routeIds)
            ).apply(i, SeaRoute::new));

    // =========================================================================
    // Fields
    // =========================================================================

    private final UUID seaRouteId;
    private final UUID villageA;
    private final UUID villageB;
    private final UUID dockA;
    private final UUID dockB;
    private final List<Long> cellPath;
    private int quality;
    private long lastMaintenanceTick;
    private boolean active;
    private final List<UUID> routeIds;

    public SeaRoute(UUID seaRouteId,
                    UUID villageA, UUID villageB,
                    UUID dockA, UUID dockB,
                    List<Long> cellPath,
                    int quality,
                    long lastMaintenanceTick,
                    boolean active,
                    List<UUID> routeIds) {
        this.seaRouteId          = seaRouteId;
        this.villageA            = villageA;
        this.villageB            = villageB;
        this.dockA               = dockA;
        this.dockB               = dockB;
        this.cellPath            = new ArrayList<>(cellPath);
        this.quality             = quality;
        this.lastMaintenanceTick = lastMaintenanceTick;
        this.active              = active;
        this.routeIds            = new ArrayList<>(routeIds);
    }

    public static SeaRoute create(UUID villageA, UUID villageB,
                                  UUID dockA, UUID dockB,
                                  List<Long> cellPath) {
        return new SeaRoute(
                UUID.randomUUID(),
                villageA, villageB,
                dockA, dockB,
                cellPath,
                100,
                0L,
                true,
                new ArrayList<>());
    }

    // =========================================================================
    // TradeConnection implementation
    // =========================================================================

    @Override public UUID getConnectionId() { return seaRouteId; }
    @Override public UUID getVillageA()     { return villageA; }
    @Override public UUID getVillageB()     { return villageB; }
    @Override public boolean isActive()     { return active; }
    @Override public void setActive(boolean active) { this.active = active; }
    @Override public int getQuality()       { return quality; }
    @Override public void setQuality(int q) { this.quality = Math.max(MIN_QUALITY, Math.min(MAX_QUALITY, q)); }

    @Override
    public int getUpkeepCost() {
        return Math.max(1, cellPath.size() * UPKEEP_COST_PER_CELL);
    }

    @Override
    public boolean needsRepair() {
        return quality < REPAIR_THRESHOLD;
    }

    @Override
    public double getSpeedMultiplier() {
        if (quality <= BLOCKED_THRESHOLD) return 0.0;
        if (quality <= MINIMUM_THRESHOLD) return 1.0;
        double normalized = (quality - MINIMUM_THRESHOLD)
                / (100.0 - MINIMUM_THRESHOLD);
        // Sea routes are slower than roads at base — boats are slow.
        return 0.7 + normalized * 0.6;
    }

    @Override
    public int getLength() {
        // Each atlas cell is 64 blocks; approximate equivalent length.
        return cellPath.size() * 64;
    }

    @Override public void addRouteReference(UUID routeId) {
        if (!routeIds.contains(routeId)) routeIds.add(routeId);
    }

    @Override public void removeRouteReference(UUID routeId) {
        routeIds.remove(routeId);
    }

    @Override public List<UUID> getRouteIds() { return routeIds; }

    @Override public Mode getMode() { return Mode.SEA; }

    // =========================================================================
    // Sea-specific accessors
    // =========================================================================

    public UUID getDockA() { return dockA; }
    public UUID getDockB() { return dockB; }
    public List<Long> getCellPath() { return cellPath; }

    public boolean tick(long currentTick) {
        if (!active) return false;
        if (currentTick - lastMaintenanceTick < MAINTENANCE_INTERVAL) return false;

        quality = Math.max(MIN_QUALITY, quality - QUALITY_DECAY);
        lastMaintenanceTick = currentTick;
        return true;
    }

    public void performMaintenance(long currentTick) {
        quality = MAX_QUALITY;
        lastMaintenanceTick = currentTick;
    }

    public void setLastMaintenanceTick(long tick) { this.lastMaintenanceTick = tick; }
    public long getLastMaintenanceTick() { return lastMaintenanceTick; }
}