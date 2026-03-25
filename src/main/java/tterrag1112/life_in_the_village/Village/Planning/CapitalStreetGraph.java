// src/main/java/tterrag1112/life_in_the_village/Village/Planning/CapitalStreetGraph.java
package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.*;

/**
 * Abstract street network for large capitals, planned before any blocks
 * are placed in the world.
 *
 * <h3>City block model</h3>
 * Each {@link CityBlock} has four faces (N/S/E/W). Buildings are packed
 * wall-to-wall along each face with a corner inset equal to
 * {@code buildingDepth} so that N-face and W/E-face buildings do not
 * collide at the corners. This restores all four faces while keeping
 * the geometry non-overlapping.
 *
 * <h3>District model</h3>
 * Each city block is assigned a {@link CapitalDistrict} based on its
 * distance from the centre and angular position. Districts are used by
 * the building assignment pass to enforce coherent neighbourhoods:
 * civic buildings in the CIVIC_FORUM, craft buildings in the CRAFT_QUARTER,
 * etc.
 */
public class CapitalStreetGraph {

    // =========================================================================
    // CapitalDistrict
    // =========================================================================

    /**
     * Named neighbourhood assigned to each city block.
     * Controls which building types are placed in which block.
     */
    public enum CapitalDistrict {
        /**
         * Innermost blocks (dist < r1). Castle, town hall, chancellery,
         * treasury. No houses — this is the seat of power.
         */
        PALACE_WARD,

        /**
         * South of palace ward (r1–r2, south quadrant ~135°–225°).
         * Market, guild hall, inn, bell tower, temple, library.
         * The social and economic heart of the city.
         */
        CIVIC_FORUM,

        /**
         * East of palace ward (r1–r2, east quadrant ~45°–135°).
         * Blacksmith, bakery, stonemason, armorer, carpentry.
         * Craft and production district.
         */
        CRAFT_QUARTER,

        /**
         * Northwest of palace ward (r1–r2, northwest ~225°–315°).
         * Noble manor, stable, library, apothecary.
         * Affluent residential district.
         */
        NOBLE_QUARTER,

        /**
         * Mid-ring (r2–r3) — all directions.
         * Houses, additional stockpiles, stables.
         * General residential.
         */
        RESIDENTIAL,

        /**
         * Outer ring (dist > r3). Barracks, guard towers,
         * watchtowers, prison. The defensive perimeter.
         */
        OUTER_WALL
    }

    // =========================================================================
    // StreetTier
    // =========================================================================

    public enum StreetTier {
        PRIMARY,   // main avenues — halfWidth 3, cobblestone
        SECONDARY, // district roads — halfWidth 2, gravel
        TERTIARY;  // alleys — halfWidth 1, dirt path

        public int halfWidth() {
            return switch (this) {
                case PRIMARY   -> 3;
                case SECONDARY -> 2;
                case TERTIARY  -> 1;
            };
        }

        /** True if this segment is axis-aligned (always true for grid segments). */
        public boolean isGrid() { return true; }
    }

    // =========================================================================
    // StreetNode
    // =========================================================================

    public static class StreetNode {
        public final UUID       id   = UUID.randomUUID();
        public final int        x, z;
        public       StreetTier tier;
        final List<StreetSegment> edges = new ArrayList<>();

        public StreetNode(int x, int z, StreetTier tier) {
            this.x = x; this.z = z; this.tier = tier;
        }

        public double distanceTo(StreetNode o) {
            double dx = x - o.x, dz = z - o.z;
            return Math.sqrt(dx * dx + dz * dz);
        }

        public void upgradeTier(StreetTier t) {
            if (t.ordinal() < tier.ordinal()) tier = t;
        }

        public BlockPos toBlockPos(int y) { return new BlockPos(x, y, z); }
    }

    // =========================================================================
    // StreetSegment
    // =========================================================================

    public static class StreetSegment {
        public final UUID       id   = UUID.randomUUID();
        public final StreetNode a, b;
        public final StreetTier tier;
        /**
         * True when this segment is perfectly axis-aligned (dx==0 or dz==0).
         * Grid segments are always axis-aligned. Spoke overlays may not be.
         */
        public final boolean axisAligned;

        public StreetSegment(StreetNode a, StreetNode b, StreetTier tier) {
            this.a    = a; this.b = b; this.tier = tier;
            this.axisAligned = (a.x == b.x) || (a.z == b.z);
            a.edges.add(this); b.edges.add(this);
        }

        public double length() { return a.distanceTo(b); }

        public double[] direction() {
            double len = Math.max(0.001, length());
            return new double[]{(b.x - a.x) / len, (b.z - a.z) / len};
        }

        public double[] leftPerp() {
            double[] d = direction(); return new double[]{-d[1], d[0]};
        }

        public StreetNode other(StreetNode n) { return n == a ? b : a; }
    }

    // =========================================================================
    // CityBlock
    // =========================================================================

