// src/main/java/tterrag1112/life_in_the_village/Kingdom/Placement/PlacementFailureRecorder.java
package tterrag1112.life_in_the_village.Kingdom.Placement;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collects structured reasons why a planned village failed to realise.
 *
 * <h3>Why thread-local</h3>
 * Planning flows through many stages — terrain analysis, ridge detection,
 * shape rule evaluation, building placement — each of which can reject
 * the site. Threading a reason-collector parameter through every call
 * path would be invasive. A thread-local recorder lets any stage call
 * {@link #record} without the caller having to know about rejection
 * reasons, while the outermost spawner consults the accumulated list
 * when it decides the plan failed.
 *
 * <h3>Also tracks aggregates</h3>
 * In addition to per-attempt detail (which gets logged), a global
 * counter tallies reasons across all realisation attempts since server
 * start. Dump it with {@code /liv debug placement-failures}.
 */
public final class PlacementFailureRecorder {

    public enum Reason {
        /** Terrain profile's suitability score below the planner's floor. */
        TERRAIN_UNSUITABLE,
        /** Not enough flat candidates for the village's ring placement. */
        NO_FLAT_AREA,
        /** Ridge detected cutting through the site footprint. */
        RIDGE_INTERSECTS_FOOTPRINT,
        /** Water body/river intrudes into the intended building area. */
        WATER_IN_FOOTPRINT,
        /** Footprint elevation range exceeds what pad leveling can handle. */
        FOOTPRINT_TOO_STEEP,
        /** Town square anchor couldn't be placed — planning aborted early. */
        NO_TOWN_SQUARE_ANCHOR,
        /** Shape recipe rejected the site (e.g. RIVERINE without a river). */
        SHAPE_RULE_REJECTED,
        /** Too few buildings placed to form a village. */
        INSUFFICIENT_BUILDINGS,
        /** Something threw — unexpected runtime failure. */
        PLANNER_EXCEPTION,
        /** Fallback when none of the above matched. */
        UNKNOWN
    }

    public record Failure(
            Reason reason,
            String detail,
            BlockPos origin,
            String villageType,
            String biomeCategory,
            int slope,
            int centerY
    ) {}

    // ── Per-attempt buffer ────────────────────────────────────────────────────

    private static final ThreadLocal<List<Failure>> CURRENT = ThreadLocal.withInitial(ArrayList::new);

    // ── Global aggregate ──────────────────────────────────────────────────────

    private static final Map<Reason, AtomicInteger> GLOBAL_TALLY = new EnumMap<>(Reason.class);
    private static final AtomicInteger TOTAL_ATTEMPTS = new AtomicInteger();
    private static final AtomicInteger TOTAL_FAILURES = new AtomicInteger();

    private PlacementFailureRecorder() {}

    // =========================================================================
    // Per-attempt API — called from planning stages
    // =========================================================================

    /** Starts a fresh attempt. Call before invoking the planner. */
    public static void beginAttempt() {
        CURRENT.get().clear();
        TOTAL_ATTEMPTS.incrementAndGet();
    }

    /** Records a rejection reason. Safe to call multiple times per attempt. */
    public static void record(Reason reason, String detail,
                              BlockPos origin, String villageType, AtlasCell cell) {
        CURRENT.get().add(new Failure(
                reason, detail, origin, villageType,
                cell == null ? "unknown" : cell.category().name(),
                cell == null ? -1 : cell.slope(),
                cell == null ? -1 : cell.centerY()));
    }

    /** Convenience when no atlas cell is handy. */
    public static void record(Reason reason, String detail,
                              BlockPos origin, String villageType) {
        record(reason, detail, origin, villageType, null);
    }

    /** The reasons accumulated during the current attempt. */
    public static List<Failure> currentFailures() {
        return Collections.unmodifiableList(CURRENT.get());
    }

    /**
     * Finalises the attempt. If the plan failed, logs the reasons and
     * increments global counters. Always clears the thread-local buffer.
     *
     * @param succeeded did the plan ultimately realise?
     */
    public static void endAttempt(boolean succeeded, String villageType, BlockPos origin) {
        List<Failure> failures = CURRENT.get();
        if (!succeeded) {
            TOTAL_FAILURES.incrementAndGet();
            if (failures.isEmpty()) {
                tally(Reason.UNKNOWN);
                System.out.println("[PlacementFailure] " + villageType + " @ "
                        + (origin == null ? "?" : origin.toShortString())
                        + " — no reason recorded (UNKNOWN)");
            } else {
                for (Failure f : failures) {
                    tally(f.reason());
                    System.out.println("[PlacementFailure] " + villageType + " @ "
                            + (origin == null ? "?" : origin.toShortString())
                            + " — " + f.reason() + ": " + f.detail()
                            + " (biome=" + f.biomeCategory()
                            + ", slope=" + f.slope() + ", y=" + f.centerY() + ")");
                }
            }
        }
        CURRENT.remove();
    }

    private static void tally(Reason reason) {
        GLOBAL_TALLY.computeIfAbsent(reason, k -> new AtomicInteger()).incrementAndGet();
    }

    // =========================================================================
    // Aggregate query — used by debug command
    // =========================================================================

    public record AggregateSnapshot(
            int totalAttempts,
            int totalFailures,
            Map<Reason, Integer> perReason
    ) {
        public double failureRate() {
            return totalAttempts == 0 ? 0.0 : (double) totalFailures / totalAttempts;
        }
    }

    public static AggregateSnapshot snapshot() {
        Map<Reason, Integer> copy = new EnumMap<>(Reason.class);
        for (var entry : GLOBAL_TALLY.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }
        return new AggregateSnapshot(TOTAL_ATTEMPTS.get(), TOTAL_FAILURES.get(), copy);
    }

    public static void resetAggregate() {
        GLOBAL_TALLY.clear();
        TOTAL_ATTEMPTS.set(0);
        TOTAL_FAILURES.set(0);
    }
}