// src/main/java/tterrag1112/life_in_the_village/Village/Decoration/VillageWeathering.java
package tterrag1112.life_in_the_village.Village.Decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingCondition;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Random;

/**
 * Applies a post-placement weathering pass to a village to make it look
 * like it has been there for some time, and provides a complementary
 * {@link #repair} pass called by
 * {@link tterrag1112.life_in_the_village.Entities.Goals.Profession.Builder.BuilderMaintenanceGoal}
 * to gradually restore weathered buildings.
 *
 * <h3>Decay passes (weather)</h3>
 * <ol>
 *   <li>Mossy replacement — cobblestone, stone bricks, walls → mossy variants</li>
 *   <li>Vine growth — sparse vines on north/south/east/west outer faces</li>
 *   <li>Worn dirt edges — grass at building base → coarse dirt</li>
 *   <li>Cracked stone bricks — only on designated civic/military building types</li>
 * </ol>
 *
 * <h3>Repair pass</h3>
 * Reverses the above changes with a 35% per-block chance, creating gradual
 * restoration over multiple builder visits.
 */
public class VillageWeathering {

    // -------------------------------------------------------------------------
    // Probability constants
    // -------------------------------------------------------------------------

    private static final float MOSSY_COBBLE_CHANCE = 0.15f;
    private static final float MOSSY_BRICK_CHANCE  = 0.10f;
    private static final float CRACKED_CHANCE      = 0.08f;
    private static final float VINE_CHANCE         = 0.12f;
    private static final float WORN_DIRT_CHANCE    = 0.35f;
    /** Chance per block that a repair pass un-weathers it. */
    private static final float REPAIR_CHANCE       = 0.35f;

    // =========================================================================
    // Entry point — decay
    // =========================================================================

    /**
     * Applies all weathering passes to the village.
     * Called at the end of {@link VillageDecorator#decorateVillage}.
     * Also sets each building's {@link BuildingCondition} to
     * {@code WEATHERED} (from {@code NEW}) after the pass completes.
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

        // Skip moss/vines in deserts and snowy biomes — doesn't fit
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

            // Advance condition: NEW → WEATHERED
            if (building.getCondition() == BuildingCondition.NEW) {
                building.setCondition(BuildingCondition.WEATHERED);
            }
        }

        data.markDirty();

        System.out.println("VillageWeathering: applied weathering to "
                + buildings.size() + " buildings in " + village.getName());
    }

    // =========================================================================
    // Entry point — repair (called by BuilderMaintenanceGoal)
    // =========================================================================

    /**
     * Reverses weathering on a single building with a per-block chance.
     * Each repair visit nudges the building toward {@code MAINTAINED} without
     * snapping it back all at once, preserving visual gradualness.
     *
     * <p>Also advances the building's {@link BuildingCondition} by one step
     * via {@link BuildingCondition#repair()} — e.g. WEATHERED → MAINTAINED.</p>
     *
     * @param level    server level
     * @param building the building to repair
     * @param style    biome style (used to restore path-edge blocks)
     * @param rng      the caller's random source
     */
    public static void repair(ServerLevel level,
                              Building building,
                              VillageBiomeStyle style,
                              RandomSource rng) {
        BlockPos min = building.getShape().getMin();
        BlockPos max = building.getShape().getMax();

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    if (rng.nextFloat() > REPAIR_CHANCE) continue;

                    BlockPos   pos   = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);

                    // Mossy cobblestone → cobblestone
                    if (state.is(Blocks.MOSSY_COBBLESTONE)) {
                        level.setBlock(pos,
                                Blocks.COBBLESTONE.defaultBlockState(), 3);
                        continue;
                    }

                    // Mossy cobblestone wall → cobblestone wall (preserve shape props)
                    if (state.is(Blocks.MOSSY_COBBLESTONE_WALL)) {
                        BlockState clean = Blocks.COBBLESTONE_WALL.defaultBlockState();
                        for (var prop : state.getProperties()) {
                            try {
                                //noinspection unchecked,rawtypes
                                clean = clean.setValue(
                                        (net.minecraft.world.level.block.state.properties.Property) prop,
                                        state.getValue(prop));
                            } catch (Exception ignored) {}
                        }
                        level.setBlock(pos, clean, 3);
                        continue;
                    }

                    // Mossy stone bricks → stone bricks
                    if (state.is(Blocks.MOSSY_STONE_BRICKS)) {
                        level.setBlock(pos,
                                Blocks.STONE_BRICKS.defaultBlockState(), 3);
                        continue;
                    }

                    // Cracked stone bricks → stone bricks
                    if (state.is(Blocks.CRACKED_STONE_BRICKS)) {
                        level.setBlock(pos,
                                Blocks.STONE_BRICKS.defaultBlockState(), 3);
                        continue;
                    }

                    // Vines — remove
                    if (state.is(Blocks.VINE)) {
                        level.setBlock(pos,
                                Blocks.AIR.defaultBlockState(), 3);
                        continue;
                    }

