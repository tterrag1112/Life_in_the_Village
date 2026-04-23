package tterrag1112.life_in_the_village.Village.Roads.Decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;

import java.util.List;

/**
 * Places atmospheric overgrowth on and around road edges based on maintenance level.
 *
 * <h3>Maintenance thresholds</h3>
 * <ul>
 *   <li>80–100: no overgrowth</li>
 *   <li>60–79: 5% density — occasional short_grass alongside edges</li>
 *   <li>40–59: 15% density — short_grass, tall_grass, occasional oak_sapling</li>
 *   <li>20–39: 35% density — dense grass, sapling clusters, fallen logs every ~80 blocks</li>
 *   <li>0–19: 60% density + road surface degradation (coarse_dirt patches)</li>
 * </ul>
 *
 * <h3>Biome variation</h3>
 * Plains/forest: oak saplings, tall_grass, oak_leaves.
 * Desert: dead_bush, sand drifts.
 * Swamp: oak_leaves, moss_block.
 * Snowy: snow_layer accumulation.
 *
 * <h3>Fallen logs</h3>
 * At maintenance &lt; 40, one stripped log is placed every ~80 blocks across the road,
 * with axis perpendicular to the road direction. Log species matches biome.
 *
 * <p>All placed positions are recorded in {@link RoadEdge#getDecorationPositions()}.
 */
public final class RoadOvergrowthDecorator {

    /** Blocks either side of centerline to place overgrowth. */
    private static final int LATERAL_RADIUS = 3;
    /** Spacing between fallen logs (blocks of path length). */
    private static final int LOG_SPACING = 80;

    private RoadOvergrowthDecorator() {}

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void decorate(ServerLevel level, RoadEdge edge, WorldRoadGraph graph) {
        List<BlockPos> path = edge.getBlockPath();
        if (path.size() < 2) return;

        int maintenance = edge.getMaintenance();
        if (maintenance >= 80) return; // pristine roads get no overgrowth

        float density     = densityForMaintenance(maintenance);
        boolean degradeSurface = maintenance < 20;
        boolean placeLogs      = maintenance < 40;

        // Deterministic "random" seed from edge id + maintenance
        long seed = edge.getEdgeId().getLeastSignificantBits() ^ (long) maintenance;

        VillageBiomeStyle biome = VillageBiomeStyle.detect(level, path.get(path.size() / 2));

        int accLen     = 0;
        int nextLog    = LOG_SPACING;

        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i - 1);
            BlockPos b = path.get(i);
            accLen += blockDist(a, b);

            // Perpendicular direction
            int dx = b.getX() - a.getX();
            int dz = b.getZ() - a.getZ();

