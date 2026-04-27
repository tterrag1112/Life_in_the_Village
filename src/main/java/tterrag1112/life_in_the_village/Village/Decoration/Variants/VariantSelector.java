package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Doc 15 §"Variant selection algorithm" — scores eligible variants
 * for a single (type, slot, village context) decision and rolls one
 * weighted-random pick.
 *
 * <p>One {@link VariantSelector} instance is created per {@code
 * PlacementMatcher} run, so the {@code maxPerVillage} hard cap and
 * the diminishing-returns multiplier (P0a-19) operate on a per-
 * village placement counter that resets when the matcher moves on
 * to the next village.</p>
 *
 * <p>P0a-06 covers the core scoring; P0a-19 layers
 * {@code × pow(0.7, alreadyPlacedCount)} on top of the base score.
 * Both live here because the placement counter is shared.</p>
 *
 * <p>All RNG calls go through the matcher's {@link Random}; we don't
 * own the source. That keeps {@code (worldSeed, villageOrigin)
 * → variant choices} reproducible.</p>
 */
public final class VariantSelector {

    /** Doc 15: diminishing-returns multiplier base. */
    public static final float DIVERSITY_BONUS_BASE = 0.7f;

    /** Doc 15: scoring weights. */
    public static final float SLOT_TAG_BONUS_PER_HIT = 0.3f;
    public static final float VILLAGE_TAG_BONUS_PER_HIT = 0.2f;
    public static final float STYLE_PREF_EXACT_MATCH = 0.4f;
    public static final float AGE_PREF_EXACT_MATCH = 0.2f;

    /** How many of each variant id (across the registry's full key)
     *  have already been placed in this village this run. Keyed by
     *  full {@link VariantKey} so two cultures with the same variant
     *  id don't share a counter. */
    private final Map<VariantKey, Integer> placedCounts = new HashMap<>();

