package tterrag1112.life_in_the_village.Village.Decoration.Subbuilding;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.List;
import java.util.UUID;

/**
 * Doc 03 §"Data structures" — a logical region inside a parent
 * building, detected at building-placement time by
 * {@link SubBuildingScanner}.
 *
 * <p>Each subbuilding owns a disjoint axis-aligned bounding box
 * inside its parent, plus a single registered door target for NPC
 * pathing. Inhabitants (households, scholars, guests, ...) are
 * attached per-subbuilding rather than per-building so multiple
 * households can share one shop-house, multiple scholars can share
 * one guild hall, etc.</p>
 */
public record SubBuilding(
        UUID subBuildingId,
        UUID parentBuildingId,
        SubBuildingType type,
        /** The world position of the anchor block. */
        BlockPos origin,
        BlockPos min,
        BlockPos max,
        /** Position the NPC paths to to enter the subbuilding. */
        BlockPos doorTarget,
        /** Outward normal of the registered door (away from the room). */
        Direction doorFacing,
        List<UUID> inhabitants,
        long createdTick
) {

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    private static final Codec<Direction> DIRECTION_CODEC =
            Codec.STRING.xmap(Direction::valueOf, Direction::name);

    public static final Codec<SubBuilding> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("subBuildingId")
                    .forGetter(SubBuilding::subBuildingId),
            UUID_CODEC.fieldOf("parentBuildingId")
                    .forGetter(SubBuilding::parentBuildingId),
            SubBuildingType.CODEC.fieldOf("type")
                    .forGetter(SubBuilding::type),
            BlockPos.CODEC.fieldOf("origin")
                    .forGetter(SubBuilding::origin),
            BlockPos.CODEC.fieldOf("min")
                    .forGetter(SubBuilding::min),
            BlockPos.CODEC.fieldOf("max")
                    .forGetter(SubBuilding::max),
            BlockPos.CODEC.fieldOf("doorTarget")
                    .forGetter(SubBuilding::doorTarget),
            DIRECTION_CODEC.fieldOf("doorFacing")
                    .forGetter(SubBuilding::doorFacing),
            UUID_CODEC.listOf().optionalFieldOf("inhabitants", List.of())
                    .forGetter(SubBuilding::inhabitants),
            Codec.LONG.optionalFieldOf("createdTick", 0L)
                    .forGetter(SubBuilding::createdTick)
    ).apply(i, SubBuilding::new));

    /** Defensive: inhabitants list must always be non-null and immutable to callers. */
    public SubBuilding {
        inhabitants = inhabitants == null ? List.of() : List.copyOf(inhabitants);
    }
}
