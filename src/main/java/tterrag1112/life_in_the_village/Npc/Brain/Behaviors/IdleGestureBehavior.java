package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Gesture;
import tterrag1112.life_in_the_village.Npc.Brain.IdleGesturePalette;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;

/**
 * Picks an idle gesture from {@link IdleGesturePalette} (weighted by
 * time-of-day and the entity's current {@code MoodBand}) and triggers
 * its animation. Runs in IDLE @ priority 0. Animation-only — never
 * writes WALK_TARGET, never sets an attack target, never rotates the
 * head.
 *
 * <p>Cool-down is encoded as a memory with TTL; the vanilla Brain
 * expires it automatically, which re-enables the entry condition.
 */
public class IdleGestureBehavior extends Behavior<TownspersonMob> {

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
        Gesture pick = IdleGesturePalette.pickIdle(
                level.getDayTime(), entity.getMoodBand(), rng);

        // NIGHT bucket can be empty; skip the trigger but still set
        // the cool-down so we don't busy-spin start() over and over.
        if (pick != null) {
            entity.triggerGesture(pick);
        }

        long cooldown = COOLDOWN_MIN + rng.nextInt(COOLDOWN_MAX - COOLDOWN_MIN + 1);
        entity.getBrain().setMemoryWithExpiry(
                NpcMemoryTypes.IDLE_GESTURE_COOLDOWN.get(),
                gameTime + cooldown,
                cooldown);
    }
}
