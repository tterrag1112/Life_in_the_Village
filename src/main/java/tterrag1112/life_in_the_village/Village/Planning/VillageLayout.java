package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Holds the planned layout for a village before any blocks are placed.
 *
 * <h3>Key change: footprint-aware overlap</h3>
 * {@link #tryAdd(LayoutSlot)} uses actual structure footprint dimensions
 * (width × length) for AABB overlap testing instead of circular radius
 * checks. PATH_NODE and DECORATION slots are excluded from building
 * overlap tests — only building slots block other buildings.
 */
public class VillageLayout {

    private final TerrainProfile       terrain;
    private final LayoutDensityProfile density;
    private final List<LayoutSlot>     slots = new ArrayList<>();

    private BlockPos center;
    private CapitalStreetGraph capitalStreetGraph = null;
    private final List<BlockPos> gatePositions = new ArrayList<>();
    private BlockPos townSquarePos;

    /** Minimum gap in blocks between building edges for AABB overlap. */
    public static final int MIN_BUILDING_GAP = 4;

    public VillageLayout(TerrainProfile terrain, LayoutDensityProfile density) {
        this.terrain = terrain;
        this.density = density;
        this.center  = terrain.origin();
    }

    // =========================================================================
    // Slot management
    // =========================================================================

    /**
     * Tries to add a slot, rejecting it if it overlaps an existing building.
     *
     * <p>Only BUILDING slots are tested against each other. PATH_NODE and
     * DECORATION slots never block building placement. FARM_PLOT slots
     * block other farm plots and buildings, but not decorations.</p>
     *
     * <p>When both slots have footprint dimensions set, uses AABB overlap
     * with {@link #MIN_BUILDING_GAP}. Otherwise falls back to radius circles.</p>
     *
     * @return true if the slot was accepted, false if it overlaps
     */
    public boolean tryAdd(LayoutSlot slot) {
        for (LayoutSlot existing : slots) {
            if (!shouldCheckOverlap(slot, existing)) continue;
            if (slotsOverlap(slot, existing)) return false;
        }
        slots.add(slot);
        return true;
    }

    /**
     * Adds a slot unconditionally, bypassing the overlap check.
     */
    public void addForced(LayoutSlot slot) {
        slots.add(slot);
    }

    private boolean shouldCheckOverlap(LayoutSlot candidate, LayoutSlot existing) {
        // PATH_NODE never blocks anything
        if (existing.getSlotType() == LayoutSlot.SlotType.PATH_NODE) return false;

        // DECORATION only blocks other decorations, not buildings
        if (existing.getSlotType() == LayoutSlot.SlotType.DECORATION
                && candidate.getSlotType() != LayoutSlot.SlotType.DECORATION) return false;

        // Buildings block buildings and are blocked by buildings
        if (candidate.getSlotType() == LayoutSlot.SlotType.BUILDING) {
            return existing.getSlotType() == LayoutSlot.SlotType.BUILDING;
        }

        // Farm plots block other farm plots and buildings
        if (candidate.getSlotType() == LayoutSlot.SlotType.FARM_PLOT) {
            return existing.getSlotType() == LayoutSlot.SlotType.FARM_PLOT
                    || existing.getSlotType() == LayoutSlot.SlotType.BUILDING;
        }

        return candidate.getSlotType() == existing.getSlotType();
    }

    private boolean slotsOverlap(LayoutSlot a, LayoutSlot b) {
        int aW = a.getFootprintWidth();
        int aL = a.getFootprintLength();
        int bW = b.getFootprintWidth();
        int bL = b.getFootprintLength();

        if (aW > 0 && aL > 0 && bW > 0 && bL > 0) {
            return footprintOverlap(
                    a.getPos(), aW, aL,
                    b.getPos(), bW, bL,
                    MIN_BUILDING_GAP);
        }
        return a.overlaps(b);
    }

    static boolean footprintOverlap(BlockPos aPos, int aW, int aL,
                                    BlockPos bPos, int bW, int bL,
                                    int gap) {
        int aMinX = aPos.getX() - aW / 2 - gap;
        int aMaxX = aPos.getX() + aW / 2 + gap;
        int aMinZ = aPos.getZ() - aL / 2 - gap;
        int aMaxZ = aPos.getZ() + aL / 2 + gap;

        int bMinX = bPos.getX() - bW / 2;
        int bMaxX = bPos.getX() + bW / 2;
        int bMinZ = bPos.getZ() - bL / 2;
        int bMaxZ = bPos.getZ() + bL / 2;

        return aMinX < bMaxX && aMaxX > bMinX
                && aMinZ < bMaxZ && aMaxZ > bMinZ;
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

    public List<LayoutSlot> buildingSlotsCopy() {
        return new ArrayList<>(buildings());
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    public TerrainProfile       getTerrain()           { return terrain; }
    public LayoutDensityProfile getDensity()           { return density; }
    public List<LayoutSlot>     getAllSlots()           { return slots; }
    public BlockPos             getCenter()            { return center; }
    public void                 setCenter(BlockPos c)  { center = c; }
    public BlockPos  getTownSquarePos()               { return townSquarePos; }
    public void      setTownSquarePos(BlockPos pos)   { townSquarePos = pos; }

    public CapitalStreetGraph getCapitalStreetGraph()                      { return capitalStreetGraph; }
    public void               setCapitalStreetGraph(CapitalStreetGraph g)  { capitalStreetGraph = g; }
    public boolean            hasCapitalStreetGraph()                      { return capitalStreetGraph != null; }

    public void addGatePosition(BlockPos pos)    { gatePositions.add(pos); }
    public List<BlockPos> getGatePositions()      { return Collections.unmodifiableList(gatePositions); }

    public BlockPos nearestGate(int x, int z) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos gate : gatePositions) {
            double dx = gate.getX() - x, dz = gate.getZ() - z;
            double d = dx * dx + dz * dz;
            if (d < bestD) { bestD = d; best = gate; }
        }
        return best;
    }

    @Override
    public String toString() {
        return "VillageLayout[buildings=" + buildings().size()
                + ", farms=" + farmPlots().size()
                + ", decorations=" + decorations().size()
                + ", suitability=" + String.format("%.2f", terrain.suitability())
                + ", density=" + density.label()
                + (hasCapitalStreetGraph()
                ? ", capital gates=" + gatePositions.size() : "")
                + "]";
    }
}