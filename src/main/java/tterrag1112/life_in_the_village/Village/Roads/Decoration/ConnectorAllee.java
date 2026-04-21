package tterrag1112.life_in_the_village.Village.Roads.Decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Roads.Docking.VillageDockingPoint;

import java.util.List;

/**
 * Plants a sapling allée along the final approach to a village gate.
 *
 * <h3>Layout</h3>
 * One sapling every 3 blocks along the approach centerline, placed
 * 2 blocks perpendicular to the road on BOTH sides. Saplings will
 * grow into trees over time, lining the road into the village.
 *
 * <h3>Culture-based species</h3>
 * <ul>
 *   <li>{@code nordic}   → spruce (cold, dense forest feel)</li>
 *   <li>{@code imperial} → dark oak (formal, austere — single saplings
 *       grow as small dark-oak bushes; intentionally restrained)</li>
 *   <li>{@code highland} → birch (bright, mountain-country feel)</li>
 *   <li>{@code default} / unknown → oak</li>
 * </ul>
 *
 * <h3>Biome suppression</h3>
 * Allées are suppressed in DESERT and SNOWY biomes: saplings would not
 * grow and look out-of-place on sand or frozen ground.
 *
 * <h3>Placement guards</h3>
 * A sapling is only placed where the ground block is naturally plantable
 * (grass, dirt, coarse dirt) and the surface position is air or
 * replaceable low vegetation. This prevents overwriting roads or
 * building materials that may have been placed perpendicular to the approach.
 */
public final class ConnectorAllee {

    /** Blocks between each sapling pair along the approach. */
    private static final int SAPLING_SPACING = 3;

    /** Blocks perpendicular from the road centerline to each sapling. */
    private static final int SAPLING_OFFSET = 2;

    private ConnectorAllee() {}

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Places an allée along the approach segment.
     *
     * @param level              world reference
     * @param dock               docking geometry (provides arm direction)
     * @param approachCenterline ordered block positions from docking anchor
     *                           to arm endpoint
     * @param culture            village type string (e.g. "nordic", "imperial")
     */
    public static void place(ServerLevel level,
                             VillageDockingPoint dock,
                             List<BlockPos> approachCenterline,
                             String culture) {
        if (approachCenterline.isEmpty()) return;

        // Biome check — no allée in desert or snowy
        VillageBiomeStyle biome = VillageBiomeStyle.detect(level, dock.armEndpoint());
        if (biome == VillageBiomeStyle.DESERT || biome == VillageBiomeStyle.SNOWY) return;

        Block sapling = saplingFor(culture);
        double dir    = dock.armDirectionRadians();

        // Perpendicular unit vector (rotate heading 90°)
        double perpX = -Math.sin(dir);
        double perpZ =  Math.cos(dir);

        for (int i = 0; i < approachCenterline.size(); i += SAPLING_SPACING) {
            BlockPos center = approachCenterline.get(i);
            tryPlant(level, center, perpX, perpZ,  SAPLING_OFFSET, sapling);
            tryPlant(level, center, perpX, perpZ, -SAPLING_OFFSET, sapling);
        }
    }

    // =========================================================================
    // Planting
    // =========================================================================

    private static void tryPlant(ServerLevel level, BlockPos center,
                                 double perpX, double perpZ,
                                 int offset, Block sapling) {
        int sx = center.getX() + (int) Math.round(perpX * offset);
        int sz = center.getZ() + (int) Math.round(perpZ * offset);

        if (!level.isLoaded(new BlockPos(sx, 0, sz))) return;

        int sy        = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz);
        BlockPos soil = new BlockPos(sx, sy - 1, sz);
        BlockPos spot = new BlockPos(sx, sy,     sz);

        if (!isPlantableSoil(level.getBlockState(soil))) return;

        BlockState above = level.getBlockState(spot);
        if (!above.isAir() && !isReplaceableVegetation(above)) return;

        level.setBlock(spot, sapling.defaultBlockState(), 3);
    }

    // =========================================================================
    // Block tests
    // =========================================================================

    private static boolean isPlantableSoil(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT);
    }

    private static boolean isReplaceableVegetation(BlockState state) {
        return state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.POPPY)
                || state.is(Blocks.DANDELION)
                || state.is(Blocks.CORNFLOWER)
                || state.is(Blocks.AZURE_BLUET)
                || state.is(Blocks.OXEYE_DAISY)
                || state.is(Blocks.BLUE_ORCHID)
                || state.is(Blocks.ALLIUM)
                || state.is(Blocks.DEAD_BUSH);
    }

    // =========================================================================
    // Culture → sapling
    // =========================================================================

    private static Block saplingFor(String culture) {
        if (culture == null) return Blocks.OAK_SAPLING;
        return switch (culture.toLowerCase(java.util.Locale.ROOT)) {
            case "nordic"   -> Blocks.SPRUCE_SAPLING;
            case "imperial" -> Blocks.DARK_OAK_SAPLING;
            case "highland" -> Blocks.BIRCH_SAPLING;
            default         -> Blocks.OAK_SAPLING;
        };
    }
}
