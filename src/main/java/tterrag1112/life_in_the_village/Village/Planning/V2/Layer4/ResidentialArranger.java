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
 * (BSP streets + alleys) are live. A1 stage 2 — TERRACE (attached row-house
 * segments from the authored {@code row_house} piece set) is live when the
 * pieces are authored; HILLSIDE remains reserved (forced → street-row
 * fallback, never a silent no-op).
 */
public final class ResidentialArranger {

    private ResidentialArranger() {}

    /** A planned house: its centre (y=0, planner snaps) + the point it faces.
     *  {@code forcedVariantId} is non-null only for composed arrangements
     *  (TERRACE segment pieces, id form {@code row_house:left}); null means
     *  the planner rolls the variant normally. */
    public record HousePlacement(BlockPos centre, BlockPos faceTarget,
                                 String forcedVariantId) {
        public HousePlacement(BlockPos centre, BlockPos faceTarget) {
            this(centre, faceTarget, null);
        }
    }

    /** A1 stage 2 — one authored terrace piece: its forced variant id
     *  ({@code row_house:left}) + its unrotated NBT width (the along-row
     *  span). Depth lives on {@link TerracePieces} (uniform per set). */
    public record TerracePiece(String variantId, int width) {}

    /** A1 stage 2 — the authored terrace piece set: LEFT/RIGHT end caps +
     *  the interior pool ("any piece that is not an end cap" — future
     *  interior pieces add variation with no code change). {@code depth} is
     *  the segments' front-to-back span (max across pieces). Built by the
     *  planner from the piece index ({@code BuildingAvailability
     *  .availablePieces}) + the footprint provider; null reaches
     *  {@link #arrange} when the row_house pieces aren't authored. */
    public record TerracePieces(TerracePiece left, TerracePiece right,
                                List<TerracePiece> interiors, int depth) {}

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
    /** 4c-c r4 — span (blocks) consumed by the internal alley between two
     *  adjacent shelf ROWS inside a multi-demand shelf leaf: exactly the
     *  alley corridor width, WITHOUT the corridor/2+1 per-side margin the
     *  BSP-cut corridors add. Tighter on purpose: every shelf cell already
     *  keeps footprints >= 1 block inside its boundary (the facing axis via
     *  {@code pullToCorridor}'s setback, the cross axis by centring), so
     *  the FOOTPATH paint (coreHalf 1 -> 3 wide) fills the gap plus those
     *  margin strips — a tight service-lane look — and the span enters the
     *  NFDH bound, where each extra block costs the planner growth steps
     *  (CITYTEST5 cells single-block at this span; +2 more pushes the root
     *  street cut infeasible in the 94x64 first candidate). */
    private static final int SHELF_ALLEY_SPAN = GRID_ALLEY_WIDTH;
    /** GRID_BLOCKS — front setback (blocks) between a house's corridor-facing
     *  footprint edge and its leaf-cell boundary. The leaf boundary already
     *  sits at corridor/2 + 1 from the corridor centerline, so a setback of 1
     *  puts the facade ~1.5-2 blocks off the painted street edge — tight city
     *  fronting — while keeping footprints clear of the corridor (open
     *  corridor lines are truncated at footprints downstream, so an
     *  overlapping house would clip its own street). */
    private static final int GRID_FRONT_SETBACK = 1;
    /** TERRACE — clearance (blocks) between a row's end-cap outer wall and
     *  the block boundary, so the caps read as row ENDS (a sliver of open
     *  ground past the finished gable). Public so the district sizer pads
     *  the block's long axis by the same amount. */
    public static final int TERRACE_END_SETBACK = 2;
    /** TERRACE — segment count at which the terrace splits into TWO facing
     *  rows. Below it everything goes in one row, so counts 3–5 build a
     *  proper cap + interior(s) + cap run instead of two caps-only pairs
     *  (the canonical row is LEFT cap + >=1 interior + RIGHT cap). Public
     *  so the district sizer mirrors the same split. */
    public static final int TERRACE_TWO_ROW_MIN = 6;

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
        return arrange(block, houseCount, cellPitch, houseDepth, edgeNode,
                variant, seed, null);
    }

    /**
     * A1 stage 2 overload — adds the terrace piece set. {@code terracePieces}
     * is only consulted by the TERRACE arm; null (pieces not authored, or a
     * call site that never arranges terraces) makes TERRACE fall back to a
     * street row exactly like the other reserved variants.
     */
    public static Arrangement arrange(Polygon.AABB block, int houseCount,
                                      int cellPitch, int houseDepth,
                                      BlockPos edgeNode, ResidentialVariant variant,
                                      long seed, TerracePieces terracePieces) {
        if (houseCount <= 0) {
            return new Arrangement(List.of(), List.of(), List.of(), null, null);
        }
        return switch (variant) {
            case STREET_ROW -> streetRow(block, houseCount, cellPitch, houseDepth, edgeNode);
            case COURTYARD -> courtyard(block, houseCount, houseDepth, edgeNode);
            case GREEN -> green(block, houseCount, houseDepth, edgeNode);
            case CLUSTER -> cluster(block, houseCount, cellPitch, houseDepth, edgeNode, seed);
            case GRID_BLOCKS -> gridBlocks(block, houseCount, cellPitch, houseDepth, edgeNode, seed);
            // A1 stage 2 — attached row-house segments. A terrace needs the
            // authored piece set and at least two segments (LEFT + RIGHT cap);
            // anything less falls back to the street row (never a silent no-op).
            case TERRACE -> terracePieces != null && houseCount >= 2
                    ? terrace(block, houseCount, terracePieces, edgeNode, seed)
                    : streetRow(block, houseCount, cellPitch, houseDepth, edgeNode);
            // Reserved → fall back (not silent): arrange as a street row for now.
            case HILLSIDE ->
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
    // TERRACE — attached row-house segments: straight flush runs (LEFT cap +
    // interior pieces + RIGHT cap, shared walls by construction) along the
    // block's long axis, one or two rows fronting a central lane.
    // =========================================================================

    private static Arrangement terrace(Polygon.AABB block, int count,
                                       TerracePieces pieces, BlockPos edgeNode,
                                       long seed) {
        Random rng = new Random(seed);
        int cx = (block.minX() + block.maxX()) / 2;
        int cz = (block.minZ() + block.maxZ()) / 2;
        int wX = block.maxX() - block.minX();
        int wZ = block.maxZ() - block.minZ();
        boolean longX = wX >= wZ;
        int halfLong = (longX ? wX : wZ) / 2;
        int alongHalfLimit = Math.max(0, halfLong - TERRACE_END_SETBACK);

        int rowOffset = pieces.depth() / 2 + LANE_HALF;
        // Two parallel rows only when both can host a CANONICAL row (cap +
        // interior + cap, >= 3 segments each); below that a single run keeps
        // interiors in play (4–5 → one row of 4–5, never two caps-only
        // pairs). count >= 2 is guaranteed by arrange()'s gate.
        int front = count >= TERRACE_TWO_ROW_MIN ? (count + 1) / 2 : count;
        int back = count - front;

        List<HousePlacement> houses = new ArrayList<>(count);
        addTerraceRow(houses, longX, cx, cz, front, +rowOffset, pieces,
                alongHalfLimit, rng);
        if (back >= 2) {
            addTerraceRow(houses, longX, cx, cz, back, -rowOffset, pieces,
                    alongHalfLimit, rng);
        }

        // Central lane — the terrace's STREET (VILLAGE_PATH, like GREEN's
        // skirt loop): the across=0 gap between the rows, run boundary to
        // boundary so it flows past the cap setbacks; stitched to the edge
        // node only when that sits off a short end (same rule as STREET_ROW —
        // a side-on connector would cross a row).
        BlockPos endA = fromAxes(longX, cx, cz, -halfLong, 0);
        BlockPos endB = fromAxes(longX, cx, cz, +halfLong, 0);
        boolean aNearer = horizDistSqr(edgeNode, endA) <= horizDistSqr(edgeNode, endB);
        BlockPos near = aNearer ? endA : endB;
        BlockPos far = aNearer ? endB : endA;
        int edgeAlong = longX ? (edgeNode.getX() - cx) : (edgeNode.getZ() - cz);
        boolean aligned = Math.abs(edgeAlong) >= alongHalfLimit;
        List<BlockPos> lane = aligned
                ? List.of(edgeNode, near, far)
                : List.of(near, far);
        return new Arrangement(houses, List.of(), List.of(lane), null, null);
    }

    /** Builds one flush terrace row of {@code m >= 2} segments at the given
     *  across offset: LEFT cap + seeded interior picks + RIGHT cap, placed
     *  edge-to-edge (centre pitch = wA/2 + wB/2 + 1 — flush under the
     *  planner's inclusive-AABB convention, so shared walls actually touch).
     *  Interiors drop (never the caps) until the row fits the along limit;
     *  a row whose caps alone don't fit adds nothing (the planner's
     *  placed-count bookkeeping re-homes the difference). Cap WORLD order
     *  derives from the row's facing so "left"/"right" read correctly from
     *  the lane side: viewer-left = (-fz, fx) for house front dir f. */
    private static void addTerraceRow(List<HousePlacement> out, boolean longX,
                                      int cx, int cz, int m, int across,
                                      TerracePieces pieces, int alongHalfLimit,
                                      Random rng) {
        // Viewer-left → viewer-right sequence: LEFT cap, m-2 interiors, RIGHT cap.
        List<TerracePiece> seq = new ArrayList<>(m);
        seq.add(pieces.left());
        for (int i = 0; i < m - 2; i++) {
            seq.add(pieces.interiors()
                    .get(rng.nextInt(pieces.interiors().size())));
        }
        seq.add(pieces.right());
        // Drop interiors until the row fits the usable along span.
        int limit = 2 * alongHalfLimit + 1;
        while (seq.size() > 2 && rowSpan(seq) > limit) {
            seq.remove(1);
        }
        if (rowSpan(seq) > limit) return;          // caps alone don't fit
        // World order: flip when the viewer-left end lands at +along. For a
        // row facing the lane, front dir f = -sign(across) on the across
        // axis; viewer-left (-fz, fx) projects onto the along axis as
        // +sign(across) when longX, -sign(across) otherwise.
        boolean leftAtPlusEnd = longX ? across > 0 : across < 0;
        if (leftAtPlusEnd) Collections.reverse(seq);
        int t = -rowSpan(seq) / 2;                  // running along edge
        for (TerracePiece p : seq) {
            int span = pieceSpan(p);
            int along = t + span / 2;
            t += span;
            BlockPos centre = fromAxes(longX, cx, cz, along, across);
            BlockPos lane = fromAxes(longX, cx, cz, along, 0);
            out.add(new HousePlacement(centre, lane, p.variantId()));
        }
    }

    /** Along-axis span (cells) one piece reserves: the planner's inclusive
     *  footprint AABB spans 2*(w/2)+1 cells (= w for the odd-width NBTs). */
    private static int pieceSpan(TerracePiece p) {
        return 2 * (p.width() / 2) + 1;
    }

    /** Total along-axis span (cells) of a flush piece sequence. */
    private static int rowSpan(List<TerracePiece> seq) {
        int s = 0;
        for (TerracePiece p : seq) s += pieceSpan(p);
        return s;
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
        bsp(x0, z0, x1, z1, 0, null, gridGuide(cellPitch, leaves), rng,
                streets, alleys);
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
            // CENTRE up to its corridor-facing leaf edge (shared with the
            // 4c-c quarter — see pullToCorridor).
            houses.add(face != null
                    ? pullToCorridor(leaf, centre, face, houseDepth)
                    : new HousePlacement(centre, edgeNode));
        }
        return new Arrangement(houses, List.copyOf(alleys), List.copyOf(streets),
                null, null);
    }

    /** One planned BSP cut: the cut coordinate (across the rect's longer
     *  axis) + the per-side payloads carried into the two halves. */
    private record BspCut<T>(int cut, T low, T high) {}

    /** 4c-c — policy seam for the shared rect-BSP core ({@link #bsp}). The
     *  guide decides where to cut (or {@code null} to finish the rect as a
     *  leaf) and receives finished leaves; the core owns everything
     *  GRID_BLOCKS established — cut across the longer axis, corridor width
     *  by depth (street at depth 0, alleys deeper), centerline emission, the
     *  corridor/2+1 footprint margin, and the recursion. */
    private interface BspGuide<T> {
        /** The cut plan for this rect, or null to finish it as a leaf. */
        BspCut<T> plan(int x0, int z0, int x1, int z1, boolean cutX,
                       int corridor, int depth, T node, Random rng);

        /** A finished leaf cell. */
        void leaf(int x0, int z0, int x1, int z1, T node, int depth);
    }

    /** Recursive rect-BSP core shared by GRID_BLOCKS ({@link #gridGuide}) and
     *  the 4c-c workshop quarter ({@link #arrangeQuarter}): asks the guide
     *  for a cut, emits the corridor centerline (street at depth 0, alley
     *  deeper) and recurses into both halves with the guide's per-side
     *  payloads. */
    private static <T> void bsp(int x0, int z0, int x1, int z1, int depth,
                                T node, BspGuide<T> guide, Random rng,
                                List<List<BlockPos>> streets,
                                List<List<BlockPos>> alleys) {
        boolean cutX = (x1 - x0) >= (z1 - z0); // cut across the longer axis
        int corridor = depth == 0 ? GRID_STREET_WIDTH : GRID_ALLEY_WIDTH;
        BspCut<T> cut = guide.plan(x0, z0, x1, z1, cutX, corridor, depth,
                node, rng);
        if (cut == null) {
            guide.leaf(x0, z0, x1, z1, node, depth);
            return;
        }
        int c = cut.cut();
        List<BlockPos> line = cutX
                ? List.of(new BlockPos(c, 0, z0), new BlockPos(c, 0, z1))
                : List.of(new BlockPos(x0, 0, c), new BlockPos(x1, 0, c));
        (depth == 0 ? streets : alleys).add(line);
        int half = corridor / 2 + 1;           // keep footprints off the corridor
        if (cutX) {
            bsp(x0, z0, c - half, z1, depth + 1, cut.low(), guide, rng, streets, alleys);
            bsp(c + half, z0, x1, z1, depth + 1, cut.high(), guide, rng, streets, alleys);
        } else {
            bsp(x0, z0, x1, c - half, depth + 1, cut.low(), guide, rng, streets, alleys);
            bsp(x0, c + half, x1, z1, depth + 1, cut.high(), guide, rng, streets, alleys);
        }
    }

    /** GRID_BLOCKS' guide — the original pitch-driven policy as a thin guide
     *  over the shared core (4c-c seam generalization): leaf when a rect can
     *  no longer host two {@code cellPitch} cells + the corridor; cut
     *  midpoint-biased with small jitter. Behaviour (including the per-cut
     *  RNG draw order) is identical to the pre-4c-c {@code subdivide}. */
    private static BspGuide<Void> gridGuide(int cellPitch, List<int[]> leaves) {
        return new BspGuide<>() {
            @Override
            public BspCut<Void> plan(int x0, int z0, int x1, int z1,
                                     boolean cutX, int corridor, int depth,
                                     Void node, Random rng) {
                int len = cutX ? x1 - x0 : z1 - z0;
                if (len < 2 * cellPitch + corridor) return null;
                int lo = (cutX ? x0 : z0) + cellPitch + corridor / 2;
                int hi = (cutX ? x1 : z1) - cellPitch - corridor / 2;
                // A1 fix-up — midpoint-biased cut (small jitter) instead of
                // uniform random: uniform cuts spread leaf sizes across
                // [pitch, 2*pitch + corridor), and the wide leaves read as
                // suburban plots (house adrift in the middle). Near-even cuts
                // converge every leaf to ~cellPitch so houses dominate their
                // cells — a city grid, not a plot grid.
                int mid = (lo + hi) / 2;
                int c = mid + (hi > lo ? jitter(rng, Math.min(2, (hi - lo) / 2)) : 0);
                return new BspCut<>(c, null, null);
            }

            @Override
            public void leaf(int x0, int z0, int x1, int z1, Void node,
                             int depth) {
                leaves.add(new int[]{x0, z0, x1, z1});
            }
        };
    }

    /** A1 fix-up (shared with the 4c-c quarter) — pulls a placement from its
     *  leaf CENTRE up to the corridor-facing leaf edge (front edge
     *  GRID_FRONT_SETBACK inside the boundary), so buildings front their
     *  street/alley tightly instead of floating mid-plot. Only the facing
     *  axis shifts; the cross axis stays centred. The faceTarget is rebuilt
     *  AXIS-ALIGNED straight out the front edge: keeping the raw corridor
     *  projection could flip chooseFacing's dominant axis once the
     *  front-axis delta shrinks below the projection's along-corridor
     *  offset, turning the building sideways. {@code footDepth} is the
     *  front-to-back span kept inside the leaf (HOUSE depth for GRID_BLOCKS;
     *  the member's footprint max-dim for the quarter — conservative under
     *  either facing rotation). */
    private static HousePlacement pullToCorridor(int[] leaf, BlockPos centre,
                                                 BlockPos face, int footDepth) {
        int dx = face.getX() - centre.getX();
        int dz = face.getZ() - centre.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            int cx = dx > 0
                    ? leaf[2] - footDepth / 2 - GRID_FRONT_SETBACK
                    : leaf[0] + footDepth / 2 + GRID_FRONT_SETBACK;
            centre = new BlockPos(cx, 0, centre.getZ());
            face = new BlockPos(dx > 0 ? leaf[2] + 2 : leaf[0] - 2,
                    0, centre.getZ());
        } else {
            int cz = dz > 0
                    ? leaf[3] - footDepth / 2 - GRID_FRONT_SETBACK
                    : leaf[1] + footDepth / 2 + GRID_FRONT_SETBACK;
            centre = new BlockPos(centre.getX(), 0, cz);
            face = new BlockPos(centre.getX(), 0,
                    dz > 0 ? leaf[3] + 2 : leaf[1] - 2);
        }
        return new HousePlacement(centre, face);
    }

    // =========================================================================
    // QUARTER (4c-c) — the workshop quarter: a demand-guided BSP over the
    // shared core. Cells are sized to each member's footprint (crafts vary
    // widely), customer-facing crafts front the central street (the depth-0
    // cut), storage takes the back/alley cells, and one cell near the street
    // midpoint is reserved OPEN as the shared work-yard.
    // =========================================================================

    /** 4c-c — one workshop-quarter member: {@code cellSide} is the demanded
     *  BSP cell side (footprint max-dim + the planner's gap),
     *  {@code footprintDim} the raw footprint max-dim (for the corridor
     *  pull — conservative under either facing rotation), {@code storage}
     *  marks back/alley preference (stockpile/warehouse). List order is the
     *  planner's craft order; {@link QuarterArrangement#buildings} is
     *  index-aligned to it (1:1 — every member gets a cell, or the
     *  arrangement is null and the planner falls back). */
    public record QuarterMember(int cellSide, int footprintDim, boolean storage) {}

    /** 4c-c — the arranged workshop quarter: per-member placements
     *  (index-aligned with the input members), the central street + the
     *  edge-node entry stitch (the planner renders these VILLAGE_PATH), the
     *  alleys (FOOTPATH — the BSP-cut corridors AND r4's shelf-leaf
     *  internal lanes), and the shared work-yard cell left OPEN (the
     *  planner stamps the well at {@code yardCentre}). 4c-c fix-up —
     *  {@code yardCentre}/{@code yard} are NULL when arranged with
     *  {@code yardSide <= 0} (the split quarter's yard-less second block). */
    public record QuarterArrangement(List<HousePlacement> buildings,
                                     List<List<BlockPos>> streets,
                                     List<List<BlockPos>> alleys,
                                     BlockPos yardCentre,
                                     Polygon.AABB yard) {}

    /**
     * Arranges the workshop QUARTER inside {@code block}: a demand-guided
     * BSP cells the block to the member footprints (+ one {@code yardSide}
     * work-yard cell), then assigns cells — the yard takes the fitting cell
     * nearest the central street's midpoint (the quarter's heart),
     * customer-facing crafts the cells nearest the central street, storage
     * the cells farthest from it. Returns {@code null} when the block cannot
     * cell every member + the yard (the planner falls back to the craft
     * row); a non-null result covers every member by construction.
     *
     * <p>4c-c fix-up — {@code yardSide <= 0} arranges WITHOUT a work-yard
     * cell ({@code yardCentre}/{@code yard} null in the result): the
     * two-block split quarter gives the yard to its first block only.
     */
    public static QuarterArrangement arrangeQuarter(Polygon.AABB block,
                                                    List<QuarterMember> members,
                                                    int yardSide,
                                                    BlockPos edgeNode,
                                                    long seed) {
        if (members.isEmpty()) return null;
        boolean hasYard = yardSide > 0;
        Random rng = new Random(seed);
        int x0 = block.minX() + 1, x1 = block.maxX() - 1;
        int z0 = block.minZ() + 1, z1 = block.maxZ() - 1;
        // Demand multiset (descending): every member's cell + the work-yard.
        List<Integer> demands = new ArrayList<>(members.size() + 1);
        for (QuarterMember m : members) demands.add(m.cellSide());
        if (hasYard) demands.add(yardSide);
        demands.sort(Collections.reverseOrder());
        List<int[]> leaves = new ArrayList<>();
        List<List<BlockPos>> streets = new ArrayList<>();
        List<List<BlockPos>> alleys = new ArrayList<>();
        boolean[] failed = {false};
        bsp(x0, z0, x1, z1, 0, demands, quarterGuide(leaves, alleys, failed),
                rng, streets, alleys);
        if (failed[0] || streets.isEmpty()
                || leaves.size() != members.size() + (hasYard ? 1 : 0)) {
            return null;
        }
        // The central street is the root (depth-0) cut — the only entry in
        // streets at this point (every deeper cut is an alley).
        List<BlockPos> central = streets.get(0);
        BlockPos cA = central.get(0), cB = central.get(central.size() - 1);
        BlockPos streetMid = new BlockPos((cA.getX() + cB.getX()) / 2, 0,
                (cA.getZ() + cB.getZ()) / 2);

        // Assignment — greedy by descending demand, fit-constrained (the cell
        // must hold the demand side on BOTH axes), preference-scored: yard →
        // nearest the street midpoint; fronts → nearest the central street;
        // storage → farthest from it (back/alley cells).
        int n = members.size();                       // item n = the yard
        Integer[] order = new Integer[n + (hasYard ? 1 : 0)];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (p, q) -> Integer.compare(
                q == n ? yardSide : members.get(q).cellSide(),
                p == n ? yardSide : members.get(p).cellSide()));
        boolean[] taken = new boolean[leaves.size()];
        int[] assigned = new int[n + 1];
        for (int item : order) {
            int want = item == n ? yardSide : members.get(item).cellSide();
            boolean storage = item != n && members.get(item).storage();
            int best = -1;
            long bestScore = 0;
            for (int li = 0; li < leaves.size(); li++) {
                if (taken[li]) continue;
                int[] lf = leaves.get(li);
                if (lf[2] - lf[0] < want || lf[3] - lf[1] < want) continue;
                BlockPos lc = new BlockPos((lf[0] + lf[2]) / 2, 0,
                        (lf[1] + lf[3]) / 2);
                long d = item == n
                        ? horizDistSqr(lc, streetMid)
                        : horizDistSqr(lc, projectToSegment(lc, cA, cB));
                long score = storage ? -d : d;
                if (best < 0 || score < bestScore) {
                    best = li;
                    bestScore = score;
                }
            }
            if (best < 0) return null;                // an item can't fit
            taken[best] = true;
            assigned[item] = best;
        }

        // Placements — each member pulled from its cell centre to the
        // corridor-facing edge: fronts face the CENTRAL STREET, storage its
        // serving alley (the street when no alley was cut).
        HousePlacement[] placements = new HousePlacement[n];
        for (int i = 0; i < n; i++) {
            int[] lf = leaves.get(assigned[i]);
            BlockPos centre = new BlockPos((lf[0] + lf[2]) / 2, 0,
                    (lf[1] + lf[3]) / 2);
            QuarterMember m = members.get(i);
            BlockPos face = m.storage() && !alleys.isEmpty()
                    ? nearestCorridorPoint(centre, alleys)
                    : projectToSegment(centre, cA, cB);
            placements[i] = pullToCorridor(lf, centre, face, m.footprintDim());
        }
        BlockPos yardCentre = null;
        Polygon.AABB yard = null;
        if (hasYard) {
            int[] ylf = leaves.get(assigned[n]);
            yardCentre = new BlockPos((ylf[0] + ylf[2]) / 2, 0,
                    (ylf[1] + ylf[3]) / 2);
            yard = new Polygon.AABB(ylf[0], ylf[1], ylf[2], ylf[3]);
        }
        // Entry — stitch the block's road-facing edge node to the central
        // street (same stitch GRID_BLOCKS uses for its internal streets).
        streets.add(List.of(edgeNode, projectToSegment(edgeNode, cA, cB)));
        return new QuarterArrangement(List.of(placements),
                List.copyOf(streets), List.copyOf(alleys), yardCentre, yard);
    }

    /** 4c-c r3 — the quarter's BSP guide: demand-guided cuts over the shared
     *  core. Each node carries the (descending) demand sides of the cells it
     *  must still produce; a cut splits them into two area-balanced halves
     *  and lands proportionally to their areas, clamped so each side can
     *  SHELF-PACK its whole partition ({@link #minFeasibleLen}) — not merely
     *  hold its biggest demand, the r2 bug: a subtree carrying several
     *  demands starved regardless of total area, so growth never converged
     *  (CITYTEST5: 66x96 → 66x146 all failed on ~3,000 blocks of demand).
     *  Invariant: every node the recursion enters shelf-packs its demand set
     *  (the root is checked explicitly; both sides of every accepted cut are
     *  checked before cutting). A node with no feasible cut finishes as a
     *  multi-demand shelf LEAF ({@link #emitShelfLeaves} — guaranteed by the
     *  invariant), except at depth 0 where the central street is mandatory
     *  (arrangeQuarter requires it): there it sets {@code failed[0]} so the
     *  planner's dry-run+grow loop grows the block. Deterministic (no
     *  jitter): the cut positions are fully demand-derived. */
    private static BspGuide<List<Integer>> quarterGuide(List<int[]> leaves,
                                                        List<List<BlockPos>> alleys,
                                                        boolean[] failed) {
        return new BspGuide<>() {
            @Override
            public BspCut<List<Integer>> plan(int x0, int z0, int x1, int z1,
                                              boolean cutX, int corridor,
                                              int depth, List<Integer> node,
                                              Random rng) {
                int len = cutX ? x1 - x0 : z1 - z0;
                int cross = cutX ? z1 - z0 : x1 - x0;
                // r3 — subtree-aware feasibility: the WHOLE demand set must
                // shelf-pack this rect (subsumes the old per-demand dim
                // check). Root failure drives the grow loop; deeper nodes
                // hold by the cut invariant, so this arm is defensive there.
                if (!shelfFits(len, cross, node)) {
                    failed[0] = true;
                    return null;
                }
                if (node.size() <= 1) return null;
                // Greedy area-balanced split (node is descending, so each
                // side's first element is its biggest demand).
                List<Integer> a = new ArrayList<>(), b = new ArrayList<>();
                long areaA = 0, areaB = 0;
                for (int d : node) {
                    if (areaA <= areaB) {
                        a.add(d);
                        areaA += (long) d * d;
                    } else {
                        b.add(d);
                        areaB += (long) d * d;
                    }
                }
                int half = corridor / 2 + 1;
                int base = cutX ? x0 : z0;
                // r3 — subtree-aware clamp: each side's along-length must
                // shelf-pack its ENTIRE partition at the fixed cross dim.
                int minA = minFeasibleLen(a, cross, len);
                int minB = minFeasibleLen(b, cross, len);
                int lo = minA < 0 ? Integer.MAX_VALUE : base + minA + half;
                int hi = minB < 0 ? Integer.MIN_VALUE
                        : base + len - minB - half;
                if (lo > hi) {
                    // No cut serves both partitions. Depth 0 must cut (the
                    // central street is the quarter's spine) — report
                    // infeasible so the planner grows. Deeper nodes finish
                    // as a multi-demand shelf leaf (invariant-guaranteed).
                    if (depth == 0) failed[0] = true;
                    return null;
                }
                int c = base + (int) Math.round(
                        len * (double) areaA / (double) (areaA + areaB));
                c = clamp(c, lo, hi);
                return new BspCut<>(c, a, b);
            }

            @Override
            public void leaf(int x0, int z0, int x1, int z1,
                             List<Integer> node, int depth) {
                if (failed[0]) return;       // plan() already gave up here
                if (node.size() <= 1) {
                    leaves.add(new int[]{x0, z0, x1, z1});
                    return;
                }
                if (!emitShelfLeaves(x0, z0, x1, z1, node, leaves, alleys)) {
                    failed[0] = true;        // defensive — invariant breach
                }
            }
        };
    }

    /** 4c-c r3 — shelf-packing (NFDH) feasibility for square demands in a
     *  {@code len x cross} rect, either shelf orientation. CONSTRUCTIVE:
     *  success means the explicit non-overlapping layout
     *  {@link #emitShelfLeaves} emits exists, so a true result is sound
     *  (never a false positive — the downstream assignment cannot fail on a
     *  rect this accepted); false negatives merely cost the planner a
     *  growth step. {@code ds} must be sorted DESCENDING (NFDH's
     *  precondition; the guide's node lists are descending throughout). */
    private static boolean shelfFits(int len, int cross, List<Integer> ds) {
        return shelfFitsOriented(len, cross, ds)
                || shelfFitsOriented(cross, len, ds);
    }

    /** One NFDH orientation: shelves of width {@code shelfW} stacked along
     *  {@code stackLen}, demands (descending) placed next-fit. 4c-c r4 —
     *  each shelf TRANSITION also consumes {@link #SHELF_ALLEY_SPAN} (the
     *  internal alley between adjacent rows), so the bound accounts for the
     *  exact layout {@link #emitShelfLeaves} emits and stays constructive.
     *  Monotonicity is preserved: widening the shelf only merges rows,
     *  which drops both a shelf height AND its gap span from the stack. */
    private static boolean shelfFitsOriented(int shelfW, int stackLen,
                                             List<Integer> ds) {
        int used = 0, curH = 0, curW = 0;
        for (int d : ds) {
            if (d > shelfW || d > stackLen) return false;
            if (curH == 0 || curW + d > shelfW) {
                used += curH == 0 ? 0 : curH + SHELF_ALLEY_SPAN;
                curH = d;                    // descending ⇒ ≤ prior shelf
                curW = d;
            } else {
                curW += d;
            }
            if (used + curH > stackLen) return false;
        }
        return true;
    }

    /** 4c-c r3 — smallest along-length in {@code [ds.get(0), maxLen]} whose
     *  {@code length x cross} rect shelf-packs {@code ds}, or -1 when even
     *  {@code maxLen} can't. Binary search — {@link #shelfFits} is monotone
     *  in the length: widening the shelf axis only merges NFDH shelves
     *  (each shelf holds a greedy prefix run, so wider shelves never push an
     *  item later — and r4's per-transition {@link #SHELF_ALLEY_SPAN} only
     *  shrinks with the shelf count), lengthening the stack axis only adds
     *  room, and the OR of two monotone orientations is monotone. */
    private static int minFeasibleLen(List<Integer> ds, int cross, int maxLen) {
        int lo = ds.get(0), hi = maxLen;
        if (lo > hi || !shelfFits(hi, cross, ds)) return -1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (shelfFits(mid, cross, ds)) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    /** 4c-c r3 — finishes a multi-demand node as side-by-side sub-cells: one
     *  EXACT {@code d x d} leaf per demand, NFDH shelves from the rect
     *  corner (stack centred on the cross axis), shelves along the rect's
     *  longer axis when that orientation fits, else the shorter. Exact-size
     *  cells keep the greedy descending assignment safe — a bigger demand
     *  can never steal a smaller demand's cell, so leaves dominate demands
     *  elementwise and Hall's condition holds. 4c-c r4 — adjacent shelf
     *  ROWS are separated by a {@link #SHELF_ALLEY_SPAN} gap carrying a
     *  FOOTPATH alley CENTERLINE spanning the leaf rect (emitted into
     *  {@code alleys}, the same collection the BSP-cut alleys land in, so
     *  it flows through the unified realizer + router no-branch mask
     *  unchanged); a single-row leaf emits no alley. The gap arithmetic
     *  mirrors {@link #shelfFitsOriented} exactly — the bound stays
     *  constructive. Returns false when neither orientation fits — the
     *  guide invariant says never; defensive only. */
    private static boolean emitShelfLeaves(int x0, int z0, int x1, int z1,
                                           List<Integer> ds,
                                           List<int[]> leaves,
                                           List<List<BlockPos>> alleys) {
        int lenX = x1 - x0, lenZ = z1 - z0;
        boolean xFirst = lenX >= lenZ;
        for (int pass = 0; pass < 2; pass++) {
            boolean alongX = (pass == 0) == xFirst;
            int shelfW = alongX ? lenX : lenZ;
            int stackLen = alongX ? lenZ : lenX;
            if (!shelfFitsOriented(shelfW, stackLen, ds)) continue;
            int total = 0, h = 0, w = 0;     // total stack height (centring)
            for (int d : ds) {
                if (h == 0) { h = d; w = d; }
                else if (w + d > shelfW) { total += h + SHELF_ALLEY_SPAN; h = d; w = d; }
                else w += d;
            }
            total += h;
            int v = (stackLen - total) / 2;
            int curH = 0, curW = 0;
            for (int d : ds) {
                if (curH == 0 || curW + d > shelfW) {
                    if (curH > 0) {
                        v += curH;
                        int mid = v + SHELF_ALLEY_SPAN / 2;
                        alleys.add(alongX
                                ? List.of(new BlockPos(x0, 0, z0 + mid),
                                          new BlockPos(x1, 0, z0 + mid))
                                : List.of(new BlockPos(x0 + mid, 0, z0),
                                          new BlockPos(x0 + mid, 0, z1)));
                        v += SHELF_ALLEY_SPAN;
                    }
                    curH = d;
                    curW = 0;
                }
                leaves.add(alongX
                        ? new int[]{x0 + curW, z0 + v, x0 + curW + d, z0 + v + d}
                        : new int[]{x0 + v, z0 + curW, x0 + v + d, z0 + curW + d});
                curW += d;
            }
            return true;
        }
        return false;
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
