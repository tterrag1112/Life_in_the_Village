package tterrag1112.life_in_the_village.Village.Decoration.TownSquare;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * Doc 04 §"NPC gathering points" — a named position the NPC social
 * layer can navigate to. Stored on {@code Village} so plaza-time
 * registration persists across saves; consumed by NPC Phase 2 hobby
 * activities. The decoration rework only writes; NPC code reads.
 */
public record GatheringPoint(
        UUID id,
        BlockPos pos,
        GatheringPointKind kind,
        int capacity
) {

    public GatheringPoint {
        if (id == null) {
            throw new IllegalArgumentException("GatheringPoint.id must be non-null");
        }
        if (pos == null) {
            throw new IllegalArgumentException("GatheringPoint.pos must be non-null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("GatheringPoint.kind must be non-null");
        }
        if (capacity < 1) capacity = 1;
    }

    public static final Codec<GatheringPoint> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id")
                    .forGetter(GatheringPoint::id),
            BlockPos.CODEC.fieldOf("pos")
                    .forGetter(GatheringPoint::pos),
            GatheringPointKind.CODEC.fieldOf("kind")
                    .forGetter(GatheringPoint::kind),
            Codec.INT.optionalFieldOf("capacity", 1)
                    .forGetter(GatheringPoint::capacity)
    ).apply(i, GatheringPoint::new));
}
