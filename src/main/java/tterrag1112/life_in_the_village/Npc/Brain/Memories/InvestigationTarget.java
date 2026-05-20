package tterrag1112.life_in_the_village.Npc.Brain.Memories;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * Investigation can target a fixed location (e.g. a witnessed crime
 * scene) or a moving entity (e.g. a fleeing suspect). Encoded as an
 * Either so consumers can branch cleanly without a sentinel field.
 */
public record InvestigationTarget(Either<BlockPos, UUID> target) {

    public static final Codec<InvestigationTarget> CODEC =
            Codec.either(BlockPos.CODEC, UUIDUtil.CODEC)
                    .xmap(InvestigationTarget::new, InvestigationTarget::target);

    public static InvestigationTarget ofPos(BlockPos pos) {
        return new InvestigationTarget(Either.left(pos));
    }

    public static InvestigationTarget ofEntity(UUID id) {
        return new InvestigationTarget(Either.right(id));
    }
}
