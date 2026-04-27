package tterrag1112.life_in_the_village.Guilds.Common;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Bidirectional map between {@link GuildType} and the
 * {@link BuildingType} of its hall. Lives in a separate class because
 * the {@code GuildType} enum is initialised before
 * {@code BuildingType} in some bootstrap orders, and pulling these
 * references from constants in the enum body would force eager
 * loading of {@code BuildingType} during the {@code GuildType}
 * &lt;clinit&gt;.
 */
public final class GuildHallTypes {

    private static final Map<GuildType, BuildingType> HALL_BY_TYPE   = build();
    private static final Map<BuildingType, GuildType> TYPE_BY_HALL   = invert();

    private GuildHallTypes() {}

    public static BuildingType hallFor(GuildType type) {
        if (type == null) return null;
        return HALL_BY_TYPE.get(type);
    }

    /** Inverse: which guild type owns this hall building? Returns null
     *  for non-hall buildings. */
    public static GuildType guildTypeForHall(BuildingType hallType) {
        if (hallType == null) return null;
        return TYPE_BY_HALL.get(hallType);
    }

    public static boolean isGuildHall(BuildingType hallType) {
        return guildTypeForHall(hallType) != null;
    }

    private static Map<GuildType, BuildingType> build() {
        EnumMap<GuildType, BuildingType> m = new EnumMap<>(GuildType.class);
        m.put(GuildType.ADVENTURER,   BuildingType.GUILD_HALL);
        m.put(GuildType.CRAFTSMEN,    BuildingType.GUILD_HALL_CRAFTSMEN);
        m.put(GuildType.MERCHANTS,    BuildingType.GUILD_HALL_MERCHANTS);
        m.put(GuildType.AGRICULTURAL, BuildingType.GUILD_HALL_AGRICULTURAL);
        m.put(GuildType.RELIGIOUS,    BuildingType.GUILD_HALL_RELIGIOUS);
        m.put(GuildType.SCHOLARLY,    BuildingType.GUILD_HALL_SCHOLARLY);
        return Map.copyOf(m);
    }

    private static Map<BuildingType, GuildType> invert() {
        EnumMap<BuildingType, GuildType> m = new EnumMap<>(BuildingType.class);
        for (Map.Entry<GuildType, BuildingType> e : HALL_BY_TYPE.entrySet()) {
            if (e.getValue() != null) m.put(e.getValue(), e.getKey());
        }
        return Map.copyOf(m);
    }
}
