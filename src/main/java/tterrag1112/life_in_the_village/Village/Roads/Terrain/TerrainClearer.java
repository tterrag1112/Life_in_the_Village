package tterrag1112.life_in_the_village.Village.Roads.Terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Clears vegetation from a corridor using connected-component feature detection.
 *
 * <h3>Policy</h3>
 * <ul>
 *   <li>If any log of a tree is within the clearance corridor: the whole tree is cleared.</li>
 *   <li>If only leaves (no log) are in the corridor: only those leaves are cleared; the trunk
 *       and canopy blocks outside the corridor are left intact.</li>
 *   <li>Grass, ferns, flowers, saplings, vines: always cleared from corridor positions.</li>
 *   <li>Player-placed leaves (PERSISTENT=true) are never cleared.</li>
 *   <li>Mushroom handling is governed by {@link MushroomPolicy}.</li>
 *   <li>Stripped logs are treated as building materials and are never cleared.</li>
 * </ul>
 *
 * <h3>Block-change event suppression</h3>
 * Callers must ensure
 * {@link tterrag1112.life_in_the_village.Village.Roads.Realization.RoadPlacementContext}
 * suppression is active when clearing from within road realization, so that cleared
 * blocks do not trigger stale-cell marking.
 */
public final class TerrainClearer {

    public enum MushroomPolicy {
        /** Clear mushrooms as if they were trees. */
        CLEAR,
        /** Never clear mushrooms, even if their stem is in the corridor. */
        PRESERVE,
        /** Clear mushroom only if its stem/cap is within the corridor. */
        CLEAR_IF_TRUNK
    }

    /**
     * @param treesCleared      number of trees fully removed
     * @param treesPartial      number of trees partially trimmed (only canopy in corridor)
     * @param mushroomsCleared  number of huge mushrooms removed
     * @param vegetationCleared number of individual grass/flower/sapling blocks removed
     * @param failedPositions   positions where clearance was attempted but block was unloaded
     */
    public record ClearanceResult(
            int treesCleared,
            int treesPartial,
            int mushroomsCleared,
            int vegetationCleared,
            List<BlockPos> failedPositions
    ) {
        public static ClearanceResult empty() {
            return new ClearanceResult(0, 0, 0, 0, Collections.emptyList());
        }
    }

