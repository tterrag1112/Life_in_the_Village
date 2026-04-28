package tterrag1112.life_in_the_village.Village.Decoration.Subbuilding;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Blocks.Entity.custom.SubBuildingAnchorBlockEntity;
import tterrag1112.life_in_the_village.Village.Building;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Doc 03 §"Detection by anchor block entities" — building-placement-time
 * sweep that finds {@link SubBuildingAnchorBlockEntity} instances inside
 * a parent building's bounding box and resolves each one to a
 * {@link SubBuilding} record.
 *
 * <p>Single entry point: {@link #scan(Building, ServerLevel)}.</p>
 *
 * <p>Per-anchor flow:</p>
 * <ol>
 *   <li>Read the anchor's {@link SubBuildingAnchorBlockEntity.State} —
 *       type, optional explicit bounds, optional explicit door target,
 *       free-form hints.</li>
 *   <li>Compute bounds: explicit override if present, otherwise
 *       flood-fill from the anchor capped at
 *       {@link #FLOOD_FILL_CAP} cells, clamped to parent bbox.</li>
 *   <li>Compute door target: explicit override if present, otherwise
 *       scan the bounded region for any {@link DoorBlock} and pick the
 *       one closest to the anchor; on no-door fall back to the anchor
 *       itself with the parent's front face as
 *       {@code doorFacing}.</li>
 *   <li>Build a {@link SubBuilding} record with a deterministic UUID
 *       derived from the parent UUID + anchor position.</li>
 *   <li>Replace the anchor block with air so it doesn't persist.</li>
 * </ol>
 */
public final class SubBuildingScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubBuildingScanner.class);

    /** Doc 03 §"Bounds resolution" — flood-fill cap per anchor. */
    public static final int FLOOD_FILL_CAP = 256;

    /** Search radius (in blocks) around an anchor for a door, when the
     *  anchor's bounds have been resolved. Doors at most this many cells
     *  away from the anchor count as the entrance. */
    private static final int DOOR_SEARCH_PAD = 1;

    private SubBuildingScanner() {}

    /**
     * Walks the parent building's bounding box for anchor block entities
     * and returns the resolved subbuildings in deterministic order
     * (sorted by anchor position: Y → Z → X). Anchor blocks are replaced
     * with air as part of the scan.
     */
    public static List<SubBuilding> scan(Building parent, ServerLevel level) {
        if (parent == null || level == null) return List.of();

        Building.BuildingShape shape = parent.getShape();
        BlockPos min = shape.getMin();
        BlockPos max = shape.getMax();

        List<AnchorHit> anchors = collectAnchors(level, min, max);
        if (anchors.isEmpty()) return List.of();

        // Determinism: sort by (Y, Z, X) before processing so subbuilding
        // UUIDs are derived in a stable order.
        anchors.sort(Comparator
                .<AnchorHit>comparingInt(a -> a.pos.getY())
                .thenComparingInt(a -> a.pos.getZ())
                .thenComparingInt(a -> a.pos.getX()));

        Direction parentFront = frontFacingOf(parent);
        long createdTick = level.getGameTime();
        List<SubBuilding> results = new ArrayList<>(anchors.size());
        Set<SubBuildingType> doorlessLogged = EnumSet.noneOf(SubBuildingType.class);

        for (AnchorHit hit : anchors) {
            SubBuildingAnchorBlockEntity.State state = hit.entity.getState();

            // Bounds.
            BlockPos[] bounds = resolveBounds(level, hit.pos, state, min, max);
            BlockPos bMin = bounds[0];
            BlockPos bMax = bounds[1];

            // Door.
            DoorResolution door = resolveDoor(level, hit.pos, state, bMin, bMax,
                    parentFront);
            if (door.fellBack && doorlessLogged.add(state.type())) {
                LOGGER.info(
                        "SubBuildingScanner: parent {} ({}) has a {} anchor "
                                + "at {} with no door — door target falls back "
                                + "to the anchor itself.",
                        parent.getId(), parent.getName(), state.type(),
                        hit.pos.toShortString());
            }

            UUID subId = deterministicId(parent.getId(), hit.pos);

            SubBuilding sub = new SubBuilding(
                    subId,
                    parent.getId(),
                    state.type(),
                    hit.pos,
                    bMin,
                    bMax,
                    door.target,
                    door.facing,
                    List.of(),
                    createdTick);
            results.add(sub);

            // Replace the anchor with air. The block entity is removed
            // automatically by the block-state change.
            level.setBlock(hit.pos, Blocks.AIR.defaultBlockState(), 3);
        }

        return results;
    }

    // ── Anchor collection ───────────────────────────────────────────────

    /** A single anchor hit inside the parent footprint. */
    private record AnchorHit(BlockPos pos, SubBuildingAnchorBlockEntity entity) {}

    private static List<AnchorHit> collectAnchors(ServerLevel level,
                                                  BlockPos min, BlockPos max) {
        // Walk the chunks the parent bbox overlaps, then filter each
        // chunk's block-entity map by anchor type and parent bbox.
        // O(anchors) rather than O(volume).
        int cxMin = min.getX() >> 4;
        int czMin = min.getZ() >> 4;
        int cxMax = max.getX() >> 4;
        int czMax = max.getZ() >> 4;

        List<AnchorHit> hits = new ArrayList<>();
        for (int cx = cxMin; cx <= cxMax; cx++) {
            for (int cz = czMin; cz <= czMax; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                for (var entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos pos = entry.getKey();
                    if (pos.getX() < min.getX() || pos.getX() > max.getX()) continue;
                    if (pos.getY() < min.getY() || pos.getY() > max.getY()) continue;
                    if (pos.getZ() < min.getZ() || pos.getZ() > max.getZ()) continue;
                    if (entry.getValue() instanceof SubBuildingAnchorBlockEntity be) {
                        hits.add(new AnchorHit(pos.immutable(), be));
                    }
                }
            }
        }
        return hits;
    }

    // ── Bounds resolution ───────────────────────────────────────────────

    /**
     * Returns {@code {min, max}} for the subbuilding region. Uses the
     * anchor's explicit override if present; otherwise flood-fills from
     * the anchor through air / replaceable blocks, clamped to the parent
     * bbox and capped at {@link #FLOOD_FILL_CAP} cells.
     */
    private static BlockPos[] resolveBounds(ServerLevel level,
                                            BlockPos anchorPos,
                                            SubBuildingAnchorBlockEntity.State state,
                                            BlockPos parentMin,
                                            BlockPos parentMax) {
        if (state.explicitMin() != null) {
            // Both fields are guaranteed non-null together by State's
            // canonical constructor.
            BlockPos eMin = clamp(state.explicitMin(), parentMin, parentMax);
            BlockPos eMax = clamp(state.explicitMax(), parentMin, parentMax);
            return normalize(eMin, eMax);
        }

        return floodFillBounds(level, anchorPos, parentMin, parentMax);
    }

    private static BlockPos[] floodFillBounds(ServerLevel level,
                                              BlockPos start,
                                              BlockPos parentMin,
                                              BlockPos parentMax) {
        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);

        int minX = start.getX(), minY = start.getY(), minZ = start.getZ();
        int maxX = minX, maxY = minY, maxZ = minZ;

        while (!queue.isEmpty() && visited.size() <= FLOOD_FILL_CAP) {
            BlockPos cur = queue.poll();
            minX = Math.min(minX, cur.getX());
            minY = Math.min(minY, cur.getY());
            minZ = Math.min(minZ, cur.getZ());
            maxX = Math.max(maxX, cur.getX());
            maxY = Math.max(maxY, cur.getY());
            maxZ = Math.max(maxZ, cur.getZ());

            for (Direction d : Direction.values()) {
                BlockPos next = cur.relative(d);
                if (visited.contains(next)) continue;
                if (next.getX() < parentMin.getX() || next.getX() > parentMax.getX()) continue;
                if (next.getY() < parentMin.getY() || next.getY() > parentMax.getY()) continue;
                if (next.getZ() < parentMin.getZ() || next.getZ() > parentMax.getZ()) continue;
                if (!isPassable(level, next)) continue;
                visited.add(next);
                queue.add(next);
            }
        }

        return new BlockPos[]{
                new BlockPos(minX, minY, minZ),
                new BlockPos(maxX, maxY, maxZ)
        };
    }

    /**
     * Flood-fill passable: air or any non-solid block. Walls (solid
     * blocks) terminate the fill. Doors are treated as walls — they
     * delineate the exterior of the room.
     */
    private static boolean isPassable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return true;
        Block block = state.getBlock();
        if (block instanceof DoorBlock) return false;
        // Non-solid (carpet, signs, banners, torches) is treated as
        // interior space; any "isCollisionShapeFullBlock"-true block
        // counts as a wall.
        return !state.isCollisionShapeFullBlock(level, pos);
    }

    // ── Door resolution ─────────────────────────────────────────────────

    private record DoorResolution(BlockPos target, Direction facing,
                                  boolean fellBack) {}

    private static DoorResolution resolveDoor(ServerLevel level,
                                              BlockPos anchorPos,
                                              SubBuildingAnchorBlockEntity.State state,
                                              BlockPos bMin, BlockPos bMax,
                                              Direction parentFront) {
        if (state.explicitDoorTarget() != null) {
            BlockPos target = state.explicitDoorTarget();
            BlockState bs = level.getBlockState(target);
            Direction facing = doorFacing(bs).orElse(parentFront);
            return new DoorResolution(target, facing, false);
        }

        // Closest DoorBlock within the bounded region (with a small pad
        // outward) wins.
        BlockPos searchMin = bMin.offset(-DOOR_SEARCH_PAD, -DOOR_SEARCH_PAD, -DOOR_SEARCH_PAD);
        BlockPos searchMax = bMax.offset(DOOR_SEARCH_PAD, DOOR_SEARCH_PAD, DOOR_SEARCH_PAD);
        BlockPos best = null;
        Direction bestFacing = parentFront;
        int bestDistSq = Integer.MAX_VALUE;
        for (int x = searchMin.getX(); x <= searchMax.getX(); x++) {
            for (int y = searchMin.getY(); y <= searchMax.getY(); y++) {
                for (int z = searchMin.getZ(); z <= searchMax.getZ(); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState bs = level.getBlockState(p);
                    if (!(bs.getBlock() instanceof DoorBlock)) continue;
                    int dx = p.getX() - anchorPos.getX();
                    int dy = p.getY() - anchorPos.getY();
                    int dz = p.getZ() - anchorPos.getZ();
                    int distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = p.immutable();
                        bestFacing = doorFacing(bs).orElse(parentFront);
                    }
                }
            }
        }

        if (best == null) {
            return new DoorResolution(anchorPos, parentFront, true);
        }
        return new DoorResolution(best, bestFacing, false);
    }

    /** Door's outward facing if the block has a HORIZONTAL_FACING property. */
    private static java.util.Optional<Direction> doorFacing(BlockState state) {
        if (!(state.getBlock() instanceof DoorBlock)) return java.util.Optional.empty();
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return java.util.Optional.of(state.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
        return java.util.Optional.empty();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Front face convention: rotation NONE → SOUTH-facing, matching
     *  building placer + AdjunctPlotPlacer. */
    private static Direction frontFacingOf(Building b) {
        return switch (b.getRotation()) {
            case NONE -> Direction.SOUTH;
            case CLOCKWISE_90 -> Direction.WEST;
            case CLOCKWISE_180 -> Direction.NORTH;
            case COUNTERCLOCKWISE_90 -> Direction.EAST;
        };
    }

    private static BlockPos clamp(BlockPos p, BlockPos min, BlockPos max) {
        return new BlockPos(
                Math.max(min.getX(), Math.min(max.getX(), p.getX())),
                Math.max(min.getY(), Math.min(max.getY(), p.getY())),
                Math.max(min.getZ(), Math.min(max.getZ(), p.getZ())));
    }

    private static BlockPos[] normalize(BlockPos a, BlockPos b) {
        return new BlockPos[]{
                new BlockPos(
                        Math.min(a.getX(), b.getX()),
                        Math.min(a.getY(), b.getY()),
                        Math.min(a.getZ(), b.getZ())),
                new BlockPos(
                        Math.max(a.getX(), b.getX()),
                        Math.max(a.getY(), b.getY()),
                        Math.max(a.getZ(), b.getZ()))
        };
    }

    private static UUID deterministicId(UUID parentId, BlockPos anchorPos) {
        String seed = parentId.toString() + "/anchor/"
                + anchorPos.getX() + "," + anchorPos.getY()
                + "," + anchorPos.getZ();
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
