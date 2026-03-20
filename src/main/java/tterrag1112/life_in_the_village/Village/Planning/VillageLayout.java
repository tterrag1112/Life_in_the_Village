// src/main/java/tterrag1112/life_in_the_village/Village/Planning/VillageLayout.java
package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;


public class VillageLayout {

    private final TerrainProfile        terrain;
    private final LayoutDensityProfile  density;
    private final List<LayoutSlot>      slots = new ArrayList<>();
    /** Center of the planned village (usually near the town hall). */
    private BlockPos                    center;

    private BlockPos townSquarePos;


    public VillageLayout(TerrainProfile terrain,
                         LayoutDensityProfile density) {
        this.terrain = terrain;
        this.density = density;
        this.center  = terrain.origin();
    }

    // -------------------------------------------------------------------------
    // Slot management
    // -------------------------------------------------------------------------

    /**
     * Tries to add a slot, rejecting it if it overlaps an existing one.
     * @return true if the slot was accepted.
     */
    public boolean tryAdd(LayoutSlot slot) {
        for (LayoutSlot existing : slots) {
            if (slot.overlaps(existing)) return false;
        }
        slots.add(slot);
        return true;
    }

    /** Adds a slot unconditionally (used for path nodes / decorations
     *  that are allowed to be close together). */
    public void addForced(LayoutSlot slot) {
        slots.add(slot);
    }

    // -------------------------------------------------------------------------
    // Filtered views
    // -------------------------------------------------------------------------

    public List<LayoutSlot> buildings() {
        return slots.stream()
                .filter(s -> s.getSlotType()
                        == LayoutSlot.SlotType.BUILDING)
                .collect(Collectors.toList());
    }

    public List<LayoutSlot> farmPlots() {
        return slots.stream()
                .filter(s -> s.getSlotType()
                        == LayoutSlot.SlotType.FARM_PLOT)
                .collect(Collectors.toList());
    }

    public List<LayoutSlot> decorations() {
        return slots.stream()
                .filter(s -> s.getSlotType()
                        == LayoutSlot.SlotType.DECORATION)
                .collect(Collectors.toList());
    }

    public List<LayoutSlot> pathNodes() {
        return slots.stream()
                .filter(s -> s.getSlotType()
                        == LayoutSlot.SlotType.PATH_NODE)
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public TerrainProfile       getTerrain()  { return terrain;  }
    public LayoutDensityProfile getDensity()  { return density;  }
    public List<LayoutSlot>     getAllSlots()  { return slots;    }
    public BlockPos             getCenter()   { return center;   }
    public void                 setCenter(BlockPos c) { center = c; }

    // -------------------------------------------------------------------------
    // Debug summary
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        long bCount = buildings().size();
        long fCount = farmPlots().size();
        long dCount = decorations().size();
        return "VillageLayout[buildings=" + bCount
                + ", farms=" + fCount
                + ", decorations=" + dCount
                + ", suitability="
                + String.format("%.2f", terrain.suitability())
                + ", density=" + density.label()
                + "]";
    }

    public BlockPos getTownSquarePos()            { return townSquarePos;  }
    public void     setTownSquarePos(BlockPos pos){ townSquarePos = pos;   }
}