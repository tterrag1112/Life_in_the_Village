package tterrag1112.life_in_the_village.Npc.Hobby;

import com.mojang.serialization.Codec;

/**
 * Where a hobby is performed. Resolved to a concrete {@code BlockPos}
 * by {@link HobbyLocationResolver}; resolution can fail (no library in
 * this village), in which case the hobby is filtered from
 * {@link HobbyCatalogue#availableFor}.
 *
 * <p>Spec: {@code docs/npc_redesign/14-hobby-activities.md} (line 45).</p>
 */
public enum HobbyLocation {
    HOME,
    TOWN_SQUARE,
    INN,
    TEMPLE_OR_SHRINE,
    LIBRARY,
    MARKET,
    FIELDS_NEARBY,
    WATER_EDGE,
    WORKSHOP_FREE,
    NATURE_TRAIL,
    /** Resolves to a target NPC's house — used by VISIT_FRIEND. */
    FRIEND_HOUSE,
    /** Resolves to a graveyard/cemetery — used by VISIT_GRAVE. */
    GRAVEYARD;

    public static final Codec<HobbyLocation> CODEC =
            Codec.STRING.xmap(HobbyLocation::valueOf, HobbyLocation::name);
}
