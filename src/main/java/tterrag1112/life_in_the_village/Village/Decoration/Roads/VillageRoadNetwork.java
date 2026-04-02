package tterrag1112.life_in_the_village.Village.Decoration.Roads;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Planning.BuildingFootprint;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

/**
 * Manages the road network for a single village, handling initial
 * placement, expansion connections, and obsolete path weathering.
 *
 * <h3>Network model</h3>
 * The road network is a tree rooted at the village hub (town square center).
 * Each building entrance is a leaf node. Segments connect nodes with
 * straight-line or L-shaped centerlines.
 *
 * <h3>Expansion flow</h3>
 * When a new building is placed by the builder NPC:
 * <ol>
 *   <li>Find the nearest existing road node to the new entrance</li>
 *   <li>Route a new segment from that node to the entrance</li>
 *   <li>Place the road using {@link OrganicRoadPlacer}</li>
 *   <li>Register the new segment in the network</li>
 *   <li>Update the footprint grid with road reservations</li>
 * </ol>
 * This "branch and merge" approach means new roads connect to the
 * existing network organically rather than routing all the way back
 * to the hub.
 *
 * <h3>Obsolete path weathering</h3>
 * When the network is rebuilt (e.g. after multiple expansions), some
 * old road blocks may no longer be part of any active segment. These
 * are marked for weathering — the village weathering system gradually
 * replaces them with natural terrain blocks over multiple in-game days.
 *
 * <h3>Thread safety</h3>
 * This class is NOT thread-safe. All operations should happen on the
 * server thread during village spawn or expansion ticks.
 */
public class VillageRoadNetwork {

    // =========================================================================
    // Segment record
    // =========================================================================

    /**
     * A single road segment connecting two positions.
     */
    public record Segment(
            UUID segmentId,
            BlockPos from,
            BlockPos to,
            UUID fromBuildingId,   // null if from hub/intersection
            UUID toBuildingId,     // null if to hub/intersection
            List<BlockPos> centerline,
            List<BlockPos> allPlacedBlocks,
            RoadShape.RoadTier tier
    ) {}

    // =========================================================================
    // State
    // =========================================================================

    private final List<Segment> segments = new ArrayList<>();
    private final Map<UUID, BlockPos> buildingEntrances = new LinkedHashMap<>();
    private BlockPos hub;

    /** All XZ positions that are part of the active road network. */
    private final Set<Long> activeRoadXZ = new HashSet<>();

    // =========================================================================
    // Construction
    // =========================================================================

    public VillageRoadNetwork(BlockPos hub) {
        this.hub = hub;
    }

    public BlockPos getHub() { return hub; }
    public void setHub(BlockPos hub) { this.hub = hub; }

    // =========================================================================
    // Initial build — called during village spawn
    // =========================================================================

    /**
     * Builds the complete road network for a freshly spawned village.
     * Routes a road from the hub to each building entrance.
     *
     * @param level     server level
     * @param village   the village
     * @param data      saved data (for building lookup)
     * @param material  path material for this village's tier
     * @param tier      road width tier
     * @param footprint building collision grid
     * @param random    random source
     * @return set of all XZ positions where road blocks were placed
     */
    public Set<Long> buildInitialNetwork(ServerLevel level,
                                         Village village,
                                         VillageSavedData data,
                                         PathMaterial material,
                                         RoadShape.RoadTier tier,
                                         BuildingFootprint footprint,
                                         RandomSource random) {
        Set<Long> placedXZ = new HashSet<>();

        // Collect building entrances
        List<Building> buildings = village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        for (Building building : buildings) {
            BlockPos entrance = PathRouter.getBuildingEntrance(building);
            buildingEntrances.put(building.getId(), entrance);
        }

        // Sort by distance from hub so closer buildings connect first
        List<Map.Entry<UUID, BlockPos>> sorted = buildingEntrances.entrySet()
                .stream()
                .sorted(Comparator.comparingDouble(e ->
                        e.getValue().distSqr(hub)))
                .toList();

        for (Map.Entry<UUID, BlockPos> entry : sorted) {
            UUID buildingId = entry.getKey();
            BlockPos entrance = entry.getValue();

            // Find best connection point: nearest existing road position
            // or hub if this is the first road
            BlockPos branchFrom = findNearestConnection(entrance);

            // Route centerline
            List<BlockPos> centerline = OrganicRoadPlacer.routeCenterline(
                    branchFrom, entrance, level);

            if (centerline.isEmpty()) continue;

            // Place road
            OrganicRoadPlacer.PlacementResult result =
                    OrganicRoadPlacer.place(level, centerline, material,
                            tier, footprint, random);

            // Register segment
            Segment segment = new Segment(
                    UUID.randomUUID(),
                    branchFrom, entrance,
                    null, buildingId,
                    centerline,
                    result.placedBlocks(),
                    tier);
            segments.add(segment);

            // Update active road positions
            for (BlockPos pos : result.placedBlocks()) {
                long key = packXZ(pos.getX(), pos.getZ());
                activeRoadXZ.add(key);
                placedXZ.add(key);
            }

            // Reserve road space in footprint grid
            footprint.reserveRoad(centerline,
                    RoadShape.RoadTier.TOWN_ROAD.reservedHalfWidth());

            // Register as VillagePath for persistence
            data.addVillagePath(new VillagePath(
                    segment.segmentId(), village.getId(),
                    result.placedBlocks(), centerline,
                    toPathTier(tier)));
        }

        return placedXZ;
    }

