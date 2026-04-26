package tterrag1112.life_in_the_village.Npc.Economy.Channels;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * One channel's offer for a {@link TradeIntent}. Spec line 58.
 *
 * @param channel              which channel produced this quote
 * @param intent               the original intent (kept so router /
 *                             execute don't have to thread it separately)
 * @param pricePerUnit         bronze per unit (positive — direction is
 *                             on {@link #intent})
 * @param availableQuantity    how many units the channel can actually
 *                             supply (may be less than {@code intent.quantity});
 *                             execute should clamp to this
 * @param travelTimeTicks      estimated travel cost for the actor to
 *                             reach the channel's location; 0 when
 *                             on-site / no travel
 * @param quoteValidUntilTick  router-level expiry; if a caller defers
 *                             execute past this tick, refresh the quote
 *                             via {@link ChannelRouter#findBestChannel}
 *                             (spec "Things to flag" #1)
 * @param location             optional pickup location (workshop, market,
 *                             stockpile); null when not spatially scoped
 */
public record ChannelQuote(
        ChannelType channel,
        TradeIntent intent,
        long pricePerUnit,
        int availableQuantity,
        int travelTimeTicks,
        long quoteValidUntilTick,
        @Nullable BlockPos location
) {
    public ChannelQuote {
        if (channel == null) throw new IllegalArgumentException("channel is required");
        if (intent == null) throw new IllegalArgumentException("intent is required");
        if (pricePerUnit < 0) pricePerUnit = 0;
        if (availableQuantity < 0) availableQuantity = 0;
        if (travelTimeTicks < 0) travelTimeTicks = 0;
    }

    public boolean isExpired(long currentTick) {
        return quoteValidUntilTick > 0L && currentTick > quoteValidUntilTick;
    }

    /**
     * Total bronze the actor would spend / receive at {@link
     * #availableQuantity} units. Useful for printing in debug commands
     * and for the actor's affordability check before {@code execute}.
     */
    public long totalBronze() {
        return pricePerUnit * availableQuantity;
    }
}
