package tterrag1112.life_in_the_village.Kingdom.Castle;


import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure data structure describing the castle's spatial plan.
 * Contains no world references and performs no block placement.
 *
 * Produced by CastleLayoutGenerator, consumed by CastleBuilder.
 * Can be serialized, cached, or previewed independently.
 */
public class CastleLayout {

    // -------------------------------------------------------------------------
    // Tower node
    // -------------------------------------------------------------------------

    /**
     * A single tower position in the castle plan.
     * Role affects what gets placed here (e.g. gatehouse vs corner tower).
     */
    public record TowerNode(
            BlockPos center,
            TowerRole role,
            int radius,         // half-width for square, actual radius for round
            int wallHeight,     // height of the adjacent wall (tower will be higher)
            Direction facing    // outward-facing direction for gatehouses
    ) {
        public enum TowerRole {
            CORNER,
            GATEHOUSE,         // main entrance tower — only one per castle
            POSTERN,           // secondary gate
            FLANKING,          // mid-wall tower (not at a corner)
            DONJON             // the central keep node
        }
    }

    // -------------------------------------------------------------------------
    // Wall edge
    // -------------------------------------------------------------------------

    /**
     * A wall segment connecting two TowerNodes.
     * from/to are indices into the towerNodes list.
     */
    public record WallEdge(
            int fromIndex,
            int toIndex,
            int wallHeight,
            boolean hasWalkway   // whether a wall walkway should be generated
    ) {}

    // -------------------------------------------------------------------------
    // Inner ward (optional concentric ring)
    // -------------------------------------------------------------------------

    /**
     * A secondary inner wall layout. Null when hasInnerWard is false.
     * Has its own towers and edges but no donjon (the outer donjon serves both).
     */
    public record InnerWard(
            List<TowerNode> towers,
            List<WallEdge> edges
    ) {}

    // -------------------------------------------------------------------------
    // Moat
    // -------------------------------------------------------------------------

    /**
     * Axis-aligned bounding box for moat excavation.
     * The moat is carved as a ring around this box.
     */
    public record MoatBounds(
            BlockPos min,
            BlockPos max,
            int width,
            int depth
    ) {}

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final BlockPos origin;                          // center of the castle
    private final List<TowerNode> outerTowers;              // in clockwise order
    private final List<WallEdge> outerWallEdges;
    private final TowerNode donjon;                         // nullable
    private final InnerWard innerWard;                      // nullable
    private final MoatBounds moatBounds;                    // nullable
    private final int nominalWallHeight;                    // baseline for this layout

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public CastleLayout(
            BlockPos origin,
            List<TowerNode> outerTowers,
            List<WallEdge> outerWallEdges,
            TowerNode donjon,
            InnerWard innerWard,
            MoatBounds moatBounds,
            int nominalWallHeight
    ) {
        this.origin = origin;
        this.outerTowers = Collections.unmodifiableList(new ArrayList<>(outerTowers));
        this.outerWallEdges = Collections.unmodifiableList(new ArrayList<>(outerWallEdges));
        this.donjon = donjon;
        this.innerWard = innerWard;
        this.moatBounds = moatBounds;
        this.nominalWallHeight = nominalWallHeight;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public BlockPos getOrigin()                   { return origin; }
    public List<TowerNode> getOuterTowers()        { return outerTowers; }
    public List<WallEdge> getOuterWallEdges()      { return outerWallEdges; }
    public boolean hasDonjon()                     { return donjon != null; }
    public TowerNode getDonjon()                   { return donjon; }
    public boolean hasInnerWard()                  { return innerWard != null; }
    public InnerWard getInnerWard()                { return innerWard; }
    public boolean hasMoat()                       { return moatBounds != null; }
    public MoatBounds getMoatBounds()              { return moatBounds; }
    public int getNominalWallHeight()              { return nominalWallHeight; }

    /**
     * Resolve a TowerNode from an edge's index reference.
     */
    public TowerNode resolveFrom(WallEdge edge) {
        return outerTowers.get(edge.fromIndex());
    }

    public TowerNode resolveTo(WallEdge edge) {
        return outerTowers.get(edge.toIndex());
    }

    /**
     * Convenience: get the single gatehouse tower, or null if none.
     */
    public TowerNode getGatehouse() {
        return outerTowers.stream()
                .filter(t -> t.role() == TowerNode.TowerRole.GATEHOUSE)
                .findFirst()
                .orElse(null);
    }

    /**
     * Approximate AABB of the entire castle footprint (not including moat).
     * Useful for region locking, spawn exclusion, etc.
     */
    public record BoundingBox(BlockPos min, BlockPos max) {
        public boolean contains(BlockPos pos) {
            return pos.getX() >= min.getX() && pos.getX() <= max.getX()
                    && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
        }
    }

    public BoundingBox getApproximateBounds() {
        if (outerTowers.isEmpty()) {
            return new BoundingBox(origin, origin);
        }
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (TowerNode t : outerTowers) {
            minX = Math.min(minX, t.center().getX() - t.radius());
            minZ = Math.min(minZ, t.center().getZ() - t.radius());
            maxX = Math.max(maxX, t.center().getX() + t.radius());
            maxZ = Math.max(maxZ, t.center().getZ() + t.radius());
        }
        return new BoundingBox(
                new BlockPos(minX, origin.getY(), minZ),
                new BlockPos(maxX, origin.getY() + nominalWallHeight + 10, maxZ)
        );
    }
}