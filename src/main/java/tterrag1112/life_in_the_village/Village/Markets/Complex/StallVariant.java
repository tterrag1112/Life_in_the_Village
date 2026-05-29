package tterrag1112.life_in_the_village.Village.Markets.Complex;

import net.minecraft.resources.Identifier;

/**
 * One candidate stall structure for the market-complex stall pool
 * (merchant arc Phase 2b). Replaces 2a's opaque {@code List<String>}
 * placeholder with a typed entry the allocator can rank and place.
 *
 * <p>2b ships a single authored stall NBT, resolved by its direct
 * resource path; the {@code weight} + per-variant footprint (read from
 * the loaded template at allocation time, never hardcoded) let the
 * allocator rank/fit multiple variants once more are authored. Promoting
 * stalls to a first-class {@code MARKET_STALL} variant-system type
 * (CultureResolver + manifests) is deferred — see {@code
 * MERCHANT_PROGRESS.md} Phase 2b "Deviations" — because it requires a
 * BuildingType enum addition (60-file switch audit) and binary NBT/
 * manifest asset authoring, neither safe to do without a compiler.
 *
 * @param id     stable variant id (for logs / future manifest keying).
 * @param nbt    resource location of the stall structure template.
 * @param weight selection weight (higher = preferred); ties broken by
 *               pool order.
 */
public record StallVariant(String id, Identifier nbt, int weight) {
    public StallVariant {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("StallVariant id required");
        if (nbt == null) throw new IllegalArgumentException("StallVariant nbt required");
        if (weight <= 0) weight = 1;
    }
}
