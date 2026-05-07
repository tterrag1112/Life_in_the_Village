package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.Footprint;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;

/**
 * V2 Layer 4 geometric helpers. All math is on the abstract
 * primitive (chord / arc / circle), not on a realised centerline —
 * Layer 4 is a graph-building pass.
 *
 * <p>For {@link RoadPrimitive.CurvedRoad} we sample the
 * quadratic-Bezier midpoint-bow used by V1's centerline computation
 * (see {@link RoadPrimitive.CurvedRoad#computeCenterline}); 17
 * samples is enough for greedy distance-to-road queries.
 */
public final class RoadGeometry {

    private static final int CURVED_ROAD_SAMPLES = 17;

    private RoadGeometry() {}

    /** Distance from {@code point} (XZ) to the road's geometric form. */
    public static double distanceToRoad(BlockPos point, RoadPrimitive primitive) {
        BlockPos closest = closestPointOnRoad(point, primitive);
        return Math.sqrt(squaredXZ(point, closest));
    }

    /** Closest point on the road's abstract form to {@code point} (XZ). */
    public static BlockPos closestPointOnRoad(BlockPos point, RoadPrimitive primitive) {
        if (primitive instanceof RoadPrimitive.StraightRoad sr) {
            return projectOntoSegment(point, sr.from(), sr.to());
        }
        if (primitive instanceof RoadPrimitive.CurvedRoad cr) {
            return projectOntoCurvedRoad(point, cr);
        }
        if (primitive instanceof RoadPrimitive.Ring ring) {
            return projectOntoRing(point, ring.centre(), ring.radius());
        }
        // Other primitives (Arc, Spur, SmoothedPath, Stairway, etc.) —
        // V1 Layer 4 doesn't construct them, so falling back to the
        // primitive's endpoint endpoints isn't meaningful. Treat them
        // as their from-endpoint if it's accessible; otherwise return
        // {@code point} (zero distance, neutral).
        return point;
    }

    /** Projects {@code p} onto the segment {@code a}→{@code b} (XZ),
     *  clamping {@code t} to {@code [0, 1]}. Y is taken from the
     *  segment endpoint nearest to the projected position. */
    public static BlockPos projectOntoSegment(BlockPos p, BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double lenSq = dx * dx + dz * dz;
        if (lenSq < 1e-9) return a;
        double t = ((p.getX() - a.getX()) * dx + (p.getZ() - a.getZ()) * dz) / lenSq;
        t = Math.max(0, Math.min(1, t));
        int x = (int) Math.round(a.getX() + dx * t);
        int z = (int) Math.round(a.getZ() + dz * t);
        int y = (t < 0.5) ? a.getY() : b.getY();
        return new BlockPos(x, y, z);
    }