    /**
     * A rectangular city block bounded by four street segments.
     *
     * <h3>Corner inset</h3>
     * All four faces are active (N, S, E, W). To prevent corner overlap,
     * N/S-face buildings are inset by {@code buildingDepth} at both ends
     * so that E/W-face buildings can occupy the corners without collision.
     *
     * <pre>
     *   ← inset →  [N][N][N]  ← inset →
     *   [W]  ←—— courtyard ——→  [E]
     *   [W]                     [E]
     *   ← inset →  [S][S][S]  ← inset →
     * </pre>
     *
     * The corner cells (inset × depth) are deliberately left empty —
     * they form the visual "corners" of the block, often used for
     * small courtyards, trees, or decorative features.
     */
    public static class CityBlock {
        public final UUID id = UUID.randomUUID();

        public final int minX, minZ, maxX, maxZ;

        /** District this block belongs to — set by CapitalLayoutPlanner. */
        public CapitalDistrict district = CapitalDistrict.RESIDENTIAL;

        /** Angle from the city centre to this block's centre (degrees, east=0). */
        public double angleFromCentre = 0.0;

        /** Distance from the city centre to this block's centre (blocks). */
        public double distFromCentre = 0.0;

        public final List<BlockFacePlot> plots = new ArrayList<>();

        public CityBlock(int minX, int minZ, int maxX, int maxZ) {
            this.minX = minX; this.minZ = minZ;
            this.maxX = maxX; this.maxZ = maxZ;
        }

        public int width()   { return maxX - minX; }
        public int depth()   { return maxZ - minZ; }
        public int centreX() { return (minX + maxX) / 2; }
        public int centreZ() { return (minZ + maxZ) / 2; }

        /**
         * Generates building plots along all four perimeter faces with
         * corner insets so E/W and N/S faces don't overlap at corners.
         *
         * @param buildingWidth  footprint dimension along the face (blocks)
         * @param buildingDepth  footprint dimension perpendicular to face (blocks)
         * @param alleyGap       gap between adjacent buildings on same face (blocks)
         * @param setback        distance from road kerb to building front (blocks)
         * @param roadHalfWidth  half-width of the bounding road (blocks);
         *                       positions are measured from this, not from the node
         */
        public void generateFacePlots(int buildingWidth, int buildingDepth,
                                      int alleyGap, int setback,
                                      int roadHalfWidth) {
            plots.clear();

            // Distance from grid node (block edge) to the inner face of buildings
            int outerMargin = roadHalfWidth + setback;
            // Distance from grid node to the centre of the building footprint
            int outerCentre = outerMargin + buildingDepth / 2;
            // Step between building centres along a face
            int step        = buildingWidth + alleyGap;
            // Corner inset: leave this many blocks clear at each end of N/S faces
            // so that E/W buildings (which run along minX/maxX walls) don't overlap
            int cornerInset = buildingDepth + alleyGap;

            // ── North face — buildings face NORTH (entrance toward −Z / minZ road) ──
            // N-face buildings are set back outerCentre from minZ.
            // They are inset cornerInset from minX and maxX so E/W buildings fit.
            {
                int faceZ  = minZ + outerCentre;
                int startX = minX + outerMargin + cornerInset;
                int endX   = maxX - outerMargin - cornerInset;
                for (int bx = startX; bx + buildingWidth <= endX; bx += step) {
                    int cx = bx + buildingWidth / 2;
                    plots.add(new BlockFacePlot(cx, faceZ, buildingWidth,
                            buildingDepth, Face.NORTH, this));
                }
            }

            // ── South face — buildings face SOUTH (entrance toward +Z / maxZ road) ──
            {
                int faceZ  = maxZ - outerCentre;
                int startX = minX + outerMargin + cornerInset;
                int endX   = maxX - outerMargin - cornerInset;
                for (int bx = startX; bx + buildingWidth <= endX; bx += step) {
                    int cx = bx + buildingWidth / 2;
                    plots.add(new BlockFacePlot(cx, faceZ, buildingWidth,
                            buildingDepth, Face.SOUTH, this));
                }
            }

            // ── West face — buildings face WEST (entrance toward −X / minX road) ──
            // W-face buildings run north–south. No corner inset needed on Z axis
            // because the N/S face buildings already left the corners clear.
            {
                int faceX  = minX + outerCentre;
                int startZ = minZ + outerMargin;
                int endZ   = maxZ - outerMargin;
                for (int bz = startZ; bz + buildingWidth <= endZ; bz += step) {
                    int cz = bz + buildingWidth / 2;
                    // Swap width/depth since the building is rotated 90°
                    plots.add(new BlockFacePlot(faceX, cz, buildingDepth,
                            buildingWidth, Face.WEST, this));
                }
            }

            // ── East face — buildings face EAST (entrance toward +X / maxX road) ──
            {
                int faceX  = maxX - outerCentre;
                int startZ = minZ + outerMargin;
                int endZ   = maxZ - outerMargin;
                for (int bz = startZ; bz + buildingWidth <= endZ; bz += step) {
                    int cz = bz + buildingWidth / 2;
                    plots.add(new BlockFacePlot(faceX, cz, buildingDepth,
                            buildingWidth, Face.EAST, this));
                }
            }
        }

