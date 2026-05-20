package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;

/**
 * Picks a random idle gesture and triggers its animation. Runs in the
 * IDLE activity at priority 0. Animation-only — never writes
 * WALK_TARGET, never sets an attack target, never rotates the head.
 *
 * <p>Cool-down is encoded as a memory with TTL (set via
 * {@code Brain#setMemoryWithExpiry}); the vanilla Brain expires the
 * memory automatically, which re-enables {@link #checkExtraStartConditions}.
 *
 * <p>Variant selection is biased by the entity's transient
 * {@code nextGestureBias} field (written by
 * {@link MoodReactionBehavior}); zero means uniform random.
 */
public class IdleGestureBehavior extends Behavior<TownspersonMob> {

    public static final int GESTURE_WAVE         = 0;
    public static final int GESTURE_STRETCH      = 1;
    public static final int GESTURE_LOOK_AROUND  = 2;
    private static final int GESTURE_COUNT       = 3;

    private static final int COOLDOWN_MIN = 200;
    private static final int COOLDOWN_MAX = 400;
    private static final int GESTURE_DURATION = 30;

    public IdleGestureBehavior() {
        super(ImmutableMap.of(
                NpcMemoryTypes.IDLE_GESTURE_COOLDOWN.get(), MemoryStatus.VALUE_ABSENT,
                NpcMemoryTypes.CURRENT_MOOD_SNAPSHOT.get(), MemoryStatus.VALUE_PRESENT
        ), GESTURE_DURATION);
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        RandomSource rng = entity.getRandom();
        int variant = pickVariant(entity, rng);
        entity.triggerIdleGesture(variant);

        long cooldown = COOLDOWN_MIN + rng.nextInt(COOLDOWN_MAX - COOLDOWN_MIN + 1);
        entity.getBrain().setMemoryWithExpiry(
                NpcMemoryTypes.IDLE_GESTURE_COOLDOWN.get(),
                gameTime + cooldown,
                cooldown);
    }

    private static int pickVariant(TownspersonMob entity, RandomSource rng) {
        int bias = entity.getIdleGestureBias();
        if (bias > 0 && rng.nextInt(100) < 60) return GESTURE_WAVE;
        if (bias < 0 && rng.nextInt(100) < 60) return GESTURE_STRETCH;
        return rng.nextInt(GESTURE_COUNT);
    }
}
