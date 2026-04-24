package tterrag1112.life_in_the_village.Village.Roads.Terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import tterrag1112.life_in_the_village.Village.Roads.Terrain.GreatRoadProfile.PositionClassification;

import java.util.List;

/**
 * Builds support structures beneath RAISED sections of a GREAT_ROAD.
 *
 * <h3>Two modes</h3>
 * <dl>
 *   <dt>Solid fill</dt>
 *   <dd>Used when the RAISED run is shorter than {@value #VIADUCT_MIN_GAP} blocks
 *       and depth is in range [{@value #MIN_FILL_DEPTH}, {@value #MAX_FILL_DEPTH}].
 *       Fills from terrain Y to profileY − 1 with Old Realm palette blocks.</dd>
 *   <dt>Pillared viaduct</dt>
 *   <dd>Used when the contiguous RAISED run length ≥ {@value #VIADUCT_MIN_GAP}
 *       and depth ≤ {@value #MAX_FILL_DEPTH}.  Pillars placed every
 *       {@value #PILLAR_INTERVAL} positions along the run; slab deck spans
 *       between pillars at profileY − 1.</dd>
 * </dl>
 * Positions where depth &gt; {@value #MAX_FILL_DEPTH} are skipped (terrain gap
 * too large to fill safely).
 *
 * <h3>Build order</h3>
 * Support structures must be placed <em>before</em> {@link
 * tterrag1112.life_in_the_village.Village.Decoration.Roads.OrganicRoadPlacer}
 * so the {@code MOTION_BLOCKING_NO_LEAVES} heightmap already returns profileY
 * when the road surface is painted.
 */
public final class RoadSupportBuilder {

    private RoadSupportBuilder() {}

    public static final int MIN_FILL_DEPTH    = 2;
    public static final int MAX_FILL_DEPTH    = 12;
    public static final int VIADUCT_MIN_GAP   = 20;
    public static final int PILLAR_INTERVAL   = 5;

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Places support structures for all RAISED positions.
     *
     * @param level    server level
     * @param dense    block-dense centerline
     * @param profileY smoothed profile from {@link GreatRoadProfile#computeProfile}
     * @param classes  per-position classification
     */
    public static void build(ServerLevel level,
                              List<BlockPos> dense,
                              int[] profileY,
                              List<PositionClassification> classes) {
        int n = dense.size();

        // Pre-compute run lengths for contiguous RAISED spans
        int[] runLen = computeRaisedRunLengths(classes, n);

        for (int i = 0; i < n; i++) {
            if (classes.get(i) != PositionClassification.RAISED) continue;

            BlockPos pos   = dense.get(i);
            int terrainY   = pos.getY();
            int profile    = profileY[i];
            int depth      = profile - terrainY;

            if (depth < MIN_FILL_DEPTH) continue;
            if (depth > MAX_FILL_DEPTH) continue; // too deep — skip

            if (runLen[i] >= VIADUCT_MIN_GAP) {
                // Part of a long span — place pillar or span deck
                boolean isPillarPos = (i % PILLAR_INTERVAL == 0);
                if (isPillarPos) {
                    placeViaductPillar(level, pos, terrainY, profile);
                } else {
                    placeViaductDeck(level, pos, profile);
                }
            } else {
                // Short span — solid fill
                placeSolidFill(level, pos, terrainY, profile);
            }
        }
    }

    // =========================================================================
    // Fill modes
    // =========================================================================

    private static void placeSolidFill(ServerLevel level, BlockPos base,
                                        int terrainY, int profileY) {
        for (int y = terrainY; y < profileY; y++) {
            BlockPos fp = new BlockPos(base.getX(), y, base.getZ());
            if (!level.isLoaded(fp)) return;
            if (!level.getBlockState(fp).isSolidRender()) {
                level.setBlock(fp, RetainingWallBuilder.oldRealmBlock(
                        base.getX(), y, base.getZ()), 3);
            }
        }
    }

    private static void placeViaductPillar(ServerLevel level, BlockPos base,
                                            int terrainY, int profileY) {
        for (int y = terrainY; y < profileY - 1; y++) {
            BlockPos pp = new BlockPos(base.getX(), y, base.getZ());
            if (!level.isLoaded(pp)) return;
            level.setBlock(pp, Blocks.STONE_BRICKS.defaultBlockState(), 3);
        }
        // Pillar cap at profileY - 1
        BlockPos cap = new BlockPos(base.getX(), profileY - 1, base.getZ());
        if (level.isLoaded(cap)) {
            level.setBlock(cap, Blocks.STONE_BRICK_SLAB.defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.TOP), 3);
        }
    }

    private static void placeViaductDeck(ServerLevel level, BlockPos base, int profileY) {
        // Single slab at profileY - 1 to carry the road surface
        BlockPos deck = new BlockPos(base.getX(), profileY - 1, base.getZ());
        if (!level.isLoaded(deck)) return;
        BlockState existing = level.getBlockState(deck);
        if (existing.isAir() || existing.canBeReplaced()) {
            level.setBlock(deck, Blocks.STONE_BRICK_SLAB.defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.TOP), 3);
        }
    }

    // =========================================================================
    // Run-length pre-computation
    // =========================================================================

    /**
     * For each position, stores the length of the contiguous RAISED run that
     * contains it (how many consecutive RAISED positions surround it including
     * itself).  Non-RAISED positions get 0.
     */
    public static int[] computeRaisedRunLengths(List<PositionClassification> classes, int n) {
        int[] runLen = new int[n];
        int i = 0;
        while (i < n) {
            if (classes.get(i) != PositionClassification.RAISED) {
                i++;
                continue;
            }
            int start = i;
            while (i < n && classes.get(i) == PositionClassification.RAISED) i++;
            int len = i - start;
            for (int j = start; j < i; j++) runLen[j] = len;
        }
        return runLen;
    }
}
