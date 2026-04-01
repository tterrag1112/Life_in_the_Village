package tterrag1112.life_in_the_village.Kingdom.Castle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a CastleLayout (spatial node graph) into a list of RoomStacks.
 *
 * Fixes applied vs previous version:
 *   - Wall panels expose ALL four faces (was: only outward face)
 *   - Gatehouse flanking tower origin uses correct absolute Y (was: additive bug)
 *   - Donjon facing computed toward gatehouse (was: arbitrary node.facing())
 *   - computeOutward now uses the layout origin not from.center() as castle center
 *   - walkwayFloor clamped to minimum 1 to prevent zero-height floors
 */
public class GroundPlanConverter {

    private final CastleStyle style;
    private final TerrainSampler terrain;

    public GroundPlanConverter(CastleStyle style, TerrainSampler terrain) {
        this.style   = style;
        this.terrain = terrain;
    }

    public List<RoomStack> convert(CastleLayout layout, RandomSource rng) {
        List<RoomStack> stacks = new ArrayList<>();

        // 1. Outer wall panels
        for (CastleLayout.WallEdge edge : layout.getOuterWallEdges()) {
            CastleLayout.TowerNode from = layout.resolveFrom(edge);
            CastleLayout.TowerNode to   = layout.resolveTo(edge);

            if (from.role() == CastleLayout.TowerNode.TowerRole.GATEHOUSE
                    || to.role() == CastleLayout.TowerNode.TowerRole.GATEHOUSE) {
                continue;
            }

            RoomStack wall = buildWallPanel(from, to, edge, layout.getOrigin());
            if (wall != null) stacks.add(wall);
        }

        // 2. Outer towers
        for (CastleLayout.TowerNode node : layout.getOuterTowers()) {
            switch (node.role()) {
                case CORNER, FLANKING -> stacks.add(buildTowerStack(node));
                case GATEHOUSE        -> stacks.addAll(buildGatehouseStacks(node));
                default               -> {}
            }
        }

        // 3. Inner ward
        if (layout.hasInnerWard()) {
            for (CastleLayout.WallEdge edge : layout.getInnerWard().edges()) {
                CastleLayout.TowerNode from =
                        layout.getInnerWard().towers().get(edge.fromIndex());
                CastleLayout.TowerNode to   =
                        layout.getInnerWard().towers().get(edge.toIndex());
                RoomStack wall = buildWallPanel(from, to, edge, layout.getOrigin());
                if (wall != null) stacks.add(wall);
            }
            for (CastleLayout.TowerNode node : layout.getInnerWard().towers()) {
                stacks.add(buildTowerStack(node));
            }
        }

        // 4. Donjon
        if (layout.hasDonjon()) {
            stacks.add(buildDonjeonStack(layout.getDonjon(), layout));
        }

        return stacks;
    }

    // =========================================================================
    // Wall panel
    // =========================================================================

    private RoomStack buildWallPanel(CastleLayout.TowerNode from,
                                     CastleLayout.TowerNode to,
                                     CastleLayout.WallEdge edge,
                                     BlockPos castleOrigin) {
        int th    = style.walls().wallThickness();
        int wallH = edge.wallHeight();

        // Start and end at each tower's face, not its center
        BlockPos startFace = offsetToward(from.center(), to.center(), from.radius() + 1);
        BlockPos endFace   = offsetToward(to.center(), from.center(), to.radius() + 1);

        int dx = Math.abs(endFace.getX() - startFace.getX());
        int dz = Math.abs(endFace.getZ() - startFace.getZ());
        boolean xDominant = dx >= dz;

        int panelLength = (int) Math.sqrt((double)dx * dx + (double)dz * dz);
        if (panelLength < 2) return null;

        int groundY = terrain.averageGroundY(startFace, endFace);

        // Clamp walkwayFloor to at least 1 so no zero-height floors exist
        int walkwayFloor = Math.max(1, wallH - CastleConstants.WALKWAY_CLEAR_HEIGHT - 1);
        int upperWall    = Math.max(1,
                wallH - walkwayFloor - CastleConstants.WALKWAY_CLEAR_HEIGHT);

        List<RoomStack.FloorDef> floors = List.of(
                RoomStack.FloorDef.standard(walkwayFloor),
                RoomStack.FloorDef.walkway(CastleConstants.WALKWAY_CLEAR_HEIGHT),
                RoomStack.FloorDef.standard(upperWall)
        );

        // Panel origin: minimum corner of the bounding box
        // xDominant = wall runs east-west: width=panelLength, depth=th
        // !xDominant = wall runs north-south: width=th, depth=panelLength
        BlockPos origin = new BlockPos(
                Math.min(startFace.getX(), endFace.getX()) - (xDominant ? 0 : th / 2),
                groundY,
                Math.min(startFace.getZ(), endFace.getZ()) - (xDominant ? th / 2 : 0));

        // outerWidthX = panelLength or th, outerDepthZ = th or panelLength
        // interior dimensions = outer - 2*th each side
        int outerW = xDominant ? panelLength : th;
        int outerD = xDominant ? th : panelLength;
        int interiorW = outerW - th * 2;
        int interiorD = outerD - th * 2;

        // Outward direction — away from castle center
        Direction outward = computeOutward(startFace, endFace, castleOrigin);

        // ALL four faces exposed — wall panels have no adjacent room on any side.
        // The design rules handle which face gets windows/decoration vs plain fill.
        return new RoomStack(
                origin,
                Math.max(0, interiorW),
                Math.max(0, interiorD),
                th,
                floors,
                RoomStack.StackShape.RECTANGULAR,
                RoomStack.StackRole.OUTER_WALL,
                outward,
                true, true, true, true
        );
    }

