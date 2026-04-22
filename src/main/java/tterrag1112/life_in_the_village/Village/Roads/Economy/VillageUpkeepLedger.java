package tterrag1112.life_in_the_village.Village.Roads.Economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent upkeep-payment history for a single village.
 *
 * <p>One ledger per village, keyed by village UUID in
 * {@link tterrag1112.life_in_the_village.Networking.WorldRoadSavedData}.
 *
 * <h3>Failure streaks</h3>
 * {@link #edgeFailureStreaks} records consecutive cycles in which this village
 * failed to pay its share of each edge. A streak of 10+ triggers a
 * {@code [RoadUpkeep]} log event noting chronic neglect.
 *
 * <h3>Yearly totals</h3>
 * {@link #totalCyclesPaidThisYear} and {@link #totalCyclesFailedThisYear} reset
 * every game-year and surface in the {@code village_upkeep} debug command.
 */
public class VillageUpkeepLedger {

    // ── Codec ────────────────────────────────────────────────────────────────

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public static final Codec<VillageUpkeepLedger> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(UUID_CODEC, Codec.INT)
                    .optionalFieldOf("edgeFailureStreaks", new HashMap<>())
                    .forGetter(l -> new HashMap<>(l.edgeFailureStreaks)),
            Codec.LONG.optionalFieldOf("lastUpkeepCycleTick", 0L)
                    .forGetter(l -> l.lastUpkeepCycleTick),
            Codec.INT.optionalFieldOf("totalCyclesPaidThisYear", 0)
                    .forGetter(l -> l.totalCyclesPaidThisYear),
            Codec.INT.optionalFieldOf("totalCyclesFailedThisYear", 0)
                    .forGetter(l -> l.totalCyclesFailedThisYear)
    ).apply(i, VillageUpkeepLedger::fromCodec));

    private static VillageUpkeepLedger fromCodec(Map<UUID, Integer> streaks,
                                                   long lastCycleTick,
                                                   int paid, int failed) {
        VillageUpkeepLedger l = new VillageUpkeepLedger();
        l.edgeFailureStreaks.putAll(streaks);
        l.lastUpkeepCycleTick = lastCycleTick;
        l.totalCyclesPaidThisYear = paid;
        l.totalCyclesFailedThisYear = failed;
        return l;
    }

    // ── Fields ───────────────────────────────────────────────────────────────

    final Map<UUID, Integer> edgeFailureStreaks = new HashMap<>();
    long lastUpkeepCycleTick = 0L;
    int totalCyclesPaidThisYear = 0;
    int totalCyclesFailedThisYear = 0;

    // ── Streak management ─────────────────────────────────────────────────────

    public int getFailureStreak(UUID edgeId) {
        return edgeFailureStreaks.getOrDefault(edgeId, 0);
    }

    public void incrementFailureStreak(UUID edgeId) {
        edgeFailureStreaks.merge(edgeId, 1, Integer::sum);
    }

    public void resetFailureStreak(UUID edgeId) {
        edgeFailureStreaks.remove(edgeId);
    }

    // ── Yearly accounting ─────────────────────────────────────────────────────

    public void recordPaid() {
        totalCyclesPaidThisYear++;
    }

    public void recordFailed() {
        totalCyclesFailedThisYear++;
    }

    public void resetYearlyTotals() {
        totalCyclesPaidThisYear = 0;
        totalCyclesFailedThisYear = 0;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public Map<UUID, Integer> getEdgeFailureStreaks() { return edgeFailureStreaks; }
    public long getLastUpkeepCycleTick()              { return lastUpkeepCycleTick; }
    public int getTotalCyclesPaidThisYear()           { return totalCyclesPaidThisYear; }
    public int getTotalCyclesFailedThisYear()         { return totalCyclesFailedThisYear; }

    void setLastCycleTick(long tick) { this.lastUpkeepCycleTick = tick; }
}
