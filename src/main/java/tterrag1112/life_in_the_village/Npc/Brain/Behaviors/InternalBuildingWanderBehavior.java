package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Village.Building;

import java.util.Optional;

/**
 * When idle inside a building the entity already belongs to (HOME or
 * JOB_SITE), pick a random walkable position inside the building's
 * footprint and walk there. Cool-down 100-300 ticks between wanders.
 *
 * <p>Footprint sampling uses {@link Building.BuildingShape#getMin} /
 * {@link Building.BuildingShape#getMax} and a walkability check
 * (solid block at sampled position, air above). Up to 5 samples per
 * start; gives up if none walkable.
 *
 * <p>Coexistence: WALK_TARGET write is gated by
 * {@link BrainNavGuard#canSteerNavigation}; entry also requires
 * WALK_TARGET absent so we don't stomp another behavior in flight.
 */
public class InternalBuildingWanderBehavior extends Behavior<TownspersonMob> {

    private static final float WALK_SPEED = 0.4f;
    private static final int CLOSE_ENOUGH = 1;
    private static final int MAX_SAMPLES = 5;
    private static final int COOLDOWN_MIN = 100;
    private static final int COOLDOWN_MAX = 300;
    private static final int MAX_RUN = 600; // path budget

    public InternalBuildingWanderBehavior() {
        super(ImmutableMap.of(
                NpcMemoryTypes.LAST_INTERIOR_WANDER.get(), MemoryStatus.VALUE_ABSENT,
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        return resolveOwnBuilding(level, entity)
                .map(shape -> shape.contains(entity.blockPosition()))
                .orElse(false);
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        Optional<Building.BuildingShape> shapeOpt = resolveOwnBuilding(level, entity);
        if (shapeOpt.isEmpty()) return;
        Building.BuildingShape shape = shapeOpt.get();

        BlockPos pick = sampleWalkable(level, shape, entity.getRandom());
        if (pick == null) {
            // No walkable sample — still set the cool-down so we don't
            // busy-spin start() over and over each tick.
            armCooldown(entity, gameTime);
            return;
        }
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(pick, WALK_SPEED, CLOSE_ENOUGH));
        armCooldown(entity, gameTime);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        // Run while the walk is in progress; let MoveToTargetSink finish.
        return entity.getNavigation().isInProgress();
    }

    private static void armCooldown(TownspersonMob entity, long gameTime) {
        long ttl = COOLDOWN_MIN
                + entity.getRandom().nextInt(COOLDOWN_MAX - COOLDOWN_MIN + 1);
        entity.getBrain().setMemoryWithExpiry(
                NpcMemoryTypes.LAST_INTERIOR_WANDER.get(), gameTime, ttl);
    }

    /** Returns the entity's HOME or JOB_SITE building shape — whichever
     *  the entity is currently inside. Prefers HOME when both contain. */
    private static Optional<Building.BuildingShape> resolveOwnBuilding(
            ServerLevel level, TownspersonMob entity) {
        VillageSavedData data = VillageSavedData.get(level);
        Optional<Building.BuildingShape> home = entity.getFamily().getHouseId()
                .flatMap(data::getBuildingById)
                .map(Building::getShape)
                .filter(s -> s.contains(entity.blockPosition()));
        if (home.isPresent()) return home;
        return entity.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .map(Building::getShape)
                .filter(s -> s.contains(entity.blockPosition()));
    }

    /** Samples up to {@link #MAX_SAMPLES} positions inside the shape and
     *  returns the first walkable one (solid below, air above). */
    private static BlockPos sampleWalkable(ServerLevel level,
                                           Building.BuildingShape shape,
                                           RandomSource rng) {
        BlockPos min = shape.getMin();
        int width = shape.getWidth();
        int length = shape.getLength();
        int height = shape.getHeight();
        for (int i = 0; i < MAX_SAMPLES; i++) {
            int dx = rng.nextInt(Math.max(1, width));
            int dz = rng.nextInt(Math.max(1, length));
            int dy = rng.nextInt(Math.max(1, height));
            BlockPos stand = min.offset(dx, dy, dz);
            BlockPos below = stand.below();
            if (!level.getBlockState(below).isSolid()) continue;
            if (!level.getBlockState(stand).isAir()) continue;
            if (!level.getBlockState(stand.above()).isAir()) continue;
            return stand;
        }
        return null;
    }

}
