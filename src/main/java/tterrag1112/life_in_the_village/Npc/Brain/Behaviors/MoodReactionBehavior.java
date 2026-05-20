package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Brain.MoodBand;

/**
 * Reads {@code CURRENT_MOOD_SNAPSHOT} and writes the corresponding
 * {@link MoodBand} into the entity's transient field. The
 * {@code IdleGesturePalette} consumes the band when computing weights.
 *
 * <p>Phase 6.1.a — replaces the Phase 6.0 single-int ±1 bias.
 * Animation-only contract still holds (no movement, no look target).
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
        applyBand(entity);
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        applyBand(entity);
    }

    private static void applyBand(TownspersonMob entity) {
        int snapshot = entity.getBrain()
                .getMemory(NpcMemoryTypes.CURRENT_MOOD_SNAPSHOT.get())
                .orElse(0);
        entity.setMoodBand(MoodBand.fromSnapshot(snapshot));
    }
}
