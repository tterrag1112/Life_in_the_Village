package tterrag1112.life_in_the_village.Village.Travel;

/**
 * Phase 5e — the kind of traveller shown on the kingdom map's traveller
 * layer. Deliberately decoupled from {@link TravellingGroup} (which has no
 * type field): the type is attached at the server build site from the
 * concrete group (a {@code Caravan}'s {@code CaravanKind}, or a
 * {@code BoatCaravan}). New travellers (pilgrims, armies) reuse the same
 * layer by adding a value here + a gather case + an icon arm.
 */
public enum TravellerType {
    CARAVAN_EXPORT,
    CARAVAN_PROCUREMENT,
    BOAT;
    // Future: PILGRIM, ARMY, … — add an icon arm in TravellerLayer when added.

    /** Safe lookup by ordinal for the network codec (unknown → export). */
    public static TravellerType byId(int id) {
        TravellerType[] v = values();
        return (id >= 0 && id < v.length) ? v[id] : CARAVAN_EXPORT;
    }
}
