package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.OpenTempleScreenPacket;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingCondition;
import tterrag1112.life_in_the_village.Village.Economy.BuildingEconomy;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Religion Rework R9b — server-side gatherer for the read-only temple screen.
 * Reads a religious building's R4 economy + R4c decay, faith / consecration /
 * candle / clergy / congregation / upcoming-festival state into a single
 * {@link OpenTempleScreenPacket}. Mirrors {@code NpcProfileSnapshotBuilder}:
 * pure read, no mutation, robust for vacant / RUINED / non-religious buildings.
 */
public final class TempleSnapshotBuilder {

    private TempleSnapshotBuilder() {}

    /** Proximity (blocks) for matching a CONSECRATION rite to its building. */
    private static final double CONSECRATION_RADIUS_SQ = 12 * 12;
    /** Scan inflation around the village bounds for resident/clergy lookups. */
    private static final double SCAN_INFLATE = 32;
    /** Number of upcoming holy days to surface. */
    private static final int MAX_UPCOMING = 3;

    public static OpenTempleScreenPacket build(ServerLevel level, Village village,
                                               Building building) {
        VillageSavedData data = VillageSavedData.get(level);

        String buildingName = building.getName();
        String buildingType = building.getType().name();
        String villageName  = village.getName();

        // ── Faith ────────────────────────────────────────────────────────────
        String faithId = BuildingFaith.resolveFaith(level, village, building);
        Religion religion = faithId == null ? null : ReligionRegistry.get(faithId);
        String faithName = religion != null ? religion.displayName() : "";
        // F1a 4a — the displayed deity is the religion's PRIMARY god (name; "" for an
        // impersonal/god-less faith, matching the old deity().orElse("") shape).
        String deityName = religion != null ? GodRegistry.primaryDeityName(religion, "") : "";

        // ── Economy (R4) + derived health ────────────────────────────────────
        BuildingEconomy econ = data.getOrCreateBuildingEconomy(building.getId());
        long treasury     = econ.getTreasury();
        long dailyCost    = TempleProsperity.dailyCost();
        long surplus      = treasury - TempleProsperity.solvencyBuffer();
        int  daysInsolvent = econ.getDaysInsolvent();

        // ── Clergy ───────────────────────────────────────────────────────────
        TownspersonMob priest = findAssignedPriest(level, village, data, building.getId())
                .orElse(null);
        boolean staffed = priest != null;
        String clergyName  = staffed ? priest.getNpcName() : "";
        String clergyOrder = staffed ? ClergyOrders.assignedOrderName(priest).orElse("") : "";
        String clergyTitle = staffed ? ClergyTitles.of(priest) : "";

        // ── Condition (R4c) + health ─────────────────────────────────────────
        BuildingCondition condition = building.getCondition();
        String healthState = TempleProsperity.healthLabel(condition, treasury, daysInsolvent, staffed);
        boolean decaying = condition.needsRepair() || daysInsolvent > 0
                || healthState.equals("Decaying") || healthState.equals("Abandoned");

        // ── Consecration (R3b-1) ─────────────────────────────────────────────
        boolean consecrated = isConsecrated(level, village, building);

        // ── Candles (R4b) ────────────────────────────────────────────────────
        int candleCount = BuildingStorageAccess.countItem(level, building, Items.WHITE_CANDLE);

        // ── Congregation (served adherents of this faith) ────────────────────
        int congregationCount = 0;
        float pietySum = 0f;
        if (faithId != null) {
            AABB bounds = village.getBounds(data).map(b -> b.inflate(SCAN_INFLATE)).orElse(null);
            if (bounds != null) {
                for (TownspersonMob m : level.getEntitiesOfClass(TownspersonMob.class, bounds,
                        npc -> npc.isAlive() && !npc.isVisitor()
                                && npc.getAssignedVillageName()
                                        .map(n -> n.equals(village.getName())).orElse(false))) {
                    if (m.getPiety().primaryReligion().map(faithId::equals).orElse(false)) {
                        congregationCount++;
                        pietySum += m.getPiety().primaryStrength();
                    }
                }
            }
        }
        float aggregatePiety = congregationCount == 0 ? 0f : pietySum / congregationCount;

        // ── Upcoming holy days / festivals ───────────────────────────────────
        List<String> upcoming = upcomingHolyDays(level, religion);

        return new OpenTempleScreenPacket(
                buildingName, buildingType, villageName, faithName, deityName,
                treasury, dailyCost, surplus, daysInsolvent, healthState,
                condition.name(), decaying, consecrated, candleCount,
                staffed, clergyName, clergyOrder, clergyTitle,
                congregationCount, aggregatePiety, upcoming);
    }

    /** The loaded PRIEST assigned to {@code buildingId}, if any (mirrors
     *  {@code TempleProsperity.findAssignedPriest}). */
    private static Optional<TownspersonMob> findAssignedPriest(
            ServerLevel level, Village village, VillageSavedData data, UUID buildingId) {
        AABB bounds = village.getBounds(data).map(b -> b.inflate(SCAN_INFLATE)).orElse(null);
        if (bounds == null) return Optional.empty();
        return level.getEntitiesOfClass(TownspersonMob.class, bounds,
                        npc -> npc.getProfession() == Profession.PRIEST
                                && npc.getAssignedBuildingId().map(buildingId::equals).orElse(false))
                .stream().findFirst();
    }

    /** A building is consecrated (R3b-1) when a SUCCESSFUL CONSECRATION rite was
     *  performed at/near it — the durable marker pruning never removes (R4e). */
    private static boolean isConsecrated(ServerLevel level, Village village, Building building) {
        BlockPos origin = building.getShape().getOrigin();
        return RiteSavedData.get(level).ritesForVillage(village.getId()).stream()
                .anyMatch(r -> r.type() == Rite.CONSECRATION
                        && r.outcome() == RiteOutcome.SUCCESSFUL
                        && origin.distSqr(r.location()) <= CONSECRATION_RADIUS_SQ);
    }

    /** The next few holy days for the faith, as "Name — in N day(s)" / "today",
     *  via the shared {@link CalendarView} day math (R9c). */
    private static List<String> upcomingHolyDays(ServerLevel level, Religion religion) {
        List<String> out = new ArrayList<>();
        for (CalendarView.Entry e : CalendarView.upcomingFor(religion, level.getGameTime())) {
            if (out.size() >= MAX_UPCOMING) break;
            String when = e.daysAway() == 0 ? "today"
                    : "in " + e.daysAway() + (e.daysAway() == 1 ? " day" : " days");
            out.add(prettyLabel(e.dayLabel()) + " — " + when);
        }
        return out;
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
