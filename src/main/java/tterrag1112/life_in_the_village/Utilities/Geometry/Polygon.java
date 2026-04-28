package tterrag1112.life_in_the_village.Utilities.Geometry;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Closed 2D polygon in the XZ plane. Vertices are listed in order
 * (CW or CCW); the polygon is implicitly closed by the segment from
 * the last vertex back to the first.
 *
 * <p>Phase 16 (decoration rework, doc 04) introduces this utility
 * for the polygon-based plaza model. Polygons here are typically
 * 4-32 vertices; algorithms are written for clarity rather than
 * raw throughput.</p>
 *
 * <p>Y coordinate is intentionally NOT carried on vertices — the
 * polygon is a 2D outline. Plaza floor Y lives separately on
 * {@code PlazaRegion.floorY}. Methods that take a {@link BlockPos}
 * use only its X and Z components.</p>
 */
public record Polygon(List<BlockPos> vertices) {

    public Polygon {
        if (vertices == null || vertices.size() < 3) {
            throw new IllegalArgumentException(
                    "Polygon needs at least 3 vertices, got "
                            + (vertices == null ? "null" : vertices.size()));
        }
        vertices = List.copyOf(vertices);
    }

    public static final Codec<Polygon> CODEC = RecordCodecBuilder.create(i -> i.group(
            BlockPos.CODEC.listOf().fieldOf("vertices")
                    .forGetter(Polygon::vertices)
    ).apply(i, Polygon::new));

    // ── Containment ─────────────────────────────────────────────────────

    /**
     * Standard ray-casting point-in-polygon test in the XZ plane.
     * Boundary points are treated as inside (a horizontal ray cast
     * to +X is consistent in its tie-breaking).
     */
    public static boolean contains(Polygon poly, BlockPos point) {
        return contains(poly, point.getX(), point.getZ());
    }

    public static boolean contains(Polygon poly, int x, int z) {
        List<BlockPos> v = poly.vertices();
        int n = v.size();
        boolean inside = false;
        int j = n - 1;
        for (int i = 0; i < n; i++) {
            int xi = v.get(i).getX();
            int zi = v.get(i).getZ();
            int xj = v.get(j).getX();
            int zj = v.get(j).getZ();
            // Edge crosses the horizontal ray through (x, z) extending +X.
            boolean intersects = ((zi > z) != (zj > z))
                    && (x < (double) (xj - xi) * (z - zi) / (double) (zj - zi) + xi);
            if (intersects) inside = !inside;
            j = i;
        }
        return inside;
    }

    // ── Distance to edge ────────────────────────────────────────────────

    /**
     * Returns the unsigned XZ distance from {@code point} to the
     * nearest edge of the polygon (interior or exterior). Useful for
     * "is this position within N blocks of the polygon boundary"
     * tests — e.g. PLAZA_ADJACENT slot proximity in prompt 18.
     */
    public static double distanceToEdge(Polygon poly, BlockPos point) {
        List<BlockPos> v = poly.vertices();
        double best = Double.POSITIVE_INFINITY;
        int n = v.size();
        int j = n - 1;
        for (int i = 0; i < n; i++) {
            double d = pointSegmentDistance(
                    point.getX(), point.getZ(),
                    v.get(j).getX(), v.get(j).getZ(),
                    v.get(i).getX(), v.get(i).getZ());
            if (d < best) best = d;
            j = i;
        }
        return best;
    }

    /** Perpendicular distance from {@code (px, pz)} to the segment
     *  from {@code (ax, az)} to {@code (bx, bz)}, clamped to segment
     *  endpoints. */
    private static double pointSegmentDistance(int px, int pz,
                                               int ax, int az,
                                               int bx, int bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSq = dx * dx + dz * dz;
        if (lengthSq == 0) {
            double ex = px - ax;
            double ez = pz - az;
            return Math.sqrt(ex * ex + ez * ez);
        }
        double t = ((px - ax) * dx + (pz - az) * dz) / lengthSq;
        if (t < 0) t = 0;
        else if (t > 1) t = 1;
        double cx = ax + t * dx;
        double cz = az + t * dz;
        double ex = px - cx;
        double ez = pz - cz;
        return Math.sqrt(ex * ex + ez * ez);
    }

    // ── Area (shoelace) ─────────────────────────────────────────────────

    /** Unsigned XZ area in square blocks. */
    public static double area(Polygon poly) {
        List<BlockPos> v = poly.vertices();
        int n = v.size();
        double sum = 0;
        int j = n - 1;
        for (int i = 0; i < n; i++) {
            sum += (double) (v.get(j).getX() + v.get(i).getX())
                    * (v.get(j).getZ() - v.get(i).getZ());
            j = i;
        }
        return Math.abs(sum) / 2.0;
    }

    // ── Bounding box ────────────────────────────────────────────────────

