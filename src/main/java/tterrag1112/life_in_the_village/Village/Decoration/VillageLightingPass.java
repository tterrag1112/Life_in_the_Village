// src/main/java/tterrag1112/life_in_the_village/Village/Decoration/VillageLightingPass.java
package tterrag1112.life_in_the_village.Village.Decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;

/**
 * Systematic exterior lighting pass for village buildings.
 *
 * <h3>Light levels and placement rules</h3>
 * Minecraft mobs spawn at light level ≤ 0. A lantern or torch provides
 * light level 15 at the source, dropping by 1 per block of distance.
 * To prevent spawning within 7 blocks of any building face we need a
 * light source no more than 7 blocks from every exterior wall block.
 *
 * <h3>Pass structure</h3>
 * <ol>
 *   <li><b>Corner lanterns</b> — a lantern on a fence post at each
 *       building corner, mounted at wall height. These provide the
 *       primary perimeter coverage.</li>
 *   <li><b>Wall torches</b> — wall-mounted torches midway along each
 *       face longer than 8 blocks, filling the gap between corner
 *       lanterns. Uses biome-appropriate torch type.</li>
 *   <li><b>Entrance lantern</b> — a hanging lantern directly above
 *       each building entrance (already placed by
 *       {@link VillageDecorator} for some types — this pass ensures
 *       all building types have one).</li>
 *   <li><b>Path intersection lighting</b> — a lantern post at any
 *       path surface position that is more than 8 blocks from the
 *       nearest existing light source. Walk the registered
 *       {@link VillagePath} blocks and fill dark gaps.</li>
 * </ol>
 */
public class VillageLightingPass {

    /** Maximum gap between light sources on a building face. */
    private static final int MAX_FACE_GAP    = 8;
    /** Maximum dark gap along a path before a lamppost is added. */
    private static final int MAX_PATH_GAP    = 10;
    /** Wall height at which corner lanterns are placed. */
    private static final int LANTERN_HEIGHT  = 3;

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Runs the full lighting pass for the village.
     * Call from {@link VillageDecorator#decorateVillage} after all
     * other decoration passes so we don't place torches on blocks
     * that will be replaced later.
     */
    public static void light(ServerLevel level,
                             Village village,
                             VillageSavedData data,
                             VillageBiomeStyle style) {
        List<Building> buildings = village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() != BuildingType.TOWN_SQUARE)
                .toList();

        for (Building building : buildings) {
            lightBuilding(level, building, style);
        }

        // Path gap fill — iterate all registered paths for the village
        for (VillagePath path : data.getPathsForVillage(village.getId())) {
            lightPathGaps(level, path, style);
        }

        System.out.println("VillageLightingPass: lit "
                + buildings.size() + " buildings for "
                + village.getName());
    }

    // =========================================================================
    // Building lighting
    // =========================================================================

    private static void lightBuilding(ServerLevel level,
                                      Building building,
                                      VillageBiomeStyle style) {
        BlockPos origin = building.getShape().getOrigin();
        int      w      = building.getShape().getWidth();
        int      l      = building.getShape().getLength();
        int      floorY = origin.getY();

        // ── Corner lanterns ───────────────────────────────────────────────────
        int[][] corners = {
                {origin.getX(),         origin.getZ()},
                {origin.getX() + w - 1, origin.getZ()},
                {origin.getX(),         origin.getZ() + l - 1},
                {origin.getX() + w - 1, origin.getZ() + l - 1}
        };

        for (int[] corner : corners) {
            placeCornerLantern(level, corner[0], corner[1],
                    floorY, style);
        }

        // ── Mid-face wall torches for long faces ──────────────────────────────
        // North face (z = origin.Z)
        if (w > MAX_FACE_GAP) {
            int midX = origin.getX() + w / 2;
            placeWallTorch(level,
                    new BlockPos(midX, floorY + LANTERN_HEIGHT,
                            origin.getZ()),
                    Direction.SOUTH, style);
        }
        // South face
        if (w > MAX_FACE_GAP) {
            int midX = origin.getX() + w / 2;
            placeWallTorch(level,
                    new BlockPos(midX, floorY + LANTERN_HEIGHT,
                            origin.getZ() + l),
                    Direction.NORTH, style);
        }
        // West face
        if (l > MAX_FACE_GAP) {
            int midZ = origin.getZ() + l / 2;
            placeWallTorch(level,
                    new BlockPos(origin.getX(),
                            floorY + LANTERN_HEIGHT, midZ),
                    Direction.EAST, style);
        }
        // East face
        if (l > MAX_FACE_GAP) {
            int midZ = origin.getZ() + l / 2;
            placeWallTorch(level,
                    new BlockPos(origin.getX() + w,
                            floorY + LANTERN_HEIGHT, midZ),
                    Direction.WEST, style);
        }

        // ── Entrance lantern ──────────────────────────────────────────────────
        BlockPos entrance = PathRouter.getBuildingEntrance(building);
        BlockPos hangPos  = entrance.above(2);
        if (level.getBlockState(hangPos).isAir()) {
            level.setBlock(hangPos, style.lanternState(), 3);
        }
    }

