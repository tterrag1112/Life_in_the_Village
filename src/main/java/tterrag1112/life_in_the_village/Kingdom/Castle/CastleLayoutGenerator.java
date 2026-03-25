package tterrag1112.life_in_the_village.Kingdom.Castle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 1: Generates the castle's spatial plan (CastleLayout) from a CastleStyle.
 *
 * Performs no world access. All output is pure data.
 * The generated layout drives CastleBuilder in Layer 2.
 */
public class CastleLayoutGenerator {

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /**
     * Generate a full CastleLayout centered on the given origin.
     * The origin Y is treated as ground level; terrain adaptation
     * is handled later by CastleBuilder when it accesses the world.
     */
    public CastleLayout generate(CastleStyle style, BlockPos origin, RandomSource rng) {
        // Mix in the style's own seed so the same world seed + different styles
        // produce different castles.
        RandomSource styleRng = RandomSource.create(
                rng.nextLong() ^ style.styleSeed()
        );

        int radius        = style.rollRadius(styleRng);
        int wallHeight    = style.rollWallHeight(styleRng);
        int towerRadius   = style.rollTowerRadius(styleRng);

        // 1. Generate outer tower nodes based on plan type
        List<CastleLayout.TowerNode> outerTowers = switch (style.planType()) {
            case SQUARE     -> generateSquarePlan(origin, radius, towerRadius, wallHeight, style, styleRng);
            case RECTANGLE  -> generateRectanglePlan(origin, radius, towerRadius, wallHeight, style, styleRng);
            case POLYGON    -> generatePolygonPlan(origin, radius, towerRadius, wallHeight, style, styleRng);
            case CONCENTRIC -> generateSquarePlan(origin, radius, towerRadius, wallHeight, style, styleRng);
            case IRREGULAR  -> generateIrregularPlan(origin, radius, towerRadius, wallHeight, style, styleRng);
        };

        // 2. Connect tower nodes with wall edges
        List<CastleLayout.WallEdge> outerEdges = buildRingEdges(outerTowers, wallHeight);

        // 3. Optionally add mid-wall flanking towers along long wall segments
        outerTowers = addFlankingTowers(outerTowers, outerEdges, style, towerRadius, wallHeight, styleRng);
        outerEdges  = buildRingEdges(outerTowers, wallHeight); // rebuild after insertions

        // 4. Assign one tower as the gatehouse (facing outward toward positive Z by default)
        assignGatehouse(outerTowers, origin);

        // 5. Optionally build a donjon at the center
        CastleLayout.TowerNode donjon = null;
        if (style.hasDonjon()) {
            donjon = buildDonjon(origin, style, wallHeight, styleRng);
        }

        // 6. Optionally build an inner ward
        CastleLayout.InnerWard innerWard = null;
        if (style.hasInnerWard() && style.planType() == CastleStyle.PlanType.CONCENTRIC) {
            innerWard = buildInnerWard(origin, radius, towerRadius, wallHeight, style, styleRng);
        }

        // 7. Optionally compute moat bounds
        CastleLayout.MoatBounds moat = null;
        if (style.hasMoat()) {
            moat = buildMoatBounds(origin, radius, style);
        }

        return new CastleLayout(
                origin,
                outerTowers,
                outerEdges,
                donjon,
                innerWard,
                moat,
                wallHeight
        );
    }

    // -------------------------------------------------------------------------
    // Plan generators
    // -------------------------------------------------------------------------

    /** Four corner towers at the corners of a square with side = 2*radius. */
    private List<CastleLayout.TowerNode> generateSquarePlan(BlockPos origin, int radius, int towerRadius,
                                                            int wallHeight, CastleStyle style, RandomSource rng) {
        int r = radius;
        List<CastleLayout.TowerNode> nodes = new ArrayList<>();
        nodes.add(makeCorner(origin.offset(-r, 0,  r), towerRadius, wallHeight, Direction.WEST));
        nodes.add(makeCorner(origin.offset( r, 0,  r), towerRadius, wallHeight, Direction.SOUTH));
        nodes.add(makeCorner(origin.offset( r, 0, -r), towerRadius, wallHeight, Direction.EAST));
        nodes.add(makeCorner(origin.offset(-r, 0, -r), towerRadius, wallHeight, Direction.NORTH));
        return nodes;
    }

