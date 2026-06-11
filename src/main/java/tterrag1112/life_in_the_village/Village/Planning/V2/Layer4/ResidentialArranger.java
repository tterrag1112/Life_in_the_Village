package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Layout Rework — computes the EXPLICIT house arrangement for a reserved
 * residential block per {@link ResidentialVariant}: where each house sits and
 * which way it faces. This replaces the emergent scorer-packing for residential
 * HOUSE — the planner places houses at these positions directly.
 *
 * <p>Y is left 0 on the returned positions; the planner snaps each to the cell
 * surface. {@code faceTarget} is the point a house should face (its
 * {@code chooseFacing} target) — the lane for STREET_ROW, the yard centre for
 * COURTYARD, the green centre for GREEN, the lane knot for CLUSTER, the
 * serving corridor for GRID_BLOCKS.
 *
 * <p>A1 stage 1 — GREEN (Angerdorf), CLUSTER (Haufendorf) and GRID_BLOCKS
 * (BSP streets + alleys) are live; TERRACE / HILLSIDE remain reserved
 * (forced → street-row fallback, never a silent no-op).
 */
public final class ResidentialArranger {

    private ResidentialArranger() {}

    /** A planned house: its centre (y=0, planner snaps) + the point it faces. */
    public record HousePlacement(BlockPos centre, BlockPos faceTarget) {}

    /**
     * The full arrangement of one block: house placements + internal-path
     * centerlines (raw waypoints, y=0 — the planner snaps to floor-Y and tags
     * the road tier). {@code lanes} render at FOOTPATH; {@code streets} one
     * tier up at VILLAGE_PATH (GRID_BLOCKS internal streets, GREEN's skirt
     * loop + entry). Lanes connect to the block's edge node so the footpath
     * flows out to the main street. {@code green} is the GREEN variant's
     * communal-green bounds (null for every other variant) — the planner
     * carries it to the render pass as flora + optional well.
     */
    public record Arrangement(List<HousePlacement> houses,
                              List<List<BlockPos>> lanes,
                              List<List<BlockPos>> streets,
                              BlockPos yardCentre,
                              Polygon.AABB green) {}

    /** Half-width (blocks) of the central lane STREET_ROW houses front. Public
     *  so the district sizer can shape the block's short axis to two rows + lane. */
    public static final int LANE_HALF = 2;
    /** Clearance (blocks) between the courtyard house ring and the perimeter
     *  border, so the fence/hedge wraps OUTSIDE the houses. Public so the
     *  district sizer can compute the same inset when shaping the rectangle. */
    public static final int COURTYARD_BORDER_CLEARANCE = 3;
    /** GREEN — half-extent (blocks) of the central communal area (green + the
     *  skirt lane's apron) on the block's SHORT axis. Public so the district
     *  sizer shapes the short axis to house ring + skirt lane + green. */
    public static final int GREEN_YARD_HALF = 7;
    /** GREEN — clearance (blocks) between the house ring and the block
     *  boundary. Smaller than COURTYARD's: a green has no perimeter fence. */
    public static final int GREEN_EDGE_CLEARANCE = 1;
    /** GREEN / shared — half-width of the skirt-lane band between the house
     *  fronts and the green, and the margin the green keeps off the lane. */
    private static final int GREEN_LANE_BAND = 2;
    /** CLUSTER — max per-axis jitter (blocks) off the relaxed grid. Public so
     *  the district sizer pads the block so jittered footprints stay inside. */
    public static final int CLUSTER_JITTER = 2;
    /** GRID_BLOCKS — internal street corridor width (VILLAGE_PATH tier).
     *  Public for the district sizer's street-overhead estimate. */
    public static final int GRID_STREET_WIDTH = 3;
    /** GRID_BLOCKS — alley corridor width (FOOTPATH tier). Public for the
     *  district sizer's alley-overhead estimate. */
    public static final int GRID_ALLEY_WIDTH = 2;
    /** GRID_BLOCKS — front setback (blocks) between a house's corridor-facing
     *  footprint edge and its leaf-cell boundary. The leaf boundary already
     *  sits at corridor/2 + 1 from the corridor centerline, so a setback of 1
     *  puts the facade ~1.5-2 blocks off the painted street edge — tight city
     *  fronting — while keeping footprints clear of the corridor (open
     *  corridor lines are truncated at footprints downstream, so an
     *  overlapping house would clip its own street). */
    private static final int GRID_FRONT_SETBACK = 1;

