package tterrag1112.life_in_the_village.Npc.Economy.Channels.impl;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Guilds.Common.AbstractGuild;
import tterrag1112.life_in_the_village.Guilds.Common.GuildSavedData;
import tterrag1112.life_in_the_village.Guilds.Common.Requests.Request;
import tterrag1112.life_in_the_village.Guilds.Common.Requests.RequestPosting;
import tterrag1112.life_in_the_village.Guilds.Common.Requests.RequestScope;
import tterrag1112.life_in_the_village.Guilds.Common.Requests.RequestType;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelQuote;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelType;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.EconomicChannel;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeDirection;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeIntent;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeResult;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.Urgency;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;

/**
 * Phase 4 doc 28 — replaces the Phase 3 stub at
 * {@code 23-economic-channels.md} line 134. The channel only quotes
 * for time-insensitive intents (NORMAL / PATIENT urgency); IMMEDIATE
 * intents bypass it because requests deferred by ~7 days don't
 * satisfy "I need it now."
 *
 * <p>{@link #execute} posts a {@link Request} on behalf of the
 * buyer's guild. The actor's bronze covers the bounty + the
 * platform fee inside the guild's treasury — the channel doesn't
 * deliver items at execute time; fulfilment lands when the
 * accepting guild's caravan arrives. v1 treats execute as a partial
 * success ({@code quantityTraded=0}, full {@code totalBronze}
 * reserved) so the router records that a deferral was filed without
 * counting it as a delivery.</p>
 */
public final class GuildRequestChannel implements EconomicChannel {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Spec line 220 — quoted price = intent maxPrice + 10 br markup. */
    private static final long REQUEST_MARKUP = 10L;
    /** Spec line 221 — fulfilment expected within ~1 week. */
    private static final int  TRAVEL_TIME_TICKS = 7 * 24000;
    /** Quote validity window. */
    private static final long QUOTE_VALID_TICKS = 24000L;

    @Override public ChannelType type() { return ChannelType.GUILD_REQUEST; }
    @Override public int basePriority() { return 40; }

    @Override
    public boolean isAvailable(Village village, VillageSavedData data,
                               ServerLevel level, long tick) {
        if (village == null) return false;
        return GuildSavedData.get(level).forVillage(village.getId()).stream()
                .anyMatch(AbstractGuild::canPostRequests);
    }

    @Override
    public Optional<ChannelQuote> quote(TradeIntent intent, Village village,
                                        VillageSavedData data, ServerLevel level) {
        if (intent == null || village == null) return Optional.empty();
        if (intent.direction() != TradeDirection.BUY) return Optional.empty();
        if (intent.urgency() == Urgency.IMMEDIATE) return Optional.empty();
        if (intent.quantity() <= 0) return Optional.empty();
        if (!isAvailable(village, data, level, level.getGameTime())) return Optional.empty();

        long pricePerUnit = Math.max(1L, intent.maxPrice() + REQUEST_MARKUP);
        long now = level.getGameTime();
        return Optional.of(new ChannelQuote(
                ChannelType.GUILD_REQUEST,
                intent,
                pricePerUnit,
                intent.quantity(),
                TRAVEL_TIME_TICKS,
                now + QUOTE_VALID_TICKS,
                /* location */ null));
    }

    @Override
    public TradeResult execute(ChannelQuote quote, TradeIntent intent, ServerLevel level) {
        if (quote == null || intent == null) return TradeResult.fail("missing quote/intent");
        if (level == null) return TradeResult.fail("server level required");

        // Find the buyer's guild — first L1+ guild matching the
        // actor's primary guild. Falls back to any L1+ guild in the
        // actor's village so unaffiliated citizens can still post.
        List<AbstractGuild> candidates = GuildSavedData.get(level).forMember(intent.actorId());
        AbstractGuild actorGuild = candidates.stream()
                .filter(AbstractGuild::canPostRequests)
                .findFirst().orElse(null);
        if (actorGuild == null) {
            actorGuild = GuildSavedData.get(level).forVillage(intent.villageId()).stream()
                    .filter(AbstractGuild::canPostRequests)
                    .findFirst().orElse(null);
        }
        if (actorGuild == null) {
            return TradeResult.fail("no eligible guild can post on actor's behalf");
        }
        long bounty   = quote.totalBronze();
        long deadline = level.getGameTime() + TRAVEL_TIME_TICKS;
        Optional<Request> posted = RequestPosting.post(level, actorGuild,
                RequestType.GATHER,
                RequestPosting.RequestSpec.items(intent.item(), intent.quantity(),
                        bounty, deadline, RequestScope.VILLAGE_PUBLIC));
        if (posted.isEmpty()) {
            return TradeResult.fail("guild treasury could not cover bounty + fee");
        }
        LOGGER.info("[GuildRequestChannel] Posted request {} for {} x {} via {}",
                posted.get().requestId(), intent.quantity(), intent.item(), actorGuild.name());
        // Partial success: request filed, no items delivered yet.
        // quantityTraded=0 reflects "we filed for you" while
        // totalBronze captures the escrow movement.
        return new TradeResult(true, 0, bounty, "");
    }
}
