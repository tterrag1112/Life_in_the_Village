// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Trade/SeaRouteRouter.java
package tterrag1112.life_in_the_village.Village.Economy.Trade;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.*;

/**
 * Cell-level A* pathfinder for sea routes. Mirror of
 * {@link AtlasRouteRouter} but with inverted traversability.
 *
 * <h3>Traversability</h3>
 * <ul>
 *   <li><b>Open ocean cells:</b> cheap. Base cost.</li>
 *   <li><b>Coastal cells:</b> cheap as endpoints, slightly more
 *       expensive to traverse (boats avoid the shore for safety).</li>
 *   <li><b>Beach cells:</b> traversable as endpoints only — they're
 *       the dock attachment points.</li>
 *   <li><b>All other land:</b> impassable.</li>
 *   <li><b>River cells:</b> impassable. Sea-going boats can't enter
 *       rivers in this phase. (River barge support could come later
 *       as a separate connection type.)</li>
 * </ul>
 *
 * <h3>Discounts</h3>
 * Cells already carrying a sea route get a {@link #ROUTE_DISCOUNT}
 * multiplier so new routes naturally fold into existing trunks,
 * mirroring the land road sharing behaviour.
 */
public class SeaRouteRouter {

    // ── Cost constants ───────────────────────────────────────────────────────

    private static final float COST_OPEN_OCEAN = 1.0f;
    private static final float COST_COAST      = 1.6f;
    private static final float COST_BEACH      = 2.0f;
    private static final float COST_UNFILLED   = 4.0f;

    private static final float ROUTE_DISCOUNT  = 0.15f;
    private static final int   MAX_NODES       = 4_000;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Finds a sea cell path between two block positions (typically the
     * shoreline positions of two dock buildings). Returns the cell key
     * sequence from start to end inclusive, or empty if unreachable.
     */
    public static List<Long> findRoute(WorldAtlas atlas,
                                       BlockPos from, BlockPos to) {
        return findRoute(atlas, from, to, null);
    }

    public static List<Long> findRoute(WorldAtlas atlas,
                                       BlockPos from, BlockPos to,
                                       Set<Long> attractors) {
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
        Map<Long, Node> all = new HashMap<>();
        Set<Long> closed   = new HashSet<>();

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

            // Endpoint cells (start, end) get a relaxed cost so the
            // router can leave the shore. Otherwise BEACH cells in
            // the middle of the path would be too expensive.
            boolean atEndpoint = current.key == startKey
                    || isAdjacentToCellKey(cx, cz, endCx, endCz);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = cx + dx;
                    int nz = cz + dz;
                    long nKey = AtlasCell.packKey(nx, nz);
                    if (closed.contains(nKey)) continue;

                    AtlasCell cell = atlas.getCellByCoord(nx, nz);
                    float cost = seaCellCost(cell, atEndpoint
                            || nKey == endKey);
                    if (cost >= Float.MAX_VALUE / 2) continue;

                    if (!atlas.getRoadsForCellKey(nKey).isEmpty()) {
                        cost *= ROUTE_DISCOUNT;
                    }
                    if (attractors != null && attractors.contains(nKey)) {
                        cost *= 0.3f;
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
            System.out.println("SeaRouteRouter: budget exhausted ("
                    + iterations + " nodes), partial path");
            return reconstruct(best);
        }
        return Collections.emptyList();
    }

    // =========================================================================
    // Cost function
    // =========================================================================

    /**
     * Returns the cost to enter the given cell. Land cells are
     * impassable except at endpoints, where beach and coast cells
     * are accepted as dock attachment points.
     */
    private static float seaCellCost(AtlasCell cell, boolean atEndpoint) {
        if (cell == null) return COST_UNFILLED;

        BiomeCategory cat = cell.category();

        // Open ocean — the bread and butter
        if (cat == BiomeCategory.OCEAN) {
            // Coast-adjacent ocean is slightly more expensive
            return cell.isCoast() ? COST_COAST : COST_OPEN_OCEAN;
        }

        // Beach — only valid at endpoints (dock attachment)
        if (cat == BiomeCategory.BEACH) {
            return atEndpoint ? COST_BEACH : Float.MAX_VALUE;
        }

        // Rivers are not navigable in this phase
        if (cat == BiomeCategory.RIVER) {
            return Float.MAX_VALUE;
        }

        // All other land categories are impassable
        return Float.MAX_VALUE;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static float heuristic(int fromCx, int fromCz, int toCx, int toCz) {
        int dx = Math.abs(toCx - fromCx);
        int dz = Math.abs(toCz - fromCz);
        return Math.max(dx, dz)
                + (float)(Math.sqrt(2) - 1) * Math.min(dx, dz);
    }

    private static boolean isAdjacentToCellKey(int cx, int cz, int targetCx, int targetCz) {
        return Math.abs(cx - targetCx) <= 1 && Math.abs(cz - targetCz) <= 1;
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