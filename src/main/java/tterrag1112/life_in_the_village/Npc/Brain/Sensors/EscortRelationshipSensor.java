package tterrag1112.life_in_the_village.Npc.Brain.Sensors;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.schedule.Activity;
import tterrag1112.life_in_the_village.Entities.LifeStage;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Brain.NpcActivities;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 6.1.c — scoped to child→parent only. Writes ESCORT_LEADER on
 * a CHILD-stage entity when its head-of-household is loaded, within
 * range, and currently in IDLE or SOCIAL (children don't tag along
 * into work). Apprenticeship and caravan escorts are deferred to
 * later phases.
 *
 * <p>Parent lookup path: {@code FamilyComponent.getHeadOfHouseholdId()}
 * — the only clean parent reference on the entity (no separate
 * mother/father fields exist; the head is the single parent we follow).
 */
public class EscortRelationshipSensor extends Sensor<TownspersonMob> {

    private static final int INTERVAL_TICKS = 100;
    private static final double MAX_RANGE_SQ = 24.0 * 24.0;

    public EscortRelationshipSensor() {
        super(INTERVAL_TICKS);
    }

    @Override
    public Set<MemoryModuleType<?>> requires() {
        return Set.of(NpcMemoryTypes.ESCORT_LEADER.get());
    }

    @Override
    protected void doTick(ServerLevel level, TownspersonMob entity) {
        Brain<?> brain = entity.getBrain();

        if (entity.getLifeStage() != LifeStage.CHILD) {
            brain.eraseMemory(NpcMemoryTypes.ESCORT_LEADER.get());
            return;
        }

        Optional<UUID> headId = entity.getFamily().getHeadOfHouseholdId();
        if (headId.isEmpty()) {
            brain.eraseMemory(NpcMemoryTypes.ESCORT_LEADER.get());
            return;
        }

        Optional<TownspersonMob> parentOpt = TownspersonMob.findByUUID(level, headId.get());
        if (parentOpt.isEmpty()) {
            brain.eraseMemory(NpcMemoryTypes.ESCORT_LEADER.get());
            return;
        }
        TownspersonMob parent = parentOpt.get();

        if (parent.distanceToSqr(entity) > MAX_RANGE_SQ) {
            brain.eraseMemory(NpcMemoryTypes.ESCORT_LEADER.get());
            return;
        }

        if (!parentInFollowableActivity(parent)) {
            brain.eraseMemory(NpcMemoryTypes.ESCORT_LEADER.get());
            return;
        }

        brain.setMemory(NpcMemoryTypes.ESCORT_LEADER.get(), parent);
    }

    /** True iff the parent's active non-core activity is IDLE or
     *  lit:social. WORK / REST suppress follow. */
    private static boolean parentInFollowableActivity(TownspersonMob parent) {
        Optional<Activity> active = parent.getBrain().getActiveNonCoreActivity();
        if (active.isEmpty()) return false;
        Activity a = active.get();
        return a == Activity.IDLE || a == NpcActivities.SOCIAL.get();
    }
}