        /** Returns the interior courtyard bounding box (between building rows). */
        public int courtyardMinX(int roadHalfWidth, int setback, int buildingDepth) {
            return minX + roadHalfWidth + setback + buildingDepth + 1;
        }
        public int courtyardMaxX(int roadHalfWidth, int setback, int buildingDepth) {
            return maxX - roadHalfWidth - setback - buildingDepth - 1;
        }
        public int courtyardMinZ(int roadHalfWidth, int setback, int buildingDepth) {
            return minZ + roadHalfWidth + setback + buildingDepth + 1;
        }
        public int courtyardMaxZ(int roadHalfWidth, int setback, int buildingDepth) {
            return maxZ - roadHalfWidth - setback - buildingDepth - 1;
        }
    }

    // =========================================================================
    // BlockFacePlot
    // =========================================================================

    /**
     * A single building slot on one face of a city block.
     * The building faces outward — its entrance is on the street side.
     */
    public static class BlockFacePlot {
        public final int        x, z;
        public final int        plotW, plotD;
        public final Face       face;
        public final CityBlock  block;

        /** Angle from the city centre to this plot (degrees, east=0, clockwise). */
        public double angleFromCentre;

        public BuildingType assignedType;
        public String       structurePath;
        public boolean      occupied;

        public BlockFacePlot(int x, int z, int plotW, int plotD,
                             Face face, CityBlock block) {
            this.x = x; this.z = z;
            this.plotW = plotW; this.plotD = plotD;
            this.face  = face; this.block = block;
        }

        /**
         * Returns the Minecraft Rotation so the building entrance faces the road.
         * NONE = entrance faces south (+Z).
         * CLOCKWISE_180 = entrance faces north (−Z).
         * CLOCKWISE_90 = entrance faces west (−X).
         * COUNTERCLOCKWISE_90 = entrance faces east (+X).
         */
        public Rotation facingRotation() {
            return switch (face) {
                case NORTH -> Rotation.CLOCKWISE_180;
                case SOUTH -> Rotation.NONE;
                case EAST  -> Rotation.COUNTERCLOCKWISE_90;
                case WEST  -> Rotation.CLOCKWISE_90;
            };
        }

        public BlockPos toBlockPos(int y) { return new BlockPos(x, y, z); }

        public double distToCenter(int cx, int cz) {
            double dx = x - cx, dz = z - cz;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    // =========================================================================
    // Face
    // =========================================================================

    public enum Face { NORTH, SOUTH, EAST, WEST }

    // =========================================================================
    // Graph state
    // =========================================================================

    private final List<StreetNode>    nodes      = new ArrayList<>();
    private final List<StreetSegment> segments   = new ArrayList<>();
    private final List<CityBlock>     cityBlocks = new ArrayList<>();

    public final int centreX, centreZ;

    public CapitalStreetGraph(int centreX, int centreZ) {
        this.centreX = centreX; this.centreZ = centreZ;
    }

    // =========================================================================
    // Graph building
    // =========================================================================

    public StreetNode addNode(int x, int z, StreetTier tier) {
        StreetNode n = new StreetNode(x, z, tier);
        nodes.add(n);
        return n;
    }

    public StreetSegment connect(StreetNode a, StreetNode b, StreetTier tier) {
        StreetSegment s = new StreetSegment(a, b, tier);
        segments.add(s);
        return s;
    }

    public void registerCityBlock(CityBlock block) {
        cityBlocks.add(block);
    }

    // =========================================================================
    // Queries
    // =========================================================================

    public List<StreetNode>    getNodes()      { return Collections.unmodifiableList(nodes);      }
    public List<StreetSegment> getSegments()   { return Collections.unmodifiableList(segments);   }
    public List<CityBlock>     getCityBlocks() { return Collections.unmodifiableList(cityBlocks); }

    /** Nearest node within maxDist, or null. */
    public StreetNode nearestNode(int x, int z, double maxDist) {
        StreetNode best = null; double bestSq = maxDist * maxDist;
        for (StreetNode n : nodes) {
            double dx = n.x - x, dz = n.z - z, sq = dx * dx + dz * dz;
            if (sq < bestSq) { bestSq = sq; best = n; }
        }
        return best;
    }

    /**
     * Returns all unoccupied plots sorted nearest-to-centre first.
     */
    public List<BlockFacePlot> getUnoccupiedPlotsByDistance() {
        List<BlockFacePlot> all = new ArrayList<>();
        for (CityBlock b : cityBlocks)
            for (BlockFacePlot p : b.plots)
                if (!p.occupied) all.add(p);
        all.sort(Comparator.comparingDouble(p -> p.distToCenter(centreX, centreZ)));
        return all;
    }

    private static long xzKey(int x, int z) {
        return ((long)(x + 32768)) << 32 | ((z + 32768) & 0xFFFFFFFFL);
    }
}