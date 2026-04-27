package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.Random;
import java.util.Set;

/**
 * Maps a {@link VillageTypeData} to a {@link ColorPalette} and
 * derives a per-building {@link TintPass.Plan}.
 *
 * <p><b>P0a-10 temporary bridge.</b> Until {@code VillageTypeData}
 * gains a {@code colorPalette} field in P0a-14, the village→palette
 * resolution is hard-wired: default culture gets {@link
 * ColorPaletteRegistry#MUTED_EARTH}, every other culture gets
 * {@link ColorPaletteRegistry#NONE}. P0a-14 replaces
 * {@link #paletteFor(VillageTypeData)} with a real lookup.</p>
 *
 * <p>Forced overrides (TEMPLE → WHITE primary, TOWN_HALL → village
 * signature, guild halls → guild colour) ride on the same plan
 * builder once P0a-12 ships. The TODO marker is in
 * {@link #planFor(VillageTypeData, BuildingVariant, Random)}.</p>
 */
public final class VillagePaletteResolver {

    private VillagePaletteResolver() {}

    /**
     * Resolves the palette for a village. Pre-P0a-14 bridge: default
     * culture → {@link ColorPaletteRegistry#MUTED_EARTH}, anything
     * else → {@link ColorPaletteRegistry#NONE}.
     */
    public static ColorPalette paletteFor(VillageTypeData typeData) {
        if (typeData == null) return ColorPaletteRegistry.getOrNone(
                ColorPaletteRegistry.NONE);
        String culture = typeData.getCulture();
        if (culture != null && culture.equalsIgnoreCase("default")) {
            return ColorPaletteRegistry.getOrNone(ColorPaletteRegistry.MUTED_EARTH);
        }
        return ColorPaletteRegistry.getOrNone(ColorPaletteRegistry.NONE);
    }

    /**
     * Builds a {@link TintPass.Plan} for one building. The variant's
     * declared {@code colorSlots} gates which slots get sampled — a
     * variant with {@code colorSlots: ["PRIMARY"]} skips accent and
     * roof entirely, leaving those tintable blocks untouched and the
     * matching {@link
     * tterrag1112.life_in_the_village.Village.Building} fields null.
     *
     * @param typeData village type — drives the palette
     * @param variant  chosen variant (registry hit or
     *                 {@link VariantSelector.Fallback#syntheticDefault
     *                 synthetic default}); {@code null} treated as
     *                 "no slots" → no-op plan
     * @param rng      placement RNG. P0a-11 will pre-fill the
     *                 exclusion sets used here from neighbouring
     *                 building colours; P0a-10 passes empty sets.
     */
    public static TintPass.Plan planFor(VillageTypeData typeData,
                                        BuildingVariant variant,
                                        Random rng) {
        ColorPalette palette = paletteFor(typeData);
        if (palette.isNoOp() || variant == null
                || variant.colorSlots().isEmpty()) {
            return TintPass.Plan.NONE;
        }

        // TODO P0a-12: forced colour overrides for TEMPLE
        //   (primary=WHITE, others=null), TOWN_HALL (village signature
        //   colour from VillageTypeData), guild halls (guild colour
        //   from GuildData). These should short-circuit before sampling.

        Set<net.minecraft.world.item.DyeColor> excludePrimary = Set.of();
        net.minecraft.world.item.DyeColor primary =
                variant.colorSlots().contains(ColorSlot.PRIMARY)
                        ? palette.sample(ColorSlot.PRIMARY, rng, excludePrimary)
                        : null;

        Set<net.minecraft.world.item.DyeColor> excludeAccent = primary != null
                ? Set.of(primary) : Set.of();
        net.minecraft.world.item.DyeColor accent =
                variant.colorSlots().contains(ColorSlot.ACCENT)
                        ? palette.sample(ColorSlot.ACCENT, rng, excludeAccent)
                        : null;

        Set<net.minecraft.world.item.DyeColor> excludeRoof = primary != null
                ? Set.of(primary) : Set.of();
        net.minecraft.world.item.DyeColor roof =
                variant.colorSlots().contains(ColorSlot.ROOF)
                        ? palette.sample(ColorSlot.ROOF, rng, excludeRoof)
                        : null;

        return new TintPass.Plan(primary, accent, roof);
    }
}
