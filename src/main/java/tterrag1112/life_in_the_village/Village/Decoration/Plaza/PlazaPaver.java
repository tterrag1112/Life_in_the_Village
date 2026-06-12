package tterrag1112.life_in_the_village.Village.Decoration.Plaza;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.PathMaterial;
import tterrag1112.life_in_the_village.Village.Planning.BuildingFootprint;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadFormality;

import java.util.HashSet;
import java.util.Set;

/**
 * Doc 04 §"Plaza-road integration" — paves a {@link PlazaRegion}
 * polygon with the village's road palette, producing a paved area
 * visually continuous with surrounding roads.
 *
 * <p>Insertion point: {@link tterrag1112.life_in_the_village.Village
 * .Decoration.VillageDecorator#decorateVillage} — runs <em>after</em>
 * the village roads are painted so any road centerline that crosses
 * the plaza interior is overwritten by the plaza palette (same source
 * via {@link PathMaterial#forBiomeAndTier}, so the overwrite is
 * visually benign — see prompt 17 audit summary).</p>
 *
 * <h3>Why a separate paver vs. modifying {@code OrganicRoadPlacer}</h3>
 * The prompt-15 audit categorized "extend the road realiser to accept
 * polygon input" as a substantial refactor (the realiser is built
 * around per-centerline iteration with three perpendicular zones —
 * polygon interior breaks the zone model). The "moderate change"
 * path the audit recommended is exactly this: a parallel pass that
 * uses the same {@code PathMaterial} source. No changes to
 * {@code OrganicRoadPlacer}.
 *
 * <h3>Edge transition</h3>
 * For positions within {@link #EDGE_OUTSET} blocks outside the
 * polygon, an edge sample is placed with deterministic coverage
 * noise (~50%) — mirrors the way road shoulders thin into terrain
 * rather than ending in a hard line.
 *
 * <h3>Formality (city-morphology step 2a follow-up)</h3>
 * The treatment keys on the region's plan-time {@link
 * PlazaRegion#formality()} (the density-profile zone at the plaza
 * centroid), reusing the EXACT pair the street realizer paints FORMAL
 * edges with ({@code VillageRoadRealizer}): {@link
 * PathMaterial#stoneBrick()} + crisp placement. FORMAL (CORE) plazas
 * pave full-coverage stone-brick core samples with no organic edge
 * outset (the polygon boundary is a clean line, like a crisp street
 * boundary); MIXED/ORGANIC plazas keep the caller's material and the
 * organic edge thinning — byte-identical to the pre-formality look,
 * mirroring how MIXED/ORGANIC street paint is unchanged.
 */
