// src/main/java/tterrag1112/life_in_the_village/Village/Decoration/TownSquarePlacer.java
package tterrag1112.life_in_the_village.Village.Decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Event.VillageEvent;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

/**
 * Procedurally constructs the TOWN_SQUARE and all of its decorations.
 *
 * <h3>Layout (radius R)</h3>
 * <pre>
 *         [lamppost]  [lamppost]
 *     ┌─────────────────────────┐
 *     │  cobble border          │
 *     │  ┌───────────────────┐  │
 *     │  │  paved interior   │  │
 *     │  │    [well/statue]  │  │
 *     │  │  [benches][trees] │  │
 *     │  └───────────────────┘  │
 *     │  cobble border          │
 *     └─────────────────────────┘
 *         [lamppost]  [lamppost]
 * </pre>
 *
 * The square is registered as a synthetic {@link Building} of type
 * {@link BuildingType#TOWN_SQUARE} so the path router and trade road
 * manager can connect to it.
 */
public class TownSquarePlacer {

    // Half-size of the town square in blocks (full side = 2*RADIUS+1)
    public static final int RADIUS = 1;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /**
     * Places the town square centred at {@code center}, registers it as
     * a building, and returns the set of block positions that make up the
     * paved surface (used to protect them from terrain smoothing).
     */
    public static Set<BlockPos> place(ServerLevel level,
                                      BlockPos center,
                                      VillageBiomeStyle style,
                                      VillageSizeTier tier,
                                      Village village,
                                      VillageSavedData data) {
        // Find the median ground Y across the square footprint to pick
        // a single flat target Y for the whole pad
        int targetY = medianGroundY(level, center, RADIUS);
        BlockPos flatCenter = new BlockPos(center.getX(), targetY, center.getZ());

        Set<BlockPos> pavedBlocks = new HashSet<>();

        // levelPad fills columns UP TO targetY - 1 (inclusive), leaving
        // targetY as the surface the paving block will replace.
        // Filling to targetY itself would push the pave block one too high.
        TerrainSmoother.levelPad(level,
                flatCenter.getX() - RADIUS, flatCenter.getZ() - RADIUS,
                flatCenter.getX() + RADIUS, flatCenter.getZ() + RADIUS,
                targetY - 1,                // ← fill to one below pave surface
                Collections.emptySet());

        // Pave at targetY — replacing whatever solid block is there.
        // targetY is MOTION_BLOCKING_NO_LEAVES result = the solid surface block.
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                BlockPos pavePos = new BlockPos(
                        flatCenter.getX() + dx,
                        targetY,
                        flatCenter.getZ() + dz);

                // Re-verify surface at this specific column — slope means
                // not every column is exactly targetY
                int colSurfY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        pavePos.getX(), pavePos.getZ());

                // If this column is lower than targetY, fill up to targetY - 1
                // then pave at targetY
                if (colSurfY < targetY) {
                    for (int y = colSurfY + 1; y < targetY; y++) {
                        BlockPos fill = new BlockPos(pavePos.getX(), y, pavePos.getZ());
                        if (level.getBlockState(fill).isAir()
                                || level.getBlockState(fill).is(BlockTags.REPLACEABLE)) {
                            level.setBlock(fill, Blocks.DIRT.defaultBlockState(), 3);
                        }
                    }
                } else if (colSurfY > targetY) {
                    // Carve down to targetY
                    for (int y = colSurfY; y > targetY; y--) {
                        BlockPos carve = new BlockPos(pavePos.getX(), y, pavePos.getZ());
                        BlockState cs = level.getBlockState(carve);
                        if (cs.is(Blocks.GRASS_BLOCK) || cs.is(Blocks.DIRT)
                                || cs.is(Blocks.COARSE_DIRT)
                                || cs.is(BlockTags.REPLACEABLE)) {
                            level.setBlock(carve, Blocks.AIR.defaultBlockState(), 3);
                        } else break;
                    }
                }

                // Place paving at targetY (the solid surface level)
                boolean isBorder = Math.abs(dx) == RADIUS
                        || Math.abs(dz) == RADIUS;
                boolean isInnerBorder = !isBorder
                        && (Math.abs(dx) == RADIUS - 1
                        || Math.abs(dz) == RADIUS - 1);

                BlockState pave = isBorder      ? style.stone.defaultBlockState()
                        : isInnerBorder  ? style.stoneSlab()
                        : style.pathState();

                level.setBlock(pavePos, pave, 3);

