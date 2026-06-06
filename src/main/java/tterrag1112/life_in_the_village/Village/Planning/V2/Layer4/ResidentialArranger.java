package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;

import java.util.ArrayList;
import java.util.List;

/**
 * Layout Rework — computes the EXPLICIT house arrangement for a reserved
 * residential block per {@link ResidentialVariant}: where each house sits and
 * which way it faces. This replaces the emergent scorer-packing for residential
 * HOUSE — the planner places houses at these positions directly.
 *
 * <p>Y is left 0 on the returned positions; the planner snaps each to the cell
 * surface. {@code faceTarget} is the point a house should face (its
 * {@code chooseFacing} target) — the lane for STREET_ROW, the yard centre for
 * COURTYARD. The decorative render (lane blocks, tofts, well, borders) is a
 * staged follow-up; this pass produces positions + facings only.
 */
public final class ResidentialArranger {

    private ResidentialArranger() {}

    /** A planned house: its centre (y=0, planner snaps) + the point it faces. */
    public record HousePlacement(BlockPos centre, BlockPos faceTarget) {}

    /**
     * The full arrangement of one block: house placements + internal-path
     * centerlines (raw waypoints, y=0 — the planner snaps to floor-Y and tags
     * the road tier). Lanes connect to the block's edge node so the footpath
     * flows out to the main street. COURTYARD's entry path is a staged
     * follow-up, so it returns no lanes yet.
     */
    public record Arrangement(List<HousePlacement> houses,
                              List<List<BlockPos>> lanes,
                              BlockPos yardCentre) {}

    /** Half-width (blocks) of the central lane STREET_ROW houses front. Public
     *  so the district sizer can shape the block's short axis to two rows + lane. */
    public static final int LANE_HALF = 2;
    /** Clearance (blocks) between the courtyard house ring and the perimeter
     *  border, so the fence/hedge wraps OUTSIDE the houses. Public so the
     *  district sizer can compute the same inset when shaping the rectangle. */
    public static final int COURTYARD_BORDER_CLEARANCE = 3;

    /**
     * Arranges {@code houseCount} houses in {@code block} per {@code variant},

     * returning house placements + internal-path centerlines. {@code cellPitch}
     * = house footprint max-dim + gap (spacing); {@code houseDepth} = footprint
     * depth (insets rows/rings off the lane/edge); {@code edgeNode} = the block's
     * road-facing edge point, so the lane connects out to the main street.
     * Reserved variants (not yet built) fall back to STREET_ROW — never a silent
     * empty result.
     */
    public static Arrangement arrange(Polygon.AABB block, int houseCount,
                                      int cellPitch, int houseDepth,
                                      BlockPos edgeNode, ResidentialVariant variant) {
        if (houseCount <= 0) return new Arrangement(List.of(), List.of(), null);
        return switch (variant) {
            case STREET_ROW -> streetRow(block, houseCount, cellPitch, houseDepth, edgeNode);
            case COURTYARD -> courtyard(block, houseCount, houseDepth, edgeNode);
            // Reserved → fall back (not silent): arrange as a street row for now.
            case CLUSTER, GREEN, GRID_BLOCKS, TERRACE, HILLSIDE ->
                    streetRow(block, houseCount, cellPitch, houseDepth, edgeNode);
        };
    }

    // =========================================================================
    // STREET_ROW — two rows fronting a central lane along the long axis
    // =========================================================================

