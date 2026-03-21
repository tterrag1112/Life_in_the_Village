// src/main/java/tterrag1112/life_in_the_village/Village/Decoration/VillageWeathering.java
package tterrag1112.life_in_the_village.Village.Decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Random;

/**
 * Applies a post-placement weathering pass to a village to make it
 * look like it has been there for some time.
 *
 * <h3>Passes</h3>
 * <ol>
 *   <li><b>Mossy replacement</b> — randomly replaces some cobblestone,
 *       stone brick, and cobblestone wall blocks with their mossy
 *       variants. Probability is higher near water, in shaded spots,
 *       and on north-facing surfaces.</li>
 *   <li><b>Vine growth</b> — places vines on exposed stone/cobblestone
 *       faces at building perimeter edges. Sparse, only on 1–2 block
 *       segments so it reads as organic rather than overgrown.</li>
 *   <li><b>Coarse dirt edges</b> — replaces some grass blocks at the
 *       immediate base of buildings (the 1-block border) with coarse
 *       dirt, as if worn away by foot traffic.</li>
 *   <li><b>Cracked stone bricks</b> — sparse random replacement of
 *       stone bricks with cracked stone bricks on older/larger
 *       buildings (town hall, guild hall, barracks).</li>
 * </ol>
 *
 * <p>Weathering is seeded from the village name so the same village
 * always produces the same weathering pattern (deterministic).
 */
public class VillageWeathering {

