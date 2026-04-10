// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Trade/TradeConnection.java
package tterrag1112.life_in_the_village.Village.Economy.Trade;

import java.util.List;
import java.util.UUID;

/**
 * Common surface for any persistent trade connection between two
 * villages. Implemented by {@link TradeRoad} (overland) and
 * {@link SeaRoute} (over water), and could be implemented by future
 * connection types like rivers, mountain passes, or magical portals.
 *
 * <h3>What this interface unifies</h3>
 * The {@link TradeRouteManager}, route upkeep tick, route quality
 * checks, and trade efficiency calculations all need to work on any
 * connection type without caring whether it's a road or a sea route.
 * {@link TradeRoute} references its connection by UUID, and
 * {@link tterrag1112.life_in_the_village.Networking.VillageSavedData}
 * looks it up against both collections in parallel.
 *
 * <h3>What this interface does NOT unify</h3>
 * <ul>
 *   <li><b>Path representation.</b> Land roads are block lists; sea
 *       routes are cell lists. Each implementation owns its own.</li>
 *   <li><b>Realisation.</b> Land roads place blocks; sea routes don't.
 *       The interface is silent on this — each type's
 *       {@link TravellingGroup} caravans handle their own
 *       spawn/despawn details.</li>
 *   <li><b>Caravan type.</b> {@link Caravan} uses land roads;
 *       {@link BoatCaravan} uses sea routes. They share
 *       {@link TravellingGroup} but not their spawn logic.</li>
 * </ul>
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