package tterrag1112.life_in_the_village.Events;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers.AdventurerSavedData;
import tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.CaravanSavedData;

/**
 * Contract for any system that needs periodic ticking from the server loop.
 *
 * <h3>Why this exists</h3>
 * Previously, {@link ServerTickDispatcher} called every subsystem inline
 * in a single flat method. This made it impossible to:
 * <ul>
 *   <li>Add/remove systems without editing the dispatcher</li>
 *   <li>Isolate failures — one exception killed every system after it</li>
 *   <li>Profile individual systems</li>
 *   <li>Control tick frequency per system</li>
 * </ul>
 *
 * Each subsystem declares its own interval and the dispatcher honours it,
 * wrapping each call in error isolation.
 *
 * <h3>Naming convention</h3>
 * Implementations should be named {@code XxxTickSystem} and registered
 * in {@link TickSubsystemRegistry#registerDefaults()}.
 */
public interface TickSubsystem {

    /**
     * A short name for logging and profiling.
     * e.g. "adventurer", "caravan", "expansion"
     */
    String name();

    /**
     * How often this system should tick, in game ticks.
     * Return 1 for every tick, 20 for once per second, 24000 for once per day.
     */
    int interval();

    /**
     * Priority for ordering within the same interval group.
     * Lower = earlier. Default implementations should use 100.
     * Use 0–50 for systems that other systems depend on (e.g. needs calculator).
     * Use 200+ for systems that consume results from earlier systems.
     */
    default int priority() { return 100; }

    /**
     * Perform the tick work.
     *
     * @param ctx immutable snapshot of all shared data sources for this tick
     */
    void tick(TickContext ctx);

    /**
     * Immutable context passed to every subsystem on each tick.
     * Avoids each system independently calling {@code VillageSavedData.get()}
     * and ensures all systems within a single server tick share the same
     * data references.
     */
    record TickContext(
            ServerLevel level,
            long tick,
            VillageSavedData villageData,
            CompanySavedData companyData,
            AdventurerSavedData adventurerData,
            CaravanSavedData caravanData
    ) {}
}