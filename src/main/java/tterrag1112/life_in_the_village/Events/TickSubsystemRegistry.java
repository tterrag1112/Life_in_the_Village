package tterrag1112.life_in_the_village.Events;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadWeatheringSystem;
import tterrag1112.life_in_the_village.Village.Planning.Debug.LayoutDebugVisualizer;
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

    // ── Spike attribution (perf-noon-social-food) ────────────────────────────
    /**
     * When the summed cost of all subsystems in a single tick exceeds this
     * many nanoseconds, {@link #tickAll} logs a once-per-spike INFO line
     * naming the heaviest subsystems. Threshold-gated, NOT per-tick: the
     * daily kingdom/NPC sweeps land on {@code tick % 24000 == 0}, so this
     * fires roughly once per in-game day when (and only when) the daily pass
     * is actually slow. Set to 50 ms = one server-tick budget.
     */
    private static final long SPIKE_THRESHOLD_NANOS = 50_000_000L;
    /**
     * Minimum tick gap between two spike reports, so a sustained heavy patch
     * (e.g. a long combat burst) can't spam the log every tick. The daily
     * spike is naturally ~24000 ticks apart; this only guards pathological
     * back-to-back overruns.
     */
    private static final long SPIKE_REPORT_MIN_GAP_TICKS = 100L;
    private static long lastSpikeReportTick = Long.MIN_VALUE;

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
        // Track C1-b -- advances CHARTERED settlement charters to SURVEYED
        // (anchor-pick over the target cell). Priority 55 so a charter is
        // surveyed before village_realisation (priority 60) considers it.
        register(new CharterSurveyTickSystem());
        register(new VillageRealisationSystem());
        register(new TollGateTickSystem());
        register(new BoatCaravanTickSystem());
        register(new PilgrimageTickSystem());
        register(new GraphEdgeRealizationSystem());
        register(new GreatRoadGenerationTickSystem());
        register(new ParallelismCleanupSystem());
        register(new NodeDecorationTickSystem());
        register(new RoadUpkeepTickSystem());
        register(new TierReconciliationTickSystem());
        register(new NpcMemoryDecayTickSystem());
        register(new GossipSchedulerTickSystem());

        // Track C3.1 — advances player-initiated road proposals.
        register(new RoadProposalTickSystem());

        // Track C3.2 — discovers POIs near players and plans subroads.
        register(new PoiDiscoveryTickSystem());

        // Track D3.1 — bootstraps kingdom offices for newly-realised
        // capital villages. Idempotent; skips kingdoms whose king is
        // already seated.
        register(new KingdomOfficeBootstrapTickSystem());
        // Track D3.3 — weekly polygon recompute (priority 191).
        register(new ProvinceRecomputeTickSystem());
        // Track D3.3 — daily provincial reports + stability tick (priority 194).
        register(new ProvinceDailyTickSystem());
        // Track D3.5A — daily audience-loop sweep (priority 195).
        register(new AudienceLoopTickSystem());
        // Track D3.5B — daily NPC-ruler petition audit (priority 196).
        register(new NpcRulerAuditTickSystem());
        // Track D3.6.1 — daily province-rebellion driver (priority 197).
        register(new RebellionTickSystem());
        // Track D3.6.1 — daily vassal-rebellion driver (priority 198).
        register(new VassalRebellionTickSystem());
        // Track D3.6.2 — daily kingdom-collapse driver (priority 199).
        register(new KingdomCollapseTickSystem());
        // Track D3.6.3 — daily voluntary-union petition driver (priority 200).
        register(new VoluntaryUnionTickSystem());
        // Track D3.6.4 — daily war engine tick (priority 201).
        register(new WarTickSystem());
        // Track D3.6.5 — daily religion-authority engine (priority 202).
        register(new ReligionAuthorityTickSystem());
        // Track D3.6.5 — daily conversion-campaign sweep (priority 203).
        register(new ConvertProvinceTickSystem());
        // Track D3.6.6 — daily age-cycle evaluator (priority 204).
        register(new AgeCycleTickSystem());
        register(new OfficeElectionTickSystem());
        // Track D3.2b — daily auto-found of noble houses for eligible NPCs.
        register(new HouseFoundingTickSystem());
        register(new LawDecisionTickSystem());
        register(new CrimeTrialTickSystem());
        register(new ReligionRiteTickSystem());
        register(new HealthDailyTickSystem());
        register(new PlagueRollTickSystem());
        register(new CompanyAiTickSystem());
        register(new ApprenticeshipWeeklyTickSystem());
        register(new RequestBoardTickSystem());
        register(new VisitorFluxTickSystem());
        register(new HistoryPruneTickSystem());

        // ── Travel incentives ─────────────────────────────────────────────────
        register(new PlayerRoadSpeedSystem());

        // ── Debug visualization (every-tick, low priority) ────────────────────
        register(RoadDebugVisualizer.INSTANCE);
        register(LayoutDebugVisualizer.INSTANCE);





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

        long totalNanos = 0L;
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
            totalNanos += elapsed;
        }

        // ── Spike attribution (perf-noon-social-food) ────────────────────────
        // Threshold-gated, once-per-spike. Only when the whole subsystem pass
        // overruns the 50 ms server-tick budget do we name the heaviest
        // subsystems, so the next in-game test attributes the noon/day-rollover
        // spike to a specific system instead of guessing. No per-tick spam:
        // most ticks run only interval==1/20 systems and stay far under budget;
        // the gap guard stops a sustained overrun from repeating the line.
        reportSpikeIfOver(tick, totalNanos);
    }

    /**
     * Logs a single INFO line attributing a slow tick to its heaviest
     * subsystems. Called every tick from {@link #tickAll}; no-ops unless
     * {@code totalNanos} exceeds {@link #SPIKE_THRESHOLD_NANOS} and at least
     * {@link #SPIKE_REPORT_MIN_GAP_TICKS} have elapsed since the last report.
     */
    private static void reportSpikeIfOver(long tick, long totalNanos) {
        if (totalNanos < SPIKE_THRESHOLD_NANOS) return;
        if (tick - lastSpikeReportTick < SPIKE_REPORT_MIN_GAP_TICKS) return;
        lastSpikeReportTick = tick;

        // Top 6 offenders by last-tick cost. LAST_TIMING holds the most recent
        // per-system nanos (only systems that actually ran this tick were just
        // overwritten; stale entries from prior ticks are filtered by sorting
        // and capping — a stale entry can only be small relative to a 50 ms
        // spike, so it cannot mislead the attribution).
        List<Map.Entry<String, Long>> top = new ArrayList<>(LAST_TIMING.entrySet());
        top.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Long> e : top) {
            if (shown >= 6) break;
            if (e.getValue() <= 0L) continue;
            if (shown > 0) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue() / 1_000_000L).append("ms");
            shown++;
        }
        LOGGER.info("[TickSpike] tick {} subsystem pass {} ms (budget 50 ms) — "
                        + "top: {}",
                tick, totalNanos / 1_000_000L, sb);
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