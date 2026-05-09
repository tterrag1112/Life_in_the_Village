package tterrag1112.life_in_the_village.Village.Roads.Lighting;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.CulturePalette;
import tterrag1112.life_in_the_village.Village.Roads.Decoration.RoadClearanceValidator;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.ArrayList;
import java.util.List;

/**
 * Places Phase 7g road lighting along a realised {@link RoadEdge} according to
 * the resolved {@link RoadLightingProfile}.
 *
 * <h3>Algorithm</h3>
 * Walk {@link RoadEdge#getBlockPath()} by accumulated XZ block distance. Every
 * {@code profile.spacing()} blocks, check the strategy's gating rules and —
 * if satisfied — place a pedestal/light/cap column on one or both sides of
 * the road, offset {@code halfWidth + 1} blocks perpendicular from the
 * centerline and ground-snapped via {@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES}.
 *
 * <h3>Determinism</h3>
 * Placement decisions (alternating side, CENTERLINE skip) derive from an
 * edge-ID-seeded counter. The same edge always produces the same lighting.
 *
 * <h3>Road-clearance integration</h3>
 * Each candidate position is checked with
 * {@link RoadClearanceValidator#isClearOfRoads} at offset 0 to ensure the
 * light doesn't overlap any realised road. Overlapping candidates are dropped.
 */
public final class RoadLightingPlacer {

    private RoadLightingPlacer() {}

    /**
     * Placement result summary.
     *
     * @param lightsPlaced total column-count placed
     * @param positions    pedestal base positions (Y = ground, not Y+1 light)
     */
    public record PlacementResult(int lightsPlaced, List<BlockPos> positions) {
        public static PlacementResult empty() {
            return new PlacementResult(0, List.of());
        }
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Places lighting along the given edge.
     *
     * @param level    server level
     * @param edge     source edge (must be realised; otherwise empty result)
     * @param palette  culture palette supplying light/base/cap blocks
     * @param profile  resolved lighting profile (frequency × strategy)
     * @param graph    world road graph, for clearance validation
     * @return the positions of placed pedestals (for tracking / debug)
     */
    public static PlacementResult placeLighting(ServerLevel level,
                                                 RoadEdge edge,
                                                 CulturePalette palette,
                                                 RoadLightingProfile profile,
                                                 WorldRoadGraph graph) {
        // Backwards-compat overload — falls back to edge.getBlockPath(), which
        // is the full-width placed-block list. Callers post-Session-B5 should
        // pass an explicit centerline via the 6-arg overload below.
        return placeLighting(level, edge, edge.getBlockPath(), palette, profile, graph);
    }

    /**
     * Session B5 — explicit centerline overload. The edge's persisted
     * {@code blockPath} is the full-width placed-block list (one block per
     * lateral position per centerline step) and is unsuitable for step-wise
     * iteration along the road direction. Callers in the realisation pipeline
     * already have the per-primitive centerline available — pass it here so
     * spacing math and perpendicular offsets work correctly.
     */
    public static PlacementResult placeLighting(ServerLevel level,
                                                 RoadEdge edge,
                                                 List<BlockPos> centerline,
                                                 CulturePalette palette,
                                                 RoadLightingProfile profile,
                                                 WorldRoadGraph graph) {
        if (profile.isNone()) return PlacementResult.empty();
        // JUNCTIONS_ONLY is handled by junction decorators, not this placer.
        if (profile.strategy() == RoadLightingProfile.Strategy.JUNCTIONS_ONLY) {
            return PlacementResult.empty();
        }

        List<BlockPos> path = centerline;
        if (path.size() < 2) return PlacementResult.empty();

        int halfWidth = halfWidthFor(edge.getTier());
        int spacing   = profile.spacing();
        long edgeSeed = edge.getEdgeId().getLeastSignificantBits()
                      ^ edge.getEdgeId().getMostSignificantBits();

        WorldAtlas atlas = profile.requiresBiomeCheck() ? WorldAtlas.get(level) : null;

        List<BlockPos> placed = new ArrayList<>();
        int intervalIndex = 0;
        int accumulated   = 0;

        for (int i = 1; i < path.size(); i++) {
            BlockPos prev = path.get(i - 1);
            BlockPos cur  = path.get(i);
            accumulated += blockDistXZ(prev, cur);

            if (accumulated < spacing) continue;
            accumulated = 0;
            intervalIndex++;

            // Strategy gating
            if (profile.requiresBiomeCheck() && atlas != null) {
                AtlasCell cell = atlas.getCellAtBlock(cur.getX(), cur.getZ());
                if (cell == null || cell.category() != BiomeCategory.FOREST) continue;
            }

            int[] perp = computePerp(path, i);

            List<Integer> sides = sidesForStrategy(profile.strategy(), intervalIndex, edgeSeed);
            for (int side : sides) {
                int px;
                int pz;
                if (side == 0) {
                    // CENTERLINE — place on the centerline itself
                    px = cur.getX();
                    pz = cur.getZ();
                } else {
                    px = cur.getX() + perp[0] * side * (halfWidth + 1);
                    pz = cur.getZ() + perp[1] * side * (halfWidth + 1);
                }

                if (!level.isLoaded(new BlockPos(px, level.getMinY() + 1, pz))) continue;

                int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
                BlockPos base = new BlockPos(px, gy, pz);

                // For CENTERLINE, skip if the base overlaps the road (any realised edge).
                // radiusBlocks=1 catches any road block at the same XZ column.
                if (side == 0 && !RoadClearanceValidator.isClearOfRoads(base, graph, 1)) {
                    continue;
                }

                if (placeColumn(level, base, palette)) {
                    placed.add(base);
                    edge.addDecorationPosition(base);
                }
            }
        }

        return new PlacementResult(placed.size(), placed);
    }

