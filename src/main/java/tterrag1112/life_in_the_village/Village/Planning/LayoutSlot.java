// src/main/java/tterrag1112/life_in_the_village/Village/Planning/LayoutSlot.java
package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.BuildingVariant;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;

/**
 * A single planned position in a {@link VillageLayout}.
 *
 * <h3>Rotation</h3>
 * Each building slot stores the rotation it will be placed at. The
 * planner decides rotation BEFORE asking {@link StructureSizeCache}
 * for footprint dimensions, so width and length are always reported
 * after rotation has been applied. This eliminates the long-standing
 * bug where 90°/270° rotations swapped W and L under the planner.
 *
 * <h3>Overlap checking</h3>
 * Uses XZ AABB comparison rather than Chebyshev distance on centres.
 * This correctly handles rectangular buildings where a long narrow
 * structure could pass the Chebyshev check but still physically
 * overlap a neighbour.
 *
 * <p>Each slot stores its footprint as half-width and half-length
 * derived from the actual structure template size at the slot's
 * rotation. The planner sets these from {@link StructureSizeCache};
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

    // ── Core fields ──────────────────────────────────────────────────────────

    private final SlotType     slotType;
    private       BlockPos     pos;
    private final BuildingType buildingType;
    private final String       structurePath;

    private int padY = 0;

    public int getPadY() { return padY; }
    public void setPadY(int padY) { this.padY = padY; }
    private int clusterId = -1;
    public int getClusterId() { return clusterId; }
    public void setClusterId(int id) { this.clusterId = id; }



    /** Rotation that will be applied at placement. Defaults to NONE. */
    private Rotation rotation = Rotation.NONE;

    /** Half the X span of the rotated footprint, in blocks. */
    private int halfW;

    /** Half the Z span of the rotated footprint, in blocks. */
    private int halfL;

    /** Full rotated footprint width and length. 0 = unset. */
    private int footprintWidth  = 0;
    private int footprintLength = 0;

    /**
     * Chosen variant identifier (P0a-06). Defaults to the type's
     * default variant id ({@code type.name().toLowerCase()}); the
     * matcher overwrites it when {@link
     * tterrag1112.life_in_the_village.Village.Decoration.Variants
     * .VariantSelector} picks a non-default variant. Persistence on
     * the {@link tterrag1112.life_in_the_village.Village.Building}
     * record is P0a-08; the field lives on the slot for now so the
     * matcher → spawner handoff is local.
     */
    private String variantId;

    /**
     * Chosen variant style folder. Defaults to {@link Style#RURAL}
     * to match the post-P0a-01 layout for slots constructed before
     * variant selection runs.
     */
    private Style style = Style.RURAL;

    /** Gap added outside the footprint before considering overlap. */
    private static final int GAP = StructureSizeCache.MIN_GAP;

    // =========================================================================
    // Constructors
    // =========================================================================

    /** Building slot with default rotation NONE. */
    public LayoutSlot(BlockPos pos, BuildingType buildingType,
                      String structurePath, int radius) {
        this.slotType      = SlotType.BUILDING;
        this.pos           = pos;
        this.buildingType  = buildingType;
        this.structurePath = structurePath;
        this.halfW         = radius;
        this.halfL         = radius;
        this.variantId     = buildingType != null
                ? BuildingVariant.defaultVariantId(buildingType) : null;
    }

    /** Building slot with an explicit rotation. */
    public LayoutSlot(BlockPos pos, BuildingType buildingType,
                      String structurePath, int radius,
                      Rotation rotation) {
        this(pos, buildingType, structurePath, radius);
        this.rotation = rotation != null ? rotation : Rotation.NONE;
    }

    /** Generic constructor for non-building slots (farm, decoration, path). */
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
     * Records the rotated footprint dimensions on this slot. Called by
     * the planner after consulting {@link StructureSizeCache} with the
     * slot's chosen rotation.
     */
    public void setFootprint(int width, int length) {
        this.footprintWidth  = width;
        this.footprintLength = length;
        this.halfW = Math.max(1, width  / 2);
        this.halfL = Math.max(1, length / 2);
    }

    // =========================================================================
    // Overlap check — XZ AABB
    // =========================================================================

    /**
     * Returns true if this slot's footprint (plus gap) overlaps
     * {@code other}'s footprint (plus gap) in the XZ plane.
     *
     * <p>Y is intentionally ignored — buildings at different elevations
     * on the same XZ still conflict because the terrain between them
     * would be claimed by both footprints.
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
    public int         getFootprintWidth()  { return footprintWidth; }
    public int         getFootprintLength() { return footprintLength; }
    public Rotation    getRotation()        { return rotation;       }

    public void setRotation(Rotation rotation) {
        this.rotation = rotation != null ? rotation : Rotation.NONE;
    }

    public String  getVariantId() { return variantId; }
    public void    setVariantId(String variantId) { this.variantId = variantId; }
    public Style   getStyle()     { return style; }
    public void    setStyle(Style style) {
        this.style = style != null ? style : Style.RURAL;
    }

    /**
     * Legacy radius accessor — returns the larger of halfW and halfL.
     * Used by code that still expects a single radius value.
     */
    public int getRadius() { return Math.max(halfW, halfL); }

    public int frontFaceLength() {
        return footprintWidth > 0 ? footprintWidth : halfW * 2;
    }

    public void snapY(int newY) {
        this.pos = new BlockPos(pos.getX(), newY, pos.getZ());
    }

    @Override
    public String toString() {
        return slotType + "@" + pos
                + (buildingType != null ? "(" + buildingType + ")" : "")
                + "[" + halfW * 2 + "x" + halfL * 2 + "]"
                + (rotation != Rotation.NONE ? " " + rotation.name() : "");
    }

    // =========================================================================
    // Backwards-compatibility shim — LayoutSlotWithRotation
    //
    // Past callers (notably CapitalLayoutPlanner) still construct this
    // subclass. It now just delegates to the rotation field on the base
    // class. New code should use the (pos, type, path, radius, rotation)
    // constructor on LayoutSlot directly.
    // =========================================================================

    public static class LayoutSlotWithRotation extends LayoutSlot {

        public LayoutSlotWithRotation(BlockPos pos,
                                      BuildingType buildingType,
                                      String structurePath,
                                      int radius,
                                      Rotation rotation) {
            super(pos, buildingType, structurePath, radius, rotation);
        }

        /** Returns the pre-determined rotation for this slot. */
        public Rotation getPresetRotation() { return getRotation(); }
    }
}