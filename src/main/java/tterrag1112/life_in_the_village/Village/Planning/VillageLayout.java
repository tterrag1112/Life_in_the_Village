package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole;
import tterrag1112.life_in_the_village.Village.Planning.Graph.NodeKind;
import tterrag1112.life_in_the_village.Village.Planning.Graph.RoadGraph;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.CenterlineResult;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PrimitiveContext;
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


    // ── Road graph ─────────────────────────────────────────────────────────
    private final RoadGraph roadGraph = new RoadGraph();
    /**
     * Footprint grid of road reservations accumulated as primitives are
     * added. Layout primitives consult this during placement so
     * buildings never land on top of a road centerline.
     */
    private final BuildingFootprint roadFootprint = new BuildingFootprint();

    public BuildingFootprint getRoadFootprint() { return roadFootprint; }

    /**
     * Phase 4: planning-time geometric features (hull, water, cliffs).
     * Stored on the layout so realization can call {@link
     * tterrag1112.life_in_the_village.Village.Planning.Features.FeatureMap#refine}
     * before consumers read it. Phase 19 replaces this transient field
     * with persisted {@code LayoutPlan} state.
     */
    @Nullable private tterrag1112.life_in_the_village.Village
            .Planning.Features.FeatureMap features;

    @Nullable
    public tterrag1112.life_in_the_village.Village.Planning.Features.FeatureMap getFeatures() {
        return features;
    }

    public void setFeatures(
            tterrag1112.life_in_the_village.Village.Planning.Features.FeatureMap f) {
        this.features = f;
    }

    /**
     * Phase 9 debug-only: snapshot of the sectors a BaseRecipe emitted
     * during compose. Captured by VillagePlanner; surfaced via
     * VillageSpawner onto Village.debugSectors so /liv layout debug
     * show_sectors can render them. Phase 19 replaces this transient
     * field with persisted LayoutPlan state.
     */
    @Nullable private transient List<tterrag1112.life_in_the_village.Village
            .Planning.Sectors.Sector> debugSectors;

    @Nullable
    public List<tterrag1112.life_in_the_village.Village.Planning.Sectors.Sector>
            getDebugSectors() {
        return debugSectors;
    }

    public void setDebugSectors(
            List<tterrag1112.life_in_the_village.Village.Planning.Sectors.Sector> s) {
        this.debugSectors = s;
    }

    private BlockPos center;
    private BlockPos townSquarePos;
    private int townSquareRadius = 0;
    @Nullable private BlockPos mainGateEndpoint;

    // ── Phase 16b unplannable signal ────────────────────────────────────────
    // Set by the cascade engine when ABORT or fallback-with-no-shape fires.
    // VillagePlanner reads this after compose to decide whether to log
    // "site unplannable" and skip the matcher / validator. VillageSpawner
    // reads it to skip local refinement (terrain is bad; refinement won't help).
    private boolean unplannable = false;
    @Nullable private String unplannableReason;

    public void markUnplannable(String reason) {
        this.unplannable = true;
        this.unplannableReason = reason;
    }
    public boolean isUnplannable() { return unplannable; }
    @Nullable public String unplannableReason() { return unplannableReason; }

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

    /** Max XZ distance for snapping a gate position to an existing graph node. */
    private static final int GATE_SNAP_SLACK = 8;

    public VillageLayout(TerrainProfile terrain, LayoutDensityProfile density) {
        this.terrain = terrain;
        this.density = density;
        this.center = terrain.origin();
    }

    // =========================================================================
    // Road management
    // =========================================================================

    public RoadGraph getRoadGraph() { return roadGraph; }

    public int addNode(BlockPos pos, NodeKind kind, RoadShape.RoadTier tier) {
        return roadGraph.addNode(pos, kind, tier);
    }

    /** Adds a road primitive and computes+caches its centerline. */
    public List<BlockPos> addRoad(RoadPrimitive primitive, ServerLevel level, long worldSeed) {
        return addRoad(primitive, level, worldSeed, null);
    }

    /**
     * Role-aware variant. Pass {@code role=null} to keep the default-role
     * inference; pass an explicit {@link EdgeRole} (e.g. {@link
     * EdgeRole#OUTER_RING}) to override it. Used when a recipe constructs a
     * Ring/Arc primitive that has a more specific semantic role than the
     * generic RING default.
     */
    public List<BlockPos> addRoad(RoadPrimitive primitive, ServerLevel level,
                                  long worldSeed, EdgeRole role) {
        PrimitiveContext ctx = PrimitiveContext.basic(level, worldSeed);
        CenterlineResult result = primitive.computeCenterline(ctx);
        if (!result.isComplete()) {
            // TODO Phase 17+: handle truncation (insert TERMINUS at
            // result.lastPoint(), chain Bridge/Stairway, abort sector).
            System.out.println("VillageLayout.addRoad: " + primitive.typeKey()
                    + " truncated " + result.reason()
                    + " at " + result.refusedAt());
        }
        List<BlockPos> cl = result.points();
        if (cl.isEmpty()) return cl;

        BlockPos fromPos = cl.get(0);
        BlockPos toPos   = cl.get(cl.size() - 1);
        RoadShape.RoadTier tier = primitive.tier();

        int fromId = roadGraph.addNode(fromPos, NodeKind.TERMINUS, tier);
        int toId   = roadGraph.addNode(toPos,   NodeKind.TERMINUS, tier);

        EdgeRole resolvedRole = role != null ? role : defaultRoleFor(primitive);
        roadGraph.addEdge(fromId, toId, primitive, cl, resolvedRole);

        roadFootprint.reserveRoad(cl, tier.reservedHalfWidth());
        return cl;
    }

    public int addEdge(int fromNodeId, int toNodeId,
                       RoadPrimitive primitive,
                       ServerLevel level,
                       long worldSeed,
                       EdgeRole role) {
        PrimitiveContext ctx = PrimitiveContext.basic(level, worldSeed);
        CenterlineResult result = primitive.computeCenterline(ctx);
        if (!result.isComplete()) {
            // TODO Phase 17+: handle truncation
            System.out.println("VillageLayout.addEdge: " + primitive.typeKey()
                    + " truncated " + result.reason()
                    + " at " + result.refusedAt());
        }
        List<BlockPos> cl = result.points();
        int edgeId = roadGraph.addEdge(fromNodeId, toNodeId, primitive, cl, role);
        if (!cl.isEmpty()) {
            roadFootprint.reserveRoad(cl, primitive.tier().reservedHalfWidth());
        }
        return edgeId;
    }

    private static EdgeRole defaultRoleFor(RoadPrimitive p) {
        if (p instanceof RoadPrimitive.Spur) return EdgeRole.SPUR;
        if (p instanceof RoadPrimitive.Ring || p instanceof RoadPrimitive.Arc) return EdgeRole.RING;
        return EdgeRole.SPINE;
    }

    public List<RoadPrimitive> getRoadPrimitives() {
        List<RoadPrimitive> out = new ArrayList<>();
        for (RoadGraph.Edge e : roadGraph.allEdges()) out.add(e.primitive());
        return Collections.unmodifiableList(out);
    }

    public List<BlockPos> getCenterline(RoadPrimitive primitive) {
        for (RoadGraph.Edge e : roadGraph.allEdges()) {
            if (e.primitive() == primitive) return e.centerline();
        }
        return List.of();
    }

    public Collection<List<BlockPos>> getAllCenterlines() {
        List<List<BlockPos>> out = new ArrayList<>();
        for (RoadGraph.Edge e : roadGraph.allEdges()) out.add(e.centerline());
        return Collections.unmodifiableList(out);
    }

    /** Reserves all road centerlines in a footprint grid. */
    public void reserveRoads(BuildingFootprint footprint) {
        for (RoadGraph.Edge e : roadGraph.allEdges()) {
            if (!e.centerline().isEmpty()) {
                footprint.reserveRoad(e.centerline(), e.primitive().tier().reservedHalfWidth());
            }
        }
    }

    /** Returns the nearest centerline point across all roads, or null if empty. */
    public BlockPos nearestCenterlinePoint(BlockPos target) {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (RoadGraph.Edge e : roadGraph.allEdges()) {
            for (BlockPos p : e.centerline()) {
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

    /**
     * Phase 18 doc 04 §"NPC gathering points" — moved off
     * TownSquareComposer (deleted) onto VillageLayout so the polygon
     * generator can register here and applyLayout copies onto the
     * persisted Village.
     */
    private final List<tterrag1112.life_in_the_village.Village.Decoration
            .TownSquare.GatheringPoint> gatheringPoints = new ArrayList<>();

    public void addGatheringPoint(tterrag1112.life_in_the_village.Village
                                          .Decoration.TownSquare.GatheringPoint gp) {
        if (gp != null) gatheringPoints.add(gp);
    }

    public List<tterrag1112.life_in_the_village.Village.Decoration
            .TownSquare.GatheringPoint> getGatheringPoints() {
        return java.util.Collections.unmodifiableList(gatheringPoints);
    }

    @Nullable public BlockPos getMainGateEndpoint() { return mainGateEndpoint; }
    public void setMainGateEndpoint(@Nullable BlockPos pos) {
        this.mainGateEndpoint = pos;
        if (pos != null) {
            int nodeId = roadGraph.findNearestNode(pos, GATE_SNAP_SLACK);
            if (nodeId >= 0) {
                roadGraph.markAsGate(nodeId);
            }
            // If no nearby node exists, the legacy `mainGateEndpoint` field
            // still works as a fallback — no behavior change for callers that
            // call setMainGateEndpoint before any addRoad.
        }
    }

    public void addGatePosition(BlockPos pos) {
        gatePositions.add(pos);
        int nodeId = roadGraph.findNearestNode(pos, GATE_SNAP_SLACK);
        if (nodeId >= 0) {
            roadGraph.markAsGate(nodeId);
        }
    }
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
                + ", roads=" + roadGraph.edgeCount()
                + ", suitability=" + String.format("%.2f", terrain.suitability())
                + ", density=" + density.label() + "]";
    }
}