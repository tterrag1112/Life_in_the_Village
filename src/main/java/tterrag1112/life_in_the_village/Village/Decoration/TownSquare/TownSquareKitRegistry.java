package tterrag1112.life_in_the_village.Village.Decoration.TownSquare;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Decoration.Framework
        .DecorationProfileRegistry;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Doc 04 §"Open decisions" — registry of {@link TownSquareKit}s
 * indexed by {@code (culture, tier)} with the doc 04-specified
 * fallback chain:
 *
 * <ol>
 *   <li>(culture, tier) — exact match wins.</li>
 *   <li>("default", tier) — same tier, default culture.</li>
 *   <li>(culture, next-larger-tier-with-content) — same culture,
 *       smaller plaza than ideal but at least the right culture.</li>
 *   <li>("default", next-larger-tier-with-content) — last resort
 *       before logging a warning and skipping.</li>
 * </ol>
 *
 * <p>Populated at mod init by {@link DefaultTownSquareKits}; other
 * cultures fill in over time. Singleton.</p>
 */
public final class TownSquareKitRegistry {

    public static final TownSquareKitRegistry INSTANCE = new TownSquareKitRegistry();

    private static final Logger LOGGER =
            LoggerFactory.getLogger(TownSquareKitRegistry.class);

    private record Key(String culture, VillageSizeTier tier) {}

    private final Map<Key, TownSquareKit> byKey = new LinkedHashMap<>();

    private TownSquareKitRegistry() {}

    /**
     * Registers {@code kit} under its declared {@code (culture, tier)}.
     * Duplicate registration replaces the prior entry and logs a
     * warning. Note that the kit's
     * {@link DecorationProfileRegistry} entries are registered
     * separately by {@link DefaultTownSquareKits} — this registry
     * only stores the kit metadata.
     */
    public void register(TownSquareKit kit) {
        if (kit == null) {
            throw new IllegalArgumentException("TownSquareKitRegistry: null kit");
        }
        Key k = new Key(kit.culture(), kit.tier());
        TownSquareKit prior = byKey.put(k, kit);
        if (prior != null) {
            LOGGER.warn("TownSquareKitRegistry: kit ({}, {}) replaced",
                    kit.culture(), kit.tier());
        }
    }

    /** Test hook. */
    public void clear() { byKey.clear(); }

    public int size() { return byKey.size(); }

    /**
     * Doc 04 §"Open decisions" — fallback chain. Returns the best
     * matching kit, or empty if no kit at any tier exists for
     * either {@code culture} or {@code "default"}.
     */
    public Optional<TownSquareKit> resolve(String culture, VillageSizeTier tier) {
        if (tier == null) return Optional.empty();
        String c = (culture == null || culture.isBlank())
                ? DecorationProfileRegistry.DEFAULT_CULTURE : culture;

        // 1. exact match
        TownSquareKit exact = byKey.get(new Key(c, tier));
        if (exact != null) return Optional.of(exact);

        // 2. same tier, default culture
        if (!DecorationProfileRegistry.DEFAULT_CULTURE.equals(c)) {
            TownSquareKit defaultTier = byKey.get(
                    new Key(DecorationProfileRegistry.DEFAULT_CULTURE, tier));
            if (defaultTier != null) return Optional.of(defaultTier);
        }

        // 3. same culture, larger tier with content
        for (VillageSizeTier larger : largerTiers(tier)) {
            TownSquareKit fallback = byKey.get(new Key(c, larger));
            if (fallback != null) return Optional.of(fallback);
        }

        // 4. default culture, larger tier with content
        if (!DecorationProfileRegistry.DEFAULT_CULTURE.equals(c)) {
            for (VillageSizeTier larger : largerTiers(tier)) {
                TownSquareKit fallback = byKey.get(
                        new Key(DecorationProfileRegistry.DEFAULT_CULTURE, larger));
                if (fallback != null) return Optional.of(fallback);
            }
        }

        return Optional.empty();
    }

    private static VillageSizeTier[] largerTiers(VillageSizeTier from) {
        // Walk upward from `from` (exclusive) to CITY.
        return switch (from) {
            case HAMLET  -> new VillageSizeTier[]{
                    VillageSizeTier.VILLAGE,
                    VillageSizeTier.TOWN,
                    VillageSizeTier.CITY };
            case VILLAGE -> new VillageSizeTier[]{
                    VillageSizeTier.TOWN, VillageSizeTier.CITY };
            case TOWN    -> new VillageSizeTier[]{ VillageSizeTier.CITY };
            case CITY    -> new VillageSizeTier[]{};
        };
    }
}
