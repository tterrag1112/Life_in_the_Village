// src/main/java/tterrag1112/life_in_the_village/Village/Decoration/Roads/TrunkRoadPlanner.java
package tterrag1112.life_in_the_village.Village.Decoration.Roads;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.Planning.LayoutDensityProfile;
import tterrag1112.life_in_the_village.Village.Planning.Rules.RuleContext;
import tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates a trunk-and-spur {@link TrunkGraph} for a village based
 * on its shape rules. Runs before building placement so the planner
 * can snap cluster anchors onto the trunk.
 *
 * <h3>Topology per shape</h3>
 * <ul>
 *   <li><b>RADIAL</b> — ring road around the centre + 4 cardinal spokes</li>
 *   <li><b>LINEAR</b> — straight trunk along the axis + perpendicular spurs</li>
 *   <li><b>CLUSTERED</b> — minimal trunk; cluster centres connect directly</li>
 *   <li><b>RIVERINE</b> — trunk parallel to water + spurs to water and inland</li>
 *   <li><b>HILLTOP</b> — spiral winding up + radial spurs at each level</li>
 *   <li><b>PLAZA</b> — tight ring around plaza + 8 radial spokes</li>
 *   <li><b>COURTYARD</b> — grid (capital topology at any scale)</li>
 * </ul>
 *
 * <h3>What each method does</h3>
 * Each topology method takes the centre, the trunk radius (from density),
 * and the rule context (for axis direction). It produces nodes at the
 * key intersections of its layout and connects them with straight-line
 * centerlines snapped to terrain Y. The renderer turns these into
 * actual road blocks later.
 */
public class TrunkRoadPlanner {

    private static final RoadShape.RoadTier TRUNK_TIER = RoadShape.RoadTier.VILLAGE_ROAD;
    private static final RoadShape.RoadTier SPUR_TIER = RoadShape.RoadTier.VILLAGE_PATH;

    public static TrunkGraph plan(ServerLevel level,
                                  BlockPos centre,
                                  ShapeType shape,
                                  LayoutDensityProfile density,
                                  RuleContext ctx) {
        int radius = density.getRing2Radius();

        return switch (shape) {
            case RADIAL    -> radial(level, centre, radius);
            case LINEAR    -> linear(level, centre, radius, ctx);
            case CLUSTERED -> clustered(level, centre, radius);
            case RIVERINE  -> riverine(level, centre, radius, ctx);
            case HILLTOP   -> hilltop(level, centre, radius);
            case PLAZA     -> plaza(level, centre, radius);
            case COURTYARD -> courtyard(level, centre, radius);
        };
    }

    // =========================================================================
    // RADIAL — ring road + 4 cardinal spokes
    // =========================================================================

