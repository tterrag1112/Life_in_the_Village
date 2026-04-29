package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Holds the planned layout for a village. After the primitive rewrite,
 * this class is the single source of truth for both buildings and road
 * primitives — the old separate TrunkGraph is gone.
 *
 * <h3>Road storage</h3>
 * Road primitives are added via {@link #addRoad(RoadPrimitive, ServerLevel, long)},
 * which immediately computes and caches the centerline. Callers should
 * use the returned centerline directly rather than recomputing it.
 */
public class VillageLayout {

    private final TerrainProfile terrain;
    private final LayoutDensityProfile density;
    private final List<LayoutSlot> slots = new ArrayList<>();

    private int civicRingRadius = 0;

    public int getCivicRingRadius() { return civicRingRadius; }
    public void setCivicRingRadius(int r) { this.civicRingRadius = r; }


    // ── Road primitives & cached centerlines ───────────────────────────────
    private final List<RoadPrimitive> roadPrimitives = new ArrayList<>();
    private final Map<RoadPrimitive, List<BlockPos>> centerlines = new IdentityHashMap<>();
    /**
     * Footprint grid of road reservations accumulated as primitives are
     * added. Layout primitives consult this during placement so
     * buildings never land on top of a road centerline.
     */
    private final BuildingFootprint roadFootprint = new BuildingFootprint();

    public BuildingFootprint getRoadFootprint() { return roadFootprint; }

    private BlockPos center;
    private BlockPos townSquarePos;
    private int townSquareRadius = 0;
    @Nullable private BlockPos mainGateEndpoint;

    /**
     * Phase 17 doc 04 — polygon plaza registrations. Populated by
     * recipe compose() via PlazaGenerator; copied onto Village in
     * applyLayout. Empty for HAMLETs (which use villageCenterMarker
     * instead) and for legacy / expansion paths.
     */
    private final List<tterrag1112.life_in_the_village.Village
            .Decoration.Plaza.PlazaRegion> plazaRegions = new ArrayList<>();
    @Nullable
    private tterrag1112.life_in_the_village.Village.Decoration
            .Plaza.VillageCenterMarker villageCenterMarker;

    private final List<BlockPos> gatePositions = new ArrayList<>();

    public static final int MIN_BUILDING_GAP = 4;

    public VillageLayout(TerrainProfile terrain, LayoutDensityProfile density) {
        this.terrain = terrain;
        this.density = density;
        this.center = terrain.origin();
    }

    // =========================================================================
    // Road management
    // =========================================================================

    /** Adds a road primitive and computes+caches its centerline. */
    public List<BlockPos> addRoad(RoadPrimitive primitive, ServerLevel level, long worldSeed) {
        List<BlockPos> cl = primitive.computeCenterline(level, worldSeed);
        roadPrimitives.add(primitive);
        centerlines.put(primitive, cl);
        // Reserve the road's blocks so building primitives won't collide
        roadFootprint.reserveRoad(cl, primitive.tier().reservedHalfWidth());
        return cl;
    }

    public List<RoadPrimitive> getRoadPrimitives() {
        return Collections.unmodifiableList(roadPrimitives);
    }

    public List<BlockPos> getCenterline(RoadPrimitive primitive) {
        return centerlines.getOrDefault(primitive, List.of());
    }

    public Collection<List<BlockPos>> getAllCenterlines() {
        return centerlines.values();
    }

    /** Reserves all road centerlines in a footprint grid. */
    public void reserveRoads(BuildingFootprint footprint) {
        for (RoadPrimitive rp : roadPrimitives) {
            List<BlockPos> cl = centerlines.get(rp);
            if (cl != null && !cl.isEmpty()) {
                footprint.reserveRoad(cl, rp.tier().reservedHalfWidth());
            }
        }
    }

    /** Returns the nearest centerline point across all roads, or null if empty. */
    public BlockPos nearestCenterlinePoint(BlockPos target) {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (List<BlockPos> cl : centerlines.values()) {
            for (BlockPos p : cl) {
                double d = p.distSqr(target);
                if (d < bestSq) { bestSq = d; best = p; }
            }
        }
        return best;
    }

    // =========================================================================
    // Slot management (unchanged behaviour)
    // =========================================================================

    public boolean tryAdd(LayoutSlot slot) {
        for (LayoutSlot existing : slots) {
            if (!shouldCheckOverlap(slot, existing)) continue;
            if (slotsOverlap(slot, existing)) return false;
        }
        slots.add(slot);
        return true;
    }

    public void addForced(LayoutSlot slot) { slots.add(slot); }

    private boolean shouldCheckOverlap(LayoutSlot candidate, LayoutSlot existing) {
        if (existing.getSlotType() == LayoutSlot.SlotType.PATH_NODE) return false;
        if (existing.getSlotType() == LayoutSlot.SlotType.DECORATION
                && candidate.getSlotType() != LayoutSlot.SlotType.DECORATION) return false;
        if (candidate.getSlotType() == LayoutSlot.SlotType.BUILDING) {
            return existing.getSlotType() == LayoutSlot.SlotType.BUILDING;
        }
        if (candidate.getSlotType() == LayoutSlot.SlotType.FARM_PLOT) {
            return existing.getSlotType() == LayoutSlot.SlotType.FARM_PLOT
                    || existing.getSlotType() == LayoutSlot.SlotType.BUILDING;
        }
        return candidate.getSlotType() == existing.getSlotType();
    }

    private boolean slotsOverlap(LayoutSlot a, LayoutSlot b) {
        int aW = a.getFootprintWidth(), aL = a.getFootprintLength();
        int bW = b.getFootprintWidth(), bL = b.getFootprintLength();
        if (aW > 0 && aL > 0 && bW > 0 && bL > 0) {
            return footprintOverlap(a.getPos(), aW, aL, b.getPos(), bW, bL, MIN_BUILDING_GAP);
        }
        return a.overlaps(b);
    }

    public static boolean footprintOverlap(BlockPos aPos, int aW, int aL,
                                           BlockPos bPos, int bW, int bL, int gap) {
        int aMinX = aPos.getX() - aW / 2 - gap, aMaxX = aPos.getX() + aW / 2 + gap;
        int aMinZ = aPos.getZ() - aL / 2 - gap, aMaxZ = aPos.getZ() + aL / 2 + gap;
        int bMinX = bPos.getX() - bW / 2, bMaxX = bPos.getX() + bW / 2;
        int bMinZ = bPos.getZ() - bL / 2, bMaxZ = bPos.getZ() + bL / 2;
        return aMinX < bMaxX && aMaxX > bMinX && aMinZ < bMaxZ && aMaxZ > bMinZ;
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
    public List<LayoutSlot> buildingSlotsCopy() { return new ArrayList<>(buildings()); }

    // =========================================================================
    // Accessors
    // =========================================================================

    public TerrainProfile getTerrain() { return terrain; }
    public LayoutDensityProfile getDensity() { return density; }
    public List<LayoutSlot> getAllSlots() { return slots; }
    public BlockPos getCenter() { return center; }
    public void setCenter(BlockPos c) { center = c; }

    public BlockPos getTownSquarePos() { return townSquarePos; }
    public void setTownSquarePos(BlockPos pos) { townSquarePos = pos; }

    public int getTownSquareRadius() { return townSquareRadius; }
    public void setTownSquareRadius(int r) { townSquareRadius = r; }

    // ── Plaza polygon accessors (Phase 17 doc 04) ──────────────────────

    public void addPlazaRegion(tterrag1112.life_in_the_village.Village
                                       .Decoration.Plaza.PlazaRegion p) {
        if (p != null) plazaRegions.add(p);
    }

    public List<tterrag1112.life_in_the_village.Village.Decoration
            .Plaza.PlazaRegion> getPlazaRegions() {
        return java.util.Collections.unmodifiableList(plazaRegions);
    }

    public void setVillageCenterMarker(@Nullable tterrag1112.life_in_the_village
            .Village.Decoration.Plaza.VillageCenterMarker m) {
        this.villageCenterMarker = m;
    }

    @Nullable
    public tterrag1112.life_in_the_village.Village.Decoration
            .Plaza.VillageCenterMarker getVillageCenterMarker() {
        return villageCenterMarker;
    }

    @Nullable public BlockPos getMainGateEndpoint() { return mainGateEndpoint; }
    public void setMainGateEndpoint(@Nullable BlockPos pos) { mainGateEndpoint = pos; }

    public void addGatePosition(BlockPos pos) { gatePositions.add(pos); }
    public List<BlockPos> getGatePositions() { return Collections.unmodifiableList(gatePositions); }

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
                + ", roads=" + roadPrimitives.size()
                + ", suitability=" + String.format("%.2f", terrain.suitability())
                + ", density=" + density.label() + "]";
    }
}