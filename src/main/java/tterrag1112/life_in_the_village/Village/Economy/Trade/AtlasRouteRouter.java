// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Trade/AtlasRouteRouter.java
package tterrag1112.life_in_the_village.Village.Economy.Trade;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.*;

/**
 * Cell-level A* pathfinder for inter-village routes.
 *
 * <h3>Why cells, not blocks</h3>
 * The block-level {@link RoadRouter} caps at {@link RoadRouter#MAX_NODES}
 * which only covers about 700 blocks of distance before the budget is
 * exhausted. Trade routes between distant villages routinely exceed
 * 2000 blocks. By searching at the atlas cell level (64×64 blocks per
 * node), we can find paths spanning thousands of blocks for the same
 * node budget — at the cost of reduced precision near the route's
 * endpoints, which the realiser fixes up with block-level routing
 * inside each cell.
 *
 * <h3>Cost function</h3>
 * Cell costs come from atlas flags. Flat cells are cheap, swamp and
 * steep cells are expensive, oceans are forbidden, and cells already
 * carrying a road get a steep discount so new routes naturally fold
 * into existing trunks.
 *
 * <h3>Cell coverage assumption</h3>
 * The router walks the atlas as it exists. If a cell on the path isn't
 * filled, it's treated as the average cost for its distance from the
 * filled neighbours — the router does NOT trigger atlas sampling.
 * Callers should call {@link WorldAtlas#ensureRegionFilled} on a
 * generous bounding box around the start and end points before invoking
 * the router. Trade route establishment is the natural place for this
 * pre-fill.
 */
public class AtlasRouteRouter {

    // ── Cost constants (standard connector routing) ──────────────────────────

    /** Base cost per cell traversed. */
    private static final float COST_FLAT      = 1.0f;
    private static final float COST_GENTLE    = 2.0f;
    private static final float COST_STEEP     = 8.0f;
    private static final float COST_SWAMP     = 6.0f;
    private static final float COST_FOREST    = 1.5f;
    private static final float COST_BEACH     = 1.2f;
    private static final float COST_RIVER_ADJ = 1.3f; // crossings cost more
    private static final float COST_UNFILLED  = 4.0f; // unknown terrain

    /** Multiplier applied to cells that already carry a road. <1 = cheaper. */
    private static final float ROAD_DISCOUNT  = 0.15f;

    /** Maximum A* expansions before giving up. */
    private static final int MAX_NODES = 4_000;

    private static final float ATTRACTOR_DISCOUNT = 0.3f;

    // ── Great-road cost constants ────────────────────────────────────────────
    // Great roads followed terrain pragmatically — steep mountain passes only
    // when necessary, but rivers are crossed eagerly (bridges = landmarks).

    /** Higher steep penalty — great roads followed passes, not ridges. */
    private static final float GR_COST_STEEP     = 15.0f;
    /** Higher swamp penalty — ancient roads avoided bogs. */
    private static final float GR_COST_SWAMP     = 8.0f;
    /** Slightly lower forest cost — great roads through forests look great. */
    private static final float GR_COST_FOREST    = 1.3f;
    /** Lower river-adjacent additive — crossings are desirable landmarks. */
    private static final float GR_COST_RIVER_ADJ = 0.9f;
    /** Weak road discount — great roads are trunks, not feeder routes. */
    private static final float GR_ROAD_DISCOUNT  = 0.8f;
    /** Higher node budget — great roads span large distances. */
    private static final int   GR_MAX_NODES      = 12_000;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Finds a cell-level path between two block positions. Equivalent
     * to calling the attractor-aware overload with no attractors.
     */
    public static List<Long> findRoute(WorldAtlas atlas,
                                       BlockPos from, BlockPos to) {
        return findRoute(atlas, from, to, null);
    }

    /**
     * Finds a cell-level path with optional attractor cells. Cells in
     * the {@code attractors} set get a {@link #ATTRACTOR_DISCOUNT}
     * multiplier applied to their traversal cost, biasing the router
     * toward paths that pass through them.
     *
     * @param attractors set of cell keys to favour, or null for none
     */
    public static List<Long> findRoute(WorldAtlas atlas,
                                       BlockPos from, BlockPos to,
                                       Set<Long> attractors) {
        return findRoute(atlas, from, to, attractors, null);
    }

