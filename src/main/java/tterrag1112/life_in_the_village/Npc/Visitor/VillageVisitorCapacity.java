package tterrag1112.life_in_the_village.Npc.Visitor;

import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 4 doc 29 lines 76-84 + 252-264. Per-village visitor capacity
 * computed from infrastructure. Empty entries default to the base
 * (1 concurrent, 0.1/day) — every village can host at least the
 * occasional traveler.
 *
 * @param maxConcurrent       hard cap on simultaneous visitors;
 *                            spec line 263 caps the total at 20 even
 *                            with rich infrastructure.
 * @param arrivalRatePerDay   expected arrivals per day; the engine
 *                            does an integer-and-fractional roll so
 *                            a 2.4 rate produces 2 guaranteed plus a
 *                            40% chance of a third.
 * @param typeWeights         relative weights used for the weighted-
 *                            pick of {@link VisitorType} on each
 *                            arrival.
 */
public record VillageVisitorCapacity(
        int maxConcurrent,
        float arrivalRatePerDay,
        Map<VisitorType, Float> typeWeights
) {

    public static final int  MAX_CONCURRENT_CAP = 20;
    public static final int  BASE_CONCURRENT    = 1;
    public static final float BASE_ARRIVAL_RATE = 0.1f;

    public VillageVisitorCapacity {
        if (typeWeights == null) typeWeights = Map.of();
        else typeWeights = Map.copyOf(typeWeights);
        if (maxConcurrent < 0) maxConcurrent = 0;
        if (arrivalRatePerDay < 0f) arrivalRatePerDay = 0f;
    }

    /** Empty / fallback capacity (base for villages without a single
     *  visitor-attracting building). */
    public static final VillageVisitorCapacity FALLBACK = new VillageVisitorCapacity(
            BASE_CONCURRENT, BASE_ARRIVAL_RATE,
            Map.of(VisitorType.TRAVELER, 1f));

    /** Computes the village's capacity by summing per-building
     *  contributions per spec lines 256-261. */
    public static VillageVisitorCapacity compute(Village village, VillageSavedData data) {
        if (village == null) return FALLBACK;
        int concurrent = BASE_CONCURRENT;
        float arrival  = BASE_ARRIVAL_RATE;
        EnumMap<VisitorType, Float> weights = new EnumMap<>(VisitorType.class);
        weights.merge(VisitorType.TRAVELER, 1f, Float::sum);

        for (var bid : village.getBuildingIds()) {
            Optional<Building> bOpt = data.getBuildingById(bid);
            if (bOpt.isEmpty()) continue;
            BuildingType t = bOpt.get().getType();
            switch (t) {
                case INN -> {
                    concurrent += 5;
                    arrival   += 0.5f;
                    weights.merge(VisitorType.TRAVELER, 0.5f, Float::sum);
                }
                case TEMPLE -> {
                    concurrent += 3;
                    arrival   += 0.3f;
                    weights.merge(VisitorType.PILGRIM, 0.3f, Float::sum);
                    weights.merge(VisitorType.SCHOLAR_VISITING, 0.05f, Float::sum);
                }
                case CHAPEL, SHRINE -> {
                    concurrent += 1;
                    arrival   += 0.1f;
                    weights.merge(VisitorType.PILGRIM, 0.15f, Float::sum);
                }
                case MARKET -> {
                    concurrent += 3;
                    arrival   += 0.4f;
                    weights.merge(VisitorType.TRAVELER, 0.2f, Float::sum);
                    weights.merge(VisitorType.MERCHANT_ITINERANT, 0.3f, Float::sum);
                }
                case SCHOLARS_RETREAT -> {
                    concurrent += 2;
                    arrival   += 0.2f;
                    weights.merge(VisitorType.STUDENT, 0.2f, Float::sum);
                    weights.merge(VisitorType.SCHOLAR_VISITING, 0.2f, Float::sum);
                }
                case LIBRARY -> {
                    concurrent += 2;
                    arrival   += 0.1f;
                    weights.merge(VisitorType.STUDENT, 0.15f, Float::sum);
                }
                case GUILD_HALL,
                     GUILD_HALL_CRAFTSMEN,
                     GUILD_HALL_MERCHANTS,
                     GUILD_HALL_AGRICULTURAL,
                     GUILD_HALL_RELIGIOUS,
                     GUILD_HALL_SCHOLARLY -> {
                    concurrent += 2;
                    arrival   += 0.1f;
                    weights.merge(VisitorType.MERCHANT_ITINERANT, 0.1f, Float::sum);
                    weights.merge(VisitorType.ENVOY, 0.05f, Float::sum);
                }
                case TOWN_HALL -> {
                    weights.merge(VisitorType.ENVOY, 0.1f, Float::sum);
                }
                default -> {}
            }
        }
        // Phase 5 doc 31 — apply the village's culture-side affinity
        // multiplier on top of the per-building base weights. Reads
        // through CultureRegistry directly (no ServerLevel needed) by
        // looking up the village's kingdom-derived culture id.
        String cultureId = data.getKingdomForVillage(village.getId())
                .map(tterrag1112.life_in_the_village.Kingdom.Kingdom::getCulture)
                .orElse(null);
        var culture = tterrag1112.life_in_the_village.Cultures.CultureRegistry
                .getOrDefault(cultureId);
        var affinity = culture.visitorAffinity();
        EnumMap<VisitorType, Float> adjusted = new EnumMap<>(VisitorType.class);
        weights.forEach((type, w) -> {
            float mult = affinity.multiplierFor(type);
            adjusted.put(type, w * mult);
        });

        // Hard ceiling per spec line 263.
        if (concurrent > MAX_CONCURRENT_CAP) concurrent = MAX_CONCURRENT_CAP;
        return new VillageVisitorCapacity(concurrent, arrival, adjusted);
    }
}
