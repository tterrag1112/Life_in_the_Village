package tterrag1112.life_in_the_village.Npc.Economy.Channels.impl;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelQuote;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelType;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.EconomicChannel;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeDirection;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeIntent;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeResult;
import tterrag1112.life_in_the_village.Npc.Visitor.VisitorState;
import tterrag1112.life_in_the_village.Npc.Visitor.VisitorType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;

/**
 * Phase 4 doc 29 — replaces the Phase 3 stub. The channel becomes
 * available when the village currently hosts a visitor whose
 * {@link VisitorType#carriesGoods} returns true (MERCHANT_ITINERANT
 * or SCHOLAR_VISITING). v1 quotes the buyer's intent at the visitor's
 * implied price (intent.maxPrice()) without inventory-checking the
 * visitor — Phase 5 polish wires actual stall stock.
 */
public final class VisitorChannel implements EconomicChannel {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long QUOTE_VALID_TICKS = 12000L; // half a day
    /** Foreign-trader markup over the buyer's max price. */
    private static final long EXOTIC_MARKUP = 5L;

    @Override public ChannelType type() { return ChannelType.VISITOR; }
    @Override public int basePriority() { return 45; }

    @Override
    public boolean isAvailable(Village village, VillageSavedData data,
                               ServerLevel level, long tick) {
        if (village == null) return false;
        return !findItinerantsIn(level, village, data).isEmpty();
    }

    @Override
    public Optional<ChannelQuote> quote(TradeIntent intent, Village village,
                                        VillageSavedData data, ServerLevel level) {
        if (intent == null || village == null) return Optional.empty();
        if (intent.direction() != TradeDirection.BUY) return Optional.empty();
        if (intent.quantity() <= 0) return Optional.empty();
        List<TownspersonMob> itinerants = findItinerantsIn(level, village, data);
        if (itinerants.isEmpty()) return Optional.empty();

        // v1 quote: intent.maxPrice() + foreign-trader markup. The
        // available quantity caps at the intent count; Phase 5 polish
        // can read the visitor's actual stall inventory.
        long pricePerUnit = Math.max(1L, intent.maxPrice() + EXOTIC_MARKUP);
        long now = level.getGameTime();
        TownspersonMob seller = itinerants.get(0);
        return Optional.of(new ChannelQuote(
                ChannelType.VISITOR, intent,
                pricePerUnit, intent.quantity(),
                /* travel time */ 200,           // ~10 seconds to walk to the stall
                now + QUOTE_VALID_TICKS,
                seller.blockPosition()));
    }

    @Override
    public TradeResult execute(ChannelQuote quote, TradeIntent intent, ServerLevel level) {
        if (quote == null || intent == null) return TradeResult.fail("missing quote/intent");
        if (level == null) return TradeResult.fail("server level required");
        long total = quote.totalBronze();
        // The channel doesn't currently take items off the visitor's
        // inventory — the trade succeeds at the data layer and the
        // bronze flows on the buyer side. Phase 5 polish will:
        //   - withdraw items from the seller's stall record
        //   - deposit bronze into the seller's wallet
        //   - apply a market-tax cut to the village treasury
        LOGGER.debug("[VisitorChannel] {} x {} executed for {} br (no item transfer in v1)",
                intent.quantity(), intent.item(), total);
        return TradeResult.ok(intent.quantity(), total);
    }

    private static List<TownspersonMob> findItinerantsIn(ServerLevel level, Village village,
                                                         VillageSavedData data) {
        AABB bounds = village.getBounds(data).orElse(null);
        if (bounds == null) return List.of();
        return level.getEntitiesOfClass(TownspersonMob.class, bounds.inflate(16),
                m -> {
                    if (!m.isAlive() || !m.isVisitor()) return false;
                    VisitorState s = m.getVisitorState();
                    return s.type().map(VisitorType::carriesGoods).orElse(false);
                });
    }
}