    /** Four corners on a rectangle — longer on the X axis by a random factor. */
    private List<CastleLayout.TowerNode> generateRectanglePlan(BlockPos origin, int radius, int towerRadius,
                                                               int wallHeight, CastleStyle style, RandomSource rng) {
        int rx = radius + rng.nextInt(radius / 2 + 1); // 1.0x–1.5x radius on X
        int rz = radius;
        List<CastleLayout.TowerNode> nodes = new ArrayList<>();
        nodes.add(makeCorner(origin.offset(-rx, 0,  rz), towerRadius, wallHeight, Direction.WEST));
        nodes.add(makeCorner(origin.offset( rx, 0,  rz), towerRadius, wallHeight, Direction.SOUTH));
        nodes.add(makeCorner(origin.offset( rx, 0, -rz), towerRadius, wallHeight, Direction.EAST));
        nodes.add(makeCorner(origin.offset(-rx, 0, -rz), towerRadius, wallHeight, Direction.NORTH));
        return nodes;
    }

    /**
     * N-sided regular polygon tower ring.
     * Sides chosen between 5 and 8 based on radius.
     */
    private List<CastleLayout.TowerNode> generatePolygonPlan(BlockPos origin, int radius, int towerRadius,
                                                             int wallHeight, CastleStyle style, RandomSource rng) {
        // More sides for larger castles for a rounder feel
        int sides = 5 + rng.nextInt(4); // 5, 6, 7, or 8
        List<CastleLayout.TowerNode> nodes = new ArrayList<>();
        for (int i = 0; i < sides; i++) {
            double angle = (2.0 * Math.PI * i / sides) - Math.PI / 2.0;
            int x = origin.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = origin.getZ() + (int) Math.round(Math.sin(angle) * radius);
            BlockPos pos = new BlockPos(x, origin.getY(), z);
            // Outward facing direction approximation
            Direction outward = approximateDirection(angle);
            nodes.add(makeCorner(pos, towerRadius, wallHeight, outward));
        }
        return nodes;
    }

    /**
     * Irregular polygon: take a square and perturb each corner randomly.
     * Produces asymmetric, organic-looking curtain wall paths.
     */
    private List<CastleLayout.TowerNode> generateIrregularPlan(BlockPos origin, int radius, int towerRadius,
                                                               int wallHeight, CastleStyle style, RandomSource rng) {
        // Start with an 8-point polygon and jitter each point
        int points = 6 + rng.nextInt(3);
        int jitter = radius / 3;
        List<CastleLayout.TowerNode> nodes = new ArrayList<>();
        for (int i = 0; i < points; i++) {
            double angle = (2.0 * Math.PI * i / points) - Math.PI / 2.0;
            int baseX = (int) Math.round(Math.cos(angle) * radius);
            int baseZ = (int) Math.round(Math.sin(angle) * radius);
            int dx = (rng.nextInt(jitter * 2 + 1)) - jitter;
            int dz = (rng.nextInt(jitter * 2 + 1)) - jitter;
            BlockPos pos = origin.offset(baseX + dx, 0, baseZ + dz);
            Direction outward = approximateDirection(angle);
            nodes.add(makeCorner(pos, towerRadius, wallHeight, outward));
        }
        return nodes;
    }

    // -------------------------------------------------------------------------
    // Edge building
    // -------------------------------------------------------------------------