    // Probability of any given cobblestone block becoming mossy
    private static final float MOSSY_COBBLE_CHANCE = 0.15f;
    // Probability of any given stone brick block becoming mossy
    private static final float MOSSY_BRICK_CHANCE  = 0.10f;
    // Probability of any given stone brick becoming cracked
    // (only on designated building types)
    private static final float CRACKED_CHANCE      = 0.08f;
    // Probability of a vine being placed on an eligible surface
    private static final float VINE_CHANCE         = 0.12f;
    // Probability of a border grass block becoming coarse dirt
    private static final float WORN_DIRT_CHANCE    = 0.35f;

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Applies all weathering passes to the village.
     * Called at the end of {@link VillageDecorator#decorateVillage}.
     */
    public static void weather(ServerLevel level,
                               Village village,
                               VillageSavedData data,
                               VillageBiomeStyle style) {
        List<Building> buildings = village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .toList();

        if (buildings.isEmpty()) return;

        // Seeded RNG — same village always weathers identically
        Random rng = new Random(village.getName().hashCode() * 31L
                + village.getId().getLeastSignificantBits());

        // Skip weathering for desert/snowy biomes — moss doesn't fit
        boolean mossyApplicable = style != VillageBiomeStyle.DESERT
                && style != VillageBiomeStyle.SNOWY;

        for (Building building : buildings) {
            if (mossyApplicable) {
                applyMossyReplacement(level, building, rng);
                applyVineGrowth(level, building, rng);
            }
            applyWornDirtEdges(level, building, rng);
            if (isOldBuilding(building)) {
                applyCrackedBricks(level, building, rng);
            }
        }

        System.out.println("VillageWeathering: applied weathering to "
                + buildings.size() + " buildings");
    }

    // =========================================================================
    // Pass 1: Mossy replacement
    // =========================================================================

    private static void applyMossyReplacement(ServerLevel level,
                                              Building building,
                                              Random rng) {
        BlockPos min = building.getShape().getMin();
        BlockPos max = building.getShape().getMax();

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos   = new BlockPos(x, y, z);
                    BlockState s   = level.getBlockState(pos);

                    if (s.is(Blocks.COBBLESTONE)
                            && rng.nextFloat() < MOSSY_COBBLE_CHANCE) {
                        level.setBlock(pos,
                                Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 3);
                    } else if (s.is(Blocks.COBBLESTONE_WALL)
                            && rng.nextFloat() < MOSSY_COBBLE_CHANCE) {
                        level.setBlock(pos,
                                Blocks.MOSSY_COBBLESTONE_WALL.defaultBlockState(), 3);
                    } else if (s.is(Blocks.STONE_BRICKS)
                            && rng.nextFloat() < MOSSY_BRICK_CHANCE) {
                        level.setBlock(pos,
                                Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), 3);
                    } else if (s.is(Blocks.STONE_BRICK_WALL)
                            && rng.nextFloat() < MOSSY_BRICK_CHANCE) {
                        level.setBlock(pos,
                                Blocks.MOSSY_STONE_BRICK_WALL.defaultBlockState(), 3);
                    } else if (s.is(Blocks.STONE_BRICK_STAIRS)
                            && rng.nextFloat() < MOSSY_BRICK_CHANCE * 0.5f) {
                        level.setBlock(pos,
                                Blocks.MOSSY_STONE_BRICK_STAIRS
                                        .withPropertiesOf(s), 3);
                    } else if (s.is(Blocks.STONE_BRICK_SLAB)
                            && rng.nextFloat() < MOSSY_BRICK_CHANCE * 0.5f) {
                        level.setBlock(pos,
                                Blocks.MOSSY_STONE_BRICK_SLAB
                                        .withPropertiesOf(s), 3);
                    }
                }
            }
        }
    }

    // =========================================================================
    // Pass 2: Vine growth
    // =========================================================================

    private static void applyVineGrowth(ServerLevel level,
                                        Building building,
                                        Random rng) {
        BlockPos min = building.getShape().getMin();
        BlockPos max = building.getShape().getMax();

        // Only place vines on the outer faces of the building
        // Scan each face: north, south, east, west
        for (int y = min.getY(); y <= Math.min(max.getY(), min.getY() + 6); y++) {

            // North face (min Z)
            for (int x = min.getX(); x <= max.getX(); x++) {
                tryPlaceVine(level, new BlockPos(x, y, min.getZ() - 1),
                        new BlockPos(x, y, min.getZ()), rng,
                        net.minecraft.core.Direction.SOUTH);
            }
            // South face (max Z)
            for (int x = min.getX(); x <= max.getX(); x++) {
                tryPlaceVine(level, new BlockPos(x, y, max.getZ() + 1),
                        new BlockPos(x, y, max.getZ()), rng,
                        net.minecraft.core.Direction.NORTH);
            }
            // West face (min X)
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                tryPlaceVine(level, new BlockPos(min.getX() - 1, y, z),
                        new BlockPos(min.getX(), y, z), rng,
                        net.minecraft.core.Direction.EAST);
            }
            // East face (max X)
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                tryPlaceVine(level, new BlockPos(max.getX() + 1, y, z),
                        new BlockPos(max.getX(), y, z), rng,
                        net.minecraft.core.Direction.WEST);
            }
        }
    }

    private static void tryPlaceVine(ServerLevel level,
                                     BlockPos vinePos,
                                     BlockPos attachPos,
                                     Random rng,
                                     net.minecraft.core.Direction attachFace) {
        if (rng.nextFloat() > VINE_CHANCE) return;

        BlockState vinePosState   = level.getBlockState(vinePos);
        BlockState attachState    = level.getBlockState(attachPos);

        // Only place vine in air, attached to solid cobblestone or stone
        if (!vinePosState.isAir()) return;
        if (!attachState.isSolidRender()) return;
        if (!attachState.is(Blocks.COBBLESTONE)
                && !attachState.is(Blocks.MOSSY_COBBLESTONE)
                && !attachState.is(Blocks.STONE_BRICKS)
                && !attachState.is(Blocks.MOSSY_STONE_BRICKS)
                && !attachState.is(Blocks.STONE)) return;

        // Build vine state with the correct face property
        BlockState vine = Blocks.VINE.defaultBlockState();
        vine = switch (attachFace) {
            case NORTH -> vine.setValue(
                    net.minecraft.world.level.block.VineBlock.NORTH, true);
            case SOUTH -> vine.setValue(
                    net.minecraft.world.level.block.VineBlock.SOUTH, true);
            case EAST  -> vine.setValue(
                    net.minecraft.world.level.block.VineBlock.EAST, true);
            case WEST  -> vine.setValue(
                    net.minecraft.world.level.block.VineBlock.WEST, true);
            default    -> vine;
        };

        level.setBlock(vinePos, vine, 3);
    }

    // =========================================================================
    // Pass 3: Worn dirt edges
    // =========================================================================

    /**
     * Replaces grass blocks in the 1-block border around a building with
     * coarse dirt — simulating foot traffic wearing away the ground.
     */
    private static void applyWornDirtEdges(ServerLevel level,
                                           Building building,
                                           Random rng) {
        BlockPos min    = building.getShape().getMin();
        BlockPos max    = building.getShape().getMax();
        int      floorY = building.getShape().getOrigin().getY();

        for (int x = min.getX() - 1; x <= max.getX() + 1; x++) {
            for (int z = min.getZ() - 1; z <= max.getZ() + 1; z++) {
                // Only the perimeter ring, not interior
                boolean onEdge = x == min.getX() - 1
                        || x == max.getX() + 1
                        || z == min.getZ() - 1
                        || z == max.getZ() + 1;
                if (!onEdge) continue;

                BlockPos pos = new BlockPos(x, floorY - 1, z);
                BlockState s = level.getBlockState(pos);

                if (s.is(Blocks.GRASS_BLOCK)
                        && rng.nextFloat() < WORN_DIRT_CHANCE) {
                    level.setBlock(pos,
                            Blocks.COARSE_DIRT.defaultBlockState(), 3);
                }
            }
        }
    }

    // =========================================================================
    // Pass 4: Cracked stone bricks
    // =========================================================================

    private static void applyCrackedBricks(ServerLevel level,
                                           Building building,
                                           Random rng) {
        BlockPos min = building.getShape().getMin();
        BlockPos max = building.getShape().getMax();

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos   pos = new BlockPos(x, y, z);
                    BlockState s   = level.getBlockState(pos);
                    if (s.is(Blocks.STONE_BRICKS)
                            && rng.nextFloat() < CRACKED_CHANCE) {
                        level.setBlock(pos,
                                Blocks.CRACKED_STONE_BRICKS
                                        .defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Returns true for building types that should show heavy weathering. */
    private static boolean isOldBuilding(Building building) {
        return switch (building.getType()) {
            case TOWN_HALL, GUILD_HALL, BARRACKS,
                 TEMPLE, CASTLE, PRISON, NOBLE_MANOR -> true;
            default -> false;
        };
    }
}