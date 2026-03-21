// src/main/java/tterrag1112/life_in_the_village/Entities/Goals/GuardPatrolGoal.java
package tterrag1112.life_in_the_village.Entities.Goals.Profession.Guard;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Controls guard NPC patrol behaviour.
 *
 * <h3>Patrol hierarchy</h3>
 * <ol>
 *   <li><b>Post patrol</b> — if the guard is assigned a post (a watchtower
 *       or guard tower entrance), they walk a tight radius around it.
 *       Posts are assigned by {@link Village#assignGuardPost}.</li>
 *   <li><b>Perimeter circuit</b> — if no post is assigned, the guard walks
 *       the stored {@link Village#getPatrolWaypoints()} in sequence,
 *       producing a continuous circuit of the village boundary.</li>
 *   <li><b>Fallback wander</b> — if no waypoints are stored (old save or
 *       small hamlet), the guard picks random points near the village
 *       centroid, avoiding building interiors.</li>
 * </ol>
 */
public class GuardPatrolGoal extends Goal {

    // ── Configuration ─────────────────────────────────────────────────────────
    /** How often (ticks) to attempt a post reassignment. */
    private static final int REASSIGN_INTERVAL  = 1200; // 60 seconds
    /** Radius for post-patrol random wander. */
    private static final int POST_PATROL_RADIUS = 10;
    /** How close to a waypoint before advancing to the next (blocks). */
    private static final int WAYPOINT_REACH     = 4;
    /** Movement speed for patrol. */
    private static final double PATROL_SPEED    = 0.6;
    /** Movement speed when rushing to post after alert. */
    private static final double ALERT_SPEED     = 1.2;

    // ── State ─────────────────────────────────────────────────────────────────
    private final TownspersonMob entity;
    private int  reassignTimer    = 0;
    private int  waypointIndex    = -1; // current waypoint in circuit
    private int  stuckTimer       = 0;  // ticks at same position
    private BlockPos lastPos      = null;

    public GuardPatrolGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    // =========================================================================
    // Goal lifecycle
    // =========================================================================

    @Override
    public boolean canUse() {
        if (!entity.isWorkTime()) return false;
        // Don't start if already navigating
        if (entity.getNavigation().isInProgress()) return false;
        // Random activation — guards don't patrol every tick
        return entity.getRandom().nextInt(60) == 0;
    }

    @Override
    public boolean canContinueToUse() {
        if (!entity.isWorkTime()) return false;
        return entity.getNavigation().isInProgress() || stuckTimer < 60;
    }

    @Override
    public void start() {
        stuckTimer = 0;
        lastPos    = entity.blockPosition();
        navigate();
    }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel serverLevel)) return;

        // ── Reassignment check ────────────────────────────────────────────────
        reassignTimer++;
        if (reassignTimer >= REASSIGN_INTERVAL) {
            reassignTimer = 0;
            tryReassignPost(serverLevel);
        }

        // ── Stuck detection ───────────────────────────────────────────────────
        BlockPos currentPos = entity.blockPosition();
        if (currentPos.equals(lastPos)) {
            stuckTimer++;
            if (stuckTimer >= 40) {
                // Stuck — advance to next waypoint to break the loop
                advanceWaypoint(serverLevel);
                stuckTimer = 0;
            }
        } else {
            stuckTimer = 0;
            lastPos    = currentPos;
        }

        // ── Waypoint arrival check ────────────────────────────────────────────
        if (!entity.getNavigation().isInProgress()) {
            navigate();
        } else {
            // Check if close enough to current waypoint to advance
            checkWaypointArrival(serverLevel);
        }
    }

    @Override
    public void stop() {
        entity.getNavigation().stop();
        stuckTimer    = 0;
        lastPos       = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    // =========================================================================
    // Navigation
    // =========================================================================

    private void navigate() {
        if (!(entity.level() instanceof ServerLevel serverLevel)) return;

        if (entity.getAssignedPost().isPresent()) {
            navigateToPost(entity.getAssignedPost().get());
        } else {
            navigateCircuit(serverLevel);
        }
    }

    /**
     * Navigates to a random point within {@link #POST_PATROL_RADIUS}
     * of the assigned post. Guards at posts stand alert and move less.
     */
    private void navigateToPost(BlockPos post) {
        // Snap post to live surface in case terrain changed
        if (entity.level() instanceof ServerLevel serverLevel) {
            int surfY = serverLevel.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    post.getX(), post.getZ());
            BlockPos livePost = new BlockPos(post.getX(), surfY + 1, post.getZ());

            for (int attempt = 0; attempt < 8; attempt++) {
                int dx = entity.getRandom().nextInt(POST_PATROL_RADIUS * 2)
                        - POST_PATROL_RADIUS;
                int dz = entity.getRandom().nextInt(POST_PATROL_RADIUS * 2)
                        - POST_PATROL_RADIUS;
                double dist = Math.sqrt(dx * dx + dz * dz);

                if (dist > POST_PATROL_RADIUS) continue;

                double tx = livePost.getX() + dx;
                double tz = livePost.getZ() + dz;
                int    ty = serverLevel.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        (int)tx, (int)tz);

                // Don't wander into a position too far above/below the post
                if (Math.abs(ty - livePost.getY()) > 4) continue;

                entity.getNavigation().moveTo(tx, ty + 1, tz, PATROL_SPEED);
                return;
            }

            // Fallback — return directly to post
            entity.getNavigation().moveTo(
                    livePost.getX(), livePost.getY(), livePost.getZ(),
                    PATROL_SPEED);
        }
    }

    /**
     * Walks the village perimeter circuit in order.
     * Advances {@link #waypointIndex} sequentially so the guard makes a
     * continuous clockwise loop of the village boundary.
     */
    private void navigateCircuit(ServerLevel level) {
        List<BlockPos> waypoints = getWaypoints(level);

        if (waypoints.isEmpty()) {
            navigateFallback(level);
            return;
        }

        // Initialise index on first use or after stop
        if (waypointIndex < 0 || waypointIndex >= waypoints.size()) {
            // Start at the waypoint closest to current position
            waypointIndex = nearestWaypointIndex(waypoints);
        }

        BlockPos target = waypoints.get(waypointIndex);
        entity.getNavigation().moveTo(
                target.getX(), target.getY(), target.getZ(),
                PATROL_SPEED);
    }

    private void checkWaypointArrival(ServerLevel level) {
        List<BlockPos> waypoints = getWaypoints(level);
        if (waypoints.isEmpty() || waypointIndex < 0) return;

        BlockPos target = waypoints.get(waypointIndex);
        double dist = entity.position().distanceTo(
                net.minecraft.world.phys.Vec3.atCenterOf(target));

        if (dist <= WAYPOINT_REACH) {
            advanceWaypoint(level);
        }
    }

    private void advanceWaypoint(ServerLevel level) {
        List<BlockPos> waypoints = getWaypoints(level);
        if (waypoints.isEmpty()) return;

        waypointIndex = (waypointIndex + 1) % waypoints.size();

        BlockPos next = waypoints.get(waypointIndex);
        entity.getNavigation().moveTo(
                next.getX(), next.getY(), next.getZ(),
                PATROL_SPEED);
    }

    /**
     * Fallback when no patrol waypoints are stored — picks a random
     * point near the village centroid that is not inside a building.
     */
    private void navigateFallback(ServerLevel level) {
        Optional<Village> villageOpt = getVillage(level);
        if (villageOpt.isEmpty()) return;

        Village village = villageOpt.get();
        Optional<net.minecraft.world.phys.AABB> bounds =
                village.getBounds(VillageSavedData.get(level));
        if (bounds.isEmpty()) return;

        net.minecraft.world.phys.AABB area = bounds.get();
        VillageSavedData data = VillageSavedData.get(level);

        for (int attempt = 0; attempt < 16; attempt++) {
            double x = area.minX + entity.getRandom().nextDouble()
                    * (area.maxX - area.minX);
            double z = area.minZ + entity.getRandom().nextDouble()
                    * (area.maxZ - area.minZ);
            int y = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (int)x, (int)z);

            BlockPos candidate = new BlockPos((int)x, y + 1, (int)z);

            // Skip positions inside buildings
            if (data.getBuildingAt(candidate).isPresent()) continue;
            if (data.getBuildingAt(candidate.below()).isPresent()) continue;

            entity.getNavigation().moveTo(x, y + 1, z, PATROL_SPEED);
            return;
        }
    }

    // =========================================================================
    // Post reassignment
    // =========================================================================

    private void tryReassignPost(ServerLevel level) {
        Optional<Village> villageOpt = getVillage(level);
        if (villageOpt.isEmpty()) return;

        Optional<BlockPos> post = villageOpt.get()
                .assignGuardPost(level, entity.getUUID());

        // assignGuardPost returns empty if there aren't enough guards
        // for posts to matter — guard stays on circuit patrol
        entity.setAssignedPost(post.orElse(null));

        if (post.isPresent()) {
            System.out.println("GuardPatrolGoal: "
                    + entity.getNpcName()
                    + " assigned to post at "
                    + post.get().toShortString());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private List<BlockPos> getWaypoints(ServerLevel level) {
        return getVillage(level)
                .map(Village::getPatrolWaypoints)
                .orElse(List.of());
    }

    private Optional<Village> getVillage(ServerLevel level) {
        return entity.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(level)
                        .getVillageByName(name));
    }

    /**
     * Returns the index of the waypoint closest to the entity's
     * current position. Used to start the circuit from the nearest
     * point rather than always from index 0.
     */
    private int nearestWaypointIndex(List<BlockPos> waypoints) {
        int    best     = 0;
        double bestDist = Double.MAX_VALUE;
        BlockPos pos    = entity.blockPosition();

        for (int i = 0; i < waypoints.size(); i++) {
            double d = waypoints.get(i).distSqr(pos);
            if (d < bestDist) {
                bestDist = d;
                best     = i;
            }
        }
        return best;
    }
}