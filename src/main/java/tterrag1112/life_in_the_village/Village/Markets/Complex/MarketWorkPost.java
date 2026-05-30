package tterrag1112.life_in_the_village.Village.Markets.Complex;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;

import java.util.Optional;

/**
 * Stable per-stall vendor work-post: where the stall's owner stands and
 * which way they face (merchant arc Phase 2c). <b>Data only</b> — Phase 3
 * occupancy behaviour consumes this to walk an NPC to its stall and man
 * it, finally giving "NPCs flock to a corner" a real target. 2d's event
 * stalls expose the same post for recruited producers.
 *
 * <p>The vendor stands at the counter (the stall's chest, or its origin
 * if no chest) and faces <b>outward onto the perimeter aisle</b> — the
 * same outward direction the stall front opens, derived geometrically
 * from the stall's position relative to the market building centre (the
 * side it sits on). This matches the aisle-facing rotation the 2b
 * allocator placed it with.
 */
public final class MarketWorkPost {

    private MarketWorkPost() {}

    /** Where the vendor stands and which way they face. */
    public record WorkPost(BlockPos stand, Direction facing) {}

    /**
     * Computes the work-post for a stall, or empty if the stall has no
     * usable anchor. Pure; no world access.
     */
    public static Optional<WorkPost> forStall(Building market, MarketStall stall) {
        if (market == null || stall == null) return Optional.empty();
        BlockPos origin = stall.getStallOrigin();
        if (origin == null) return Optional.empty();
        BlockPos anchor = stall.getChestPos();
        if (anchor == null || anchor.equals(BlockPos.ZERO)) anchor = origin;
        Direction facing = outwardFacing(origin, market);
        return Optional.of(new WorkPost(anchor, facing));
    }

    /** Outward side (toward the aisle) the stall sits on, by dominant axis
     *  from the market centre to the stall origin. */
    private static Direction outwardFacing(BlockPos origin, Building market) {
        net.minecraft.world.phys.AABB box = market.getShape().toAABB();
        double dx = (origin.getX() + 0.5) - box.getCenter().x;
        double dz = (origin.getZ() + 0.5) - box.getCenter().z;
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
