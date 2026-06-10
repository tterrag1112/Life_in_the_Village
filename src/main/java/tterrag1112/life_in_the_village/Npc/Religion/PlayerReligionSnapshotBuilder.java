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
            Optional<Religion> religion = piety.primaryReligion().flatMap(id -> Religions.find(level, id));
            religionName = religion.map(Religion::displayName).orElse("");
            // F1a 4a — the headline deity name is the primary god's (per-god rows are 4b).
            deityName = religion.map(r -> GodRegistry.primaryDeityName(r, "")).orElse("");
            pietyStrength = piety.primaryStrength();
            pietyTier = piety.primaryTier().displayName();
            var beliefs = piety.beliefs();
            if (beliefs.size() > 1) {
                for (var e : beliefs.entrySet()) {
                    String fname = Religions.find(level, e.getKey())
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
                    pledgeFaithName = Religions.find(level, faithId)
                            .map(Religion::displayName).orElse(faithId);
                }
            }
        }

        // ── Religious calendar (% 365 axis, all faiths) ──────────────────────
        int today = CalendarView.dayOfYear(level.getGameTime());
        List<CalendarRow> calendar = new ArrayList<>();
        for (CalendarView.Entry e : CalendarView.upcomingAcross(
                Religions.all(level), level.getGameTime(), MAX_CALENDAR_ROWS)) {
            boolean own = ownFaithId != null && ownFaithId.equals(e.faithId());
            calendar.add(new CalendarRow(e.faithDisplay(), prettyLabel(e.dayLabel()),
                    e.dayOfYear(), e.daysAway(), own));
        }

        // ── Active divine calling (Divine Layer V3) ──────────────────────────
        long now = level.getGameTime();
        String activeCalling = rites.getPlayerCalling(playerId)
                .map(c -> {
                    String fname = Religions.find(level, c.religionId())
                            .map(Religion::displayName).orElse(c.religionId());
                    return fname + ": " + c.describe();
                })
                .orElse("");

        // ── Per-god divine standing (F1a 4b) — favour + band + miracles + theophany ──
        // The standing set: gods with a favour entry + the gods of believed religions.
        java.util.Set<String> standingGods = new java.util.LinkedHashSet<>();
        if (ownFaithId != null) {
            Religions.find(level, ownFaithId).ifPresent(r ->
                    GodRegistry.godsFor(r).forEach(g -> standingGods.add(g.id())));  // primary first
        }
        rites.getPlayerFavour(playerId).ifPresent(f -> standingGods.addAll(f.all().keySet()));
        pietyOpt.ifPresent(p -> {
            for (String rid : p.beliefs().keySet()) {
                Religions.find(level, rid).ifPresent(r ->
                        GodRegistry.godsFor(r).forEach(g -> standingGods.add(g.id())));
            }
        });
        var theophanyStore = rites.theophanies(playerId);
        List<OpenPlayerReligionPacket.GodStanding> gods = new ArrayList<>();
        for (String godId : standingGods) {
            God god = GodRegistry.get(godId);
            if (god == null) continue;
            int favour = Math.round(DivineFavour.current(level, playerId, godId, now));
            String band = DivineFavour.displeasureOf(favour).name();   // NONE/OMEN/CURSE/WRATH

            // This god's miracles (selected by its domain), gated by this god.
            List<String> miracles = new ArrayList<>();
            for (Miracle m : Miracles.forDomain(god.domain())) {
                String glyph = switch (MiracleInvoker.status(level, player, m, now)) {
                    case AVAILABLE   -> "✓";
                    case ON_COOLDOWN -> "⏳";
                    case LOCKED_TIER, LOCKED_FAVOUR -> "🔒";
                };
                miracles.add(m.displayName() + " " + glyph);
            }

            // This god's theophany history ("glory (Nd)" / "wrath (Nd)").
            List<String> theophanies = new ArrayList<>();
            for (var e : theophanyStore.entrySet()) {
                String[] parts = e.getKey().split("\\|");
                if (parts.length < 2 || !parts[0].equals(godId)) continue;
                long days = Math.max(0, (now - e.getValue()) / 24000L);
                theophanies.add((parts[1].equals("wrath") ? "wrath" : "glory") + " (" + days + "d)");
            }
            gods.add(new OpenPlayerReligionPacket.GodStanding(
                    god.displayName(), favour, band, miracles, theophanies));
        }

        return new OpenPlayerReligionPacket(
                religionName, deityName, pietyStrength, pietyTier, beliefSummary,
                hasPledge, pledgeTempleName, pledgeFaithName,
                ritesThisMonth, meetsMonthly,
                today, calendar, activeCalling, gods);
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
