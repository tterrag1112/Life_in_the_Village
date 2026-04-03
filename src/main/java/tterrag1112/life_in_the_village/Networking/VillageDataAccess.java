package tterrag1112.life_in_the_village.Networking;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Guilds.Adventurer.GuildData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

/**
 * Domain-scoped read-only views of {@link VillageSavedData}.
 *
 * <h3>The problem</h3>
 * VillageSavedData is a god object — every system reaches into it to read
 * and mutate villages, buildings, trade routes, guilds, kingdoms, events,
 * properties, orders, and reputation. This creates invisible coupling:
 * a change to how buildings are stored can break the trade route system
 * because both access the same mutable maps.
 *
 * <h3>The solution (incremental)</h3>
 * Rather than splitting VillageSavedData into separate SavedData subclasses
 * (which NeoForge makes painful), we introduce <b>view interfaces</b> that
 * expose only the queries a given domain needs. Systems receive these views
 * instead of the full VillageSavedData.
 *
 * <h3>Rules</h3>
 * <ol>
 *   <li>Managers (ReputationManager, CraftingOrderManager, etc.) receive
 *       the full VillageSavedData because they own their domain's mutations.</li>
 *   <li>Goals, tick systems, and UI code receive the appropriate View
 *       interface, which only exposes reads.</li>
 *   <li>No code outside a Manager should call {@code data.setDirty()}
 *       directly — dirty marking is the manager's responsibility.</li>
 * </ol>
 *
 * <h3>Migration path</h3>
 * 1. Add these interfaces now.
 * 2. Make VillageSavedData implement all of them (it already has the methods).
 * 3. Gradually change method signatures from {@code VillageSavedData} to the
 *    appropriate view. This is a type-narrowing refactor — existing code
 *    compiles without changes, but new code gets compile-time enforcement.
 *
 * <h3>Example</h3>
 * <pre>
 * // Old (any code can mutate anything):
 * public void doWork(VillageSavedData data) {
 *     data.addTradeRoute(...);  // compiles, but shouldn't be here
 * }
 *
 * // New (trade routes not visible):
 * public void doWork(VillageDataAccess.VillageView data) {
 *     data.addTradeRoute(...);  // compile error — method not on interface
 * }
 * </pre>
 */
public final class VillageDataAccess {

    private VillageDataAccess() {}

    // =========================================================================
    // View interfaces
    // =========================================================================

    /**
     * Read-only access to village and building data.
     * Used by: NPC goals, decoration systems, UI packet builders.
     */
    public interface VillageView {
        List<Village> getAllVillages();
        Optional<Village> getVillageById(UUID id);
        Optional<Village> getVillageByName(String name);
        Optional<Building> getBuildingById(UUID id);
        Optional<Building> getBuildingAt(net.minecraft.core.BlockPos pos);
        List<Building> getAllBuildings();
    }

    /**
     * Read-only access to reputation data.
     * Used by: trade handlers (to check tier), NPC interaction (greeting tone).
     */
    public interface ReputationView {
        Optional<tterrag1112.life_in_the_village.Village.Reputation.VillageReputation>
        getReputation(UUID playerId, UUID villageId);
    }


    /**
     * Read-only access to economy data (trade routes, orders, listings, treasury).
     * Used by: economy display UIs, caravan dispatch logic, market tax hooks.
     */
    public interface EconomyView extends TreasuryView {
        List<tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoute>
        getAllTradeRoutes();

        Optional<tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoute>
        getRouteBetween(UUID villageA, UUID villageB);

        List<tterrag1112.life_in_the_village.Village.Economy.CraftingOrder>
        getOrdersForVillage(UUID villageId);
    }
    /**
     * Read-only access to village treasury data.
     * Used by: economy display UIs, NPC wage goals, market tax hooks.
     */
    public interface TreasuryView {
        Optional<tterrag1112.life_in_the_village.Village.Economy.VillageTreasury>
        getTreasury(java.util.UUID villageId);
    }

    /**
     * Read-only access to kingdom data.
     * Used by: diplomacy UI, kingdom book screen.
     */
    public interface KingdomView {
        List<tterrag1112.life_in_the_village.Kingdom.Kingdom> getAllKingdoms();
        Optional<tterrag1112.life_in_the_village.Kingdom.Kingdom>
        getKingdomById(UUID id);
        Optional<tterrag1112.life_in_the_village.Kingdom.Kingdom>
        getKingdomForVillage(UUID villageId);
    }

    /**
     * Read-only access to household and property data.
     * Used by: social goals, family-related goals.
     */
    public interface HouseholdView {
        Optional<tterrag1112.life_in_the_village.Entities.HouseholdData>
        getHouseholdForBuilding(UUID buildingId);

        Optional<tterrag1112.life_in_the_village.Entities.HouseholdData>
        getHouseholdForNpc(UUID npcId);

        boolean isPlayerOwned(UUID buildingId);
    }

    /**
     * Read-only access to guild data.
     * Used by: guild screen packet builder, adventurer spawn system.
     */
    public interface GuildView {
        Optional<GuildData>
        getGuildForVillage(UUID villageId);

        Optional<GuildData>
        getGuildById(UUID guildId);
    }


    /**
     * Read-only access to simulation snapshots and treasury.
     * Used by: kingdom economy engine, village book statistics,
     * treasury tick handler.
     */
    public interface SimulationView extends TreasuryView {
        Optional<tterrag1112.life_in_the_village.Village.Simulation.VillageSimData>
        getSimData(UUID villageId);
    }

    // =========================================================================
    // Composite view for common use cases
    // =========================================================================

    /**
     * Combined read-only view for NPC goals that need village + household
     * data but should never touch trade routes or kingdoms.
     */
    public interface NpcGoalView extends VillageView, HouseholdView {}

    /**
     * Combined view for the village book UI that needs most read data.
     */
    /**
     * Combined view for the village book UI that needs most read data.
     */
    public interface VillageBookView extends VillageView, ReputationView,
            EconomyView, KingdomView, HouseholdView, GuildView,
            TreasuryView {}

    // =========================================================================
    // Implementation note
    // =========================================================================

    /**
     * To make VillageSavedData implement these interfaces, add:
     * <pre>
     * public class VillageSavedData extends SavedData
     *         implements VillageDataAccess.VillageView,
     *                    VillageDataAccess.ReputationView,
     *                    VillageDataAccess.EconomyView,
     *                    VillageDataAccess.KingdomView,
     *                    VillageDataAccess.HouseholdView,
     *                    VillageDataAccess.GuildView,
     *                    VillageDataAccess.SimulationView {
     *     // All methods already exist — just add the implements clause
     * }
     * </pre>
     *
     * Then change method signatures incrementally:
     * <pre>
     * // Before:
     * public static void tick(ServerLevel level, VillageSavedData data) { ... }
     *
     * // After (only reads villages and buildings):
     * public static void tick(ServerLevel level, VillageDataAccess.VillageView data) { ... }
     * </pre>
     */
    public static final String IMPLEMENTATION_NOTE =
            "Add 'implements VillageDataAccess.VillageView, ...' to VillageSavedData";
}