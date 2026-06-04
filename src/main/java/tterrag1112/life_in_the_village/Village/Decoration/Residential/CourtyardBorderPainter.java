package tterrag1112.life_in_the_village.Village.Decoration.Residential;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Buildings.Complex.BorderStyleId;
import tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders.AbstractBorderGenerator;
import tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders.BorderGenerator;
import tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders.BorderGeneratorRegistry;
import tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders.HedgeBorder;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Layout Rework — paints a fence/hedge enclosure around a residential COURTYARD
 * block's perimeter, REUSING the farm border generators ({@link HedgeBorder} /
 * stone-wall / post-and-rail / drystone via {@link BorderGeneratorRegistry}) and
 * the shared ground-Y snapshot ({@link AbstractBorderGenerator#resolveGroundY}).
 * Not a new border painter — it walks a rectangle perimeter and drives the same
 * per-column generators the farm complex uses, skipping the entry gate + any
 * cell a house footprint sits on.
 */
public final class CourtyardBorderPainter {

    private CourtyardBorderPainter() {}

    private static final BorderStyleId[] POOL = BorderStyleId.values();
    /** Radius (blocks) around a path cell the border leaves open, so the gap is
     *  wide enough for the footpath that crosses there. */
    private static final int PATH_GAP_RADIUS = 1;

    /**
     * Paints the block-perimeter border at a seed-varied style. {@code pathLines}
     * are the courtyard's footpath centerlines — the border gaps wherever one
     * crosses the perimeter (the road-aware skip-set, ported from the farm
     * complex), so there is no hardcoded gate.
     */
    public static void paint(ServerLevel level, Polygon.AABB block,
                             List<Polygon.AABB> houseFootprints,
                             List<List<BlockPos>> pathLines,
                             long seed, String culture) {
        if (block == null) return;
        BorderStyleId style = POOL[Math.floorMod((int) (seed ^ (seed >>> 32)), POOL.length)];
        BorderGenerator gen = BorderGeneratorRegistry.get(culture, style)
                .orElseGet(HedgeBorder::new);
        Random rng = new Random(seed);
        Set<Long> pathCells = rasterizePathCells(pathLines);

        int minX = block.minX(), maxX = block.maxX();
        int minZ = block.minZ(), maxZ = block.maxZ();
        // North (z=minZ) + South (z=maxZ) edges run the full X span.
        for (int x = minX; x <= maxX; x++) {
            paintCell(level, gen, x, minZ, Direction.NORTH, houseFootprints, pathCells, rng);
            paintCell(level, gen, x, maxZ, Direction.SOUTH, houseFootprints, pathCells, rng);
        }
        // West (x=minX) + East (x=maxX) edges run the interior Z span (corners
        // already painted above).
        for (int z = minZ + 1; z < maxZ; z++) {
            paintCell(level, gen, minX, z, Direction.WEST, houseFootprints, pathCells, rng);
            paintCell(level, gen, maxX, z, Direction.EAST, houseFootprints, pathCells, rng);
        }
    }

    private static void paintCell(ServerLevel level, BorderGenerator gen, int x, int z,
                                  Direction outward, List<Polygon.AABB> footprints,
                                  Set<Long> pathCells, Random rng) {
        // Gap the border wherever a path crosses it.
        if (pathCells.contains(packXZ(x, z))) return;
        // Skip any perimeter cell a house footprint already occupies.
        if (footprints != null) {
            for (Polygon.AABB fp : footprints) {
                if (x >= fp.minX() && x <= fp.maxX() && z >= fp.minZ() && z <= fp.maxZ()) {
                    return;
                }
            }
        }
        int groundY = AbstractBorderGenerator.resolveGroundY(level, x, z);
        gen.paintColumnAt(level, x, z, groundY, outward, rng);
    }

    /** Rasterizes the path centerlines into a cell skip-set (Bresenham-stepped
     *  along each segment, dilated by {@link #PATH_GAP_RADIUS}) — same packed-XZ
     *  convention the farm border path-skip uses. */
    private static Set<Long> rasterizePathCells(List<List<BlockPos>> pathLines) {
        Set<Long> cells = new HashSet<>();
        if (pathLines == null) return cells;
        for (List<BlockPos> line : pathLines) {
            for (int i = 1; i < line.size(); i++) {
                BlockPos a = line.get(i - 1), b = line.get(i);
                int steps = Math.max(1, (int) Math.ceil(Math.sqrt(a.distSqr(b))));
                for (int s = 0; s <= steps; s++) {
                    int x = a.getX() + (b.getX() - a.getX()) * s / steps;
                    int z = a.getZ() + (b.getZ() - a.getZ()) * s / steps;
                    for (int dx = -PATH_GAP_RADIUS; dx <= PATH_GAP_RADIUS; dx++) {
                        for (int dz = -PATH_GAP_RADIUS; dz <= PATH_GAP_RADIUS; dz++) {
                            cells.add(packXZ(x + dx, z + dz));
                        }
                    }
                }
            }
        }
        return cells;
    }

    /** Packed XZ key (matches the farm complex's border path-skip layout). */
    private static long packXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