                    // Worn coarse dirt edges — restore to path block
                    if (state.is(Blocks.COARSE_DIRT)) {
                        BlockState below = level.getBlockState(pos.below());
                        if (below.isSolidRender()) {
                            level.setBlock(pos,
                                    style.pathBlock.defaultBlockState(), 3);
                        }
                    }
                }
            }
        }

        System.out.println("VillageWeathering: repaired " + building.getName());
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
                    BlockPos   pos   = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);

                    if (state.is(Blocks.COBBLESTONE)
                            && rng.nextFloat() < MOSSY_COBBLE_CHANCE) {
                        level.setBlock(pos,
                                Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 3);

                    } else if (state.is(Blocks.COBBLESTONE_WALL)
                            && rng.nextFloat() < MOSSY_COBBLE_CHANCE) {
                        BlockState mossy = Blocks.MOSSY_COBBLESTONE_WALL.defaultBlockState();
                        for (var prop : state.getProperties()) {
                            try {
                                //noinspection unchecked,rawtypes
                                mossy = mossy.setValue(
                                        (net.minecraft.world.level.block.state.properties.Property) prop,
                                        state.getValue(prop));
                            } catch (Exception ignored) {}
                        }
                        level.setBlock(pos, mossy, 3);

                    } else if (state.is(Blocks.STONE_BRICKS)
                            && rng.nextFloat() < MOSSY_BRICK_CHANCE) {
                        level.setBlock(pos,
                                Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), 3);
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

        for (int y = min.getY(); y <= Math.min(max.getY(), min.getY() + 6); y++) {

            // North face (min Z)
            for (int x = min.getX(); x <= max.getX(); x++) {
                tryPlaceVine(level,
                        new BlockPos(x, y, min.getZ() - 1),
                        new BlockPos(x, y, min.getZ()),
                        rng, net.minecraft.core.Direction.SOUTH);
            }
            // South face (max Z)
            for (int x = min.getX(); x <= max.getX(); x++) {
                tryPlaceVine(level,
                        new BlockPos(x, y, max.getZ() + 1),
                        new BlockPos(x, y, max.getZ()),
                        rng, net.minecraft.core.Direction.NORTH);
            }
            // West face (min X)
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                tryPlaceVine(level,
                        new BlockPos(min.getX() - 1, y, z),
                        new BlockPos(min.getX(), y, z),
                        rng, net.minecraft.core.Direction.EAST);
            }
            // East face (max X)
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                tryPlaceVine(level,
                        new BlockPos(max.getX() + 1, y, z),
                        new BlockPos(max.getX(), y, z),
                        rng, net.minecraft.core.Direction.WEST);
            }
        }
    }

    private static void tryPlaceVine(ServerLevel level,
                                     BlockPos vinePos,
                                     BlockPos attachPos,
                                     Random rng,
                                     net.minecraft.core.Direction attachFace) {
        if (rng.nextFloat() > VINE_CHANCE) return;

        BlockState vinePosState = level.getBlockState(vinePos);
        BlockState attachState  = level.getBlockState(attachPos);

        if (!vinePosState.isAir()) return;
        if (!attachState.isSolidRender()) return;
        if (!attachState.is(Blocks.COBBLESTONE)
                && !attachState.is(Blocks.MOSSY_COBBLESTONE)
                && !attachState.is(Blocks.STONE_BRICKS)
                && !attachState.is(Blocks.MOSSY_STONE_BRICKS)
                && !attachState.is(Blocks.STONE)) return;

        BlockState vine = Blocks.VINE.defaultBlockState();
        vine = switch (attachFace) {
            case NORTH -> vine.setValue(VineBlock.NORTH, true);
            case SOUTH -> vine.setValue(VineBlock.SOUTH, true);
            case EAST  -> vine.setValue(VineBlock.EAST,  true);
            case WEST  -> vine.setValue(VineBlock.WEST,  true);
            default    -> vine;
        };
        level.setBlock(vinePos, vine, 3);
    }

    // =========================================================================
    // Pass 3: Worn dirt edges
    // =========================================================================

    private static void applyWornDirtEdges(ServerLevel level,
                                           Building building,
                                           Random rng) {
        BlockPos min    = building.getShape().getMin();
        BlockPos max    = building.getShape().getMax();
        int      floorY = building.getShape().getOrigin().getY();

        for (int x = min.getX() - 1; x <= max.getX() + 1; x++) {
            for (int z = min.getZ() - 1; z <= max.getZ() + 1; z++) {
                boolean onEdge = x == min.getX() - 1
                        || x == max.getX() + 1
                        || z == min.getZ() - 1
                        || z == max.getZ() + 1;
                if (!onEdge) continue;

                BlockPos   pos = new BlockPos(x, floorY - 1, z);
                BlockState s   = level.getBlockState(pos);

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
                                Blocks.CRACKED_STONE_BRICKS.defaultBlockState(), 3);
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