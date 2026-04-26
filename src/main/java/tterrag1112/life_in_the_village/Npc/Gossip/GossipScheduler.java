package tterrag1112.life_in_the_village.Npc.Gossip;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Relations.RelationshipMode;
import tterrag1112.life_in_the_village.Npc.Schedule.ScheduleResolver;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 *
 * <h3>Performance contract</h3>
 * <p>Spec line 72 says "NPCs within 4 blocks of each other" — gossip
 * is village-local. The roller buckets candidates by assigned village
 * before pair-scanning so the inner loop stays O(sum of v_i²) instead
 * of O(N²) over every loaded townsperson. The candidate filter walks
 * each village's bounds via {@code getEntitiesOfClass(TownspersonMob,
 * AABB)} so non-mob entities (items, projectiles) never enter the
 * scan.</p>
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
    /** Inflate village bounds when scanning for in-village NPCs. NPCs
     *  occasionally roam slightly outside their planned bounds (path
     *  jitter, errand goals); this gives 16-block headroom matching
     *  the convention used by the village-guard scan. */
    public static final double VILLAGE_INFLATE = 16.0;

    private GossipScheduler() {}

    /** Active channels keyed by speaker UUID. */
    private static final Map<UUID, GossipChannel> ACTIVE = new LinkedHashMap<>();
    /** Membership index for {@link #channelFor} — kept in lockstep with
     *  {@link #ACTIVE} via {@link #startChannel} / {@link #expireChannel}.
     *  Replaces the old O(channels) stream walk, which became hot when
     *  the scheduler ran on every loaded NPC during the candidate
     *  pre-filter. */
    private static final Set<UUID> PARTICIPANTS = new HashSet<>();

    public static Map<UUID, GossipChannel> activeChannels() { return ACTIVE; }

    // ── Diagnostics (transient, read by /gossip diagnostics) ──────────────

    /**
     * Snapshot of the most recent tick. Updated by
     * {@link #tick}; {@code lastTickMicros} measures wall time spent in
     * the tick body so spikes are visible without a JFR trace.
     */
    public static final class TickStats {
        public long lastTickMicros;
        public int  loadedNpcs;
        public int  socialCandidates;
        public int  pairsExamined;
        public int  pairsInProximity;
        public int  channelsCreated;
        public int  channelsExpired;
        public int  exchangesAttempted;
        public int  exchangesCommitted;

        void reset() {
            loadedNpcs = 0;
            socialCandidates = 0;
            pairsExamined = 0;
            pairsInProximity = 0;
            channelsCreated = 0;
            channelsExpired = 0;
            exchangesAttempted = 0;
            exchangesCommitted = 0;
        }
    }
    private static final TickStats STATS = new TickStats();
    public static TickStats stats() { return STATS; }

    /** Looks up the active channel for a participant on either side. */
    public static Optional<GossipChannel> channelFor(UUID participantId) {
        if (participantId == null || !PARTICIPANTS.contains(participantId)) {
            return Optional.empty();
        }
        for (GossipChannel c : ACTIVE.values()) {
            if (c.involves(participantId)) return Optional.of(c);
        }
        return Optional.empty();
    }

    /** Constant-time membership probe used by the rolling scan. */
    public static boolean isInChannel(UUID participantId) {
        return participantId != null && PARTICIPANTS.contains(participantId);
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
     * Periodic tick (every 20 game ticks per
     * {@code GossipSchedulerTickSystem}). Walks loaded townspersons
     * via per-village bounding boxes, ages active channels, runs
     * exchanges, and rolls the start probability for nearby unpaired
     * SOCIAL pairs inside the same village.
     */
    public static void tick(ServerLevel level, long currentTick) {
        long startNanos = System.nanoTime();
        STATS.reset();

        // 1. Run / expire any active channel.
        tickActiveChannels(level, currentTick);

        // 2. Roll new channels for nearby SOCIAL pairs, bucketed by village.
        rollNewChannels(level, currentTick);

        STATS.lastTickMicros = (System.nanoTime() - startNanos) / 1_000L;
    }

    private static void tickActiveChannels(ServerLevel level, long currentTick) {
        if (ACTIVE.isEmpty()) return;
        List<UUID> expired = null;
        double idleSpacingSq = (START_RADIUS + 2) * (START_RADIUS + 2);
        for (Map.Entry<UUID, GossipChannel> e : ACTIVE.entrySet()) {
            GossipChannel ch = e.getValue();
            TownspersonMob speaker  = TownspersonMob.findByUUID(level, ch.speakerId()).orElse(null);
            TownspersonMob listener = TownspersonMob.findByUUID(level, ch.listenerId()).orElse(null);
            if (speaker == null || listener == null
                    || speaker.distanceToSqr(listener) > idleSpacingSq
                    || currentTick - ch.lastExchangeTick() > IDLE_TIMEOUT_TICKS
                    || ch.exchangeCount() >= ch.context().maxExchanges()) {
                if (expired == null) expired = new ArrayList<>();
                expired.add(e.getKey());
                continue;
            }
            // Roll a per-tick exchange chance — frequency between min and
            // max exchanges over the channel's lifetime.
            if (level.getRandom().nextFloat() < 0.25f) {
                STATS.exchangesAttempted++;
                detectInterruption(level, ch, speaker);
                var result = GossipExchange.run(level, speaker, listener, currentTick);
                if (result.transferred()) {
                    ch.recordExchange(currentTick, result.topic(), result.content());
                    STATS.exchangesCommitted++;
                }
            }
        }
        if (expired != null) {
            for (UUID id : expired) expireChannel(id);
            STATS.channelsExpired = expired.size();
        }
    }

    private static void rollNewChannels(ServerLevel level, long currentTick) {
        VillageSavedData vdata = VillageSavedData.get(level);
        List<Village> villages = vdata.getAllVillages();
        if (villages.isEmpty()) return;
        RandomSource rng = level.getRandom();
        double startRadiusSq = START_RADIUS * START_RADIUS;

        // Per-village bucketing: each village gives a bounded chunk
        // scan via getEntitiesOfClass; pair-scan only sees villagers
        // sharing a village (gossip is village-internal in v1).
        for (Village village : villages) {
            AABB bounds = village.getBounds(vdata).orElse(null);
            if (bounds == null) continue;
            List<TownspersonMob> nearby = level.getEntitiesOfClass(
                    TownspersonMob.class, bounds.inflate(VILLAGE_INFLATE));
            if (nearby.size() < 2) continue;
            STATS.loadedNpcs += nearby.size();

            // Filter: same village + SOCIAL-phase + not already in a
            // channel + non-empty knowledge ledger. The empty-ledger
            // gate is a cheap early-out — an NPC with zero memories
            // can't be a speaker (GossipExchange would bail anyway,
            // but only after the proximity + relationship work).
            List<TownspersonMob> candidates = null;
            for (TownspersonMob mob : nearby) {
                if (PARTICIPANTS.contains(mob.getUUID())) continue;
                if (!belongsToVillage(mob, village)) continue;
                if (!ScheduleResolver.isSocialTime(mob, currentTick)) continue;
                if (mob.getKnowledge().isEmpty()) continue;
                if (candidates == null) candidates = new ArrayList<>();
                candidates.add(mob);
            }
            if (candidates == null || candidates.size() < 2) continue;
            STATS.socialCandidates += candidates.size();

            rollVillageBucket(candidates, rng, startRadiusSq, currentTick);
        }
    }

    private static void rollVillageBucket(List<TownspersonMob> candidates,
                                          RandomSource rng,
                                          double startRadiusSq,
                                          long currentTick) {
        for (int i = 0; i < candidates.size(); i++) {
            TownspersonMob a = candidates.get(i);
            // a may have been paired earlier in this same bucket pass.
            if (PARTICIPANTS.contains(a.getUUID())) continue;
            for (int j = i + 1; j < candidates.size(); j++) {
                TownspersonMob b = candidates.get(j);
                if (PARTICIPANTS.contains(b.getUUID())) continue;
                STATS.pairsExamined++;
                if (a.distanceToSqr(b) > startRadiusSq) continue;
                STATS.pairsInProximity++;

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
                    STATS.channelsCreated++;
                    break; // a is paired up this round
                }
            }
        }
    }

    private static boolean belongsToVillage(TownspersonMob mob, Village village) {
        return mob.getAssignedVillageName()
                .map(name -> name.equals(village.getName()))
                .orElse(false);
    }

    /** Starts a channel and immediately runs the first exchange. */
    public static GossipChannel startChannel(TownspersonMob speaker,
                                             TownspersonMob listener,
                                             GossipContext context,
                                             long tick) {
        GossipChannel ch = new GossipChannel(
                speaker.getUUID(), listener.getUUID(), tick, context);
        ACTIVE.put(speaker.getUUID(), ch);
        PARTICIPANTS.add(speaker.getUUID());
        PARTICIPANTS.add(listener.getUUID());
        return ch;
    }

    private static void expireChannel(UUID speakerId) {
        GossipChannel removed = ACTIVE.remove(speakerId);
        if (removed != null) {
            PARTICIPANTS.remove(removed.speakerId());
            PARTICIPANTS.remove(removed.listenerId());
        }
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
        PARTICIPANTS.clear();
    }
}