    // =========================================================================
    // Column placement
    // =========================================================================

    private static boolean placeColumn(ServerLevel level, BlockPos base, CulturePalette palette) {
        BlockPos pedestal = base;
        BlockPos light    = base.above();
        BlockPos cap      = base.above(2);

        // Require pedestal position to be replaceable (air / grass / etc.) or already solid ground.
        // We want the base block to land on top of the existing terrain surface.
        BlockState existing = level.getBlockState(pedestal);
        if (!existing.isAir() && !existing.canBeReplaced()) return false;
        if (!level.getBlockState(light).isAir()
                && !level.getBlockState(light).canBeReplaced()) return false;

        level.setBlock(pedestal, palette.lightBaseBlock().defaultBlockState(), 3);
        level.setBlock(light,    palette.lightBlock().defaultBlockState(),     3);
        palette.lightCapBlock().ifPresent(c -> {
            BlockState above = level.getBlockState(cap);
            if (above.isAir() || above.canBeReplaced()) {
                level.setBlock(cap, c.defaultBlockState(), 3);
            }
        });
        return true;
    }

    // =========================================================================
    // Strategy helpers
    // =========================================================================

    /**
     * Returns the list of sides for a single interval.
     * {@code -1} = left, {@code +1} = right, {@code 0} = centerline.
     */
    private static List<Integer> sidesForStrategy(RoadLightingProfile.Strategy strategy,
                                                   int intervalIndex,
                                                   long edgeSeed) {
        return switch (strategy) {
            case BOTH_SIDES, FOREST_ONLY_BOTH ->
                    List.of(-1, 1);
            case ALTERNATING_SIDES, FOREST_ONLY_ALTERNATING -> {
                // Deterministic alternation: seed the first side with the edge seed
                // so identical edges always start on the same side.
                boolean startLeft = (edgeSeed & 1L) == 0L;
                int side = (intervalIndex % 2 == 0) == startLeft ? -1 : 1;
                yield List.of(side);
            }
            case SINGLE_SIDE_LEFT  -> List.of(-1);
            case SINGLE_SIDE_RIGHT -> List.of(1);
            case CENTERLINE        -> List.of(0);
            // NONE / JUNCTIONS_ONLY are filtered before this point.
            case NONE, JUNCTIONS_ONLY -> List.of();
        };
    }

    // =========================================================================
    // Tier → halfWidth
    // =========================================================================

    /** Mirrors the RoadShape half-widths for each edge tier. */
    private static int halfWidthFor(RoadEdge.EdgeTier tier) {
        return switch (tier) {
            case GREAT_ROAD -> 4; // CAPITAL_ROAD
            case TRUNK      -> 3; // TOWN_ROAD
            case CONNECTOR  -> 2; // VILLAGE_ROAD
            case LOCAL      -> 1; // FOOTPATH
            // Track C3.3 — SEA edges have no width; lighting pass is
            // gated separately to skip them.
            case SEA        -> 0;
        };
    }

    // =========================================================================
    // Geometry helpers
    // =========================================================================

    /**
     * Returns a {x,z} unit vector perpendicular to the local road direction at
     * index {@code i} of {@code path}. Mirrors the convention used by
     * milestone/overgrowth decorators: +1 = right of heading.
     */
    static int[] computePerp(List<BlockPos> path, int i) {
        BlockPos prev = i > 0 ? path.get(i - 1) : path.get(i);
        BlockPos next = i < path.size() - 1 ? path.get(i + 1) : path.get(i);
        int dx = next.getX() - prev.getX();
        int dz = next.getZ() - prev.getZ();
        int headX = Integer.signum(dx);
        int headZ = Integer.signum(dz);
        if (headX == 0 && headZ == 0) return new int[]{1, 0};
        // Perpendicular (right-hand): (dz, -dx). For unit axis: (-headZ, headX).
        if (headX != 0 && headZ != 0) {
            // Diagonal heading — collapse to an axis based on index parity for stable pattern.
            return (i & 1) == 0 ? new int[]{0, 1} : new int[]{1, 0};
        }
        return new int[]{-headZ, headX};
    }

    private static int blockDistXZ(BlockPos a, BlockPos b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dz = Math.abs(a.getZ() - b.getZ());
        // Chebyshev-like for dense paths; the block path is 1-block-steps so
        // this equals 1 per step on cardinal moves and rounds diagonals to 1.
        return Math.max(dx, dz);
    }
}
