package tterrag1112.life_in_the_village.Village.Decoration.Framework;

/**
 * B2.2 — per-village density knobs consumed by
 * {@link DecorationSlotEmitter}. Replaces the hardcoded constants
 * (road-side sample step, building-gap range, facade ornament
 * count, village-boundary angular step) with a tier-tunable record
 * so HAMLET-tier villages get visibly sparser furniture than CITY-
 * tier ones.
 *
 * <p>Density profiles are looked up via
 * {@link DecorationDensityRegistry} keyed by
 * {@code (culture, VillageSizeTier)} with culture fallback to
 * {@code "default"} and tier fallback to nearest authored tier.</p>
 *
 * <p>Doc 05 §"Tier gating" describes the intended feel: "Hamlets
 * get a minimal subset (bench + planter + signpost only); Villages
 * add lampposts and notice boards; Towns add handcarts, woodpiles,
 * wells; Cities add shrines, communal ovens, mounting blocks." The
 * piece-availability axis is handled by {@link
 * DecorationProfile#minTier}; this record drives the orthogonal
 * spatial-density axis (more slots per metre of road in CITY, fewer
 * in HAMLET).</p>
 *
 * @param roadSideSampleStep         distance in blocks between
 *                                   consecutive road-side slot
 *                                   samples along a centerline
 * @param roadSideOffset             perpendicular distance from
 *                                   road centerline to the road-
 *                                   side slot
 * @param buildingGapMin             minimum gap (blocks) between
 *                                   adjacent street-facing
 *                                   buildings to qualify for a
 *                                   building-gap slot
 * @param buildingGapMax             maximum gap (blocks) before the
 *                                   space is too wide to read as a
 *                                   "gap"
 * @param facadeOrnamentsPerWall     max facade-ornament slots
 *                                   emitted per building front
 * @param villageBoundaryAngleStepDeg angular step (degrees) for
 *                                   village-boundary marker
 *                                   spacing — smaller means more
 *                                   markers around the perimeter
 */
public record DecorationDensityProfile(
        int roadSideSampleStep,
        int roadSideOffset,
        int buildingGapMin,
        int buildingGapMax,
        int facadeOrnamentsPerWall,
        double villageBoundaryAngleStepDeg) {

    /** Pre-B2.2 default — matches the hardcoded constants that lived
     *  in {@link DecorationSlotEmitter}. Used as the ultimate
     *  fallback when no registry entry resolves. Values: road-side
     *  every 6 blocks, offset 3; gap 4-12; 2 facade ornaments per
     *  wall; 15° boundary step. */
    public static final DecorationDensityProfile LEGACY_DEFAULT =
            new DecorationDensityProfile(6, 3, 4, 12, 2, 15.0);
}
