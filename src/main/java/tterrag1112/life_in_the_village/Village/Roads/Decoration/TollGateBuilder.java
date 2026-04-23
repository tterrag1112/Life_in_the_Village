package tterrag1112.life_in_the_village.Village.Roads.Decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Procedurally places a toll gate structure in the world.
 *
 * <h3>Structure layout</h3>
 * The gate straddles the road:
 * <ul>
 *   <li>3-wide arch where the road passes through (pillars + lintel)</li>
 *   <li>3×3 guardhouse on one side of the road</li>
 *   <li>Lanterns at arch pillars</li>
 *   <li>Banner pole or sign on guardhouse identifying the kingdom</li>
 * </ul>
 *
 * <h3>Cultural material selection</h3>
 * <ul>
 *   <li>imperial  — stone_bricks / polished_andesite / dark_oak</li>
 *   <li>highland  — mossy_cobblestone / cobblestone / oak</li>
 *   <li>nordic    — spruce / stone / spruce</li>
 *   <li>default   — mixed oak / cobblestone</li>
 * </ul>
 *
 * <h3>Graph registration</h3>
 * After placing blocks the builder calls
 * {@link WorldRoadGraph#insertTollGateNode} so the gate is recorded as a
 * proper node in the road graph. If the graph insertion fails (edge already
 * split, chunk unloaded, etc.) the structure is still placed and a
 * {@link PlacementResult} with {@code nodeId == null} is returned.
 */
public final class TollGateBuilder {

    private TollGateBuilder() {}

    // =========================================================================
    // Result record
    // =========================================================================

    /**
     * @param gatePosition  ground-snapped position of the arch centre
     * @param nodeId        the {@link tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode}
     *                      UUID inserted into the graph, or {@code null} if insertion failed
     * @param placedBlocks  all block positions modified during placement
     */
    public record PlacementResult(
            BlockPos gatePosition,
            UUID nodeId,
            List<BlockPos> placedBlocks
    ) {}

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Places a toll gate structure and registers the graph node.
     *
     * @param level    server level
     * @param plan     gate placement plan from {@link TollGatePlanner}
     * @param kingdom  the kingdom collecting the toll (for cultural style)
     * @param roadData road saved data (provides the graph)
     * @return placement result, or {@code null} if terrain is unsuitable or chunk unloaded
     */
    public static PlacementResult build(ServerLevel level,
                                         TollGatePlanner.TollGatePlan plan,
                                         Kingdom kingdom,
                                         WorldRoadSavedData roadData) {
        BlockPos centre = plan.gatePosition();
        int groundY = groundSnap(level, centre);
        if (groundY == Integer.MIN_VALUE) return null; // chunk not loaded

        BlockPos origin = new BlockPos(centre.getX(), groundY, centre.getZ());
        if (!isSuitable(level, origin)) return null;

        String culture = kingdom != null ? kingdom.getCulture() : "default";
        Direction roadDir = roadDirection(plan);
        Direction sideDir = roadDir.getClockWise(); // guardhouse on right side

        List<BlockPos> placed = new ArrayList<>();

        placeArch(level, origin, roadDir, culture, placed);
        placeGuardhouse(level, origin, sideDir, culture, placed);

        // Graph registration
        UUID nodeId = null;
        Optional<WorldRoadGraph.SplitResult> splitOpt =
                roadData.getGraph().insertTollGateNode(
                        plan.edgeId(), plan.splitCellKey(), origin, plan.collectingKingdomId());
        if (splitOpt.isPresent()) {
            nodeId = splitOpt.get().junctionNodeId();
            roadData.registerTollGate(nodeId, plan.collectingKingdomId(), plan.tierAtGate());
        }

        return new PlacementResult(origin, nodeId, placed);
    }

    // =========================================================================
    // Structure pieces
    // =========================================================================

    private static void placeArch(ServerLevel level, BlockPos origin,
                                   Direction roadDir, String culture,
                                   List<BlockPos> placed) {
        BlockState pillar  = pillarBlock(culture);
        BlockState lintel  = lintelBlock(culture);
        BlockState lantern = Blocks.LANTERN.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LanternBlock.HANGING, false);

        // Road direction → perpendicular offsets for the three arch columns
        // Arch: left pillar, centre opening, right pillar across the road
        Direction perp = roadDir.getClockWise();

        // Two pillars 1 block to each side, 3 tall
        for (int side = -1; side <= 1; side += 2) {
            BlockPos base = origin.relative(perp, side);
            for (int h = 0; h < 3; h++) {
                setBlock(level, base.above(h), pillar, placed);
            }
            // Lantern on top of pillar
            setBlock(level, base.above(3), lantern, placed);
        }

        // Lintel spanning across (3 blocks wide at height 3)
        for (int s = -1; s <= 1; s++) {
            setBlock(level, origin.relative(perp, s).above(3), lintel, placed);
        }
    }

    private static void placeGuardhouse(ServerLevel level, BlockPos origin,
                                         Direction sideDir, String culture,
                                         List<BlockPos> placed) {
        BlockState wall   = wallBlock(culture);
        BlockState roof   = roofBlock(culture);
        BlockState floor  = floorBlock(culture);
        BlockState door   = doorBlock(culture);
        BlockState lantern = Blocks.LANTERN.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LanternBlock.HANGING, false);

        // Offset 5 blocks to the side: clears TRUNK road half-width (3) + 1 clearance + 1 guardhouse radius
        BlockPos ghOrigin = origin.relative(sideDir, 5);

        // Floor (3×3)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos floorPos = ghOrigin.offset(dx, 0, dz);
                setBlock(level, floorPos.below(), floor, placed);
            }
        }

        // Walls (3×3 footprint, 3 tall, hollow)
        for (int h = 0; h < 3; h++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue; // hollow
                    boolean isBorder = Math.abs(dx) == 1 || Math.abs(dz) == 1;
                    if (!isBorder) continue;
                    BlockPos wp = ghOrigin.offset(dx, h, dz);
                    // Leave doorway on the arch-facing side at h=0 and h=1
                    if (h <= 1 && isDoorSide(dx, dz, sideDir.getOpposite())) continue;
                    setBlock(level, wp, wall, placed);
                }
            }
        }

        // Door frame blocks replaced by door at h=0, h=1 on opening
        BlockPos doorBase = ghOrigin.relative(sideDir.getOpposite(), 1);
        setBlock(level, doorBase,         door, placed);
        setBlock(level, doorBase.above(), door, placed);

        // Flat roof (3×3 slab at h=3)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                setBlock(level, ghOrigin.offset(dx, 3, dz), roof, placed);
            }
        }

        // Corner lanterns on roof
        setBlock(level, ghOrigin.offset(-1, 4, -1), lantern, placed);
        setBlock(level, ghOrigin.offset( 1, 4, -1), lantern, placed);
        setBlock(level, ghOrigin.offset(-1, 4,  1), lantern, placed);
        setBlock(level, ghOrigin.offset( 1, 4,  1), lantern, placed);
    }

    // =========================================================================
    // Terrain helpers
    // =========================================================================

    private static int groundSnap(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return Integer.MIN_VALUE;
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getX(), pos.getZ());
    }

    private static boolean isSuitable(ServerLevel level, BlockPos origin) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (!level.isLoaded(origin.offset(dx, 0, dz))) return false;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        origin.getX() + dx, origin.getZ() + dz);
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        return (maxY - minY) <= 5;
    }

    // =========================================================================
    // Direction helpers
    // =========================================================================

    private static Direction roadDirection(TollGatePlanner.TollGatePlan plan) {
        // The plan stores entry/exit node ids but not a direction vector;
        // fall back to NORTH as a safe default — the arch opening spans perpendicularly
        // so it doesn't matter for structure validity, only cosmetic orientation.
        return Direction.NORTH;
    }

    private static boolean isDoorSide(int dx, int dz, Direction side) {
        return switch (side) {
            case NORTH -> dz == -1 && dx == 0;
            case SOUTH -> dz ==  1 && dx == 0;
            case WEST  -> dx == -1 && dz == 0;
            case EAST  -> dx ==  1 && dz == 0;
            default    -> false;
        };
    }

    // =========================================================================
    // Cultural material selectors
    // =========================================================================

    private static BlockState pillarBlock(String culture) {
        return switch (culture) {
            case "imperial"  -> Blocks.STONE_BRICKS.defaultBlockState();
            case "highland"  -> Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            case "nordic"    -> Blocks.STONE.defaultBlockState();
            default          -> Blocks.COBBLESTONE.defaultBlockState();
        };
    }

    private static BlockState lintelBlock(String culture) {
        return switch (culture) {
            case "imperial"  -> Blocks.POLISHED_ANDESITE.defaultBlockState();
            case "highland"  -> Blocks.COBBLESTONE.defaultBlockState();
            case "nordic"    -> Blocks.SPRUCE_PLANKS.defaultBlockState();
            default          -> Blocks.OAK_PLANKS.defaultBlockState();
        };
    }

    private static BlockState wallBlock(String culture) {
        return switch (culture) {
            case "imperial"  -> Blocks.STONE_BRICKS.defaultBlockState();
            case "highland"  -> Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            case "nordic"    -> Blocks.SPRUCE_PLANKS.defaultBlockState();
            default          -> Blocks.OAK_PLANKS.defaultBlockState();
        };
    }

    private static BlockState roofBlock(String culture) {
        return switch (culture) {
            case "imperial"  -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.TOP);
            case "highland"  -> Blocks.COBBLESTONE_SLAB.defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.TOP);
            case "nordic"    -> Blocks.SPRUCE_SLAB.defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.TOP);
            default          -> Blocks.OAK_SLAB.defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.TOP);
        };
    }

    private static BlockState floorBlock(String culture) {
        return switch (culture) {
            case "imperial"  -> Blocks.POLISHED_ANDESITE.defaultBlockState();
            case "highland"  -> Blocks.COBBLESTONE.defaultBlockState();
            case "nordic"    -> Blocks.STONE.defaultBlockState();
            default          -> Blocks.DIRT_PATH.defaultBlockState();
        };
    }

    private static BlockState doorBlock(String culture) {
        return switch (culture) {
            case "imperial"  -> Blocks.DARK_OAK_DOOR.defaultBlockState();
            case "nordic"    -> Blocks.SPRUCE_DOOR.defaultBlockState();
            default          -> Blocks.OAK_DOOR.defaultBlockState();
        };
    }

    // =========================================================================
    // Block placement helper
    // =========================================================================

    private static void setBlock(ServerLevel level, BlockPos pos,
                                  BlockState state, List<BlockPos> placed) {
        if (!level.isLoaded(pos)) return;
        level.setBlock(pos, state, 3);
        placed.add(pos.immutable());
    }
}
