package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.BlockCategory;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.Cell;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 Phase 2c — spine path planning.
 *
 * <p>Walks both directions from the anchor along {@code primaryAxis},
 * appending {@code STEP_LENGTH}-block straight segments while
 * terrain remains admissible. When a forward segment would hit
 * inadmissible terrain, tries arc deflections at ±10°, ±20°, ±30°
 * (smallest angle wins). Cumulative deflection from the primary
 * axis is bounded at {@link #MAX_DEFLECTION_DEG}; once exhausted,
 * truncates that direction.
 *
 * <p>Each straight segment is a {@link RoadPrimitive.StraightRoad}
 * with subtle drift ({@link #SPINE_DRIFT}) so the path has a slight
 * meander mirroring V1 linear-village character. Arcs use V1's
 * {@link RoadPrimitive.Arc}.
 *
 * <p>Half-length per direction is bounded by the tier max length so
 * total spine ≤ {@code 2 * tierHalfLength}.
 */
public final class SpinePathPlanner {

    /** Subtle meandering drift on straight spine segments. V1 linear
     *  used 8.0 (between visible and hairpin per the primitive's own
     *  doc); V2 prefers a quieter 3.0 (= "subtle wander"). */
    public static final double SPINE_DRIFT = 3.0;
    /** Per-segment forward step in blocks. Smaller = finer arc
     *  decisions; larger = less recompute. */
    private static final int STEP_LENGTH = 20;
    /** Cumulative deflection budget from {@code primaryAxis} (degrees,
     *  unsigned). Once exhausted the planner truncates rather than
     *  pushing further off-axis. */
    private static final double MAX_DEFLECTION_DEG = 30.0;
    /** Arc radius for deflection segments. Larger radius = gentler curve. */
    private static final int ARC_RADIUS = 30;
    /** Candidate deflection angles (degrees, unsigned). Smallest first. */
    private static final double[] DEFLECTION_ANGLES = {10, 20, 30};
    /** Sample step (blocks) when checking centerline admissibility. */
    private static final int ADMIN_SAMPLE_STEP = 2;
    /** Max localSlope tolerated on a centerline cell. */
    private static final int MAX_SLOPE = 3;
    /** Max number of marginal-admissibility cells permitted in a
     *  segment's sample (terrain noise tolerance). */
    private static final int MARGIN_TOLERANCE = 2;

    private SpinePathPlanner() {}

    public static SpinePath plan(V2FeatureMap fmap, BlockPos anchor,
                                 CardinalAxis primaryAxis, int tierHalfLength) {
        // Walk forward from anchor.
        WalkResult forward = walk(fmap, anchor, primaryAxis.axisUnit(), tierHalfLength);
        // Walk backward (negate axis vector).
        WalkResult backward = walk(fmap, anchor,
                primaryAxis.axisUnit().scale(-1), tierHalfLength);

        // Combine: backward segments (reversed order) + forward segments.
        List<RoadPrimitive> all = new ArrayList<>();
        for (int i = backward.segments.size() - 1; i >= 0; i--) {
            // Backward segments were generated anchor → endA. The full
            // path goes endA → anchor → endB so we want backward
            // segments in reverse and with from/to flipped.
            RoadPrimitive seg = backward.segments.get(i);
            all.add(reverse(seg));
        }
        all.addAll(forward.segments);

        BlockPos start = backward.endpoint != null ? backward.endpoint : anchor;
        BlockPos end = forward.endpoint != null ? forward.endpoint : anchor;
        int totalLength = forward.length + backward.length;

        return new SpinePath(primaryAxis, List.copyOf(all), start, end, totalLength);
    }

    // =========================================================================
    // One-direction walk
    // =========================================================================

    private static WalkResult walk(V2FeatureMap fmap, BlockPos anchor,
                                   net.minecraft.world.phys.Vec3 axisDir, int maxLength) {
        List<RoadPrimitive> segments = new ArrayList<>();
        BlockPos current = anchor;
        net.minecraft.world.phys.Vec3 dir = axisDir;
        double cumulativeDeflectionDeg = 0;
        int totalLength = 0;

        while (totalLength + STEP_LENGTH <= maxLength) {
            BlockPos forwardEnd = step(current, dir, STEP_LENGTH);
            if (segmentAdmissible(fmap, current, forwardEnd)) {
                segments.add(new RoadPrimitive.StraightRoad(current, forwardEnd,
                        SPINE_DRIFT, RoadShape.RoadTier.VILLAGE_ROAD));
                current = forwardEnd;
                totalLength += STEP_LENGTH;
                continue;
            }

            // Try arc deflections at ±10/20/30°.
            ArcCandidate best = null;
            double remainingBudget = MAX_DEFLECTION_DEG - cumulativeDeflectionDeg;
            for (double absAngle : DEFLECTION_ANGLES) {
                if (absAngle > remainingBudget) break;
                for (int sign : new int[]{+1, -1}) {
                    double signedDeg = absAngle * sign;
                    ArcCandidate c = tryArc(fmap, current, dir, signedDeg);
                    if (c == null) continue;
                    if (best == null || absAngle < best.absAngleDeg) {
                        best = c;
                    }
                }
                if (best != null) break;  // smallest-angle preference
            }

            if (best == null) break;  // truncate this direction
            segments.add(best.arc);
            current = best.exitPos;
            dir = best.exitDir;
            cumulativeDeflectionDeg += best.absAngleDeg;
            totalLength += STEP_LENGTH;
        }

        return new WalkResult(segments, totalLength,
                segments.isEmpty() ? null : current);
    }

    private static BlockPos step(BlockPos from, net.minecraft.world.phys.Vec3 dir,
                                 int length) {
        return new BlockPos(
                from.getX() + (int) Math.round(dir.x * length),
                from.getY(),
                from.getZ() + (int) Math.round(dir.z * length));
    }

    /** True iff the straight from {@code start} to {@code end} stays
     *  on admissible cells (≥ MAX_SLOPE-good, OPEN/SHORE) with at most
     *  {@link #MARGIN_TOLERANCE} marginal cells. */
    private static boolean segmentAdmissible(V2FeatureMap fmap, BlockPos start,
                                             BlockPos end) {
        double dx = end.getX() - start.getX();
        double dz = end.getZ() - start.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1) return true;
        int steps = Math.max(1, (int) Math.round(len / ADMIN_SAMPLE_STEP));
        int marginal = 0;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int x = (int) Math.round(start.getX() + dx * t);
            int z = (int) Math.round(start.getZ() + dz * t);
            if (!fmap.inBounds(x, z)) return false;
            Cell cell = fmap.cellAt(x, z);
            BlockCategory cat = cell.category();
            boolean hardFail = (cat != BlockCategory.OPEN && cat != BlockCategory.SHORE);
            if (hardFail) return false;
            if (cell.localSlope() > MAX_SLOPE) {
                marginal++;
                if (marginal > MARGIN_TOLERANCE) return false;
            }
        }
        return true;
    }

    // =========================================================================
    // Arc deflection
    // =========================================================================

    /** Attempts an arc of {@code signedDeg} degrees deflection from
     *  {@code currentDir} starting at {@code currentPos}, with fixed
     *  {@link #ARC_RADIUS}. Returns {@code null} if the arc's
     *  centerline isn't admissible. */
    private static ArcCandidate tryArc(V2FeatureMap fmap, BlockPos currentPos,
                                       net.minecraft.world.phys.Vec3 currentDir,
                                       double signedDeg) {
        double signedRad = Math.toRadians(signedDeg);
        // Arc centre is perpendicular to currentDir, on the side of
        // the turn (left for + deflection, right for −).
        // 90° CCW perpendicular = (-dz, dx); flip for - turns.
        double perpX, perpZ;
        if (signedDeg > 0) {
            perpX = -currentDir.z;
            perpZ = currentDir.x;
        } else {
            perpX = currentDir.z;
            perpZ = -currentDir.x;
        }
        BlockPos centre = new BlockPos(
                currentPos.getX() + (int) Math.round(perpX * ARC_RADIUS),
                currentPos.getY(),
                currentPos.getZ() + (int) Math.round(perpZ * ARC_RADIUS));

        // startAngle = angle from centre to currentPos
        double rcx = currentPos.getX() - centre.getX();
        double rcz = currentPos.getZ() - centre.getZ();
        double startAngle = Math.atan2(rcz, rcx);
        // arcSpan: signed (positive = CCW). For + deflection turning
        // left, the arc traverses CCW (positive angle increase).
        double arcSpan = signedRad;

        // Sample the arc and check admissibility.
        int samples = Math.max(4, Math.abs((int) Math.round(arcSpan * ARC_RADIUS)));
        int marginal = 0;
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples;
            double angle = startAngle + arcSpan * t;
            int x = centre.getX() + (int) Math.round(Math.cos(angle) * ARC_RADIUS);
            int z = centre.getZ() + (int) Math.round(Math.sin(angle) * ARC_RADIUS);
            if (!fmap.inBounds(x, z)) return null;
            Cell cell = fmap.cellAt(x, z);
            BlockCategory cat = cell.category();
            if (cat != BlockCategory.OPEN && cat != BlockCategory.SHORE) return null;
            if (cell.localSlope() > MAX_SLOPE) {
                marginal++;
                if (marginal > MARGIN_TOLERANCE) return null;
            }
        }

        // Exit position + direction.
        double exitAngle = startAngle + arcSpan;
        BlockPos exitPos = new BlockPos(
                centre.getX() + (int) Math.round(Math.cos(exitAngle) * ARC_RADIUS),
                centre.getY(),
                centre.getZ() + (int) Math.round(Math.sin(exitAngle) * ARC_RADIUS));
        // Tangent at exit = perpendicular to radius. For positive
        // arcSpan (CCW), tangent direction = (-sin, cos) at exit angle.
        double tx = -Math.sin(exitAngle) * Math.signum(arcSpan);
        double tz = Math.cos(exitAngle) * Math.signum(arcSpan);
        net.minecraft.world.phys.Vec3 exitDir = new net.minecraft.world.phys.Vec3(tx, 0, tz);

        RoadPrimitive.Arc arc = new RoadPrimitive.Arc(centre, ARC_RADIUS, startAngle,
                arcSpan, SPINE_DRIFT, RoadShape.RoadTier.VILLAGE_ROAD);
        return new ArcCandidate(arc, Math.abs(signedDeg), exitPos, exitDir);
    }

    /** Reverses a primitive's start/end so a backward-walk segment
     *  reads anchor → endpoint after concatenation. Only StraightRoad
     *  and Arc are produced by this planner; anything else is left
     *  as-is (defensive — shouldn't happen). */
    private static RoadPrimitive reverse(RoadPrimitive prim) {
        if (prim instanceof RoadPrimitive.StraightRoad sr) {
            return new RoadPrimitive.StraightRoad(sr.to(), sr.from(),
                    sr.driftAmplitude(), sr.tier());
        }
        if (prim instanceof RoadPrimitive.Arc arc) {
            // Reverse: start at endAngle, sweep -arcSpan back to startAngle.
            return new RoadPrimitive.Arc(arc.centre(), arc.radius(),
                    arc.startAngle() + arc.arcSpan(), -arc.arcSpan(),
                    arc.driftAmplitude(), arc.tier());
        }
        return prim;
    }

    private record WalkResult(List<RoadPrimitive> segments, int length,
                              BlockPos endpoint) {}

    private record ArcCandidate(RoadPrimitive.Arc arc, double absAngleDeg,
                                BlockPos exitPos,
                                net.minecraft.world.phys.Vec3 exitDir) {}
}
