package tterrag1112.life_in_the_village.Village.Planning.Primitives;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;

import java.util.ArrayList;
import java.util.List;

/**
 * A composable road centerline primitive.
 *
 * <h3>What it is</h3>
 * A primitive describes HOW a road curves through space — its centerline —
 * but not how it's painted. The organic block painting is done later by
 * {@code OrganicRoadPlacer}, which takes the computed centerline and
 * handles perpendicular block expansion and edge noise. Primitives are
 * the geometric intent; the placer is the rendering.
 *
 * <h3>Drift</h3>
 * Every road wanders slightly, because Minecraft's square grid makes
 * perfectly-straight roads look artificial. Drift is a 1D value-noise
 * offset applied perpendicular to the local heading. Drift amplitude
 * scales with {@code min(1.0, length / 64.0)} so short connector roads
 * stay visibly straight while long trunk roads have room to wander.
 *
 * <h3>Determinism</h3>
 * Noise is seeded from the primitive's endpoints plus the world seed.
 * Planning the same village twice with the same seed produces identical
 * centerlines — important for validation and for planned-but-unrealised
 * villages that will be rendered later.
 *
 * <h3>Spur parent reference</h3>
 * {@link Spur} holds its parent centerline as a direct field rather than
 * a reference to the parent primitive. The recipe that constructs the
 * spur already has the parent's centerline in hand, so there's no reason
 * to thread a "computed so far" map through the API.
 */
