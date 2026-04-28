package tterrag1112.life_in_the_village.Village.Decoration.Framework;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * Doc 01 — durable record of one decoration placement made by
 * {@link DecorationMatcher} and stamped into the world by
 * {@link DecorationPass}.
 *
 * <p>Unlike {@link DecorationSlot} (ephemeral, per-pass), placements
 * persist on {@code VillageSavedData} so the village retains a
 * record of what was placed where. This supports debug visibility
 * (the {@code /liv decoration list} command in P0b-06), future
 * regeneration / re-render passes, and player-action tracking.</p>
 *
 * @param slotId      the originating slot's id (for debug correlation)
 * @param villageId   owning village
 * @param pos         the placement position in world coordinates
 * @param facing      orientation chosen at match time
 * @param pieceId     the {@link DecorationProfile#pieceId} that was
 *                    matched — also the resource path under
 *                    {@code structures/decoration/}
 * @param culture     the culture id resolved for this placement
 *                    (may be {@code "default"} via the registry's
 *                    fallback chain)
 * @param placedTick  server tick at which the placement was committed
 */
public record DecorationPlacement(
        UUID slotId,
        UUID villageId,
        BlockPos pos,
        Direction facing,
        String pieceId,
        String culture,
        long placedTick) {

    public static final Codec<DecorationPlacement> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    UUIDUtil.CODEC.fieldOf("slotId")
                            .forGetter(DecorationPlacement::slotId),
                    UUIDUtil.CODEC.fieldOf("villageId")
                            .forGetter(DecorationPlacement::villageId),
                    BlockPos.CODEC.fieldOf("pos")
                            .forGetter(DecorationPlacement::pos),
                    Direction.CODEC.fieldOf("facing")
                            .forGetter(DecorationPlacement::facing),
                    Codec.STRING.fieldOf("pieceId")
                            .forGetter(DecorationPlacement::pieceId),
                    Codec.STRING.fieldOf("culture")
                            .forGetter(DecorationPlacement::culture),
                    Codec.LONG.fieldOf("placedTick")
                            .forGetter(DecorationPlacement::placedTick)
            ).apply(instance, DecorationPlacement::new));
}
