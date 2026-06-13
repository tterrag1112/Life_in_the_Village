package tterrag1112.life_in_the_village.Npc.Economy.Channels;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.impl.CaravanChannel;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.impl.DirectBusinessChannel;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.impl.GuildRequestChannel;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.impl.MarketChannel;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.impl.StockpileChannel;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.impl.VisitorChannel;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Static router that scores every available {@link EconomicChannel}'s
 * quote for an intent and returns the best. Spec section
 * "ChannelRouter" (line 76) and "Scoring" (line 158).
 *
 * <p>Channels are stateless singletons registered at first use; the
 * registration list is fixed v1 (no plugin hooks). Phase 4 follow-up
 * may swap the lookup for a registry to allow content packs to add
 * their own channels.</p>
 */
public final class ChannelRouter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Insertion-ordered list of registered channels. */
    private static final List<EconomicChannel> CHANNELS = buildChannelList();

    private ChannelRouter() {}

    private static List<EconomicChannel> buildChannelList() {
        List<EconomicChannel> out = new ArrayList<>();
        out.add(new MarketChannel());
        out.add(new DirectBusinessChannel());
        out.add(new CaravanChannel());
        out.add(new GuildRequestChannel()); // stub
        out.add(new StockpileChannel());
        out.add(new VisitorChannel());      // stub
        return Collections.unmodifiableList(out);
    }

    /** Read-only view of every registered channel. */
    public static List<EconomicChannel> registeredChannels() {
        return CHANNELS;
    }

    // ── Lookup ─────────────────────────────────────────────────────────────

    /**
     * Top-scoring channel quote for {@code intent}, or empty when no
     * channel can fill it.
     */
    public static Optional<ChannelQuote> findBestChannel(TradeIntent intent, Village village,
                                                         VillageSavedData data, ServerLevel level) {
        // noon-meal-perf — coarse quote-pipeline attribution (accumulate-always,
        // report-rarely via NoonProfile). The router quote loop is one of the
        // three meal-window hot buckets.
        long t0 = System.nanoTime();
        try {
            List<ChannelQuote> quotes = collectQuotes(intent, village, data, level);
            if (quotes.isEmpty()) return Optional.empty();
            quotes.sort((a, b) -> Double.compare(score(b, intent), score(a, intent)));
            return Optional.of(quotes.get(0));
        } finally {
            tterrag1112.life_in_the_village.Events.NoonProfile
                    .addQuote(System.nanoTime() - t0);
        }
    }

    /**
     * Returns every available channel's quote, sorted descending by
     * score. Used by {@code /economy quote} debug to show the full
     * ranking.
     */
    public static List<ChannelQuote> rankAllChannels(TradeIntent intent, Village village,
                                                      VillageSavedData data, ServerLevel level) {
        List<ChannelQuote> quotes = collectQuotes(intent, village, data, level);
        quotes.sort((a, b) -> Double.compare(score(b, intent), score(a, intent)));
        return Collections.unmodifiableList(quotes);
    }

    private static List<ChannelQuote> collectQuotes(TradeIntent intent, Village village,
                                                     VillageSavedData data, ServerLevel level) {
        long now = level.getGameTime();
        List<ChannelQuote> quotes = new ArrayList<>();
        for (EconomicChannel channel : CHANNELS) {
            try {
                if (!channel.isAvailable(village, data, level, now)) continue;
                channel.quote(intent, village, data, level).ifPresent(quotes::add);
            } catch (Throwable t) {
                LOGGER.warn("[ChannelRouter] {} threw during quote for {}: {}",
                        channel.type(), intent.shortDescription(), t.getMessage());
            }
        }
        return quotes;
    }

    // ── Scoring ────────────────────────────────────────────────────────────

    /**
     * Spec score formula (line 95):
     * <pre>
     * score = basePriority(channel.type)
     *       - (BUY ? +pricePenalty : -pricePenalty)   // BUY: cheaper wins; SELL: dearer wins
     *       - travelTimeTicks / 200.0
     *       + (urgency == IMMEDIATE ? +20 : 0)
     *       + (urgency == PATIENT ? +10 if cheap : 0)
     * </pre>
     *
     * <p>Tiebreak (spec "Edge cases", line 275): priority → travel →
     * price. The descending sort already puts higher score first; the
     * scoring formula folds the tiebreak chain into the value so a
     * stable Comparable suffices.</p>
     */
    public static double score(ChannelQuote quote, TradeIntent intent) {
        double s = priorityFor(quote.channel());
        double pricePenalty = quote.pricePerUnit() / 100.0;
        if (intent.direction() == TradeDirection.BUY) s -= pricePenalty;
        else                                          s += pricePenalty;
        s -= quote.travelTimeTicks() / 200.0;
        if (intent.urgency() == Urgency.IMMEDIATE) s += 20.0;
        // PATIENT bias: prefer cheap-and-slow over fast-and-pricey.
        // Adds a small bonus when the channel's price is in the lower
        // half vs the requested price range (BUY only — symmetric for
        // SELL would penalise fast sales which usually cost more).
        if (intent.urgency() == Urgency.PATIENT && intent.direction() == TradeDirection.BUY
                && intent.maxPrice() > 0 && quote.pricePerUnit() <= intent.maxPrice() / 2L) {
            s += 5.0;
        }
        return s;
    }

    private static int priorityFor(ChannelType type) {
        for (EconomicChannel c : CHANNELS) {
            if (c.type() == type) return c.basePriority();
        }
        return 0;
    }
}
