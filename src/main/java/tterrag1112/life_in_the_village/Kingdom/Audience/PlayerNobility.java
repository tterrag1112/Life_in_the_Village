package tterrag1112.life_in_the_village.Kingdom.Audience;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Optional;
import java.util.UUID;

/**
 * Track D3.5C — kingdom-side authoritative record of a player's
 * nobility status with one specific kingdom.
 *
 * <p>Per the user-confirmed lock "standing kingdom-side
 * authoritative", the same lock applies to ennoblement: the
 * player's nobility lives on the granting kingdom's record. A
 * player ennobled by kingdom A is NOT automatically noble in
 * kingdom B.
 *
 * <p>Created when an {@link AudienceDriver}-approved petition
 * (or debug command) grants a {@code TITLE_GRANT} charter to a
 * grantee with kind {@code PLAYER}. Subsequent
 * {@code LAND_GRANT} charters extend the same record with
 * manor coordinates.
 *
 * @param playerUuid       the player.
 * @param rankIndex        nobility rank index in the kingdom's
 *                         culture rank table (0 = lowest noble;
 *                         see {@code NobilityRanks.maxRankIndex}).
 * @param ennobledTick     server tick the title was granted.
 * @param dynastyHouseId   optional house affiliation. Empty for
 *                         player-founded "House of <Player>"
 *                         which we don't auto-create in v1; can
 *                         be set later via debug or charter.
 * @param landGrantBlockX  optional X coord of the LAND_GRANT
 *                         manor origin; empty if no land grant.
 * @param landGrantBlockZ  optional Z coord; mirrors blockX.
 * @param landGrantSize    optional manor footprint in cells.
 * @param titleCharterId   the originating TITLE_GRANT charter
 *                         UUID; revocation of the charter
 *                         strips the rank.
 */
public record PlayerNobility(
        UUID playerUuid,
        int rankIndex,
        long ennobledTick,
        Optional<UUID> dynastyHouseId,
        Optional<Integer> landGrantBlockX,
        Optional<Integer> landGrantBlockZ,
        Optional<Integer> landGrantSize,
        Optional<UUID> titleCharterId
) {

    public static final Codec<PlayerNobility> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("playerUuid").forGetter(PlayerNobility::playerUuid),
            Codec.INT.optionalFieldOf("rankIndex", 0).forGetter(PlayerNobility::rankIndex),
            Codec.LONG.optionalFieldOf("ennobledTick", 0L).forGetter(PlayerNobility::ennobledTick),
            UUIDUtil.CODEC.optionalFieldOf("dynastyHouseId").forGetter(PlayerNobility::dynastyHouseId),
            Codec.INT.optionalFieldOf("landGrantBlockX").forGetter(PlayerNobility::landGrantBlockX),
            Codec.INT.optionalFieldOf("landGrantBlockZ").forGetter(PlayerNobility::landGrantBlockZ),
            Codec.INT.optionalFieldOf("landGrantSize").forGetter(PlayerNobility::landGrantSize),
            UUIDUtil.CODEC.optionalFieldOf("titleCharterId").forGetter(PlayerNobility::titleCharterId)
    ).apply(i, PlayerNobility::new));

    public boolean hasLandGrant() {
        return landGrantBlockX.isPresent() && landGrantBlockZ.isPresent()
                && landGrantSize.isPresent();
    }

    public PlayerNobility withRank(int newRank, long tick, UUID charterId) {
        return new PlayerNobility(playerUuid, newRank, tick, dynastyHouseId,
                landGrantBlockX, landGrantBlockZ, landGrantSize,
                Optional.of(charterId));
    }

    public PlayerNobility withLandGrant(int blockX, int blockZ, int sizeCells) {
        return new PlayerNobility(playerUuid, rankIndex, ennobledTick, dynastyHouseId,
                Optional.of(blockX), Optional.of(blockZ), Optional.of(sizeCells),
                titleCharterId);
    }

    public PlayerNobility withoutLandGrant() {
        return new PlayerNobility(playerUuid, rankIndex, ennobledTick, dynastyHouseId,
                Optional.empty(), Optional.empty(), Optional.empty(),
                titleCharterId);
    }

    /** Strips the rank back to commoner (rankIndex = -1). */
    public PlayerNobility asStripped() {
        return new PlayerNobility(playerUuid, -1, ennobledTick, Optional.empty(),
                landGrantBlockX, landGrantBlockZ, landGrantSize,
                Optional.empty());
    }

    public static PlayerNobility freshEnnoblement(UUID playerUuid, int rankIndex,
                                                  long tick, UUID titleCharterId) {
        return new PlayerNobility(playerUuid, rankIndex, tick,
                Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(titleCharterId));
    }
}
