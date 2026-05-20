package tterrag1112.life_in_the_village.Items;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.Optional;
import java.util.UUID;

/**
 * Data-component payload for {@link StallLeaseItem}. Per Q1 the lease
 * is bound to the original purchaser — only that player can redeem it
 * to claim a stall slot. Non-transferable.
 *
 * @param ownerUuid      UUID of the player who bought the lease.
 * @param durationTicks  How long the lease runs once redeemed.
 *                       {@code Long.MAX_VALUE} = outright purchase
 *                       (see {@code MarketStallPlacer.PURCHASE_PRICE}).
 * @param marketBuildingId Optional bound market; empty = "any free
 *                       slot at point-of-use".
 */
public record StallLeaseTerms(
        UUID ownerUuid,
        long durationTicks,
        Optional<UUID> marketBuildingId
) {

    public StallLeaseTerms {
        if (ownerUuid == null) ownerUuid = new UUID(0L, 0L);
        if (marketBuildingId == null) marketBuildingId = Optional.empty();
        if (durationTicks < 0) durationTicks = 0;
    }

    public boolean isPurchase() { return durationTicks == Long.MAX_VALUE; }

    public static final Codec<StallLeaseTerms> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("ownerUuid").forGetter(StallLeaseTerms::ownerUuid),
            Codec.LONG.fieldOf("durationTicks").forGetter(StallLeaseTerms::durationTicks),
            UUIDUtil.CODEC.optionalFieldOf("marketBuildingId")
                    .forGetter(StallLeaseTerms::marketBuildingId)
    ).apply(i, StallLeaseTerms::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, StallLeaseTerms> STREAM_CODEC =
            ByteBufCodecs.fromCodecWithRegistries(CODEC);
}
