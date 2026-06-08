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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        VillageSavedData vdata = VillageSavedData.get(level);
        // One bounded pass over loaded NPCs, shared by the scan-driven rites
        // (ordination R1c, consecration R3b-1) so officiant availability is
        // computed once.
        Map<UUID, List<TownspersonMob>> priestsByVillage = buildPriestsByVillage(level, vdata);

        // 2. R1c — schedule ordinations for un-ordained priests.
        try { scheduleOrdinations(level, vdata, priestsByVillage); }
        catch (Throwable t) {
            LOGGER.warn("[RiteScheduler] ordination pass threw: {}", t.getMessage());
        }

        // 3. R3b-1 — schedule consecrations for un-consecrated religious
        // buildings, then apply the ongoing blessing for consecrated ones.
        try { scheduleConsecrations(level, vdata, priestsByVillage); }
        catch (Throwable t) {
            LOGGER.warn("[RiteScheduler] consecration pass threw: {}", t.getMessage());
        }
        try { applyConsecrationBlessings(level, vdata); }
        catch (Throwable t) {
            LOGGER.warn("[RiteScheduler] consecration-blessing pass threw: {}", t.getMessage());
        }
    }

    /** Groups loaded PRIEST NPCs by their village id (one entity pass). */
    private static Map<UUID, List<TownspersonMob>> buildPriestsByVillage(
            ServerLevel level, VillageSavedData vdata) {
        Map<UUID, List<TownspersonMob>> byVillage = new HashMap<>();
        for (var e : level.getEntities().getAll()) {
            if (!(e instanceof TownspersonMob npc)) continue;
            if (npc.getProfession() != Profession.PRIEST) continue;
            // R3e-3a tie-in — a PILGRIM visitor's underlying profession is PRIEST,
            // but a transient pilgrim is not village clergy: exclude visitors so
            // they aren't handed ordinations/consecrations (which would churn the
            // unpruned rite ledger). Resident clergy are never visitors.
            if (npc.isVisitor()) continue;
            Village v = npc.getAssignedVillageName()
                    .flatMap(vdata::getVillageByName).orElse(null);
            if (v == null) continue;
            byVillage.computeIfAbsent(v.getId(), k -> new ArrayList<>()).add(npc);
        }
        return byVillage;
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
        return scheduleBlessingRite(level, village, rite, participants, scheduledTick, null, null);
    }

    /**
     * R3e-2b — faith-aware blessing rite. {@code faithId} (null → village
     * dominant) gates ritualisation against THAT faith and is the authority for
     * effect tuning; {@code location} (null → temple/village-centre) pins the
     * rite at the faith's venue (a shrine for a minority faith) so the officiant
     * and congregation converge there. The 5-arg overload preserves the
     * dominant-at-temple behavior exactly (both args null).
     */
    public static java.util.Optional<UUID> scheduleBlessingRite(
            ServerLevel level, Village village, Rite rite,
            List<UUID> participants, long scheduledTick,
            String faithId, BlockPos location) {
        if (level == null || village == null || rite == null) return java.util.Optional.empty();
        if (!religionRitualises(level, village, faithId, rite)) return java.util.Optional.empty();
        BlockPos loc = location != null ? location
                : templeLocation(village, VillageSavedData.get(level))
                        .orElseGet(() -> village.getVillageCentre() != null
                                ? village.getVillageCentre()
                                : BlockPos.ZERO);
        RiteExecution exec = new RiteExecution(UUID.randomUUID(), rite,
                java.util.Optional.empty(),
                participants == null ? List.of() : participants,
                loc, scheduledTick, 0L,
                RiteOutcome.PENDING, village.getId());
        RiteSavedData.get(level).putRite(exec);
        return java.util.Optional.of(exec.riteId());
    }

    /** Whether the village's dominant religion ritualises {@code rite}. R3a —
     *  the culture→religion resolution is centralized in
     *  {@link ReligionContent#villageReligionId} (single source of truth). */
    public static boolean villageRitualises(ServerLevel level, Village village, Rite rite) {
        return religionRitualises(level, village, null, rite);
    }

    /** R3e-2b — whether {@code faithId} (null → village dominant) ritualises
     *  {@code rite}. One gate for both the dominant and shrine-faith paths. */
    public static boolean religionRitualises(ServerLevel level, Village village,
                                             String faithId, Rite rite) {
        String id = faithId != null ? faithId : ReligionContent.villageReligionId(level, village);
        Religion religion = ReligionRegistry.get(id);
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
    private static void scheduleOrdinations(ServerLevel level, VillageSavedData vdata,
                                            Map<UUID, List<TownspersonMob>> priestsByVillage) {
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

    // ── Consecration scheduling (R3b-1) ───────────────────────────────────

    /** Per-faith base daily treasury blessing per consecrated religious
     *  building (scaled by the religion's CONSECRATION profile). Minor + bounded
     *  (one deposit per village per day). */
    private static final long CONSECRATION_DAILY_BRONZE = 5L;

    /**
     * Mirrors {@link #scheduleOrdinations}: a bounded daily per-village scan
     * that schedules a {@link Rite#CONSECRATION} for each religious building
     * (TEMPLE / CHAPEL / SHRINE) not yet consecrated and with no pending
     * consecration, provided the village has a priest able to officiate one
     * (GRAND tier). A vacant presider is left → claimed by a qualified priest
     * through the normal R1a/R1b path. Gating on an available officiant avoids
     * SKIP-churn in the unpruned rite ledger (same reasoning as ordinations).
     */
    private static void scheduleConsecrations(ServerLevel level, VillageSavedData vdata,
                                              Map<UUID, List<TownspersonMob>> priestsByVillage) {
        for (Village village : vdata.getAllVillages()) {
            List<TownspersonMob> priests =
                    priestsByVillage.getOrDefault(village.getId(), List.of());
            boolean officiantAvailable = priests.stream()
                    .anyMatch(p -> RiteCapability.canOfficiate(p, Rite.CONSECRATION));
            if (!officiantAvailable) continue;

            // One ledger pass per village (the ledger is unpruned, so compute
            // the consecrated/pending building sets once, not per building).
            Set<UUID> consecrated = new HashSet<>();
            Set<UUID> pending = new HashSet<>();
            collectConsecrationMarkers(level, village.getId(), consecrated, pending);

            for (UUID bid : village.getBuildingIds()) {
                if (consecrated.contains(bid) || pending.contains(bid)) continue;
                Building b = vdata.getBuildingById(bid).orElse(null);
                if (b == null || !isReligiousBuilding(b.getType())) continue;
                BlockPos origin = b.getShape().getOrigin();
                scheduleConsecrationRite(level, village, bid,
                        origin != null ? origin : village.getVillageCentre());
            }
        }
    }

    /**
     * Grants the ongoing consecration blessing: a small daily treasury deposit
     * per consecrated religious building that STILL EXISTS (the building being
     * gone ends the blessing even though the marker rite persists). Scaled
     * per-faith via {@link ReligionContent}.
     */
    private static void applyConsecrationBlessings(ServerLevel level, VillageSavedData vdata) {
        for (Village village : vdata.getAllVillages()) {
            Set<UUID> consecrated = new HashSet<>();
            collectConsecrationMarkers(level, village.getId(), consecrated, new HashSet<>());
            if (consecrated.isEmpty()) continue;

            int standing = 0;
            for (UUID bid : consecrated) {
                Building b = vdata.getBuildingById(bid).orElse(null);
                if (b != null && isReligiousBuilding(b.getType())) standing++;
            }
            if (standing == 0) continue;
            RiteProfile profile = ReligionContent.profileFor(
                    ReligionContent.villageReligionId(level, village), Rite.CONSECRATION);
            long blessing = Math.round(
                    CONSECRATION_DAILY_BRONZE * standing * profile.pietyScale());
            if (blessing > 0) {
                village.depositToTreasury(blessing);
                vdata.markDirty();
            }
        }
    }

    /** Single ledger pass: fills {@code consecrated} with building ids that have
     *  a SUCCESSFUL CONSECRATION rite and {@code pending} with those that have a
     *  PENDING one. The rite ledger IS the consecration marker (unpruned — no
     *  building/codec field). */
    private static void collectConsecrationMarkers(ServerLevel level, UUID villageId,
                                                   Set<UUID> consecrated, Set<UUID> pending) {
        for (RiteExecution r : RiteSavedData.get(level).ritesForVillage(villageId)) {
            if (r.type() != Rite.CONSECRATION || r.participantIds().isEmpty()) continue;
            UUID buildingId = r.participantIds().get(0);
            if (r.outcome() == RiteOutcome.SUCCESSFUL) consecrated.add(buildingId);
            else if (r.outcome() == RiteOutcome.PENDING) pending.add(buildingId);
        }
    }

    private static boolean isReligiousBuilding(BuildingType type) {
        return type == BuildingType.TEMPLE
                || type == BuildingType.CHAPEL
                || type == BuildingType.SHRINE;
    }

    /** Schedules a CONSECRATION rite at the building's origin, vacant presider,
     *  due immediately. Not {@code ritualises}-gated — every faith consecrates
     *  its own buildings (like ordination, this is universal). */
    private static void scheduleConsecrationRite(ServerLevel level, Village village,
                                                 UUID buildingId, BlockPos location) {
        RiteExecution exec = new RiteExecution(UUID.randomUUID(), Rite.CONSECRATION,
                java.util.Optional.empty(), List.of(buildingId),
                location != null ? location : BlockPos.ZERO,
                level.getGameTime(), 0L, RiteOutcome.PENDING, village.getId());
        RiteSavedData.get(level).putRite(exec);
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
