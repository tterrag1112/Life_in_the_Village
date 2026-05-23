package tterrag1112.life_in_the_village.World;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.UUID;

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
 * <p>Phase 6.3.3.k.1/k.2 extends the helper with persistent drought
 * tracking ({@link #isDrought}, {@link #droughtYieldMultiplier}) and
 * per-position frost detection ({@link #isFrost}). Drought tracking
 * lives in {@code VillageSavedData} as a per-village
 * {@link VillageClimateState}; frost is location-based and derived
 * from the biome's freezing condition at the queried position.</p>
 */
public final class WeatherContext {

    private WeatherContext() {}

    public static final float RAIN_BONUS    = 0.05f;
    public static final float THUNDER_PENALTY = 0.03f;

    /** Phase 6.3.3.k.2 — drought penalty on the yield formula. */
    public static final float DROUGHT_YIELD_PENALTY = 0.30f; // → ×0.7

    /** Days without precipitation that constitute drought during a
     *  cropping season. Spec line k.2.B: 5 in-game days. */
    public static final int DROUGHT_DRY_DAY_THRESHOLD = 5;
    public static final long TICKS_PER_DAY = 24000L;

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

    // ── Phase 6.3.3.k.2 — drought ────────────────────────────────────────────

    /**
     * True when a village has gone {@link #DROUGHT_DRY_DAY_THRESHOLD}
     * or more in-game days without precipitation at its centre AND
     * the current season permits cropping. Winter dryness is normal
     * dormancy and never reads as drought.
     *
     * <p>Tolerates {@code null} level / {@code null} villageId so
     * call sites in early-load paths can probe safely.</p>
     */
    public static boolean isDrought(ServerLevel level, UUID villageId) {
        if (level == null || villageId == null) return false;
        if (!SeasonTracker.currentSeason(level).isCropSeason()) return false;
        VillageSavedData data = VillageSavedData.get(level);
        VillageClimateState state = data.getClimateState(villageId).orElse(null);
        if (state == null) return false;
        long ticksSinceRain = level.getGameTime() - state.lastRainTick();
        return ticksSinceRain
                >= ((long) DROUGHT_DRY_DAY_THRESHOLD) * TICKS_PER_DAY;
    }

    /** Drought yield multiplier — ×0.7 when in drought, else 1.0.
     *  Composes multiplicatively with {@link #yieldMultiplier}. */
    public static float droughtYieldMultiplier(ServerLevel level, UUID villageId) {
        return isDrought(level, villageId) ? (1.0f - DROUGHT_YIELD_PENALTY) : 1.0f;
    }

    // ── Phase 6.3.3.k.3 — frost ──────────────────────────────────────────────

    /**
     * True when the biome at {@code pos} can freeze right now. Uses
     * vanilla {@link ServerLevel#isRaining()} + biome cold-precipitation
     * semantics: a "raining" sky in a snowy biome is snow, and snow at
     * the surface implies frost. Cold biomes that aren't actively
     * precipitating still register as frost during the winter season
     * (biome temperature ≤ FROST_BIOME_TEMP) — frozen ground damages
     * frost-sensitive crops regardless of immediate weather.
     */
    public static boolean isFrost(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return false;
        float temp = level.getBiome(pos).value().getBaseTemperature();
        if (temp <= FROST_BIOME_TEMP) return true;
        // Mild biomes only frost during active winter storms.
        if (temp <= COOL_BIOME_TEMP
                && SeasonTracker.currentSeason(level) == SeasonTracker.Season.WINTER
                && level.isRaining()) {
            return true;
        }
        return false;
    }

    /** Biome temperature at-or-below which frost is the default state
     *  (matches vanilla "cold" biome category cutoffs). */
    public static final float FROST_BIOME_TEMP = 0.15f;
    /** Biome temperature at-or-below which winter precipitation
     *  produces frost rather than cold rain. */
    public static final float COOL_BIOME_TEMP  = 0.35f;

    /**
     * Phase 6.3.3.k.3 — yield multiplier under frost, keyed by the
     * crop's cold tolerance. Returns 1.0 when frost isn't active at
     * {@code pos}, regardless of tolerance.
     *
     * <ul>
     *   <li>HARDY: 1.0 (no penalty)</li>
     *   <li>COOL_SEASON: 0.9 (small penalty — root crops survive light frost)</li>
     *   <li>WARM_SEASON: 0.7 (full penalty — warm-season plantings fail)</li>
     *   <li>TREE_FRUIT: 0.8 (trees survive but yield drops)</li>
     * </ul>
     */
    public static float frostYieldMultiplier(ServerLevel level, BlockPos pos,
            tterrag1112.life_in_the_village.Village.Buildings
                    .FarmPlot.CropType.ColdTolerance tolerance) {
        if (tolerance == null) return 1.0f;
        if (!isFrost(level, pos)) return 1.0f;
        return switch (tolerance) {
            case HARDY       -> 1.0f;
            case COOL_SEASON -> 0.9f;
            case WARM_SEASON -> 0.7f;
            case TREE_FRUIT  -> 0.8f;
        };
    }
}
