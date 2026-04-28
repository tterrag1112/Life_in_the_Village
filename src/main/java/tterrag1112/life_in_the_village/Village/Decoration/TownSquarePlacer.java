// src/main/java/tterrag1112/life_in_the_village/Village/Decoration/TownSquarePlacer.java
package tterrag1112.life_in_the_village.Village.Decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Village.Event.VillageEvent;

import java.util.ArrayList;
import java.util.List;

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
/**
 * Phase 1 doc 04 — placement responsibilities migrated to
 * {@link tterrag1112.life_in_the_village.Village.Decoration.TownSquare
 * .TownSquareComposer}. This class is reduced to event-time
 * redecoration helpers (MARKET_DAY stalls, harvest festival props,
 * etc.) called from {@code EventEffects}. The {@link #RADIUS}
 * constant remains as the offset basis for those helpers; tier-
 * scaled plaza geometry is held on the {@link
 * tterrag1112.life_in_the_village.Village.Village} record.
 */
public class TownSquarePlacer {

    /** Legacy half-extent referenced by the event-decoration helpers
     *  for offset positioning. The plaza's real half-extent now lives
     *  on {@code Village.getTownSquareRadius()} and is tier-scaled
     *  via {@link tterrag1112.life_in_the_village.Village.Decoration
     *  .TownSquare.TownSquareTier}. Don't add new readers of this
     *  constant — read the village's stored radius instead. */
    public static final int RADIUS = 1;

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

}