package tterrag1112.life_in_the_village.Entities;

import java.util.UUID;

/**
 * Deterministic per-NPC stagger for daily-recurring triggers (sell windows,
 * meal windows, etc.). The offset is a fixed function of the NPC's UUID
 * and spreads triggers across {@link #SPREAD_TICKS} ticks of the day cycle,
 * so populations of NPCs don't all fire at the same daytime tick.
 *
 * <p>{@link #SPREAD_TICKS} is exposed for tuning. 2000 ticks ≈ 100 seconds
 * ≈ about 2 in-game hours, which is wide enough to defuse mob behaviour
 * without making any individual NPC's schedule feel off.</p>
 */
public final class NpcDailyOffset {

    private NpcDailyOffset() {}

    /** Spread window in ticks. ~2 in-game hours. */
    public static final int SPREAD_TICKS = 2000;

    /** Deterministic offset in {@code [0, SPREAD_TICKS)} for the given UUID. */
    public static int offset(UUID id) {
        if (id == null) return 0;
        return (int) Math.floorMod((long) id.hashCode(), (long) SPREAD_TICKS);
    }
}
