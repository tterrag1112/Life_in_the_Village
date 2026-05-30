package tterrag1112.life_in_the_village.Village.Markets.Complex;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.Complex.MarketComplexSpec;
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
        StallVariant variant = spec.stallPool().get(0); // 2b: single authored variant

        List<BoundingBox> occupied = new ArrayList<>();
        int baseSlot = data.getStallsForMarket(market.getId()).size();
        int placed = 0;
        for (int i = 0; i < SEED_COUNT; i++) {
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
                    + "variant '{}'. Likely the pad band is shallower than the "
                    + "stall footprint; raise padMargin/minPadMargin in "
                    + "MarketComplexRegistry.",
                    market.getName(),
                    (r.maxX() - r.minX() + 1), (r.maxZ() - r.minZ() + 1),
                    r.minX(), r.maxX(), r.minZ(), r.maxZ(), variant.id());
        }
        return placed;
    }
}
