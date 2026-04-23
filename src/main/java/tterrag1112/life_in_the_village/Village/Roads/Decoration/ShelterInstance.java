package tterrag1112.life_in_the_village.Village.Roads.Decoration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistent record of a shelter that has been placed along a road edge.
 *
 * <p>Instances are stored in {@code WorldRoadSavedData} keyed by edge UUID so
 * that re-realization skips edges that already have shelters, and debug commands
 * can query and manipulate them without walking the full block path.
 *
 * <h3>Codec</h3>
 * All fields are persisted. {@code placedBlocks} records every block the builder
 * wrote so that {@code replace_shelter} can remove the structure cleanly before
 * re-placing it.
 */
public record ShelterInstance(
        UUID instanceId,
        ShelterPlanner.ShelterType type,
        BlockPos position,
        List<BlockPos> placedBlocks,
        long placedTick
) {
    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public static final Codec<ShelterInstance> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("instanceId")
                    .forGetter(ShelterInstance::instanceId),
            ShelterPlanner.ShelterType.CODEC.fieldOf("type")
                    .forGetter(ShelterInstance::type),
            BlockPos.CODEC.fieldOf("position")
                    .forGetter(ShelterInstance::position),
            BlockPos.CODEC.listOf()
                    .optionalFieldOf("placedBlocks", new ArrayList<>())
                    .forGetter(ShelterInstance::placedBlocks),
            Codec.LONG.optionalFieldOf("placedTick", 0L)
                    .forGetter(ShelterInstance::placedTick)
    ).apply(i, ShelterInstance::new));
}
