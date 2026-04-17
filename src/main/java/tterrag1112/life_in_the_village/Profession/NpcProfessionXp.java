package tterrag1112.life_in_the_village.Profession;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

/**
 * Tracks profession experience for NPC entities.
 *
 * <h3>Design</h3>
 * XP is stored as a single integer in the NPC's PersistentData under
 * {@value #NBT_KEY}. A single value is sufficient because each NPC has
 * exactly one profession. If an NPC ever changes profession the XP should
 * be reset via {@link #reset}.
 *
 * <h3>Progression</h3>
 * NPCs advance through the same five tiers as players, but with different
 * thresholds — NPCs accumulate XP slowly through production ticks, so the
 * curve is shallower. A grandmaster NPC is rare and takes many in-game
 * years to develop organically.
 *
 * <h3>Effects</h3>
 * <ul>
 *   <li><b>Speed</b> — higher-level NPCs complete production steps faster
 *       via {@link #getSpeedMultiplier}. Used by
 *       {@code AbstractWorkstationProductionGoal} when scaling step ticks.</li>
 *   <li><b>Quality chance</b> — higher-level NPCs have a small chance to
 *       produce an extra output item per batch. Used by subclass
 *       {@code onProductionComplete} overrides.</li>
 * </ul>
 */
public final class NpcProfessionXp {

    private NpcProfessionXp() {}

    // ── NBT key ───────────────────────────────────────────────────────────────
    private static final String NBT_KEY = "npcProfXp";

    // ── Tier thresholds (cumulative XP) ───────────────────────────────────────
    // NPCs earn ~1-5 XP per production cycle. Reaching Grandmaster
    // (8 000 XP) organically takes thousands of cycles — roughly 1–2
    // in-game years of continuous employment.
    private static final int[] THRESHOLDS = {0, 150, 600, 2000, 8000};

    public static final String[] TIER_NAMES =
            {"Novice", "Journeyman", "Expert", "Master", "Grandmaster"};

    // ── XP per production event ───────────────────────────────────────────────
    /** XP awarded when an NPC completes one full production cycle. */
    public static final int XP_PER_PRODUCTION_CYCLE = 3;
    /** Bonus XP when an NPC completes a crafting order commission. */
    public static final int XP_PER_COMMISSION        = 10;
    /** Small XP tick for passive activities (tending stall, patrolling). */
    public static final int XP_PER_PASSIVE_TICK      = 1;

    // =========================================================================
    // Read / write
    // =========================================================================

    public static int get(TownspersonMob npc) {
        return npc.getPersistentData().getInt(NBT_KEY).orElse(0);
    }

    /**
     * Adds {@code amount} XP to the NPC, capped at the maximum grandmaster
     * threshold so the integer never overflows.
     */
    public static void add(TownspersonMob npc, int amount) {
        int current = get(npc);
        int updated = Math.min(current + amount, THRESHOLDS[THRESHOLDS.length - 1] + 1);
        npc.getPersistentData().putInt(NBT_KEY, updated);
    }

    /** Resets XP to zero — call when an NPC changes profession. */
    public static void reset(TownspersonMob npc) {
        npc.getPersistentData().remove(NBT_KEY);
    }

    // =========================================================================
    // Level queries
    // =========================================================================

    /** Returns the 0-based tier index (0 = Novice, 4 = Grandmaster). */
    public static int getLevel(TownspersonMob npc) {
        int xp = get(npc);
        for (int i = THRESHOLDS.length - 1; i >= 0; i--) {
            if (xp >= THRESHOLDS[i]) return i;
        }
        return 0;
    }

    public static String getTierName(TownspersonMob npc) {
        return TIER_NAMES[getLevel(npc)];
    }

    public static boolean isMaxLevel(TownspersonMob npc) {
        return getLevel(npc) >= THRESHOLDS.length - 1;
    }

    /** XP needed to reach the next tier, or 0 if already at Grandmaster. */
    public static int getXpToNextLevel(TownspersonMob npc) {
        int level = getLevel(npc);
        if (level >= THRESHOLDS.length - 1) return 0;
        return THRESHOLDS[level + 1] - get(npc);
    }

    // =========================================================================
    // Effect multipliers
    // =========================================================================

    /**
     * Production speed multiplier for this NPC's current tier.
     *
     * <p>This value is used as a <em>divisor</em> on production step ticks:
     * a multiplier of 1.3 means the NPC finishes 30% faster. The goal's
     * {@code buildSteps} divides base ticks by this value.</p>
     *
     * <pre>
     * Novice      →  1.00× (baseline)
     * Journeyman  →  1.10×
     * Expert      →  1.25×
     * Master      →  1.45×
     * Grandmaster →  1.70×
     * </pre>
     */
    public static float getSpeedMultiplier(TownspersonMob npc) {
        return switch (getLevel(npc)) {
            case 1  -> 1.10f;
            case 2  -> 1.25f;
            case 3  -> 1.45f;
            case 4  -> 1.70f;
            default -> 1.00f;
        };
    }

    /**
     * Chance (0.0–1.0) that this NPC produces one bonus output item per
     * production cycle, representing higher craftsmanship at senior tiers.
     *
     * <pre>
     * Novice      →  0%
     * Journeyman  →  0%
     * Expert      →  5%
     * Master      →  15%
     * Grandmaster →  30%
     * </pre>
     */
    public static float getQualityChance(TownspersonMob npc) {
        return switch (getLevel(npc)) {
            case 2  -> 0.05f;
            case 3  -> 0.15f;
            case 4  -> 0.30f;
            default -> 0.00f;
        };
    }
}
