package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Trade;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Roles.NpcRoleTypes;
import tterrag1112.life_in_the_village.Village.Travel.Pilgrimage;
import tterrag1112.life_in_the_village.Village.Travel.PilgrimageSavedData;

import java.util.List;

/**
 * Religion Rework R3e-3b — drives a realized pilgrim's per-tick road walking and
 * advances its {@link Pilgrimage} progress, mirroring {@code CaravanMerchantBehavior}
 * (the engine advances progress only while the group is simulated; this behavior
 * owns it while realized). Universal + self-gated on the {@link NpcRoleTypes#PILGRIM}
 * away-state role, so it is inert for every NPC not on pilgrimage.
 */
public class PilgrimTravelBehavior extends Behavior<TownspersonMob> {

    private static final double WAYPOINT_REACH   = 2.5;
    private static final double MOVE_SPEED       = 0.6;
    private static final int    LOOKAHEAD_BLOCKS = 16;

    private int currentRoadIndex = -1;
    private BlockPos currentWaypoint = null;

    public PilgrimTravelBehavior() {
        super(ImmutableMap.of(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED), 24000);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        return entity.getRoles().hasRole(NpcRoleTypes.PILGRIM);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        return entity.getRoles().hasRole(NpcRoleTypes.PILGRIM);
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        currentRoadIndex = -1;
        currentWaypoint  = null;
        entity.setCurrentActivity("On pilgrimage...");
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.clearCurrentActivity();
        currentWaypoint  = null;
        currentRoadIndex = -1;
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        Pilgrimage p = PilgrimageSavedData.get(level).getByPrincipal(entity.getUUID()).orElse(null);
        if (p == null) return;

        VillageSavedData vdata = VillageSavedData.get(level);
        List<BlockPos> blocks = p.getPath(level, vdata);
        if (blocks.isEmpty()) return;

        boolean returning = p.getState() == Pilgrimage.PilgrimState.RETURNING;

        // Initialise the road index from progress on the first tick of a leg.
        if (currentRoadIndex < 0) {
            int raw = (int) (p.getProgress() * (blocks.size() - 1));
            currentRoadIndex = returning ? (blocks.size() - 1) - raw : raw;
            currentRoadIndex = Math.max(0, Math.min(blocks.size() - 1, currentRoadIndex));
        }

        // Advance the index once we reach the current waypoint.
        if (currentWaypoint != null
                && entity.blockPosition().closerThan(currentWaypoint, WAYPOINT_REACH)) {
            currentRoadIndex = returning
                    ? Math.max(currentRoadIndex - LOOKAHEAD_BLOCKS, 0)
                    : Math.min(currentRoadIndex + LOOKAHEAD_BLOCKS, blocks.size() - 1);
            currentWaypoint = null;
        }

        if (currentWaypoint == null) {
            currentWaypoint = blocks.get(currentRoadIndex);
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(currentWaypoint));
        }
        // Reissue if navigation stalled.
        if (entity.getNavigation().isDone() && currentWaypoint != null
                && !entity.blockPosition().closerThan(currentWaypoint, WAYPOINT_REACH)) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(currentWaypoint));
        }

        // Sync pilgrimage progress to the road index.
        double leg = (double) currentRoadIndex / Math.max(1, blocks.size() - 1);
        p.setProgress(returning ? 1.0 - leg : leg);

        // Leg completion — mirror Caravan: OUTBOUND arrival turns around;
        // RETURNING arrival snaps progress to 1.0 so the SavedData tick reintegrates.
        if (!returning && currentRoadIndex >= blocks.size() - 1) {
            p.setState(Pilgrimage.PilgrimState.RETURNING);
            p.setProgress(0.0);
            currentRoadIndex = -1;
            currentWaypoint  = null;
            entity.setCurrentActivity("Returning from pilgrimage...");
        } else if (returning && currentRoadIndex <= 0) {
            p.setProgress(1.0);
        }
    }

    private static WalkTarget navWalkTarget(BlockPos pos) {
        return new WalkTarget(
                BlockPos.containing(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5),
                (float) MOVE_SPEED, 1);
    }
}
