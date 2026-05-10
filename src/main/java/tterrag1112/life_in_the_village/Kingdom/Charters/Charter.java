package tterrag1112.life_in_the_village.Kingdom.Charters;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Optional;
import java.util.UUID;

/**
 * Track D3.4b — a persistent grant from a kingdom to a grantee.
 * Charters survive ruler changes (no logic in
 * {@code NobilityEventDispatcher.runSuccession} touches them).
 * Revocation is the only legal removal path; revocation cost
 * scales with charter age per {@link CharterType#legitimacyHit}.
 *
 * @param id               stable UUID; primary key in
 *                         {@code Kingdom.charters}.
 * @param name             display name for the GUI / debug.
 * @param grantee          recipient of the grant.
 * @param granterKingdomId issuing kingdom.
 * @param grantedTick      server tick the charter was issued.
 * @param grantedRulerId   ruler NPC / player UUID who signed it
 *                         (informational; charter remains valid
 *                         after their reign).
 * @param type             discriminator selecting which
 *                         {@link CharterParams} variant applies.
 * @param params           type-specific parameters.
 * @param active           {@code false} after revocation.
 * @param revokedTick      tick the charter was revoked, or 0 if
 *                         still active.
 */
public record Charter(
        UUID id,
        String name,
        GranteeRef grantee,
        UUID granterKingdomId,
        long grantedTick,
        Optional<UUID> grantedRulerId,
        CharterType type,
        CharterParams params,
        boolean active,
        long revokedTick
) {

    public static final Codec<Charter> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Charter::id),
            Codec.STRING.fieldOf("name").forGetter(Charter::name),
            GranteeRef.CODEC.fieldOf("grantee").forGetter(Charter::grantee),
            UUIDUtil.CODEC.fieldOf("granterKingdomId").forGetter(Charter::granterKingdomId),
            Codec.LONG.fieldOf("grantedTick").forGetter(Charter::grantedTick),
            UUIDUtil.CODEC.optionalFieldOf("grantedRulerId").forGetter(Charter::grantedRulerId),
            Codec.STRING.xmap(CharterType::valueOf, Enum::name)
                    .fieldOf("type").forGetter(Charter::type),
            CharterParams.CODEC.fieldOf("params").forGetter(Charter::params),
            Codec.BOOL.optionalFieldOf("active", true).forGetter(Charter::active),
            Codec.LONG.optionalFieldOf("revokedTick", 0L).forGetter(Charter::revokedTick)
    ).apply(i, Charter::new));

    /** Charter's age in in-game days as of {@code currentTick}. */
    public long ageInDays(long currentTick) {
        return Math.max(0L, (currentTick - grantedTick) / 24000L);
    }

    /** Convenience constructor for a brand-new charter. */
    public static Charter freshGrant(UUID id, String name, GranteeRef grantee,
                                     UUID granterKingdomId,
                                     Optional<UUID> grantedRulerId,
                                     CharterParams params, long tick) {
        return new Charter(id, name, grantee, granterKingdomId, tick,
                grantedRulerId, params.type(), params, true, 0L);
    }

    public Charter revoke(long tick) {
        return new Charter(id, name, grantee, granterKingdomId, grantedTick,
                grantedRulerId, type, params, false, tick);
    }
}
