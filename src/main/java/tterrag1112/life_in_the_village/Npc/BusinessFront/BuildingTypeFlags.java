package tterrag1112.life_in_the_village.Npc.BusinessFront;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Spec line 87 wants a flag field directly on {@link BuildingType}, but
 * {@code BuildingType} is a bare-entry enum (every constant on one line,
 * no body). Adding constructor arguments would touch every existing
 * line and conflict with concurrent enum work in other branches.
 *
 * <p>This static lookup keeps the same surface — {@link
 * #hasBusinessFront} and {@link #hasServiceFront} read identically to
 * the spec's "{@code type.hasBusinessFront()}" — without modifying
 * the enum itself. Spec deviation logged in 24 Revision Notes.</p>
 */
public final class BuildingTypeFlags {

    public enum Flag { BUSINESS_FRONT, SERVICE_FRONT, RESIDENCE }

    private static final Map<BuildingType, Set<Flag>> TABLE = build();

    private BuildingTypeFlags() {}

    private static Map<BuildingType, Set<Flag>> build() {
        EnumMap<BuildingType, Set<Flag>> m = new EnumMap<>(BuildingType.class);

        // ── BUSINESS_FRONT (worker greets, channel router routes trades) ─
        m.put(BuildingType.MARKET,           EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.BLACKSMITH,       EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.CARPENTRY,        EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.BAKERY,           EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.INN,              EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.SCRIBE_WORKSHOP,  EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.LIBRARY,          EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.STOCKPILE,        EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.STONEMASON,       EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.WEAVER,           EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.CANDLEMAKER,      EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.MILLER,           EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.APOTHECARY,       EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.HEALER_HUT,       EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.SCHOLARS_RETREAT, EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.STABLE,           EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.WAREHOUSE,        EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.WINERY,           EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.ARMORER,          EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.TOOLSMITH,        EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.ATELIER,          EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.DOCKS,            EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.FISHERY,          EnumSet.of(Flag.BUSINESS_FRONT));
        m.put(BuildingType.WOODCUTTER,       EnumSet.of(Flag.BUSINESS_FRONT));

        // ── SERVICE_FRONT (greeter behaviour for civic / religious work) ──
        m.put(BuildingType.TEMPLE,           EnumSet.of(Flag.SERVICE_FRONT));
        m.put(BuildingType.CHAPEL,           EnumSet.of(Flag.SERVICE_FRONT));
        m.put(BuildingType.SHRINE,           EnumSet.of(Flag.SERVICE_FRONT));
        m.put(BuildingType.TOWN_HALL,        EnumSet.of(Flag.SERVICE_FRONT));
        m.put(BuildingType.CHANCELLERY,      EnumSet.of(Flag.SERVICE_FRONT));
        m.put(BuildingType.GUILD_HALL,       EnumSet.of(Flag.SERVICE_FRONT));

        // ── BUSINESS_FRONT + RESIDENCE (live-where-you-work) ──────────────
        m.put(BuildingType.FARMHOUSE,        EnumSet.of(Flag.BUSINESS_FRONT, Flag.RESIDENCE));

        // ── RESIDENCE only ────────────────────────────────────────────────
        m.put(BuildingType.HOUSE,            EnumSet.of(Flag.RESIDENCE));
        m.put(BuildingType.NOBLE_MANOR,      EnumSet.of(Flag.RESIDENCE));

        // Everything not listed defaults to no flags (treated as "not a
        // business surface"; right-click opens NpcProfileScreen as before).
        return Map.copyOf(m);
    }

    public static Set<Flag> flagsFor(BuildingType type) {
        if (type == null) return Set.of();
        return TABLE.getOrDefault(type, Set.of());
    }

    public static boolean hasBusinessFront(BuildingType type) {
        return flagsFor(type).contains(Flag.BUSINESS_FRONT);
    }

    public static boolean hasServiceFront(BuildingType type) {
        return flagsFor(type).contains(Flag.SERVICE_FRONT);
    }

    public static boolean isResidence(BuildingType type) {
        return flagsFor(type).contains(Flag.RESIDENCE);
    }

    /** True for either business OR service front — what the greeter goal cares about. */
    public static boolean hasGreeterFront(BuildingType type) {
        Set<Flag> f = flagsFor(type);
        return f.contains(Flag.BUSINESS_FRONT) || f.contains(Flag.SERVICE_FRONT);
    }
}
