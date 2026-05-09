package tterrag1112.life_in_the_village.Npc.Nobility;

import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.CultureKingdomDefaults;
import tterrag1112.life_in_the_village.Cultures.CultureRegistry;

import java.util.List;

/**
 * Track D3.2a — culture-driven name resolution for the rank index
 * stored on {@link NobilityComponent#getRankIndex()}. Names live in
 * each culture's {@link CultureKingdomDefaults#nobilityRanks} list;
 * the helpers below clamp out-of-range indices and gracefully handle
 * cultures that declare no ranks (typically non-monarchies — they
 * answer with the {@link #COMMONER_FALLBACK} label).
 *
 * <p>Pure read helper; no state, no side effects. D3.2b's promotion
 * + heraldry-display layers consume it.
 */
public final class NobilityRanks {

    private NobilityRanks() {}

    /** Returned for cultures that declare an empty rank list. */
    public static final String COMMONER_FALLBACK = "Commoner";

    /**
     * Resolves the display name for {@code rankIndex} in {@code cultureId}'s
     * rank table. Out-of-range indices clamp to the nearest valid slot;
     * an unknown culture or empty rank list returns {@link #COMMONER_FALLBACK}.
     */
    public static String nameFor(String cultureId, int rankIndex) {
        Culture culture = CultureRegistry.getOrDefault(cultureId);
        return nameFor(culture, rankIndex);
    }

    /** Variant taking a resolved {@link Culture} directly. */
    public static String nameFor(Culture culture, int rankIndex) {
        if (culture == null) return COMMONER_FALLBACK;
        List<String> ranks = culture.kingdomDefaults().nobilityRanks();
        if (ranks == null || ranks.isEmpty()) return COMMONER_FALLBACK;
        int clamped = Math.max(0, Math.min(ranks.size() - 1, rankIndex));
        String name = ranks.get(clamped);
        return (name == null || name.isEmpty()) ? COMMONER_FALLBACK : name;
    }

    /** Number of ranks declared by the given culture (0 for non-monarchy cultures). */
    public static int rankCount(String cultureId) {
        Culture culture = CultureRegistry.getOrDefault(cultureId);
        if (culture == null) return 0;
        List<String> ranks = culture.kingdomDefaults().nobilityRanks();
        return ranks == null ? 0 : ranks.size();
    }

    /** Highest valid rank index for the culture, or 0 if none declared. */
    public static int maxRankIndex(String cultureId) {
        return Math.max(0, rankCount(cultureId) - 1);
    }

    /** True iff the culture's rank table declares two or more entries. */
    public static boolean isMonarchyCulture(String cultureId) {
        return rankCount(cultureId) >= 2;
    }

    /** Snapshot of all rank names in the culture's table, in order. */
    public static List<String> namesFor(String cultureId) {
        Culture culture = CultureRegistry.getOrDefault(cultureId);
        if (culture == null) return List.of();
        List<String> ranks = culture.kingdomDefaults().nobilityRanks();
        return ranks == null ? List.of() : List.copyOf(ranks);
    }
}
