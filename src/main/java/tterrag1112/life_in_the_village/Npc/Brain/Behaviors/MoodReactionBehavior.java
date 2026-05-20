package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;

/**
 * Reads CURRENT_MOOD_SNAPSHOT and biases the entity's next-gesture
 * selection. Pure mood→tuning translation; no movement, no animation
 * triggering of its own — {@link IdleGestureBehavior} reads
 * {@code TownspersonMob#getIdleGestureBias()} when picking a variant.
 */
public class MoodReactionBehavior extends Behavior<TownspersonMob> {

    public MoodReactionBehavior() {
        super(ImmutableMap.of(
                NpcMemoryTypes.CURRENT_MOOD_SNAPSHOT.get(), MemoryStatus.VALUE_PRESENT
        ), Integer.MAX_VALUE);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        return true;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        applyBias(entity);
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        applyBias(entity);
    }

    private static void applyBias(TownspersonMob entity) {
        int mood = entity.getBrain()
                .getMemory(NpcMemoryTypes.CURRENT_MOOD_SNAPSHOT.get())
                .orElse(0);
        int bias = mood >= 25 ? 1 : (mood <= -25 ? -1 : 0);
        entity.setIdleGestureBias(bias);
    }
}
