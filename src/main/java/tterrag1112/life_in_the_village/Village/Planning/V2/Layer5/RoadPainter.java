package tterrag1112.life_in_the_village.Village.Planning.V2.Layer5;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.V2.Culture.Culture;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.CrossStreet;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadNetwork;

/**
 * V2 Layer 5 — paint road blocks along the skeleton.
 *
 * <p>Two passes:
 * <ul>
 *   <li>Spine path: iterate {@code skeleton.spinePath().segments()},
 *       dispatch by primitive type. {@code StraightRoad} →
 *       chord-walk; {@code CurvedRoad} → bezier-bow walk;
 *       {@code Arc} → polar walk. At each centerline sample, paint
 *       a {@code SpineSegment.width()}-wide perpendicular strip
 *       perpendicular to the LOCAL tangent.</li>
 *   <li>Cross-streets: chord-walk; cross-streets are always
 *       cardinal-aligned per Layer 4.</li>
 * </ul>
 *
 * <p>Material from {@code culture.roadMaterial()} (fallback
 * {@link Blocks#DIRT_PATH}). Air clearance above the new pave to
 * remove leftover vegetation.
 */
public final class RoadPainter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RoadPainter.class);

    /** Air clearance above the road surface (so trees / leftover
     *  vegetation that sneaked in don't sit IN the road). */
    private static final int ROAD_HEAD_CLEARANCE = 3;

    private RoadPainter() {}

    public static int paintAll(ServerLevel level, RoadNetwork roads, Culture culture) {
        BlockState material = resolveMaterial(culture.roadMaterial());
        int total = 0;
        // Spine path: per-primitive painting (handles drift / arcs).
        int spineWidth = roads.skeleton().spineSegments().isEmpty()
                ? 3
                : roads.skeleton().spineSegments().get(0).width();
        for (RoadPrimitive prim : roads.skeleton().spinePath().segments()) {
            total += paintPrimitive(level, prim, spineWidth, material);
        }
        // Cross-streets: straight-strip paint.
        for (CrossStreet cs : roads.skeleton().crossStreets()) {
            total += paintStraight(level, cs.start(), cs.end(), cs.width(), material);
        }
        return total;
    }

    private static int paintPrimitive(ServerLevel level, RoadPrimitive prim,
                                      int width, BlockState material) {
        if (prim instanceof RoadPrimitive.StraightRoad sr) {
            return paintStraight(level, sr.from(), sr.to(), width, material);
        }
        if (prim instanceof RoadPrimitive.CurvedRoad cr) {
            return paintCurvedBow(level, cr.from(), cr.to(), cr.curvature(),
                    width, material);
        }
        if (prim instanceof RoadPrimitive.Arc arc) {
            return paintArc(level, arc, width, material);
        }
        // Other primitive types not produced by SpinePathPlanner in
        // V1; fall through with chord-walk approximation.
        return 0;
    }

    /** Walk start→end at 1-block resolution; at each centerline
     *  sample paint a perpendicular strip perpendicular to the
     *  chord direction. */
    private static int paintStraight(ServerLevel level, BlockPos a, BlockPos b,
                                     int width, BlockState material) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-9) return 0;
        int steps = Math.max(1, (int) Math.round(len));
        double perpX = -dz / len;
        double perpZ = dx / len;
        int half = (width + 1) / 2;
        int painted = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int cx = (int) Math.round(a.getX() + dx * t);
            int cz = (int) Math.round(a.getZ() + dz * t);
            painted += paintStripAt(level, cx, cz, perpX, perpZ, half, material, cursor);
        }
        return painted;
    }

    /** Walk a quadratic-bezier bow (CurvedRoad's geometry: midpoint
     *  pulled along chord-perpendicular by {@code curvature * chord}).
     *  Local tangent recomputed per sample. */
    private static int paintCurvedBow(ServerLevel level, BlockPos a, BlockPos b,
                                      double curvature, int width, BlockState material) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double chord = Math.sqrt(dx * dx + dz * dz);
        if (chord < 1) return 0;
        double pX = -dz / chord;
        double pZ = dx / chord;
        double bow = curvature * chord;
        int steps = Math.max(2, (int) Math.ceil(chord));
        int half = (width + 1) / 2;
        int painted = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // Track previous sample for tangent computation.
        double prevX = a.getX(), prevZ = a.getZ();
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double s = 4 * t * (1 - t);  // 0 at ends, 1 at middle
            double xd = a.getX() + dx * t + pX * bow * s;
            double zd = a.getZ() + dz * t + pZ * bow * s;
            // Local tangent = (this - prev) for i > 0; chord direction
            // for i == 0.
            double tx, tz;
            if (i == 0) { tx = dx; tz = dz; }
            else { tx = xd - prevX; tz = zd - prevZ; }
            double tlen = Math.sqrt(tx * tx + tz * tz);
            if (tlen < 1e-9) { tx = dx; tz = dz; tlen = chord; }
            double localPerpX = -tz / tlen;
            double localPerpZ = tx / tlen;
            int cx = (int) Math.round(xd);
            int cz = (int) Math.round(zd);
            painted += paintStripAt(level, cx, cz, localPerpX, localPerpZ, half,
                    material, cursor);
            prevX = xd;
            prevZ = zd;
        }
        return painted;
    }

    /** Walk an arc by polar sampling. Local tangent at each sample
     *  is perpendicular to the radius. */
    private static int paintArc(ServerLevel level, RoadPrimitive.Arc arc,
                                int width, BlockState material) {
        int samples = Math.max(8,
                (int) Math.ceil(Math.abs(arc.arcSpan()) * arc.radius()));
        int half = (width + 1) / 2;
        int painted = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            double angle = arc.startAngle() + arc.arcSpan() * t;
            int cx = arc.centre().getX()
                    + (int) Math.round(Math.cos(angle) * arc.radius());
            int cz = arc.centre().getZ()
                    + (int) Math.round(Math.sin(angle) * arc.radius());
            // Tangent at this point on the circle: perpendicular to
            // radius, direction depends on arcSpan sign.
            double tx = -Math.sin(angle) * Math.signum(arc.arcSpan());
            double tz = Math.cos(angle) * Math.signum(arc.arcSpan());
            // Perpendicular to the tangent (= radial).
            double perpX = -tz;
            double perpZ = tx;
            painted += paintStripAt(level, cx, cz, perpX, perpZ, half,
                    material, cursor);
        }
        return painted;
    }

    /** Paint a perpendicular strip of total width 2*half+1 at the
     *  given (cx, cz), aligned along (perpX, perpZ). Returns blocks
     *  painted. */
    private static int paintStripAt(ServerLevel level, int cx, int cz,
                                    double perpX, double perpZ, int half,
                                    BlockState material, BlockPos.MutableBlockPos cursor) {
        int painted = 0;
        for (int p = -half; p <= half; p++) {
            int x = cx + (int) Math.round(perpX * p);
            int z = cz + (int) Math.round(perpZ * p);
            int surfY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            int paveY = surfY - 1;
            cursor.set(x, paveY, z);
            level.setBlock(cursor, material, 2);
            painted++;
            for (int dy = 1; dy <= ROAD_HEAD_CLEARANCE; dy++) {
                cursor.set(x, paveY + dy, z);
                BlockState bs = level.getBlockState(cursor);
                if (!bs.isAir()) {
                    level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        return painted;
    }

    /** Parse {@code id} (e.g. {@code "minecraft:dirt_path"}) into a
     *  block state. Logs once on failure, falls back to dirt path. */
    private static BlockState resolveMaterial(String id) {
        if (id == null || id.isEmpty()) return Blocks.DIRT_PATH.defaultBlockState();
        try {
            Identifier rid = Identifier.parse(id);
            return BuiltInRegistries.BLOCK.getOptional(rid)
                    .map(Block::defaultBlockState)
                    .orElseGet(() -> {
                        LOGGER.warn("RoadPainter: unknown road material '{}', falling back to dirt path", id);
                        return Blocks.DIRT_PATH.defaultBlockState();
                    });
        } catch (Exception e) {
            LOGGER.warn("RoadPainter: malformed road material id '{}': {}", id, e.getMessage());
            return Blocks.DIRT_PATH.defaultBlockState();
        }
    }
}
