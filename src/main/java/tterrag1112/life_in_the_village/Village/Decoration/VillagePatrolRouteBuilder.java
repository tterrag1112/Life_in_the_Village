// src/main/java/tterrag1112/life_in_the_village/Village/Decoration/VillagePatrolRouteBuilder.java
package tterrag1112.life_in_the_village.Village.Decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.PathRouter;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

/**
 * Builds the patrol circuit for a village's guard NPCs.
 *
 * <h3>Route strategy</h3>
 * <ol>
 *   <li>Compute the village perimeter circle (same radius used by
 *       {@link VillagePerimeter}) at the outermost building ring.</li>
 *   <li>Sample that circle at regular intervals (every 16 blocks of
 *       arc length) to produce ordered waypoints.</li>
 *   <li>Snap each waypoint to the live surface so guards don't float
 *       or clip underground.</li>
 *   <li>Register guard posts at watchtower and guard-tower building
 *       entrances — guards assigned a post patrol a small radius
 *       around it rather than the full circuit.</li>
 * </ol>
 *
 * <p>Waypoints are stored on {@link Village} so they persist across
 * restarts without recomputing.
 */
public class VillagePatrolRouteBuilder {

    /** Arc distance between perimeter waypoints. */
    private static final int WAYPOINT_SPACING = 16;
    /** Offset outward from outermost building to perimeter patrol line. */
    private static final int PATROL_OFFSET    = 6;

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Generates and stores patrol waypoints and guard posts for the village.
     * Call from {@link
     * tterrag1112.life_in_the_village.Village.Decoration.VillageDecorator}
     * after all buildings are placed.
     */
    public static void build(ServerLevel level,
                             Village village,
                             VillageSavedData data) {
        List<Building> buildings = village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        if (buildings.isEmpty()) return;

        // ── Step 1: Register guard posts at defensive buildings ───────────────
        registerGuardPosts(level, village, buildings);

        // ── Step 2: Build perimeter waypoints ─────────────────────────────────
        List<BlockPos> waypoints = buildPerimeterWaypoints(
                level, village, data, buildings);

        village.setPatrolWaypoints(waypoints);
        data.setDirty();

        System.out.println("VillagePatrolRouteBuilder: "
                + waypoints.size() + " patrol waypoints, "
                + village.getGuardPosts().size() + " guard posts for "
                + village.getName());
    }

    // =========================================================================
    // Guard post registration
    // =========================================================================

    /**
     * Registers the entrance of each watchtower and guard tower as a
     * guard post. Guards assigned a post stand near it rather than
     * walking the full perimeter circuit.
     */
    private static void registerGuardPosts(ServerLevel level,
                                           Village village,
                                           List<Building> buildings) {
        // Clear stale posts first (in case of re-decoration)
        buildings.stream()
                .filter(b -> b.getType() == BuildingType.WATCHTOWER
                        || b.getType() == BuildingType.GUARD_TOWER)
                .forEach(b -> {
                    BlockPos entrance = PathRouter.getBuildingEntrance(b);
                    // Snap to live surface
                    int surfY = level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            entrance.getX(), entrance.getZ());
                    BlockPos post = new BlockPos(
                            entrance.getX(), surfY + 1, entrance.getZ());
                    village.addGuardPost(post);
                });

        // Also add the perimeter gate positions as secondary posts
        // (guards can be stationed at village entrances)
        buildings.stream()
                .filter(b -> b.getType() == BuildingType.BARRACKS)
                .findFirst()
                .ifPresent(barracks -> {
                    // Barracks entrance is always a valid fallback post
                    BlockPos entrance = PathRouter.getBuildingEntrance(barracks);
                    int surfY = level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            entrance.getX(), entrance.getZ());
                    village.addGuardPost(
                            new BlockPos(entrance.getX(), surfY + 1,
                                    entrance.getZ()));
                });
    }

    // =========================================================================
    // Perimeter waypoint generation
    // =========================================================================

    private static List<BlockPos> buildPerimeterWaypoints(
            ServerLevel level,
            Village village,
            VillageSavedData data,
            List<Building> buildings) {

        // Find centroid of all buildings
        int cx = 0, cz = 0;
        for (Building b : buildings) {
            cx += b.getShape().getOrigin().getX()
                    + b.getShape().getWidth() / 2;
            cz += b.getShape().getOrigin().getZ()
                    + b.getShape().getLength() / 2;
        }
        cx /= buildings.size();
        cz /= buildings.size();

        // Find outermost building radius
        int maxRadius = 0;
        for (Building b : buildings) {
            int bCx = b.getShape().getOrigin().getX()
                    + b.getShape().getWidth() / 2;
            int bCz = b.getShape().getOrigin().getZ()
                    + b.getShape().getLength() / 2;
            int r = (int) Math.sqrt(
                    Math.pow(bCx - cx, 2) + Math.pow(bCz - cz, 2))
                    + Math.max(b.getShape().getWidth(),
                    b.getShape().getLength()) / 2;
            if (r > maxRadius) maxRadius = r;
        }

        int patrolRadius = maxRadius + PATROL_OFFSET;
        int circumference = (int)(2 * Math.PI * patrolRadius);
        int waypointCount = Math.max(6, circumference / WAYPOINT_SPACING);

        List<BlockPos> waypoints = new ArrayList<>();

        for (int i = 0; i < waypointCount; i++) {
            double angle = 2 * Math.PI * i / waypointCount;
            int wx = cx + (int)(Math.cos(angle) * patrolRadius);
            int wz = cz + (int)(Math.sin(angle) * patrolRadius);

            // Snap to live walkable surface
            int surfY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz);

            // Skip waypoints that are in water or too far below/above
            // the village base — these aren't walkable
            BlockState below = level.getBlockState(
                    new BlockPos(wx, surfY - 1, wz));
            if (below.liquid()) continue;

            int baseY = village.getVillageCentre() != null
                    ? village.getVillageCentre().getY()
                    : surfY;
            if (Math.abs(surfY - baseY) > 24) continue;

            waypoints.add(new BlockPos(wx, surfY + 1, wz));
        }

        return waypoints;
    }
}