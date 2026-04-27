package tterrag1112.life_in_the_village.Npc.Aging;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.LifeStage;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Gossip.GossipTopicHeat;
import tterrag1112.life_in_the_village.Npc.Memory.MemoryType;
import tterrag1112.life_in_the_village.Npc.Memory.NpcMemory;

import java.util.List;
import java.util.UUID;

/**
 * Handlers for the spec's age-natural death arc (spec line 222) and
 * the {@link UnfinishedBusiness} → {@code DIE_WITH_REGRET} hook
 * (spec line 217).
 *
 * <p>Phase 2 ships the data flow + memory + gossip-seed:</p>
 * <ul>
 *   <li>Age-natural deaths attach a small "passed away peacefully"
 *       memory to nearby NPCs, distinguishable from combat deaths.</li>
 *   <li>Unresolved unfinished business at death seeds a gossip
 *       topic via {@link GossipTopicHeat#bump} so villagers can
 *       carry the regret forward.</li>
 * </ul>
 *
 * <p>The actual funeral event is Phase 3 priest content; this
 * module only fires the data hook.</p>
 */
public final class DeathArc {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Search radius for funeral-stub attendees + gossip seeding. */
    public static final double ATTENDEE_RADIUS = 32.0;
    /** Memory value stamped on attendees of a peaceful passing. */
    public static final int PEACEFUL_DEATH_MEMORY_VALUE = 50;

    private DeathArc() {}

    /**
     * Called from {@code TownspersonMob.onNpcDeath} once the entity
     * is confirmed dead. Drives both the natural-death funeral stub
     * and the regret hook.
     */
    public static void onNpcDeath(TownspersonMob deceased, ServerLevel level) {
        if (deceased == null) return;
        boolean ageNatural = deceased.isDyingNatural()
                || deceased.getLifeStage() == LifeStage.ELDERLY;
        if (ageNatural) {
            seedPeacefulDeathMemories(deceased, level);
        }
        if (deceased.getLifeStage() == LifeStage.ELDERLY) {
            checkUnfinishedBusinessRegret(deceased, level);
        }
    }

    private static void seedPeacefulDeathMemories(TownspersonMob deceased, ServerLevel level) {
        long now = level.getGameTime();
        List<TownspersonMob> attendees = level.getEntitiesOfClass(
                TownspersonMob.class,
                deceased.getBoundingBox().inflate(ATTENDEE_RADIUS),
                m -> m != deceased);
        for (TownspersonMob a : attendees) {
            a.getMemory().add(NpcMemory.create(
                    MemoryType.WITNESSED_DEATH_OF,
                    List.of(deceased.getUUID()),
                    now,
                    PEACEFUL_DEATH_MEMORY_VALUE,
                    deceased.getNpcName() + " passed away peacefully"));
        }
    }

    /**
     * Spec line 217: {@code DIE_WITH_REGRET} — kingdom history note
     * + nearby mood drop + gossip seed about the unresolved matter.
     * Phase 2 ships the gossip seed and the local mood drop; the
     * kingdom-history wiring is Phase 4.
     */
    private static void checkUnfinishedBusinessRegret(TownspersonMob deceased,
                                                       ServerLevel level) {
        RetirementState rs = deceased.getRetirementState();
        if (!rs.hasUnresolvedBusiness()) return;
        UnfinishedBusiness biz = rs.unfinished().orElseThrow();
        seedRegretGossip(deceased, biz, level);
        seedNearbyMoodDrop(deceased, level);
        LOGGER.info("[DeathArc] DIE_WITH_REGRET: {} died with unresolved {} ({})",
                deceased.getNpcName(), biz.type().name(), biz.description());
    }

    private static void seedRegretGossip(TownspersonMob deceased,
                                         UnfinishedBusiness biz,
                                         ServerLevel level) {
        // Push the topic into every nearby village's heat tracker so
        // gossip (task 12) carries it.
        var data = tterrag1112.life_in_the_village.Networking.VillageSavedData.get(level);
        deceased.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .ifPresent(v -> {
                    String topic = "regret." + biz.type().name().toLowerCase()
                            + "." + deceased.getUUID().toString().substring(0, 8);
                    GossipTopicHeat heat = data.getOrCreateGossipHeat(v.getId());
                    heat.bump(topic);
                    heat.bump(topic);
                    heat.bump(topic);
                    data.markDirty();
                });
    }

    private static void seedNearbyMoodDrop(TownspersonMob deceased, ServerLevel level) {
        long now = level.getGameTime();
        UUID deceasedId = deceased.getUUID();
        List<TownspersonMob> attendees = level.getEntitiesOfClass(
                TownspersonMob.class,
                deceased.getBoundingBox().inflate(ATTENDEE_RADIUS),
                m -> m != deceased);
        for (TownspersonMob a : attendees) {
            // Slight extra mood drop on top of the peaceful-death
            // memory — uses INSULTED_BY's negative mood routing
            // since spec doesn't supply a dedicated "regret" trigger.
            a.getMood().applyWithRawMagnitude(
                    tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger.INSULT_RECEIVED,
                    -3, now);
            a.getMemory().add(NpcMemory.create(
                    MemoryType.WITNESSED_DEATH_OF,
                    List.of(deceasedId),
                    now,
                    20,
                    deceased.getNpcName() + " left things unfinished"));
        }
    }
}
