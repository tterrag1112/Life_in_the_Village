package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.Cell;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.Gateways;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.LayoutTopology;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NetworkEdge;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NetworkNode;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NetworkSpec;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NodeKind;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ZonePartition;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Layout Rework Stage 3b — terrain-aware <b>block-serving road router</b>.
 *
 * <p>The engine for the Path-A flip: given the as-placed buildings, the
 * gateways, and the terrain ({@link V2FeatureMap}), it routes a road
 * network that <i>serves the buildings</i> (the reverse of today's
 * grow-network-then-hang-buildings). It emits the existing
 * {@link NetworkSpec} shape so {@code Skeleton}/{@code RoadPainter} would
 * work unchanged.
 *
 * <p><b>Not wired into spawning.</b> Stage 3b only exercises this via the
 * comparison dump (the layout dump's {@code candidateNetwork} section).
 * Stage 3c installs it as the real network source.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li><b>Terminals</b> — one per building (a front-point proxy: the
 *       centre stepped toward the anchor by {@code ½footprint+1}, snapped
 *       to the nearest buildable cell, since orientation isn't set until
 *       Stage 3c), one per gateway, plus the anchor (the core). Deduped by
 *       cell.</li>
 *   <li><b>Candidate edges</b> — each terminal to its {@value #NEIGHBOUR_K}
 *       Euclidean-nearest neighbours (gateways/anchor get more), routed
 *       with grid A* over the FeatureMap reusing {@link ZonePartition}'s
 *       per-cell enter-cost (slope- and water/forest-weighted), so roads
 *       seek valleys and bend around water/cliffs.</li>
 *   <li><b>Spanning tree</b> — Kruskal over the candidate edges by routed
 *       terrain cost (prefers cheap terrain). A connectivity sweep adds
 *       cross-component edges if the sparse candidate graph left the tree
 *       disconnected.</li>
 *   <li><b>Tiered widths</b> — edges on the gateway→gateway trunk get the
 *       wider trunk width/tier; local branches are narrower.</li>
 *   <li><b>Emit</b> — one {@link NetworkEdge} per tree edge as a
 *       {@link RoadPrimitive.SmoothedPath} over the routed cell path;
 *       nodes are the terminals.</li>
 * </ol>
 *
 * <p>All numbers are starting baselines meant for tuning once the router
 * is visible in-world (Stage 3c).
 */
public final class BlockServingRouter {

    /** Euclidean neighbours each building terminal proposes as candidate
     *  edges. */
    private static final int NEIGHBOUR_K = 6;
    /** Gateways/anchor propose more candidates so the trunk has options. */
    private static final int HUB_NEIGHBOUR_K = 10;
    /** Cells to search outward for a buildable terminal cell. */
    private static final int SNAP_SEARCH_RADIUS = 6;

    /** Trunk (gateway→gateway) width + tier. */
    private static final int TRUNK_WIDTH = 3;
    private static final RoadShape.RoadTier TRUNK_TIER = RoadShape.RoadTier.VILLAGE_ROAD;
    /** Local branch width + tier. */
    private static final int BRANCH_WIDTH = 2;
    private static final RoadShape.RoadTier BRANCH_TIER = RoadShape.RoadTier.VILLAGE_PATH;

    /** Stage 3 fix-up — enter-cost added to a cell that lies inside a
     *  placed building footprint. A strong FINITE penalty (not a hard
     *  block) so a degenerate dense site can still connect — being forced
     *  through a footprint as a last resort is a spacing signal, not a
     *  crash. It dominates terrain cost (~2..20/cell) by ~100×, so A*
     *  routes around footprints whenever any detour exists. */
    private static final int FOOTPRINT_PENALTY = 2000;

    private BlockServingRouter() {}

    /**
     * Route a block-serving network. Always returns a non-null spec
     * (degenerate inputs yield a node-only spec). Routes that cross
     * impassable terrain (a lake with no buildable bridge) are simply
     * absent — the result is the connectable subforest, which is honest.
     *
     * @param placed   as-placed buildings (terminals)
     * @param gateways the gateway pair (terminals); may be null
     * @param fmap     terrain map (routing space + cost model)
     * @param anchor   the village core (an additional terminal)
     * @param voids    Stage 4a — reserved plaza voids (civic/market squares);
     *                 treated as obstacles so no road crosses or terminates in
     *                 a square. The anchor "core" terminal lands inside the
     *                 civic void and is therefore dropped (the same masked-cell
     *                 logic as footprints); building front-cells that fall in a
     *                 void relocate to its perimeter.
     */
    public static NetworkSpec route(List<PlacedBuilding> placed, Gateways gateways,
                                    V2FeatureMap fmap, BlockPos anchor,
                                    List<tterrag1112.life_in_the_village.Utilities
                                            .Geometry.Polygon.AABB> voids) {
        int g = fmap.gridSize();
        // Stage 3 fix-up + Stage 4a — placed building footprints AND reserved
        // plaza voids are obstacles for routing: roads thread BETWEEN buildings
        // to reach their front-cells and SKIRT the squares, never cutting
        // across a footprint (OverlapAuditor) or a designed plaza.
        boolean[][] obstacle = obstacleMask(placed, voids, fmap, g);
        List<Terminal> terms = buildTerminals(placed, gateways, fmap, anchor, g, obstacle);

        List<NetworkNode> nodes = new ArrayList<>(terms.size());
        for (Terminal t : terms) {
            nodes.add(new NetworkNode(t.nodeId, fmap.cellWorldPos(t.ci, t.cj), t.kind));
        }
        if (terms.size() < 2) {
            return new NetworkSpec(LayoutTopology.CLUSTER, nodes, List.of(), List.of());
        }

        // Candidate edges (k-nearest) routed with A*.
        List<RoutedEdge> candidates = candidateEdges(terms, fmap, g, obstacle);

        // Kruskal MST by routed terrain cost.
        candidates.sort(Comparator.comparingInt(e -> e.cost));
        UnionFind uf = new UnionFind(terms.size());
        List<RoutedEdge> tree = new ArrayList<>();
        for (RoutedEdge e : candidates) {
            if (uf.union(e.a, e.b)) tree.add(e);
        }
        // Connectivity sweep — if the sparse candidate graph left the
        // tree disconnected, route the cheapest cross-component pairs.
        if (uf.components() > 1) {
            ensureConnected(terms, fmap, g, uf, tree, obstacle);
        }

        // Trunk = the tree path between the two gateway terminals.
        Set<Long> trunkEdges = trunkEdgeKeys(terms, tree);

        List<NetworkEdge> edges = new ArrayList<>(tree.size());
        int idx = 0;
        for (RoutedEdge e : tree) {
            boolean trunk = trunkEdges.contains(edgeKey(e.a, e.b));
            List<BlockPos> waypoints = new ArrayList<>(e.path.size());
            for (int[] c : e.path) waypoints.add(fmap.cellWorldPos(c[0], c[1]));
            RoadPrimitive prim = new RoadPrimitive.SmoothedPath(
                    waypoints, 0.5f, 0.0,
                    trunk ? TRUNK_TIER : BRANCH_TIER, 0L);
            edges.add(new NetworkEdge("ce" + idx++,
                    terms.get(e.a).nodeId, terms.get(e.b).nodeId,
                    prim, trunk ? TRUNK_WIDTH : BRANCH_WIDTH));
        }
        return new NetworkSpec(LayoutTopology.CLUSTER, nodes, edges, List.of());
    }

    // =========================================================================
    // Terminals
    // =========================================================================

    private record Terminal(String nodeId, NodeKind kind, int ci, int cj) {}

    private static List<Terminal> buildTerminals(List<PlacedBuilding> placed,
                                                 Gateways gateways, V2FeatureMap fmap,
                                                 BlockPos anchor, int g,
                                                 boolean[][] obstacle) {
        List<Terminal> terms = new ArrayList<>();
        Set<Long> usedCells = new HashSet<>();

        // Anchor core first — but ONLY if it isn't inside an obstacle
        // (a placed footprint OR a reserved plaza void). Stage 3: TOWN_HALL
        // is commonly placed on the anchor → a "core:anchor" terminal would
        // route every edge into its footprint. Stage 4a: the civic square
        // void is centred on the anchor → same drop fires, so trunk edges
        // never home onto the square centre. The covering building's /
        // perimeter front-cells serve that area, so dropping it is safe.
        int[] anchorCell = nearestBuildableCell(fmap, anchor.getX(), anchor.getZ());
        if (anchorCell != null && !obstacle[anchorCell[0]][anchorCell[1]]) {
            addTerminal(terms, usedCells, "core:anchor", NodeKind.ANCHOR, anchorCell, g);
        }

        // Gateways — relocate out of any obstacle (rare at the village edge).
        if (gateways != null) {
            addTerminal(terms, usedCells, "gateway:PRIMARY", NodeKind.GATEWAY,
                    nearestUnobstructedCell(fmap, gateways.primary().getX(),
                            gateways.primary().getZ(), obstacle), g);
            addTerminal(terms, usedCells, "gateway:SECONDARY", NodeKind.GATEWAY,
                    nearestUnobstructedCell(fmap, gateways.secondary().getX(),
                            gateways.secondary().getZ(), obstacle), g);
        }

        // Buildings — front-point proxy, relocated out of obstacles. Stage 4a:
        // a square-fronting building's front-cell steps toward the anchor,
        // INTO the civic void; relocating it to the nearest non-obstacle cell
        // pins the terminal on the square's perimeter so the road skirts the
        // plaza instead of entering it.
        int bi = 0;
        for (PlacedBuilding pb : placed) {
            int[] cell = frontCell(pb, anchor, fmap, obstacle);
            addTerminal(terms, usedCells,
                    "bldg:" + pb.type().name() + ":" + bi++, NodeKind.JUNCTION, cell, g);
        }
        return terms;
    }

    private static void addTerminal(List<Terminal> terms, Set<Long> usedCells,
                                    String id, NodeKind kind, int[] cell, int g) {
        if (cell == null) return;
        long key = (long) cell[0] * g + cell[1];
        if (!usedCells.add(key)) return;   // another terminal already on this cell
        terms.add(new Terminal(id, kind, cell[0], cell[1]));
    }

    /** Front-point proxy: building centre stepped toward the anchor by
     *  {@code ½footprint+1}, then relocated to the nearest buildable cell
     *  that is NOT an obstacle (a footprint or plaza void). A stable
     *  pre-orientation terminal that sits just outside footprints/squares. */
    private static int[] frontCell(PlacedBuilding pb, BlockPos anchor,
                                   V2FeatureMap fmap, boolean[][] obstacle) {
        BlockPos c = pb.centre();
        int half = Math.max(pb.footprint().width(), pb.footprint().length()) / 2 + 1;
        double dx = anchor.getX() - c.getX();
        double dz = anchor.getZ() - c.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        int fx = c.getX();
        int fz = c.getZ();
        if (len >= 1e-9) {
            fx += (int) Math.round(dx / len * half);
            fz += (int) Math.round(dz / len * half);
        }
        return nearestUnobstructedCell(fmap, fx, fz, obstacle);
    }

    /** Nearest buildable cell to a world position, searching outward up
     *  to {@link #SNAP_SEARCH_RADIUS} chebyshev cells. Null if none. */
    private static int[] nearestBuildableCell(V2FeatureMap fmap, int wx, int wz) {
        return nearestCell(fmap, wx, wz, null);
    }

    /** Nearest cell that is buildable AND not an obstacle (footprint /
     *  plaza void). Used for the terminals that must sit OUTSIDE squares
     *  and footprints (gateways, building front-cells). Null if none. */
    private static int[] nearestUnobstructedCell(V2FeatureMap fmap, int wx, int wz,
                                                 boolean[][] obstacle) {
        return nearestCell(fmap, wx, wz, obstacle);
    }

    /** Shared spiral search: nearest buildable cell, optionally also
     *  requiring it be un-masked by {@code obstacle} (null = no mask). */
    private static int[] nearestCell(V2FeatureMap fmap, int wx, int wz,
                                     boolean[][] obstacle) {
        int g = fmap.gridSize();
        int ci = fmap.worldXToCellI(wx);   // clamped to grid
        int cj = fmap.worldZToCellJ(wz);
        if (ok(fmap, obstacle, ci, cj)) return new int[]{ci, cj};
        for (int r = 1; r <= SNAP_SEARCH_RADIUS; r++) {
            for (int di = -r; di <= r; di++) {
                for (int dj = -r; dj <= r; dj++) {
                    if (Math.max(Math.abs(di), Math.abs(dj)) != r) continue; // ring only
                    int ni = ci + di, nj = cj + dj;
                    if (ni < 0 || nj < 0 || ni >= g || nj >= g) continue;
                    if (ok(fmap, obstacle, ni, nj)) return new int[]{ni, nj};
                }
            }
        }
        return null;
    }

    private static boolean ok(V2FeatureMap fmap, boolean[][] obstacle, int i, int j) {
        return ZonePartition.isBuildable(fmap.cell(i, j))
                && (obstacle == null || !obstacle[i][j]);
    }

    /** Stage 3 fix-up + Stage 4a — cell mask of every routing obstacle: each
     *  placed building's rotated footprint AABB (the rect {@code OverlapAuditor}
     *  checks) PLUS each reserved plaza void AABB. A* pays
     *  {@link #FOOTPRINT_PENALTY} to enter these cells, so roads thread between
     *  buildings and skirt the squares. Front-cell terminals sit just outside
     *  footprints; void-adjacent ones are relocated to the void perimeter. */
    private static boolean[][] obstacleMask(List<PlacedBuilding> placed,
                                            List<tterrag1112.life_in_the_village.Utilities
                                                    .Geometry.Polygon.AABB> voids,
                                            V2FeatureMap fmap, int g) {
        boolean[][] mask = new boolean[g][g];
        for (PlacedBuilding pb : placed) {
            boolean swap = pb.rotation() == Rotation.CLOCKWISE_90
                    || pb.rotation() == Rotation.COUNTERCLOCKWISE_90;
            int halfW = (swap ? pb.footprint().length() : pb.footprint().width()) / 2;
            int halfL = (swap ? pb.footprint().width() : pb.footprint().length()) / 2;
            BlockPos c = pb.centre();
            markRect(mask, fmap, g, c.getX() - halfW, c.getZ() - halfL,
                    c.getX() + halfW, c.getZ() + halfL);
        }
        if (voids != null) {
            for (var v : voids) {
                markRect(mask, fmap, g, v.minX(), v.minZ(), v.maxX(), v.maxZ());
            }
        }
        return mask;
    }

    /** Mark every cell whose index range covers world rect [minX,maxX]×
     *  [minZ,maxZ] (clamped). */
    private static void markRect(boolean[][] mask, V2FeatureMap fmap, int g,
                                 int minX, int minZ, int maxX, int maxZ) {
        int minI = fmap.worldXToCellI(minX);
        int maxI = fmap.worldXToCellI(maxX);
        int minJ = fmap.worldZToCellJ(minZ);
        int maxJ = fmap.worldZToCellJ(maxZ);
        for (int i = minI; i <= maxI; i++) {
            for (int j = minJ; j <= maxJ; j++) {
                if (i >= 0 && j >= 0 && i < g && j < g) mask[i][j] = true;
            }
        }
    }

    // =========================================================================
    // Candidate edges + connectivity
    // =========================================================================

    private record RoutedEdge(int a, int b, int cost, List<int[]> path) {}

    private static List<RoutedEdge> candidateEdges(List<Terminal> terms,
                                                   V2FeatureMap fmap, int g,
                                                   boolean[][] footprint) {
        int n = terms.size();
        List<RoutedEdge> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < n; i++) {
            int k = terms.get(i).kind == NodeKind.JUNCTION ? NEIGHBOUR_K : HUB_NEIGHBOUR_K;
            for (int j : nearestNeighbours(terms, i, k)) {
                long key = edgeKey(i, j);
                if (!seen.add(key)) continue;
                RoutedEdge e = routeEdge(terms, i, j, fmap, g, footprint);
                if (e != null) out.add(e);
            }
        }
        return out;
    }

    private static int[] nearestNeighbours(List<Terminal> terms, int i, int k) {
        int n = terms.size();
        Integer[] order = new Integer[n - 1];
        int p = 0;
        for (int j = 0; j < n; j++) if (j != i) order[p++] = j;
        Terminal ti = terms.get(i);
        java.util.Arrays.sort(order, Comparator.comparingLong(
                j -> cellDistSq(ti, terms.get(j))));
        int m = Math.min(k, order.length);
        int[] result = new int[m];
        for (int x = 0; x < m; x++) result[x] = order[x];
        return result;
    }

    private static long cellDistSq(Terminal a, Terminal b) {
        long di = a.ci - b.ci, dj = a.cj - b.cj;
        return di * di + dj * dj;
    }

    /** Connect remaining components by routing the cheapest cross-
     *  component terminal pairs (Euclidean-ordered), A*-routing each;
     *  unreachable pairs (water-split, no buildable bridge) are skipped. */
    private static void ensureConnected(List<Terminal> terms, V2FeatureMap fmap,
                                        int g, UnionFind uf, List<RoutedEdge> tree,
                                        boolean[][] footprint) {
        int n = terms.size();
        record Pair(int a, int b, long d) {}
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                pairs.add(new Pair(i, j, cellDistSq(terms.get(i), terms.get(j))));
            }
        }
        pairs.sort(Comparator.comparingLong(Pair::d));
        for (Pair pr : pairs) {
            if (uf.components() <= 1) break;
            if (uf.find(pr.a) == uf.find(pr.b)) continue;
            RoutedEdge e = routeEdge(terms, pr.a, pr.b, fmap, g, footprint);
            if (e != null && uf.union(e.a, e.b)) tree.add(e);
        }
    }

    // =========================================================================
    // Grid A* over the FeatureMap (reuses ZonePartition's enter-cost)
    // =========================================================================

    private static RoutedEdge routeEdge(List<Terminal> terms, int a, int b,
                                        V2FeatureMap fmap, int g,
                                        boolean[][] footprint) {
        Terminal ta = terms.get(a), tb = terms.get(b);
        int start = ta.ci * g + ta.cj;
        int goal = tb.ci * g + tb.cj;

        int[] gScore = new int[g * g];
        int[] came = new int[g * g];
        java.util.Arrays.fill(gScore, Integer.MAX_VALUE);
        java.util.Arrays.fill(came, -1);
        boolean[] closed = new boolean[g * g];

        gScore[start] = 0;
        PriorityQueue<int[]> open = new PriorityQueue<>(Comparator.comparingInt(x -> x[1]));
        open.add(new int[]{start, heuristic(ta.ci, ta.cj, tb.ci, tb.cj)});

        while (!open.isEmpty()) {
            int cur = open.poll()[0];
            if (closed[cur]) continue;
            closed[cur] = true;
            if (cur == goal) break;
            int ci = cur / g, cj = cur % g;
            for (int di = -1; di <= 1; di++) {
                for (int dj = -1; dj <= 1; dj++) {
                    if (di == 0 && dj == 0) continue;
                    int ni = ci + di, nj = cj + dj;
                    if (ni < 0 || nj < 0 || ni >= g || nj >= g) continue;
                    int nIdx = ni * g + nj;
                    if (closed[nIdx]) continue;
                    Cell nc = fmap.cell(ni, nj);
                    if (!ZonePartition.isBuildable(nc)) continue;
                    // Stage 3 fix-up — strongly avoid building footprints
                    // (finite penalty, so a dense site can still connect).
                    int tentative = gScore[cur] + ZonePartition.enterCost(nc)
                            + (footprint[ni][nj] ? FOOTPRINT_PENALTY : 0);
                    if (tentative < gScore[nIdx]) {
                        gScore[nIdx] = tentative;
                        came[nIdx] = cur;
                        open.add(new int[]{nIdx,
                                tentative + heuristic(ni, nj, tb.ci, tb.cj)});
                    }
                }
            }
        }
        if (gScore[goal] == Integer.MAX_VALUE) return null;   // unreachable

        // Reconstruct path (start → goal).
        List<int[]> path = new ArrayList<>();
        for (int cur = goal; cur != -1; cur = came[cur]) {
            path.add(new int[]{cur / g, cur % g});
            if (cur == start) break;
        }
        java.util.Collections.reverse(path);
        return new RoutedEdge(a, b, gScore[goal], path);
    }

    /** Admissible heuristic: chebyshev cell steps × the minimum per-step
     *  cost ({@link ZonePartition#BASE_STEP_COST}). */
    private static int heuristic(int i, int j, int ti, int tj) {
        return Math.max(Math.abs(i - ti), Math.abs(j - tj)) * ZonePartition.BASE_STEP_COST;
    }

    // =========================================================================
    // Trunk detection (tree path between the two gateways)
    // =========================================================================

    private static Set<Long> trunkEdgeKeys(List<Terminal> terms, List<RoutedEdge> tree) {
        int primary = indexOfNode(terms, "gateway:PRIMARY");
        int secondary = indexOfNode(terms, "gateway:SECONDARY");
        if (primary < 0 || secondary < 0) return Set.of();

        // Adjacency over the tree.
        int n = terms.size();
        List<List<int[]>> adj = new ArrayList<>(n);   // [{neighbour, edgeA, edgeB}]
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (RoutedEdge e : tree) {
            adj.get(e.a).add(new int[]{e.b, e.a, e.b});
            adj.get(e.b).add(new int[]{e.a, e.a, e.b});
        }
        // BFS primary → secondary, tracking the parent edge.
        int[] parentEdgeA = new int[n];
        int[] parentEdgeB = new int[n];
        boolean[] visited = new boolean[n];
        java.util.Arrays.fill(parentEdgeA, -1);
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        q.add(primary);
        visited[primary] = true;
        while (!q.isEmpty()) {
            int u = q.poll();
            if (u == secondary) break;
            for (int[] nb : adj.get(u)) {
                if (visited[nb[0]]) continue;
                visited[nb[0]] = true;
                parentEdgeA[nb[0]] = nb[1];
                parentEdgeB[nb[0]] = nb[2];
                q.add(nb[0]);
            }
        }
        if (!visited[secondary]) return Set.of();   // gateways in different components

        // Walk secondary → primary along parent edges, collecting the
        // trunk edge keys. Each node's parent edge is {ea, eb}; the parent
        // terminal is whichever endpoint isn't the current node.
        Set<Long> trunk = new HashSet<>();
        int cur = secondary;
        while (cur != primary) {
            int ea = parentEdgeA[cur], eb = parentEdgeB[cur];
            if (ea == -1) break;
            trunk.add(edgeKey(ea, eb));
            cur = (ea == cur) ? eb : ea;   // move to the parent terminal
        }
        return trunk;
    }

    private static int indexOfNode(List<Terminal> terms, String nodeId) {
        for (int i = 0; i < terms.size(); i++) {
            if (terms.get(i).nodeId.equals(nodeId)) return i;
        }
        return -1;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static long edgeKey(int a, int b) {
        int lo = Math.min(a, b), hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }

    private static final class UnionFind {
        private final int[] parent;
        private int components;

        UnionFind(int n) {
            parent = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;
            components = n;
        }

        int find(int x) {
            while (parent[x] != x) {
                parent[x] = parent[parent[x]];
                x = parent[x];
            }
            return x;
        }

        boolean union(int a, int b) {
            int ra = find(a), rb = find(b);
            if (ra == rb) return false;
            parent[ra] = rb;
            components--;
            return true;
        }

        int components() { return components; }
    }
}