    // =========================================================================
    // Tower
    // =========================================================================

    private RoomStack buildTowerStack(CastleLayout.TowerNode node) {
        int groundY = terrain.maxGroundYInRadius(node.center(), node.radius());
        int towerH  = node.wallHeight() + style.towers().towerHeightBonus();

        List<RoomStack.FloorDef> floors = new ArrayList<>();
        int remaining = towerH;
        while (remaining > CastleConstants.FLOOR_INTERVAL) {
            floors.add(RoomStack.FloorDef.standard(CastleConstants.FLOOR_INTERVAL));
            remaining -= CastleConstants.FLOOR_INTERVAL;
        }
        if (remaining > 0) {
            floors.add(RoomStack.FloorDef.standard(remaining));
        }

        int r = node.radius();
        return new RoomStack(
                new BlockPos(node.center().getX() - r, groundY, node.center().getZ() - r),
                0, 0, r,
                floors,
                RoomStack.StackShape.CYLINDRICAL,
                RoomStack.StackRole.TOWER,
                node.facing(),
                true, true, true, true
        );
    }

    // =========================================================================
    // Donjon
    // =========================================================================

    private RoomStack buildDonjeonStack(CastleLayout.TowerNode node,
                                        CastleLayout layout) {
        int groundY = terrain.maxGroundYInRadius(node.center(), node.radius());
        int size    = node.radius();
        int totalH  = node.wallHeight() + style.donjon().donjeonHeightBonus();
        int th      = style.walls().wallThickness();

        // Stepped floors: upper half narrows by 1 block per floor
        int floorCount = Math.max(1, totalH / CastleConstants.FLOOR_INTERVAL);
        List<RoomStack.FloorDef> floors = new ArrayList<>();
        for (int i = 0; i < floorCount; i++) {
            int inset = i >= floorCount / 2 ? (i - floorCount / 2) : 0;
            floors.add(new RoomStack.FloorDef(
                    CastleConstants.FLOOR_INTERVAL, inset, inset, false));
        }
        int capHeight = totalH - floorCount * CastleConstants.FLOOR_INTERVAL;
        if (capHeight > 0) {
            floors.add(RoomStack.FloorDef.standard(capHeight));
        }

        // Face the donjon toward the gatehouse so the entrance aligns
        Direction donjeonFacing = computeDonjeonFacing(node, layout);

        return new RoomStack(
                new BlockPos(node.center().getX() - size, groundY,
                        node.center().getZ() - size),
                Math.max(0, size * 2 - th * 2),
                Math.max(0, size * 2 - th * 2),
                th,
                floors,
                RoomStack.StackShape.RECTANGULAR,
                RoomStack.StackRole.DONJON,
                donjeonFacing,
                true, true, true, true
        );
    }