    /** Axis-aligned XZ bounding box. Y is the polygon's first vertex
     *  Y for both min and max — this utility doesn't track elevation. */
    public static AABB boundingBox(Polygon poly) {
        List<BlockPos> v = poly.vertices();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : v) {
            if (p.getX() < minX) minX = p.getX();
            if (p.getX() > maxX) maxX = p.getX();
            if (p.getZ() < minZ) minZ = p.getZ();
            if (p.getZ() > maxZ) maxZ = p.getZ();
        }
        return new AABB(minX, minZ, maxX, maxZ);
    }

    /** Compact 2D AABB used by {@link #boundingBox(Polygon)}. */
    public record AABB(int minX, int minZ, int maxX, int maxZ) {
        public int width()  { return maxX - minX + 1; }
        public int length() { return maxZ - minZ + 1; }
    }

    // ── Douglas-Peucker simplification ──────────────────────────────────

    /**
     * Standard Douglas-Peucker polyline simplification, applied to
     * the closed polygon by simplifying each "half" between the two
     * extreme-distance vertices and stitching them back together.
     *
     * <p>{@code tolerance} is in block units. Default usage:
     * IRREGULAR plaza polygons feed in raw boundary samples and
     * call simplify with tolerance ≈ 1.5 to drop vertices that are
     * collinear within ~1 block of their neighbours.</p>
     *
     * <p>If the simplified vertex count drops below 3, returns the
     * original polygon — a polygon needs at least a triangle.</p>
     */
    public static Polygon simplify(Polygon poly, double tolerance) {
        List<BlockPos> v = poly.vertices();
        int n = v.size();
        if (n <= 3) return poly;

        // Find the two vertices farthest apart; split the closed
        // polygon into two open polylines at those, simplify each,
        // and stitch.
        int a = 0;
        int b = 0;
        double bestDistSq = -1;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double dx = v.get(i).getX() - v.get(j).getX();
                double dz = v.get(i).getZ() - v.get(j).getZ();
                double d = dx * dx + dz * dz;
                if (d > bestDistSq) {
                    bestDistSq = d;
                    a = i;
                    b = j;
                }
            }
        }
        List<BlockPos> firstHalf = ringSlice(v, a, b);
        List<BlockPos> secondHalf = ringSlice(v, b, a);
        List<BlockPos> simpFirst = douglasPeucker(firstHalf, tolerance);
        List<BlockPos> simpSecond = douglasPeucker(secondHalf, tolerance);

        // Stitch — drop the duplicated endpoint between halves.
        List<BlockPos> out = new ArrayList<>(simpFirst.size() + simpSecond.size() - 2);
        out.addAll(simpFirst);
        for (int i = 1; i < simpSecond.size() - 1; i++) {
            out.add(simpSecond.get(i));
        }
        if (out.size() < 3) return poly;
        return new Polygon(out);
    }

    private static List<BlockPos> ringSlice(List<BlockPos> v, int from, int to) {
        List<BlockPos> out = new ArrayList<>();
        int i = from;
        int n = v.size();
        out.add(v.get(i));
        while (i != to) {
            i = (i + 1) % n;
            out.add(v.get(i));
        }
        return out;
    }

    private static List<BlockPos> douglasPeucker(List<BlockPos> line, double tolerance) {
        int n = line.size();
        if (n <= 2) return new ArrayList<>(line);
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        douglasPeuckerRecursive(line, 0, n - 1, tolerance, keep);
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < n; i++) if (keep[i]) out.add(line.get(i));
        return out;
    }

    private static void douglasPeuckerRecursive(List<BlockPos> line,
                                                int from, int to,
                                                double tolerance,
                                                boolean[] keep) {
        if (to <= from + 1) return;
        double maxDist = -1;
        int maxIdx = -1;
        int ax = line.get(from).getX();
        int az = line.get(from).getZ();
        int bx = line.get(to).getX();
        int bz = line.get(to).getZ();
        for (int i = from + 1; i < to; i++) {
            double d = pointSegmentDistance(
                    line.get(i).getX(), line.get(i).getZ(),
                    ax, az, bx, bz);
            if (d > maxDist) {
                maxDist = d;
                maxIdx = i;
            }
        }
        if (maxDist > tolerance && maxIdx > 0) {
            keep[maxIdx] = true;
            douglasPeuckerRecursive(line, from, maxIdx, tolerance, keep);
            douglasPeuckerRecursive(line, maxIdx, to, tolerance, keep);
        }
    }

    // ── Centroid ────────────────────────────────────────────────────────

    /**
     * Returns the centroid (geometric center) of the polygon.
     * Uses the standard signed-area weighted formula. Y component
     * is the first vertex's Y — this utility doesn't track
     * elevation. Callers that need plaza floor Y should use
     * {@code PlazaRegion.floorY()} instead.
     */
    public static BlockPos centroid(Polygon poly) {
        List<BlockPos> v = poly.vertices();
        int n = v.size();
        double cx = 0, cz = 0;
        double signedArea = 0;
        int j = n - 1;
        for (int i = 0; i < n; i++) {
            double xi = v.get(i).getX();
            double zi = v.get(i).getZ();
            double xj = v.get(j).getX();
            double zj = v.get(j).getZ();
            double cross = xj * zi - xi * zj;
            signedArea += cross;
            cx += (xj + xi) * cross;
            cz += (zj + zi) * cross;
            j = i;
        }
        signedArea /= 2.0;
        if (signedArea == 0) {
            // Degenerate (collinear) — fall back to mean of vertices.
            int sx = 0, sz = 0;
            for (BlockPos p : v) { sx += p.getX(); sz += p.getZ(); }
            return new BlockPos(sx / n, v.get(0).getY(), sz / n);
        }
        cx /= 6.0 * signedArea;
        cz /= 6.0 * signedArea;
        return new BlockPos((int) Math.round(cx), v.get(0).getY(), (int) Math.round(cz));
    }
}