    private TerrainClearer() {}

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Clears vegetation in the given XZ corridor.
     *
     * @param level                 server level
     * @param clearanceCorridorXZ   packed XZ keys (use {@link #xzKey}) of corridor columns
     * @param maxHeightAboveGround  how many blocks above ground surface to scan (e.g. 6)
     * @param mushroomPolicy        what to do with huge mushrooms
     * @return clearance statistics
     */
    public static ClearanceResult clear(ServerLevel level,
                                        Set<Long> clearanceCorridorXZ,
                                        int maxHeightAboveGround,
                                        MushroomPolicy mushroomPolicy) {
        if (clearanceCorridorXZ.isEmpty()) return ClearanceResult.empty();

        Set<BlockPos> toRemove    = new HashSet<>();
        Set<BlockPos> seenFeature = new HashSet<>(); // seeds already detected
        List<BlockPos> failed     = new ArrayList<>();

        int treesCleared = 0, treesPartial = 0, mushroomsCleared = 0, vegetation = 0;

        for (long xzKey : clearanceCorridorXZ) {
            int cx = unpackX(xzKey);
            int cz = unpackZ(xzKey);
            if (!level.isLoaded(new BlockPos(cx, 0, cz))) {
                failed.add(new BlockPos(cx, 0, cz));
                continue;
            }

            int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);

            // Scan from groundY-1 up to groundY + maxHeightAboveGround
            for (int y = groundY - 1; y <= groundY + maxHeightAboveGround; y++) {
                BlockPos pos = new BlockPos(cx, y, cz);
                if (seenFeature.contains(pos)) continue;

                BlockState state = level.getBlockState(pos);

                if (state.is(BlockTags.LOGS)) {
                    if (isStripped(state)) continue; // never clear building material
                    if (!seenFeature.contains(pos)) {
                        Set<BlockPos> feature = VegetationFeatureDetector.detectFeature(level, pos);
                        if (feature.isEmpty()) {
                            // Over safety cap — clear the individual block only
                            toRemove.add(pos.immutable());
                        } else {
                            seenFeature.addAll(feature);
                            // Trunk is in the corridor → clear whole tree
                            toRemove.addAll(feature);
                            treesCleared++;
                        }
                    }
                    continue;
                }

                if (state.is(BlockTags.LEAVES)) {
                    if (!seenFeature.contains(pos)) {
                        Set<BlockPos> feature = VegetationFeatureDetector.detectFeature(level, pos);
                        if (feature.isEmpty()) {
                            // Over cap or isolated leaf — just remove this block
                            toRemove.add(pos.immutable());
                        } else {
                            seenFeature.addAll(feature);
                            // Check if any log in the feature is in the corridor
                            boolean trunkInCorridor = false;
                            for (BlockPos fp : feature) {
                                if (!level.getBlockState(fp).is(BlockTags.LOGS)) continue;
                                if (clearanceCorridorXZ.contains(xzKey(fp.getX(), fp.getZ()))) {
                                    trunkInCorridor = true;
                                    break;
                                }
                            }
                            if (trunkInCorridor) {
                                toRemove.addAll(feature);
                                treesCleared++;
                            } else {
                                // Only remove the leaves that are inside the corridor
                                for (BlockPos fp : feature) {
                                    if (clearanceCorridorXZ.contains(xzKey(fp.getX(), fp.getZ()))) {
                                        toRemove.add(fp);
                                    }
                                }
                                treesPartial++;
                            }
                        }
                    }
                    continue;
                }

                if (isHugeMushroom(state)) {
                    if (mushroomPolicy == MushroomPolicy.PRESERVE) continue;
                    if (!seenFeature.contains(pos)) {
                        Set<BlockPos> feature = VegetationFeatureDetector.detectFeature(level, pos);
                        if (feature.isEmpty()) {
                            if (mushroomPolicy != MushroomPolicy.PRESERVE) toRemove.add(pos.immutable());
                        } else {
                            seenFeature.addAll(feature);
                            boolean trunkInCorridor = mushroomPolicy == MushroomPolicy.CLEAR;
                            if (!trunkInCorridor) {
                                // CLEAR_IF_TRUNK: check if any block is a stem and in the corridor
                                for (BlockPos fp : feature) {
                                    BlockState fs = level.getBlockState(fp);
                                    if (!fs.is(Blocks.MUSHROOM_STEM)) continue;
                                    if (clearanceCorridorXZ.contains(xzKey(fp.getX(), fp.getZ()))) {
                                        trunkInCorridor = true;
                                        break;
                                    }
                                }
                            }
                            if (trunkInCorridor) {
                                toRemove.addAll(feature);
                                mushroomsCleared++;
                            }
                        }
                    }
                    continue;
                }

                if (isSurfaceVegetation(state)) {
                    toRemove.add(pos.immutable());
                    vegetation++;
                }
            }
        }

        // Batch clear all collected positions
        for (BlockPos pos : toRemove) {
            if (!level.isLoaded(pos)) {
                failed.add(pos);
                continue;
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 18);
        }

        return new ClearanceResult(treesCleared, treesPartial, mushroomsCleared, vegetation, failed);
    }

    // =========================================================================
    // Corridor builders
    // =========================================================================

    /**
     * Builds the XZ corridor key-set for a dense block path at the given tier half-width.
     * The half-width covers the road body; one additional block of margin is added on each side.
     *
     * @param centerline     dense block path
     * @param halfWidth      inclusive half-width of the road (e.g. 4 for GREAT_ROAD)
     * @return packed XZ keys
     */
    public static Set<Long> buildRoadCorridor(List<BlockPos> centerline, int halfWidth) {
        Set<Long> keys = new HashSet<>();
        for (BlockPos center : centerline) {
            for (int dx = -halfWidth; dx <= halfWidth; dx++) {
                for (int dz = -halfWidth; dz <= halfWidth; dz++) {
                    keys.add(xzKey(center.getX() + dx, center.getZ() + dz));
                }
            }
        }
        return keys;
    }

    /**
     * Builds an XZ corridor key-set for a rectangular footprint centred on {@code origin},
     * extending {@code halfW} in X and {@code halfD} in Z.
     */
    public static Set<Long> buildFootprintCorridor(BlockPos origin, int halfW, int halfD) {
        Set<Long> keys = new HashSet<>((2 * halfW + 1) * (2 * halfD + 1));
        for (int dx = -halfW; dx <= halfW; dx++) {
            for (int dz = -halfD; dz <= halfD; dz++) {
                keys.add(xzKey(origin.getX() + dx, origin.getZ() + dz));
            }
        }
        return keys;
    }

    /**
     * Builds an XZ corridor key-set for a radius circle around {@code center}.
     */
    public static Set<Long> buildRadiusCorridor(BlockPos center, int radius) {
        Set<Long> keys = new HashSet<>();
        int rSq = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= rSq) {
                    keys.add(xzKey(center.getX() + dx, center.getZ() + dz));
                }
            }
        }
        return keys;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    public static long xzKey(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    private static int unpackX(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    private static int unpackZ(long key) {
        return (int) (key >>> 32);
    }

    private static boolean isStripped(BlockState state) {
        // Stripped logs are building materials (used in NPC housing, arches, etc.)
        net.minecraft.resources.ResourceLocation id =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(state.getBlock());
        return id != null && id.getPath().contains("stripped");
    }

    private static boolean isHugeMushroom(BlockState state) {
        return state.is(Blocks.BROWN_MUSHROOM_BLOCK)
                || state.is(Blocks.RED_MUSHROOM_BLOCK)
                || state.is(Blocks.MUSHROOM_STEM);
    }

    private static boolean isSurfaceVegetation(BlockState state) {
        return state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.SMALL_FLOWERS)
                || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.REPLACEABLE)
                || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.FERN)
                || state.is(Blocks.DEAD_BUSH)
                || state.is(Blocks.VINE)
                || state.is(Blocks.BAMBOO)
                || state.is(Blocks.BAMBOO_SAPLING)
                || state.is(Blocks.BROWN_MUSHROOM)
                || state.is(Blocks.RED_MUSHROOM)
                || state.is(Blocks.SUGAR_CANE);
    }
}
