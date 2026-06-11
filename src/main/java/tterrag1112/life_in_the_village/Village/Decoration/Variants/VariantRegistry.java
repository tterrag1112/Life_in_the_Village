package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Doc 15 — registry of {@link BuildingVariant}s indexed by
 * {@link BuildingType}. Rebuilt from
 * {@link VariantManifestLoader#all()} every time a resource reload
 * applies; consumers (matcher, planner, NBT path resolution) read
 * from this registry rather than re-parsing manifests.
 *
 * <p>The registry intentionally holds <em>all</em> cultures and
 * styles in one flat structure. Culture/style filtering is the
 * caller's job — for a given village placement, the matcher decides
 * which (culture, style) pair applies and asks
 * {@link #eligibleFor(BuildingType, Style, VillageSizeTier,
 * AgeCategory)} for matching variants.</p>
 *
 * <p>P0a-03 stops short of variant scoring and weighted selection;
 * those land in P0a-06. {@code eligibleFor} returns every variant
 * that passes hard filters (style folder match + tier window). The
 * matcher's existing single-NBT pickup path keeps working because
 * {@link #defaultVariant(BuildingType, String, Style)} still resolves
 * to the type-named default when no other variant is selected.</p>
 */
public final class VariantRegistry {

    public static final VariantRegistry INSTANCE = new VariantRegistry();

    private static final Logger LOGGER =
            LoggerFactory.getLogger(VariantRegistry.class);

    /** Variants keyed by their full {@link VariantKey}. Insertion-
     *  ordered so iteration is deterministic across reloads. */
    private Map<VariantKey, BuildingVariant> byKey = Map.of();

    /** Per-type lookup. Populated alongside {@link #byKey}. */
    private Map<BuildingType, List<BuildingVariant>> byType = Map.of();

    private VariantRegistry() {}

    // ── Rebuild ───────────────────────────────────────────────────────────

    /**
     * Rebuilds the registry from a snapshot of parsed manifests. Called
     * by {@link VariantManifestLoader#apply} every reload. Variants
     * whose folder names a {@link Style} value the enum doesn't yet
     * know about are skipped with a warning.
     */
    public void rebuild(Map<VariantKey, VariantManifest> manifests) {
        Map<VariantKey, BuildingVariant> nextByKey = new LinkedHashMap<>();
        Map<BuildingType, List<BuildingVariant>> nextByType =
                new LinkedHashMap<>();

        for (Map.Entry<VariantKey, VariantManifest> entry
                : manifests.entrySet()) {
            VariantKey key = entry.getKey();
            if (Style.fromFolder(key.style()).isEmpty()) {
                LOGGER.warn("VariantRegistry: skipping {} — folder style "
                        + "'{}' is not a known Style enum value",
                        key, key.style());
                continue;
            }

            BuildingVariant variant;
            try {
                variant = BuildingVariant.from(key, entry.getValue());
            } catch (RuntimeException e) {
                LOGGER.error("VariantRegistry: failed to register {}: {}",
                        key, e.getMessage());
                continue;
            }
            nextByKey.put(key, variant);
            nextByType
                    .computeIfAbsent(variant.type(), t -> new ArrayList<>())
                    .add(variant);
        }

        Map<BuildingType, List<BuildingVariant>> frozen = new LinkedHashMap<>();
        for (Map.Entry<BuildingType, List<BuildingVariant>> e
                : nextByType.entrySet()) {
            frozen.put(e.getKey(),
                    Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        }

        this.byKey = Collections.unmodifiableMap(nextByKey);
        this.byType = Collections.unmodifiableMap(frozen);

        LOGGER.info("VariantRegistry: rebuilt — {} variants across {} types",
                byKey.size(), byType.size());
    }

    // ── Query API ─────────────────────────────────────────────────────────

    /**
     * Returns the first variant matching {@code (type, variantId)}
     * across any culture/style. Determinism comes from insertion
     * order — manifests load in resource-pack order. Callers that
     * need a specific culture/style should use {@link #find}.
     */
    public Optional<BuildingVariant> resolve(BuildingType type, String variantId) {
        if (variantId == null) return Optional.empty();
        // A1 stage 2 — a piece variant id ({@code row_house:left})
        // resolves to its variant FOLDER's manifest entry: pieces share
        // the folder's metadata (repaint colour slots, tags) and never
        // register individually.
        final String folderId = BuildingVariant.baseId(variantId);
        return byType.getOrDefault(type, List.of()).stream()
                .filter(v -> folderId.equals(v.id()))
                .findFirst();
    }

    /**
     * Exact lookup by full {@link VariantKey}. Used by P0a-04
     * resolution to confirm a manifest exists before constructing
     * the NBT identifier.
     */
    public Optional<BuildingVariant> find(VariantKey key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /**
     * Convenience: lookup by culture + style + type + variantId. Wraps
     * {@link #find(VariantKey)}.
     */
    public Optional<BuildingVariant> find(String culture, Style style,
                                          BuildingType type, String variantId) {
        return find(new VariantKey(culture, style.folder(), type, variantId));
    }

    /**
     * Returns the registered default variant for a type — the one
     * whose folder is named identically to the type. Doc 15: "the
     * variant defaults to the type name, so a HOUSE becomes
     * {@code house/house/level_n}".
     */
    public Optional<BuildingVariant> defaultVariant(BuildingType type,
                                                    String culture,
                                                    Style style) {
        return find(culture, style, type, BuildingVariant.defaultVariantId(type));
    }

    /**
     * Doc 15 eligibility query: variants for {@code type} whose folder
     * style matches {@code style} and whose tier window covers
     * {@code tier}. Age and slot-tag scoring is intentionally not
     * applied here — it's part of P0a-06's score function. Callers
     * receive an unmodifiable view in registration order.
     */
    public List<BuildingVariant> eligibleFor(BuildingType type, Style style,
                                             VillageSizeTier tier,
                                             AgeCategory ageCategory) {
        // ageCategory is accepted for API parity with doc 15's signature
        // and used by P0a-06's scoring; this method intentionally does
        // not filter on it.
        if (style == null || tier == null) return List.of();

        List<BuildingVariant> all = byType.getOrDefault(type, List.of());
        if (all.isEmpty()) return List.of();

        return all.stream()
                .filter(v -> v.style() == style)
                .filter(v -> tierInWindow(tier, v.minTier(), v.maxTier()))
                .collect(Collectors.toUnmodifiableList());
    }

    // ── Diagnostics ───────────────────────────────────────────────────────

    /** All variants for a type across cultures/styles, registration order. */
    public List<BuildingVariant> all(BuildingType type) {
        return byType.getOrDefault(type, List.of());
    }

    /** Total registered variants. Useful for debug commands and tests. */
    public int size() {
        return byKey.size();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static boolean tierInWindow(VillageSizeTier tier,
                                        VillageSizeTier min,
                                        VillageSizeTier max) {
        int t = tier.ordinal();
        return t >= min.ordinal() && t <= max.ordinal();
    }
}
