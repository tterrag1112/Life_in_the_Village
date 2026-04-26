package tterrag1112.life_in_the_village.Npc.Health;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * Spec line 132. A single remedy instance — produced by a healer at
 * craft time, sits in their personal stash, consumed when applied.
 *
 * <p>Stored as a record (not a vanilla {@code Item}) because the
 * spec ships v1 without item NBT round-tripping; the healer keeps
 * them in {@link HealerInventory}, the player buys them via
 * a verb that simply hands a {@code Remedy} to the player's
 * stash on the saved-data side.</p>
 *
 * @param remedyId      stable identity (used in dialogue + memories)
 * @param type          which condition family it treats
 * @param potency       1..3; clamps the duration cut for a treated
 *                      condition (potency 1 = 33% cut, potency 3 =
 *                      full halving per spec line 112)
 * @param craftedTick   tick the healer produced it
 * @param expiresTick   tick the remedy decays — 30..60d after craft
 *                      depending on type (spec line 152)
 */
public record Remedy(
        UUID remedyId,
        RemedyType type,
        int potency,
        long craftedTick,
        long expiresTick
) {
    public Remedy {
        if (remedyId == null) throw new IllegalArgumentException("remedyId required");
        if (type == null) throw new IllegalArgumentException("type required");
        potency = Math.max(1, Math.min(3, potency));
    }

    public boolean isExpired(long currentTick) {
        return currentTick >= expiresTick;
    }

    public static Remedy create(RemedyType type, int potency, long currentTick) {
        long shelfLife = switch (type) {
            case PLAGUE_ANTIDOTE -> 60L * HealthDurations.DAY;
            case HERBAL_POULTICE, BONE_SALVE -> 45L * HealthDurations.DAY;
            default -> 30L * HealthDurations.DAY;
        };
        return new Remedy(UUID.randomUUID(), type, potency, currentTick,
                currentTick + shelfLife);
    }

    public static final Codec<Remedy> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("remedyId").forGetter(Remedy::remedyId),
            RemedyType.CODEC.fieldOf("type").forGetter(Remedy::type),
            Codec.INT.optionalFieldOf("potency", 1).forGetter(Remedy::potency),
            Codec.LONG.fieldOf("craftedTick").forGetter(Remedy::craftedTick),
            Codec.LONG.fieldOf("expiresTick").forGetter(Remedy::expiresTick)
    ).apply(i, Remedy::new));
}
