package tterrag1112.life_in_the_village.Npc.BusinessFront;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Transient (session-only) per-building greeter state. Spec lines 70-83.
 *
 * <p>Stored in {@link BusinessFrontTracker}'s in-memory map; never
 * persisted because all of greeter, presence, and player are
 * server-life-cycle bound.</p>
 *
 * @param buildingId        building whose state this is
 * @param greeterNpcId      currently-assigned greeter ({@code null} when
 *                          status is NONE / CLOSED)
 * @param targetPlayerId    player the greeter is attending; null until
 *                          assignment
 * @param attendingSinceTick tick at which the greeter started APPROACH;
 *                           used to age out abandoned greeters
 * @param status            see {@link BusinessFrontStatus}
 */
public record BusinessFrontState(
        UUID buildingId,
        @Nullable UUID greeterNpcId,
        @Nullable UUID targetPlayerId,
        long attendingSinceTick,
        BusinessFrontStatus status
) {
    public static BusinessFrontState none(UUID buildingId) {
        return new BusinessFrontState(buildingId, null, null, 0L, BusinessFrontStatus.NONE);
    }

    public BusinessFrontState withStatus(BusinessFrontStatus next) {
        return new BusinessFrontState(buildingId, greeterNpcId, targetPlayerId,
                attendingSinceTick, next);
    }

    public BusinessFrontState withGreeter(UUID greeterId, UUID playerId, long tick,
                                          BusinessFrontStatus next) {
        return new BusinessFrontState(buildingId, greeterId, playerId, tick, next);
    }
}
