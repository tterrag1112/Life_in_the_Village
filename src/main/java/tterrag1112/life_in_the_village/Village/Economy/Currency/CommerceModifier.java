package tterrag1112.life_in_the_village.Village.Economy.Currency;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;

import javax.annotation.Nullable;

/**
 * T6 — per-NPC COMMERCE skill modifiers for buy cost and sell revenue.
 *
 * <h3>Curve</h3>
 * <pre>
 *   discount / markup = clamp(commerceLevel × RATE, 0, MAX)
 *   RATE = 0.002,  MAX = 0.20
 * </pre>
 * Examples (representative seeded levels):
 * <ul>
 *   <li>COMMERCE 5  (fresh producer secondary) → 1% discount / markup</li>
 *   <li>COMMERCE 20 (journeyman producer)      → 4%</li>
 *   <li>COMMERCE 50 (experienced merchant)     → 10%</li>
 *   <li>COMMERCE 70 (merchant promotion floor) → 14%</li>
 *   <li>COMMERCE 100 (grandmaster cap)         → 20% (hard cap)</li>
 * </ul>
 *
 * <h3>XP award</h3>
 * {@link #awardForTrade} awards {@code max(1, bronzeValue/200 + 1)} XP,
 * capped at 50 per transaction.  At a typical 10-bronze sale that is
 * 1 XP; at a 200-bronze sale it is 2 XP.  Requires ~700 small trades
 * to reach COMMERCE 5 — intentionally slow so the curve feels earned.
 *
 * <h3>Tuning knobs</h3>
 * {@link #RATE} and {@link #MAX_MODIFIER} are the only parameters.
 * Increase RATE to accelerate the curve; increase MAX_MODIFIER to raise
 * the ceiling.  XP cap lives in {@link #MAX_XP_PER_TRADE}.
 */
public final class CommerceModifier {

    // ── Tuning knobs ────────────────────────────────────────────────────────

    /** XP per bronze awarded on a trade; 0.002 → ~14% benefit at COMMERCE 70. */
    private static final double RATE          = 0.002;
    /** Hard cap on discount / markup fraction (0.20 = 20%). */
    private static final double MAX_MODIFIER  = 0.20;
    /**
     * XP divisor for trade value: {@code bronzeValue / XP_BRONZE_DIVISOR + 1}.
     * At 200 divisor a 200-bronze trade yields 2 XP.
     */
    private static final int    XP_BRONZE_DIVISOR = 200;
    /** Per-transaction XP ceiling. Keeps COMMERCE from spiking on large one-offs. */
    private static final int    MAX_XP_PER_TRADE  = 50;

    private CommerceModifier() {}

    // ── Multipliers ─────────────────────────────────────────────────────────

    /**
     * Buy-side price multiplier: higher COMMERCE → lower ceiling bid.
     * Returns a value in {@code [0.80, 1.00]}; null npc → 1.0.
     */
    public static double buyMultiplier(@Nullable TownspersonMob npc) {
        return 1.0 - modifier(npc);
    }

    /**
     * Sell-side revenue multiplier: higher COMMERCE → higher revenue.
     * Returns a value in {@code [1.00, 1.20]}; null npc → 1.0.
     */
    public static double sellMultiplier(@Nullable TownspersonMob npc) {
        return 1.0 + modifier(npc);
    }

    // ── XP ──────────────────────────────────────────────────────────────────

    /**
     * Award a small amount of COMMERCE XP proportional to the bronze value
     * of the completed trade.  Call once per successful buy or sell event.
     *
     * @param npc         the NPC who traded (null-safe; no-op if null)
     * @param bronzeValue total bronze spent or earned (0 → 1 XP minimum)
     * @param gameTime    current game tick (forwarded to {@link SkillXp})
     */
    public static void awardForTrade(@Nullable TownspersonMob npc,
                                     long bronzeValue, long gameTime) {
        if (npc == null) return;
        int xp = Math.min(MAX_XP_PER_TRADE,
                Math.max(1, (int) (bronzeValue / XP_BRONZE_DIVISOR) + 1));
        SkillXp.award(npc, Skill.COMMERCE, xp, gameTime);
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private static double modifier(@Nullable TownspersonMob npc) {
        if (npc == null) return 0.0;
        int level = npc.getSkills().getLevel(Skill.COMMERCE);
        return Math.min(MAX_MODIFIER, level * RATE);
    }
}
