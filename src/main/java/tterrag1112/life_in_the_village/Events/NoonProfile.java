package tterrag1112.life_in_the_village.Events;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/**
 * Coarse whole-tick attribution for the meal/noon server-tick spike
 * (noon-meal-perf, 2026-06-12).
 *
 * <p>The daily {@code TickSubsystem} pass is instrumented separately
 * ({@code [TickSpike]} in {@link TickSubsystemRegistry}) and stays silent
 * during the meal-window spike — so the cost is in entity/brain ticking, not
 * the daily pass. This profiler buckets the three suspected hot paths so the
 * next test can name the remaining cost instead of guessing:
 *
 * <ul>
 *   <li><b>brains</b> — total time in the mod's per-NPC brain step
 *       ({@code TownspersonMob.customServerAiStep}'s {@code brain.tick}),
 *       summed across every NPC ticked this server tick.</li>
 *   <li><b>quotes</b> — total time in the channel-router quote pipeline
 *       ({@code ChannelRouter.findBestChannel} / {@code rankAllChannels}).</li>
 *   <li><b>marketScan</b> — total time discovering market stall containers
 *       ({@code MarketInventory.stallChests}, including the footprint scan
 *       on a cache miss).</li>
 * </ul>
 *
 * <h3>Cost model — accumulate always, report rarely</h3>
 * The only steady-state overhead is the {@code nanoTime} calls bracketing the
 * three paths plus a handful of {@code long} adds; there is no allocation, no
 * map, no per-tick logging. Each server tick {@link #maybeReport} sums the
 * buckets, and <i>only</i> when the total exceeds {@link #REPORT_THRESHOLD_NANOS}
 * (~40 ms) AND at least {@link #MIN_REPORT_GAP_TICKS} have elapsed since the
 * last line does it emit ONE INFO line. Either way the per-tick accumulators
 * reset, so a printed line always describes a single server tick's work.
 *
 * <p>Threading: all accumulation happens on the server thread (NPC ticking,
 * the dispatcher) so plain non-volatile {@code long}s suffice. {@code reset()}
 * runs once per tick from the dispatcher.
 */
public final class NoonProfile {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Report only when the three buckets together exceed this (≈40 ms). */
    private static final long REPORT_THRESHOLD_NANOS = 40_000_000L;
    /** At most one report per ~second (20 ticks) so a sustained spike does
     *  not print every tick. */
    private static final int MIN_REPORT_GAP_TICKS = 20;

    private static long brainNanos = 0L;
    private static long quoteNanos = 0L;
    private static long marketScanNanos = 0L;
    private static int npcsTicked = 0;
    private static int quotesRun = 0;

    private static long lastReportTick = Long.MIN_VALUE;

    private NoonProfile() {}

    // ── Accumulators (server thread) ─────────────────────────────────────────

    /** Adds {@code nanos} of brain-step time and counts one ticked NPC. */
    public static void addBrain(long nanos) { brainNanos += nanos; npcsTicked++; }

    /** Adds {@code nanos} of channel-router quote time and counts one quote. */
    public static void addQuote(long nanos) { quoteNanos += nanos; quotesRun++; }

    /** Adds {@code nanos} of market stall-container discovery time. */
    public static void addMarketScan(long nanos) { marketScanNanos += nanos; }

    // ── Per-tick report + reset (dispatcher) ─────────────────────────────────

    /**
     * Called once per server tick from {@link ServerTickDispatcher}. Emits the
     * {@code [NoonProfile]} line when this tick's bucketed work crossed the
     * threshold (rate-limited), then resets the per-tick accumulators.
     */
    public static void maybeReport(ServerLevel level) {
        long total = brainNanos + quoteNanos + marketScanNanos;
        if (total >= REPORT_THRESHOLD_NANOS) {
            long tick = level.getGameTime();
            if (tick - lastReportTick >= MIN_REPORT_GAP_TICKS) {
                lastReportTick = tick;
                LOGGER.info("[NoonProfile] brains={}ms quotes={}ms marketScan={}ms "
                        + "npcs={} quotesRun={}",
                        brainNanos / 1_000_000L, quoteNanos / 1_000_000L,
                        marketScanNanos / 1_000_000L, npcsTicked, quotesRun);
            }
        }
        brainNanos = 0L;
        quoteNanos = 0L;
        marketScanNanos = 0L;
        npcsTicked = 0;
        quotesRun = 0;
    }
}
