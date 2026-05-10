package tterrag1112.life_in_the_village.Kingdom.Intrigue;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Optional;
import java.util.UUID;

/**
 * Track D3.4b — record of a single sow-discontent (or future
 * intrigue) attempt. Stored on the source kingdom's bounded
 * rolling buffer for cooldown lookup + history.
 *
 * <p>Determinism seed: {@code (sourceKingdomId, "intrigue",
 * targetKingdomId, gameDay)}. Same world seed produces same
 * outcomes across reloads.
 *
 * @param id              stable UUID for the attempt.
 * @param sourceKingdomId kingdom whose Spymaster initiated.
 * @param targetKingdomId target kingdom.
 * @param targetProvinceId province within the target whose
 *                         stability is impacted; empty for non-
 *                         province-targeted attempts.
 * @param spymasterUuid   the Spymaster NPC; empty if cast by
 *                         player intervention or auto-tick.
 * @param tick            server tick the attempt fired.
 * @param success         deterministic outcome from the seeded roll.
 * @param discovered      true if the target's counter-intelligence
 *                         caught the attempt.
 * @param stabilityHit    stability damage applied to the target
 *                         province (0 if unsuccessful or
 *                         province missing).
 */
public record IntrigueAttempt(
        UUID id,
        UUID sourceKingdomId,
        UUID targetKingdomId,
        Optional<UUID> targetProvinceId,
        Optional<UUID> spymasterUuid,
        long tick,
        boolean success,
        boolean discovered,
        int stabilityHit
) {

    public static final Codec<IntrigueAttempt> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(IntrigueAttempt::id),
            UUIDUtil.CODEC.fieldOf("sourceKingdomId").forGetter(IntrigueAttempt::sourceKingdomId),
            UUIDUtil.CODEC.fieldOf("targetKingdomId").forGetter(IntrigueAttempt::targetKingdomId),
            UUIDUtil.CODEC.optionalFieldOf("targetProvinceId").forGetter(IntrigueAttempt::targetProvinceId),
            UUIDUtil.CODEC.optionalFieldOf("spymasterUuid").forGetter(IntrigueAttempt::spymasterUuid),
            Codec.LONG.fieldOf("tick").forGetter(IntrigueAttempt::tick),
            Codec.BOOL.optionalFieldOf("success", false).forGetter(IntrigueAttempt::success),
            Codec.BOOL.optionalFieldOf("discovered", false).forGetter(IntrigueAttempt::discovered),
            Codec.INT.optionalFieldOf("stabilityHit", 0).forGetter(IntrigueAttempt::stabilityHit)
    ).apply(i, IntrigueAttempt::new));

    /** Per-kingdom rolling buffer cap (~12 in-game weeks). */
    public static final int BUFFER_CAPACITY = 28;
}
