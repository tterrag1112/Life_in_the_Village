package tterrag1112.life_in_the_village.Village.Decoration.Framework;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * B2.2 — registry of {@link DecorationDensityProfile}s keyed by
 * (culture, {@link VillageSizeTier}). Lookup falls back through
 * {@code culture → "default" culture → {@link DecorationDensityProfile#LEGACY_DEFAULT}}
 * so unknown cultures behave sensibly without code changes.
 *
 * <p>Initial population covers only the {@code "default"} culture
 * across all four village size tiers. Future cultures register
 * their own profiles via {@link #register(String, VillageSizeTier,
 * DecorationDensityProfile)} without touching the lookup logic.</p>
 *
 * <p>Density curves (default culture):</p>
 * <ul>
 *   <li>HAMLET — sparse: 14-block road-side step; 1 facade
 *       ornament per wall; 30° boundary step.</li>
 *   <li>VILLAGE — moderate: 10-block step; 2 facade ornaments;
 *       20° boundary step.</li>
 *   <li>TOWN — dense: 8-block step; 2 facade ornaments; 15°
 *       boundary step.</li>
 *   <li>CITY — densest: 6-block step (matches doc 01's
 *       LEGACY_DEFAULT); 2 facade ornaments; 12° boundary step.</li>
 * </ul>
 */
public final class DecorationDensityRegistry {

    public static final DecorationDensityRegistry INSTANCE =
            new DecorationDensityRegistry();

    public static final String DEFAULT_CULTURE =
            DecorationProfileRegistry.DEFAULT_CULTURE;

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DecorationDensityRegistry.class);

    private final Map<String, EnumMap<VillageSizeTier, DecorationDensityProfile>> table =
            new HashMap<>();

    private DecorationDensityRegistry() { registerDefaults(); }

    private void registerDefaults() {
        register(DEFAULT_CULTURE, VillageSizeTier.HAMLET,
                new DecorationDensityProfile(14, 3,  5, 14, 1, 30.0));
        register(DEFAULT_CULTURE, VillageSizeTier.VILLAGE,
                new DecorationDensityProfile(10, 3,  4, 13, 2, 20.0));
        register(DEFAULT_CULTURE, VillageSizeTier.TOWN,
                new DecorationDensityProfile( 8, 3,  4, 12, 2, 15.0));
        register(DEFAULT_CULTURE, VillageSizeTier.CITY,
                new DecorationDensityProfile( 6, 3,  4, 12, 2, 12.0));
    }

    /** Adds or replaces the profile for {@code (culture, tier)}. */
    public void register(String culture, VillageSizeTier tier,
                         DecorationDensityProfile profile) {
        Objects.requireNonNull(culture, "culture");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(profile, "profile");
        table.computeIfAbsent(culture,
                k -> new EnumMap<>(VillageSizeTier.class)).put(tier, profile);
    }

    /**
     * Resolves the density profile for {@code (culture, tier)} with
     * fallback chain: culture → "default" culture → LEGACY_DEFAULT.
     */
    public DecorationDensityProfile resolve(String culture,
                                            VillageSizeTier tier) {
        VillageSizeTier resolvedTier = tier != null ? tier : VillageSizeTier.HAMLET;
        DecorationDensityProfile p = lookup(culture, resolvedTier);
        if (p != null) return p;
        if (!DEFAULT_CULTURE.equals(culture)) {
            p = lookup(DEFAULT_CULTURE, resolvedTier);
            if (p != null) return p;
        }
        LOGGER.debug("DecorationDensityRegistry: no entry for culture={} tier={}; "
                + "falling back to LEGACY_DEFAULT", culture, resolvedTier);
        return DecorationDensityProfile.LEGACY_DEFAULT;
    }

    private DecorationDensityProfile lookup(String culture, VillageSizeTier tier) {
        EnumMap<VillageSizeTier, DecorationDensityProfile> byTier = table.get(culture);
        return byTier == null ? null : byTier.get(tier);
    }
}
