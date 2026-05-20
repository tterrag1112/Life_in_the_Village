package tterrag1112.life_in_the_village.Npc.Brain.Sensors;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.Sensor;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Scans {@link MemoryModuleType#NEAREST_LIVING_ENTITIES} for nearby
 * TownspersonMob instances that look like reasonable conversation
 * partners and writes them to CONVERSATION_CANDIDATES.
 *
 * <p>Phase 6.0 — this sensor exists to validate the candidate-list
 * pattern. No behavior reads the memory yet (that's Phase 6.1). The
 * eligibility gate is intentionally permissive: alive, on the same
 * level, within ~10 blocks, and not the entity itself. Profession /
 * mood / cool-down gating will land alongside the social behaviors
 * that consume the list.
 */
public class ConversationCandidatesSensor extends Sensor<TownspersonMob> {

    private static final int INTERVAL_TICKS = 60;
    private static final double SCAN_RADIUS_SQ = 10.0 * 10.0;
    private static final int MAX_CANDIDATES = 8;

    public ConversationCandidatesSensor() {
        super(INTERVAL_TICKS);
    }

    @Override
    public Set<MemoryModuleType<?>> requires() {
        return Set.of(
                MemoryModuleType.NEAREST_LIVING_ENTITIES,
                NpcMemoryTypes.CONVERSATION_CANDIDATES.get()
        );
    }

    @Override
    protected void doTick(ServerLevel level, TownspersonMob entity) {
        Brain<?> brain = entity.getBrain();
        Optional<List<LivingEntity>> nearby =
                brain.getMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES);

        if (nearby.isEmpty()) {
            brain.eraseMemory(NpcMemoryTypes.CONVERSATION_CANDIDATES.get());
            return;
        }

        List<LivingEntity> candidates = nearby.get().stream()
                .filter(e -> e != entity)
                .filter(e -> e instanceof TownspersonMob)
                .filter(LivingEntity::isAlive)
                .filter(e -> e.distanceToSqr(entity) <= SCAN_RADIUS_SQ)
                .limit(MAX_CANDIDATES)
                .toList();

        if (candidates.isEmpty()) {
            brain.eraseMemory(NpcMemoryTypes.CONVERSATION_CANDIDATES.get());
        } else {
            brain.setMemory(NpcMemoryTypes.CONVERSATION_CANDIDATES.get(), candidates);
        }
    }
}
