package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

/**
 * Track E1 prompt-4 — declarative per-building nucleus pull.
 *
 * <p>A {@code NucleusAffinity} states "buildings of this type are
 * pulled toward nuclei of this {@link NucleusKind}, ideally
 * {@code idealDistance} away, with score falling off to zero
 * past {@code maxDistance}."
 *
 * <p>The score curve is a triangle centred on {@code idealDistance}:
 * full {@code weight} at d=idealDistance, linearly decaying to
 * zero at d=0 (too close) and d=maxDistance (too far). For
 * {@code idealDistance == 0} the curve degenerates to a single
 * descending ramp from full at d=0 to zero at d=maxDistance.
 *
 * @param preferred       desired nucleus kind
 * @param weight          [0..1] magnitude of the affinity. Multiple
 *                        affinities sum.
 * @param idealDistance   sweet-spot distance from the nucleus, in
 *                        blocks. {@code 0} = "on top of" the
 *                        nucleus (TOWN_HALL on civic core).
 * @param maxDistance     hard cutoff in blocks. Past this, the
 *                        affinity contributes zero.
 * @param fallback        optional fallback affinity used when no
 *                        {@code preferred}-kind nucleus exists
 *                        in range. Single-level fallback only;
 *                        the fallback's own {@code fallback} is
 *                        ignored.
 */
public record NucleusAffinity(
        NucleusKind preferred,
        double weight,
        double idealDistance,
        double maxDistance,
        NucleusAffinity fallback) {

    public NucleusAffinity {
        if (preferred == null) {
            throw new IllegalArgumentException("preferred required");
        }
        if (weight < 0) weight = 0;
        if (weight > 1) weight = 1;
        if (idealDistance < 0) idealDistance = 0;
        if (maxDistance < idealDistance) maxDistance = idealDistance + 1;
    }

    /** Convenience: no-fallback affinity. */
    public static NucleusAffinity of(NucleusKind preferred, double weight,
                                     double idealDistance, double maxDistance) {
        return new NucleusAffinity(preferred, weight, idealDistance,
                maxDistance, null);
    }

    /** Convenience: affinity with a single fallback. */
    public static NucleusAffinity withFallback(NucleusKind preferred, double weight,
                                               double idealDistance, double maxDistance,
                                               NucleusAffinity fallback) {
        return new NucleusAffinity(preferred, weight, idealDistance, maxDistance,
                fallback);
    }
}
