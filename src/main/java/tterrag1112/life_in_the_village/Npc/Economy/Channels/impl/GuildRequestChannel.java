package tterrag1112.life_in_the_village.Npc.Economy.Channels.impl;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelQuote;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelType;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.EconomicChannel;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeIntent;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeResult;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;

/**
 * Phase 4 stub. Spec line 134; doc 28 wires the cross-village request
 * board fully. v1 always returns "not available" so the channel is
 * registered (so Phase 4 only has to swap the impl) but never wins a
 * router pass.
 */
public final class GuildRequestChannel implements EconomicChannel {

    @Override public ChannelType type() { return ChannelType.GUILD_REQUEST; }

    @Override public int basePriority() { return 40; }

    @Override
    public boolean isAvailable(Village village, VillageSavedData data, ServerLevel level, long tick) {
        return false; // Phase 4 doc 28 fills in.
    }

    @Override
    public Optional<ChannelQuote> quote(TradeIntent intent, Village village,
                                        VillageSavedData data, ServerLevel level) {
        return Optional.empty();
    }

    @Override
    public TradeResult execute(ChannelQuote quote, TradeIntent intent, ServerLevel level) {
        return TradeResult.fail("GUILD_REQUEST channel is a Phase 4 stub");
    }
}
