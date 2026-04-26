package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Gossip.GossipChannel;
import tterrag1112.life_in_the_village.Npc.Gossip.GossipScheduler;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;

import java.util.Optional;

/**
 * "Listen in" — eavesdrop on a nearby gossip channel. Spec line 199.
 * Available when the targeted NPC participates in an active channel
 * within {@link GossipScheduler#LISTEN_IN_RADIUS} of the player. The
 * result returns the latest exchange in {@code statusText} so the host
 * can show it as a chat message; no dialogue tree is opened.
 */
public final class ListenInVerb implements PlayerVerb {

    public static final String VERB_ID = "listen_in";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Listen in"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Eavesdrop on what they're saying."));
    }
    @Override public int displayOrder() { return 50; }
    @Override public int cooldownTicks() { return 200; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        var ch = GossipScheduler.channelFor(ctx.npc().getUUID()).orElse(null);
        if (ch == null) return false;
        // Player must be within listen-in radius of the speaker (or
        // listener — proximity to either side counts).
        TownspersonMob speaker = TownspersonMob.findByUUID(ctx.level(), ch.speakerId()).orElse(null);
        if (speaker == null) return false;
        return ctx.player().distanceToSqr(speaker)
                <= GossipScheduler.LISTEN_IN_RADIUS * GossipScheduler.LISTEN_IN_RADIUS;
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        GossipChannel ch = GossipScheduler.channelFor(ctx.npc().getUUID()).orElse(null);
        if (ch == null) return VerbResult.failure("They aren't in conversation.");
        ch.markInterrupted();
        String content = ch.lastExchangeContent();
        if (content == null || content.isEmpty()) {
            return VerbResult.failure("They haven't said much yet.");
        }
        TownspersonMob speaker = TownspersonMob.findByUUID(ctx.level(), ch.speakerId()).orElse(null);
        TownspersonMob listener = TownspersonMob.findByUUID(ctx.level(), ch.listenerId()).orElse(null);
        String spName = speaker == null ? "someone" : speaker.getNpcName();
        String lnName = listener == null ? "someone else" : listener.getNpcName();
        String overheard = "You overhear " + spName + " telling " + lnName + ": \"" + content + "\"";
        return new VerbResult(true, "", java.util.List.of(), false, overheard);
    }
}