    /**
     * Connect nodes in order as a closed ring (last node connects back to first).
     */
    private List<CastleLayout.WallEdge> buildRingEdges(List<CastleLayout.TowerNode> nodes, int wallHeight) {
        List<CastleLayout.WallEdge> edges = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            int next = (i + 1) % nodes.size();
            edges.add(new CastleLayout.WallEdge(i, next, wallHeight, true));
        }
        return edges;
    }

    // -------------------------------------------------------------------------
    // Flanking towers
    // -------------------------------------------------------------------------

    /**
     * For wall edges longer than a threshold, insert a flanking tower in the middle.
     * This is done after the base polygon is generated so it doesn't affect ring symmetry.
     *
     * NOTE: This modifies the towers list and returns a new combined list.
     * Edges are rebuilt by the caller after this step.
     */
    private List<CastleLayout.TowerNode> addFlankingTowers(List<CastleLayout.TowerNode> towers, List<CastleLayout.WallEdge> edges,
                                                           CastleStyle style, int towerRadius,
                                                           int wallHeight, RandomSource rng) {
        if (style.towerFrequency() <= 0f) return towers;

        // Threshold: add a flanking tower if segment length > 2.5 * tower spacing
        int threshold = (towerRadius * 2 + 8) * 3;
        List<CastleLayout.TowerNode> result = new ArrayList<>(towers);
        int inserted = 0;

        for (CastleLayout.WallEdge edge : edges) {
            // Account for already-inserted nodes shifting indices
            int fromIdx = edge.fromIndex() + inserted;
            int toIdx   = edge.toIndex()   + inserted;
            // Handle wrap-around: if toIdx is 0 in original, it stays at original size + inserted
            if (toIdx < fromIdx) toIdx = result.size();

            CastleLayout.TowerNode from = result.get(fromIdx);
            CastleLayout.TowerNode to   = result.get(toIdx % result.size());

            double dist = from.center().distSqr(to.center());
            if (dist > (long) threshold * threshold && rng.nextFloat() < style.towerFrequency()) {
                BlockPos mid = midpoint(from.center(), to.center());
                CastleLayout.TowerNode flanking = new CastleLayout.TowerNode(
                        mid,
                        CastleLayout.TowerNode.TowerRole.FLANKING,
                        towerRadius,
                        wallHeight,
                        from.facing()
                );
                // Insert after fromIdx
                result.add(fromIdx + 1, flanking);
                inserted++;
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Gatehouse assignment
    // -------------------------------------------------------------------------

    /**
     * Assign the GATEHOUSE role to the tower closest to the south face of the castle.
     * (South = positive Z = the conventional "front" of a castle.)
     * Mutates the list in-place by replacing the chosen node.
     */
    private void assignGatehouse(List<CastleLayout.TowerNode> towers, BlockPos origin) {
        if (towers.isEmpty()) return;

        int bestIdx = 0;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < towers.size(); i++) {
            CastleLayout.TowerNode t = towers.get(i);
            // Prefer corners or flanking towers on the south face
            if (t.center().getZ() > maxZ) {
                maxZ = t.center().getZ();
                bestIdx = i;
            }
        }

        CastleLayout.TowerNode original = towers.get(bestIdx);
        towers.set(bestIdx, new CastleLayout.TowerNode(
                original.center(),
                CastleLayout.TowerNode.TowerRole.GATEHOUSE,
                original.radius() + 1, // gatehouses slightly larger
                original.wallHeight(),
                Direction.SOUTH
        ));
    }

    // -------------------------------------------------------------------------
    // Donjon
    // -------------------------------------------------------------------------

    private CastleLayout.TowerNode buildDonjon(BlockPos origin, CastleStyle style,
                                               int wallHeight, RandomSource rng) {
        int size = style.rollDonjeonSize(rng);
        return new CastleLayout.TowerNode(
                origin, // Always at the center
                CastleLayout.TowerNode.TowerRole.DONJON,
                size,
                wallHeight + style.donjeonHeightBonus(),
                Direction.SOUTH
        );
    }

    // -------------------------------------------------------------------------
    // Inner ward
    // -------------------------------------------------------------------------

    private CastleLayout.InnerWard buildInnerWard(BlockPos origin, int outerRadius, int towerRadius,
                                                  int wallHeight, CastleStyle style, RandomSource rng) {
        int innerRadius = (int) (outerRadius * style.innerWardScale());
        if (innerRadius < 8) return null; // Too small to be meaningful

        // Inner ward uses a square plan for simplicity
        List<CastleLayout.TowerNode> innerTowers = generateSquarePlan(
                origin, innerRadius, Math.max(2, towerRadius - 1),
                wallHeight - 2, style, rng
        );
        List<CastleLayout.WallEdge> innerEdges = buildRingEdges(innerTowers, wallHeight - 2);
        return new CastleLayout.InnerWard(innerTowers, innerEdges);
    }

    // -------------------------------------------------------------------------
    // Moat
    // -------------------------------------------------------------------------

    private CastleLayout.MoatBounds buildMoatBounds(BlockPos origin, int radius, CastleStyle style) {
        int pad = style.moatWidth() + 4;
        BlockPos min = origin.offset(-radius - pad, 0, -radius - pad);
        BlockPos max = origin.offset( radius + pad, 0,  radius + pad);
        return new CastleLayout.MoatBounds(min, max, style.moatWidth(), style.moatDepth());
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    private CastleLayout.TowerNode makeCorner(BlockPos pos, int radius, int wallHeight, Direction outward) {
        return new CastleLayout.TowerNode(pos, CastleLayout.TowerNode.TowerRole.CORNER, radius, wallHeight, outward);
    }

    private BlockPos midpoint(BlockPos a, BlockPos b) {
        return new BlockPos(
                (a.getX() + b.getX()) / 2,
                (a.getY() + b.getY()) / 2,
                (a.getZ() + b.getZ()) / 2
        );
    }

    /**
     * Approximate the outward-facing cardinal direction from a radial angle.
     */
    private Direction approximateDirection(double angleRad) {
        double deg = Math.toDegrees(angleRad) % 360;
        if (deg < 0) deg += 360;
        if (deg < 45 || deg >= 315) return Direction.NORTH;
        if (deg < 135)              return Direction.EAST;
        if (deg < 225)              return Direction.SOUTH;
        return Direction.WEST;
    }
}
