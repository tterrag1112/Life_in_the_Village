package tterrag1112.life_in_the_village.Npc.Brain.Sensors;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.Sensor;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;

import java.util.Set;

/**
 * Reads {@link tterrag1112.life_in_the_village.Npc.Mood.NpcMoodState}'s
 * scalar value and writes a rounded integer into CURRENT_MOOD_SNAPSHOT.
 *
 * <p>Phase 6.0 — pure observation. Never modifies the entity.
 */
public class MoodSnapshotSensor extends Sensor<TownspersonMob> {

    /** Snapshot interval. Mood drifts slowly, so 40 ticks (2 s) is plenty. */
    private static final int INTERVAL_TICKS = 40;

    public MoodSnapshotSensor() {
        super(INTERVAL_TICKS);
    }

    @Override
    public Set<MemoryModuleType<?>> requires() {
        return Set.of(NpcMemoryTypes.CURRENT_MOOD_SNAPSHOT.get());
    }

    @Override
    protected void doTick(ServerLevel level, TownspersonMob entity) {
        int snapshot = Math.round(entity.getMood().value());
        entity.getBrain().setMemory(NpcMemoryTypes.CURRENT_MOOD_SNAPSHOT.get(), snapshot);
    }
}
