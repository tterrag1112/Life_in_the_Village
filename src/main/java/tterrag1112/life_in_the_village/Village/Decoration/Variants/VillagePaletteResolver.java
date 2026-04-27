package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import net.minecraft.world.item.DyeColor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Doc 15 §"Color system" — bridges a {@link VillageTypeData} to the
 * {@link ColorPalette} the placer should sample for one building,
 * and applies the doc 15 forced colour overrides.
 *
 * <p>Resolution chain for the palette ({@link
 * #paletteFor(VillageTypeData)}):</p>
 * <ol>
 *   <li>{@link VillageTypeData#getColorPalette()} — explicit
 *       declaration on the village type;</li>
 *   <li>{@link CultureDefaultPalettes#paletteFor(String)} — the
 *       culture's authored default;</li>
 *   <li>{@link ColorPaletteRegistry#NONE} — no tint.</li>
 * </ol>
 *
 * <p>Forced overrides ({@link #planFor planFor} short-circuits before
 * sampling):</p>
 * <ul>
 *   <li>{@code TEMPLE} → primary = WHITE, accent + roof = null.</li>
 *   <li>{@code TOWN_HALL} → primary = {@link
 *       VillageTypeData#getSignatureColor()} when non-null;
 *       otherwise the slot samples normally.</li>
 *   <li>{@code GUILD_HALL*} → would force the guild colour, but
 *       {@code GuildData} carries no colour fields today; we log a
 *       one-time warning per type and fall through to sampling.</li>
 * </ul>
 */
public final class VillagePaletteResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(VillagePaletteResolver.class);

    /** Doc 15 §"Color assignment": neighbour-scan radius (blocks). */
    public static final int NEIGHBOUR_RADIUS = 12;

    /** Tracks guild types we've already warned about so the log
     *  doesn't spam once per placement. */
    private static final Set<BuildingType> WARNED_GUILD_HALL_TYPES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private VillagePaletteResolver() {}

    /** Three-step resolution chain: village → culture default → NONE. */
    public static ColorPalette paletteFor(VillageTypeData typeData) {
        if (typeData == null) {
            return ColorPaletteRegistry.getOrNone(ColorPaletteRegistry.NONE);
        }
        ColorPalette declared = typeData.getColorPalette();
        if (declared != null) return declared;
        return CultureDefaultPalettes.paletteFor(typeData.getCulture());
    }

    /**
     * Builds a {@link TintPass.Plan} for one building. Forced
     * overrides take precedence over palette sampling; for any slot
     * not forced, the resolver runs normal weighted sampling.
     *
     * @param typeData       village type — drives palette + signature
     * @param variant        chosen variant (drives {@code colorSlots})
     * @param rng            placement RNG (caller's deterministic source)
     * @param neighborColors primary colours of buildings within
     *                       {@link #NEIGHBOUR_RADIUS} blocks of the
     *                       new building, used as the soft-exclusion
     *                       set for primary sampling. Pass an empty
     *                       set when the index hasn't accumulated any
     *                       same-radius neighbours yet.
     */
    public static TintPass.Plan planFor(VillageTypeData typeData,
                                        BuildingVariant variant,
                                        Random rng,
                                        Set<DyeColor> neighborColors) {
        if (variant == null || variant.colorSlots().isEmpty()) {
            return TintPass.Plan.NONE;
        }

        BuildingType type = variant.type();

        // ── Forced overrides (P0a-12) ────────────────────────────────────

        // TEMPLE: WHITE primary, accent + roof always null. Doesn't
        // depend on the palette being set.
        if (type == BuildingType.TEMPLE) {
            DyeColor primary = variant.colorSlots().contains(ColorSlot.PRIMARY)
                    ? DyeColor.WHITE : null;
            return new TintPass.Plan(primary, null, null);
        }

        ColorPalette palette = paletteFor(typeData);

        // TOWN_HALL: signature colour overrides primary only when set.
        DyeColor forcedPrimary = null;
        if (type == BuildingType.TOWN_HALL
                && typeData != null
                && typeData.getSignatureColor() != null) {
            forcedPrimary = typeData.getSignatureColor();
        }

        // Guild halls: GuildData has no colour fields today so we
        // can't honour the override. Log once per type and fall
        // through to palette sampling. P0a-12 spec line "If a guild
        // hall's GuildData doesn't carry a color, fall through".
        if (isGuildHallType(type)
                && WARNED_GUILD_HALL_TYPES.add(type)) {
            LOGGER.warn("VillagePaletteResolver: guild-hall colour "
                    + "override skipped for {} — GuildData has no "
                    + "colour fields. Tints from palette like a "
                    + "regular building.", type);
        }

        // ── Palette sampling for non-forced slots ────────────────────────

        if (palette.isNoOp()) {
            // Even with NONE palette, a forced override might still
            // produce a colour. Most commonly TEMPLE is handled above
            // already, but TOWN_HALL with an explicit signatureColor
            // should still tint even on NONE palette.
            if (forcedPrimary != null
                    && variant.colorSlots().contains(ColorSlot.PRIMARY)) {
                return new TintPass.Plan(forcedPrimary, null, null);
            }
            return TintPass.Plan.NONE;
        }

        Set<DyeColor> softExclude = neighborColors != null
                ? neighborColors : Set.of();

        // PRIMARY — forced override if present, else weighted sample
        // with neighbour soft-exclusion. If soft-excluded sampling
        // yields null (every weight collapsed), retry without the soft
        // set per the P0a-11 fallback safety net.
        DyeColor primary = forcedPrimary;
        if (primary == null && variant.colorSlots().contains(ColorSlot.PRIMARY)) {
            primary = palette.sample(ColorSlot.PRIMARY, rng,
                    Set.of(), softExclude);
            if (primary == null && !softExclude.isEmpty()) {
                primary = palette.sample(ColorSlot.PRIMARY, rng,
                        Set.of(), Set.of());
            }
        }

        // ACCENT — sample excluding the chosen primary.
        Set<DyeColor> excludeAccent = primary != null
                ? java.util.Collections.singleton(primary) : Set.of();
        DyeColor accent = variant.colorSlots().contains(ColorSlot.ACCENT)
                ? palette.sample(ColorSlot.ACCENT, rng, excludeAccent)
                : null;

        // ROOF — sample excluding the chosen primary.
        Set<DyeColor> excludeRoof = primary != null
                ? java.util.Collections.singleton(primary) : Set.of();
        DyeColor roof = variant.colorSlots().contains(ColorSlot.ROOF)
                ? palette.sample(ColorSlot.ROOF, rng, excludeRoof)
                : null;

        return new TintPass.Plan(primary, accent, roof);
    }

    private static boolean isGuildHallType(@Nullable BuildingType type) {
        if (type == null) return false;
        if (type == BuildingType.GUILD_HALL) return true;
        return type.name().startsWith("GUILD_HALL_");
    }
}
