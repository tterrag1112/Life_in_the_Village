package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.BuildingAvailability;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Track A3 — canonical variant resolution.
 *
 * <p>Combines features previously split between
 * {@link VariantSelector} (decoration-side weighted scoring,
 * maxPerVillage cap, diminishing returns, palette / forced-override
 * tinting) and the deleted {@code V2.Layer3.VariantPicker} (V2 path's
 * HOUSE distance-banding and explicit availability filtering). Both
 * the V1 spawn loop and the V2 spawn adapter route through this
 * class for variant lookup and tint planning.
 *
 * <p>Stateful per village: the {@link #placedCounts} map drives the
 * diminishing-returns multiplier and the {@code maxPerVillage} hard
 * cap. Static methods ({@link #findById}, {@link #planTint}) are
 * stateless and can be reused without owning a resolver instance.
 *
 * <p>Features migrated from {@link VariantSelector}:
 * <ul>
 *   <li>Same-culture preference with {@code default} fallback.</li>
 *   <li>Per-variant {@code maxPerVillage} cap.</li>
 *   <li>Diminishing returns × {@code pow(0.7, alreadyPlacedCount)}.</li>
 *   <li>Weighted-random pick using the caller's {@link Random}.</li>
 *   <li>Synthetic-default fallback when no variant is registered.</li>
 * </ul>
 *
 * <p>Features migrated from the deleted {@code VariantPicker}:
 * <ul>
 *   <li>HOUSE distance-banded preference (large near anchor, cottage
 *       at edge, with graceful degradation).</li>
 *   <li>Explicit availability check via
 *       {@link BuildingAvailability} so V2 only ever names a variant
 *       whose NBT is actually authored.</li>
 * </ul>
 *
 * <p>VariantSelector features that don't fit V2's planning context
 * are intentionally not migrated (slot-tag / village-preferred-tag
 * / style / age scoring); the corresponding methods on
 * {@code VariantSelector} stay in place for the V1 matcher path
 * until V1 is deleted in Track A1b.
 */
public final class VariantResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(VariantResolver.class);

    /** Doc 15: diminishing-returns multiplier base. */
    public static final float DIVERSITY_BONUS_BASE = 0.7f;

    /** All V2 placement happens at level 1 today. */
    private static final int LEVEL = 1;

    /** HOUSE distance bands. */
    private static final double LARGE_THRESHOLD = 0.3;
    private static final double MEDIUM_THRESHOLD = 0.7;

    /** Per-village placement counts keyed by full {@link VariantKey}
     *  so two cultures with the same variant id don't share a counter. */
    private final Map<VariantKey, Integer> placedCounts = new HashMap<>();

    public VariantResolver() {}

    // =========================================================================
    // V2 entry — pick a variant id (PhasedPlanner, V2VillageSpawnerAdapter)
    // =========================================================================

    /**
     * V2 entry: pick a variant id whose NBT is authored. Combines
     * V2's HOUSE distance-banding with V1's culture filter, max-cap,
     * and diminishing-returns scoring.
     *
     * <p>For HOUSE: walks the distance-banded preference list and
     * returns the first available variant that hasn't hit its
     * {@code maxPerVillage} cap; falls back to the next preference
     * if any is capped, then to any available variant
     * deterministically (alphabetical) if all are capped.
     *
     * <p>For other types: filters availability by culture (same
     * culture preferred; {@code default} fallback), drops any variant
     * whose cap has been hit, then runs weighted-random over the
     * remaining candidates using {@link BuildingVariant#weight} ×
     * diminishing-returns. Falls back to the type's default id, then
     * to the first available alphabetically when no candidate is
     * registered.
     *
     * @return variant id string, never null. {@code recordPlacement}
     *         is called internally so the caller does not need to.
     */
    public String pickVariantIdForV2(BuildingType type, BlockPos pos,
                                     BlockPos anchor, int villageRadius,
                                     String culture, Style style,
                                     Random rng,
                                     BuildingAvailability availability) {
        Set<String> available =
                availability.availableVariants(culture, style, type, LEVEL);
        if (available.isEmpty()) {
            throw new IllegalStateException(
                    "VariantResolver.pickVariantIdForV2 called for "
                            + "unavailable type " + type + " in culture " + culture);
        }

        if (type == BuildingType.HOUSE) {
            String chosen = pickHouseByDistance(available, pos, anchor,
                    villageRadius, culture, style);
            recordPlacementById(culture, style, type, chosen);
            return chosen;
        }

        // Build candidate list: registered BuildingVariants in this
        // (culture, style, type) that are also in `available` and
        // haven't hit maxPerVillage. Same-culture preferred; fall back
        // to default culture only when same-culture has no candidate.
        List<BuildingVariant> sameCulture = new ArrayList<>();
        List<BuildingVariant> defaultCulture = new ArrayList<>();
        for (String variantId : available) {
            BuildingVariant v = VariantRegistry.INSTANCE
                    .find(culture, style, type, variantId).orElse(null);
            if (v == null) continue;
            if (v.maxPerVillage() > 0 && countOf(v) >= v.maxPerVillage()) continue;
            if (v.culture().equals(culture)) sameCulture.add(v);
            else if ("default".equals(v.culture())) defaultCulture.add(v);
        }
        List<BuildingVariant> candidates = !sameCulture.isEmpty()
                ? sameCulture : defaultCulture;

        if (candidates.isEmpty()) {
            // No registered+available+uncapped candidate. Prefer the
            // type-default id if it's still in `available`; otherwise
            // pick alphabetically first available for determinism.
            String defaultId = BuildingVariant.defaultVariantId(type);
            String chosen = available.contains(defaultId)
                    ? defaultId : new TreeSet<>(available).first();
            recordPlacementById(culture, style, type, chosen);
            return chosen;
        }

        BuildingVariant chosen;
        if (candidates.size() == 1) {
            chosen = candidates.get(0);
        } else {
            double[] scores = new double[candidates.size()];
            double total = 0.0;
            for (int i = 0; i < candidates.size(); i++) {
                BuildingVariant v = candidates.get(i);
                double s = v.weight();
                int placed = countOf(v);
                if (placed > 0) s *= Math.pow(DIVERSITY_BONUS_BASE, placed);
                if (s < 0) s = 0;
                scores[i] = s;
                total += s;
            }
            if (total <= 0.0) {
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
        }
        recordPlacement(chosen);
        return chosen.id();
    }

    /** HOUSE preference walk with cap-skipping. */
    private String pickHouseByDistance(Set<String> available, BlockPos pos,
                                       BlockPos anchor, int villageRadius,
                                       String culture, Style style) {
        double normDist = normalisedDistance(pos, anchor, villageRadius);
        String[] preferred = orderForHouseDistance(normDist);
        for (String v : preferred) {
            if (!available.contains(v)) continue;
            BuildingVariant variant = VariantRegistry.INSTANCE
                    .find(culture, style, BuildingType.HOUSE, v).orElse(null);
            if (variant != null && variant.maxPerVillage() > 0
                    && countOf(variant) >= variant.maxPerVillage()) continue;
            return v;
        }
        return new TreeSet<>(available).first();
    }

    private static String[] orderForHouseDistance(double normDist) {
        if (normDist < LARGE_THRESHOLD)  return new String[]{"large_house", "house", "cottage"};
        if (normDist < MEDIUM_THRESHOLD) return new String[]{"house", "large_house", "cottage"};
        return new String[]{"cottage", "house", "large_house"};
    }

    /**
     * Side-effect-free: the HOUSE variant id that {@link #pickHouseByDistance}
     * would prefer at {@code pos} — for SIZING a residential district to its
     * real roster (Phase-2 fix-up) WITHOUT recording a placement or consuming a
     * maxPerVillage slot. Distance-banded only (the same size order placement
     * uses), so a tentative district-centre proxy yields the footprint the
     * district will actually hold. Returns null only when {@code available} is
     * empty (caller falls back to the default footprint).
     */
    public String houseVariantForSizing(java.util.Set<String> available,
                                        BlockPos pos, BlockPos anchor,
                                        int villageRadius) {
        if (available.isEmpty()) return null;
        double normDist = normalisedDistance(pos, anchor, villageRadius);
        for (String v : orderForHouseDistance(normDist)) {
            if (available.contains(v)) return v;
        }
        return new TreeSet<>(available).first();
    }

    private static double normalisedDistance(BlockPos pos, BlockPos anchor,
                                             int villageRadius) {
        double dx = pos.getX() - anchor.getX();
        double dz = pos.getZ() - anchor.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        return Math.min(1.0, dist / Math.max(1, villageRadius));
    }

    // =========================================================================
    // Static helpers — id lookup + tint planning (V1 + V2 paths)
    // =========================================================================

    /**
     * Resolves a variant id to a {@link BuildingVariant} via the
     * registry chain, falling back to {@link
     * VariantSelector.Fallback#syntheticDefault} when no entry
     * exists at all. Used by V1's spawn loop (variant id was set on
     * the slot at planning time) and V2's spawn adapter (variant id
     * was set on the {@code PlacedBuilding} by
     * {@link #pickVariantIdForV2}).
     */
    public static BuildingVariant findById(String culture, Style style,
                                           BuildingType type, String variantId) {
        // A1 stage 2 — piece variant ids ({@code row_house:left}) take
        // their manifest metadata (tint colour slots, tags) from the
        // piece's variant FOLDER; only NBT resolution is piece-aware.
        final String folderId = BuildingVariant.baseId(variantId);
        return VariantRegistry.INSTANCE
                .find(culture, style, type, folderId)
                .or(() -> VariantRegistry.INSTANCE
                        .defaultVariant(type, culture, style))
                .or(() -> VariantRegistry.INSTANCE
                        .defaultVariant(type, "default", style))
                .orElseGet(() -> VariantSelector.Fallback
                        .syntheticDefault(type, style));
    }

    /**
     * Builds a {@link TintPass.Plan} mirroring the doc-15
     * forced-override + palette-sampling logic that previously lived
     * in {@code VillagePaletteResolver.planFor}.
     *
     * <p>Forced overrides applied by {@code VillagePaletteResolver}:
     * <ul>
     *   <li>{@code TEMPLE} → primary = {@link DyeColor#WHITE}, accent
     *       and roof null.</li>
     *   <li>{@code TOWN_HALL} → primary = village's signature colour
     *       when present; otherwise samples normally.</li>
     * </ul>
     *
     * <p>Palette sampling resolves through village-declared palette
     * → culture default palette → NONE, with neighbour soft-exclusion
     * for primary and primary exclusion for accent / roof.
     */
    public static TintPass.Plan planTint(VillageTypeData typeData,
                                         BuildingVariant variant,
                                         Random rng,
                                         Set<DyeColor> neighborColors) {
        return planTint(typeData, variant, rng, neighborColors,
                tterrag1112.life_in_the_village.Guilds.Common.GuildPalette.NONE);
    }

    /**
     * Track B1 overload — adds caller-supplied forced overrides on
     * top of the doc-15 type-derived overrides. Used by the V2 spawn
     * adapter for guild halls: P0a-12 wires the guild's
     * {@link tterrag1112.life_in_the_village.Guilds.Common.GuildPalette}
     * into this slot so the hall paints in the guild's identity
     * colours regardless of the village palette.
     *
     * <p>Precedence: type-derived TEMPLE / TOWN_HALL overrides apply
     * first (delegated to {@code VillagePaletteResolver.planFor});
     * then {@code forced} overrides any non-null colour in the result
     * — so a guild hall whose type isn't TEMPLE / TOWN_HALL takes
     * the guild's colours as its final tint.
     *
     * <p>Pass {@link tterrag1112.life_in_the_village.Guilds.Common.GuildPalette#NONE}
     * (or {@code null}) for no forced overrides — equivalent to the
     * 4-arg overload.
     */
    public static TintPass.Plan planTint(
            VillageTypeData typeData,
            BuildingVariant variant,
            Random rng,
            Set<DyeColor> neighborColors,
            tterrag1112.life_in_the_village.Guilds.Common.GuildPalette forced) {
        TintPass.Plan base = VillagePaletteResolver.planFor(typeData, variant, rng,
                neighborColors != null ? neighborColors : Set.of());
        if (forced == null || forced.isEmpty() || variant == null) {
            return base;
        }
        // Forced overrides only paint slots the variant actually
        // declares; if the variant has no ROOF slot, a forced roof
        // colour is irrelevant.
        DyeColor primary = forced.primaryColor() != null
                && variant.colorSlots().contains(ColorSlot.PRIMARY)
                ? forced.primaryColor() : base.primaryColor();
        DyeColor accent = forced.accentColor() != null
                && variant.colorSlots().contains(ColorSlot.ACCENT)
                ? forced.accentColor() : base.accentColor();
        DyeColor roof = forced.roofColor() != null
                && variant.colorSlots().contains(ColorSlot.ROOF)
                ? forced.roofColor() : base.roofColor();
        return new TintPass.Plan(primary, accent, roof);
    }

    // =========================================================================
    // Internals — placement counting
    // =========================================================================

    private void recordPlacement(BuildingVariant v) {
        placedCounts.merge(keyOf(v), 1, Integer::sum);
    }

    /**
     * Records a placement for an id that wasn't a registered
     * {@link BuildingVariant} (synthetic fallback / pure-availability
     * pick). Cap tracking is best-effort — if the registry has the
     * variant we cap-track normally; otherwise we still record so a
     * subsequent registered pick of the same id sees the count.
     */
    private void recordPlacementById(String culture, Style style,
                                     BuildingType type, String variantId) {
        VariantRegistry.INSTANCE.find(culture, style, type, variantId)
                .ifPresentOrElse(
                        this::recordPlacement,
                        () -> placedCounts.merge(
                                new VariantKey(culture, style.folder(), type, variantId),
                                1, Integer::sum));
    }

    private int countOf(BuildingVariant v) {
        return placedCounts.getOrDefault(keyOf(v), 0);
    }

    private static VariantKey keyOf(BuildingVariant v) {
        return new VariantKey(v.culture(), v.style().folder(),
                v.type(), v.id());
    }
}