    // =========================================================================
    // Expansion — connect a new building to existing network
    // =========================================================================

    /**
     * Connects a newly placed expansion building to the existing road
     * network. Finds the nearest road position and routes a new segment.
     *
     * @param level     server level
     * @param building  the new building
     * @param village   the village
     * @param data      saved data
     * @param material  path material
     * @param tier      road tier
     * @param footprint collision grid
     * @param random    random source
     */
    public void connectExpansionBuilding(ServerLevel level,
                                         Building building,
                                         Village village,
                                         VillageSavedData data,
                                         PathMaterial material,
                                         RoadShape.RoadTier tier,
                                         BuildingFootprint footprint,
                                         RandomSource random) {
        BlockPos entrance = PathRouter.getBuildingEntrance(building);
        buildingEntrances.put(building.getId(), entrance);

        // Find nearest existing road block to branch from
        BlockPos branchFrom = findNearestConnection(entrance);

        // Route
        List<BlockPos> centerline = OrganicRoadPlacer.routeCenterline(
                branchFrom, entrance, level);
        if (centerline.isEmpty()) return;

        // Place
        OrganicRoadPlacer.PlacementResult result =
                OrganicRoadPlacer.place(level, centerline, material,
                        tier, footprint, random);

        // Register
        Segment segment = new Segment(
                UUID.randomUUID(),
                branchFrom, entrance,
                null, building.getId(),
                centerline, result.placedBlocks(), tier);
        segments.add(segment);

        for (BlockPos pos : result.placedBlocks()) {
            activeRoadXZ.add(packXZ(pos.getX(), pos.getZ()));
        }

        footprint.reserveRoad(centerline,
                RoadShape.RoadTier.TOWN_ROAD.reservedHalfWidth());

        data.addVillagePath(new VillagePath(
                segment.segmentId(), village.getId(),
                result.placedBlocks(), centerline,
                toPathTier(tier)));

        data.setDirty();
    }

    // =========================================================================
    // Upgrade — resurface all roads with new material
    // =========================================================================

    /**
     * Upgrades all road segments to a new material and tier.
     * Preserves positions, only changes surface blocks.
     */
    public void upgradeAll(ServerLevel level,
                           Village village,
                           VillageSavedData data,
                           PathMaterial newMaterial,
                           RoadShape.RoadTier newTier,
                           BuildingFootprint footprint,
                           RandomSource random) {
        for (VillagePath path : data.getPathsForVillage(village.getId())) {
            OrganicRoadPlacer.upgrade(level, path, newMaterial,
                    newTier, footprint, random);
            path.setTier(toPathTier(newTier));
        }
        data.setDirty();
    }

    // =========================================================================
    // Obsolete path detection and weathering
    // =========================================================================