    private static TrunkGraph radial(ServerLevel level, BlockPos centre, int radius) {
        TrunkGraph g = new TrunkGraph();
        TrunkGraph.Node hub = g.addNode(centre, TRUNK_TIER);

        // 8 nodes around the ring at every 45°
        TrunkGraph.Node[] ring = new TrunkGraph.Node[8];
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            BlockPos pos = surfaceAt(level, centre,
                    (int)(Math.cos(angle) * radius),
                    (int)(Math.sin(angle) * radius));
            ring[i] = g.addNode(pos, TRUNK_TIER);
        }
        // Connect ring nodes
        for (int i = 0; i < 8; i++) {
            int next = (i + 1) % 8;
            g.connect(ring[i], ring[next], TRUNK_TIER,
                    straightLine(level, ring[i].pos, ring[next].pos));
        }
        // 4 cardinal spokes hub → ring (every other ring node)
        for (int i = 0; i < 8; i += 2) {
            g.connect(hub, ring[i], TRUNK_TIER,
                    straightLine(level, hub.pos, ring[i].pos));
        }
        return g;
    }

    // =========================================================================
    // LINEAR — straight trunk along axis + perpendicular spurs
    // =========================================================================

    private static TrunkGraph linear(ServerLevel level, BlockPos centre,
                                     int radius, RuleContext ctx) {
        TrunkGraph g = new TrunkGraph();
        double axis = ctx.axisRad() != null ? ctx.axisRad() : 0;

        // Trunk extends 2× radius along the axis
        BlockPos start = surfaceAt(level, centre,
                (int)(Math.cos(axis) * -radius), (int)(Math.sin(axis) * -radius));
        BlockPos end = surfaceAt(level, centre,
                (int)(Math.cos(axis) * radius), (int)(Math.sin(axis) * radius));

        TrunkGraph.Node startNode = g.addNode(start, TRUNK_TIER);
        TrunkGraph.Node endNode = g.addNode(end, TRUNK_TIER);
        g.connect(startNode, endNode, TRUNK_TIER, straightLine(level, start, end));

        // Perpendicular spurs every 20 blocks along the trunk
        double perpAxis = axis + Math.PI / 2;
        int spurLength = radius / 2;
        for (int t = -radius + 20; t < radius; t += 20) {
            BlockPos onTrunk = surfaceAt(level, centre,
                    (int)(Math.cos(axis) * t), (int)(Math.sin(axis) * t));
            BlockPos spurEnd1 = surfaceAt(level, onTrunk,
                    (int)(Math.cos(perpAxis) * spurLength),
                    (int)(Math.sin(perpAxis) * spurLength));
            BlockPos spurEnd2 = surfaceAt(level, onTrunk,
                    (int)(Math.cos(perpAxis) * -spurLength),
                    (int)(Math.sin(perpAxis) * -spurLength));
            TrunkGraph.Node trunkPt = g.addNode(onTrunk, TRUNK_TIER);
            TrunkGraph.Node sp1 = g.addNode(spurEnd1, SPUR_TIER);
            TrunkGraph.Node sp2 = g.addNode(spurEnd2, SPUR_TIER);
            g.connect(trunkPt, sp1, SPUR_TIER, straightLine(level, onTrunk, spurEnd1));
            g.connect(trunkPt, sp2, SPUR_TIER, straightLine(level, onTrunk, spurEnd2));
        }
        return g;
    }

    // =========================================================================
    // CLUSTERED — minimal hub, no real trunk
    // =========================================================================

    private static TrunkGraph clustered(ServerLevel level, BlockPos centre, int radius) {
        TrunkGraph g = new TrunkGraph();
        // Single hub. Cluster connections happen during spur pass.
        g.addNode(centre, TRUNK_TIER);
        return g;
    }

    // =========================================================================
    // RIVERINE — trunk parallel to water + spurs to water and inland
    // =========================================================================

    private static TrunkGraph riverine(ServerLevel level, BlockPos centre,
                                       int radius, RuleContext ctx) {
        // Without water position info, fall back to LINEAR along axis.
        // Phase 9c-2 reads waterFacingDir from terrain profile to do this properly.
        return linear(level, centre, radius, ctx);
    }

    // =========================================================================
    // HILLTOP — spiral
    // =========================================================================

    private static TrunkGraph hilltop(ServerLevel level, BlockPos centre, int radius) {
        TrunkGraph g = new TrunkGraph();
        TrunkGraph.Node hub = g.addNode(centre, TRUNK_TIER);

        // Spiral: 3 turns, 12 nodes total
        TrunkGraph.Node prev = hub;
        for (int i = 1; i <= 12; i++) {
            double t = i / 12.0;
            double angle = t * 3 * 2 * Math.PI;
            int r = (int)(radius * t);
            BlockPos pos = surfaceAt(level, centre,
                    (int)(Math.cos(angle) * r), (int)(Math.sin(angle) * r));
            TrunkGraph.Node node = g.addNode(pos, TRUNK_TIER);
            g.connect(prev, node, TRUNK_TIER, straightLine(level, prev.pos, pos));
            prev = node;
        }
        return g;
    }

    // =========================================================================
    // PLAZA — tight ring + 8 spokes
    // =========================================================================

    private static TrunkGraph plaza(ServerLevel level, BlockPos centre, int radius) {
        TrunkGraph g = new TrunkGraph();
        TrunkGraph.Node hub = g.addNode(centre, TRUNK_TIER);

        int innerRing = 12;
        int outerRing = radius;

        TrunkGraph.Node[] inner = new TrunkGraph.Node[8];
        TrunkGraph.Node[] outer = new TrunkGraph.Node[8];
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            inner[i] = g.addNode(surfaceAt(level, centre,
                    (int)(Math.cos(angle) * innerRing),
                    (int)(Math.sin(angle) * innerRing)), TRUNK_TIER);
            outer[i] = g.addNode(surfaceAt(level, centre,
                    (int)(Math.cos(angle) * outerRing),
                    (int)(Math.sin(angle) * outerRing)), TRUNK_TIER);
        }
        for (int i = 0; i < 8; i++) {
            int next = (i + 1) % 8;
            g.connect(inner[i], inner[next], TRUNK_TIER,
                    straightLine(level, inner[i].pos, inner[next].pos));
            g.connect(inner[i], outer[i], TRUNK_TIER,
                    straightLine(level, inner[i].pos, outer[i].pos));
        }
        return g;
    }

    // =========================================================================
    // COURTYARD — grid
    // =========================================================================

    private static TrunkGraph courtyard(ServerLevel level, BlockPos centre, int radius) {
        TrunkGraph g = new TrunkGraph();
        int spacing = 24;
        int gridSize = (radius / spacing) * 2 + 1;
        int half = gridSize / 2;

        TrunkGraph.Node[][] grid = new TrunkGraph.Node[gridSize][gridSize];
        for (int gx = -half; gx <= half; gx++) {
            for (int gz = -half; gz <= half; gz++) {
                BlockPos pos = surfaceAt(level, centre, gx * spacing, gz * spacing);
                grid[gx + half][gz + half] = g.addNode(pos, TRUNK_TIER);
            }
        }
        // Horizontal edges
        for (int gx = 0; gx < gridSize - 1; gx++) {
            for (int gz = 0; gz < gridSize; gz++) {
                g.connect(grid[gx][gz], grid[gx + 1][gz], TRUNK_TIER,
                        straightLine(level, grid[gx][gz].pos, grid[gx + 1][gz].pos));
            }
        }
        // Vertical edges
        for (int gx = 0; gx < gridSize; gx++) {
            for (int gz = 0; gz < gridSize - 1; gz++) {
                g.connect(grid[gx][gz], grid[gx][gz + 1], TRUNK_TIER,
                        straightLine(level, grid[gx][gz].pos, grid[gx][gz + 1].pos));
            }
        }
        return g;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static BlockPos surfaceAt(ServerLevel level, BlockPos centre, int dx, int dz) {
        int x = centre.getX() + dx;
        int z = centre.getZ() + dz;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    private static List<BlockPos> straightLine(ServerLevel level, BlockPos a, BlockPos b) {
        List<BlockPos> line = new ArrayList<>();
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) { line.add(a); return line; }
        for (int i = 0; i <= steps; i++) {
            int x = a.getX() + dx * i / steps;
            int z = a.getZ() + dz * i / steps;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            line.add(new BlockPos(x, y, z));
        }
        return line;
    }
}