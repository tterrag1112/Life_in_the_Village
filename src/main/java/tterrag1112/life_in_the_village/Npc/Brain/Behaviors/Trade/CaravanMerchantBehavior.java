package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Trade;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import com.google.common.collect.ImmutableMap;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.Caravan;
import tterrag1112.life_in_the_village.Village.Economy.Trade.CaravanSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.GraphTradeRouteEstablisher;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoute;
import tterrag1112.life_in_the_village.Village.Economy.Trade.RouteSegment;

import java.util.List;
import java.util.UUID;

public class CaravanMerchantBehavior extends Behavior<TownspersonMob> {

    // How close the merchant needs to get to a waypoint
    // before moving to the next one
    private static final double WAYPOINT_REACH    = 2.5;
    private static final double MOVE_SPEED        = 0.6;
    // How far ahead on the road to look for next waypoint
    private static final int    LOOKAHEAD_BLOCKS  = 16;

    private TownspersonMob entity;

    public CaravanMerchantBehavior() {
        super(com.google.common.collect.ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), 24000);
    }
    private int currentRoadIndex = -1;
    private BlockPos currentWaypoint = null;
    private boolean hasLoggedPath = false;

    

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        this.entity = entity;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        return entity.isCaravanMember();
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        return entity.isCaravanMember();
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        currentRoadIndex = -1;
        currentWaypoint  = null;
        hasLoggedPath    = false;
        entity.setCurrentActivity("Travelling...");
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.clearCurrentActivity();
        currentWaypoint  = null;
        currentRoadIndex = -1;
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;

        UUID caravanId = entity.getCaravanId().orElse(null);
        if (caravanId == null) return;

        CaravanSavedData caravanData =
                CaravanSavedData.get(level);
        Caravan caravan = caravanData.getCaravan(caravanId)
                .orElse(null);
        if (caravan == null) return;

        if (caravan.getState()
                == Caravan.CaravanState.DELIVERING) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            entity.setCurrentActivity("Delivering goods...");
            return;
        }

        List<BlockPos> blocks = resolveBlocks(caravan, level);
        if (blocks.isEmpty()) return;
        if (!hasLoggedPath) {
            System.out.println("[CaravanGoal] Caravan " + caravanId.toString().substring(0, 8)
                    + ": path=" + blocks.size() + " blocks"
                    + " (graphRoute=" + isGraphRoute(caravan, level) + ")");
            hasLoggedPath = true;
        }
        boolean returning = caravan.getState()
                == Caravan.CaravanState.RETURNING;

        // Initialize index from progress on first tick
        if (currentRoadIndex < 0) {
            int raw = (int)(caravan.getProgress()
                    * (blocks.size() - 1));
            currentRoadIndex = returning
                    ? (blocks.size() - 1) - raw : raw;
            currentRoadIndex = Math.max(0, Math.min(
                    blocks.size() - 1, currentRoadIndex));
        }

        // Check if close enough to current waypoint
        // to advance — don't wait for nav to finish
        if (currentWaypoint != null
                && entity.blockPosition().closerThan(
                currentWaypoint, WAYPOINT_REACH)) {
            // Advance index
            if (!returning) {
                currentRoadIndex = Math.min(
                        currentRoadIndex + LOOKAHEAD_BLOCKS,
                        blocks.size() - 1);
            } else {
                currentRoadIndex = Math.max(
                        currentRoadIndex - LOOKAHEAD_BLOCKS,
                        0);
            }
            currentWaypoint = null; // force new nav request
        }

        // Always request navigation if no active waypoint
        if (currentWaypoint == null) {
            currentWaypoint = blocks.get(currentRoadIndex);

            // Always issue moveTo — even if already navigating
            // this overrides the current path with the new target
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    currentWaypoint.getX() + 0.5,
                    currentWaypoint.getY(),
                    currentWaypoint.getZ() + 0.5,
                    MOVE_SPEED));
        }

        // If navigation stalled for any reason, reissue it
        if (entity.getNavigation().isDone()
                && currentWaypoint != null
                && !entity.blockPosition().closerThan(
                currentWaypoint, WAYPOINT_REACH)) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    currentWaypoint.getX() + 0.5,
                    currentWaypoint.getY(),
                    currentWaypoint.getZ() + 0.5,
                    MOVE_SPEED));
        }

        // Sync caravan progress to entity position
        double newProgress = (double) currentRoadIndex
                / Math.max(1, blocks.size() - 1);
        caravan.setProgress(returning
                ? 1.0 - newProgress : newProgress);

        // Check completion
        if (!returning && currentRoadIndex
                >= blocks.size() - 1) {
            caravan.setState(Caravan.CaravanState.DELIVERING);
        } else if (returning && currentRoadIndex <= 0) {
            caravan.setProgress(1.0);
        }

        entity.setCurrentActivity(
                returning ? "Returning home..."
                        : "Travelling to "
                        + getDestName(caravan, level) + "...");
    }

    private void advanceRoadIndex(Caravan caravan,
                                  List<BlockPos> blocks) {
        boolean returning = caravan.getState()
                == Caravan.CaravanState.RETURNING;

        if (!returning) {
            currentRoadIndex = Math.min(
                    currentRoadIndex + LOOKAHEAD_BLOCKS,
                    blocks.size() - 1);
        } else {
            currentRoadIndex = Math.max(
                    currentRoadIndex - LOOKAHEAD_BLOCKS, 0);
        }
    }

    private void setNextWaypoint(Caravan caravan,
                                 List<BlockPos> blocks) {
        if (currentRoadIndex < 0
                || currentRoadIndex >= blocks.size()) return;

        currentWaypoint = blocks.get(currentRoadIndex);

        double moveSpeed = MOVE_SPEED;

        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                currentWaypoint.getX() + 0.5,
                currentWaypoint.getY(),
                currentWaypoint.getZ() + 0.5,
                moveSpeed));
    }

    /**
     * Resolves the block path for the caravan's current route. Segment-based
     * routes resolve via their RouteSegment list; graph-based routes resolve
     * through {@link GraphTradeRouteEstablisher#resolveGraphBlocks}.
     */
    private List<BlockPos> resolveBlocks(Caravan caravan, ServerLevel level) {
        VillageSavedData vdata = VillageSavedData.get(level);
        TradeRoute route = vdata.getRouteById(caravan.getRouteId()).orElse(null);
        if (route == null) return List.of();

        if (route.hasSegments()) {
            return Caravan.resolveSegmentBlocks(level, route.getSegments());
        }

        if (route.hasGraphPath()) {
            return GraphTradeRouteEstablisher.resolveGraphBlocks(
                    WorldRoadSavedData.get(level).getGraph(),
                    route.getEdgeIds(),
                    route.getRouteStartNodeId());
        }

        return List.of();
    }

    private boolean isGraphRoute(Caravan caravan, ServerLevel level) {
        return VillageSavedData.get(level)
                .getRouteById(caravan.getRouteId())
                .map(r -> r.hasGraphPath() || r.hasSegments())
                .orElse(false);
    }

    private String getDestName(Caravan caravan,
                               ServerLevel level) {
        return VillageSavedData.get(level)
                .getVillageById(caravan.getDestVillageId())
                .map(v -> v.getName())
                .orElse("destination");
    }

    /** Bridge helper — Goal-side used entity.getNavigation().moveTo(x,y,z,speed);
     *  Behavior-side writes WALK_TARGET memory and lets CORE MoveToTargetSink steer. */
    private static WalkTarget navWalkTarget(double x, double y, double z, double speed) {
        return new WalkTarget(net.minecraft.core.BlockPos.containing(x, y, z), (float) speed, 1);
    }

}