package tterrag1112.life_in_the_village.Village.Farms.Complex;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Detour A — converts a set of admitted grid cells (the output of
 * {@link FloodFillRegionClaim}) into a closed {@link Polygon}
 * tracing the boundary of the cell set.
 *
 * <p>Algorithm: for each cell in the set, examine its 4 cardinal
 * neighbours. For each neighbour NOT in the set (or out of bounds),
 * emit the shared edge as a directed boundary segment (clockwise
 * around the set). Then walk the emitted edges by matching head→tail
 * to produce a closed vertex list.
 *
 * <p>Coordinates are world-space {@link BlockPos} at the grid
 * corners. Y is fixed at the value supplied by the caller (the
 * farmhouse anchor's Y; vertices are 2D-XZ).
 *
 * <p>The output polygon is then {@link Polygon#simplify simplified}
 * with a 1-block tolerance to collapse colinear runs — boundary
 * trace produces a vertex at every grid corner along a long
 * straight edge, which is correct but noisy.
 *
 * <p>Pure function. Same cell set, same output. Handles only
 * simply-connected sets — flood-fill from a single seed produces
 * one connected component, so no hole-detection needed.
 */
public final class CellPolygonizer {

    private CellPolygonizer() {}

    /** Pack a grid coord pair into a long for set membership. */
    public static long packCell(int i, int j) {
        return ((long) i << 32) | (j & 0xFFFFFFFFL);
    }

    /** Convert an admitted cell set to a closed boundary polygon.
     *
     *  @param cells       packed {@code (i, j)} grid coords admitted
     *                     by flood-fill (use {@link #packCell}).
     *  @param cellSize    side length of a grid cell in blocks
     *                     ({@code V2FeatureMap.CELL_SIZE}, == 2 today).
     *  @param worldOrigin world position of grid cell {@code (0, 0)}'s
     *                     top-left corner — the {@code (minX, _, minZ)}
     *                     of the FeatureMap's scanned area.
     *  @param vertexY     Y coordinate to attach to every vertex.
     *  @return            boundary polygon, simplified. Null when the
     *                     cell set is empty or too small to bound.
     */
    public static Polygon polygonize(Set<Long> cells, int cellSize,
                                      BlockPos worldOrigin, int vertexY) {
        if (cells == null || cells.size() < 3) return null;

        // Collect directed boundary edges. Each edge is a pair of
        // grid corners (corner coords, not cell coords). Edges are
        // emitted clockwise around the interior so the resulting
        // polygon has CW winding.
        //
        // For cell (i, j) with top-left corner (i, j) and bottom-
        // right corner (i+1, j+1):
        //   - top edge (neighbour (i, j-1) absent):
        //         from (i, j) to (i+1, j)
        //   - right edge (neighbour (i+1, j) absent):
        //         from (i+1, j) to (i+1, j+1)
        //   - bottom edge (neighbour (i, j+1) absent):
        //         from (i+1, j+1) to (i, j+1)
        //   - left edge (neighbour (i-1, j) absent):
        //         from (i, j+1) to (i, j)
        List<long[]> edges = new ArrayList<>();
        for (long packed : cells) {
            int i = (int) (packed >> 32);
            int j = (int) packed;
            if (!cells.contains(packCell(i, j - 1))) {
                edges.add(new long[]{packCorner(i, j),     packCorner(i + 1, j)});
            }
            if (!cells.contains(packCell(i + 1, j))) {
                edges.add(new long[]{packCorner(i + 1, j), packCorner(i + 1, j + 1)});
            }
            if (!cells.contains(packCell(i, j + 1))) {
                edges.add(new long[]{packCorner(i + 1, j + 1), packCorner(i, j + 1)});
            }
            if (!cells.contains(packCell(i - 1, j))) {
                edges.add(new long[]{packCorner(i, j + 1), packCorner(i, j)});
            }
        }
        if (edges.isEmpty()) return null;

        // Walk edges by matching tail→head. Start at the topmost-
        // leftmost edge's start corner so the walk is deterministic
        // across runs.
        long startCorner = Long.MAX_VALUE;
        for (long[] e : edges) {
            if (e[0] < startCorner) startCorner = e[0];
        }
        // Index remaining edges by their start corner.
        java.util.Map<Long, java.util.List<long[]>> bySrc = new java.util.HashMap<>();
        for (long[] e : edges) {
            bySrc.computeIfAbsent(e[0], k -> new ArrayList<>()).add(e);
        }
        List<Long> walk = new ArrayList<>();
        Set<Object> usedEdges = new HashSet<>();
        long cursor = startCorner;
        walk.add(cursor);
        int safety = edges.size() + 4;
        while (safety-- > 0) {
            List<long[]> out = bySrc.get(cursor);
            if (out == null || out.isEmpty()) break;
            long[] picked = null;
            for (long[] e : out) {
                if (!usedEdges.contains(e)) { picked = e; break; }
            }
            if (picked == null) break;
            usedEdges.add(picked);
            cursor = picked[1];
            if (cursor == startCorner) break;
            walk.add(cursor);
        }
        if (walk.size() < 3) return null;

        // Convert grid corners → world BlockPos.
        List<BlockPos> verts = new ArrayList<>(walk.size());
        for (long c : walk) {
            int cx = (int) (c >> 32);
            int cz = (int) c;
            int wx = worldOrigin.getX() + cx * cellSize;
            int wz = worldOrigin.getZ() + cz * cellSize;
            verts.add(new BlockPos(wx, vertexY, wz));
        }
        Polygon raw = new Polygon(verts);
        return Polygon.simplify(raw, 1.0);
    }

    /** E.bug.4 — Chaikin corner-cutting smoothing pass.
     *
     *  <p>Replaces each polygon vertex with two new vertices at
     *  1/4 and 3/4 along the incoming/outgoing edges. One
     *  iteration rounds off sharp corners; the result reads as
     *  organic / lobed rather than rectilinear. Vertex count
     *  doubles per iteration — only used on the OUTER region
     *  polygon (where high vertex count is fine), never on plot
     *  perimeters (where the border renderer's edge rasterization
     *  is per-vertex-pair and keeping them tight is desirable).
     *
     *  <p>Output is simplified at 1.5 tolerance to drop the
     *  smallest 1-block jitter the corner-cutting still leaves,
     *  yielding a polygon ~1.5× the input vertex count rather
     *  than 2×.
     *
     *  @param poly       input polygon
     *  @param iterations 1 is the usual call; 2+ gives noticeably
     *                    smoother / more circular shape
     *  @return smoothed polygon, or {@code poly} unchanged if it
     *          has fewer than 4 vertices */
    public static Polygon chaikinSmooth(Polygon poly, int iterations) {
        if (poly == null) return null;
        List<BlockPos> current = poly.vertices();
        int y = current.isEmpty() ? 0 : current.get(0).getY();
        for (int iter = 0; iter < iterations && current.size() >= 4; iter++) {
            int n = current.size();
            List<BlockPos> next = new ArrayList<>(n * 2);
            for (int i = 0; i < n; i++) {
                BlockPos a = current.get(i);
                BlockPos b = current.get((i + 1) % n);
                int qx = (int) Math.round(a.getX() * 0.75 + b.getX() * 0.25);
                int qz = (int) Math.round(a.getZ() * 0.75 + b.getZ() * 0.25);
                int rx = (int) Math.round(a.getX() * 0.25 + b.getX() * 0.75);
                int rz = (int) Math.round(a.getZ() * 0.25 + b.getZ() * 0.75);
                next.add(new BlockPos(qx, y, qz));
                next.add(new BlockPos(rx, y, rz));
            }
            current = next;
        }
        if (current.size() < 3) return poly;
        return Polygon.simplify(new Polygon(current), 1.5);
    }

    /** Pack a grid CORNER coord — same layout as packCell but on
     *  the corner lattice. Kept separate for readability. */
    private static long packCorner(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
