package tterrag1112.life_in_the_village.Npc.Laws;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;
import java.util.UUID;

/**
 * High-level enact / repeal entry point. Wraps {@link VillagePolicy}'s
 * raw mutators with conflict detection, lifecycle events
 * ({@link LawAnnouncement}), and per-law {@link LawEffect#onEnact} /
 * {@link LawEffect#onRepeal} dispatch.
 *
 * <p>Every call site (player UI / verb / NPC decision engine /
 * {@code /law} debug command) goes through here so the lifecycle
 * sequence is identical regardless of who triggered the change.</p>
 */
public final class LawEnactment {

    private static final Logger LOGGER = LogUtils.getLogger();

    private LawEnactment() {}

    /** Outcome record for the {@code /law} command + verb feedback. */
    public record Outcome(boolean success, String reason) {
        public static Outcome ok() { return new Outcome(true, ""); }
        public static Outcome fail(String reason) { return new Outcome(false, reason); }
    }

    /**
     * Tries to enact {@code law} on {@code village}. Returns
     * {@code Outcome.ok()} on success, or a failure with reason for the
     * caller to surface (already enacted, conflict, etc.).
     */
    public static Outcome enact(Village village, VillageLaw law, LawParams params,
                                ServerLevel level, UUID actorId) {
        if (village == null) return Outcome.fail("village missing");
        if (law == null)     return Outcome.fail("unknown law");
        VillagePolicy policy = village.getPolicy();
        if (policy.hasLaw(law)) return Outcome.fail("law already enacted");
        Optional<VillageLaw> conflict = policy.firstConflictWith(law);
        if (conflict.isPresent()) {
            return Outcome.fail("conflicts with " + conflict.get().name());
        }

        LawParams effectiveParams = params != null ? params : LawParams.withDefaults(law);
        if (!policy.enact(law, effectiveParams, level.getGameTime())) {
            return Outcome.fail("policy refused enactment");
        }

        // One-shot per-effect hook (most are no-ops; subsidies use this
        // moment to snapshot a "starting balance" if needed).
        LawEffect effect = LawEffectRegistry.get(law);
        if (effect != null) {
            try {
                effect.onEnact(level, village, VillageSavedData.get(level), effectiveParams);
            } catch (Throwable t) {
                LOGGER.warn("[LawEnactment] {}.onEnact threw: {}", law, t.getMessage());
            }
        }

        // Lifecycle: gossip + mood + reputation.
        LawAnnouncement.onEnact(law, village, VillageSavedData.get(level), level, actorId);
        VillageSavedData.get(level).markDirty();
        // Phase 4 doc 30 archival hook.
        archiveLawChange(level, village,
                tterrag1112.life_in_the_village.Village.History.HistoryEventType.LAW_ENACTED,
                law, actorId);
        return Outcome.ok();
    }

    /** Repeals {@code law}, mirroring the {@link #enact} lifecycle. */
    public static Outcome repeal(Village village, VillageLaw law,
                                 ServerLevel level, UUID actorId) {
        if (village == null) return Outcome.fail("village missing");
        if (law == null)     return Outcome.fail("unknown law");
        VillagePolicy policy = village.getPolicy();
        if (!policy.hasLaw(law)) return Outcome.fail("law not enacted");

        LawEffect effect = LawEffectRegistry.get(law);
        if (effect != null) {
            try {
                effect.onRepeal(level, village, VillageSavedData.get(level));
            } catch (Throwable t) {
                LOGGER.warn("[LawEnactment] {}.onRepeal threw: {}", law, t.getMessage());
            }
        }

        policy.repeal(law);
        LawAnnouncement.onRepeal(law, village, VillageSavedData.get(level), level, actorId);
        VillageSavedData.get(level).markDirty();
        // Phase 4 doc 30 archival hook.
        archiveLawChange(level, village,
                tterrag1112.life_in_the_village.Village.History.HistoryEventType.LAW_REPEALED,
                law, actorId);
        return Outcome.ok();
    }

    private static void archiveLawChange(ServerLevel level, Village village,
                                         tterrag1112.life_in_the_village.Village.History.HistoryEventType type,
                                         VillageLaw law, UUID actorId) {
        java.util.Map<String, String> details = new java.util.LinkedHashMap<>();
        details.put("village_name", village.getName());
        details.put("law_name", law.name());
        details.put("leader_name", actorName(level, actorId));
        java.util.List<UUID> related = actorId == null
                ? java.util.List.of() : java.util.List.of(actorId);
        tterrag1112.life_in_the_village.Village.History.HistoryProducer
                .record(level, village, type, level.getGameTime(), details, related);
    }

    private static String actorName(ServerLevel level, UUID actorId) {
        if (actorId == null) return "(council)";
        return tterrag1112.life_in_the_village.Entities.custom.TownspersonMob
                .findByUUID(level, actorId)
                .map(tterrag1112.life_in_the_village.Entities.custom.TownspersonMob::getNpcName)
                .orElse(actorId.toString().substring(0, 8));
    }
}