    /**
     * Computes which direction the donjon faces by pointing it toward the
     * gatehouse. Falls back to SOUTH if no gatehouse exists.
     */
    private Direction computeDonjeonFacing(CastleLayout.TowerNode donjon,
                                           CastleLayout layout) {
        CastleLayout.TowerNode gatehouse = layout.getGatehouse();
        if (gatehouse == null) return Direction.SOUTH;

        int dx = gatehouse.center().getX() - donjon.center().getX();
        int dz = gatehouse.center().getZ() - donjon.center().getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    // =========================================================================
    // Gatehouse
    // =========================================================================

    private List<RoomStack> buildGatehouseStacks(CastleLayout.TowerNode node) {
        List<RoomStack> stacks = new ArrayList<>();
        Direction facing  = node.facing();
        Direction lateral = facing.getClockWise();
        int r       = node.radius();
        int groundY = terrain.groundY(node.center());
        int totalH  = node.wallHeight() + style.towers().towerHeightBonus() + 2;
        int th      = style.walls().wallThickness();

        List<RoomStack.FloorDef> towerFloors = List.of(
                RoomStack.FloorDef.standard(totalH));

        // Left flanking tower — absolute origin, not additive offset
        BlockPos leftCenter  = node.center().relative(lateral,  r + 2);
        BlockPos rightCenter = node.center().relative(lateral, -(r + 2));

        stacks.add(new RoomStack(
                new BlockPos(leftCenter.getX() - r, groundY, leftCenter.getZ() - r),
                0, 0, r, towerFloors,
                RoomStack.StackShape.CYLINDRICAL, RoomStack.StackRole.GATEHOUSE,
                facing, true, true, true, true));

        stacks.add(new RoomStack(
                new BlockPos(rightCenter.getX() - r, groundY, rightCenter.getZ() - r),
                0, 0, r, towerFloors,
                RoomStack.StackShape.CYLINDRICAL, RoomStack.StackRole.GATEHOUSE,
                facing, true, true, true, true));

        // Gate passage — rectangular tunnel connecting the two towers
        // Width spans between the two tower inner faces
        // Depth = wall thickness so it punches through the curtain wall
        int passageWidth = (r + 2) * 2;  // center to center of flanking towers
        int passageH     = node.wallHeight();

        // Origin: left inner face of left tower, at ground level
        // Passage runs along the lateral axis
        boolean lateralIsX = (lateral == Direction.EAST || lateral == Direction.WEST);
        BlockPos passageOrigin;
        if (lateralIsX) {
            passageOrigin = new BlockPos(
                    Math.min(leftCenter.getX(), rightCenter.getX()) - r,
                    groundY,
                    node.center().getZ() - th / 2);
        } else {
            passageOrigin = new BlockPos(
                    node.center().getX() - th / 2,
                    groundY,
                    Math.min(leftCenter.getZ(), rightCenter.getZ()) - r);
        }

        int interiorW = lateralIsX ? passageWidth : 0;
        int interiorD = lateralIsX ? 0 : passageWidth;

        // Only expose the two faces that the gate passage opens through
        // (the faces parallel to the wall, i.e. in the facing direction)
        stacks.add(new RoomStack(
                passageOrigin,
                interiorW, interiorD, th,
                List.of(RoomStack.FloorDef.standard(passageH)),
                RoomStack.StackShape.RECTANGULAR, RoomStack.StackRole.GATEHOUSE,
                facing,
                facing == Direction.NORTH,   // north face exposed if facing north/south
                facing == Direction.SOUTH,
                facing == Direction.EAST,
                facing == Direction.WEST));

        return stacks;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private BlockPos offsetToward(BlockPos from, BlockPos toward, int distance) {
        double dx  = toward.getX() - from.getX();
        double dz  = toward.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len == 0) return from;
        return from.offset(
                (int) Math.round(dx / len * distance), 0,
                (int) Math.round(dz / len * distance));
    }

    /**
     * Computes the outward-facing direction of a wall panel relative to
     * the castle center. Uses the midpoint of the panel vs the castle origin.
     */
    private Direction computeOutward(BlockPos start, BlockPos end,
                                     BlockPos castleOrigin) {
        int mx = (start.getX() + end.getX()) / 2;
        int mz = (start.getZ() + end.getZ()) / 2;
        int dx = mx - castleOrigin.getX();
        int dz = mz - castleOrigin.getZ();
        if (Math.abs(dx) >= Math.abs(dz))
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}