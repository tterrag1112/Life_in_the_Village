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
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive.DriftNoise;
import tterrag1112.life_in_the_village.Cultures.Culture;
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
        BlockState material = resolveMaterial(culture.planningBias().roadMaterial());
        long worldSeed = level.getSeed();
        int total = 0;
        // Spine path: per-primitive painting (handles drift / arcs).
        int spineWidth = roads.skeleton().spineSegments().isEmpty()
                ? 3
                : roads.skeleton().spineSegments().get(0).width();
        for (RoadPrimitive prim : roads.skeleton().spinePath().segments()) {
            total += paintPrimitive(level, prim, spineWidth, material, worldSeed);
        }
        // Cross-streets: straight-strip paint, no drift (cross-streets
        // are short and cardinal — meandering reads as a layout error).
        for (CrossStreet cs : roads.skeleton().crossStreets()) {
            total += paintStraight(level, cs.start(), cs.end(), cs.width(),
                    material, /*driftAmplitude*/ 0, worldSeed);
        }
        return total;
    }

    private static int paintPrimitive(ServerLevel level, RoadPrimitive prim,
                                      int width, BlockState material, long worldSeed) {
        if (prim instanceof RoadPrimitive.StraightRoad sr) {
            return paintStraight(level, sr.from(), sr.to(), width, material,
                    sr.driftAmplitude(), worldSeed);
        }
        if (prim instanceof RoadPrimitive.CurvedRoad cr) {
            return paintCurvedBow(level, cr.from(), cr.to(), cr.curvature(),
                    cr.driftAmplitude(), width, material, worldSeed);
        }
        if (prim instanceof RoadPrimitive.Arc arc) {
            return paintArc(level, arc, width, material, worldSeed);
        }
        if (prim instanceof RoadPrimitive.Ring ring) {
            return paintRing(level, ring, width, material, worldSeed);
        }
        if (prim instanceof RoadPrimitive.Spur spur) {
            return paintSpur(level, spur, width, material, worldSeed);
        }
        if (prim instanceof RoadPrimitive.ArmApproach aa) {
            return paintStraight(level, aa.dockingAnchor(), aa.armEndpoint(),
                    width, material, /*drift*/ 2.0, worldSeed);
        }
        if (prim instanceof RoadPrimitive.SmoothedPath sp) {
            return paintSmoothedPath(level, sp, width, material, worldSeed);
        }
        if (prim instanceof RoadPrimitive.Bridge br) {
            return paintStraight(level, br.from(), br.to(), width, material,
                    /*drift*/ 1.5, worldSeed);
        }
        // Stairway is not produced by NetworkPlanner recipes today (the
        // Y-jumping centerline needs a dedicated painter that's out of
        // scope for prompt 3). Recipes that would want it use Spur or
        // StraightRoad instead.
        return 0;
    }

    /** Walk start→end at 1-block resolution. At each centerline
     *  sample, displace perpendicular to the chord by
     *  {@code DriftNoise.sample(t) * driftAmplitude * ampScale}, then
     *  paint a strip perpendicular to the LOCAL tangent (chord +
     *  drift derivative). Endpoints meet exactly because
     *  {@link DriftNoise#sample} returns 0 at {@code t=0} and {@code t=1}. */
    private static int paintStraight(ServerLevel level, BlockPos a, BlockPos b,
                                     int width, BlockState material,
                                     double driftAmplitude, long worldSeed) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-9) return 0;
        int steps = Math.max(1, (int) Math.round(len));
        double perpX = -dz / len;
        double perpZ = dx / len;
        int half = (width + 1) / 2;
        boolean drift = driftAmplitude > 0 && len >= 8;
        long localSeed = drift ? DriftNoise.localSeed(worldSeed, a, b) : 0L;
        double ampScale = drift ? Math.min(1.0, len / 64.0) : 0.0;
        int painted = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        double prevX = a.getX(), prevZ = a.getZ();
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double driftOff = drift
                    ? DriftNoise.sample(t, localSeed) * driftAmplitude * ampScale
                    : 0.0;
            double xd = a.getX() + dx * t + perpX * driftOff;
            double zd = a.getZ() + dz * t + perpZ * driftOff;
            int cx = (int) Math.round(xd);
            int cz = (int) Math.round(zd);
            // Local tangent for the strip perpendicular: chord for i=0,
            // (this − prev) afterwards. With drift this rotates the
            // strip slightly — the painted band tracks the centerline.
            double lpX, lpZ;
            if (i == 0 || !drift) { lpX = perpX; lpZ = perpZ; }
            else {
                double tx = xd - prevX, tz = zd - prevZ;
                double tlen = Math.sqrt(tx * tx + tz * tz);
                if (tlen < 1e-9) { lpX = perpX; lpZ = perpZ; }
                else { lpX = -tz / tlen; lpZ = tx / tlen; }
            }
            painted += paintStripAt(level, cx, cz, lpX, lpZ, half, material, cursor);
            prevX = xd;
            prevZ = zd;
        }
        return painted;
    }

    /** Walk a quadratic-bezier bow (CurvedRoad's geometry: midpoint
     *  pulled along chord-perpendicular by {@code curvature * chord}).
     *  Local tangent recomputed per sample; drift added in the local
     *  perpendicular direction. */
    private static int paintCurvedBow(ServerLevel level, BlockPos a, BlockPos b,
                                      double curvature, double driftAmplitude,
                                      int width, BlockState material, long worldSeed) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double chord = Math.sqrt(dx * dx + dz * dz);
        if (chord < 1) return 0;
        double pX = -dz / chord;
        double pZ = dx / chord;
        double bow = curvature * chord;
        int steps = Math.max(2, (int) Math.ceil(chord));
        int half = (width + 1) / 2;
        boolean drift = driftAmplitude > 0 && chord >= 8;
        long localSeed = drift ? DriftNoise.localSeed(worldSeed, a, b) : 0L;
        double ampScale = drift ? Math.min(1.0, chord / 64.0) : 0.0;
        int painted = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // Track previous sample for tangent computation.
        double prevX = a.getX(), prevZ = a.getZ();
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double s = 4 * t * (1 - t);  // 0 at ends, 1 at middle
            double driftOff = drift
                    ? DriftNoise.sample(t, localSeed) * driftAmplitude * ampScale
                    : 0.0;
            double xd = a.getX() + dx * t + pX * (bow * s + driftOff);
            double zd = a.getZ() + dz * t + pZ * (bow * s + driftOff);
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
     *  is perpendicular to the radius. Drift, if any, displaces
     *  radially. */
    private static int paintArc(ServerLevel level, RoadPrimitive.Arc arc,
                                int width, BlockState material, long worldSeed) {
        int samples = Math.max(8,
                (int) Math.ceil(Math.abs(arc.arcSpan()) * arc.radius()));
        int half = (width + 1) / 2;
        double driftAmplitude = arc.driftAmplitude();
        double arcLen = Math.abs(arc.arcSpan()) * arc.radius();
        boolean drift = driftAmplitude > 0 && arcLen >= 8;
        long localSeed = drift
                ? DriftNoise.localSeed(worldSeed, arc.centre(), arc.centre())
                ^ Double.doubleToLongBits(arc.startAngle())
                : 0L;
        double ampScale = drift ? Math.min(1.0, arcLen / 64.0) : 0.0;
        int painted = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            double angle = arc.startAngle() + arc.arcSpan() * t;
            // Tangent at this point on the circle: perpendicular to
            // radius, direction depends on arcSpan sign.
            double tx = -Math.sin(angle) * Math.signum(arc.arcSpan());
            double tz = Math.cos(angle) * Math.signum(arc.arcSpan());
            // Perpendicular to the tangent (= radial).
            double perpX = -tz;
            double perpZ = tx;
            double driftOff = drift
                    ? DriftNoise.sample(t, localSeed) * driftAmplitude * ampScale
                    : 0.0;
            int cx = arc.centre().getX()
                    + (int) Math.round(Math.cos(angle) * arc.radius() + perpX * driftOff);
            int cz = arc.centre().getZ()
                    + (int) Math.round(Math.sin(angle) * arc.radius() + perpZ * driftOff);
            painted += paintStripAt(level, cx, cz, perpX, perpZ, half,
                    material, cursor);
        }
        return painted;
    }

    /** Walk a full-circle Ring. One sample per block of circumference;
     *  paint a width strip perpendicular to the radial at each
     *  sample. Functionally equivalent to {@link #paintArc} with
     *  {@code arcSpan = 2π}. */
    private static int paintRing(ServerLevel level, RoadPrimitive.Ring ring,
                                 int width, BlockState material, long worldSeed) {
        int radius = ring.radius();
        int samples = Math.max(16, (int) Math.ceil(2 * Math.PI * radius));
        int half = (width + 1) / 2;
        double driftAmplitude = ring.driftAmplitude();
        double circ = 2 * Math.PI * radius;
        boolean drift = driftAmplitude > 0 && circ >= 8;
        long localSeed = drift
                ? DriftNoise.localSeed(worldSeed, ring.centre(), ring.centre())
                ^ ((long) radius * 2654435761L)
                : 0L;
        double ampScale = drift ? Math.min(1.0, circ / 64.0) : 0.0;
        int painted = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            double angle = t * 2 * Math.PI;
            double tx = -Math.sin(angle);
            double tz = Math.cos(angle);
            double perpX = -tz;
            double perpZ = tx;
            double driftOff = drift
                    ? DriftNoise.sample(t, localSeed) * driftAmplitude * ampScale
                    : 0.0;
            int cx = ring.centre().getX()
                    + (int) Math.round(Math.cos(angle) * radius + perpX * driftOff);
            int cz = ring.centre().getZ()
                    + (int) Math.round(Math.sin(angle) * radius + perpZ * driftOff);
            painted += paintStripAt(level, cx, cz, perpX, perpZ, half, material, cursor);
        }
        return painted;
    }

    /** Walk a Spur from its snapped branch point along
     *  {@code directionRad} for {@code length} blocks. Treated as a
     *  short StraightRoad with the same drift conventions. */
    private static int paintSpur(ServerLevel level, RoadPrimitive.Spur spur,
                                 int width, BlockState material, long worldSeed) {
        BlockPos hint = spur.branchPointHint();
        BlockPos start = nearestOnCenterline(spur.parentCenterline(), hint);
        int endX = start.getX()
                + (int) Math.round(Math.cos(spur.directionRad()) * spur.length());
        int endZ = start.getZ()
                + (int) Math.round(Math.sin(spur.directionRad()) * spur.length());
        BlockPos end = new BlockPos(endX, start.getY(), endZ);
        return paintStraight(level, start, end, width, material,
                spur.driftAmplitude(), worldSeed);
    }

    /** Paint a SmoothedPath by walking consecutive waypoint pairs as
     *  short straights. Drift on each segment is the path's amplitude
     *  scaled down by chord length so very short links don't wobble
     *  noticeably. */
    private static int paintSmoothedPath(ServerLevel level,
                                         RoadPrimitive.SmoothedPath sp,
                                         int width, BlockState material,
                                         long worldSeed) {
        java.util.List<BlockPos> wp = sp.waypoints();
        if (wp.size() < 2) return 0;
        int painted = 0;
        for (int i = 0; i + 1 < wp.size(); i++) {
            painted += paintStraight(level, wp.get(i), wp.get(i + 1), width,
                    material, sp.driftAmplitude(), worldSeed);
        }
        return painted;
    }

    /** Nearest point on a centerline polyline to {@code hint}.
     *  Local helper mirroring {@code RoadPrimitive.Spur.nearestOnCenterline}
     *  (which is private to the primitive). */
    private static BlockPos nearestOnCenterline(java.util.List<BlockPos> line,
                                                BlockPos hint) {
        if (line.isEmpty()) return hint;
        BlockPos best = line.get(0);
        double bestD = best.distSqr(hint);
        for (BlockPos p : line) {
            double d = p.distSqr(hint);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
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
