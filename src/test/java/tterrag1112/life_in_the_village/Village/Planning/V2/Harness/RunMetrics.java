package tterrag1112.life_in_the_village.Village.Planning.V2.Harness;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DropReason;

import java.util.Map;

/**
 * Track E1 — the seven metrics computed per battery run, plus the
 * minimal identifying header. All numbers come from data the planner
 * already produces; no new instrumentation inside Layers 1-5.
 *
 * <p>{@link #aborted} is set when the planner produced no viable
 * village (placement.placed empty or villageViable=false). In that
 * case the geometric metrics (compactness, frontage, connectivity,
 * clustering) are reported as {@code Double.NaN} so the diff renderer
 * can show "-" without mistaking absence for a value of zero.
 */
public record RunMetrics(
        String terrain,
        String configLabel,
        long seed,
        boolean aborted,
        int requested,
        int placed,
        int dropped,
        double placedRate,
        Map<BuildingType, Integer> requestedPerType,
        Map<BuildingType, Integer> placedPerType,
        Map<BuildingType, Double> placedRatePerType,
        double compactness,
        double fracBuildingsOnNetwork,
        double roadCoverage,
        int networkComponents,
        double fracBuildingsOnMainComponent,
        double terrainViolence,
        double vegetationPerPlaced,
        Map<BuildingType, Double> clusteringCoherence,
        Map<DropReason, Integer> dropHistogram,
        // ---- District-era metrics (2026-06 refresh) ----------------------
        // Read from PhasedPlanner.Result.districtReport() (+ the squares).
        // All are honest "as-is" measurements: a 0 area is the paves-0
        // bug surfacing, a NO_REGION market is the market that didn't
        // reserve, a LOTS workshop seating is the row fallback firing.
        DistrictMetrics district,
        long elapsedMs
) {

    /**
     * District-era reservation outcomes for one run. Mirrors
     * {@code PhasedPlanner.DistrictReport} but lives test-side so the
     * baseline JSON has a stable shape independent of the production
     * record. Gated asymmetrically (see {@link Baseline}); a reserve
     * rate dropping or a plaza area collapsing to 0 is the regression
     * direction.
     *
     * @param workshopSeating one of {@code QUARTER} / {@code ROW} /
     *        {@code LOTS} / {@code NONE} — the workshop seating outcome
     *        (QUARTER the CITY-tier 4c-c quarter, ROW the shared craft
     *        row, LOTS the per-craft fallback, NONE no craft set in
     *        roster).
     */
    public record DistrictMetrics(
            boolean civicReserved,
            int civicArea,
            boolean marketSelected,
            boolean marketReserved,
            int marketArea,
            int residentialHousesRequested,
            int residentialPrecinctsReserved,
            int residentialHousesPlaced,
            int residentialHousesDropped,
            boolean residentialBandActive,
            int workshopCraftsRequested,
            String workshopSeating,
            int workshopCraftsPlaced,
            int workshopCraftsDropped) {

        public static DistrictMetrics empty() {
            return new DistrictMetrics(false, 0, false, false, 0,
                    0, 0, 0, 0, false, 0, "NONE", 0, 0);
        }
    }
}
