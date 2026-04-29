package tterrag1112.life_in_the_village.Village.Planning.Features;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * A 2D polygon in the XZ plane, stored as an ordered ring of {@link BlockPos}
 * vertices. Y values on vertices are advisory metadata about elevation —
 * containment, distance, and edge queries operate purely on XZ.
 */
public final class PolygonXZ {

    private final List<BlockPos> vertices;
    private final int minX, maxX, minZ, maxZ;

    public PolygonXZ(List<BlockPos> vertices) {
        if (vertices.size() < 3) {
            throw new IllegalArgumentException(
                "Polygon needs at least 3 vertices, got " + vertices.size());
        }
        this.vertices = List.copyOf(vertices);
        int mnx = Integer.MAX_VALUE, mxx = Integer.MIN_VALUE;
        int mnz = Integer.MAX_VALUE, mxz = Integer.MIN_VALUE;
        for (BlockPos v : this.vertices) {
            mnx = Math.min(mnx, v.getX()); mxx = Math.max(mxx, v.getX());
            mnz = Math.min(mnz, v.getZ()); mxz = Math.max(mxz, v.getZ());
        }
        this.minX = mnx; this.maxX = mxx; this.minZ = mnz; this.maxZ = mxz;
    }

    public List<BlockPos> vertices() { return vertices; }
    public int minX() { return minX; }
    public int maxX() { return maxX; }
    public int minZ() { return minZ; }
    public int maxZ() { return maxZ; }

    public boolean contains(int x, int z) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return false;
        boolean inside = false;
        int n = vertices.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            int xi = vertices.get(i).getX(), zi = vertices.get(i).getZ();
            int xj = vertices.get(j).getX(), zj = vertices.get(j).getZ();
            boolean intersect = ((zi > z) != (zj > z))
                    && (x < (long)(xj - xi) * (z - zi) / (zj - zi) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    public boolean contains(BlockPos pos) { return contains(pos.getX(), pos.getZ()); }

    public double distanceSqToEdge(int x, int z) {
        double best = Double.MAX_VALUE;
        int n = vertices.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            BlockPos a = vertices.get(i), b = vertices.get(j);
            double d = pointToSegmentSqXZ(x, z,
                    a.getX(), a.getZ(), b.getX(), b.getZ());
            if (d < best) best = d;
        }
        return best;
    }

    public record Edge(BlockPos a, BlockPos b) {}