    /**
     * Picks a variant for one slot. Returns the chosen
     * {@link BuildingVariant} (registered in the registry) or
     * {@link Fallback#syntheticDefault(BuildingType, Style)} when no
     * variant is registered at all for {@code (culture, style, type)} —
     * callers can still resolve an NBT through {@link
     * tterrag1112.life_in_the_village.Village.CultureResolver}'s
     * default-variant fallback chain.
     *
     * @param culture               village culture id; eligible
     *                              variants are filtered to this
     *                              culture, falling back to the
     *                              {@code "default"} culture's
     *                              variants when the village's own
     *                              culture has none authored
     * @param type                  building type being placed
     * @param style                 picked style for this slot
     * @param tier                  village tier
     * @param ageCategory           village age bucket
     * @param slotTags              slot's advertised {@link SlotTag}s
     * @param villagePreferredTags  free-form variant tags the village
     *                              prefers (manifest {@code tags}
     *                              field). Empty set is fine.
     * @param rng                   matcher's RNG (do not introduce a
     *                              new instance — determinism)
     */
    public BuildingVariant select(String culture,
                                  BuildingType type, Style style,
                                  VillageSizeTier tier,
                                  AgeCategory ageCategory,
                                  Set<SlotTag> slotTags,
                                  Set<String> villagePreferredTags,
                                  Random rng) {

        List<BuildingVariant> registered =
                VariantRegistry.INSTANCE.eligibleFor(type, style, tier, ageCategory);

        // Filter by culture: prefer the village's own culture; fall
        // back to the "default" culture only if there's no authored
        // content for the village's culture. Mirrors the resolver's
        // step-1 → step-3 fallback at the selection layer so the
        // chosen variant's metadata matches the NBT we end up loading.
        List<BuildingVariant> sameCulture = new ArrayList<>();
        List<BuildingVariant> defaultCulture = new ArrayList<>();
        for (BuildingVariant v : registered) {
            if (v.culture().equals(culture)) sameCulture.add(v);
            else if ("default".equals(v.culture())) defaultCulture.add(v);
        }
        List<BuildingVariant> eligible = !sameCulture.isEmpty()
                ? sameCulture : defaultCulture;

        // Apply the maxPerVillage hard cap before scoring.
        List<BuildingVariant> candidates = new ArrayList<>();
        for (BuildingVariant v : eligible) {
            if (v.maxPerVillage() > 0
                    && countOf(v) >= v.maxPerVillage()) {
                continue;
            }
            candidates.add(v);
        }

        if (candidates.isEmpty()) {
            // Doc 15 fallback: the type's default variant. Try the
            // village's culture first, then the "default" culture; if
            // both miss, synthesize a default so the placer can still
            // hit step 5/6 of the resolver's fallback chain. No RNG
            // consumed.
            BuildingVariant fallback = VariantRegistry.INSTANCE
                    .defaultVariant(type, culture, style)
                    .or(() -> VariantRegistry.INSTANCE
                            .defaultVariant(type, "default", style))
                    .orElseGet(() -> Fallback.syntheticDefault(type, style));
            recordPlacement(fallback);
            return fallback;
        }

        // Single-candidate fast path: no RNG roll needed. This keeps
        // the matcher's RNG stream identical to the pre-P0a-06
        // sequence for villages where every type has only the
        // default variant authored — the common case during the
        // rollout.
        if (candidates.size() == 1) {
            BuildingVariant only = candidates.get(0);
            recordPlacement(only);
            return only;
        }

        // Score and weighted-roll
        double[] scores = new double[candidates.size()];
        double total = 0.0;
        for (int i = 0; i < candidates.size(); i++) {
            double s = score(candidates.get(i), style, ageCategory,
                    slotTags, villagePreferredTags);
            if (s < 0) s = 0;
            scores[i] = s;
            total += s;
        }

        BuildingVariant chosen;
        if (total <= 0.0) {
            // Every candidate scored zero (e.g. diminishing returns
            // collapsed every score). Fall back to the first
            // candidate deterministically — same seed, same village,
            // same pick. Don't consume RNG.
            chosen = candidates.get(0);
        } else {
            double roll = rng.nextDouble() * total;
            int idx = candidates.size() - 1;
            for (int i = 0; i < candidates.size(); i++) {
                roll -= scores[i];
                if (roll <= 0.0) { idx = i; break; }
            }
            chosen = candidates.get(idx);
        }
        recordPlacement(chosen);
        return chosen;
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private double score(BuildingVariant v, Style style,
                         AgeCategory ageCategory,
                         Set<SlotTag> slotTags,
                         Set<String> villagePreferredTags) {
        double s = v.weight();

        // +0.3 per matching preferredTag in slot.tags
        if (!slotTags.isEmpty()) {
            int hits = 0;
            for (SlotTag t : v.preferredTags()) if (slotTags.contains(t)) hits++;
            s += SLOT_TAG_BONUS_PER_HIT * hits;
        }

        // +0.2 per matching variant tag in village preferred tags
        if (!villagePreferredTags.isEmpty()) {
            int hits = 0;
            for (String t : v.tags()) if (villagePreferredTags.contains(t)) hits++;
            s += VILLAGE_TAG_BONUS_PER_HIT * hits;
        }

        // +0.4 if stylePreference matches village style exactly
        if (v.stylePref() != StylePreference.ANY
                && stylePrefMatches(v.stylePref(), style)) {
            s += STYLE_PREF_EXACT_MATCH;
        }

        // +0.2 if agePreference matches village age category
        if (v.agePref() != AgePreference.ANY
                && agePrefMatches(v.agePref(), ageCategory)) {
            s += AGE_PREF_EXACT_MATCH;
        }

        // P0a-19: diminishing returns × pow(0.7, alreadyPlacedCount)
        int placed = countOf(v);
        if (placed > 0) s *= Math.pow(DIVERSITY_BONUS_BASE, placed);

        return s;
    }

    private static boolean stylePrefMatches(StylePreference pref, Style style) {
        return (pref == StylePreference.RURAL && style == Style.RURAL)
                || (pref == StylePreference.URBAN && style == Style.URBAN);
    }

    private static boolean agePrefMatches(AgePreference pref, AgeCategory cat) {
        return (pref == AgePreference.FRESH && cat == AgeCategory.FRESH)
                || (pref == AgePreference.WEATHERED && cat == AgeCategory.WEATHERED)
                || (pref == AgePreference.ANCIENT && cat == AgeCategory.ANCIENT);
    }

    private int countOf(BuildingVariant v) {
        return placedCounts.getOrDefault(keyOf(v), 0);
    }

    private void recordPlacement(BuildingVariant v) {
        placedCounts.merge(keyOf(v), 1, Integer::sum);
    }

    private static VariantKey keyOf(BuildingVariant v) {
        return new VariantKey(v.culture(), v.style().folder(),
                v.type(), v.id());
    }

    /**
     * Returns the current placement count for a registered variant.
     * Exposed for diagnostics and tests.
     */
    public int placedCount(BuildingVariant v) { return countOf(v); }

    // ── Fallback helpers ──────────────────────────────────────────────────

    public static final class Fallback {
        private Fallback() {}

        /**
         * Synthesizes the type-default {@link BuildingVariant} when the
         * registry has no entry at all for the requested
         * {@code (type, style)}. The synthetic variant has all-default
         * scoring fields and uses the {@code "default"} culture tag —
         * the resolver's seven-step fallback chain handles the actual
         * NBT lookup.
         */
        public static BuildingVariant syntheticDefault(BuildingType type, Style style) {
            VariantManifest m = VariantManifest.defaults(
                    BuildingVariant.defaultVariantId(type));
            return new BuildingVariant(
                    "default",
                    style,
                    type,
                    m.id(),
                    m.displayName(),
                    new BuildingVariant.Footprint(m.footprintX(), m.footprintZ()),
                    m.minTier(),
                    m.maxTier(),
                    m.weight(),
                    m.preferredTags(),
                    m.stylePreference(),
                    m.agePreference(),
                    m.tags(),
                    m.maxPerVillage(),
                    m.colorSlots(),
                    m.ruinationLevel());
        }
    }
}
