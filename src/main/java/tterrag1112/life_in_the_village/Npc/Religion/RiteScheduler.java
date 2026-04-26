package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
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