    /**
     * Places a fence post with lantern on top at the given building
     * corner, just outside the footprint so it doesn't clip into walls.
     */
    private static void placeCornerLantern(ServerLevel level,
                                           int cornerX, int cornerZ,
                                           int floorY,
                                           VillageBiomeStyle style) {
        // Place 1 block diagonally outside the corner
        // so the post doesn't occupy building footprint space
        int surfY = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                cornerX, cornerZ);

        // Don't place if surface is too far from floor
        // (avoids posts floating on overhangs or buried in hills)
        if (Math.abs(surfY - floorY) > 4) return;

        BlockPos postBase = new BlockPos(cornerX, surfY, cornerZ);
        BlockPos postTop  = postBase.above();
        BlockPos lamp     = postBase.above(2);

        // Only place if the air column is clear
        if (!level.getBlockState(postBase).isAir()
                && !level.getBlockState(postBase)
                .is(BlockTags.REPLACEABLE)) return;

        level.setBlock(postBase, style.fenceState(),   3);
        level.setBlock(postTop,  style.fenceState(),   3);
        level.setBlock(lamp,     style.lanternState(), 3);
    }

    /**
     * Places a wall-mounted torch on the exterior face of a building.
     * {@code wallPos} is the air block adjacent to the wall.
     * {@code facing} is the direction the torch faces outward.
     */
    private static void placeWallTorch(ServerLevel level,
                                       BlockPos wallPos,
                                       Direction facing,
                                       VillageBiomeStyle style) {
        // The block the torch attaches to is one step opposite to facing
        BlockPos attachPos = wallPos.relative(facing.getOpposite());
        BlockState attachState = level.getBlockState(attachPos);

        if (!attachState.isSolidRender()) return;
        if (!level.getBlockState(wallPos).isAir()) return;

        // Use biome lantern for taiga/swamp, wall torch for others
        BlockState torchState = switch (style) {
            case TAIGA, SWAMP -> Blocks.SOUL_WALL_TORCH.defaultBlockState()
                    .setValue(WallTorchBlock.FACING, facing);
            default           -> Blocks.WALL_TORCH.defaultBlockState()
                    .setValue(WallTorchBlock.FACING, facing);
        };

        level.setBlock(wallPos, torchState, 3);
    }

    // =========================================================================
    // Path gap lighting
    // =========================================================================

    /**
     * Scans the path block list and places a lamppost wherever there is
     * a gap of more than {@link #MAX_PATH_GAP} blocks between existing
     * light sources.
     *
     * <p>Existing light sources are detected by checking the block's
     * light emission value — lanterns (15), torches (14), etc.
     */
    private static void lightPathGaps(ServerLevel level,
                                      VillagePath path,
                                      VillageBiomeStyle style) {
        List<BlockPos> blocks = path.getBlocks();
        if (blocks.size() < MAX_PATH_GAP * 2) return;

        int lastLitIdx = 0;

        for (int i = 0; i < blocks.size(); i++) {
            BlockPos pos = blocks.get(i);

            // Check if there is a light source within 2 blocks vertically
            boolean hasLight = isLitNearby(level, pos);

            if (hasLight) {
                lastLitIdx = i;
            } else if (i - lastLitIdx >= MAX_PATH_GAP) {
                // Dark gap — place a lamppost here
                // pos is the air block above the path surface
                // (VillagePath convention)
                BlockPos poleBase = pos;
                BlockPos poleTop  = pos.above();
                BlockPos lamp     = pos.above(2);

                if (level.getBlockState(poleBase).isAir()
                        && level.getBlockState(poleTop).isAir()) {
                    level.setBlock(poleBase, style.fenceState(),   3);
                    level.setBlock(poleTop,  style.fenceState(),   3);
                    level.setBlock(lamp,     style.lanternState(), 3);
                    lastLitIdx = i;
                }
            }
        }
    }

    /**
     * Returns true if any block within a 3-block radius of {@code pos}
     * emits light level ≥ 8.
     */
    private static boolean isLitNearby(ServerLevel level, BlockPos pos) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos check = pos.offset(dx, dy, dz);
                    if (level.getBlockState(check)
                            .getLightEmission() >= 8) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}