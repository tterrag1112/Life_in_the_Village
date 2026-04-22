package tterrag1112.life_in_the_village.Village.Roads.Decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Places Old Realm milestone markers along {@link RoadEdge.EdgeTier#GREAT_ROAD} edges.
 *
 * <h3>Placement</h3>
 * Milestones appear every 500–1000 blocks of path length, on alternating sides
 * of the centerline (2 blocks perpendicular), ground-snapped. Placement is
 * deterministic via the edge's meander seed so re-realization produces the same
 * result. Already-decorated positions are skipped to prevent duplication.
 *
 * <h3>Variants</h3>
 * <ul>
 *   <li><b>Intact</b> (60%) — polished-andesite base, chiseled-stone-bricks pillar, stone-brick-slab cap</li>
 *   <li><b>Broken/toppled</b> (25%) — scattered stone pieces with grass overgrowth</li>
 *   <li><b>Eroded</b> (15%) — mossy-cobblestone base, cracked-stone-bricks top</li>
 * </ul>
 *
 * <p>Placed positions are recorded in {@link RoadEdge#getDecorationPositions()} for
 * persistence and duplicate prevention.
 */
public final class MilestoneDecorator {

    private static final int MIN_SPACING = 500;
    private static final int MAX_SPACING = 1000;
    private static final int PERP_OFFSET = 2;

    private static final int VARIANT_INTACT  = 0;
    private static final int VARIANT_BROKEN  = 1;
    private static final int VARIANT_ERODED  = 2;

    private MilestoneDecorator() {}

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Decorates the edge with Old Realm milestones. No-ops for non-GREAT_ROAD
     * edges or edges with empty block paths.
     */
    public static void decorate(ServerLevel level, RoadEdge edge, WorldRoadGraph graph) {
        if (edge.getTier() != RoadEdge.EdgeTier.GREAT_ROAD) return;
        List<BlockPos> path = edge.getBlockPath();
        if (path.size() < 2) return;

        Random rng = new Random(edge.getMeanderProfile().seed() ^ 0xDECAFBADL);

        int accLen       = 0;
        int nextTarget   = nextSpacing(rng);
        int milestoneIdx = 0;

        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i - 1);
            BlockPos b = path.get(i);
            accLen += blockDist(a, b);

            if (accLen < nextTarget) continue;

            // Compute perpendicular from road direction at this point
            int dx = b.getX() - a.getX();
            int dz = b.getZ() - a.getZ();
            double len  = Math.max(1.0, Math.sqrt((double) dx * dx + (double) dz * dz));
            double pdx  = -dz / len;   // perpendicular unit vector
            double pdz  =  dx / len;
            double side = (milestoneIdx % 2 == 0) ? 1.0 : -1.0;

            int mx = b.getX() + (int) Math.round(pdx * PERP_OFFSET * side);
            int mz = b.getZ() + (int) Math.round(pdz * PERP_OFFSET * side);

            if (level.isLoaded(new BlockPos(mx, 0, mz))) {
                int my = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, mx, mz);
                BlockPos base = new BlockPos(mx, my, mz);

                if (!nearExistingDecoration(edge, base, 8)) {
                    int variant = pickVariant(base);
                    List<BlockPos> placed = switch (variant) {
                        case VARIANT_INTACT -> placeIntact(level, base, rng);
                        case VARIANT_BROKEN -> placeBroken(level, base, rng);
                        default             -> placeEroded(level, base);
                    };
                    placed.forEach(edge::addDecorationPosition);
                }
            }

            milestoneIdx++;
            accLen     = 0;
            nextTarget = nextSpacing(rng);
        }
    }

    // =========================================================================
    // Variant selection
    // =========================================================================

    private static int pickVariant(BlockPos pos) {
        // Deterministic by position; 60% intact, 25% broken, 15% eroded
        long h = (long) pos.getX() * 374761393L ^ (long) pos.getZ() * 668265263L;
        int roll = (int) ((h & 0x7FFFFFFFL) % 100);
        if (roll < 60) return VARIANT_INTACT;
        if (roll < 85) return VARIANT_BROKEN;
        return VARIANT_ERODED;
    }

    // =========================================================================
    // Variant A — Intact waymarker
    // =========================================================================

    private static List<BlockPos> placeIntact(ServerLevel level, BlockPos base, Random rng) {
        List<BlockPos> placed = new ArrayList<>();
        // Base: polished_andesite
        set(level, base, Blocks.POLISHED_ANDESITE.defaultBlockState(), placed);
        // Pillar: chiseled_stone_bricks
        BlockPos pillar = base.above();
        set(level, pillar, Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), placed);
        // Cap: stone_brick_slab (top half)
        set(level, pillar.above(),
                Blocks.STONE_BRICK_SLAB.defaultBlockState()
                        .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP),
                placed);
        // Optional mossy_cobblestone_wall beside it (20% chance, east side)
        if (rng.nextFloat() < 0.20f) {
            BlockPos wallPos = base.east();
            if (level.isLoaded(wallPos)) {
                int wy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        wallPos.getX(), wallPos.getZ());
                set(level, new BlockPos(wallPos.getX(), wy, wallPos.getZ()),
                        Blocks.MOSSY_COBBLESTONE_WALL.defaultBlockState(), placed);
            }
        }
        return placed;
    }

    // =========================================================================
    // Variant B — Broken/toppled
    // =========================================================================

    private static List<BlockPos> placeBroken(ServerLevel level, BlockPos base, Random rng) {
        List<BlockPos> placed = new ArrayList<>();
        // Main piece on ground
        set(level, base, Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), placed);
        // Toppled piece 1-2 blocks to the east
        int offset = 1 + rng.nextInt(2);
        BlockPos toppledRaw = base.east(offset);
        if (level.isLoaded(toppledRaw)) {
            int ty = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    toppledRaw.getX(), toppledRaw.getZ());
            BlockPos toppled = new BlockPos(toppledRaw.getX(), ty, toppledRaw.getZ());
            set(level, toppled, Blocks.POLISHED_ANDESITE.defaultBlockState(), placed);

            // Break-point cobblestone between the two pieces
            if (offset == 2 && rng.nextFloat() < 0.5f) {
                int bx = (base.getX() + toppledRaw.getX()) / 2;
                int bz = (base.getZ() + toppledRaw.getZ()) / 2;
                int by = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
                set(level, new BlockPos(bx, by, bz),
                        Blocks.COBBLESTONE.defaultBlockState(), placed);
            }
        }
        // Short_grass overgrowth on north side
        BlockPos grassRaw = base.north();
        if (level.isLoaded(grassRaw)) {
            int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    grassRaw.getX(), grassRaw.getZ());
            BlockPos grassPos = new BlockPos(grassRaw.getX(), gy, grassRaw.getZ());
            BlockState soil = level.getBlockState(grassPos.below());
            if (isPlantable(soil) && level.getBlockState(grassPos).isAir()) {
                set(level, grassPos, Blocks.SHORT_GRASS.defaultBlockState(), placed);
            }
        }
        return placed;
    }

    // =========================================================================
    // Variant C — Eroded
    // =========================================================================

    private static List<BlockPos> placeEroded(ServerLevel level, BlockPos base) {
        List<BlockPos> placed = new ArrayList<>();
        set(level, base, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), placed);
        set(level, base.above(), Blocks.CRACKED_STONE_BRICKS.defaultBlockState(), placed);
        return placed;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void set(ServerLevel level, BlockPos pos, BlockState state,
                             List<BlockPos> placed) {
        if (!level.isLoaded(pos)) return;
        level.setBlock(pos, state, 3);
        placed.add(pos.immutable());
    }

    private static boolean isPlantable(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT);
    }

    private static boolean nearExistingDecoration(RoadEdge edge, BlockPos pos, int radius) {
        int r2 = radius * radius;
        for (BlockPos d : edge.getDecorationPositions()) {
            if (d.distSqr(pos) < r2) return true;
        }
        return false;
    }

    private static int nextSpacing(Random rng) {
        return MIN_SPACING + rng.nextInt(MAX_SPACING - MIN_SPACING);
    }

    private static int blockDist(BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        return (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
    }
}
