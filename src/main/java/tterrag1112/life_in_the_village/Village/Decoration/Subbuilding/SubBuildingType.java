package tterrag1112.life_in_the_village.Village.Decoration.Subbuilding;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * Doc 03 §"Data structures" — categories of logical region inside a
 * parent building. Codec-stable: new values must always be appended,
 * never reordered.
 */
public enum SubBuildingType implements StringRepresentable {
    /** Market stall — migrates from {@code MarketStallPlacer}. */
    STALL,
    /** A single household's living space. */
    APARTMENT,
    /** A commercial space within a larger building. */
    SHOP,
    /** Scholar workspace inside a guild hall or town hall. */
    ARCHIVE,
    /** Rentable room for a visitor or player. */
    INN_ROOM,
    /** Production space — larger than a stall. */
    WORKSHOP,
    /** Small shrine inside a temple. */
    CHAPEL_ROOM,
    /** Storage subregion that contributes to inventory cap. */
    CELLAR;

    public static final Codec<SubBuildingType> CODEC =
            StringRepresentable.fromEnum(SubBuildingType::values);

    @Override
    public String getSerializedName() {
        return name();
    }
}
