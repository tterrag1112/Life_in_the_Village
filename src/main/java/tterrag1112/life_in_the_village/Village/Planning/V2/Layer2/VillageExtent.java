package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

/**
 * Layout Rework Stage 3 fix-up #3 — the per-tier village radius, in
 * blocks, in one shared place.
 *
 * <p>This used to live only on {@code PhasedPlanner.villageRadiusFor}
 * (Layer 4). The Stage-3a {@link ZonePartition} (Layer 2) needs the same
 * number to bound the zoned region to the village footprint (so the
 * settlement is compact and farm fields land inside the scan grid),
 * and Layer 2 must not depend on Layer 4. Hoisting it here lets both
 * layers read it; {@code PhasedPlanner.villageRadiusFor} delegates.
 */
public final class VillageExtent {

    private VillageExtent() {}

    /** Nominal village radius (blocks from the anchor) per tier. The
     *  scan grid is larger (see {@code FEATURE_MAP_RADIUS}); the area beyond
     *  this radius is left for farm fields + outward expansion, not buildings.
     *  4c-a fix-up — CITY relaxed 80 → 120 so the WORKSHOP BAND gets its own
     *  ring beyond the residential band (which caps at ~courtyard depth, leaving
     *  the new outer room for workshops). Carries a scan-grid bump (the scan must
     *  cover the larger footprint — see V2VillageSpawnerAdapter.FEATURE_MAP_RADIUS).
     *  CITY-only; smaller tiers don't have the workshop-ring pressure. */
    public static int radiusFor(ViabilityTier tier) {
        return switch (tier) {
            case CITY -> 120;
            case TOWN -> 40;
            case HAMLET -> 20;
            case OUTPOST, UNVIABLE -> 10;
        };
    }
}
