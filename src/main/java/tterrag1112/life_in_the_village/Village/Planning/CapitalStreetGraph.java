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
 * <h3>Key concepts</h3>
 *
 * <b>StreetNode</b> — an XZ intersection or endpoint. Holds a tier
 * (PRIMARY / SECONDARY / TERTIARY) that governs road width and surface.
 *
 * <b>StreetSegment</b> — a connection between two nodes. Each segment
 * has a half-width in blocks. No block positions are stored here — those
 * are produced during decoration by RoadRouter.
 *
 * <b>CityBlock</b> — a rectangular parcel bounded on four sides by
 * street segments. Buildings are packed wall-to-wall along the perimeter
 * faces of the block, facing outward toward the street. This is the key
 * difference from per-plot placement: a CityBlock can hold 3–6 houses
 * along each face, producing the "rows of terraced houses" look of a
 * dense medieval city.
 *
 * <h3>Building placement model</h3>
 * The planner fills each CityBlock face with buildings packed at 1-block
 * gaps. Given a face of length L (blocks) and buildings averaging W
 * blocks wide, the number of buildings on that face is roughly L / (W+1).
 * Buildings on the north/south faces of a block face north/south
 * (NONE or CLOCKWISE_180). Buildings on east/west faces face those
 * directions (CLOCKWISE_90 or COUNTERCLOCKWISE_90). This avoids the
 * current problem where every building faces the town square regardless
 * of where it actually is.
 */
public class CapitalStreetGraph {

    // =========================================================================
    // Tier
    // =========================================================================

    public enum StreetTier {
        PRIMARY,    // main avenues — 3-block half-width, cobblestone
        SECONDARY,  // district roads — 2-block half-width, gravel
        TERTIARY    // alleys between blocks — 1-block half-width, dirt path

        ;
        public int halfWidth() {
            return switch (this) {
                case PRIMARY   -> 3;
                case SECONDARY -> 2;
                case TERTIARY  -> 1;
            };
        }
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
        public final UUID         id   = UUID.randomUUID();
        public final StreetNode   a, b;
        public final StreetTier   tier;

        public StreetSegment(StreetNode a, StreetNode b, StreetTier tier) {
            this.a = a; this.b = b; this.tier = tier;
            a.edges.add(this); b.edges.add(this);
        }

        public double length() { return a.distanceTo(b); }

        /** Unit direction vector a→b as {dx, dz}. */
        public double[] direction() {
            double len = length();
            if (len < 0.001) return new double[]{1, 0};
            return new double[]{(b.x - a.x) / len, (b.z - a.z) / len};
        }

        /** Left perpendicular (CCW 90°) of direction. */
        public double[] leftPerp() {
            double[] d = direction(); return new double[]{-d[1], d[0]};
        }

        /** Returns the other node of this segment. */
        public StreetNode other(StreetNode n) { return n == a ? b : a; }
    }

    // =========================================================================
    // CityBlock
    // =========================================================================

    /**
     * A rectangular city block bounded by four street segments.
     *
     * <h3>Face model</h3>
     * Each block has four faces (NORTH, SOUTH, EAST, WEST). Buildings are
     * placed along these faces, wall-to-wall, facing outward. The road
     * on each face is on the outside of the building row — buildings do
     * not face into the block interior.
     *
     * <h3>Why axis-aligned?</h3>
     * Keeping blocks axis-aligned means buildings always face one of the
     * four cardinal directions, which is a hard constraint of Minecraft's
     * NBT structure system. Diagonal blocks would require diagonal buildings
     * which are not supported.
     */
    public static class CityBlock {
        public final UUID id = UUID.randomUUID();

        /** Axis-aligned bounding box of this block in world XZ. */
        public final int minX, minZ, maxX, maxZ;

        /**
         * Assigned building face slots. Populated by
         */
        public final List<BlockFacePlot> plots = new ArrayList<>();

        public CityBlock(int minX, int minZ, int maxX, int maxZ) {
            this.minX = minX; this.minZ = minZ;
            this.maxX = maxX; this.maxZ = maxZ;
        }

        public int width()  { return maxX - minX; }
        public int depth()  { return maxZ - minZ; }
        public int centreX(){ return (minX + maxX) / 2; }
        public int centreZ(){ return (minZ + maxZ) / 2; }

