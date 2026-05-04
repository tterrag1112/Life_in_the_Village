package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Planning.FarmPlotSpec;
import tterrag1112.life_in_the_village.Village.Planning.Graph.RoadGraph;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.Sector;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 19: builds an immutable {@link LayoutPlan} from a fully-composed
 * {@link VillageLayout}. Called once at the end of
 * {@code VillagePlanner.plan()}.
 *
 * <p>Field access uses the actual accessors on each domain type
 * (the original prompt's pseudocode used names that don't all exist
 * — see {@code ROADS_PROGRESS.md} Phase 19 entry for the corrected
 * mapping).
 */
public final class LayoutPlanBuilder {

    private LayoutPlanBuilder() {}

    public static LayoutPlan build(VillageLayout layout, PlanContext pctx) {
        BlockPos centre = layout.getCenter();

        VillageTypeData.ShapeType finalShape = pctx.finalShape();

        Plaza plaza = layout.getPlaza();
        List<tterrag1112.life_in_the_village.Village.Decoration
                .Plaza.PlazaRegion> plazaRegions =
                layout.getPlazaRegions();

        // ── Roads ──────────────────────────────────────────────────────────
        // RoadGraph.Edge holds (id, fromNodeId, toNodeId, primitive,
        // centerline, role). Resolve node IDs to BlockPos via the graph's
        // node() lookup so the plan is self-contained.
        RoadGraph graph = layout.getRoadGraph();
        List<LayoutPlan.RoadEdge> roads = new ArrayList<>();
        for (RoadGraph.Edge e : graph.allEdges()) {
            BlockPos from = graph.node(e.fromNodeId()).pos();
            BlockPos to   = graph.node(e.toNodeId()).pos();
            roads.add(new LayoutPlan.RoadEdge(
                    e.id(), e.role(), e.primitive().tier(),
                    e.centerline(), from, to));
        }

        // ── Buildings ──────────────────────────────────────────────────────
        // LayoutSlot has (pos, buildingType, footprintWidth, footprintLength,
        // rotation, feedingRoad, ...). Sector attribution lives on
        // pctx.committedSectorIds (Map<BlockPos, String>) — a slot's
        // sectorId is "unknown" when it was claimed from the flat pool or
        // Plaza pool.
        Map<BlockPos, String> committedSectorIds = pctx.committedSectorIds;
        List<LayoutPlan.PlacedBuilding> buildings = new ArrayList<>();
        int slotIndex = 0;
        for (LayoutSlot slot : layout.buildings()) {
            UUID id = deriveStableId(centre, slotIndex++);
            String sectorId = committedSectorIds.getOrDefault(
                    slot.getPos(), "unknown");
            buildings.add(new LayoutPlan.PlacedBuilding(
                    id,
                    slot.getBuildingType(),
                    slot.getPos(),
                    slot.getRotation(),
                    slot.getFootprintWidth(),
                    slot.getFootprintLength(),
                    sectorId,
                    slot.getFeedingRoad() != null
                            ? slot.getFeedingRoad() : List.of()));
        }

        // ── Plot slots ─────────────────────────────────────────────────────
        List<LayoutPlan.PlannedPlot> plots = new ArrayList<>();
        for (PlacementSlot ps : layout.plotSlots()) {
            FarmPlotSpec spec = layout.getPlotSpec(ps);
            if (spec == null) continue;
            plots.add(new LayoutPlan.PlannedPlot(
                    spec.ownerFarmhousePos(),
                    spec.subtype(),
                    ps.pos(),
                    spec.halfW(),
                    spec.halfL(),
                    spec.edgeJitterSeed()));
        }

        // ── Sectors (metadata-only per Phase 19 open-question default) ─────
        List<LayoutPlan.SectorView> sectors = new ArrayList<>();
        for (Sector s : pctx.offeredSectors()) {
            sectors.add(new LayoutPlan.SectorView(
                    s.id(),
                    s.role(),
                    s.zoneHint(),
                    s.slots().size(),
                    s.capacity(),
                    s.parentEdgeId(),
                    s.expectedMaxFootprint()));
        }

        // ── Anchors ───────────────────────────────────────────────────────
        Map<AnchorKind, BlockPos> anchors = new EnumMap<>(AnchorKind.class);
        if (plaza != null) {
            anchors.put(AnchorKind.TOWN_SQUARE, plaza.centre());
        } else if (layout.getTownSquarePos() != null) {
            // ENCLAVE-style courtyard — no Plaza, but legacy field set.
            anchors.put(AnchorKind.TOWN_SQUARE, layout.getTownSquarePos());
        }
        if (layout.getMainGateEndpoint() != null) {
            anchors.put(AnchorKind.MAIN_GATE, layout.getMainGateEndpoint());
        }
        List<BlockPos> gates = layout.getGatePositions();
        if (gates != null && gates.size() > 1) {
            // First gate position is conventionally the main gate (already
            // captured above if MAIN_GATE was set). Use the second one as
            // SECONDARY_GATE for multi-gate recipes.
            for (BlockPos g : gates) {
                if (!g.equals(layout.getMainGateEndpoint())) {
                    anchors.put(AnchorKind.SECONDARY_GATE, g);
                    break;
                }
            }
        }

        // ── Status (Phase 16b doesn't surface a final RecipeStatus on
        // pctx; per the prompt's open-question default, derive it from
        // layout.isUnplannable()). ────────────────────────────────────────
        LayoutPlan.Status status = layout.isUnplannable()
                ? LayoutPlan.Status.ABORT
                : LayoutPlan.Status.OK;

        return new LayoutPlan(
                centre,
                finalShape,
                plaza,
                plazaRegions,
                roads,
                buildings,
                plots,
                sectors,
                anchors,
                status,
                pctx.truncationCount(),
                pctx.cascadeRetryCount(),
                layout.isUnplannable() ? layout.unplannableReason() : null);
    }

    /**
     * Phase 19 open-question (1) default: deterministic from the village
     * centre + slot index. The prompt suggested {@code (villageId,
     * slotIndex)} but no Village UUID exists at planning time
     * (BuildingPlacer.placeAndRegister mints UUIDs at realisation).
     * Centre coordinates are stable per spawn, so this is good enough
     * for cross-system reference; realisation can substitute its own
     * UUID without breaking the plan's immutability.
     */
    private static UUID deriveStableId(BlockPos centre, int slotIndex) {
        long msb = ((long) centre.getX() << 32) ^ centre.getY()
                ^ ((long) slotIndex << 48);
        long lsb = ((long) centre.getZ() << 32) ^ slotIndex;
        return new UUID(msb, lsb);
    }
}
