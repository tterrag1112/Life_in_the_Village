package tterrag1112.life_in_the_village.Events;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadWeatheringSystem;
import tterrag1112.life_in_the_village.Village.Roads.Debug.RoadDebugVisualizer;
import tterrag1112.life_in_the_village.World.Atlas.AtlasFillSystem;

import java.util.*;

/**
 * Manages the lifecycle of all {@link TickSubsystem} implementations.
 *
 * <h3>Registration</h3>
 * Call {@link #registerDefaults()} once during mod setup (from
 * {@link ServerTickDispatcher} static init). Additional systems can be
 * registered at any time via {@link #register(TickSubsystem)}.
 *
 * <h3>Execution model</h3>
 * On each server tick, {@link #tickAll(TickSubsystem.TickContext)} iterates
 * all registered systems in priority order. For each system:
 * <ol>
 *   <li>Check if {@code tick % system.interval() == 0}</li>
 *   <li>If yes, call {@code system.tick(ctx)} inside a try-catch</li>
 *   <li>Log any exception with the system name so failures are traceable</li>
 *   <li>Continue to the next system regardless of failure</li>
 * </ol>
 *
 * <h3>Profiling</h3>
 * Each system's last execution time (nanoseconds) is recorded and
 * accessible via {@link #getLastTimingNanos(String)} for debug commands.
 */
public final class TickSubsystemRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final List<TickSubsystem> SYSTEMS = new ArrayList<>();
    private static boolean sorted = false;

    /**
     * Nanosecond timing for the most recent tick of each system.
     * Keyed by {@link TickSubsystem#name()}.
     */
    private static final Map<String, Long> LAST_TIMING = new LinkedHashMap<>();

    /**
     * Cumulative error count per system since server start.
     * Systems that error frequently can be flagged in debug output.
     */
    private static final Map<String, Integer> ERROR_COUNTS = new LinkedHashMap<>();

    // =========================================================================
    // Registration
    // =========================================================================

    /**
     * Register a subsystem. Can be called at any time — the list is re-sorted
     * on the next tick.
     */
    public static void register(TickSubsystem system) {
        SYSTEMS.add(system);
        sorted = false;
        LOGGER.debug("Registered tick subsystem: {} (interval={}, priority={})",
                system.name(), system.interval(), system.priority());
    }

    /**
     * Registers all built-in subsystems. Called once from
     * {@link ServerTickDispatcher} on first server tick.
     */
    public static void registerDefaults() {
        if (!SYSTEMS.isEmpty()) return; // already registered

        // ── Every-tick systems (interval = 1) ────────────────────────────────
        register(new EventTickSystem());

        // ── Every-second systems (interval = 20) ─────────────────────────────
        register(new AdventurerTickSystem());
        register(new CaravanTickSystem());
        register(new CompanyTickSystem());
        register(new TradeRouteTickSystem());
        register(new WarningTickSystem());
        register(new ExpansionTickSystem());
        register(new AgingTickSystem());
        register(new PropertyTaxTickSystem());
        register(new WorkplacePayTickSystem());
        register(new CraftingOrderTickSystem());
        register(new PlayerPerkTickSystem());

        // ── Per-village daily systems are handled inside VillageDailyTickSystem
        register(new VillageDailyTickSystem());

        // ── Road weathering (once per day, staggered) ────────────────────────
        register(new RoadWeatheringSystem());

        register(new WanderingTraderTickSystem());

        register(new AtlasFillSystem());
        register(new VillageRealisationSystem());
        register(new RouteRealisationTickSystem());
        register(new RoadEventTickSystem());
        register(new BoatCaravanTickSystem());
        register(new GraphEdgeRealizationSystem());
        register(new ParallelismCleanupSystem());
        register(new NodeDecorationTickSystem());
        register(new RoadUpkeepTickSystem());
        register(new TierReconciliationTickSystem());
        register(new NpcMemoryDecayTickSystem());

        // ── Debug visualization (every-tick, low priority) ────────────────────
        register(RoadDebugVisualizer.INSTANCE);





        LOGGER.info("Registered {} tick subsystems", SYSTEMS.size());
    }

    // =========================================================================
    // Execution
    // =========================================================================

    /**
     * Called once per server tick from {@link ServerTickDispatcher}.
     * Iterates all registered systems, respecting intervals, with error
     * isolation per system.
     */
    public static void tickAll(TickSubsystem.TickContext ctx) {
        if (!sorted) {
            SYSTEMS.sort(Comparator.comparingInt(TickSubsystem::interval)
                    .thenComparingInt(TickSubsystem::priority));
            sorted = true;
        }

        long tick = ctx.tick();

        for (TickSubsystem system : SYSTEMS) {
            if (tick % system.interval() != 0) continue;

            long startNanos = System.nanoTime();
            try {
                system.tick(ctx);
            } catch (Exception e) {
                int count = ERROR_COUNTS.merge(system.name(), 1, Integer::sum);
                if (count <= 5 || count % 100 == 0) {
                    LOGGER.error("[TickSubsystem] '{}' threw on tick {} " +
                                    "(error #{}, suppressing further logs until #{})",
                            system.name(), tick, count, count <= 5 ? count + 1 : count + 100, e);
                }
            }
            long elapsed = System.nanoTime() - startNanos;
            LAST_TIMING.put(system.name(), elapsed);
        }
    }

    // =========================================================================
    // Diagnostics
    // =========================================================================

    /** Returns the last tick duration in nanoseconds for the named system. */
    public static long getLastTimingNanos(String systemName) {
        return LAST_TIMING.getOrDefault(systemName, 0L);
    }

    /** Returns the total error count for the named system since server start. */
    public static int getErrorCount(String systemName) {
        return ERROR_COUNTS.getOrDefault(systemName, 0);
    }

    /** Returns an unmodifiable snapshot of all registered system names. */
    public static List<String> getSystemNames() {
        return SYSTEMS.stream().map(TickSubsystem::name).toList();
    }

    /** Returns timing data for all systems as name → microseconds. */
    public static Map<String, Long> getAllTimingsMicros() {
        Map<String, Long> result = new LinkedHashMap<>();
        LAST_TIMING.forEach((name, nanos) -> result.put(name, nanos / 1000));
        return result;
    }

    /** Resets error counts — useful after fixing a known issue at runtime. */
    public static void resetErrorCounts() {
        ERROR_COUNTS.clear();
    }
}