        /**
         * Generates face plots along all four perimeter faces.
         * Buildings are spaced every {@code (buildingWidth + alleyGap)} blocks.
         *
         * @param buildingWidth   typical building footprint in the face direction
         * @param buildingDepth   typical building footprint perpendicular to face
         * @param alleyGap        gap between adjacent buildings (1–2 blocks)
         * @param setback         distance from road kerb to building front face
         */
        public void generateFacePlots(int buildingWidth, int buildingDepth,
                                      int alleyGap, int setback) {
            plots.clear();
            int step = buildingWidth + alleyGap;

            // ── North face (buildings face NORTH, entrance on north/min-Z side) ──
            // Buildings run east–west along minZ edge
            for (int bx = minX + setback; bx + buildingWidth <= maxX - setback; bx += step) {
                int cx = bx + buildingWidth / 2;
                int cz = minZ + setback + buildingDepth / 2;
                plots.add(new BlockFacePlot(cx, cz, buildingWidth, buildingDepth,
                        Face.NORTH, this));
            }

            // ── South face (buildings face SOUTH, entrance on south/max-Z side) ──
            for (int bx = minX + setback; bx + buildingWidth <= maxX - setback; bx += step) {
                int cx = bx + buildingWidth / 2;
                int cz = maxZ - setback - buildingDepth / 2;
                plots.add(new BlockFacePlot(cx, cz, buildingWidth, buildingDepth,
                        Face.SOUTH, this));
            }

            // ── West face (buildings face WEST, entrance on west/min-X side) ──
            // Buildings run north–south along minX edge
            // Width/depth are swapped because these buildings are rotated 90°
            for (int bz = minZ + setback; bz + buildingWidth <= maxZ - setback; bz += step) {
                int cx = minX + setback + buildingDepth / 2;
                int cz = bz + buildingWidth / 2;
                plots.add(new BlockFacePlot(cx, cz, buildingDepth, buildingWidth,
                        Face.WEST, this));
            }

            // ── East face (buildings face EAST, entrance on east/max-X side) ──
            for (int bz = minZ + setback; bz + buildingWidth <= maxZ - setback; bz += step) {
                int cx = maxX - setback - buildingDepth / 2;
                int cz = bz + buildingWidth / 2;
                plots.add(new BlockFacePlot(cx, cz, buildingDepth, buildingWidth,
                        Face.EAST, this));
            }
        }
    }

    // =========================================================================
    // BlockFacePlot
    // =========================================================================

    /**
     * A single building slot on one face of a city block.
     *
     * <p>The building placed here will face outward (toward the road on
     * that face), with its entrance on the street-facing side.
     */
    public static class BlockFacePlot {
        public final int            x, z;        // world XZ centre of the plot
        public final int            plotW, plotD; // approx footprint dimensions
        public final Face           face;         // which block face this is on
        public final CityBlock      block;        // parent block

        public BuildingType assignedType;
        public       String         structurePath;
        public       boolean        occupied;

        public BlockFacePlot(int x, int z, int plotW, int plotD,
                             Face face, CityBlock block) {
            this.x = x; this.z = z;
            this.plotW = plotW; this.plotD = plotD;
            this.face = face; this.block = block;
        }

        /** The Minecraft Rotation that makes the building entrance face the road. */
        public Rotation facingRotation() {
            return switch (face) {
                case NORTH -> Rotation.CLOCKWISE_180;       // entrance faces north (−Z)
                case SOUTH -> Rotation.NONE;                // entrance faces south (+Z)
                case EAST  -> Rotation.COUNTERCLOCKWISE_90; // entrance faces east (+X)
                case WEST  -> Rotation.CLOCKWISE_90;        // entrance faces west (−X)
            };
        }

        public BlockPos toBlockPos(int y) { return new BlockPos(x, y, z); }