    /**
     * Arranges {@code houseCount} houses in {@code block} per {@code variant},
     * returning house placements + internal-path centerlines. {@code cellPitch}
     * = house footprint max-dim + gap (spacing); {@code houseDepth} = footprint
     * depth (insets rows/rings off the lane/edge); {@code edgeNode} = the block's
     * road-facing edge point, so the lane connects out to the main street;
     * {@code seed} drives CLUSTER's jitter + GRID_BLOCKS' cut positions
     * (deterministic per block). Reserved variants (TERRACE / HILLSIDE) fall
     * back to STREET_ROW — never a silent empty result.
     */
    public static Arrangement arrange(Polygon.AABB block, int houseCount,
                                      int cellPitch, int houseDepth,
                                      BlockPos edgeNode, ResidentialVariant variant,
                                      long seed) {
        if (houseCount <= 0) {
            return new Arrangement(List.of(), List.of(), List.of(), null, null);
        }
        return switch (variant) {
            case STREET_ROW -> streetRow(block, houseCount, cellPitch, houseDepth, edgeNode);
            case COURTYARD -> courtyard(block, houseCount, houseDepth, edgeNode);
            case GREEN -> green(block, houseCount, houseDepth, edgeNode);
            case CLUSTER -> cluster(block, houseCount, cellPitch, houseDepth, edgeNode, seed);
            case GRID_BLOCKS -> gridBlocks(block, houseCount, cellPitch, houseDepth, edgeNode, seed);
            // Reserved → fall back (not silent): arrange as a street row for now.
            case TERRACE, HILLSIDE ->
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
        return new Arrangement(houses, List.of(lane), List.of(), null, null);
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
            return new Arrangement(one, List.copyOf(lanes), List.of(), yard, null);
        }
        List<HousePlacement> out = ringHouses(count, edgeNode, yard,
                ix0, ix1, iz0, iz1);
        // Ring path just inside the house fronts (houses face inward), as a
        // closed loop — the courtyard's deliberate circulation.
        int rIn = houseDepth / 2 + 2;
        int rx0 = ix0 + rIn, rx1 = ix1 - rIn, rz0 = iz0 + rIn, rz1 = iz1 - rIn;
        if (rx1 > rx0 && rz1 > rz0) {
            lanes.add(closedLoop(rx0, rx1, rz0, rz1));
        }
        return new Arrangement(out, List.copyOf(lanes), List.of(), yard, null);
    }

    // =========================================================================
    // GREEN — Angerdorf: houses ring a central communal green; the lane skirts
    // the green's perimeter (VILLAGE_PATH); the green itself renders as open
    // lawn + scattered flora (+ optional well) via the planner's GreenDecor.
    // =========================================================================

    private static Arrangement green(Polygon.AABB block, int count,
                                     int houseDepth, BlockPos edgeNode) {
        int cx = (block.minX() + block.maxX()) / 2;
        int cz = (block.minZ() + block.maxZ()) / 2;
        BlockPos centre = new BlockPos(cx, 0, cz);
        // House ring inset — tighter than COURTYARD's (no perimeter fence).
        int inset = houseDepth / 2 + 1 + GREEN_EDGE_CLEARANCE;
        int ix0 = block.minX() + inset, ix1 = block.maxX() - inset;
        int iz0 = block.minZ() + inset, iz1 = block.maxZ() - inset;
        if (ix1 <= ix0 || iz1 <= iz0) {
            // Block too small to ring — single house, simple entry lane.
            List<HousePlacement> one = new ArrayList<>();
            one.add(new HousePlacement(new BlockPos(cx, 0, iz0), centre));
            return new Arrangement(one, List.of(List.of(edgeNode, centre)),
                    List.of(), null, null);
        }
        List<HousePlacement> out = ringHouses(count, edgeNode, centre,
                ix0, ix1, iz0, iz1);
        // Skirt lane: a closed loop between the house fronts and the green —
        // the Anger's street, so it renders one tier up (VILLAGE_PATH).
        int rIn = houseDepth / 2 + GREEN_LANE_BAND;
        int rx0 = ix0 + rIn, rx1 = ix1 - rIn, rz0 = iz0 + rIn, rz1 = iz1 - rIn;
        List<List<BlockPos>> streets = new ArrayList<>();
        Polygon.AABB greenArea = null;
        if (rx1 > rx0 && rz1 > rz0) {
            streets.add(closedLoop(rx0, rx1, rz0, rz1));
            // The communal green fills the loop's interior, kept off the lane.
            int gx0 = rx0 + GREEN_LANE_BAND, gx1 = rx1 - GREEN_LANE_BAND;
            int gz0 = rz0 + GREEN_LANE_BAND, gz1 = rz1 - GREEN_LANE_BAND;
            if (gx1 > gx0 && gz1 > gz0) {
                greenArea = new Polygon.AABB(gx0, gz0, gx1, gz1);
            }
        }
        // Entry: edge node to the nearest point on the skirt loop — it threads
        // the house-ring gap phased to face the entry bearing (ringHouses), and
        // STOPS at the loop: lanes skirt the green, they never cross it.
        int ex = clamp(edgeNode.getX(), Math.min(rx0, rx1), Math.max(rx0, rx1));
        int ez = clamp(edgeNode.getZ(), Math.min(rz0, rz1), Math.max(rz0, rz1));
        streets.add(List.of(edgeNode, new BlockPos(ex, 0, ez)));
        return new Arrangement(out, List.of(), List.copyOf(streets), null, greenArea);
    }