    /** Closest point on a circle (XZ) to {@code p}. */
    public static BlockPos projectOntoRing(BlockPos p, BlockPos centre, int radius) {
        double dx = p.getX() - centre.getX();
        double dz = p.getZ() - centre.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1e-9) {
            // Point at centre — pick an arbitrary ring point.
            return new BlockPos(centre.getX() + radius, centre.getY(), centre.getZ());
        }
        int x = centre.getX() + (int) Math.round(dx / dist * radius);
        int z = centre.getZ() + (int) Math.round(dz / dist * radius);
        return new BlockPos(x, centre.getY(), z);
    }

    /** Closest point on the curve sampled along the V1
     *  CurvedRoad's quadratic-Bezier midpoint-bow. */
    private static BlockPos projectOntoCurvedRoad(BlockPos p, RoadPrimitive.CurvedRoad cr) {
        BlockPos a = cr.from();
        BlockPos b = cr.to();
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double chord = Math.sqrt(dx * dx + dz * dz);
        if (chord < 1) return a;

        double perpX = -dz / chord;
        double perpZ = dx / chord;
        double bow = cr.curvature() * chord;

        BlockPos best = a;
        long bestD2 = Long.MAX_VALUE;
        for (int i = 0; i < CURVED_ROAD_SAMPLES; i++) {
            double t = i / (double) (CURVED_ROAD_SAMPLES - 1);
            double s = 4 * t * (1 - t);
            int x = (int) Math.round(a.getX() + dx * t + perpX * bow * s);
            int z = (int) Math.round(a.getZ() + dz * t + perpZ * bow * s);
            BlockPos candidate = new BlockPos(x, a.getY(), z);
            long d2 = squaredXZ(p, candidate);
            if (d2 < bestD2) {
                bestD2 = d2;
                best = candidate;
            }
        }
        return best;
    }

    /** Closest point on {@code building}'s axis-aligned footprint
     *  rectangle to {@code from}. Footprint is centred at
     *  {@code building.centre} with the recorded width × length;
     *  this routine clamps {@code from} into that rect. */
    public static BlockPos closestPointOnFootprint(BlockPos from, PlacedBuilding building) {
        Footprint fp = building.footprint();
        BlockPos c = building.centre();
        int halfW = fp.width() / 2;
        int halfL = fp.length() / 2;
        int x = clamp(from.getX(), c.getX() - halfW, c.getX() + halfW);
        int z = clamp(from.getZ(), c.getZ() - halfL, c.getZ() + halfL);
        return new BlockPos(x, c.getY(), z);
    }

    /** True if the segment {@code a}→{@code b} (XZ) crosses
     *  {@code building}'s axis-aligned footprint rectangle. Uses a
     *  Liang-Barsky-style clip for the segment against the rect. */
    public static boolean segmentCrossesFootprint(BlockPos a, BlockPos b,
                                                  PlacedBuilding building) {
        Footprint fp = building.footprint();
        BlockPos c = building.centre();
        int halfW = fp.width() / 2;
        int halfL = fp.length() / 2;
        return segmentIntersectsAabb(a.getX(), a.getZ(), b.getX(), b.getZ(),
                c.getX() - halfW, c.getZ() - halfL,
                c.getX() + halfW, c.getZ() + halfL);
    }

    /** True if footprint AABB overlaps the corridor of half-width
     *  {@code corridorHalf} blocks around segment {@code a}→{@code b}.
     *  Approximate (samples 9 points along the segment). */
    public static boolean footprintOverlapsCorridor(PlacedBuilding building,
                                                    BlockPos a, BlockPos b,
                                                    int corridorHalf) {
        for (int i = 0; i <= 8; i++) {
            double t = i / 8.0;
            int x = (int) Math.round(a.getX() + (b.getX() - a.getX()) * t);
            int z = (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t);
            BlockPos sample = new BlockPos(x, a.getY(), z);
            BlockPos clamped = closestPointOnFootprint(sample, building);
            int dx = sample.getX() - clamped.getX();
            int dz = sample.getZ() - clamped.getZ();
            if (dx * dx + dz * dz <= corridorHalf * corridorHalf) return true;
        }
        return false;
    }

    /** True if the building footprint rectangle overlaps the annulus
     *  from {@code radius - corridorHalf} to {@code radius + corridorHalf}
     *  around {@code centre}. Approximate via 4 corner checks. */
    public static boolean footprintOverlapsRingCorridor(PlacedBuilding building,
                                                        BlockPos centre,
                                                        int radius, int corridorHalf) {
        Footprint fp = building.footprint();
        BlockPos bc = building.centre();
        int halfW = fp.width() / 2;
        int halfL = fp.length() / 2;
        int[][] corners = {
                {bc.getX() - halfW, bc.getZ() - halfL},
                {bc.getX() + halfW, bc.getZ() - halfL},
                {bc.getX() - halfW, bc.getZ() + halfL},
                {bc.getX() + halfW, bc.getZ() + halfL},
                {bc.getX(), bc.getZ()}  // centre, for full-interior test
        };
        for (int[] corner : corners) {
            int dx = corner[0] - centre.getX();
            int dz = corner[1] - centre.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (Math.abs(dist - radius) <= corridorHalf) return true;
        }
        // Centre inside the inner edge of the annulus → footprint covers it.
        // Already covered by the "centre" point above when its distance < radius - corridorHalf.
        return false;
    }

    private static long squaredXZ(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 2D segment-vs-AABB intersection (Liang-Barsky). Returns true
     *  iff any portion of the segment lies inside (or on the
     *  boundary of) the rectangle. */
    private static boolean segmentIntersectsAabb(int ax, int az, int bx, int bz,
                                                 int minX, int minZ,
                                                 int maxX, int maxZ) {
        double dx = bx - ax;
        double dz = bz - az;
        double tMin = 0, tMax = 1;
        double[] p = {-dx, dx, -dz, dz};
        double[] q = {ax - minX, maxX - ax, az - minZ, maxZ - az};
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 1e-9) {
                if (q[i] < 0) return false;
                continue;
            }
            double r = q[i] / p[i];
            if (p[i] < 0) {
                if (r > tMax) return false;
                if (r > tMin) tMin = r;
            } else {
                if (r < tMin) return false;
                if (r < tMax) tMax = r;
            }
        }
        return tMin <= tMax;
    }
}
