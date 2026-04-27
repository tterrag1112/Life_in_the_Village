package tterrag1112.life_in_the_village.Npc.Aging;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Optional;
import java.util.UUID;

/**
 * One outstanding personal goal an elderly NPC carries through their
 * final years. Spec line 75. Resolution / unresolved-at-death
 * mechanics live on {@link RetirementState} + the death hook.
 */
public record UnfinishedBusiness(
        UnfinishedBusinessType type,
        String description,
        Optional<UUID> relatedId,
        long startTick,
        boolean resolved
) {
    public UnfinishedBusiness {
        if (type == null) type = UnfinishedBusinessType.RECONCILIATION;
        if (description == null) description = "";
        if (relatedId == null) relatedId = Optional.empty();
    }

    public UnfinishedBusiness withResolved(boolean newResolved) {
        return new UnfinishedBusiness(type, description, relatedId, startTick, newResolved);
    }

    public static final Codec<UnfinishedBusiness> CODEC = RecordCodecBuilder.create(i -> i.group(
            UnfinishedBusinessType.CODEC.fieldOf("type").forGetter(UnfinishedBusiness::type),
            Codec.STRING.optionalFieldOf("description", "")
                    .forGetter(UnfinishedBusiness::description),
            UUIDUtil.CODEC.optionalFieldOf("relatedId").forGetter(UnfinishedBusiness::relatedId),
            Codec.LONG.fieldOf("startTick").forGetter(UnfinishedBusiness::startTick),
            Codec.BOOL.optionalFieldOf("resolved", false)
                    .forGetter(UnfinishedBusiness::resolved)
    ).apply(i, UnfinishedBusiness::new));
}