    /** Houses spaced around the perimeter of the inset rectangle, facing
     *  {@code faceTarget}, phased so a GAP (not a house) faces the entry
     *  bearing — the entry path threads between two houses. Shared by
     *  COURTYARD + GREEN. */
    private static List<HousePlacement> ringHouses(int count, BlockPos edgeNode,
                                                   BlockPos faceTarget,
                                                   int ix0, int ix1,
                                                   int iz0, int iz1) {
        long perim = 2L * (ix1 - ix0) + 2L * (iz1 - iz0);
        double fracEntry = perimeterFracNearest(edgeNode, ix0, ix1, iz0, iz1);
        List<HousePlacement> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double f = fracEntry + (i + 0.5) / count;
            f -= Math.floor(f);
            long t = (long) (perim * f);
            out.add(new HousePlacement(perimeterPoint(ix0, ix1, iz0, iz1, t),
                    faceTarget));
        }
        return out;
    }

    /** Closed rectangular loop centerline (clockwise, repeated first point). */
    private static List<BlockPos> closedLoop(int x0, int x1, int z0, int z1) {
        return List.of(
                new BlockPos(x0, 0, z0), new BlockPos(x1, 0, z0),
                new BlockPos(x1, 0, z1), new BlockPos(x0, 0, z1),
                new BlockPos(x0, 0, z0));
    }

    // =========================================================================
    // CLUSTER — Haufendorf: jittered positions off a relaxed grid, short lane
    // spurs from a central knot to each house (all FOOTPATH, organic).
    // =========================================================================

    private static Arrangement cluster(Polygon.AABB block, int count,
                                       int cellPitch, int houseDepth,
                                       BlockPos edgeNode, long seed) {
        Random rng = new Random(seed);
        int cx = (block.minX() + block.maxX()) / 2;
        int cz = (block.minZ() + block.maxZ()) / 2;
        // Relaxed grid of candidate cells, centred on the block.
        int n = (int) Math.ceil(Math.sqrt(count));
        int pitch = cellPitch + 2;
        List<int[]> cells = new ArrayList<>(n * n);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int ax = Math.round((i - (n - 1) / 2.0f) * pitch);
                int az = Math.round((j - (n - 1) / 2.0f) * pitch);
                cells.add(new int[]{cx + ax, cz + az});
            }
        }
        Collections.shuffle(cells, rng);                  // irregular fill order
        // Lane knot: the meeting point of the spurs, jittered off-centre.
        BlockPos knot = new BlockPos(cx + jitter(rng, 3), 0, cz + jitter(rng, 3));
        // Keep jittered footprints inside the block.
        int margin = cellPitch / 2 + 1;
        int lx0 = block.minX() + margin, lx1 = block.maxX() - margin;
        int lz0 = block.minZ() + margin, lz1 = block.maxZ() - margin;
        boolean knotClear = cells.size() > count;          // spare cell to skip
        List<HousePlacement> houses = new ArrayList<>(count);
        List<List<BlockPos>> lanes = new ArrayList<>(count + 1);
        lanes.add(List.of(edgeNode, knot));                // entry to the knot
        for (int[] c : cells) {
            if (houses.size() >= count) break;
            int hx = lx1 < lx0 ? c[0] : clamp(c[0] + jitter(rng, CLUSTER_JITTER), lx0, lx1);
            int hz = lz1 < lz0 ? c[1] : clamp(c[1] + jitter(rng, CLUSTER_JITTER), lz0, lz1);
            // Leave the knot's cell open when capacity allows (the knot is the
            // little plaza-knot the spurs radiate from).
            if (knotClear
                    && Math.abs(hx - knot.getX()) < cellPitch / 2
                    && Math.abs(hz - knot.getZ()) < cellPitch / 2) {
                knotClear = false;                          // skip at most one
                continue;
            }
            BlockPos house = new BlockPos(hx, 0, hz);
            houses.add(new HousePlacement(house, knot));
            lanes.add(List.of(knot, house));               // spur (clipped at the
        }                                                   // footprint later)
        return new Arrangement(houses, List.copyOf(lanes), List.of(), null, null);
    }

    /** Uniform jitter in [-j, +j]. */
    private static int jitter(Random rng, int j) {
        return rng.nextInt(2 * j + 1) - j;
    }

    // =========================================================================
    // GRID_BLOCKS — BSP streets + alleys: recursive cuts carve corridors
    // (depth-0 = street @ VILLAGE_PATH, deeper = alley @ FOOTPATH); houses
    // fill the leaf cells facing their serving corridor.
    // =========================================================================

    private static Arrangement gridBlocks(Polygon.AABB block, int count,
                                          int cellPitch, int houseDepth,
                                          BlockPos edgeNode, long seed) {
        Random rng = new Random(seed);
        int x0 = block.minX() + 1, x1 = block.maxX() - 1;
        int z0 = block.minZ() + 1, z1 = block.maxZ() - 1;
        List<int[]> leaves = new ArrayList<>();
        List<List<BlockPos>> streets = new ArrayList<>();
        List<List<BlockPos>> alleys = new ArrayList<>();
        subdivide(x0, z0, x1, z1, 0, cellPitch, rng, leaves, streets, alleys);
        if (streets.isEmpty() && alleys.isEmpty()) {
            // Too small for even one cut — degrade to the street row rather
            // than an unreadable single-cell "grid".
            return streetRow(block, count, cellPitch, houseDepth, edgeNode);
        }
        // Entry stitches the edge node to the nearest internal street end
        // (corridors span the whole block, so their ends sit at the boundary).
        BlockPos entry = nearestCorridorPoint(edgeNode, streets);
        if (entry != null) streets.add(List.of(edgeNode, entry));
        // Houses fill leaf cells (seeded order for variety), each facing the
        // nearest corridor point — fronts on the internal street/alley.
        Collections.shuffle(leaves, rng);
        List<HousePlacement> houses = new ArrayList<>(count);
        for (int[] leaf : leaves) {
            if (houses.size() >= count) break;
            BlockPos centre = new BlockPos((leaf[0] + leaf[2]) / 2, 0,
                    (leaf[1] + leaf[3]) / 2);
            BlockPos face = nearestCorridorPoint(centre, streets);
            BlockPos faceAlley = nearestCorridorPoint(centre, alleys);
            if (face == null
                    || (faceAlley != null && horizDistSqr(centre, faceAlley)
                            < horizDistSqr(centre, face))) {
                face = faceAlley;
            }
            // A1 fix-up — city-grid density: pull the house from the leaf
            // CENTRE up to its corridor-facing leaf edge (front edge
            // GRID_FRONT_SETBACK inside the boundary), so houses front their
            // street/alley tightly instead of floating mid-plot. Only the
            // facing axis shifts; the cross axis stays centred (leaves are
            // ~cellPitch wide, leaving ~1 block to each neighbour). The
            // faceTarget is rebuilt AXIS-ALIGNED straight out the front edge:
            // keeping the raw corridor projection could flip chooseFacing's
            // dominant axis once the front-axis delta shrinks below the
            // projection's along-corridor offset, turning the house sideways.
            if (face != null) {
                int dx = face.getX() - centre.getX();
                int dz = face.getZ() - centre.getZ();
                if (Math.abs(dx) >= Math.abs(dz)) {
                    int cx = dx > 0
                            ? leaf[2] - houseDepth / 2 - GRID_FRONT_SETBACK
                            : leaf[0] + houseDepth / 2 + GRID_FRONT_SETBACK;
                    centre = new BlockPos(cx, 0, centre.getZ());
                    face = new BlockPos(dx > 0 ? leaf[2] + 2 : leaf[0] - 2,
                            0, centre.getZ());
                } else {
                    int cz = dz > 0
                            ? leaf[3] - houseDepth / 2 - GRID_FRONT_SETBACK
                            : leaf[1] + houseDepth / 2 + GRID_FRONT_SETBACK;
                    centre = new BlockPos(centre.getX(), 0, cz);
                    face = new BlockPos(centre.getX(), 0,
                            dz > 0 ? leaf[3] + 2 : leaf[1] - 2);
                }
            }
            houses.add(new HousePlacement(centre, face != null ? face : edgeNode));
        }
        return new Arrangement(houses, List.copyOf(alleys), List.copyOf(streets),
                null, null);
    }

    /** Recursive BSP cut across the longer axis: emits the corridor centerline
     *  (street at depth 0, alley deeper) and recurses into both halves, until
     *  a cell can no longer host two {@code cellPitch} cells + the corridor. */
    private static void subdivide(int x0, int z0, int x1, int z1, int depth,
                                  int cellPitch, Random rng, List<int[]> leaves,
                                  List<List<BlockPos>> streets,
                                  List<List<BlockPos>> alleys) {
        int w = x1 - x0, h = z1 - z0;
        boolean cutX = w >= h;                 // cut across the longer axis
        int corridor = depth == 0 ? GRID_STREET_WIDTH : GRID_ALLEY_WIDTH;
        int len = cutX ? w : h;
        if (len < 2 * cellPitch + corridor) {
            leaves.add(new int[]{x0, z0, x1, z1});
            return;
        }
        int lo = (cutX ? x0 : z0) + cellPitch + corridor / 2;
        int hi = (cutX ? x1 : z1) - cellPitch - corridor / 2;
        // A1 fix-up — midpoint-biased cut (small jitter) instead of uniform
        // random: uniform cuts spread leaf sizes across [pitch, 2*pitch +
        // corridor), and the wide leaves read as suburban plots (house adrift
        // in the middle). Near-even cuts converge every leaf to ~cellPitch so
        // houses dominate their cells — a city grid, not a plot grid.
        int mid = (lo + hi) / 2;
        int c = mid + (hi > lo ? jitter(rng, Math.min(2, (hi - lo) / 2)) : 0);
        List<BlockPos> line = cutX
                ? List.of(new BlockPos(c, 0, z0), new BlockPos(c, 0, z1))
                : List.of(new BlockPos(x0, 0, c), new BlockPos(x1, 0, c));
        (depth == 0 ? streets : alleys).add(line);
        int half = corridor / 2 + 1;           // keep footprints off the corridor
        if (cutX) {
            subdivide(x0, z0, c - half, z1, depth + 1, cellPitch, rng, leaves, streets, alleys);
            subdivide(c + half, z0, x1, z1, depth + 1, cellPitch, rng, leaves, streets, alleys);
        } else {
            subdivide(x0, z0, x1, c - half, depth + 1, cellPitch, rng, leaves, streets, alleys);
            subdivide(x0, c + half, x1, z1, depth + 1, cellPitch, rng, leaves, streets, alleys);
        }
    }

    /** Nearest point to {@code p} on any corridor centerline (segment-projected),
     *  or null when {@code corridors} is empty. */
    private static BlockPos nearestCorridorPoint(BlockPos p,
                                                 List<List<BlockPos>> corridors) {
        BlockPos best = null;
        long bestD = Long.MAX_VALUE;
        for (List<BlockPos> line : corridors) {
            for (int i = 1; i < line.size(); i++) {
                BlockPos q = projectToSegment(p, line.get(i - 1), line.get(i));
                long d = horizDistSqr(p, q);
                if (d < bestD) { bestD = d; best = q; }
            }
        }
        return best;
    }

    /** Closest point on segment ab to p (XZ, y=0). */
    private static BlockPos projectToSegment(BlockPos p, BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        double lenSq = dx * dx + dz * dz;
        if (lenSq < 1e-9) return new BlockPos(a.getX(), 0, a.getZ());
        double t = ((p.getX() - a.getX()) * dx + (p.getZ() - a.getZ()) * dz) / lenSq;
        t = Math.max(0, Math.min(1, t));
        return new BlockPos((int) Math.round(a.getX() + t * dx), 0,
                (int) Math.round(a.getZ() + t * dz));
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
