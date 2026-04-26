package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One scheduled or completed rite. Spec line 99.
 *
 * @param riteId            primary key
 * @param type              which {@link Rite}
 * @param presidingPriestId optional — empty when scheduled before
 *                          a priest is seated; the daily tick
 *                          assigns one when the {@link Rite}'s
 *                          village_priest is filled
 * @param participantIds    rite-specific list — for MARRIAGE the
 *                          two spouses; for COMING_OF_AGE the
 *                          subject; for FUNERAL the deceased +
 *                          household; HARVEST_THANKSGIVING is
 *                          village-wide so the list is empty and
 *                          attendance is computed at execute time
 * @param location          ritual venue (typically the temple's
 *                          origin); falls back to village centre
 * @param scheduledTick     when the rite is to be performed
 * @param completedTick     0 until performed
 * @param outcome           {@link RiteOutcome#PENDING} until
 *                          performed
 * @param villageId         which village owns this rite
 */
public record RiteExecution(
        UUID riteId,
        Rite type,
        Optional<UUID> presidingPriestId,
        List<UUID> participantIds,
        BlockPos location,
        long scheduledTick,
        long completedTick,
        RiteOutcome outcome,
        UUID villageId
) {
    public RiteExecution {
        if (riteId == null) riteId = UUID.randomUUID();
        if (type == null) throw new IllegalArgumentException("type required");
        if (presidingPriestId == null) presidingPriestId = Optional.empty();
        participantIds = participantIds == null ? List.of() : List.copyOf(participantIds);
        if (location == null) location = BlockPos.ZERO;
        if (outcome == null) outcome = RiteOutcome.PENDING;
        if (villageId == null) throw new IllegalArgumentException("villageId required");
    }

    public RiteExecution withPresider(UUID priestId) {
        return new RiteExecution(riteId, type, Optional.ofNullable(priestId),
                participantIds, location, scheduledTick, completedTick,
                outcome, villageId);
    }

    public RiteExecution withOutcome(RiteOutcome o, long completed) {
        return new RiteExecution(riteId, type, presidingPriestId,
                participantIds, location, scheduledTick, completed,
                o, villageId);
    }

    public static final Codec<RiteExecution> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("riteId").forGetter(RiteExecution::riteId),
            Rite.CODEC.fieldOf("type").forGetter(RiteExecution::type),
            UUIDUtil.CODEC.optionalFieldOf("presidingPriestId").forGetter(RiteExecution::presidingPriestId),
            UUIDUtil.CODEC.listOf().optionalFieldOf("participantIds", List.of())
                    .forGetter(RiteExecution::participantIds),
            BlockPos.CODEC.optionalFieldOf("location", BlockPos.ZERO).forGetter(RiteExecution::location),
            Codec.LONG.fieldOf("scheduledTick").forGetter(RiteExecution::scheduledTick),
            Codec.LONG.optionalFieldOf("completedTick", 0L).forGetter(RiteExecution::completedTick),
            RiteOutcome.CODEC.optionalFieldOf("outcome", RiteOutcome.PENDING).forGetter(RiteExecution::outcome),
            UUIDUtil.CODEC.fieldOf("villageId").forGetter(RiteExecution::villageId)
    ).apply(i, RiteExecution::new));
}
