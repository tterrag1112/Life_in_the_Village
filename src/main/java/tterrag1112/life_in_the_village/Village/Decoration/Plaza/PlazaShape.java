package tterrag1112.life_in_the_village.Village.Decoration.Plaza;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * Doc 04 §"Layout-to-shape mapping" — geometric outline of a plaza.
 * The polygon generator (prompt 17) produces vertices matching this
 * shape; the renderer doesn't otherwise distinguish them.
 *
 * <p>Codec stability: append-only.</p>
 */
public enum PlazaShape implements StringRepresentable {
    /** Regular ~16-gon approximation of a circle. */
    CIRCLE,
    /** Axis-aligned (or 45°-rotated) square, 4 vertices. */
    SQUARE,
    /** Long-axis rectangle for villages along rivers / coasts /
     *  ridges where the plaza naturally stretches. */
    LINEAR,
    /** Boundary-sampled outline simplified via Douglas-Peucker —
     *  used when terrain or existing buildings shape the plaza
     *  organically. */
    IRREGULAR;

    public static final Codec<PlazaShape> CODEC =
            StringRepresentable.fromEnum(PlazaShape::values);

    @Override
    public String getSerializedName() { return name(); }
}
