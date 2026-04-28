package tterrag1112.life_in_the_village.Village.Decoration.Framework;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * Doc 01 — placement-attachment rule for a decoration piece. Drives
 * the matcher's terrain check: a {@code FACADE_ORNAMENT} piece with
 * {@link #AGAINST_WALL} requires the slot's facing direction to back
 * onto a solid building face; an {@code ON_GROUND} piece needs a
 * solid block below; a {@link #FLOATING} piece (lantern, banner)
 * skips the ground check.
 */
public enum AnchorRule implements StringRepresentable {
    /** Piece must back onto a solid wall in the slot's facing
     *  direction (e.g. wall planters, lanterns). */
    AGAINST_WALL,
    /** Piece needs a solid block immediately below the slot
     *  position (e.g. benches, signposts, headstones). */
    ON_GROUND,
    /** No structural attachment requirement (e.g. hanging banners,
     *  particle markers). The matcher only validates the slot
     *  position itself is air or replaceable. */
    FLOATING;

    public static final Codec<AnchorRule> CODEC =
            StringRepresentable.fromEnum(AnchorRule::values);

    @Override public String getSerializedName() { return name(); }
}
