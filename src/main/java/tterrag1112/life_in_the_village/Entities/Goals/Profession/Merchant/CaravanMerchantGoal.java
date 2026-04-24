package tterrag1112.life_in_the_village.Entities.Goals.Profession.Merchant;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.Caravan;
import tterrag1112.life_in_the_village.Village.Economy.Trade.CaravanSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.GraphTradeRouteEstablisher;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoad;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoute;
import tterrag1112.life_in_the_village.Village.Economy.Trade.RouteSegment;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public class CaravanMerchantGoal extends Goal {

    // How close the merchant needs to get to a waypoint
    // before moving to the next one
    private static final double WAYPOINT_REACH    = 2.5;
    private static final double MOVE_SPEED        = 0.6;
    // How far ahead on the road to look for next waypoint
    private static final int    LOOKAHEAD_BLOCKS  = 16;

    private final TownspersonMob entity;
    private int currentRoadIndex = -1;
    private BlockPos currentWaypoint = null;
    private boolean hasLoggedPath = false;

    public CaravanMerchantGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return entity.isCaravanMember();
    }

    @Override
    public boolean canContinueToUse() {
        return entity.isCaravanMember();
    }

    @Override
    public void start() {
        currentRoadIndex = -1;
        currentWaypoint  = null;
        hasLoggedPath    = false;
        entity.setCurrentActivity("Travelling...");
    }

    @Override
    public void stop() {
        entity.getNavigation().stop();
        entity.clearCurrentActivity();
        currentWaypoint  = null;
        currentRoadIndex = -1;
    }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level))
            return;

        UUID caravanId = entity.getCaravanId().orElse(null);
        if (caravanId == null) return;

        CaravanSavedData caravanData =
                CaravanSavedData.get(level);
        Caravan caravan = caravanData.getCaravan(caravanId)
                .orElse(null);
        if (caravan == null) return;

        if (caravan.getState()
                == Caravan.CaravanState.DELIVERING) {
            entity.getNavigation().stop();
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
            entity.getNavigation().moveTo(
                    currentWaypoint.getX() + 0.5,
                    currentWaypoint.getY(),
                    currentWaypoint.getZ() + 0.5,
                    MOVE_SPEED);
        }

        // If navigation stalled for any reason, reissue it
        if (entity.getNavigation().isDone()
                && currentWaypoint != null
                && !entity.blockPosition().closerThan(
                currentWaypoint, WAYPOINT_REACH)) {
            entity.getNavigation().moveTo(
                    currentWaypoint.getX() + 0.5,
                    currentWaypoint.getY(),
                    currentWaypoint.getZ() + 0.5,
                    MOVE_SPEED);
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

        entity.getNavigation().moveTo(
                currentWaypoint.getX() + 0.5,
                currentWaypoint.getY(),
                currentWaypoint.getZ() + 0.5,
                moveSpeed);
    }

    /**
     * Resolves the block path for the caravan's current route.
     * Segment-based routes are resolved first; graph-based routes fall back to
     * {@link GraphTradeRouteEstablisher#resolveGraphBlocks}; legacy routes use the TradeRoad block list.
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

        return vdata.getRoadById(route.getConnectionId())
                .map(TradeRoad::getBlocks)
                .orElse(List.of());
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
}