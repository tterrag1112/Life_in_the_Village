package tterrag1112.life_in_the_village.World;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * Phase 6.3.3.k.1 — per-village climate snapshot. Currently tracks
 * the last game tick at which precipitation was recorded at the
 * village centre; {@link WeatherContext#isDrought} derives the
 * drought condition from this plus the current season.
 *
 * <p>Per-village rather than per-dimension because different villages
 * sit in different biomes; a desert village and a forest village in
 * the same world progress along different drought clocks (the desert
 * village's {@code lastRainTick} never advances since rain doesn't
 * fall there).</p>
 *
 * <p>The "drying counter" the spec mentions is derived rather than
 * stored — {@code daysSinceRain = (now - lastRainTick) / 24000} —
 * so there's no per-tick counter mutation to maintain.</p>
 */
public record VillageClimateState(UUID villageId, long lastRainTick) {

    public static final Codec<VillageClimateState> CODEC =
            RecordCodecBuilder.create(i -> i.group(
                    UUIDUtil.CODEC.fieldOf("villageId")
                            .forGetter(VillageClimateState::villageId),
                    Codec.LONG.optionalFieldOf("lastRainTick", 0L)
                            .forGetter(VillageClimateState::lastRainTick)
            ).apply(i, VillageClimateState::new));

    /** Convenience: fresh state with no recorded rain (drought clock
     *  starts at the world's gameTime baseline). */
    public static VillageClimateState fresh(UUID villageId) {
        return new VillageClimateState(villageId, 0L);
    }
}
