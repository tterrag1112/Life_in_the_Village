package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Daily-tick driver that:
 * <ol>
 *   <li>Runs every due rite via {@link RiteExecutor#runDue}.</li>
 *   <li>R1c — schedules clergy ordinations.</li>
 * </ol>
 *
 * <p>R2a — the old calendar holy-day rite path was removed: holy-day and
 * life-event rites are now created as the blessing-extension of their
 * gathering ({@code VillageEvent}) via {@code CeremonyBlessings}, so each
 * ceremony flows through one coordinated path instead of a rite and an
 * event being scheduled independently. This class still owns standalone /
 * debug rite scheduling ({@link #schedule}) and the blessing-rite helper
 * ({@link #scheduleBlessingRite}) the event side calls.</p>
 *
 * <p>Spec line 215-222.</p>
 */
public final class RiteScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RiteScheduler() {}

    public static void dailyTick(ServerLevel level) {
        if (level == null) return;
        // 1. Run due rites first.
        RiteExecutor.runDue(level);

        // 2. R1c — schedule ordinations for un-ordained priests (daily,
        // one bounded pass over loaded NPCs).
        VillageSavedData vdata = VillageSavedData.get(level);
        try { scheduleOrdinations(level, vdata); }
        catch (Throwable t) {
            LOGGER.warn("[RiteScheduler] ordination pass threw: {}", t.getMessage());
        }
    }

    // ── Blessing-rite scheduling (R2a) ────────────────────────────────────

    /**
     * Schedules a ceremony's blessing rite (vacant presider), linked to a
     * gathering by the caller via the gathering's {@code eventData}. Gated by
     * the village religion's {@code ritualises} filter so a religion that
     * doesn't ritualise this rite produces no blessing — the same gate the
     * old life-event / calendar producers applied. Returns the new rite id
     * (for the gathering link), or empty when not ritualised / inputs invalid.
     *
     * <p>The rite is located at the village temple (its officiant works
     * there); the surrounding gathering is village-wide. R2b refines venue /
     * physical attendance.</p>
     */
    public static java.util.Optional<UUID> scheduleBlessingRite(
            ServerLevel level, Village village, Rite rite,
            List<UUID> participants, long scheduledTick) {
        if (level == null || village == null || rite == null) return java.util.Optional.empty();
        if (!villageRitualises(level, village, rite)) return java.util.Optional.empty();
        BlockPos location = templeLocation(village, VillageSavedData.get(level))
                .orElseGet(() -> village.getVillageCentre() != null
                        ? village.getVillageCentre()
                        : BlockPos.ZERO);
        RiteExecution exec = new RiteExecution(UUID.randomUUID(), rite,
                java.util.Optional.empty(),
                participants == null ? List.of() : participants,
                location, scheduledTick, 0L,
                RiteOutcome.PENDING, village.getId());
        RiteSavedData.get(level).putRite(exec);
        return java.util.Optional.of(exec.riteId());
    }

    /** Whether the village's dominant religion ritualises {@code rite}. R3a —
     *  the culture→religion resolution is centralized in
     *  {@link ReligionContent#villageReligionId} (single source of truth). */
    public static boolean villageRitualises(ServerLevel level, Village village, Rite rite) {
        Religion religion = ReligionRegistry.get(
                ReligionContent.villageReligionId(level, village));
        return religion != null && religion.ritualises(rite);
    }

    // ── Ordination scheduling (R1c) ───────────────────────────────────────

    /**
     * Schedules an {@link Rite#ORDINATION} for every loaded PRIEST-profession
     * NPC that is not yet ordained (lacks the clergy specialization) and has
     * no pending ordination, provided their village has a priest qualified to
     * officiate it. A vacant presider is left so the normal R1a/R1b claim
     * path (a qualified senior priest) picks it up.
     *
     * <p>Gating on an available officiant (rather than scheduling
     * unconditionally and relying on the no-priest SKIP edge) matters because
     * the rite ledger is never pruned — a no-officiant ordination would SKIP
     * and re-schedule every day, churning the ledger. Founders are
     * pre-ordained by the populator, so the gap cases (leader hires,
     * conversions) normally have a senior present.</p>
     */
    private static void scheduleOrdinations(ServerLevel level, VillageSavedData vdata) {
        // One pass: group loaded PRIEST NPCs by their village.
        Map<UUID, List<TownspersonMob>> priestsByVillage = new HashMap<>();
        for (var e : level.getEntities().getAll()) {
            if (!(e instanceof TownspersonMob npc)) continue;
            if (npc.getProfession() != Profession.PRIEST) continue;
            Village v = npc.getAssignedVillageName()
                    .flatMap(vdata::getVillageByName).orElse(null);
            if (v == null) continue;
            priestsByVillage.computeIfAbsent(v.getId(), k -> new ArrayList<>()).add(npc);
        }

        for (Map.Entry<UUID, List<TownspersonMob>> entry : priestsByVillage.entrySet()) {
            List<TownspersonMob> priests = entry.getValue();
            // Need at least one priest who can officiate an ORDINATION
            // (STANDARD tier) before scheduling — else it would only SKIP.
            boolean officiantAvailable = priests.stream()
                    .anyMatch(p -> RiteCapability.canOfficiate(p, Rite.ORDINATION));
            if (!officiantAvailable) continue;

            Village village = vdata.getVillageById(entry.getKey()).orElse(null);
            if (village == null) continue;

            for (TownspersonMob p : priests) {
                if (isOrdained(p)) continue;
                if (hasPendingOrdination(level, village.getId(), p.getUUID())) continue;
                schedule(level, village, Rite.ORDINATION, List.of(p.getUUID()), 0L);
            }
        }
    }

    /** Ordained = carries any clergy (PRIEST-profession) specialization.
     *  Forward-compatible with future religion orders, which are also
     *  PRIEST specializations. */
    private static boolean isOrdained(TownspersonMob npc) {
        return npc.getSpecializationComponent().get()
                .map(def -> def.profession() == Profession.PRIEST)
                .orElse(false);
    }

    private static boolean hasPendingOrdination(ServerLevel level, UUID villageId,
                                                UUID ordinandId) {
        return RiteSavedData.get(level).ritesForVillage(villageId).stream()
                .anyMatch(r -> r.type() == Rite.ORDINATION
                        && r.outcome() == RiteOutcome.PENDING
                        && r.participantIds().contains(ordinandId));
    }

    /** Schedule a one-shot rite for an external trigger (lifecycle event). */
    public static void schedule(ServerLevel level, Village village, Rite rite,
                                List<UUID> participantIds, long delayTicks) {
        if (level == null || village == null || rite == null) return;
        BlockPos location = templeLocation(village, VillageSavedData.get(level))
                .orElseGet(() -> village.getVillageCentre() != null
                        ? village.getVillageCentre()
                        : BlockPos.ZERO);
        long scheduledTick = level.getGameTime() + Math.max(0L, delayTicks);
        RiteExecution exec = new RiteExecution(UUID.randomUUID(), rite,
                java.util.Optional.empty(),
                participantIds == null ? List.of() : participantIds,
                location, scheduledTick, 0L,
                RiteOutcome.PENDING, village.getId());
        RiteSavedData.get(level).putRite(exec);
    }

    // R2a — scheduleCalendarRites removed. Holy-day rites are now attached to
    // their gathering (the seasonal HARVEST_FESTIVAL / cultural holy-day
    // VillageEvent) by CeremonyBlessings, co-ordinated with the event rather
    // than scheduled independently off the religion calendar.

    private static java.util.Optional<BlockPos> templeLocation(Village village,
                                                               VillageSavedData data) {
        for (UUID bid : village.getBuildingIds()) {
            Building b = data.getBuildingById(bid).orElse(null);
            if (b != null && b.getType() == BuildingType.TEMPLE) {
                return java.util.Optional.of(b.getShape().getOrigin());
            }
        }
        return java.util.Optional.empty();
    }
}
