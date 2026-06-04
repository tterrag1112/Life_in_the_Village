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

import java.util.List;
import java.util.Random;

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
    /** Half-width of the border gap left open at the entry-path crossing. */
    private static final int GATE_HALF = 2;

    /** Paints the block-perimeter border at a seed-varied style. */
    public static void paint(ServerLevel level, Polygon.AABB block,
                             List<Polygon.AABB> houseFootprints, BlockPos entryGate,
                             long seed, String culture) {
        if (block == null) return;
        BorderStyleId style = POOL[Math.floorMod((int) (seed ^ (seed >>> 32)), POOL.length)];
        BorderGenerator gen = BorderGeneratorRegistry.get(culture, style)
                .orElseGet(HedgeBorder::new);
        Random rng = new Random(seed);

        int minX = block.minX(), maxX = block.maxX();
        int minZ = block.minZ(), maxZ = block.maxZ();
        // North (z=minZ) + South (z=maxZ) edges run the full X span.
        for (int x = minX; x <= maxX; x++) {
            paintCell(level, gen, x, minZ, Direction.NORTH, houseFootprints, entryGate, rng);
            paintCell(level, gen, x, maxZ, Direction.SOUTH, houseFootprints, entryGate, rng);
        }
        // West (x=minX) + East (x=maxX) edges run the interior Z span (corners
        // already painted above).
        for (int z = minZ + 1; z < maxZ; z++) {
            paintCell(level, gen, minX, z, Direction.WEST, houseFootprints, entryGate, rng);
            paintCell(level, gen, maxX, z, Direction.EAST, houseFootprints, entryGate, rng);
        }
    }

    private static void paintCell(ServerLevel level, BorderGenerator gen, int x, int z,
                                  Direction outward, List<Polygon.AABB> footprints,
                                  BlockPos entryGate, Random rng) {
        // Leave a gap at the entry-path crossing.
        if (entryGate != null
                && Math.abs(x - entryGate.getX()) <= GATE_HALF
                && Math.abs(z - entryGate.getZ()) <= GATE_HALF) {
            return;
        }
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
}
