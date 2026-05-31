package tterrag1112.life_in_the_village.Village.Travel;

import java.util.List;

/**
 * Phase 5e — client-side cache of the travellers from the most recent
 * kingdom map sync. Mirrors {@code ClientTradeConnectionCache}: the map
 * packet drops the list here, and {@code KingdomMapDataBuilder} reads it
 * when (re)building the {@code KingdomMapData} on refresh.
 */
public final class ClientTravellerCache {
    private ClientTravellerCache() {}

    private static List<TravellerSnapshot> travellers = List.of();

    public static void setTravellers(List<TravellerSnapshot> incoming) {
        travellers = incoming == null ? List.of() : List.copyOf(incoming);
    }

    public static List<TravellerSnapshot> getTravellers() { return travellers; }

    public static void clear() { travellers = List.of(); }
}
