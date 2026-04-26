package tterrag1112.life_in_the_village.Npc.Gossip;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Relations.RelationshipMode;
import tterrag1112.life_in_the_village.Npc.Schedule.ScheduleResolver;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the lifecycle of {@link GossipChannel}s: spins up new ones on
 * each 20-tick check between SOCIAL-phase NPCs in proximity, runs
 * exchanges via {@link GossipExchange}, and ages out idle channels.
 *
 * <p>Per spec line 76 the per-bucket probability formula is
 * {@code 0.10 * relationship_mod * sociability_mod}. Feud-tier pairs
 * (mode == GRUDGE/FEUD on either side) skip entirely.</p>
 *
 * <p>Channels are transient state — never persisted. Static map is
 * rebuilt from scratch on server restart; spec line 245 explicitly
 * marks gossip channels as session-only.</p>
 */
public final class GossipScheduler {

    /** Per-tick channel-start probability (spec line 77). */
    public static final float BASE_START_PROB = 0.10f;
    /** Distance within which two NPCs are eligible to start a channel. */
    public static final double START_RADIUS = 4.0;
    /** Channel idles out this many ticks after the last exchange. */
    public static final long IDLE_TIMEOUT_TICKS = 200L;
    /** Player-overhear distance for ListenInVerb. */
    public static final double LISTEN_IN_RADIUS = 6.0;
    /** Distance under which a visible nearby player marks the channel as interrupted. */
    public static final double INTERRUPTION_RADIUS = 3.0;

    private GossipScheduler() {}

    /** Active channels keyed by speaker UUID. */
    private static final Map<UUID, GossipChannel> ACTIVE = new LinkedHashMap<>();

    public static Map<UUID, GossipChannel> activeChannels() { return ACTIVE; }

    /** Looks up the active channel for a participant on either side. */
    public static Optional<GossipChannel> channelFor(UUID participantId) {
        return ACTIVE.values().stream()
                .filter(c -> c.involves(participantId))
                .findFirst();
    }

