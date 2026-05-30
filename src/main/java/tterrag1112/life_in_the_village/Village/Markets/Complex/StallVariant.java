package tterrag1112.life_in_the_village.Village.Markets.Complex;

import net.minecraft.resources.Identifier;

/**
 * One candidate stall structure for the market-complex stall pool
 * (merchant arc Phase 2b). Replaces 2a's opaque {@code List<String>}
 * placeholder with a typed entry the allocator can rank and place.
 *
 * <p>Stalls are a first-class {@code BuildingType.MARKET_STALL} variant-
 * system type: {@code variantId} resolves through {@code CultureResolver}
 * at {@code structures/{culture}/{style}/market_stall/{variantId}/} (the
 * 7-step default fallback + "only authored variants" gate). The allocator
 * resolves the variant path first and falls back to {@link #directNbt}
 * (the legacy {@code default/rural/market/stall/stall_1} location) when
 * the variant template isn't authored yet — so stalls keep placing with
 * the one existing NBT until it is relocated into the variant layout. The
 * {@code weight} + per-variant footprint (read from the loaded template at
 * allocation time, never hardcoded) let the allocator rank/fit multiple
 * variants once more are authored.
 *
 * <p>Culture + style live in the path; profession + size are manifest
 * axes (free-form tags / optional fields), not path segments. A foreign-
 * merchant stall is an allocator selection policy that draws a variant
 * from another culture's folder — not a path-layout change.
 *
 * @param id        stable variant id (also the {@code variantId} path
 *                  segment for {@code CultureResolver}).
 * @param directNbt fallback resource location of the stall template,
 *                  used when the variant-system path is unauthored.
 * @param weight    selection weight (higher = preferred); ties by order.
 */
public record StallVariant(String id, Identifier directNbt, int weight) {
    public StallVariant {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("StallVariant id required");
        if (directNbt == null) throw new IllegalArgumentException("StallVariant directNbt required");
        if (weight <= 0) weight = 1;
    }
}
