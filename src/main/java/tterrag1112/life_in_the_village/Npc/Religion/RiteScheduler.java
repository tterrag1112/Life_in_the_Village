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
 *   <li>Schedules calendar rites (HARVEST_THANKSGIVING, FEAST_DAY) on
 *   the matching day-of-year per village's culture-derived
 *   {@link Religion#calendar()}.</li>
 * </ol>
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

        // 2. Schedule calendar rites for villages whose religion has a
        // holy day matching today.
        VillageSavedData vdata = VillageSavedData.get(level);
        long now = level.getGameTime();
        int dayOfYear = (int) ((now / 24000L) % ReligiousCalendar.DAYS_PER_YEAR);
        for (Village village : vdata.getAllVillages()) {
            try { scheduleCalendarRites(village, vdata, level, now, dayOfYear); }
            catch (Throwable t) {
                LOGGER.warn("[RiteScheduler] {} threw: {}", village.getName(), t.getMessage());
            }
        }

        // 3. R1c — schedule ordinations for un-ordained priests (daily,
        // one bounded pass over loaded NPCs).
        try { scheduleOrdinations(level, vdata); }
        catch (Throwable t) {
            LOGGER.warn("[RiteScheduler] ordination pass threw: {}", t.getMessage());
        }
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

    // ── Calendar scheduling ───────────────────────────────────────────────

    private static void scheduleCalendarRites(Village village, VillageSavedData vdata,
                                              ServerLevel level, long now, int dayOfYear) {
        String culture = vdata.getKingdomForVillage(village.getId())
                .map(tterrag1112.life_in_the_village.Kingdom.Kingdom::getCulture)
                .orElse("default");
        String religionId = ReligionRegistry.dominantReligionFor(culture);
        Religion religion = ReligionRegistry.get(religionId);
        if (religion == null) return;
        if (!religion.calendar().isHolyDay(dayOfYear)) return;

        // Don't double-schedule: skip if a calendar rite was already
        // queued for today.
        long todayStart = (now / 24000L) * 24000L;
        long todayEnd   = todayStart + 24000L;
        boolean alreadyQueued = RiteSavedData.get(level).ritesForVillage(village.getId()).stream()
                .anyMatch(r -> (r.type() == Rite.HARVEST_THANKSGIVING
                                || r.type() == Rite.FEAST_DAY)
                        && r.scheduledTick() >= todayStart
                        && r.scheduledTick() <  todayEnd);
        if (alreadyQueued) return;

        // Pick a sensible rite for the religion. If today matches the
        // religion's named "Harvest Equinox" / "Last Catch" entry and
        // the religion ritualises HARVEST_THANKSGIVING, fire that;
        // otherwise FEAST_DAY covers any other holy day.
        Integer harvestDay = religion.calendar().holyDaysByName().get("Harvest Equinox");
        if (harvestDay == null) harvestDay = religion.calendar().holyDaysByName().get("Last Catch");
        boolean isHarvestToday = harvestDay != null && harvestDay == dayOfYear;
        Rite rite = (isHarvestToday && religion.ritualises(Rite.HARVEST_THANKSGIVING))
                ? Rite.HARVEST_THANKSGIVING
                : Rite.FEAST_DAY;
        schedule(level, village, rite, List.of(), 0L);
    }

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
