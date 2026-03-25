// src/main/java/tterrag1112/life_in_the_village/Village/Planning/VillageLayout.java
package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Holds the planned layout for a village before any blocks are placed.
 *
 * <h3>Capital extensions</h3>
 * When a capital is planned by {@link CapitalLayoutPlanner}, two extra
 * fields are populated:
 * <ul>
 *   <li>{@link #capitalStreetGraph} — the abstract street network used by
 *       {@link tterrag1112.life_in_the_village.Village.Decoration.VillageDecorator}
 *       to place actual road blocks after buildings are spawned.</li>
 *   <li>{@link #gatePositions} — the outer-ring node positions where the
 *       city perimeter wall will have gates. Trade routes terminate here
 *       instead of routing through the city to the town square.</li>
 * </ul>
 */
public class VillageLayout {

    private final TerrainProfile       terrain;
    private final LayoutDensityProfile density;
    private final List<LayoutSlot>     slots = new ArrayList<>();

    /** Centre of the planned village (usually the town hall position). */
    private BlockPos center;

    /** Abstract street graph — non-null only for capitals. */
    private CapitalStreetGraph capitalStreetGraph = null;

    /**
     * Positions of city gates on the outer perimeter ring.
     * Trade roads terminate here for capitals when
     * {@link tterrag1112.life_in_the_village.Village.VillageTypeData.CapitalProfile#gateRoads()}
     * is true.
     */
    private final List<BlockPos> gatePositions = new ArrayList<>();

    private BlockPos townSquarePos;

    public VillageLayout(TerrainProfile terrain, LayoutDensityProfile density) {
        this.terrain = terrain;
        this.density = density;
        this.center  = terrain.origin();
    }

    // =========================================================================
    // Slot management
    // =========================================================================

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

    /**
     * Adds a slot unconditionally, bypassing the overlap check.
     * Used for city-block face plots (geometrically pre-validated),
     * path nodes, and decorations that are allowed to be close together.
     */
    public void addForced(LayoutSlot slot) {
        slots.add(slot);
    }

    // =========================================================================
    // Filtered views
    // =========================================================================

    public List<LayoutSlot> buildings() {
        return slots.stream()
                .filter(s -> s.getSlotType() == LayoutSlot.SlotType.BUILDING)
                .collect(Collectors.toList());
    }

    public List<LayoutSlot> farmPlots() {
        return slots.stream()
                .filter(s -> s.getSlotType() == LayoutSlot.SlotType.FARM_PLOT)
                .collect(Collectors.toList());
    }

    public List<LayoutSlot> decorations() {
        return slots.stream()
                .filter(s -> s.getSlotType() == LayoutSlot.SlotType.DECORATION)
                .collect(Collectors.toList());
    }

    public List<LayoutSlot> pathNodes() {
        return slots.stream()
                .filter(s -> s.getSlotType() == LayoutSlot.SlotType.PATH_NODE)
                .collect(Collectors.toList());
    }

    // =========================================================================
    // Accessors — core
    // =========================================================================

    public TerrainProfile       getTerrain()           { return terrain;      }
    public LayoutDensityProfile getDensity()           { return density;      }
    public List<LayoutSlot>     getAllSlots()           { return slots;        }
    public BlockPos             getCenter()            { return center;       }
    public void                 setCenter(BlockPos c)  { center = c;          }

    public BlockPos  getTownSquarePos()               { return townSquarePos; }
    public void      setTownSquarePos(BlockPos pos)   { townSquarePos = pos;  }

    // =========================================================================
    // Accessors — capital street graph
    // =========================================================================

    public CapitalStreetGraph getCapitalStreetGraph()             { return capitalStreetGraph;        }
    public void               setCapitalStreetGraph(CapitalStreetGraph g) { capitalStreetGraph = g;  }
    public boolean            hasCapitalStreetGraph()             { return capitalStreetGraph != null; }

    // =========================================================================
    // Accessors — gate positions
    // =========================================================================

    /**
     * Adds a city gate position to the layout.
     * Called by {@link CapitalLayoutPlanner} for each spoke tip on the
     * outer ring. Trade routes use these positions as their endpoint for
     * capitals so roads terminate at the gate, not the town square.
     */
    public void addGatePosition(BlockPos pos) {
        gatePositions.add(pos);
    }

    /** Returns an unmodifiable view of all registered gate positions. */
    public List<BlockPos> getGatePositions() {
        return Collections.unmodifiableList(gatePositions);
    }

    /**
     * Finds the nearest gate position to the given world XZ coordinate,
     * or returns null if no gate positions are registered.
     */
    public BlockPos nearestGate(int x, int z) {
        BlockPos best  = null;
        double   bestD = Double.MAX_VALUE;
        for (BlockPos gate : gatePositions) {
            double dx = gate.getX() - x, dz = gate.getZ() - z;
            double d  = dx * dx + dz * dz;
            if (d < bestD) { bestD = d; best = gate; }
        }
        return best;
    }

    // =========================================================================
    // Debug
    // =========================================================================

    @Override
    public String toString() {
        return "VillageLayout[buildings=" + buildings().size()
                + ", farms="       + farmPlots().size()
                + ", decorations=" + decorations().size()
                + ", suitability=" + String.format("%.2f", terrain.suitability())
                + ", density="     + density.label()
                + (hasCapitalStreetGraph()
                ? ", capital gates=" + gatePositions.size() : "")
                + "]";
    }
}