public final class PlazaPaver {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlazaPaver.class);

    /** Edge transition extends this many blocks outside the polygon. */
    private static final int EDGE_OUTSET = 2;

    /** Y-clamp range around floorY — blocks outside this band are
     *  filled / carved during paving so the plaza reads as locally
     *  flat. */
    private static final int Y_CLAMP_RANGE = 2;

    private PlazaPaver() {}

    /**
     * Paves {@code region}'s polygon interior and edge transition
     * onto {@code level}.
     *
     * @return the set of paved block positions (one Y per XZ
     *         column, the surface block); empty on failure
     */
    public static Set<BlockPos> pave(ServerLevel level,
                                     PlazaRegion region,
                                     PathMaterial material,
                                     BuildingFootprint footprint) {
        if (level == null || region == null || material == null) return Set.of();

        // FORMAL = the street realizer's crisp pair: stone-brick base,
        // core-only sampling, no organic boundary dropout. Anything else
        // keeps the caller's (tier/biome) material and the organic edge.
        boolean crisp = region.formality() == RoadFormality.FORMAL;
        PathMaterial mat = crisp ? PathMaterial.stoneBrick() : material;

        RandomSource random = level.getRandom();
        Set<BlockPos> paved = new HashSet<>();
        Polygon poly = region.footprint();
        Polygon.AABB bbox = Polygon.boundingBox(poly);
        int floorY = region.floorY();

        // Iterate the polygon's bounding box. For each XZ column,
        // determine inside / edge / outside and apply the
        // appropriate treatment.
        for (int x = bbox.minX() - EDGE_OUTSET; x <= bbox.maxX() + EDGE_OUTSET; x++) {
            for (int z = bbox.minZ() - EDGE_OUTSET; z <= bbox.maxZ() + EDGE_OUTSET; z++) {
                if (footprint != null && footprint.isBuildingAt(x, z)) continue;

                boolean inside = Polygon.contains(poly, x, z);
                double distToEdge = Polygon.distanceToEdge(poly,
                        new BlockPos(x, floorY, z));

                BlockPos pavePos;
                if (inside) {
                    // Interior: clamp Y to floor band; place core block.
                    pavePos = clampAndCarveColumn(level, x, z, floorY);
                    if (pavePos == null) continue;
                    BlockState state = mat.sampleCore(random);
                    placeAndClearAbove(level, pavePos, state);
                    paved.add(pavePos);
                } else if (!crisp && distToEdge <= EDGE_OUTSET) {
                    // Edge transition: deterministic coverage noise.
                    if (!shouldPlaceEdge(x, z, distToEdge)) continue;
                    int surfY = level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    pavePos = new BlockPos(x, surfY - 1, z);
                    BlockState existing = level.getBlockState(pavePos);
                    if (existing.liquid()) continue;
                    BlockState state = mat.sampleEdge(random);
                    placeAndClearAbove(level, pavePos, state);
                    paved.add(pavePos);
                }
            }
        }

        LOGGER.info("PlazaPaver: paved {} blocks for {} plaza ({}) at {}",
                paved.size(), region.purpose(), region.formality(),
                region.centroid().toShortString());
        return paved;
    }

    /**
     * Clamps the column's Y to {@code floorY ± Y_CLAMP_RANGE} so the
     * plaza reads as locally flat. Returns the position to place the
     * paving block at, or {@code null} if the column is on water /
     * non-replaceable terrain.
     *
     * <p>{@code floorY} is the {@code MOTION_BLOCKING_NO_LEAVES}
     * heightmap value at the plaza centre — i.e. the Y of the first
     * AIR block above the surface. The pave block goes one below
     * ({@code paveY = floorY - 1}) so it replaces the existing
     * surface block and players walk on top at {@code floorY},
     * matching the road realiser's {@code surfY - 1} convention.
     * Original implementation paved at {@code floorY} which left the
     * plaza one block higher than surrounding terrain.</p>
     */
    private static BlockPos clampAndCarveColumn(ServerLevel level, int x, int z,
                                                int floorY) {
        int paveY = floorY - 1;
        int surfY = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockState surfaceBelow = level.getBlockState(new BlockPos(x, surfY - 1, z));
        if (surfaceBelow.liquid()) {
            // Don't pave water columns — the plaza generator already
            // receded from water during accommodation, but a vertex-
            // pulled polygon can still cover a thin water inlet.
            return null;
        }

        // If terrain is below paveY (column low), fill dirt up to
        // paveY - 1 so the column has substrate under the pave.
        if (surfY < paveY) {
            for (int y = surfY; y < paveY; y++) {
                BlockPos fill = new BlockPos(x, y, z);
                BlockState s = level.getBlockState(fill);
                if (s.isAir() || s.is(BlockTags.REPLACEABLE)) {
                    level.setBlock(fill, Blocks.DIRT.defaultBlockState(), 3);
                }
            }
        } else if (surfY > paveY + 1 + Y_CLAMP_RANGE) {
            // If terrain is above the plaza floor band, carve down
            // until paveY (exclusive — leave the surface block at
            // paveY for the pave to replace).
            for (int y = surfY - 1; y > paveY; y--) {
                BlockPos carve = new BlockPos(x, y, z);
                BlockState cs = level.getBlockState(carve);
                if (cs.is(Blocks.GRASS_BLOCK) || cs.is(Blocks.DIRT)
                        || cs.is(Blocks.COARSE_DIRT)
                        || cs.is(BlockTags.REPLACEABLE)) {
                    level.setBlock(carve, Blocks.AIR.defaultBlockState(), 3);
                } else break;
            }
        }
        return new BlockPos(x, paveY, z);
    }

    private static void placeAndClearAbove(ServerLevel level, BlockPos pavePos,
                                           BlockState state) {
        level.setBlock(pavePos, state, 3);
        BlockPos above = pavePos.above();
        BlockState aboveState = level.getBlockState(above);
        if (!aboveState.isAir()
                && (aboveState.is(BlockTags.REPLACEABLE)
                || aboveState.is(BlockTags.FLOWERS)
                || aboveState.is(BlockTags.SMALL_FLOWERS)
                || aboveState.is(BlockTags.LEAVES))) {
            level.setBlock(above, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    /**
     * Position-seeded coverage noise for the edge ring. Closer to
     * the polygon (distToEdge ~0) gets fuller coverage; further out
     * thins. Deterministic by world coordinate so re-runs match.
     */
    private static boolean shouldPlaceEdge(int x, int z, double distToEdge) {
        // Coverage probability shrinks with distance.
        double coverage = Math.max(0, 1.0 - (distToEdge / (EDGE_OUTSET + 1.0)));
        // Hash the coordinate for a [0, 1) noise sample.
        long h = (((long) x) * 0x9E3779B97F4A7C15L)
                ^ (((long) z) * 0xBF58476D1CE4E5B9L);
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        double noise = ((h >>> 11) & 0x1FFFFFFFFFFFFFL) / (double) (1L << 53);
        return noise < coverage;
    }
}
