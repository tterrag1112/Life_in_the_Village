package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

/**
 * Track E1 anchor detection — axis-aligned bounding rectangle of
 * an anchor's feature zone.
 *
 * <p>World-space block coordinates throughout. The XZ rectangle
 * spans from {@code (originX, originZ)} to
 * {@code (originX + width - 1, originZ + length - 1)} inclusive,
 * matching {@code BoundingBox}'s convention.
 *
 * @param originX north-west X corner in world block coordinates
 * @param originZ north-west Z corner in world block coordinates
 * @param width   X-axis extent in blocks
 * @param length  Z-axis extent in blocks
 */
public record AnchorExtent(int originX, int originZ, int width, int length) {

    /** Convenience: centre X in world coordinates. */
    public int centreX() { return originX + width / 2; }

    /** Convenience: centre Z in world coordinates. */
    public int centreZ() { return originZ + length / 2; }

    /** Cell-count area surrogate: width × length in blocks. */
    public int blockArea() { return Math.max(1, width) * Math.max(1, length); }

    /** True if this extent overlaps {@code other}. */
    public boolean overlaps(AnchorExtent other) {
        if (other == null) return false;
        int aMaxX = originX + width - 1;
        int aMaxZ = originZ + length - 1;
        int bMaxX = other.originX + other.width - 1;
        int bMaxZ = other.originZ + other.length - 1;
        return originX <= bMaxX && other.originX <= aMaxX
                && originZ <= bMaxZ && other.originZ <= aMaxZ;
    }

    /**
     * Fraction of this extent that falls inside {@code other}'s
     * bounds — 0 if disjoint, 1 if fully contained. Used by
     * {@link AnchorDetector} de-duplication.
     */
    public double overlapFraction(AnchorExtent other) {
        if (other == null) return 0.0;
        int aMaxX = originX + width - 1;
        int aMaxZ = originZ + length - 1;
        int bMaxX = other.originX + other.width - 1;
        int bMaxZ = other.originZ + other.length - 1;
        int ix0 = Math.max(originX, other.originX);
        int iz0 = Math.max(originZ, other.originZ);
        int ix1 = Math.min(aMaxX, bMaxX);
        int iz1 = Math.min(aMaxZ, bMaxZ);
        if (ix1 < ix0 || iz1 < iz0) return 0.0;
        long intersection = (long) (ix1 - ix0 + 1) * (iz1 - iz0 + 1);
        return (double) intersection / Math.max(1L, blockArea());
    }
}
