package tterrag1112.life_in_the_village.Village.Event;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Events.EventDispatcher;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 5 doc 32 § "Life-event driven" — a {@link NpcLifeEventBus
 * EventDispatcher} that schedules a corresponding {@link VillageEvent}
 * whenever the bus fires a Married / BirthInFamily / FamilyDeath /
 * LifeStageAdvanced (TEEN→ADULT) / OfficeChange event.
 *
 * <p>The actual rite (MARRIAGE / NAMING / FUNERAL / COMING_OF_AGE) is
 * still scheduled by {@code RiteLifeEventProducer}; this producer
 * adds the public ceremony around the rite — the rite is the
 * religious core, the event is the social envelope.</p>
 */
public final class EventLifeEventProducer implements EventDispatcher {

    /** Lead-in delay before the event runs. Spec: 1-3 days. v1 uses 1 day. */
    public static final long DEFAULT_LEAD_DAYS = 1L;
    public static final long TICKS_PER_DAY = 24000L;

    @Override public String name() { return "EventLifeEventProducer"; }

    @Override
    public void onEvent(NpcLifeEvent event) {
        if (event instanceof NpcLifeEvent.Married m) {
            scheduleWedding(m);
        } else if (event instanceof NpcLifeEvent.BirthInFamily b) {
            scheduleNaming(b);
        } else if (event instanceof NpcLifeEvent.FamilyDeath d) {
            scheduleFuneral(d);
        } else if (event instanceof NpcLifeEvent.LifeStageAdvanced ls) {
            if ("TEEN".equalsIgnoreCase(ls.oldStage())
                    && "ADULT".equalsIgnoreCase(ls.newStage())) {
                scheduleComingOfAge(ls);
            }
        } else if (event instanceof NpcLifeEvent.OfficeChange oc) {
            scheduleInauguration(oc);
        }
    }

    // ── Per-event scheduling ─────────────────────────────────────────

    private void scheduleWedding(NpcLifeEvent.Married m) {
        TownspersonMob subject = m.subject();
        ServerLevel level = serverLevelOf(subject);
        if (level == null) return;
        Village village = villageOf(subject, level).orElse(null);
        if (village == null) return;

        long sched = level.getGameTime() + DEFAULT_LEAD_DAYS * TICKS_PER_DAY;
        List<UUID> required = new ArrayList<>();
        required.add(subject.getUUID());
        required.add(m.spouseId());

        List<UUID> invited = householdsOf(subject, m.spouseId(), level);

        VillageEventScheduler.scheduleLifeEvent(level, village,
                VillageEvent.EventType.WEDDING, sched,
                subject.getUUID(), required, invited);
    }

    private void scheduleNaming(NpcLifeEvent.BirthInFamily b) {
        TownspersonMob parent = b.subject();
        ServerLevel level = serverLevelOf(parent);
        if (level == null) return;
        Village village = villageOf(parent, level).orElse(null);
        if (village == null) return;

        long sched = level.getGameTime() + DEFAULT_LEAD_DAYS * TICKS_PER_DAY;
        List<UUID> required = List.of(parent.getUUID());
        List<UUID> invited = householdsOf(parent, null, level);

        VillageEventScheduler.scheduleLifeEvent(level, village,
                VillageEvent.EventType.NAMING_CEREMONY, sched,
                b.childId(), required, invited);
    }

    private void scheduleFuneral(NpcLifeEvent.FamilyDeath d) {
        TownspersonMob mourner = d.subject();
        ServerLevel level = serverLevelOf(mourner);
        if (level == null) return;
        Village village = villageOf(mourner, level).orElse(null);
        if (village == null) return;

        // Avoid double-scheduling: only the first family member to
        // process the death schedules the funeral. Use deceased UUID
        // as the dedupe key.
        UUID deceased = d.deceasedId();
        boolean already = VillageSavedData.get(level).getAllEvents().stream()
                .anyMatch(e -> e.getType() == VillageEvent.EventType.FUNERAL
                        && e.getPrimarySubjectId().map(deceased::equals).orElse(false));
        if (already) return;

        long sched = level.getGameTime() + DEFAULT_LEAD_DAYS * TICKS_PER_DAY;
        List<UUID> required = List.of(mourner.getUUID());
        List<UUID> invited = householdsOf(mourner, null, level);

        VillageEventScheduler.scheduleLifeEvent(level, village,
                VillageEvent.EventType.FUNERAL, sched,
                deceased, required, invited);
    }

    private void scheduleComingOfAge(NpcLifeEvent.LifeStageAdvanced ls) {
        TownspersonMob subject = ls.subject();
        ServerLevel level = serverLevelOf(subject);
        if (level == null) return;
        Village village = villageOf(subject, level).orElse(null);
        if (village == null) return;

        long sched = level.getGameTime() + DEFAULT_LEAD_DAYS * TICKS_PER_DAY;
        List<UUID> required = List.of(subject.getUUID());
        List<UUID> invited = householdsOf(subject, null, level);

        VillageEventScheduler.scheduleLifeEvent(level, village,
                VillageEvent.EventType.COMING_OF_AGE, sched,
                subject.getUUID(), required, invited);
    }

    private void scheduleInauguration(NpcLifeEvent.OfficeChange oc) {
        TownspersonMob subject = oc.subject();
        ServerLevel level = serverLevelOf(subject);
        if (level == null) return;
        Village village = villageOf(subject, level).orElse(null);
        if (village == null) return;

        long sched = level.getGameTime() + DEFAULT_LEAD_DAYS * TICKS_PER_DAY;
        List<UUID> required = List.of(subject.getUUID());
        // Invite the whole village — inauguration is a civic event.
        List<UUID> invited = EventAttendance.villageNpcIds(
                level, village, VillageSavedData.get(level));

        VillageEvent event = VillageEventScheduler.scheduleLifeEvent(level, village,
                VillageEvent.EventType.OFFICE_INAUGURATION, sched,
                subject.getUUID(), required, invited);
        if (event != null) event.putEventData("officeId", oc.officeId());
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static ServerLevel serverLevelOf(TownspersonMob npc) {
        return npc.level() instanceof ServerLevel s ? s : null;
    }

    private static Optional<Village> villageOf(TownspersonMob npc, ServerLevel level) {
        return npc.getAssignedVillageName().flatMap(name ->
                VillageSavedData.get(level).getVillageByName(name));
    }

    /**
     * Best-effort household assembly: returns the union of the two
     * NPCs' household members (if present), de-duped, excluding self
     * and primary-subject collisions. Used to seed the invited list.
     */
    private static List<UUID> householdsOf(TownspersonMob a, UUID otherId,
                                           ServerLevel level) {
        List<UUID> out = new ArrayList<>();
        addHousehold(a, out);
        if (otherId != null) {
            TownspersonMob b = TownspersonMob.findByUUID(level, otherId).orElse(null);
            if (b != null) addHousehold(b, out);
        }
        // de-dupe
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        out.removeIf(id -> !seen.add(id));
        return out;
    }

    private static void addHousehold(TownspersonMob npc, List<UUID> out) {
        try {
            npc.getFamily().getSpouseId().ifPresent(out::add);
            out.addAll(npc.getFamily().getChildrenIds());
            npc.getFamily().getHeadOfHouseholdId().ifPresent(out::add);
        } catch (RuntimeException ignored) {
            // Family component not loaded — skip.
        }
    }
}
