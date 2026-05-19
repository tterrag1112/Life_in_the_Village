package tterrag1112.life_in_the_village.Village.Economy.Market;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.NpcDailyOffset;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;

import java.util.List;

/**
 * Resolves the {@link BlockPos} a seller-NPC should walk to when visiting
 * a market building. Replaces the legacy single-point
 * {@code market.getShape().getOrigin()} target that caused every NPC in a
 * village to converge on one block.
 *
 * <h3>Fallback order</h3>
 * <ol>
 *   <li>The NPC's own active stall at this market (if any)</li>
 *   <li>A free counter / merchant anchor slot, picked deterministically
 *       per NPC so the population fans out across all stalls</li>
 *   <li>A spread standoff position on a ring around the market origin —
 *       used when soft capacity is reached or no anchors exist</li>
 * </ol>
 *
 * <h3>Soft capacity</h3>
 * Counter use is capped at {@link #SOFT_CAPACITY} concurrent sellers (or
 * the anchor count, whichever is smaller). Overflow sellers receive a
 * standoff position; their {@link Spot#atCounter()} returns {@code false}
 * so callers can defer the actual transaction and retry on their personal
 * offset rather than mobbing the counter.
 */
public final class MarketApproach {

    /** Maximum concurrent sellers actively at counter / anchor positions. */
    public static final int SOFT_CAPACITY = 4;

    /** Ring radius (in blocks) for the standoff fallback. */
    private static final double STANDOFF_RADIUS = 8.0;

    /** Inflate the market AABB by this many blocks when counting active sellers. */
    private static final double OCCUPANCY_INFLATE = 2.0;

    private MarketApproach() {}

    /**
     * Resolved approach target for one NPC.
     *
     * @param pos        block to walk to
     * @param atCounter  {@code true} when the spot is an active selling
     *                   position (own stall, free anchor); {@code false}
     *                   for the standoff ring — caller should wait, not
     *                   transact
     */
    public record Spot(BlockPos pos, boolean atCounter) {}

    public static Spot resolveSellSpot(TownspersonMob npc,
                                       Building market,
                                       VillageSavedData data) {
        if (market == null) return new Spot(BlockPos.ZERO, false);

        // 1) Own active stall at this market
        MarketStall own = data.getStallByOwner(npc.getUUID()).orElse(null);
        if (own != null && own.isActive()
                && market.getId().equals(own.getMarketBuildingId())
                && !own.getStallOrigin().equals(BlockPos.ZERO)) {
            return new Spot(own.getStallOrigin(), true);
        }

        // 2) Free counter / anchor slot — deterministic per-NPC fan-out
        List<BlockPos> anchors = MarketStallPlacer.findAnchorSlots(market, data);
        if (!anchors.isEmpty()) {
            int cap = Math.min(SOFT_CAPACITY, anchors.size());
            int active = countSellersAtMarket(npc, market);
            if (active < cap) {
                int idx = (int) Math.floorMod(
                        (long) npc.getUUID().hashCode(), (long) anchors.size());
                return new Spot(anchors.get(idx), true);
            }
        }

        // 3) Standoff ring — fan out angle/radius via the per-NPC offset
        return new Spot(standoff(npc, market), false);
    }

    private static BlockPos standoff(TownspersonMob npc, Building market) {
        BlockPos origin = market.getShape().getOrigin();
        int off = NpcDailyOffset.offset(npc.getUUID());
        double angle = (off / (double) NpcDailyOffset.SPREAD_TICKS) * Math.PI * 2.0;
        double radius = STANDOFF_RADIUS + (off % 3);
        int dx = (int) Math.round(Math.cos(angle) * radius);
        int dz = (int) Math.round(Math.sin(angle) * radius);
        return origin.offset(dx, 0, dz);
    }

    /**
     * Counts other townspersons currently inside (or adjacent to) the
     * market footprint. Cheap and approximate — the cap is "soft" so
     * occasional miscounts are fine.
     */
    private static int countSellersAtMarket(TownspersonMob self, Building market) {
        if (!(self.level() instanceof ServerLevel level)) return 0;
        var box = market.getShape().toAABB().inflate(OCCUPANCY_INFLATE);
        return level.getEntitiesOfClass(TownspersonMob.class, box,
                mob -> mob != self).size();
    }
}
