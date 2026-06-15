package tterrag1112.life_in_the_village.Npc.Tasks;

import com.mojang.serialization.Codec;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/** Stable identity of a {@link Task}. Thin record over a UUID. */
public record TaskId(UUID value) {

    public static final Codec<TaskId> CODEC =
            UUIDUtil.CODEC.xmap(TaskId::new, TaskId::value);

    public static TaskId random() {
        return new TaskId(UUID.randomUUID());
    }
}