            // Place lateral overgrowth
            for (int off = 2; off <= LATERAL_RADIUS; off++) {
                for (int side = -1; side <= 1; side += 2) {
                    // Skip if density roll fails (deterministic by position)
                    long h = posHash(b.getX() + dx * off, b.getZ() + dz * off) ^ seed;
                    if ((h & 0x7FL) / 128.0f > density) continue;

                    int ox = perpX(dx, dz, off * side);
                    int oz = perpZ(dx, dz, off * side);
                    int nx = b.getX() + ox;
                    int nz = b.getZ() + oz;
                    if (!level.isLoaded(new BlockPos(nx, 0, nz))) continue;
                    int ny = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz);
                    BlockPos target = new BlockPos(nx, ny, nz);

                    // Skip if the block is already a road material or water
                    BlockState existing = level.getBlockState(target);
                    if (!existing.isAir() && !isReplaceableVegetation(existing)) continue;

                    BlockState overgrowth = pickOvergrowth(h, biome, maintenance, off);
                    if (overgrowth == null) continue;

                    // Skip if soil below is not plantable (for vegetation blocks)
                    if (isVegetation(overgrowth) && !isPlantable(level.getBlockState(target.below()))) continue;

                    level.setBlock(target, overgrowth, 3);
                    edge.addDecorationPosition(target);
                }
            }

            // Road surface degradation at extreme decay
            if (degradeSurface) {
                long sh = posHash(b.getX(), b.getZ()) ^ seed ^ 0xBADFL;
                if ((sh & 0x7L) == 0) { // ~12.5% of centerline blocks
                    BlockState cur = level.getBlockState(b);
                    if (isRoadSurface(cur)) {
                        level.setBlock(b, Blocks.COARSE_DIRT.defaultBlockState(), 3);
                        edge.addDecorationPosition(b.immutable());
                    }
                }
            }

            // Fallen log across the road
            if (placeLogs && accLen >= nextLog) {
                placeFallenLog(level, b, dx, dz, biome, edge);
                accLen  = 0;
                nextLog = LOG_SPACING;
            }
        }
    }

    // =========================================================================
    // Fallen log
    // =========================================================================

    private static void placeFallenLog(ServerLevel level, BlockPos center,
                                        int dx, int dz,
                                        VillageBiomeStyle biome,
                                        RoadEdge edge) {
        // Log spans road edge-to-edge (5 blocks) perpendicular to travel direction
        Block logBlock = biomeLogBlock(biome);
        Direction.Axis axis = (Math.abs(dx) >= Math.abs(dz)) ? Direction.Axis.Z : Direction.Axis.X;
        BlockState logState = logBlock.defaultBlockState()
                .setValue(BlockStateProperties.AXIS, axis);

        for (int off = -2; off <= 2; off++) {
            int nx = center.getX() + (axis == Direction.Axis.Z ? off : 0);
            int nz = center.getZ() + (axis == Direction.Axis.X ? off : 0);
            if (!level.isLoaded(new BlockPos(nx, 0, nz))) continue;
            int ny = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz);
            BlockPos logPos = new BlockPos(nx, ny, nz);
            // Only place on solid ground (not over water or air)
            if (level.getBlockState(logPos.below()).isAir()) continue;
            level.setBlock(logPos, logState, 3);
            edge.addDecorationPosition(logPos);
        }
    }

    // =========================================================================
    // Block selection
    // =========================================================================

    private static BlockState pickOvergrowth(long h, VillageBiomeStyle biome,
                                              int maintenance, int lateralOffset) {
        return switch (biome) {
            case DESERT -> pickDesertOvergrowth(h, maintenance);
            case SNOWY  -> Blocks.SNOW.defaultBlockState();
            case SWAMP  -> pickSwampOvergrowth(h, maintenance);
            default     -> pickDefaultOvergrowth(h, maintenance, lateralOffset);
        };
    }

    private static BlockState pickDefaultOvergrowth(long h, int maintenance, int lateralOffset) {
        int roll = (int) ((h & 0xFFL) % 100);
        if (maintenance < 20) {
            // Dense: heavy grass + leaves + saplings
            if (roll < 40) return Blocks.SHORT_GRASS.defaultBlockState();
            if (roll < 65) return Blocks.TALL_GRASS.defaultBlockState();
            if (roll < 80) return Blocks.OAK_SAPLING.defaultBlockState();
            if (roll < 90) return Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(BlockStateProperties.PERSISTENT, true);
            return null;
        } else if (maintenance < 40) {
            if (roll < 50) return Blocks.SHORT_GRASS.defaultBlockState();
            if (roll < 75) return Blocks.TALL_GRASS.defaultBlockState();
            if (roll < 90 && lateralOffset >= 2) return Blocks.OAK_SAPLING.defaultBlockState();
            return null;
        } else if (maintenance < 60) {
            if (roll < 60) return Blocks.SHORT_GRASS.defaultBlockState();
            if (roll < 85) return Blocks.TALL_GRASS.defaultBlockState();
            return null;
        } else {
            // 60-79: mostly short_grass
            if (roll < 70) return Blocks.SHORT_GRASS.defaultBlockState();
            return null;
        }
    }

    private static BlockState pickDesertOvergrowth(long h, int maintenance) {
        int roll = (int) ((h & 0xFFL) % 100);
        if (maintenance < 40 && roll < 50) return Blocks.DEAD_BUSH.defaultBlockState();
        if (roll < 30) return Blocks.SAND.defaultBlockState();
        return null;
    }

    private static BlockState pickSwampOvergrowth(long h, int maintenance) {
        int roll = (int) ((h & 0xFFL) % 100);
        if (roll < 30) return Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(BlockStateProperties.PERSISTENT, true);
        if (maintenance < 40 && roll < 50) return Blocks.MOSS_BLOCK.defaultBlockState();
        if (roll < 70) return Blocks.SHORT_GRASS.defaultBlockState();
        return null;
    }

    private static Block biomeLogBlock(VillageBiomeStyle biome) {
        return switch (biome) {
            case TAIGA, SNOWY -> Blocks.STRIPPED_SPRUCE_LOG;
            case DESERT       -> Blocks.STRIPPED_ACACIA_LOG;
            case SWAMP        -> Blocks.STRIPPED_DARK_OAK_LOG;
            default           -> Blocks.STRIPPED_OAK_LOG;
        };
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static float densityForMaintenance(int maintenance) {
        if (maintenance < 20) return 0.60f;
        if (maintenance < 40) return 0.35f;
        if (maintenance < 60) return 0.15f;
        return 0.05f; // 60-79
    }

    /** Perpendicular X offset: rotate (dx, dz) 90° and scale. */
    private static int perpX(int dx, int dz, int scale) {
        double len = Math.max(1.0, Math.sqrt((double) dx * dx + (double) dz * dz));
        return (int) Math.round((-dz / len) * scale);
    }

    /** Perpendicular Z offset. */
    private static int perpZ(int dx, int dz, int scale) {
        double len = Math.max(1.0, Math.sqrt((double) dx * dx + (double) dz * dz));
        return (int) Math.round((dx / len) * scale);
    }

    private static long posHash(int x, int z) {
        return (long) x * 374761393L ^ (long) z * 668265263L;
    }

    private static int blockDist(BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        return (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
    }

    private static boolean isPlantable(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT);
    }

    private static boolean isReplaceableVegetation(BlockState state) {
        return state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.DEAD_BUSH)
                || state.is(Blocks.SNOW);
    }

    private static boolean isVegetation(BlockState state) {
        return state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.OAK_SAPLING)
                || state.is(Blocks.SPRUCE_SAPLING)
                || state.is(Blocks.DEAD_BUSH);
    }

    private static boolean isRoadSurface(BlockState state) {
        return state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.STONE)
                || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.POLISHED_ANDESITE);
    }
}
