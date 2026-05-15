package tterrag1112.life_in_the_village.Kingdom.Provinces;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Nobility.FealtyChain;

/**
 * Track D3.3 — daily entry point for per-province bookkeeping.
 *
 * <p>Runs after the weekly recompute (191) and before office
 * elections (195) so freshly-formed provinces participate in the
 * same daily cycle. For each province:
 * <ul>
 *   <li>Reads the day's tax outcome from {@link FealtyChain}'s
 *       per-province ledger (ledger entries are appended by
 *       {@code KingdomTaxEvent.collectTaxes} when the daily tax
 *       tick fires; this tick reads + clears them).</li>
 *   <li>Computes a stability delta for the day:
 *       <ul>
 *         <li>{@code -2} if the province is ungoverned</li>
 *         <li>{@code -1} per unit of withhold above 0</li>
 *         <li>{@code +1} when a competent governor remitted</li>
 *       </ul>
 *       The base scalar drifts toward {@link Province#DEFAULT_STABILITY}
 *       at {@code 1} per day (gentle pull).</li>
 *   <li>Generates a {@link ProvincialReport} and appends to the
 *       rolling buffer (capacity {@link ProvincialReport#BUFFER_CAPACITY}).</li>
 * </ul>
 *
 * <p>Pure-ish: mutates kingdom province records via
 * {@link Kingdom#replaceProvince}; otherwise self-contained.
 */
public final class ProvincialDailyDriver {

    /** Stability drift speed (per day) toward DEFAULT_STABILITY. */
    private static final int DRIFT_PER_DAY = 1;

    /** Penalty applied when a province has no governor. */
    private static final int UNGOVERNED_PENALTY = -2;

    /** Bonus applied when a governor successfully remitted tax. */
    private static final int GOVERNOR_REMITTED_BONUS = +1;

    private ProvincialDailyDriver() {}

    public static void dailyTick(ServerLevel level, VillageSavedData data, long tick) {
        if (level == null || data == null) return;
        boolean dirty = false;
        for (Kingdom kingdom : data.getAllKingdoms()) {
            for (Province province : kingdom.getProvinces()) {
                Province updated = stepProvince(province, tick);
                kingdom.replaceProvince(updated);
                dirty = true;
            }
        }
        if (dirty) data.setDirty();
    }

    /**
     * Applies the day's stability delta + appends a report. Reads
     * the latest tax-tick ledger entry from {@link FealtyChain}'s
     * per-province ledger; clears the entry afterward so the next
     * day's tick reads a fresh number.
     */
    private static Province stepProvince(Province province, long tick) {
        FealtyChain.ProvinceLedgerEntry entry =
                FealtyChain.consumeProvinceLedger(province.id());

        long collected = entry == null ? 0L : entry.taxCollected();
        long remitted  = entry == null ? 0L : entry.taxRemitted();
        long withhold  = entry == null ? 0L : entry.withhold();

        // Stability deltas.
        int delta = 0;
        if (!province.isGoverned()) {
            delta += UNGOVERNED_PENALTY;
        } else if (collected > 0L && withhold == 0L) {
            delta += GOVERNOR_REMITTED_BONUS;
        }
        if (withhold > 0L) {
            delta -= 1;
        }
        // Drift toward default.
        int target = Province.DEFAULT_STABILITY;
        int cur = province.stability();
        if (cur < target) delta += DRIFT_PER_DAY;
        else if (cur > target) delta -= DRIFT_PER_DAY;

        int newStability = clamp(cur + delta, 0, 100);

        // Compose summary line.
        String summary = composeSummary(province, collected, remitted, withhold, delta);

        ProvincialReport report = new ProvincialReport(
                tick, collected, remitted, withhold, delta, summary);

        return province.withStability(newStability).appendReport(report);
    }

    private static String composeSummary(Province p, long collected, long remitted,
                                         long withhold, int stabilityDelta) {
        StringBuilder sb = new StringBuilder();
        if (!p.isGoverned()) {
            sb.append("Ungoverned. ");
        } else if (collected > 0L) {
            sb.append("Governor remitted ").append(remitted).append("b");
            if (withhold > 0L) sb.append(" (withheld ").append(withhold).append("b)");
            sb.append(". ");
        } else {
            sb.append("No tax cycle. ");
        }
        if (stabilityDelta > 0) sb.append("Stability +").append(stabilityDelta);
        else if (stabilityDelta < 0) sb.append("Stability ").append(stabilityDelta);
        else sb.append("Stability stable");
        sb.append(".");
        return sb.toString();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
