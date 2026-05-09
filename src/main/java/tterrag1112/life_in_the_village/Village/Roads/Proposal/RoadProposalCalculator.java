package tterrag1112.life_in_the_village.Village.Roads.Proposal;

import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;

import java.util.List;

/**
 * Track C3.1 — derives the cost / progress budget for a planned road
 * proposal from its routed cellPath.
 *
 * <h3>Formula</h3>
 * <ul>
 *   <li>{@code blockCount = cellPath.size() × BLOCKS_PER_CELL}</li>
 *   <li>{@code currencyFeeBronze = blockCount × BASE_COST_PER_BLOCK_BRONZE × tier}
 *       where the tier multiplier is LOCAL=1.0, CONNECTOR=1.5, TRUNK=2.5,
 *       GREAT_ROAD=4.0.</li>
 * </ul>
 *
 * <h3>Anchor</h3>
 * For an 800-block route on the default {@code CONNECTOR} tier, the
 * upfront fee is 6&thinsp;000 bronze ≈ 1.5 gold — meaningful but not
 * punishing for a player who has built a kingdom-scale economy. For a
 * GREAT_ROAD-tier crossing the same span the fee scales to 16&thinsp;000
 * bronze ≈ 4 gold.
 */
public final class RoadProposalCalculator {

    private RoadProposalCalculator() {}

    /** A cellPath cell covers a 16×16 block area; ~16 blocks of road per cell. */
    public static final int BLOCKS_PER_CELL = 16;

    /** Base cost in bronze per block of placed road, before tier multiplier. */
    public static final long BASE_COST_PER_BLOCK_BRONZE = 5L;

    public record Estimate(int totalBlocks, long currencyFeeBronze) {

        public CurrencyValue currencyFee() {
            return CurrencyValue.of(currencyFeeBronze);
        }
    }

    public static Estimate estimate(List<Long> cellPath, RoadEdge.EdgeTier tier) {
        int blockCount = cellPath.size() * BLOCKS_PER_CELL;
        double mult = tierMultiplier(tier);
        long fee = (long) Math.ceil(blockCount * BASE_COST_PER_BLOCK_BRONZE * mult);
        return new Estimate(blockCount, fee);
    }

    private static double tierMultiplier(RoadEdge.EdgeTier tier) {
        return switch (tier) {
            case LOCAL      -> 1.0;
            case CONNECTOR  -> 1.5;
            case TRUNK      -> 2.5;
            case GREAT_ROAD -> 4.0;
        };
    }
}
