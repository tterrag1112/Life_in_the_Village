package tterrag1112.life_in_the_village.Npc.Brain.Sensors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.Sensor;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Derives the vanilla {@link MemoryModuleType#HOME} and
 * {@link MemoryModuleType#JOB_SITE} memories from the NPC's existing
 * assignments (house from {@code FamilyComponent.houseId}, workplace
 * from {@code assignedBuildingId}).
 *
 * <p>Phase 6.0 — pure derivation. Does not move the entity, does not
 * mutate assignments. If an assignment is absent the corresponding
 * memory is erased.
 */
public class HomeAndJobSiteSensor extends Sensor<TownspersonMob> {

    /** Assignments change rarely; 200 ticks (10 s) is plenty. */
    private static final int INTERVAL_TICKS = 200;

    public HomeAndJobSiteSensor() {
        super(INTERVAL_TICKS);
    }

    @Override
    public Set<MemoryModuleType<?>> requires() {
        return Set.of(MemoryModuleType.HOME, MemoryModuleType.JOB_SITE);
    }

    @Override
    protected void doTick(ServerLevel level, TownspersonMob entity) {
        Brain<?> brain = entity.getBrain();
        VillageSavedData data = VillageSavedData.get(level);

        update(brain, MemoryModuleType.HOME, lookup(data, entity.getFamily().getHouseId(), level));
        update(brain, MemoryModuleType.JOB_SITE,
                lookup(data, entity.getAssignedBuildingId(), level));
    }

    private static Optional<GlobalPos> lookup(VillageSavedData data,
                                              Optional<UUID> id,
                                              ServerLevel level) {
        return id.flatMap(data::getBuildingById)
                .map(Building::getShape)
                .map(Building.BuildingShape::getOrigin)
                .map(origin -> GlobalPos.of(level.dimension(), origin));
    }

    private static void update(Brain<?> brain,
                               MemoryModuleType<GlobalPos> type,
                               Optional<GlobalPos> value) {
        if (value.isPresent()) {
            brain.setMemory(type, value.get());
        } else {
            brain.eraseMemory(type);
        }
    }
}