    /** Finds the closest active channel within {@code radius} of {@code pos}. */
    public static Optional<GossipChannel> findChannelNear(ServerLevel level,
                                                          BlockPos pos,
                                                          double radius) {
        double r2 = radius * radius;
        GossipChannel best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (GossipChannel c : ACTIVE.values()) {
            TownspersonMob speaker = TownspersonMob.findByUUID(level, c.speakerId()).orElse(null);
            if (speaker == null) continue;
            double d = speaker.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            if (d <= r2 && d < bestDistSq) {
                best = c;
                bestDistSq = d;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Periodic tick from {@link tterrag1112.life_in_the_village.Events.TickSystems}.
     * Walks loaded NPCs once: ages channels, runs exchanges on active
     * channels, and rolls the start probability for any nearby unpaired
     * SOCIAL pair.
     */
    public static void tick(ServerLevel level, long currentTick) {
        // 1. Run / expire any active channel.
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, GossipChannel> e : ACTIVE.entrySet()) {
            GossipChannel ch = e.getValue();
            TownspersonMob speaker  = TownspersonMob.findByUUID(level, ch.speakerId()).orElse(null);
            TownspersonMob listener = TownspersonMob.findByUUID(level, ch.listenerId()).orElse(null);
            if (speaker == null || listener == null
                    || speaker.distanceToSqr(listener) > (START_RADIUS + 2) * (START_RADIUS + 2)
                    || currentTick - ch.lastExchangeTick() > IDLE_TIMEOUT_TICKS
                    || ch.exchangeCount() >= ch.context().maxExchanges()) {
                expired.add(e.getKey());
                continue;
            }
            // Roll a per-tick exchange chance — frequency between min and
            // max exchanges over the channel's lifetime.
            if (level.getRandom().nextFloat() < 0.25f) {
                detectInterruption(level, ch, speaker);
                var result = GossipExchange.run(level, speaker, listener, currentTick);
                if (result.transferred()) {
                    ch.recordExchange(currentTick, result.topic(), result.content());
                }
            }
        }
        for (UUID id : expired) ACTIVE.remove(id);

        // 2. Roll new channels for nearby SOCIAL pairs.
        rollNewChannels(level, currentTick);
    }

    private static void rollNewChannels(ServerLevel level, long currentTick) {
        List<TownspersonMob> social = new ArrayList<>();
        for (var entity : level.getEntities().getAll()) {
            if (!(entity instanceof TownspersonMob mob)) continue;
            if (channelFor(mob.getUUID()).isPresent()) continue;
            if (ScheduleResolver.isSocialTime(mob, currentTick)) social.add(mob);
        }
        RandomSource rng = level.getRandom();
        for (int i = 0; i < social.size(); i++) {
            TownspersonMob a = social.get(i);
            for (int j = i + 1; j < social.size(); j++) {
                TownspersonMob b = social.get(j);
                if (channelFor(a.getUUID()).isPresent()
                        || channelFor(b.getUUID()).isPresent()) continue;
                if (a.distanceToSqr(b) > START_RADIUS * START_RADIUS) continue;

                RelationshipMode aToB = a.getNpcRelationships().getMode(b.getUUID());
                RelationshipMode bToA = b.getNpcRelationships().getMode(a.getUUID());
                if (aToB == RelationshipMode.GRUDGE || aToB == RelationshipMode.FEUD
                        || bToA == RelationshipMode.GRUDGE || bToA == RelationshipMode.FEUD) {
                    continue;
                }

                float modifier = 1f;
                if (aToB == RelationshipMode.FRIEND || aToB == RelationshipMode.CLOSE_FRIEND
                        || bToA == RelationshipMode.FRIEND || bToA == RelationshipMode.CLOSE_FRIEND) {
                    modifier *= 2.0f;
                }
                float aSoc = a.getTraitVector().get(TraitAxis.SOCIABILITY);
                float bSoc = b.getTraitVector().get(TraitAxis.SOCIABILITY);
                if (aSoc > 0) modifier *= (1f + aSoc * 0.5f);
                if (bSoc > 0) modifier *= (1f + bSoc * 0.5f);

                float chance = BASE_START_PROB * modifier;
                if (rng.nextFloat() < chance) {
                    startChannel(a, b, contextByLocation(a), currentTick);
                    break; // a paired up this round
                }
            }
        }
    }

    /** Starts a channel and immediately runs the first exchange. */
    public static GossipChannel startChannel(TownspersonMob speaker,
                                             TownspersonMob listener,
                                             GossipContext context,
                                             long tick) {
        GossipChannel ch = new GossipChannel(
                speaker.getUUID(), listener.getUUID(), tick, context);
        ACTIVE.put(speaker.getUUID(), ch);
        return ch;
    }

    /**
     * Detects player nearby in line-of-audio: marks the channel as
     * interrupted, which downstream slant logic could use to switch to
     * a guarded topic. v1 just records the flag.
     */
    private static void detectInterruption(ServerLevel level,
                                           GossipChannel ch,
                                           TownspersonMob speaker) {
        if (ch.interrupted()) return;
        var nearbyPlayers = level.getNearestPlayer(speaker, INTERRUPTION_RADIUS);
        if (nearbyPlayers != null) ch.markInterrupted();
    }

    /**
     * Picks a {@link GossipContext} from the speaker's surroundings.
     * v1 fallback: derive from the speaker's assigned building type or
     * default to {@code CASUAL_MEETING}. The full sweep over markets,
     * inns, festivals lands when those building types call into the
     * scheduler explicitly.
     */
    public static GossipContext contextByLocation(TownspersonMob speaker) {
        return speaker.getAssignedBuildingId().isPresent()
                ? GossipContext.AT_WORKPLACE
                : GossipContext.CASUAL_MEETING;
    }

    /** Drop every active channel (used by /gossip debug reset). */
    public static void clearAll() {
        ACTIVE.clear();
    }
}
