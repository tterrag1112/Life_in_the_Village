package tterrag1112.life_in_the_village.Npc.Gossip;

import java.util.UUID;

/**
 * A transient gossip session between two NPCs. Held in memory only by
 * {@link GossipScheduler}; never persisted (spec line 245).
 *
 * <p>{@code lastExchangeContent} is the last line of dialogue produced
 * by the channel — the player's "Listen in" verb returns this string.
 * {@code interrupted} flips when a player is detected nearby in line of
 * audio range; the next exchange picks a guarded topic.</p>
 */
public final class GossipChannel {

    private final UUID speakerId;
    private final UUID listenerId;
    private final long startedTick;
    private final GossipContext context;
    private long lastExchangeTick;
    private int exchangeCount;
    private String lastExchangeContent;
    private String lastExchangeTopic;
    private boolean interrupted;

    public GossipChannel(UUID speakerId, UUID listenerId,
                         long startedTick, GossipContext context) {
        this.speakerId = speakerId;
        this.listenerId = listenerId;
        this.startedTick = startedTick;
        this.context = context;
        this.lastExchangeTick = startedTick;
        this.exchangeCount = 0;
        this.lastExchangeContent = "";
        this.lastExchangeTopic = "";
    }

    public UUID speakerId()           { return speakerId; }
    public UUID listenerId()          { return listenerId; }
    public long startedTick()         { return startedTick; }
    public GossipContext context()    { return context; }
    public long lastExchangeTick()    { return lastExchangeTick; }
    public int exchangeCount()        { return exchangeCount; }
    public String lastExchangeContent() { return lastExchangeContent; }
    public String lastExchangeTopic() { return lastExchangeTopic; }
    public boolean interrupted()      { return interrupted; }

    public void recordExchange(long tick, String topic, String content) {
        this.lastExchangeTick = tick;
        this.lastExchangeTopic = topic == null ? "" : topic;
        this.lastExchangeContent = content == null ? "" : content;
        this.exchangeCount++;
    }

    public void markInterrupted() { this.interrupted = true; }

    /** True when the pair stops being a participant (one is the other party). */
    public boolean involves(UUID id) {
        return speakerId.equals(id) || listenerId.equals(id);
    }
}
