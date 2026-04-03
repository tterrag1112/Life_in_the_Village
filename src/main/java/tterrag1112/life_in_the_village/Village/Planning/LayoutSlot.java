// src/main/java/tterrag1112/life_in_the_village/Village/Planning/LayoutSlot.java
package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

/**
 * A single planned position in a {@link VillageLayout}.
 *
 * <h3>Overlap checking</h3>
 * Uses an XZ AABB (axis-aligned bounding box) comparison rather than
 * Chebyshev distance on centres. This correctly handles rectangular
 * buildings where a long narrow structure could pass the Chebyshev
 * check but still physically overlap a neighbour.
 *
 * <p>Each slot stores its footprint as a half-width and half-length
 * derived from the actual structure template size (via
 * {@link StructureSizeCache}). The planner sets these from the cache;
 * slots without known sizes fall back to
 * {@link StructureSizeCache#DEFAULT_RADIUS} for both dimensions.
 */
public class LayoutSlot {

    public enum SlotType {
        BUILDING,
        FARM_PLOT,
        DECORATION,
        PATH_NODE,
        TRADE_ROAD_START
    }

    private final SlotType     slotType;
    private       BlockPos     pos;
    private final BuildingType buildingType;
    private final String       structurePath;

    /**
     * Half-width (X) of the footprint in blocks.
     * Defaults to {@link StructureSizeCache#DEFAULT_RADIUS}.
     */
    private int halfW;

    /**
     * Half-length (Z) of the footprint in blocks.
     * Defaults to {@link StructureSizeCache#DEFAULT_RADIUS}.
     */
    private int halfL;

    /** Gap added outside the footprint before considering overlap. */
    private static final int GAP = StructureSizeCache.MIN_GAP;

    // ── BUILDING constructor ──────────────────────────────────────────────────

    public LayoutSlot(BlockPos pos, BuildingType buildingType,
                      String structurePath, int radius) {
        this.slotType      = SlotType.BUILDING;
        this.pos           = pos;
        this.buildingType  = buildingType;
        this.structurePath = structurePath;
        this.halfW         = radius;
        this.halfL         = radius;
    }

    // ── Generic constructor (farm, decoration, path) ──────────────────────────

    public LayoutSlot(SlotType slotType, BlockPos pos, int radius) {
        this.slotType      = slotType;
        this.pos           = pos;
        this.buildingType  = null;
        this.structurePath = null;
        this.halfW         = radius;
        this.halfL         = radius;
    }

    // =========================================================================
    // Footprint update
    // =========================================================================

    /**
     * Updates the footprint dimensions from a
     * {@link StructureSizeCache.FootprintInfo}.
     * Called by the planner after loading the template size.
     */



    // =========================================================================
    // Overlap check — XZ AABB
    // =========================================================================

    /**
     * Returns true if this slot's footprint (plus gap) overlaps
     * {@code other}'s footprint (plus gap) in the XZ plane.
     *
     * <p>Y is intentionally ignored — buildings at different
     * elevations on the same XZ still conflict (the terrain between
     * them would be claimed by both footprints).
     *
     * <h3>AABB math</h3>
     * <pre>
     *   thisMinX  = pos.X - halfW - GAP
     *   thisMaxX  = pos.X + halfW + GAP
     *   otherMinX = other.pos.X - other.halfW - GAP
     *   otherMaxX = other.pos.X + other.halfW + GAP
     *   overlap X iff thisMinX <= otherMaxX && thisMaxX >= otherMinX
     *   (and same for Z)
     * </pre>
     */
    public boolean overlaps(LayoutSlot other) {
        // PATH_NODE and DECORATION slots don't block each other
        if ((this.slotType == SlotType.PATH_NODE
                || this.slotType == SlotType.DECORATION)
                && (other.slotType == SlotType.PATH_NODE
                || other.slotType == SlotType.DECORATION)) {
            return false;
        }

        int thisMinX  = this.pos.getX()  - this.halfW  - GAP;
        int thisMaxX  = this.pos.getX()  + this.halfW  + GAP;
        int thisMinZ  = this.pos.getZ()  - this.halfL  - GAP;
        int thisMaxZ  = this.pos.getZ()  + this.halfL  + GAP;

        int otherMinX = other.pos.getX() - other.halfW - GAP;
        int otherMaxX = other.pos.getX() + other.halfW + GAP;
        int otherMinZ = other.pos.getZ() - other.halfL - GAP;
        int otherMaxZ = other.pos.getZ() + other.halfL + GAP;

        return thisMinX <= otherMaxX && thisMaxX >= otherMinX
                && thisMinZ <= otherMaxZ && thisMaxZ >= otherMinZ;
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    public SlotType    getSlotType()      { return slotType;      }
    public BlockPos    getPos()           { return pos;           }
    public BuildingType getBuildingType() { return buildingType;  }
    public String      getStructurePath() { return structurePath; }
    public int         getHalfW()         { return halfW;         }
    public int         getHalfL()         { return halfL;         }
    private int footprintWidth = 0;
    private int footprintLength = 0;
    public void setFootprint(int width, int length) {
        this.footprintWidth = width;
        this.footprintLength = length;
    }
    public int getFootprintWidth()  { return footprintWidth; }
    public int getFootprintLength() { return footprintLength; }



    /**
     * Legacy radius accessor — returns the larger of halfW and halfL.
     * Used by code that still expects a single radius value.
     */
    public int         getRadius()        { return Math.max(halfW, halfL); }

    public void snapY(int newY) {
        this.pos = new BlockPos(pos.getX(), newY, pos.getZ());
    }

    @Override
    public String toString() {
        return slotType + "@" + pos
                + (buildingType != null ? "(" + buildingType + ")" : "")
                + "[" + halfW * 2 + "x" + halfL * 2 + "]";
    }


public static class LayoutSlotWithRotation extends LayoutSlot {

    private final Rotation rotation;

    public LayoutSlotWithRotation(BlockPos pos,
                                  BuildingType buildingType,
                                  String structurePath,
                                  int radius,
                                  Rotation rotation) {
        super(pos, buildingType, structurePath, radius);
        this.rotation = rotation;
    }

    /** Returns the pre-determined rotation for this slot. */
    public Rotation getPresetRotation() { return rotation; }
}
}
