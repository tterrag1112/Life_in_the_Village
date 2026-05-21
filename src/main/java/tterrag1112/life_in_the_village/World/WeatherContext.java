package tterrag1112.life_in_the_village.World;

import net.minecraft.server.level.ServerLevel;

/**
 * Phase 6.3.3.h.4 — thin weather-state helper for farming logic.
 *
 * <p>Composites a {@code weatherMultiplier} into the existing yield
 * formula in {@code FarmerBehavior.harvest()}:
 * {@code yieldMult = seasonMult * soilQuality * weatherMultiplier}.
 * Also exposes a {@link #shouldPauseFarming} probe so weather-aware
 * farmers skip planting during heavy rain, and an {@link #isStorm}
 * probe consumed by Phase 6.3.3.h.6 for livestock storm-retreat.
 *
 * <p>Drought / frost mechanics are deferred — the existing weather
 * surface in the mod is rain/thunder only (per 6.3.3.h.0 inspection)
 * and persistent drought-day tracking is out of scope for h.4. The
 * helper's signature is shaped so a future "DroughtTracker" can add
 * a multiplier component without touching the call sites.
 */
public final class WeatherContext {

    private WeatherContext() {}

    public static final float RAIN_BONUS    = 0.05f;
    public static final float THUNDER_PENALTY = 0.03f;

    /**
     * Returns the weather component of the yield multiplier.
     * <ul>
     *   <li>Clear: 1.0</li>
     *   <li>Raining: 1.0 + RAIN_BONUS (rain helps growth)</li>
     *   <li>Thundering: 1.0 - THUNDER_PENALTY (lightning damages crops)</li>
     * </ul>
     * Thunder is checked first so a thundering-and-raining sky reads
     * as the storm penalty, not the rain bonus.
     */
    public static float yieldMultiplier(ServerLevel level) {
        if (level == null) return 1.0f;
        if (level.isThundering()) return 1.0f - THUNDER_PENALTY;
        if (level.isRaining())    return 1.0f + RAIN_BONUS;
        return 1.0f;
    }

    /** True when the farmer should pause planting (heavy rain). */
    public static boolean shouldPauseFarming(ServerLevel level) {
        return level != null && level.isRaining();
    }

    /** True when livestock should retreat to pen (h.6 consumer). */
    public static boolean isStorm(ServerLevel level) {
        return level != null && level.isThundering();
    }
}
