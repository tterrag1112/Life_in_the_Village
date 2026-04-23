package tterrag1112.life_in_the_village.Village.Roads.Decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Places procedural shelter structures at {@link ShelterPlanner.ShelterPlan} positions.
 *
 * <h3>Terrain suitability</h3>
 * Before placing, the builder samples a 3×3 grid of surface heights centred on the
 * plan position. If the height variance exceeds 4 blocks the site is rejected and
 * no structure is placed (the caller receives {@code null}).
 *
 * <h3>Ground-snapping</h3>
 * All blocks use {@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES} to determine the
 * surface Y, so structures sit on terrain rather than floating or clipping into hills.
 *
 * <h3>Cultural variants</h3>
 * {@link ShelterPlanner.ShelterType#INN} and {@link ShelterPlanner.ShelterType#CARAVANSERAI}
 * accept a {@code culture} string that selects wall, roof, and decorative materials.
 * The Old Realm ruin types (WATCHTOWER, WAYSTATION) always use stone-brick / mossy-
 * cobblestone regardless of culture, reflecting their pre-kingdom origin.
 *
 * <h3>Orientation</h3>
 * Structures are rotated so their entrance faces the road. The plan's {@code facingDx}
 * and {@code facingDz} fields give the road-forward direction; the entrance is placed
 * on the road-facing side and all interior furniture (beds, fire, well) is oriented
 * to match.
 */
public final class ShelterBuilder {

    /** Maximum surface-height variance (blocks) before a site is rejected. */
    private static final int MAX_HEIGHT_VARIANCE = 4;

    private ShelterBuilder() {}

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Attempts to place a shelter at the plan position.
     *
     * @param level     server level for block placement
     * @param plan      planned position + type from {@link ShelterPlanner}
     * @param culture   village culture hint ("imperial", "nordic", "highland", or null)
     * @param graph     road graph used to verify the site does not overlap any road surface
     * @param tick      current game tick (stored in the returned instance)
     * @return a {@link ShelterInstance} if placement succeeded, or {@code null} if
     *         the site was rejected (terrain too uneven, road overlap, or chunk unloaded)
     */
    public static ShelterInstance place(ServerLevel level,
                                        ShelterPlanner.ShelterPlan plan,
                                        String culture,
                                        WorldRoadGraph graph,
                                        long tick) {
        BlockPos origin = plan.position();

        // Chunk must be loaded for block placement
        if (!level.isLoaded(origin)) return null;

        // Ground-snap origin to terrain surface
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                origin.getX(), origin.getZ());
        origin = new BlockPos(origin.getX(), groundY, origin.getZ());

        // Terrain suitability check
        if (!isSuitable(level, origin)) return null;

        // Road-clearance check: reject any site whose footprint overlaps a realized road
        if (!RoadClearanceValidator.isClearOfRoads(origin, graph, 2)) {
            System.out.println("[ShelterBuilder] Skipping " + plan.type()
                    + " at " + origin.toShortString() + ": overlaps road surface");
            return null;
        }

        List<BlockPos> placed = new ArrayList<>();
        Random rng = new Random(origin.asLong() ^ 0xABCD1234L);

        switch (plan.type()) {
            case INN              -> placeInn(level, origin, plan, culture, rng, placed);
            case CARAVANSERAI     -> placeCaravanserai(level, origin, plan, culture, rng, placed);
            case ROADSIDE_SHRINE  -> placeShrine(level, origin, plan, rng, placed);
            case RUINED_WATCHTOWER -> placeRuinedWatchtower(level, origin, plan, rng, placed);
            case RUINED_WAYSTATION -> placeRuinedWaystation(level, origin, plan, rng, placed);
        }

        if (placed.isEmpty()) return null;

        System.out.println("[ShelterBuilder] Placed " + plan.type() + " at "
                + origin.toShortString() + " (" + placed.size() + " blocks)");

        return new ShelterInstance(UUID.randomUUID(), plan.type(),
                origin, placed, tick);
    }

    // =========================================================================
    // INN  (7×9 two-story, entrance on road-facing wall)
    // =========================================================================

    private static void placeInn(ServerLevel level, BlockPos origin,
                                  ShelterPlanner.ShelterPlan plan,
                                  String culture, Random rng,
                                  List<BlockPos> placed) {
        BlockState wall  = wallBlock(culture);
        BlockState roof  = roofBlock(culture);
        BlockState floor = floorBlock(culture);
        BlockState bed   = bedBlock(culture, roadFacing(plan));

        int w = 7, d = 9;

        // Foundation + floor
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                set(level, origin.offset(x, -1, z), Blocks.COBBLESTONE.defaultBlockState(), placed);
                set(level, origin.offset(x, 0, z), floor, placed);
            }
        }

        // Walls — ground floor
        for (int x = 0; x < w; x++) {
            for (int y = 1; y <= 3; y++) {
                set(level, origin.offset(x, y, 0),   wall, placed);
                set(level, origin.offset(x, y, d-1), wall, placed);
            }
        }
        for (int z = 0; z < d; z++) {
            for (int y = 1; y <= 3; y++) {
                set(level, origin.offset(0,   y, z), wall, placed);
                set(level, origin.offset(w-1, y, z), wall, placed);
            }
        }

        // Interior floor at y=4 (upper story)
        for (int x = 1; x < w-1; x++) {
            for (int z = 1; z < d-1; z++) {
                set(level, origin.offset(x, 4, z), floor, placed);
            }
        }

        // Upper walls
        for (int x = 0; x < w; x++) {
            for (int y = 5; y <= 6; y++) {
                set(level, origin.offset(x, y, 0),   wall, placed);
                set(level, origin.offset(x, y, d-1), wall, placed);
            }
        }
        for (int z = 0; z < d; z++) {
            for (int y = 5; y <= 6; y++) {
                set(level, origin.offset(0,   y, z), wall, placed);
                set(level, origin.offset(w-1, y, z), wall, placed);
            }
        }

        // Roof
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                set(level, origin.offset(x, 7, z), roof, placed);
            }
        }

        // Door opening on road-facing wall (middle of southern wall = z=0)
        level.removeBlock(origin.offset(3, 1, 0), false);
        level.removeBlock(origin.offset(3, 2, 0), false);

        // Ground-floor beds (2 beds side-by-side)
        set(level, origin.offset(1, 1, 6), bedFoot(culture, roadFacing(plan)), placed);
        set(level, origin.offset(1, 1, 7), bedHead(culture, roadFacing(plan)), placed);
        set(level, origin.offset(2, 1, 6), bedFoot(culture, roadFacing(plan)), placed);
        set(level, origin.offset(2, 1, 7), bedHead(culture, roadFacing(plan)), placed);

        // Upper-story beds
        set(level, origin.offset(1, 5, 6), bedFoot(culture, roadFacing(plan)), placed);
        set(level, origin.offset(1, 5, 7), bedHead(culture, roadFacing(plan)), placed);
        set(level, origin.offset(2, 5, 6), bedFoot(culture, roadFacing(plan)), placed);
        set(level, origin.offset(2, 5, 7), bedHead(culture, roadFacing(plan)), placed);

        // Hearth on back wall
        set(level, origin.offset(5, 1, 7), Blocks.CAMPFIRE.defaultBlockState(), placed);
        set(level, origin.offset(5, 2, 7), Blocks.IRON_BARS.defaultBlockState(), placed);

        // Sign post by entrance
        set(level, origin.offset(3, 3, 0), Blocks.OAK_WALL_SIGN.defaultBlockState(), placed);
    }

    // =========================================================================
    // CARAVANSERAI  (9×11 U-shape courtyard)
    // =========================================================================

    private static void placeCaravanserai(ServerLevel level, BlockPos origin,
                                           ShelterPlanner.ShelterPlan plan,
                                           String culture, Random rng,
                                           List<BlockPos> placed) {
        BlockState wall  = wallBlock(culture);
        BlockState roof  = roofBlock(culture);
        BlockState floor = floorBlock(culture);

        int w = 9, d = 11;

        // Outer perimeter walls (U-shape: open on road-facing z=0 side)
        for (int x = 0; x < w; x++) {
            for (int y = 1; y <= 3; y++) {
                set(level, origin.offset(x, y, d-1), wall, placed); // back wall
            }
            set(level, origin.offset(x, 0, d-1), floor, placed);
        }
        for (int z = 1; z < d; z++) {
            for (int y = 1; y <= 3; y++) {
                set(level, origin.offset(0,   y, z), wall, placed); // left arm
                set(level, origin.offset(w-1, y, z), wall, placed); // right arm
            }
            set(level, origin.offset(0,   0, z), floor, placed);
            set(level, origin.offset(w-1, 0, z), floor, placed);
        }

        // Roofed wings on left and right arms (3 blocks wide)
        for (int z = 1; z < d; z++) {
            for (int x = 1; x <= 2; x++) {
                set(level, origin.offset(x, 4, z), roof, placed);
                set(level, origin.offset(w-1-x, 4, z), roof, placed);
                set(level, origin.offset(x, 0, z), floor, placed);
                set(level, origin.offset(w-1-x, 0, z), floor, placed);
            }
        }
        // Roof back section
        for (int x = 1; x < w-1; x++) {
            set(level, origin.offset(x, 4, d-1), roof, placed);
            set(level, origin.offset(x, 0, d-1), floor, placed);
        }

        // Courtyard cobblestone floor
        for (int x = 3; x <= 5; x++) {
            for (int z = 1; z < d-1; z++) {
                set(level, origin.offset(x, 0, z), Blocks.COBBLESTONE.defaultBlockState(), placed);
            }
        }

        // Central well
        BlockPos wellCenter = origin.offset(4, 0, 5);
        set(level, wellCenter.above(), Blocks.WATER.defaultBlockState(), placed);
        set(level, wellCenter.above().north(), Blocks.COBBLESTONE_WALL.defaultBlockState(), placed);
        set(level, wellCenter.above().south(), Blocks.COBBLESTONE_WALL.defaultBlockState(), placed);
        set(level, wellCenter.above().east(),  Blocks.COBBLESTONE_WALL.defaultBlockState(), placed);
        set(level, wellCenter.above().west(),  Blocks.COBBLESTONE_WALL.defaultBlockState(), placed);

        // Gate posts
        set(level, origin.offset(0, 1, 0), Blocks.STONE_BRICKS.defaultBlockState(), placed);
        set(level, origin.offset(0, 2, 0), Blocks.STONE_BRICKS.defaultBlockState(), placed);
        set(level, origin.offset(w-1, 1, 0), Blocks.STONE_BRICKS.defaultBlockState(), placed);
        set(level, origin.offset(w-1, 2, 0), Blocks.STONE_BRICKS.defaultBlockState(), placed);

        // Beds along back wing
        set(level, origin.offset(1, 1, d-2), bedFoot(culture, roadFacing(plan)), placed);
        set(level, origin.offset(2, 1, d-2), bedFoot(culture, roadFacing(plan)), placed);
        set(level, origin.offset(w-2, 1, d-2), bedFoot(culture, roadFacing(plan)), placed);
        set(level, origin.offset(w-3, 1, d-2), bedFoot(culture, roadFacing(plan)), placed);
    }

    // =========================================================================
    // ROADSIDE_SHRINE  (3×3 small stone shrine)
    // =========================================================================

    private static void placeShrine(ServerLevel level, BlockPos origin,
                                     ShelterPlanner.ShelterPlan plan,
                                     Random rng, List<BlockPos> placed) {
        // Stone slab base
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                set(level, origin.offset(x, 0, z), Blocks.POLISHED_ANDESITE.defaultBlockState(), placed);
            }
        }

        // Central pillar (chiseled)
        set(level, origin.offset(1, 1, 1), Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), placed);
        set(level, origin.offset(1, 2, 1), Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), placed);

        // Cap — stone brick slab
        set(level, origin.offset(1, 3, 1),
                Blocks.STONE_BRICK_SLAB.defaultBlockState(), placed);

        // Corner posts
        set(level, origin.offset(0, 1, 0), Blocks.STONE_BRICK_WALL.defaultBlockState(), placed);
        set(level, origin.offset(2, 1, 0), Blocks.STONE_BRICK_WALL.defaultBlockState(), placed);
        set(level, origin.offset(0, 1, 2), Blocks.STONE_BRICK_WALL.defaultBlockState(), placed);
        set(level, origin.offset(2, 1, 2), Blocks.STONE_BRICK_WALL.defaultBlockState(), placed);

        // Roof over corners — stone_brick_slab
        set(level, origin.offset(0, 2, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState(), placed);
        set(level, origin.offset(2, 2, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState(), placed);
        set(level, origin.offset(0, 2, 2), Blocks.STONE_BRICK_SLAB.defaultBlockState(), placed);
        set(level, origin.offset(2, 2, 2), Blocks.STONE_BRICK_SLAB.defaultBlockState(), placed);

        // Optional lantern on top (70% chance)
        if (rng.nextFloat() < 0.70f) {
            set(level, origin.offset(1, 4, 1), Blocks.LANTERN.defaultBlockState(), placed);
        }

        // Moss / candle offerings (15% each tile around base)
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                if (x == 1 && z == 1) continue;
                if (rng.nextFloat() < 0.25f) {
                    BlockPos candidate = origin.offset(x, 1, z);
                    if (level.getBlockState(candidate).isAir()) {
                        set(level, candidate, Blocks.CANDLE.defaultBlockState(), placed);
                    }
                }
            }
        }
    }

    // =========================================================================
    // RUINED_WATCHTOWER  (5×5 collapsed Old Realm tower)
    // =========================================================================

    private static void placeRuinedWatchtower(ServerLevel level, BlockPos origin,
                                               ShelterPlanner.ShelterPlan plan,
                                               Random rng, List<BlockPos> placed) {
        // Perimeter walls — lower intact section
        for (int x = 0; x < 5; x++) {
            for (int y = 1; y <= 4; y++) {
                // Rubble density: south face and some sides are collapsed
                if (x == 0 || x == 4 || y <= 2) {
                    set(level, origin.offset(x, y, 0),   ruinBlock(rng), placed);
                    set(level, origin.offset(x, y, 4),   ruinBlock(rng), placed);
                    set(level, origin.offset(0, y, x),   ruinBlock(rng), placed);
                    set(level, origin.offset(4, y, x),   ruinBlock(rng), placed);
                } else if (rng.nextFloat() < 0.6f) {
                    // Upper rows: 60% intact
                    set(level, origin.offset(x, y, 0),   ruinBlock(rng), placed);
                    set(level, origin.offset(x, y, 4),   ruinBlock(rng), placed);
                    set(level, origin.offset(0, y, x),   ruinBlock(rng), placed);
                    set(level, origin.offset(4, y, x),   ruinBlock(rng), placed);
                }
            }
        }

        // Partial floor
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                if (rng.nextFloat() < 0.7f) {
                    set(level, origin.offset(x, 0, z), Blocks.STONE_BRICKS.defaultBlockState(), placed);
                }
            }
        }

        // Collapsed roof debris — scattered around exterior
        for (int x = -1; x <= 5; x++) {
            for (int z = -1; z <= 5; z++) {
                if (rng.nextFloat() < 0.15f) {
                    BlockPos rubble = origin.offset(x, 0, z);
                    if (!level.isLoaded(rubble)) continue;
                    int ry = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            rubble.getX(), rubble.getZ());
                    set(level, new BlockPos(rubble.getX(), ry, rubble.getZ()),
                            Blocks.COBBLESTONE.defaultBlockState(), placed);
                }
            }
        }

        // Vine overgrowth on north wall (25% blocks)
        for (int y = 1; y <= 4; y++) {
            for (int x = 0; x < 5; x++) {
                if (rng.nextFloat() < 0.25f) {
                    BlockPos vinePos = origin.offset(x, y, 4);
                    if (level.isLoaded(vinePos) && level.getBlockState(vinePos).isAir()) {
                        set(level, vinePos, Blocks.VINE.defaultBlockState(), placed);
                    }
                }
            }
        }

        // Entrance opening — south face, middle
        level.removeBlock(origin.offset(2, 1, 0), false);
        level.removeBlock(origin.offset(2, 2, 0), false);

        // Old campfire inside
        set(level, origin.offset(2, 1, 2), Blocks.CAMPFIRE.defaultBlockState()
                .setValue(BlockStateProperties.LIT, false), placed);
    }

    // =========================================================================
    // RUINED_WAYSTATION  (7×7 partially roofed ruin)
    // =========================================================================

    private static void placeRuinedWaystation(ServerLevel level, BlockPos origin,
                                               ShelterPlanner.ShelterPlan plan,
                                               Random rng, List<BlockPos> placed) {
        // Foundation perimeter
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                if (x == 0 || x == 6 || z == 0 || z == 6) {
                    if (rng.nextFloat() < 0.8f) {
                        // Wall height: 1–3 blocks (heavily collapsed)
                        int wallH = 1 + rng.nextInt(3);
                        for (int y = 1; y <= wallH; y++) {
                            set(level, origin.offset(x, y, z), ruinBlock(rng), placed);
                        }
                    }
                    set(level, origin.offset(x, 0, z), Blocks.COBBLESTONE.defaultBlockState(), placed);
                }
            }
        }

        // Interior floor — cobblestone with gaps
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                if (rng.nextFloat() < 0.65f) {
                    set(level, origin.offset(x, 0, z), Blocks.COBBLESTONE.defaultBlockState(), placed);
                }
            }
        }

        // Surviving roof over back corner (3×3)
        for (int x = 1; x <= 3; x++) {
            for (int z = 3; z <= 5; z++) {
                set(level, origin.offset(x, 3, z), Blocks.STONE_BRICKS.defaultBlockState(), placed);
                // Support walls
                set(level, origin.offset(x, 2, z == 3 ? 3 : (z == 5 ? 5 : z)),
                        ruinBlock(rng), placed);
                set(level, origin.offset(x, 1, z), ruinBlock(rng), placed);
            }
        }

        // Single bed under surviving roof
        set(level, origin.offset(2, 1, 4), bedFoot("default", roadFacing(plan)), placed);
        set(level, origin.offset(2, 1, 5), bedHead("default", roadFacing(plan)), placed);

        // Broken well / cistern in center
        set(level, origin.offset(3, 0, 3), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), placed);
        set(level, origin.offset(3, 1, 3), Blocks.MOSSY_COBBLESTONE_WALL.defaultBlockState(), placed);

        // Scattered debris around exterior
        for (int ox = -1; ox <= 7; ox++) {
            for (int oz = -1; oz <= 7; oz++) {
                if ((ox >= 0 && ox < 7) && (oz >= 0 && oz < 7)) continue;
                if (rng.nextFloat() < 0.12f) {
                    BlockPos debrisRaw = origin.offset(ox, 0, oz);
                    if (!level.isLoaded(debrisRaw)) continue;
                    int dy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            debrisRaw.getX(), debrisRaw.getZ());
                    set(level, new BlockPos(debrisRaw.getX(), dy, debrisRaw.getZ()),
                            Blocks.COBBLESTONE.defaultBlockState(), placed);
                }
            }
        }
    }

    // =========================================================================
    // Terrain suitability
    // =========================================================================

    private static boolean isSuitable(ServerLevel level, BlockPos origin) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int ox = -3; ox <= 3; ox += 3) {
            for (int oz = -3; oz <= 3; oz += 3) {
                BlockPos sample = origin.offset(ox, 0, oz);
                if (!level.isLoaded(sample)) return false;
                int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        sample.getX(), sample.getZ());
                if (sy < minY) minY = sy;
                if (sy > maxY) maxY = sy;
            }
        }
        return (maxY - minY) <= MAX_HEIGHT_VARIANCE;
    }

    // =========================================================================
    // Culture material helpers
    // =========================================================================

    private static BlockState wallBlock(String culture) {
        if (culture == null) return Blocks.STONE_BRICKS.defaultBlockState();
        return switch (culture.toLowerCase(java.util.Locale.ROOT)) {
            case "nordic"    -> Blocks.SPRUCE_PLANKS.defaultBlockState();
            case "highland"  -> Blocks.STONE_BRICKS.defaultBlockState();
            case "imperial"  -> Blocks.SMOOTH_STONE.defaultBlockState();
            default          -> Blocks.COBBLESTONE.defaultBlockState();
        };
    }

    private static BlockState roofBlock(String culture) {
        if (culture == null) return Blocks.DARK_OAK_PLANKS.defaultBlockState();
        return switch (culture.toLowerCase(java.util.Locale.ROOT)) {
            case "nordic"    -> Blocks.SPRUCE_SLAB.defaultBlockState();
            case "highland"  -> Blocks.STONE_BRICK_SLAB.defaultBlockState();
            case "imperial"  -> Blocks.STONE_SLAB.defaultBlockState();
            default          -> Blocks.OAK_PLANKS.defaultBlockState();
        };
    }

    private static BlockState floorBlock(String culture) {
        if (culture == null) return Blocks.OAK_PLANKS.defaultBlockState();
        return switch (culture.toLowerCase(java.util.Locale.ROOT)) {
            case "nordic"   -> Blocks.SPRUCE_PLANKS.defaultBlockState();
            case "highland" -> Blocks.COBBLESTONE.defaultBlockState();
            case "imperial" -> Blocks.SMOOTH_STONE.defaultBlockState();
            default         -> Blocks.OAK_PLANKS.defaultBlockState();
        };
    }

    /** Bed foot block (colored by culture). */
    private static BlockState bedFoot(String culture, Direction facing) {
        return bedBlockState(culture, BedPart.FOOT, facing);
    }

    /** Bed head block (colored by culture). */
    private static BlockState bedHead(String culture, Direction facing) {
        return bedBlockState(culture, BedPart.HEAD, facing);
    }

    private static BlockState bedBlock(String culture, Direction facing) {
        return bedBlockState(culture, BedPart.FOOT, facing);
    }

    private static BlockState bedBlockState(String culture, BedPart part, Direction facing) {
        net.minecraft.world.level.block.BedBlock block = (net.minecraft.world.level.block.BedBlock)
                bedBlockFor(culture);
        return block.defaultBlockState()
                .setValue(BedBlock.PART, part)
                .setValue(BedBlock.FACING, facing);
    }

    private static net.minecraft.world.level.block.Block bedBlockFor(String culture) {
        if (culture == null) return Blocks.RED_BED;
        return switch (culture.toLowerCase(java.util.Locale.ROOT)) {
            case "nordic"    -> Blocks.WHITE_BED;
            case "imperial"  -> Blocks.PURPLE_BED;
            case "highland"  -> Blocks.BROWN_BED;
            case "old_realm" -> Blocks.GREEN_BED;
            default          -> Blocks.RED_BED;
        };
    }

    private static Direction roadFacing(ShelterPlanner.ShelterPlan plan) {
        // Convert road direction vector to the closest cardinal Direction
        int dx = plan.facingDx();
        int dz = plan.facingDz();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static BlockState ruinBlock(Random rng) {
        int roll = rng.nextInt(4);
        return switch (roll) {
            case 0 -> Blocks.STONE_BRICKS.defaultBlockState();
            case 1 -> Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
            case 2 -> Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
            default -> Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        };
    }

    // =========================================================================
    // Block placement helper
    // =========================================================================

    private static void set(ServerLevel level, BlockPos pos, BlockState state,
                             List<BlockPos> placed) {
        if (!level.isLoaded(pos)) return;
        level.setBlock(pos, state, 3);
        placed.add(pos.immutable());
    }
}