    private static Arrangement streetRow(Polygon.AABB block, int count,
                                         int cellPitch, int houseDepth,
                                         BlockPos edgeNode) {
        int cx = (block.minX() + block.maxX()) / 2;
        int cz = (block.minZ() + block.maxZ()) / 2;
        int wX = block.maxX() - block.minX();
        int wZ = block.maxZ() - block.minZ();
        boolean longX = wX >= wZ;
        int halfLong = (longX ? wX : wZ) / 2;

        int rowOffset = houseDepth / 2 + LANE_HALF;       // across-distance from the lane
        int alongHalfLimit = Math.max(0, halfLong - cellPitch / 2);

        int perRowFront = (count + 1) / 2;                // row on +across
        int perRowBack = count / 2;                       // row on -across

        List<HousePlacement> houses = new ArrayList<>(count);
        addRow(houses, longX, cx, cz, perRowFront, +rowOffset, cellPitch, alongHalfLimit);
        addRow(houses, longX, cx, cz, perRowBack, -rowOffset, cellPitch, alongHalfLimit);

        // Central lane: the across=0 gap BETWEEN the two rows, run to the block
        // boundary at both short ends — that gap is open there (houses sit at
        // across=±rowOffset), so the lane never crosses a footprint.
        BlockPos endA = fromAxes(longX, cx, cz, -halfLong, 0);
        BlockPos endB = fromAxes(longX, cx, cz, +halfLong, 0);
        boolean aNearer = horizDistSqr(edgeNode, endA) <= horizDistSqr(edgeNode, endB);
        BlockPos near = aNearer ? endA : endB;
        BlockPos far = aNearer ? endB : endA;
        // Stitch to the edge node only when it sits roughly off the lane's short
        // end (anchor aligned with the long axis): then the connector runs
        // through the open end gap, just past the house ends. When the edge node
        // is beside a LONG side, a connector would cross a house row, so the lane
        // simply exits the short end (side-entry stitching lands with the typed-
        // toft follow-up that reworks per-house frontage).
        int edgeAlong = longX ? (edgeNode.getX() - cx) : (edgeNode.getZ() - cz);
        boolean aligned = Math.abs(edgeAlong) >= alongHalfLimit;
        List<BlockPos> lane = aligned
                ? List.of(edgeNode, near, far)
                : List.of(near, far);
        return new Arrangement(houses, List.of(lane), null);
    }

    /** Places {@code m} houses spaced along the long axis at the given across
     *  offset; each faces the lane (across = 0 at the same along position). */
    private static void addRow(List<HousePlacement> out, boolean longX,
                               int cx, int cz, int m, int across,
                               int cellPitch, int alongHalfLimit) {
        for (int i = 0; i < m; i++) {
            int along = Math.round((i - (m - 1) / 2.0f) * cellPitch);
            along = clamp(along, -alongHalfLimit, alongHalfLimit);
            BlockPos centre = fromAxes(longX, cx, cz, along, across);
            BlockPos lane = fromAxes(longX, cx, cz, along, 0);   // face the lane
            out.add(new HousePlacement(centre, lane));
        }
    }

    // =========================================================================
    // COURTYARD — houses around the perimeter of an inset rectangle, facing in
    // =========================================================================

