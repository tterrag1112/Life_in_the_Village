package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

/**
 * A single resolved position in the village layout plan.
 */
public class LayoutSlot {

    public enum SlotType {
        BUILDING,
        FARM_PLOT,
        DECORATION,
        PATH_NODE,
        TRADE_ROAD_START
    }

    private final SlotType    slotType;
    private BlockPos   pos;
    /** Non-null when slotType == BUILDING */
    private final BuildingType buildingType;
    /** Non-null when slotType == BUILDING */
    private final String       structurePath;
    /** Estimated footprint radius — used for overlap avoidance */
    private final int          radius;

    // BUILDING constructor
    public LayoutSlot(BlockPos pos, BuildingType buildingType,
                      String structurePath, int radius) {
        this.slotType     = SlotType.BUILDING;
        this.pos          = pos;
        this.buildingType = buildingType;
        this.structurePath= structurePath;
        this.radius       = radius;
    }

    // Generic constructor (farm, decoration, path)
    public LayoutSlot(SlotType slotType, BlockPos pos, int radius) {
        this.slotType     = slotType;
        this.pos          = pos;
        this.buildingType = null;
        this.structurePath= null;
        this.radius       = radius;
    }

    // -------------------------------------------------------------------------
    // Overlap check
    // -------------------------------------------------------------------------

    public boolean overlaps(LayoutSlot other) {
        int minDist = this.radius + other.radius + 2; // 2-block gap
        int dx = Math.abs(this.pos.getX() - other.pos.getX());
        int dz = Math.abs(this.pos.getZ() - other.pos.getZ());
        // Chebyshev distance on XZ only
        return Math.max(dx, dz) < minDist;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public SlotType    getSlotType()     { return slotType;      }
    public BlockPos    getPos()          { return pos;           }
    public BuildingType getBuildingType(){ return buildingType;  }
    public String      getStructurePath(){ return structurePath; }
    public int         getRadius()       { return radius;        }

    @Override
    public String toString() {
        return slotType + "@" + pos
                + (buildingType != null ? "(" + buildingType + ")" : "");
    }

    public void snapY(int newY) {
        this.pos = new BlockPos(pos.getX(), newY, pos.getZ());
    }
}