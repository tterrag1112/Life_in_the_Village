package tterrag1112.life_in_the_village.Village.Decoration.Plaza;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * Doc 04 §"Core concepts" — what kind of civic role a plaza plays.
 * A village may host multiple plazas with different purposes (e.g.
 * DUAL_PLAZA = primary CIVIC + secondary MARKET).
 *
 * <p>Codec stability: append-only.</p>
 */
public enum PlazaPurpose implements StringRepresentable {
    /** Town hall fronts, civic gatherings, primary social space. */
    CIVIC,
    /** Market square — reserves space for stalls during MARKET_DAY. */
    MARKET,
    /** Temple courtyard — quieter, more contemplative ambience. */
    RELIGIOUS_COURTYARD;

    public static final Codec<PlazaPurpose> CODEC =
            StringRepresentable.fromEnum(PlazaPurpose::values);

    @Override
    public String getSerializedName() { return name(); }
}