        public double distToCenter(int cx, int cz) {
            double dx = x - cx, dz = z - cz;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    // =========================================================================
    // Face enum
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
        this.centreX = centreX;
        this.centreZ = centreZ;
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
    // City block detection
    // =========================================================================

    /**
     * Finds all axis-aligned rectangular city blocks formed by the
     * intersection of streets on the grid overlay.
     *
     * <p>A city block exists wherever four grid nodes form a rectangle
     * with streets on all four sides. This is called after
     * nodes, so all intersections are axis-aligned.
     *
     * <p>The detection works by collecting all X-coordinates and all
     * Z-coordinates that have at least one grid node, then checking
     * every (xA, zA) → (xB, zB) pair where xA &lt; xB and zA &lt; zB.
     * A block is registered if all four corner nodes exist and all four
     * sides have a direct street segment.
     *
     * @param minBlockSize minimum block dimension in blocks (avoids tiny slivers)
     * @param maxBlockSize maximum block dimension in blocks (avoids huge empty blocks)
     */
    public void detectCityBlocks(Map<Long, StreetNode> gridNodes,
                                     int minBlockSize, int maxBlockSize) {
        cityBlocks.clear();

        // Collect unique X and Z coordinates from grid nodes only.
        // Radial spoke nodes are excluded so off-grid angles don't
        // produce degenerate corner lookups.
        TreeSet<Integer> xs = new TreeSet<>(), zs = new TreeSet<>();
        for (StreetNode n : gridNodes.values()) {
            xs.add(n.x);
            zs.add(n.z);
        }

        Integer[] xArr = xs.toArray(new Integer[0]);
        Integer[] zArr = zs.toArray(new Integer[0]);

        for (int xi = 0; xi < xArr.length - 1; xi++) {
            for (int zi = 0; zi < zArr.length - 1; zi++) {
                int x0 = xArr[xi], x1 = xArr[xi + 1];
                int z0 = zArr[zi], z1 = zArr[zi + 1];
                int w  = x1 - x0;
                int d  = z1 - z0;

                if (w < minBlockSize || d < minBlockSize) continue;
                if (w > maxBlockSize || d > maxBlockSize) continue;

                // All four corners must be present in the grid node map
                StreetNode sw = gridNodes.get(xzKey(x0, z0));
                StreetNode se = gridNodes.get(xzKey(x1, z0));
                StreetNode nw = gridNodes.get(xzKey(x0, z1));
                StreetNode ne = gridNodes.get(xzKey(x1, z1));

                if (sw == null || se == null || nw == null || ne == null) continue;

                // All four sides must have a direct street segment
                if (!hasSegment(sw, se)) continue;
                if (!hasSegment(nw, ne)) continue;
                if (!hasSegment(sw, nw)) continue;
                if (!hasSegment(se, ne)) continue;

                cityBlocks.add(new CityBlock(x0, z0, x1, z1));
            }
        }

        System.out.println("CapitalStreetGraph: detected "
                + cityBlocks.size() + " city blocks");
    }

    /** Returns true if a direct segment exists between nodes a and b. */
    private boolean hasSegment(StreetNode a, StreetNode b) {
        for (StreetSegment s : segments) {
            if ((s.a == a && s.b == b) || (s.a == b && s.b == a)) return true;
        }
        return false;
    }

    // =========================================================================
    // Queries
    // =========================================================================

    public List<StreetNode>    getNodes()      { return Collections.unmodifiableList(nodes);      }
    public List<StreetSegment> getSegments()   { return Collections.unmodifiableList(segments);   }
    public List<CityBlock>     getCityBlocks() { return Collections.unmodifiableList(cityBlocks); }

    /** Nearest node within maxDist of (x, z), or null. */
    public StreetNode nearestNode(int x, int z, double maxDist) {
        StreetNode best = null; double bestSq = maxDist * maxDist;
        for (StreetNode n : nodes) {
            double dx = n.x - x, dz = n.z - z, sq = dx * dx + dz * dz;
            if (sq < bestSq) { bestSq = sq; best = n; }
        }
        return best;
    }

    /** True if any occupied plot within radius blocks of (x, z). */
    public boolean hasOccupiedPlotNear(int x, int z, int radius) {
        int rSq = radius * radius;
        for (CityBlock block : cityBlocks) {
            for (BlockFacePlot p : block.plots) {
                if (!p.occupied) continue;
                int dx = p.x - x, dz = p.z - z;
                if (dx * dx + dz * dz < rSq) return true;
            }
        }
        return false;
    }

    /**
     * Returns all unoccupied block-face plots across all city blocks,
     * sorted by distance from the graph centre (nearest first — civic
     * buildings fill innermost blocks, residential fills outward).
     */
    public List<BlockFacePlot> getUnoccupiedPlotsByDistance() {
        List<BlockFacePlot> all = new ArrayList<>();
        for (CityBlock block : cityBlocks) {
            for (BlockFacePlot p : block.plots) {
                if (!p.occupied) all.add(p);
            }
        }
        all.sort(Comparator.comparingDouble(
                p -> p.distToCenter(centreX, centreZ)));
        return all;
    }

    private static long xzKey(int x, int z) {
        return ((long)(x + 32768)) << 32 | ((z + 32768) & 0xFFFFFFFFL);
    }
    private static long gridKey(int x, int z) {
        return ((long)(x + 32768)) << 32 | ((z + 32768) & 0xFFFFFFFFL);
    }
}