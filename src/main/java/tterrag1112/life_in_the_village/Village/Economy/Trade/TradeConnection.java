// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Trade/TradeConnection.java
package tterrag1112.life_in_the_village.Village.Economy.Trade;

import java.util.List;
import java.util.UUID;

/**
 * Common surface for any persistent trade connection between two
 * villages. After C1 the only implementation is {@link SeaRoute}; land
 * routes have been folded into the road graph and are addressed by
 * {@link TradeRoute#getEdgeIds()} rather than by a connection ID.
 *
 * <p>The interface survives because future connection types (rivers,
 * mountain passes, etc.) and the planned C3 sea-route unification will
 * benefit from a shared abstraction.
 */
public interface TradeConnection {

    // =========================================================================
    // Identity
    // =========================================================================

    UUID getConnectionId();
    UUID getVillageA();
    UUID getVillageB();

    /** True if this connection is currently usable for trade. */
    boolean isActive();
    void setActive(boolean active);

    // =========================================================================
    // Quality and upkeep
    // =========================================================================

    /** 0–100. 0 = unusable, 100 = perfect. */
    int getQuality();
    void setQuality(int quality);

    /** Total upkeep cost in silver per maintenance cycle. */
    int getUpkeepCost();

    /** True if quality has dropped below the repair threshold. */
    boolean needsRepair();

    /**
     * Returns the speed multiplier caravans get from this connection.
     * 1.0 = base, &gt;1 = faster, &lt;1 = slower.
     */
    double getSpeedMultiplier();

    // =========================================================================
    // Length
    // =========================================================================

    /** Length in blocks (land) or equivalent block-distance (sea). */
    int getLength();

    // =========================================================================
    // Routes referencing this connection
    // =========================================================================

    /** Adds a route reference. Idempotent. */
    void addRouteReference(UUID routeId);

    /** Removes a route reference. */
    void removeRouteReference(UUID routeId);

    List<UUID> getRouteIds();

    // =========================================================================
    // Mode
    // =========================================================================

    /** Distinguishes connection types for dispatch and rendering. */
    Mode getMode();

    enum Mode {
        LAND,   // overland road, walked by people and animals
        SEA;    // open water, traversed by boats
    }
}