public sealed interface RoadPrimitive
        permits RoadPrimitive.StraightRoad,
        RoadPrimitive.CurvedRoad,
        RoadPrimitive.Ring,
        RoadPrimitive.Arc,
        RoadPrimitive.Spur {

    /**
     * Computes the centerline of this road, snapped to the surface Y at
     * every step. Pure — no blocks are placed.
     *
     * @param level world reference for surface Y lookup
     * @param worldSeed stable seed used to derive deterministic noise
     * @return ordered list of centerline positions from start to end
     */
    List<BlockPos> computeCenterline(ServerLevel level, long worldSeed);

    /** Tier this road should be painted at. */
    RoadShape.RoadTier tier();

    // =========================================================================
    // StraightRoad
    // =========================================================================

    /**
     * A nearly-straight road from {@code from} to {@code to} with natural
     * perpendicular drift. Use for main roads leading out of the village,
     * direct links between the town square and an inner focal point,
     * and any other connection where the intent is "go directly there
     * but don't look like a ruler drew it."
     *
     * @param driftAmplitude maximum perpendicular offset in blocks.
     *                       3 = subtle wander, 6 = visible wander,
     *                       10+ = hairpin territory (don't).
     */
    record StraightRoad(
            BlockPos from,
            BlockPos to,
            double driftAmplitude,
            RoadShape.RoadTier tier
    ) implements RoadPrimitive {

        @Override
        public List<BlockPos> computeCenterline(ServerLevel level, long worldSeed) {
            long localSeed = DriftNoise.localSeed(worldSeed, from, to);
            return driftedLine(level, from, to, driftAmplitude, localSeed);
        }
    }

    // =========================================================================
    // CurvedRoad
    // =========================================================================

    /**
     * A bowed arc between {@code from} and {@code to}. The midpoint of the
     * arc is offset perpendicular to the chord by {@code curvature *
     * chordLength}. Use for ring segments and for roads that need to
     * sweep around an obstacle rather than going straight through it.
     *
     * @param curvature bow amount as a fraction of chord length.
     *                  0 = straight, 0.15 = gentle curve, 0.3 = strong arc,
     *                  0.5+ = nearly semicircular.
     * @param driftAmplitude additional wander on top of the curve
     */
    record CurvedRoad(
            BlockPos from,
            BlockPos to,
            double curvature,
            double driftAmplitude,
            RoadShape.RoadTier tier
    ) implements RoadPrimitive {

        @Override
        public List<BlockPos> computeCenterline(ServerLevel level, long worldSeed) {
            long localSeed = DriftNoise.localSeed(worldSeed, from, to)
                    ^ Double.doubleToLongBits(curvature);

            int dx = to.getX() - from.getX();
            int dz = to.getZ() - from.getZ();
            double chordLen = Math.sqrt(dx * dx + dz * dz);
            if (chordLen < 1) {
                List<BlockPos> out = new ArrayList<>();
                out.add(surfaceAt(level, from.getX(), from.getZ()));
                return out;
            }

            // Unit perpendicular (rotate heading 90° CCW)
            double perpX = -dz / chordLen;
            double perpZ = dx / chordLen;
            double bow = curvature * chordLen;

            int steps = Math.max(2, (int) Math.ceil(chordLen));
            List<BlockPos> line = new ArrayList<>(steps + 1);

            for (int i = 0; i <= steps; i++) {
                double t = i / (double) steps;
                // Quadratic Bezier with midpoint pulled along perpendicular
                double s = 4 * t * (1 - t);  // 0 at ends, 1 at middle
                double baseX = from.getX() + dx * t;
                double baseZ = from.getZ() + dz * t;
                double arcX = baseX + perpX * bow * s;
                double arcZ = baseZ + perpZ * bow * s;

                double driftOffset = DriftNoise.sample(t, localSeed)
                        * driftAmplitude
                        * Math.min(1.0, chordLen / 64.0);

                int x = (int) Math.round(arcX + perpX * driftOffset);
                int z = (int) Math.round(arcZ + perpZ * driftOffset);
                line.add(surfaceAt(level, x, z));
            }
            return dedupe(line);
        }
    }

    // =========================================================================
    // Ring
    // =========================================================================

    /**
     * A closed ring around {@code centre} at {@code radius}, with per-angle
     * drift so the ring wobbles organically rather than tracing a perfect
     * circle. Use for ring roads around plazas or around the entire village.
     *
     * <p>The centerline closes on itself — last point equals first point —
     * so the road placer paints a continuous loop.
     */
    record Ring(
            BlockPos centre,
            int radius,
            double driftAmplitude,
            RoadShape.RoadTier tier
    ) implements RoadPrimitive {

        @Override
        public List<BlockPos> computeCenterline(ServerLevel level, long worldSeed) {
            long localSeed = DriftNoise.localSeed(worldSeed, centre, centre)
                    ^ ((long) radius * 2654435761L);

            // One sample per block of circumference
            int steps = Math.max(16, (int) Math.ceil(2 * Math.PI * radius));
            List<BlockPos> line = new ArrayList<>(steps + 1);

            double ampScale = Math.min(1.0, (2 * Math.PI * radius) / 64.0);

            for (int i = 0; i <= steps; i++) {
                double t = i / (double) steps;
                double angle = t * 2 * Math.PI;
                double drift = DriftNoise.sample(t, localSeed) * driftAmplitude * ampScale;
                double r = radius + drift;
                int x = centre.getX() + (int) Math.round(Math.cos(angle) * r);
                int z = centre.getZ() + (int) Math.round(Math.sin(angle) * r);
                line.add(surfaceAt(level, x, z));
            }
            return dedupe(line);
        }
    }

    // =========================================================================
    // Arc
    // =========================================================================

    /**
     * A partial-circle road segment — a slice of a Ring spanning
     * {@code arcSpan} radians starting from {@code startAngle}. Used by
     * radial layouts to create concentric half-rings that visually
     * connect adjacent spurs without forming closed loops.
     *
     * <p>Where {@link Ring} closes on itself, an Arc has two distinct
     * endpoints — its start and end coordinates can serve as branch
     * targets for spurs that want to T-into the arc midway.
     *
     * @param centre  centre of the parent circle
     * @param radius  radius of the arc
     * @param startAngle starting angle in radians (math convention)
     * @param arcSpan angular extent in radians (e.g. Math.PI for a half-ring)
     */
    record Arc(
            BlockPos centre,
            int radius,
            double startAngle,
            double arcSpan,
            double driftAmplitude,
            RoadShape.RoadTier tier
    ) implements RoadPrimitive {

        @Override
        public List<BlockPos> computeCenterline(ServerLevel level, long worldSeed) {
            long localSeed = DriftNoise.localSeed(worldSeed, centre, centre)
                    ^ ((long) radius * 2654435761L)
                    ^ Double.doubleToLongBits(startAngle)
                    ^ Double.doubleToLongBits(arcSpan);

            // One sample per block of arc length
            double arcLength = Math.abs(arcSpan) * radius;
            int steps = Math.max(8, (int) Math.ceil(arcLength));
            List<BlockPos> line = new ArrayList<>(steps + 1);

            double ampScale = Math.min(1.0, arcLength / 64.0);

            for (int i = 0; i <= steps; i++) {
                double t = i / (double) steps;
                double angle = startAngle + arcSpan * t;
                double drift = DriftNoise.sample(t, localSeed) * driftAmplitude * ampScale;
                double r = radius + drift;
                int x = centre.getX() + (int) Math.round(Math.cos(angle) * r);
                int z = centre.getZ() + (int) Math.round(Math.sin(angle) * r);
                line.add(surfaceAt(level, x, z));
            }
            return dedupe(line);
        }
    }

    // =========================================================================
    // Spur
    // =========================================================================

    /**
     * A road that branches off an existing road at a specific point.
     *
     * <h3>Branch snapping</h3>
     * {@code branchPointHint} is the recipe's ideal location for the
     * branch — e.g. "halfway along the main road" or "east end of the
     * ring." The spur snaps this hint to the nearest actual block on the
     * parent centerline so the two roads meet exactly, not one block off.
     *
     * <h3>Parent centerline as a field</h3>
     * The parent centerline is stored directly on the spur, not looked
     * up via a reference. The recipe already has it in hand when it
     * builds the spur, and this keeps {@code computeCenterline}'s signature
     * simple with no ordering dependency between primitives.
     *
     * @param parentCenterline the centerline the spur branches off
     * @param branchPointHint recipe's ideal branch position (will snap)
     * @param directionRad outward direction from the parent in radians
     * @param length spur length in blocks from branch point to endpoint
     */
    record Spur(
            List<BlockPos> parentCenterline,
            BlockPos branchPointHint,
            double directionRad,
            int length,
            double driftAmplitude,
            RoadShape.RoadTier tier
    ) implements RoadPrimitive {

        @Override
        public List<BlockPos> computeCenterline(ServerLevel level, long worldSeed) {
            BlockPos snappedStart = nearestOnCenterline(parentCenterline, branchPointHint);
            int endX = snappedStart.getX() + (int) Math.round(Math.cos(directionRad) * length);
            int endZ = snappedStart.getZ() + (int) Math.round(Math.sin(directionRad) * length);
            BlockPos end = surfaceAt(level, endX, endZ);

            long localSeed = DriftNoise.localSeed(worldSeed, snappedStart, end);
            return driftedLine(level, snappedStart, end, driftAmplitude, localSeed);
        }

        private static BlockPos nearestOnCenterline(List<BlockPos> centerline, BlockPos hint) {
            BlockPos best = centerline.get(0);
            double bestDistSq = best.distSqr(hint);
            for (BlockPos p : centerline) {
                double d = p.distSqr(hint);
                if (d < bestDistSq) { bestDistSq = d; best = p; }
            }
            return best;
        }
    }

    // =========================================================================
    // Shared helpers — line walking, surface snap, dedupe
    // =========================================================================

    /**
     * Walks a straight line from {@code from} to {@code to} in 1-block
     * steps along the dominant axis, applying a drift offset perpendicular
     * to the heading at each step. Used by StraightRoad and Spur.
     */
    static List<BlockPos> driftedLine(ServerLevel level,
                                      BlockPos from, BlockPos to,
                                      double driftAmplitude,
                                      long localSeed) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        double chordLen = Math.sqrt(dx * dx + dz * dz);
        if (chordLen < 1) {
            List<BlockPos> out = new ArrayList<>();
            out.add(surfaceAt(level, from.getX(), from.getZ()));
            return out;
        }

        double perpX = -dz / chordLen;
        double perpZ = dx / chordLen;
        double ampScale = Math.min(1.0, chordLen / 64.0);

        int steps = Math.max(2, (int) Math.ceil(chordLen));
        List<BlockPos> line = new ArrayList<>(steps + 1);

        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double baseX = from.getX() + dx * t;
            double baseZ = from.getZ() + dz * t;
            double drift = DriftNoise.sample(t, localSeed) * driftAmplitude * ampScale;
            int x = (int) Math.round(baseX + perpX * drift);
            int z = (int) Math.round(baseZ + perpZ * drift);
            line.add(surfaceAt(level, x, z));
        }
        return dedupe(line);
    }

    static BlockPos surfaceAt(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    /** Removes consecutive XZ duplicates. Y differences are ignored. */
    static List<BlockPos> dedupe(List<BlockPos> line) {
        if (line.size() < 2) return line;
        List<BlockPos> out = new ArrayList<>(line.size());
        BlockPos prev = null;
        for (BlockPos p : line) {
            if (prev == null || p.getX() != prev.getX() || p.getZ() != prev.getZ()) {
                out.add(p);
                prev = p;
            }
        }
        return out;
    }

    /**
     * Generates a surface-snapped drift-noise centerline for trade road
     * realisation. Replaces A* diagonals with organic curves — the same
     * DriftNoise used by {@link StraightRoad}, seeded from the world seed
     * and the endpoint positions for determinism.
     *
     * @param driftAmplitude max perpendicular wander in blocks (6 = visible)
     */
    public static List<BlockPos> tradeCenterline(ServerLevel level,
                                                 BlockPos from, BlockPos to,
                                                 double driftAmplitude,
                                                 long worldSeed) {
        long seed = DriftNoise.localSeed(worldSeed, from, to);
        return driftedLine(level, from, to, driftAmplitude, seed);
    }

    // =========================================================================
    // Drift noise — deterministic 1D value noise
    // =========================================================================

    /**
     * Deterministic smoothed noise used to wander road centerlines.
     * Sampled along a 0..1 parameter (the road's arc position) and
     * returns a value in [-1, 1]. Two samples at similar t return
     * similar values, which is what makes the wander smooth rather
     * than jittery.
     */
    final class DriftNoise {
        private DriftNoise() {}

        /** Number of control points along the road's parameter. */
        private static final int CONTROL_POINTS = 8;

        static long localSeed(long worldSeed, BlockPos a, BlockPos b) {
            long h = worldSeed;
            h = h * 6364136223846793005L + a.getX() * 2862933555777941757L;
            h = h * 6364136223846793005L + a.getZ() * 2862933555777941757L;
            h = h * 6364136223846793005L + b.getX() * 2862933555777941757L;
            h = h * 6364136223846793005L + b.getZ() * 2862933555777941757L;
            return h ^ (h >>> 33);
        }

        /**
         * Samples smoothed noise at {@code t} in [0, 1]. Value at t=0
         * and t=1 is always 0 so road endpoints meet their targets exactly.
         */
        static double sample(double t, long localSeed) {
            if (t <= 0 || t >= 1) return 0;
            double scaled = t * (CONTROL_POINTS - 1);
            int idx = (int) Math.floor(scaled);
            double frac = scaled - idx;
            double a = hashToUnit(localSeed, idx);
            double b = hashToUnit(localSeed, idx + 1);
            // Smoothstep interpolation
            double s = frac * frac * (3 - 2 * frac);
            double value = a * (1 - s) + b * s;
            // Clamp endpoints to 0 for clean meeting
            double fadeIn = Math.min(1.0, t * 4);
            double fadeOut = Math.min(1.0, (1 - t) * 4);
            return value * fadeIn * fadeOut;
        }

        private static double hashToUnit(long seed, int i) {
            long h = seed ^ ((long) i * 0x9E3779B97F4A7C15L);
            h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
            h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
            h = h ^ (h >>> 31);
            // Map to [-1, 1]
            return ((h & 0xFFFFL) / 32767.5) - 1.0;
        }
    }
}