    /**
     * Identifies road blocks that are no longer part of any active segment.
     * These blocks are candidates for gradual weathering back to terrain.
     *
     * @param village the village
     * @param data    saved data
     * @return set of BlockPos that should weather away
     */
    public List<BlockPos> findObsoleteBlocks(Village village,
                                             VillageSavedData data) {
        // Rebuild active set from current segments
        Set<Long> currentActive = new HashSet<>();
        for (VillagePath path : data.getPathsForVillage(village.getId())) {
            for (BlockPos pos : path.getBlocks()) {
                currentActive.add(packXZ(pos.getX(), pos.getZ()));
            }
        }

        // Any position in the old active set but not the current is obsolete
        List<BlockPos> obsolete = new ArrayList<>();
        // This would come from a previously stored "all ever placed" set
        // For now, we return empty — the weathering system handles this
        // by checking if a road block is still registered in any VillagePath
        return obsolete;
    }

    /**
     * Weathers a single obsolete road block one step toward natural terrain.
     * Called by the village weathering system over multiple ticks.
     *
     * <h3>Weathering stages</h3>
     * <pre>
     *   stone_brick → cobblestone → gravel → coarse_dirt → grass_block
     * </pre>
     *
     * Each call advances one stage. The block is fully restored to natural
     * terrain after 4 weathering passes.
     */
    public static void weatherBlock(ServerLevel level, BlockPos pos) {
        int surfY = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getX(), pos.getZ());
        BlockPos surface = new BlockPos(pos.getX(), surfY - 1, pos.getZ());
        BlockState current = level.getBlockState(surface);

        BlockState next = weatherTransition(current);
        if (next != null) {
            level.setBlock(surface, next, 3);
        }
    }

    private static BlockState weatherTransition(BlockState current) {
        if (current.is(Blocks.STONE_BRICKS) || current.is(Blocks.POLISHED_ANDESITE))
            return Blocks.COBBLESTONE.defaultBlockState();
        if (current.is(Blocks.COBBLESTONE) || current.is(Blocks.STONE)
                || current.is(Blocks.ANDESITE))
            return Blocks.GRAVEL.defaultBlockState();
        if (current.is(Blocks.GRAVEL))
            return Blocks.COARSE_DIRT.defaultBlockState();
        if (current.is(Blocks.COARSE_DIRT) || current.is(Blocks.DIRT_PATH)
                || current.is(Blocks.DIRT))
            return Blocks.GRASS_BLOCK.defaultBlockState();

        // Desert variants
        if (current.is(Blocks.SMOOTH_SANDSTONE) || current.is(Blocks.SANDSTONE))
            return Blocks.SAND.defaultBlockState();

        return null; // already natural terrain
    }

    // =========================================================================
    // Connection finding
    // =========================================================================

    /**
     * Finds the nearest connection point for a new road segment.
     * Prefers existing road centerline positions over the hub.
     */
    private BlockPos findNearestConnection(BlockPos target) {
        if (segments.isEmpty()) return hub;

        BlockPos nearest = hub;
        double nearestDist = hub.distSqr(target);

        for (Segment seg : segments) {
            for (BlockPos center : seg.centerline()) {
                double d = center.distSqr(target);
                if (d < nearestDist) {
                    nearestDist = d;
                    nearest = center;
                }
            }
        }

        return nearest;
    }

    // =========================================================================
    // Queries
    // =========================================================================

    /** Returns all segments in the network. */
    public List<Segment> getSegments() {
        return Collections.unmodifiableList(segments);
    }

    /** Returns the entrance position for a building, or null. */
    public BlockPos getEntrance(UUID buildingId) {
        return buildingEntrances.get(buildingId);
    }

    /** Returns the set of all active road XZ positions. */
    public Set<Long> getActiveRoadXZ() {
        return Collections.unmodifiableSet(activeRoadXZ);
    }

    /** Tests if an XZ position is part of the active road network. */
    public boolean isRoadAt(int x, int z) {
        return activeRoadXZ.contains(packXZ(x, z));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static long packXZ(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    private static VillagePath.PathTier toPathTier(RoadShape.RoadTier tier) {
        return switch (tier) {
            case FOOTPATH      -> VillagePath.PathTier.DIRT;
            case VILLAGE_PATH  -> VillagePath.PathTier.GRAVEL;
            case VILLAGE_ROAD  -> VillagePath.PathTier.COBBLESTONE;
            case TOWN_ROAD, CAPITAL_ROAD -> VillagePath.PathTier.STONE_BRICK;
        };
    }
}