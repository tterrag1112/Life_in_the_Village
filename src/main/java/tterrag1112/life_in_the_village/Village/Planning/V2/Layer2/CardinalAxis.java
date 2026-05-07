package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import net.minecraft.world.phys.Vec3;

/**
 * V2 cardinal axis. The village's primary spine direction is always
 * one of the four world cardinals — diagonal spines are not produced.
 *
 * <p>Selected by {@link SiteAnalyzer} from terrain features
 * (river/coast direction, hill orientation, flat-region bbox)
 * snapped to the nearer cardinal. {@code X} = spine runs east-west;
 * {@code Z} = spine runs north-south.
 */
public enum CardinalAxis {
    X,
    Z;

    /** Unit vector along this axis (positive direction). */
    public Vec3 axisUnit() {
        return this == X ? new Vec3(1, 0, 0) : new Vec3(0, 0, 1);
    }

    /** Unit vector perpendicular to this axis (cardinal). */
    public Vec3 perpUnit() {
        return this == X ? new Vec3(0, 0, 1) : new Vec3(1, 0, 0);
    }

    /** The perpendicular axis. */
    public CardinalAxis perpAxis() {
        return this == X ? Z : X;
    }

    /** Axis nearer to {@code (vx, vz)}. Pure |vx| / |vz| comparison. */
    public static CardinalAxis nearestTo(double vx, double vz) {
        return Math.abs(vx) > Math.abs(vz) ? X : Z;
    }
}
