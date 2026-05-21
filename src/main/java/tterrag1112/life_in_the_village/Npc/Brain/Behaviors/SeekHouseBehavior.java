package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import tterrag1112.life_in_the_village.Entities.FamilyRole;
import tterrag1112.life_in_the_village.Entities.HouseholdManager;
import tterrag1112.life_in_the_village.Entities.LifeStage;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Comparator;
import java.util.Optional;

/**
 * Phase 6.2.c — migrated from {@code SeekHouseGoal}. Universal IDLE @ 1
 * + SOCIAL @ 2. Homeless ADULT/TEEN/ELDERLY NPCs scan for an empty
 * HOUSE / FARMHOUSE in their nearby village, walk over, claim it via
 * {@code setHouseId + FamilyRole.HEAD + HouseholdManager.onNpcMovedHouse}.
 *
 * <p>SEEK_HOUSE_COOLDOWN TTL memory (600t) replaces the goal's 2400t
 * checkTimer — the new cadence is slightly faster because the gate is
 * already in the activity layer (only IDLE/SOCIAL hours).
 */
public class SeekHouseBehavior extends Behavior<TownspersonMob> {

    private static final long COOLDOWN_TICKS = 600L;
    private static final int WALK_TIMEOUT = 600;
    private static final double ARRIVAL_DIST_SQ = 9.0;
    private static final float WALK_SPEED = 1.0f;
    private static final int CLOSE_ENOUGH = 1;
    private static final int MAX_RUN = WALK_TIMEOUT + 60;

    private Building targetHouse;
    private int walkTimer;

    public SeekHouseBehavior() {
        super(ImmutableMap.of(
                NpcMemoryTypes.SEEK_HOUSE_COOLDOWN.get(), MemoryStatus.VALUE_ABSENT,
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (entity.hasHome()) return false;
        if (entity.getLifeStage() == LifeStage.CHILD) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;

        VillageSavedData data = VillageSavedData.get(level);
        Optional<Village> village = findNearbyVillage(level, data, entity);
        if (village.isEmpty()) return false;
        targetHouse = findEmptyHouse(level, data, village.get(), entity);
        return targetHouse != null;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        return !entity.hasHome() && walkTimer < WALK_TIMEOUT && targetHouse != null;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        walkTimer = 0;
        if (targetHouse != null) {
            BlockPos origin = targetHouse.getShape().getOrigin();
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(origin, WALK_SPEED, CLOSE_ENOUGH));
        }
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (targetHouse == null) return;
        walkTimer++;

        BlockPos target = targetHouse.getShape().getOrigin();
        double distSq = entity.distanceToSqr(target.getX(), target.getY(), target.getZ());
        if (distSq > ARRIVAL_DIST_SQ) {
            // Keep WALK_TARGET fresh in case MoveToTargetSink finished early.
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(target, WALK_SPEED, CLOSE_ENOUGH));
            return;
        }

        // Arrived — claim if still empty.
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        VillageSavedData data = VillageSavedData.get(level);
        if (isHouseEmpty(level, targetHouse)) {
            entity.setHouseId(targetHouse.getId());
            entity.setFamilyRole(FamilyRole.HEAD);
            HouseholdManager.onNpcMovedHouse(
                    entity.getUUID(), targetHouse.getId(),
                    entity.isChild(), data, level.getGameTime());
            entity.setAssignedVillageName(
                    data.getVillageAt(targetHouse.getShape().getOrigin())
                            .map(Village::getName).orElse(""));
        }
        targetHouse = null;
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        // Stamp cooldown only on a failed / aborted attempt; a successful
        // claim leaves hasHome()=true and the entry condition self-blocks.
        if (!entity.hasHome()) {
            entity.getBrain().setMemoryWithExpiry(
                    NpcMemoryTypes.SEEK_HOUSE_COOLDOWN.get(), gameTime, COOLDOWN_TICKS);
        }
        targetHouse = null;
        walkTimer = 0;
    }

    // ── Verbatim from SeekHouseGoal ─────────────────────────────────────────

    private static Building findEmptyHouse(ServerLevel level, VillageSavedData data,
                                           Village village, TownspersonMob entity) {
        return village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() == BuildingType.HOUSE
                        || b.getType() == BuildingType.FARMHOUSE)
                .filter(b -> isHouseEmpty(level, b))
                .min(Comparator.comparingDouble(b -> entity.distanceToSqr(
                        b.getShape().getOrigin().getX(),
                        b.getShape().getOrigin().getY(),
                        b.getShape().getOrigin().getZ())))
                .orElse(null);
    }

    private static boolean isHouseEmpty(ServerLevel level, Building house) {
        return level.getEntitiesOfClass(TownspersonMob.class,
                house.getShape().toAABB().inflate(4),
                mob -> house.getId().equals(mob.getHouseId().orElse(null))
                        && mob.getFamilyRole() == FamilyRole.HEAD
        ).isEmpty();
    }

    private static Optional<Village> findNearbyVillage(ServerLevel level,
                                                       VillageSavedData data,
                                                       TownspersonMob entity) {
        return data.getAllVillages().stream()
                .filter(v -> v.getBounds(data)
                        .map(b -> b.inflate(32).contains(
                                entity.getX(), entity.getY(), entity.getZ()))
                        .orElse(false))
                .findFirst();
    }
}