    private static Arrangement courtyard(Polygon.AABB block, int count,
                                         int houseDepth, BlockPos edgeNode) {
        int cx = (block.minX() + block.maxX()) / 2;
        int cz = (block.minZ() + block.maxZ()) / 2;
        BlockPos yard = new BlockPos(cx, 0, cz);
        // Inset the house ring well inside the block boundary so the perimeter
        // border (painted at the boundary) wraps OUTSIDE the houses with
        // clearance, instead of the houses clipping it.
        int inset = houseDepth / 2 + 1 + COURTYARD_BORDER_CLEARANCE;
        int ix0 = block.minX() + inset, ix1 = block.maxX() - inset;
        int iz0 = block.minZ() + inset, iz1 = block.maxZ() - inset;
        // Entry path: straight from the edge node in to the yard centre, plus a
        // ring fronting the houses (deliberate circulation, not emergent
        // branches). The planner truncates + snaps + renders these at FOOTPATH.
        List<List<BlockPos>> lanes = new ArrayList<>();
        lanes.add(List.of(edgeNode, yard));
        if (ix1 <= ix0 || iz1 <= iz0) {
            // Block too small to ring — fall back to a single centred-ish house.
            List<HousePlacement> one = new ArrayList<>();
            one.add(new HousePlacement(new BlockPos(cx, 0, iz0), yard));
            return new Arrangement(one, List.copyOf(lanes), yard);
        }
        long perim = 2L * (ix1 - ix0) + 2L * (iz1 - iz0);
        // Phase the houses so a GAP (not a house) faces the entry bearing, so the
        // entry path threads between two houses to reach the yard.
        double fracEntry = perimeterFracNearest(edgeNode, ix0, ix1, iz0, iz1);
        List<HousePlacement> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double f = fracEntry + (i + 0.5) / count;
            f -= Math.floor(f);
            long t = (long) (perim * f);
            out.add(new HousePlacement(perimeterPoint(ix0, ix1, iz0, iz1, t), yard));
        }
        // Ring path just inside the house fronts (houses face inward), as a
        // closed loop — the courtyard's deliberate circulation.
        int rIn = houseDepth / 2 + 2;
        int rx0 = ix0 + rIn, rx1 = ix1 - rIn, rz0 = iz0 + rIn, rz1 = iz1 - rIn;
        if (rx1 > rx0 && rz1 > rz0) {
            lanes.add(List.of(
                    new BlockPos(rx0, 0, rz0), new BlockPos(rx1, 0, rz0),
                    new BlockPos(rx1, 0, rz1), new BlockPos(rx0, 0, rz1),
                    new BlockPos(rx0, 0, rz0)));
        }
        return new Arrangement(out, List.copyOf(lanes), yard);
    }

    /** Normalised arc-length (0..1) of the inset-rectangle perimeter point
     *  nearest {@code p}, matching {@link #perimeterPoint}'s walk order
     *  (top → right → bottom → left). Used to phase houses so a gap faces the
     *  entry bearing. */
    private static double perimeterFracNearest(BlockPos p, int x0, int x1,
                                               int z0, int z1) {
        int ex = clamp(p.getX(), x0, x1);
        int ez = clamp(p.getZ(), z0, z1);
        long w = x1 - x0, h = z1 - z0;
        long perim = 2 * w + 2 * h;
        if (perim == 0) return 0;
        long dTop = Math.abs(p.getZ() - z0), dBot = Math.abs(p.getZ() - z1);
        long dLeft = Math.abs(p.getX() - x0), dRight = Math.abs(p.getX() - x1);
        long m = Math.min(Math.min(dTop, dBot), Math.min(dLeft, dRight));
        double arc;
        if (m == dTop) arc = ex - x0;
        else if (m == dRight) arc = w + (ez - z0);
        else if (m == dBot) arc = w + h + (x1 - ex);
        else arc = 2 * w + h + (z1 - ez);
        return arc / (double) perim;
    }

    /** Point at arc-length {@code t} (0..perim) clockwise around the rectangle
     *  [x0,x1]×[z0,z1], starting at the (x0,z0) corner. */
    private static BlockPos perimeterPoint(int x0, int x1, int z0, int z1, long t) {
        long top = x1 - x0, right = z1 - z0, bottom = x1 - x0;   // left = z1 - z0
        if (t < top) return new BlockPos((int) (x0 + t), 0, z0);
        t -= top;
        if (t < right) return new BlockPos(x1, 0, (int) (z0 + t));
        t -= right;
        if (t < bottom) return new BlockPos((int) (x1 - t), 0, z1);
        t -= bottom;
        return new BlockPos(x0, 0, (int) (z1 - t));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Maps (along, across) to world XZ — along the long axis (longX ? X : Z),
     *  across the short axis. */
    private static BlockPos fromAxes(boolean longX, int cx, int cz,
                                     int along, int across) {
        return longX
                ? new BlockPos(cx + along, 0, cz + across)
                : new BlockPos(cx + across, 0, cz + along);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Horizontal (XZ) distance² — ignores Y, which the planner snaps later. */
    private static long horizDistSqr(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