                // Clear vegetation above
                BlockPos above = pavePos.above();
                BlockState aboveState = level.getBlockState(above);
                if (!aboveState.isAir()
                        && (aboveState.is(BlockTags.REPLACEABLE)
                        || aboveState.is(BlockTags.FLOWERS)
                        || aboveState.is(BlockTags.SMALL_FLOWERS)
                        || aboveState.is(BlockTags.LEAVES))) {
                    level.setBlock(above, Blocks.AIR.defaultBlockState(), 3);
                }

                // pavedBlocks stores the SOLID pave block position (not air above)
                // so that protectedXZ checks in VillageDecorator use the correct Y
                pavedBlocks.add(pavePos);
            }
        }
        // ── Central feature ──────────────────────────────────────────────────
        placeCenter(level, flatCenter, targetY, style, tier);

        // ── Corner lampposts ─────────────────────────────────────────────────
        placeCornerLampposts(level, flatCenter, targetY, style);

        // ── Benches and planters ─────────────────────────────────────────────
        placeBenchesAndPlanters(level, flatCenter, targetY, style, tier);

        // ── Notice board ─────────────────────────────────────────────────────
        placeNoticeBoard(level, flatCenter, targetY, style);

        // ── Register as a building ───────────────────────────────────────────
        registerAsBuilding(level, flatCenter, village, data);

        System.out.println("TownSquarePlacer: placed town square at "
                + flatCenter + " (Y=" + targetY + ")");

        return pavedBlocks;
    }

    // -------------------------------------------------------------------------
    // Event-driven redecoration
    // -------------------------------------------------------------------------

    /**
     * Clears the previous event decorations from the square and places
     * new ones appropriate for the current active event.
     * Called by {@link tterrag1112.life_in_the_village.Village.Event.EventEffects}.
     */
    public static List<BlockPos> decorateForEvent(ServerLevel level,
                                                  BlockPos squareCenter,
                                                  VillageBiomeStyle style,
                                                  VillageEvent event) {
        List<BlockPos> placed = new ArrayList<>();
        int y = squareCenter.getY();

        switch (event.getType()) {
            case VILLAGE_FAIR     -> placeFairDecorations(level, squareCenter, y, placed);
            case MARKET_DAY       -> placeMarketDecorations(level, squareCenter, y, style, placed);
            case FESTIVAL_OF_LIGHTS -> placeLightFestivalDecorations(level, squareCenter, y, placed);
            case HARVEST_FESTIVAL -> placeHarvestDecorations(level, squareCenter, y, placed);
            case TRAINING_DAY     -> placeTrainingDecorations(level, squareCenter, y, placed);
            default               -> {} // no change for other events
        }
        return placed;
    }

    // -------------------------------------------------------------------------
    // Central feature
    // -------------------------------------------------------------------------

    private static void placeCenter(ServerLevel level, BlockPos flatCenter,
                                    int targetY,
                                    VillageBiomeStyle style,
                                    VillageSizeTier tier) {
        BlockPos base = new BlockPos(
                flatCenter.getX(), targetY, flatCenter.getZ());

        if (tier.hasWell) {
            // Decorative well with full stone surround
            level.setBlock(base, Blocks.WATER.defaultBlockState(), 3);
            level.setBlock(base.above(), style.stoneWallState(), 3);
            // Cardinal walls
            for (BlockPos side : List.of(
                    base.north(), base.south(),
                    base.east(), base.west())) {
                level.setBlock(side, style.stone.defaultBlockState(), 3);
                level.setBlock(side.above(), style.fenceState(), 3);
            }
            // Diagonal slabs
            for (int[] d : new int[][]{{1,1},{1,-1},{-1,1},{-1,-1}}) {
                level.setBlock(
                        base.offset(d[0], 0, d[1]),
                        style.stoneSlab(), 3);
            }
            level.setBlock(base.above(2), style.lanternState(), 3);
        } else {
            // Small obelisk for hamlets
            level.setBlock(base, style.stone.defaultBlockState(), 3);
            level.setBlock(base.above(), style.stone.defaultBlockState(), 3);
            level.setBlock(base.above(2), style.stoneSlab(), 3);
            level.setBlock(base.above(3), style.lanternState(), 3);
        }
    }

    // -------------------------------------------------------------------------
    // Corner lampposts
    // -------------------------------------------------------------------------

    private static void placeCornerLampposts(ServerLevel level,
                                             BlockPos center, int y,
                                             VillageBiomeStyle style) {
        int inner = RADIUS - 2;
        for (int[] corner : new int[][]{{inner,inner},{inner,-inner},{-inner,inner},{-inner,-inner}}) {
            BlockPos base = new BlockPos(
                    center.getX() + corner[0], y,
                    center.getZ() + corner[1]);
            level.setBlock(base,         style.fenceState(),   3);
            level.setBlock(base.above(), style.fenceState(),   3);
            level.setBlock(base.above(2),style.lanternState(), 3);
        }
    }

    private static void placeBenchesAndPlanters(ServerLevel level,
                                                BlockPos center, int y,
                                                VillageBiomeStyle style,
                                                VillageSizeTier tier) {
        int bench = RADIUS - 3;
        int[][] benchDirs = {{bench,0},{-bench,0},{0,bench},{0,-bench}};
        for (int[] bd : benchDirs) {
            BlockPos bpos = new BlockPos(
                    center.getX() + bd[0], y,
                    center.getZ() + bd[1]);
            level.setBlock(bpos, style.woodSlab(), 3);
            level.setBlock(bpos.offset(
                            bd[0] == 0 ? 1 : 0, 0, bd[1] == 0 ? 1 : 0),
                    style.woodSlab(), 3);
        }
        if (tier.flowerAttempts > 0) {
            int planter = RADIUS - 4;
            for (int[] corner : new int[][]{{planter,planter},{planter,-planter},{-planter,planter},{-planter,-planter}}) {
                BlockPos pos = new BlockPos(
                        center.getX() + corner[0], y,
                        center.getZ() + corner[1]);
                level.setBlock(pos,         Blocks.COMPOSTER.defaultBlockState(), 3);
                level.setBlock(pos.above(), style.flowerState(),                  3);
            }
        }
    }

    private static void placeNoticeBoard(ServerLevel level,
                                         BlockPos center, int y,
                                         VillageBiomeStyle style) {
        BlockPos pos = new BlockPos(
                center.getX() + RADIUS - 3, y, center.getZ());
        level.setBlock(pos, Blocks.LECTERN.defaultBlockState(), 3);
    }

    // -------------------------------------------------------------------------
    // Event decorations
    // -------------------------------------------------------------------------

    private static void placeFairDecorations(ServerLevel level,
                                             BlockPos center, int y,
                                             List<BlockPos> placed) {
        BlockState[] colors = {
                Blocks.RED_WOOL.defaultBlockState(),
                Blocks.YELLOW_WOOL.defaultBlockState(),
                Blocks.BLUE_WOOL.defaultBlockState(),
                Blocks.GREEN_WOOL.defaultBlockState(),
                Blocks.PURPLE_WOOL.defaultBlockState()
        };
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            int dx = (int)(Math.cos(angle) * (RADIUS - 3));
            int dz = (int)(Math.sin(angle) * (RADIUS - 3));
            BlockPos base = new BlockPos(
                    center.getX() + dx, y , center.getZ() + dz);
            level.setBlock(base, colors[i % colors.length], 3);
            level.setBlock(base.above(), Blocks.OAK_FENCE.defaultBlockState(), 3);
            level.setBlock(base.above(2), Blocks.LANTERN.defaultBlockState(), 3);
            placed.add(base); placed.add(base.above()); placed.add(base.above(2));
        }
    }

    private static void placeMarketDecorations(ServerLevel level,
                                               BlockPos center, int y,
                                               VillageBiomeStyle style,
                                               List<BlockPos> placed) {
        // Four market stalls around the center
        int[][] stallPositions = {{4,0},{-4,0},{0,4},{0,-4}};
        BlockState[] carpets = {
                Blocks.RED_CARPET.defaultBlockState(),
                Blocks.BLUE_CARPET.defaultBlockState(),
                Blocks.YELLOW_CARPET.defaultBlockState(),
                Blocks.GREEN_CARPET.defaultBlockState()
        };
        for (int i = 0; i < 4; i++) {
            BlockPos stallBase = new BlockPos(
                    center.getX() + stallPositions[i][0],
                    y,
                    center.getZ() + stallPositions[i][1]);
            // 3-block carpet strip
            for (int s = -1; s <= 1; s++) {
                BlockPos carpet = stallBase.offset(
                        stallPositions[i][1] != 0 ? s : 0, 0,
                        stallPositions[i][0] != 0 ? s : 0);
                level.setBlock(carpet, carpets[i], 3);
                placed.add(carpet);
            }
            // Barrel on top of centre
            level.setBlock(stallBase.above(),
                    Blocks.BARREL.defaultBlockState(), 3);
            placed.add(stallBase.above());
            // Canopy pole
            level.setBlock(stallBase.above(2),
                    style.fenceState(), 3);
            placed.add(stallBase.above(2));
        }
    }

    private static void placeLightFestivalDecorations(ServerLevel level,
                                                      BlockPos center, int y,
                                                      List<BlockPos> placed) {
        // Ring of lanterns on the perimeter
        for (int deg = 0; deg < 360; deg += 20) {
            double rad = Math.toRadians(deg);
            int dx = (int)(Math.cos(rad) * (RADIUS - 2));
            int dz = (int)(Math.sin(rad) * (RADIUS - 2));
            BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
            level.setBlock(pos, Blocks.LANTERN.defaultBlockState(), 3);
            placed.add(pos);
        }
        // Glowstone cluster at center
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos pos = new BlockPos(center.getX() + dx, y , center.getZ() + dz);
                level.setBlock(pos, Blocks.GLOWSTONE.defaultBlockState(), 3);
                placed.add(pos);
            }
        }
    }

    private static void placeHarvestDecorations(ServerLevel level,
                                                BlockPos center, int y,
                                                List<BlockPos> placed) {
        int[][] offsets = {{3,0},{-3,0},{0,3},{0,-3},{3,3},{-3,-3},{3,-3},{-3,3}};
        boolean toggle = true;
        for (int[] off : offsets) {
            BlockPos pos = new BlockPos(
                    center.getX() + off[0], y , center.getZ() + off[1]);
            level.setBlock(pos, toggle
                    ? Blocks.HAY_BLOCK.defaultBlockState()
                    : Blocks.CARVED_PUMPKIN.defaultBlockState(), 3);
            placed.add(pos);
            toggle = !toggle;
        }
    }

    private static void placeTrainingDecorations(ServerLevel level,
                                                 BlockPos center, int y,
                                                 List<BlockPos> placed) {
        // Target dummies (hay bales) in a row
        for (int i = -2; i <= 2; i++) {
            BlockPos pos = new BlockPos(center.getX() + i * 2, y , center.getZ() + 5);
            level.setBlock(pos, Blocks.HAY_BLOCK.defaultBlockState(), 3);
            placed.add(pos);
        }
        // Weapon rack (fences) on the opposite side
        for (int i = -1; i <= 1; i++) {
            BlockPos pos = new BlockPos(center.getX() + i * 2, y , center.getZ() - 5);
            level.setBlock(pos, Blocks.OAK_FENCE.defaultBlockState(), 3);
            placed.add(pos);
        }
    }

    // -------------------------------------------------------------------------
    // Building registration
    // -------------------------------------------------------------------------

    // In TownSquarePlacer.java — replace the registerAsBuilding method entirely

    private static void registerAsBuilding(ServerLevel level,
                                           BlockPos center,
                                           Village village,
                                           VillageSavedData data) {
        int side   = RADIUS * 2 + 1;
        // Origin at the SW corner, one block below the paved surface.
        BlockPos origin = new BlockPos(
                center.getX() - RADIUS,
                center.getY() ,
                center.getZ() - RADIUS);

        // Height of 64 ensures getBuildingAt() finds the square regardless
        // of whether the player is standing on the pavement or nearby.
        Building.BuildingShape shape = new Building.BuildingShape(
                origin,
                side,   // width  (X)
                64,     // height (Y) — covers ground to roof generously
                side    // length (Z)
        );

        Identifier structId = Identifier.fromNamespaceAndPath(
                tterrag1112.life_in_the_village.Life_in_the_village.MODID,
                "town_square");

        Building square = new Building(
                "town_square",
                BuildingType.TOWN_SQUARE,
                shape,
                structId,
                net.minecraft.world.level.block.Rotation.NONE,
                0);

        data.addBuilding(square);
        village.addBuilding(square);
        data.setDirty();

        System.out.println("TownSquarePlacer: registered town square "
                + square.getId() + " at " + origin
                + " (side=" + side + ", h=64)");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static int medianGroundY(ServerLevel level,
                                     BlockPos center, int radius) {
        List<Integer> ys = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx += 2) {
            for (int dz = -radius; dz <= radius; dz += 2) {
                ys.add(level.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types
                                .MOTION_BLOCKING_NO_LEAVES,
                        center.getX() + dx,
                        center.getZ() + dz));
            }
        }
        Collections.sort(ys);
        return ys.get(ys.size() / 2);
    }
}