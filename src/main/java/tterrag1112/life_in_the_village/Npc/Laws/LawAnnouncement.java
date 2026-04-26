package tterrag1112.life_in_the_village.Npc.Laws;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Gossip.GossipTopicHeat;
import tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.UUID;

/**
 * Lifecycle helpers fired around law enact / repeal. Spec lines 196-201.
 *
 * <ul>
 *   <li><b>Gossip seed.</b> Bumps the village's
 *   {@link GossipTopicHeat} for a topic key derived from the law id —
 *   future Phase 2 task 12 gossip exchanges already pick the topic from
 *   topic heat, so the law's enactment becomes a chat-able event for
 *   free.</li>
 *   <li><b>Mood shock.</b> Per-villager: supporters of the law gain
 *   +5 via {@link MoodTrigger#LAW_TRANSITION}; opponents take −5. The
 *   "supporter / opponent" classification reuses the
 *   {@link LawPopularity} per-profession benefits/affected table.</li>
 *   <li><b>Leader reputation delta.</b> Player-leaders gain or lose
 *   reputation in the village proportional to the popularity score —
 *   {@link #applyLeaderReputationDelta} hits the player ↔ village
 *   reputation ledger directly.</li>
 * </ul>
 *
 * <p>Repeal mirrors enact: the gossip topic refires, mood shocks
 * invert (supporters of the repealed law take −5, opponents +5), and
 * the leader rep delta inverts so repealing an unpopular law actually
 * helps the player's reputation.</p>
 */
public final class LawAnnouncement {

    /** Seeds gossip with this prefix; matches Phase 2 topic-key style. */
    public static final String LAW_TOPIC_PREFIX = "law:";

    private LawAnnouncement() {}

    public static void onEnact(VillageLaw law, Village village, VillageSavedData data,
                               ServerLevel level, UUID actorId) {
        if (law == null || village == null || level == null) return;
        seedGossip(law, village, data);
        moodShock(law, village, level, +1);
        applyLeaderReputationDelta(law, village, data, level, actorId, +1);
    }

    public static void onRepeal(VillageLaw law, Village village, VillageSavedData data,
                                ServerLevel level, UUID actorId) {
        if (law == null || village == null || level == null) return;
        seedGossip(law, village, data);
        moodShock(law, village, level, -1);
        applyLeaderReputationDelta(law, village, data, level, actorId, -1);
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private static void seedGossip(VillageLaw law, Village village, VillageSavedData data) {
        GossipTopicHeat heat = data.getOrCreateGossipHeat(village.getId());
        // Bump twice — Phase 2's bump is constant +0.20; two bumps puts
        // a fresh law squarely above HOT_THRESHOLD (0.40) so NPCs talk
        // about it for the next few in-game days.
        String topic = LAW_TOPIC_PREFIX + law.name();
        heat.bump(topic);
        heat.bump(topic);
        data.markDirty();
    }

    private static void moodShock(VillageLaw law, Village village, ServerLevel level, int sign) {
        long now = level.getGameTime();
        String name = village.getName();
        for (var entity : level.getEntities().getAll()) {
            if (!(entity instanceof TownspersonMob npc)) continue;
            if (!npc.isAdult()) continue;
            if (npc.getAssignedVillageName().filter(n -> n.equals(name)).isEmpty()) continue;

            int rawMag = classify(law, npc.getProfession()) * sign;
            if (rawMag != 0) {
                npc.getMood().applyWithRawMagnitude(MoodTrigger.LAW_TRANSITION, rawMag, now);
            }
        }
    }

    /** +5 for supporters, -5 for opponents, 0 for everyone else. */
    private static int classify(VillageLaw law, Profession p) {
        boolean benefits = LawPopularity.benefitsTo(law, p);
        boolean harms    = LawPopularity.affectedNegatively(law, p);
        if (benefits && !harms) return +5;
        if (harms    && !benefits) return -5;
        return 0;
    }

    /**
     * Player-leader-only path: adjusts the actor's village reputation
     * by the popularity / 10 delta, with a sign matching enact (+) /
     * repeal (-). NPC-leader actors don't have a player rep ledger
     * entry; the mood shock above already handles their NPC-side
     * fallout.
     */
    private static void applyLeaderReputationDelta(VillageLaw law, Village village,
                                                   VillageSavedData data, ServerLevel level,
                                                   UUID actorId, int sign) {
        if (actorId == null) return;
        // Heuristic: a UUID belongs to a player if the connected player
        // list contains it. Avoids a TownspersonMob.findByUUID world walk.
        var player = level.getServer().getPlayerList().getPlayer(actorId);
        if (player == null) return;
        int popularity = LawPopularity.compute(law, village, data, level);
        int delta = sign * (popularity / 10);
        if (delta != 0) {
            village.modifyReputation(actorId, delta);
            data.markDirty();
        }
    }
}