    public Edge nearestEdge(int x, int z) {
        double best = Double.MAX_VALUE;
        Edge bestEdge = null;
        int n = vertices.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            BlockPos a = vertices.get(i), b = vertices.get(j);
            double d = pointToSegmentSqXZ(x, z,
                    a.getX(), a.getZ(), b.getX(), b.getZ());
            if (d < best) { best = d; bestEdge = new Edge(a, b); }
        }
        return bestEdge;
    }

    /**
     * Convex hull of a point set in the XZ plane via Graham scan. Output
     * vertices receive the average Y of the input points (advisory only).
     * Falls back to an axis-aligned bbox rectangle when the hull would
     * degenerate (fewer than 3 distinct extreme points, e.g. all input
     * points collinear).
     */
    public static PolygonXZ convexHull(List<BlockPos> points) {
        if (points.isEmpty()) {
            throw new IllegalArgumentException("convexHull requires at least one point");
        }

        long sumY = 0;
        for (BlockPos p : points) sumY += p.getY();
        int avgY = (int) (sumY / points.size());

        if (points.size() < 3) {
            return bboxFallback(points, avgY);
        }

        BlockPos pivot = points.get(0);
        for (BlockPos p : points) {
            if (p.getZ() < pivot.getZ()
                    || (p.getZ() == pivot.getZ() && p.getX() < pivot.getX())) {
                pivot = p;
            }
        }
        final BlockPos pv = pivot;

        List<BlockPos> sorted = new ArrayList<>(points.size());
        for (BlockPos p : points) {
            if (p.getX() != pv.getX() || p.getZ() != pv.getZ()) sorted.add(p);
        }
        sorted.sort((a, b) -> {
            long cross = (long)(a.getX() - pv.getX()) * (b.getZ() - pv.getZ())
                       - (long)(b.getX() - pv.getX()) * (a.getZ() - pv.getZ());
            if (cross != 0) return cross > 0 ? -1 : 1;
            long da = sqDistXZ(pv, a), db = sqDistXZ(pv, b);
            return Long.compare(da, db);
        });

        Deque<BlockPos> stack = new ArrayDeque<>();
        stack.push(pv);
        for (BlockPos p : sorted) {
            while (stack.size() >= 2) {
                BlockPos top = stack.pop();
                BlockPos next = stack.peek();
                long cross = (long)(top.getX() - next.getX()) * (p.getZ() - next.getZ())
                           - (long)(top.getZ() - next.getZ()) * (p.getX() - next.getX());
                if (cross > 0) {
                    stack.push(top);
                    break;
                }
            }
            stack.push(p);
        }

        List<BlockPos> hull = new ArrayList<>(stack);
        Collections.reverse(hull);

        if (hull.size() < 3) {
            return bboxFallback(points, avgY);
        }

        List<BlockPos> withAvgY = new ArrayList<>(hull.size());
        for (BlockPos p : hull) {
            withAvgY.add(new BlockPos(p.getX(), avgY, p.getZ()));
        }
        return new PolygonXZ(withAvgY);
    }

    /**
     * Inflates the polygon outward by {@code blocks}. Phase 3 stub: returns
     * the axis-aligned bbox expanded uniformly. Phase 8+ may replace with
     * proper edge-offset if hull quality matters for placement.
     */
    public PolygonXZ inflate(int blocks) {
        long sumY = 0;
        for (BlockPos v : vertices) sumY += v.getY();
        int avgY = (int) (sumY / vertices.size());
        return new PolygonXZ(List.of(
            new BlockPos(minX - blocks, avgY, minZ - blocks),
            new BlockPos(maxX + blocks, avgY, minZ - blocks),
            new BlockPos(maxX + blocks, avgY, maxZ + blocks),
            new BlockPos(minX - blocks, avgY, maxZ + blocks)
        ));
    }

    private static PolygonXZ bboxFallback(List<BlockPos> points, int avgY) {
        int mnx = Integer.MAX_VALUE, mxx = Integer.MIN_VALUE;
        int mnz = Integer.MAX_VALUE, mxz = Integer.MIN_VALUE;
        for (BlockPos p : points) {
            mnx = Math.min(mnx, p.getX()); mxx = Math.max(mxx, p.getX());
            mnz = Math.min(mnz, p.getZ()); mxz = Math.max(mxz, p.getZ());
        }
        if (mnx == mxx) mxx++;
        if (mnz == mxz) mxz++;
        return new PolygonXZ(List.of(
            new BlockPos(mnx, avgY, mnz),
            new BlockPos(mxx, avgY, mnz),
            new BlockPos(mxx, avgY, mxz),
            new BlockPos(mnx, avgY, mxz)
        ));
    }

    private static long sqDistXZ(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static double pointToSegmentSqXZ(int px, int pz,
                                             int ax, int az,
                                             int bx, int bz) {
        long dx = bx - ax, dz = bz - az;
        long lenSq = dx * dx + dz * dz;
        if (lenSq == 0) {
            long ddx = px - ax, ddz = pz - az;
            return ddx * ddx + ddz * ddz;
        }
        double t = ((px - ax) * (double) dx + (pz - az) * (double) dz) / lenSq;
        t = Math.max(0, Math.min(1, t));
        double cx = ax + t * dx, cz = az + t * dz;
        double ex = px - cx, ez = pz - cz;
        return ex * ex + ez * ez;
    }
}
