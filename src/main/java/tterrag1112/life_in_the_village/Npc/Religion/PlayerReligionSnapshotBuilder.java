package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Networking.OpenPlayerReligionPacket;
import tterrag1112.life_in_the_village.Networking.OpenPlayerReligionPacket.CalendarRow;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Religion Rework R9c — server-side gatherer for the read-only player-religion +
 * calendar screen. Reads the player's own {@link PietyComponent} (faith / piety /
 * beliefs / observance) and tithe pledge from {@link RiteSavedData}, plus the
 * upcoming religious calendar across faiths via the shared {@link CalendarView}.
 * Pure read; graceful for an unaffiliated player / empty calendars.
 */
public final class PlayerReligionSnapshotBuilder {

    private PlayerReligionSnapshotBuilder() {}

    /** Calendar rows to surface across all faiths (the ScrollList scrolls). */
    private static final int MAX_CALENDAR_ROWS = 24;

    public static OpenPlayerReligionPacket build(ServerPlayer player, ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        RiteSavedData rites = RiteSavedData.get(level);
        UUID playerId = player.getUUID();

        // ── Your faith ───────────────────────────────────────────────────────
        Optional<PietyComponent> pietyOpt = rites.getPlayerPiety(playerId);
        String religionName = "";
        String deityName = "";
        float pietyStrength = 0f;
        String pietyTier = "";
        List<String> beliefSummary = new ArrayList<>();
        int ritesThisMonth = 0;
        boolean meetsMonthly = false;
        String ownFaithId = null;

        if (pietyOpt.isPresent()) {
            PietyComponent piety = pietyOpt.get();
            ownFaithId = piety.primaryReligion().orElse(null);
            Optional<Religion> religion = piety.primaryReligion().flatMap(ReligionRegistry::find);
            religionName = religion.map(Religion::displayName).orElse("");
            deityName = religion.flatMap(Religion::deity).orElse("");
            pietyStrength = piety.primaryStrength();
            pietyTier = piety.primaryTier().displayName();
            var beliefs = piety.beliefs();
            if (beliefs.size() > 1) {
                for (var e : beliefs.entrySet()) {
                    String fname = ReligionRegistry.find(e.getKey())
                            .map(Religion::displayName).orElse(e.getKey());
                    beliefSummary.add(fname + " — " + Math.round(e.getValue() * 100) + "%");
                }
            }
            ritesThisMonth = piety.ritesAttendedThisMonth();
            meetsMonthly = piety.meetsMonthlyAttendance();
        }

        // ── Tithe pledge (R4d-1) ─────────────────────────────────────────────
        boolean hasPledge = rites.isAutoTithe(playerId);
        String pledgeTempleName = "";
        String pledgeFaithName = "";
        if (hasPledge) {
            UUID templeId = rites.autoTitheTemples().get(playerId);
            Building temple = templeId == null ? null : data.getBuildingById(templeId).orElse(null);
            if (temple != null) {
                pledgeTempleName = temple.getName();
                Village village = villageOf(data, templeId);
                String faithId = village != null
                        ? BuildingFaith.resolveFaith(level, village, temple)
                        : temple.getPatronFaith();
                if (faithId != null) {
                    pledgeFaithName = ReligionRegistry.find(faithId)
                            .map(Religion::displayName).orElse(faithId);
                }
            }
        }

        // ── Religious calendar (% 365 axis, all faiths) ──────────────────────
        int today = CalendarView.dayOfYear(level.getGameTime());
        List<CalendarRow> calendar = new ArrayList<>();
        for (CalendarView.Entry e : CalendarView.upcomingAcross(
                ReligionRegistry.all(), level.getGameTime(), MAX_CALENDAR_ROWS)) {
            boolean own = ownFaithId != null && ownFaithId.equals(e.faithId());
            calendar.add(new CalendarRow(e.faithDisplay(), prettyLabel(e.dayLabel()),
                    e.dayOfYear(), e.daysAway(), own));
        }

        return new OpenPlayerReligionPacket(
                religionName, deityName, pietyStrength, pietyTier, beliefSummary,
                hasPledge, pledgeTempleName, pledgeFaithName,
                ritesThisMonth, meetsMonthly,
                today, calendar);
    }

    /** The village owning {@code buildingId}, if any. */
    private static Village villageOf(VillageSavedData data, UUID buildingId) {
        for (Village v : data.getAllVillages()) {
            if (v.getBuildingIds().contains(buildingId)) return v;
        }
        return null;
    }

    private static String prettyLabel(String raw) {
        String s = raw.replace('_', ' ').replace('.', ' ').trim();
        if (s.isEmpty()) return raw;
        StringBuilder sb = new StringBuilder();
        boolean cap = true;
        for (char c : s.toCharArray()) {
            if (c == ' ') { sb.append(' '); cap = true; }
            else if (cap) { sb.append(Character.toUpperCase(c)); cap = false; }
            else sb.append(c);
        }
        return sb.toString();
    }
}
