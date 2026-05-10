package tterrag1112.life_in_the_village.Village.Planning.V2.Debug;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer5.OverlapAuditor;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer5.TerrainAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Track E1 follow-up — V2 Layer 5 realization-delta accumulator.
 *
 * <p>Strategy (per locked design pass): each Layer 5 step keeps
 * its existing return; the V2 adapter aggregates into this log
 * before passing it to {@code LayoutDumpSerializer}. No Layer 5
 * step's signature changes.
 *
 * <p>The log is a plain mutable bag. The V2 adapter creates one
 * per spawn, appends as Layer 5 progresses, and hands it to the
 * serializer at the end. Each accumulator method is null-safe so
 * abort branches with partial state can pass a partially-filled
 * log.
 *
 * <h3>Captured deltas</h3>
 * <ul>
 *   <li>{@link OverlapAuditor.OverlapReport} from Layer 5a.</li>
 *   <li>Per-building {@link TerrainAdapter.AdaptationDecision}
 *       from Layer 5b — mode (LEVEL / PLATFORM / DROP) + targetY
 *       + reason. Drives both per-building realization fields
 *       and the {@code realization.pads[]} array.</li>
 *   <li>{@link ViabilityValidator.ViabilityCheck} from Layer 5c.</li>
 *   <li>Vegetation cell counts from {@code VegetationClearer.clear*}
 *       calls — aggregate totals + per-source counts.</li>
 *   <li>NBT placement errors from {@code BuildingPlacer.placeAndRegister}
 *       returning empty.</li>
 *   <li>Per-spawn abort reason (free-form string).</li>
 * </ul>
 */
public final class RealizationLog {

    /** Track D3.6 style: explicit final-vs-mutable separation. */
    private OverlapAuditor.OverlapReport overlapReport;
    private final List<TerrainAdapter.AdaptationDecision> terrainDecisions = new ArrayList<>();
    private List<String> viabilityFailureReasons;
    private long vegetationBlocksTotal = 0L;
    private int vegetationByBuildingCount = 0;
    private int vegetationByRoadCount = 0;
    private final List<PlacementError> placementErrors = new ArrayList<>();
    private boolean aborted = false;
    private String abortReason;

    public RealizationLog() {}

    // ── Setters / accumulators ────────────────────────────────────────────

    public void recordOverlap(OverlapAuditor.OverlapReport report) {
        this.overlapReport = report;
    }

    public void recordTerrainDecisions(List<TerrainAdapter.AdaptationDecision> decisions) {
        if (decisions != null) terrainDecisions.addAll(decisions);
    }

    public void recordViabilityFailure(List<String> reasons) {
        this.viabilityFailureReasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public void addBuildingVegetation(int blocksCleared) {
        if (blocksCleared > 0) {
            vegetationBlocksTotal += blocksCleared;
            vegetationByBuildingCount++;
        }
    }

    public void addRoadVegetation(int blocksCleared) {
        if (blocksCleared > 0) {
            vegetationBlocksTotal += blocksCleared;
            vegetationByRoadCount++;
        }
    }

    public void recordPlacementError(BuildingType type, String variantId, String reason) {
        placementErrors.add(new PlacementError(type, variantId, reason));
    }

    public void markAborted(String reason) {
        this.aborted = true;
        this.abortReason = reason;
    }

    // ── Readers ───────────────────────────────────────────────────────────

    public Optional<OverlapAuditor.OverlapReport> overlapReport() {
        return Optional.ofNullable(overlapReport);
    }

    public List<TerrainAdapter.AdaptationDecision> terrainDecisions() {
        return List.copyOf(terrainDecisions);
    }

    public Optional<TerrainAdapter.AdaptationDecision> decisionFor(PlacedBuilding building) {
        if (building == null) return Optional.empty();
        BlockPos centre = building.centre();
        if (centre == null) return Optional.empty();
        for (TerrainAdapter.AdaptationDecision d : terrainDecisions) {
            if (d.building() != null && centre.equals(d.building().centre())) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    public Optional<List<String>> viabilityFailureReasons() {
        return Optional.ofNullable(viabilityFailureReasons);
    }

    public long vegetationBlocksTotal()    { return vegetationBlocksTotal; }
    public int  vegetationByBuildingCount(){ return vegetationByBuildingCount; }
    public int  vegetationByRoadCount()    { return vegetationByRoadCount; }
    public List<PlacementError> placementErrors() { return List.copyOf(placementErrors); }
    public boolean aborted() { return aborted; }
    public Optional<String> abortReason() { return Optional.ofNullable(abortReason); }

    public record PlacementError(BuildingType type, String variantId, String reason) {}
}
