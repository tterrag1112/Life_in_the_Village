package tterrag1112.life_in_the_village.Village.Decoration.TownSquare;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * Doc 04 §"NPC gathering points" — kinds of named position the NPC
 * social layer (Phase 2 hobbies + weekly schedule) can target.
 *
 * <p>Codec-stable string-representable enum. Append-only.</p>
 */
public enum GatheringPointKind implements StringRepresentable {

    /** Sit-and-chat slot — one NPC per bench. */
    BENCH,
    /** Edge-stand around a fountain, 2-3 NPCs. */
    FOUNTAIN,
    /** Small-group gather, 3-5 NPCs. */
    GAZEBO,
    /** Read-posts at a notice board, one NPC at a time. */
    NOTICE_BOARD,
    /** Stall-run during market days. */
    VENDOR_ZONE;

    public static final Codec<GatheringPointKind> CODEC =
            StringRepresentable.fromEnum(GatheringPointKind::values);

    @Override
    public String getSerializedName() {
        return name();
    }
}
