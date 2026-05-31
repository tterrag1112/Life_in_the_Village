package tterrag1112.life_in_the_village.Village.Markets.Complex;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.Complex.MarketComplexSpec;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.BuildingVariant;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.VariantRegistry;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Seeds a few vacant ("for rent") stalls onto a freshly graded market pad
 * at spawn (merchant arc Phase 2b). Runs once, in the V2 spawn hook,
 * right after the 2a pad render — the region geometry is in hand there, so
 * no runtime region recompute is needed.
 *
 * <p>Seeded stalls are owned by {@link MarketStall#VACANT_UUID} and never
 * expire ({@code rentPaidUntilTick = MAX}); the claim path assigns a real
 * owner to one of these. Stall <em>tending</em> behaviour is Phase 3; the
 * stall's chest is the goods endpoint 2c makes authoritative.
 */
public final class MarketStallSeeder {

    private static final Logger LOGGER = LoggerFactory.getLogger(MarketStallSeeder.class);

    /** How many stalls to seed at spawn (allocator stops earlier if the
     *  pad fills). Conservative; the rest of the pad stays free for
     *  runtime claims and event (2d) stalls. */
    public static final int SEED_COUNT = 4;

    private MarketStallSeeder() {}

    /**
     * Places up to {@link #SEED_COUNT} vacant stalls on the pad and
     * registers their {@link MarketStall} records. Returns how many were
     * placed.
     */
    public static int seed(ServerLevel level, VillageSavedData data, Building market,
                           Polygon region, Polygon footprint, int padY,
                           MarketComplexSpec spec) {
        if (spec == null || spec.stallPool().isEmpty()) return 0;
        if (region == null || footprint == null) return 0;

        // Pull the seed pool from the authored MARKET_STALL variant
        // registry (default/rural/market_stall/{id}) so newly-authored
        // stall variants are actually used. The spec's stallPool is the
        // fallback when no variants are registered (keeps 2b behaviour).
        // Each entry's directNbt is the variant-layout path; StallAllocator
        // resolves it through CultureResolver first and only drops to the
        // legacy direct path if a variant's NBT is missing.
        List<StallVariant> pool = resolveStallPool(spec);

        List<BoundingBox> occupied = new ArrayList<>();
        int baseSlot = data.getStallsForMarket(market.getId()).size();
        int placed = 0;
        for (int i = 0; i < SEED_COUNT; i++) {
            // Round-robin across the pool so a market shows a mix of
            // variants rather than four identical stalls.
            StallVariant variant = pool.get(i % pool.size());
            Optional<MarketStall> s = StallAllocator.place(
                    level, market, region, footprint, padY, variant,
                    MarketStall.VACANT_UUID, MarketStall.OwnerType.NPC, "",
                    Long.MAX_VALUE, baseSlot + i, occupied);
            if (s.isEmpty()) break; // pad full
            data.addMarketStall(s.get());
            placed++;
        }
        if (placed > 0) {
            LOGGER.info("[MarketStallSeeder] seeded {} vacant stall(s) at market {}",
                    placed, market.getName());
        } else {
            // Phase 2b bug-fix: never fail silently again. Zero placed means
            // the allocator found no seat on the first try — almost always
            // the usable perimeter band (margin − aisle) is shallower than
            // the stall footprint (the pad shrank past the stall depth). Log
            // the region size + variant so it's diagnosable from the pad.
            Polygon.AABB r = Polygon.boundingBox(region);
            LOGGER.warn("[MarketStallSeeder] seeded ZERO stalls at market {} — "
                    + "no seat fit. Pad region {}x{} (x[{}..{}] z[{}..{}]), "
                    + "first variant '{}'. Likely the pad band is shallower than "
                    + "the stall footprint; raise padMargin/minPadMargin in "
                    + "MarketComplexRegistry.",
                    market.getName(),
                    (r.maxX() - r.minX() + 1), (r.maxZ() - r.minZ() + 1),
                    r.minX(), r.maxX(), r.minZ(), r.maxZ(), pool.get(0).id());
        }
        return placed;
    }

    /**
     * Builds the seed pool from the authored {@code MARKET_STALL} variant
     * manifests (RURAL) — one {@link StallVariant} per registered variant,
     * carrying the variant {@code id} (so {@code StallAllocator} resolves
     * {@code default/rural/market_stall/{id}/level_1} through
     * {@code CultureResolver}) and a shared legacy fallback NBT for the
     * not-yet-authored case. Falls back to the spec's {@code stallPool}
     * when the registry has no MARKET_STALL variants (preserves the 2b
     * single-authored-stall behaviour). Never returns empty (callers guard
     * {@code stallPool} for emptiness upstream).
     */
    private static List<StallVariant> resolveStallPool(MarketComplexSpec spec) {
        List<BuildingVariant> registered =
                VariantRegistry.INSTANCE.all(BuildingType.MARKET_STALL);
        // directNbt is the FALLBACK used only when a variant's own
        // variant-layout NBT (default/rural/market_stall/{id}/level_1) is
        // missing — StallAllocator.resolveTemplate tries the variant path
        // (by id) first. So point the fallback at the legacy known-good
        // stall NBT, never the variant's own (possibly-unauthored) path,
        // or a manifest-only variant would seed nothing. Take it from the
        // spec's first pool entry so the legacy location stays in one place.
        net.minecraft.resources.Identifier fallbackNbt =
                spec.stallPool().get(0).directNbt();
        List<StallVariant> pool = new ArrayList<>();
        for (BuildingVariant v : registered) {
            if (v.style() != Style.RURAL) continue;
            int weight = Math.max(1, Math.round(v.weight()));
            pool.add(new StallVariant(v.id(), fallbackNbt, weight));
        }
        if (pool.isEmpty()) {
            LOGGER.debug("[MarketStallSeeder] no MARKET_STALL variants registered; "
                    + "falling back to spec stallPool ({} entries)",
                    spec.stallPool().size());
            return spec.stallPool();
        }
        return pool;
    }
}
