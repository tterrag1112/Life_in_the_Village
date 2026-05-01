package tterrag1112.life_in_the_village.Village.Planning.Primitives;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Village.Planning.Features.FeatureMap;

/**
 * Bundle of inputs a {@link RoadPrimitive} needs to compute its
 * centerline. Replaces the old {@code (ServerLevel, long)} tuple.
 *
 * <p>Phase 16 plumbing. Most callers can use {@link #basic} when no
 * {@link FeatureMap} is in hand (realiser callsites, route system).
 * Planning callers that hold a feature map pass it via the canonical
 * constructor so future phases can drive truncation off planning-time
 * polygons rather than live terrain queries.
 *
 * @param level           world reference for surface-Y lookups
 * @param worldSeed       world seed for deterministic noise
 * @param features        feature map for water/cliff queries; never null
 *                        (use {@link FeatureMap#empty()} when unknown)
 * @param maxStepDeltaY   max allowed |Y| change between consecutive
 *                        centerline points before a primitive truncates
 *                        with {@link TerminationReason#CLIFF_DROP} or
 *                        {@link TerminationReason#CLIFF_RISE}. Default 4.
 */
public record PrimitiveContext(
        ServerLevel level,
        long worldSeed,
        FeatureMap features,
        int maxStepDeltaY) {

    /** Default cliff threshold — Y change beyond this triggers CLIFF_*.
     *  Tuned to 6 (was 4): default-Minecraft hilly biomes regularly produce
     *  4–5 block Y-steps without an actual cliff being present, and 4 was
     *  truncating spurs/arcs that should traverse cleanly. 6 corresponds
     *  roughly to a 1-block-per-2-horizontal slope; sheer cliffs (8+ block
     *  vertical-chunk drops) still trip CLIFF_RISE/DROP. */
    public static final int DEFAULT_MAX_STEP_DELTA_Y = 6;

    /**
     * Convenience factory for callers that don't have a {@link FeatureMap}
     * (realiser-time, trade-route system). Uses {@link FeatureMap#empty()}
     * so water/cliff queries report false — primitives effectively skip
     * the water-truncation branch and the cliff check still runs against
     * the heightmap-derived Y values.
     */
    public static PrimitiveContext basic(ServerLevel level, long worldSeed) {
        return new PrimitiveContext(
                level, worldSeed, FeatureMap.empty(), DEFAULT_MAX_STEP_DELTA_Y);
    }
}