    /**
     * Finds a cell-level path with per-cell attractor discount multipliers.
     * If {@code attractorDiscounts} contains a cell key, that multiplier is
     * used instead of the flat {@link #ATTRACTOR_DISCOUNT}. Per-cell discounts
     * supersede the road-cell discount — the corridor already accounts for
     * the existing road, so both are not applied together.
     *
     * @param attractors        flat attractor set (null = none)
     * @param attractorDiscounts per-cell multipliers keyed by cell key (null = use flat discount only)
     */
    public static List<Long> findRoute(WorldAtlas atlas,
                                       BlockPos from, BlockPos to,
                                       Set<Long> attractors,
                                       Map<Long, Float> attractorDiscounts) {
        int startCx = WorldAtlas.blockToCell(from.getX());
        int startCz = WorldAtlas.blockToCell(from.getZ());
        int endCx   = WorldAtlas.blockToCell(to.getX());
        int endCz   = WorldAtlas.blockToCell(to.getZ());

        if (startCx == endCx && startCz == endCz) {
            return List.of(AtlasCell.packKey(startCx, startCz));
        }

        long startKey = AtlasCell.packKey(startCx, startCz);
        long endKey   = AtlasCell.packKey(endCx,   endCz);

        PriorityQueue<Node> open = new PriorityQueue<>(
                Comparator.comparing(n -> n.f));
        Map<Long, Node> all  = new HashMap<>();
        Set<Long> closed     = new HashSet<>();

        Node start = new Node(startKey, null, 0f,
                heuristic(startCx, startCz, endCx, endCz));
        open.add(start);
        all.put(startKey, start);

        int iterations = 0;
        Node best = start;

        while (!open.isEmpty() && iterations < MAX_NODES) {
            iterations++;
            Node current = open.poll();

            if (current.h < best.h) best = current;
            if (current.key == endKey) {
                return reconstruct(current);
            }

            closed.add(current.key);

            int cx = AtlasCell.unpackX(current.key);
            int cz = AtlasCell.unpackZ(current.key);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = cx + dx;
                    int nz = cz + dz;
                    long nKey = AtlasCell.packKey(nx, nz);
                    if (closed.contains(nKey)) continue;

                    AtlasCell cell = atlas.getCellByCoord(nx, nz);
                    float cost = cellCost(cell);
                    if (cost >= Float.MAX_VALUE / 2) continue; // impassable

                    // Per-cell corridor discount supersedes both road and flat-attractor discounts.
                    Float attrDiscount = (attractorDiscounts != null) ? attractorDiscounts.get(nKey) : null;
                    if (attrDiscount != null) {
                        cost *= attrDiscount;
                    } else {
                        // Standard road discount
                        if (!atlas.getRoadsForCellKey(nKey).isEmpty()) {
                            cost *= ROAD_DISCOUNT;
                        }
                        // Flat trade-hub attractor
                        if (attractors != null && attractors.contains(nKey)) {
                            cost *= ATTRACTOR_DISCOUNT;
                        }
                    }

                    boolean diagonal = dx != 0 && dz != 0;
                    if (diagonal) cost *= 1.414f;

                    float g = current.g + cost;
                    float h = heuristic(nx, nz, endCx, endCz);
                    Node existing = all.get(nKey);
                    if (existing == null || g < existing.g) {
                        Node node = new Node(nKey, current, g, h);
                        all.put(nKey, node);
                        open.add(node);
                    }
                }
            }
        }

        if (best.parent != null) {
            System.out.println("AtlasRouteRouter: budget exhausted ("
                    + iterations + " nodes), partial path");
            return reconstruct(best);
        }
        return Collections.emptyList();
    }

    // =========================================================================
    // Cost & heuristic
    // =========================================================================

    /**
     * Returns the traversal cost for one cell. Returns
     * {@code Float.MAX_VALUE} for impassable cells (open ocean).
     */
    private static float cellCost(AtlasCell cell) {
        if (cell == null) return COST_UNFILLED;

        // Hard barriers
        if (cell.category() == BiomeCategory.OCEAN
                && !cell.isCoast()) {
            return Float.MAX_VALUE;
        }
        if (cell.has(AtlasCell.FLAG_HAS_RIVER) && !cell.isRiverAdj()) {
            // A pure river cell with no land neighbours is impassable
            return Float.MAX_VALUE;
        }

        float base;
        if (cell.isSteep()) base = COST_STEEP;
        else if (cell.has(AtlasCell.FLAG_HAS_SWAMP)) base = COST_SWAMP;
        else if (cell.category() == BiomeCategory.FOREST) base = COST_FOREST;
        else if (cell.has(AtlasCell.FLAG_HAS_BEACH)) base = COST_BEACH;
        else if (cell.slope() > 4) base = COST_GENTLE;
        else base = COST_FLAT;

        // River-adjacent cells cost a bit more (the road needs a bridge)
        if (cell.isRiverAdj()) base += COST_RIVER_ADJ;

        return base;
    }

    private static float heuristic(int fromCx, int fromCz, int toCx, int toCz) {
        int dx = Math.abs(toCx - fromCx);
        int dz = Math.abs(toCz - fromCz);
        return Math.max(dx, dz)
                + (float) (Math.sqrt(2) - 1) * Math.min(dx, dz);
    }

    private static List<Long> reconstruct(Node end) {
        LinkedList<Long> path = new LinkedList<>();
        Node cur = end;
        while (cur != null) {
            path.addFirst(cur.key);
            cur = cur.parent;
        }
        return new ArrayList<>(path);
    }

    // =========================================================================
    // Great-road route (modified cost profile)
    // =========================================================================

    /**
     * Finds a cell-level path optimised for great roads. Uses a different cost
     * profile than the standard connector router: higher steep/swamp penalties
     * (great roads avoided ridges and bogs), lower river-adjacent cost (crossings
     * become natural landmarks), weak road-present discount (great roads are the
     * trunks, not feeders), and a higher node budget for long distances.
     *
     * <p>No attractor support — at worldgen time there are no trade hubs yet.
     *
     * @param worldSeed provided for future per-route seeding; not currently used
     *                  in the cost function itself
     */
    public static List<Long> findGreatRoadRoute(WorldAtlas atlas,
                                                BlockPos from, BlockPos to,
                                                long worldSeed) {
        int startCx = WorldAtlas.blockToCell(from.getX());
        int startCz = WorldAtlas.blockToCell(from.getZ());
        int endCx   = WorldAtlas.blockToCell(to.getX());
        int endCz   = WorldAtlas.blockToCell(to.getZ());

        if (startCx == endCx && startCz == endCz) {
            return List.of(AtlasCell.packKey(startCx, startCz));
        }

        long startKey = AtlasCell.packKey(startCx, startCz);
        long endKey   = AtlasCell.packKey(endCx,   endCz);

        PriorityQueue<Node> open = new PriorityQueue<>(
                Comparator.comparing(n -> n.f));
        Map<Long, Node> all  = new HashMap<>();
        Set<Long> closed     = new HashSet<>();

        Node start = new Node(startKey, null, 0f,
                heuristic(startCx, startCz, endCx, endCz));
        open.add(start);
        all.put(startKey, start);

        int iterations = 0;
        Node best = start;

        while (!open.isEmpty() && iterations < GR_MAX_NODES) {
            iterations++;
            Node current = open.poll();

            if (current.h < best.h) best = current;
            if (current.key == endKey) {
                return reconstruct(current);
            }

            closed.add(current.key);

            int cx = AtlasCell.unpackX(current.key);
            int cz = AtlasCell.unpackZ(current.key);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = cx + dx;
                    int nz = cz + dz;
                    long nKey = AtlasCell.packKey(nx, nz);
                    if (closed.contains(nKey)) continue;

                    AtlasCell cell = atlas.getCellByCoord(nx, nz);
                    float cost = greatRoadCellCost(cell);
                    if (cost >= Float.MAX_VALUE / 2) continue; // impassable

                    // Weak road discount — great roads are the trunks, not feeders
                    if (!atlas.getRoadsForCellKey(nKey).isEmpty()) {
                        cost *= GR_ROAD_DISCOUNT;
                    }

                    boolean diagonal = dx != 0 && dz != 0;
                    if (diagonal) cost *= 1.414f;

                    float g = current.g + cost;
                    float h = heuristic(nx, nz, endCx, endCz);
                    Node existing = all.get(nKey);
                    if (existing == null || g < existing.g) {
                        Node node = new Node(nKey, current, g, h);
                        all.put(nKey, node);
                        open.add(node);
                    }
                }
            }
        }

        if (best.parent != null) {
            System.out.println("[GreatRoad Router] budget exhausted ("
                    + iterations + " nodes), using partial path");
            return reconstruct(best);
        }
        return Collections.emptyList();
    }

    /**
     * Great-road cell cost. Mirrors {@link #cellCost} but with tuned constants
     * for ancient road character: penalises steep terrain heavily, rewards
     * river crossings, accepts forest corridors.
     */
    private static float greatRoadCellCost(AtlasCell cell) {
        if (cell == null) return COST_UNFILLED;

        // Hard barriers (same as standard)
        if (cell.category() == BiomeCategory.OCEAN && !cell.isCoast()) {
            return Float.MAX_VALUE;
        }
        if (cell.has(AtlasCell.FLAG_HAS_RIVER) && !cell.isRiverAdj()) {
            return Float.MAX_VALUE;
        }

        float base;
        if (cell.isSteep()) base = GR_COST_STEEP;
        else if (cell.has(AtlasCell.FLAG_HAS_SWAMP)) base = GR_COST_SWAMP;
        else if (cell.category() == BiomeCategory.FOREST) base = GR_COST_FOREST;
        else if (cell.has(AtlasCell.FLAG_HAS_BEACH)) base = COST_BEACH;
        else if (cell.slope() > 4) base = COST_GENTLE;
        else base = COST_FLAT;

        // River-adjacent: cheaper for great roads (bridge = landmark)
        if (cell.isRiverAdj()) base += GR_COST_RIVER_ADJ;

        return base;
    }

    // =========================================================================
    // Overlap detection
    // =========================================================================

    /**
     * Returns the fraction of {@code newPath} cells that are also in
     * {@code existingPath}. Used by {@link TradeRouteManager} to decide
     * whether a new route should reuse an existing road instead of
     * placing a fresh one.
     *
     * @return value in [0, 1] — 0 = no overlap, 1 = identical paths
     */
    public static double pathOverlapRatio(List<Long> newPath,
                                          List<Long> existingPath) {
        if (newPath.isEmpty()) return 0.0;
        Set<Long> existingSet = new HashSet<>(existingPath);
        int matches = 0;
        for (long key : newPath) {
            if (existingSet.contains(key)) matches++;
        }
        return (double) matches / newPath.size();
    }

    // =========================================================================
    // Cell-key → block-position helper
    // =========================================================================

    /**
     * Returns the world block position at the centre of the given
     * atlas cell key. Used by the realiser to convert a cell sequence
     * into block-level waypoints.
     */
    public static BlockPos cellKeyToBlockCenter(long cellKey) {
        int cx = AtlasCell.unpackX(cellKey);
        int cz = AtlasCell.unpackZ(cellKey);
        return new BlockPos(
                (cx << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF,
                0,
                (cz << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF);
    }

    // =========================================================================
    // Supporting types
    // =========================================================================

    private static final class Node {
        final long key;
        final Node parent;
        final float g, h, f;

        Node(long key, Node parent, float g, float h) {
            this.key    = key;
            this.parent = parent;
            this.g      = g;
            this.h      = h;
            this.f      = g + h;
        }
    }
}