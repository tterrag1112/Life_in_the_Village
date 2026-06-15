package tterrag1112.life_in_the_village.Npc.Tasks;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Mth;
import tterrag1112.life_in_the_village.Profession.Tasks.TaskPriority;

import java.util.Comparator;

/**
 * A task's importance: a coarse {@link TaskPriority} tier plus a fine
 * {@code urgency} (0..1) used to order tasks within a tier. Urgency is
 * data only in T0 — later phases derive it from need gaps / deadline
 * pressure when a {@code TaskSource} generates the task.
 *
 * <p>Reuses the existing {@link TaskPriority} enum
 * ({@code Profession/Tasks/TaskPriority}) rather than introducing a
 * parallel tier type.</p>
 *
 * @param tier    coarse band (CRITICAL/HIGH/NORMAL/LOW)
 * @param urgency intra-tier ordering, clamped to [0,1]
 */
public record Priority(TaskPriority tier, float urgency) {

    public Priority {
        urgency = Mth.clamp(urgency, 0f, 1f);
    }

    public static Priority of(TaskPriority tier) {
        return new Priority(tier, 0f);
    }

    public static final Codec<Priority> CODEC = RecordCodecBuilder.create(i -> i.group(
            TaskPriority.CODEC.fieldOf("tier").forGetter(Priority::tier),
            Codec.FLOAT.optionalFieldOf("urgency", 0f).forGetter(Priority::urgency)
    ).apply(i, Priority::new));

    /**
     * Higher tier first, then higher urgency first. Deadline is broken
     * separately by {@link TaskBoard} (it lives on the {@link Task}, not
     * here), so this comparator covers only the priority component.
     */
    public static final Comparator<Priority> HIGH_FIRST =
            Comparator.comparingInt((Priority p) -> p.tier().weight())
                    .thenComparingDouble(Priority::urgency)
                    .reversed();
}
