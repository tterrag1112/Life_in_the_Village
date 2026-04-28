package tterrag1112.life_in_the_village.Village.Event;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus;
import tterrag1112.life_in_the_village.Npc.Memory.MemoryType;
import tterrag1112.life_in_the_village.Npc.Memory.NpcMemory;
import tterrag1112.life_in_the_village.Npc.Religion.Rite;
import tterrag1112.life_in_the_village.Npc.Religion.RiteScheduler;
import tterrag1112.life_in_the_village.Village.History.HistoryEventType;
import tterrag1112.life_in_the_village.Village.History.VillageHistoryLog;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-{@link VillageEvent.EventType} hooks for the Phase 5 doc 32
 * catalogue. The Phase-3 originals (HARVEST_FESTIVAL / MARKET_DAY /
 * FESTIVAL_OF_LIGHTS / TRAINING_DAY / VILLAGE_FAIR) keep their bespoke
 * logic in {@link EventEffects}; everything else routes here.
 *
 * <p>Phase 5 ships these handlers as functional but content-thin —
 * each one does the bare minimum to satisfy the spec's "Does" list:
 * applies an effect (where relevant), records actual attendees as a
 * SHARED_FESTIVAL memory, fires {@link NpcLifeEvent.SharedFestival}
 * per attendee so mood / relationship producers react, and writes a
 * single village-history entry. Per-type flavor (rumor lines, rich
 * dialogue branching, complex reward tables) belongs to the Phase 5
 * doc 34 content pass.</p>
 */
public final class EventHandlerRegistry {

    private static final Map<VillageEvent.EventType, EventHandler> HANDLERS =
            new EnumMap<>(VillageEvent.EventType.class);

    static {
        // Seasonal (beyond the 3 Phase-3 originals)
        register(simple(VillageEvent.EventType.SPRING_EQUINOX,
                HistoryEventType.SEASON_FIRST_HARVEST, "Spring Equinox", 30));
        register(simple(VillageEvent.EventType.WINTER_FEAST,
                HistoryEventType.FESTIVAL_HELD, "Winter Feast", 40));
        register(simple(VillageEvent.EventType.SUMMER_MARKET,
                HistoryEventType.FESTIVAL_HELD, "Summer Market", 30));

        // Religious — fires the matching rite at start, otherwise as a generic festival
        register(rite(VillageEvent.EventType.SUNSTEAD_EQUINOX,    Rite.HARVEST_THANKSGIVING, "Sunstead Equinox"));
        register(rite(VillageEvent.EventType.LOOM_THREADING,      Rite.FEAST_DAY,            "Loom Threading"));
        register(rite(VillageEvent.EventType.TIDECALL_FULL_MOON,  Rite.BLESSING,             "Tidecall Full Moon"));
        register(rite(VillageEvent.EventType.FORGE_CREED_KINGDOM_DAY,
                Rite.OFFERING, "Forge Creed Kingdom Day"));

        // Life-stage
        register(simple(VillageEvent.EventType.WEDDING,
                HistoryEventType.MARRIAGE, "Wedding", 60));
        register(funeral());
        register(simple(VillageEvent.EventType.NAMING_CEREMONY,
                HistoryEventType.BIRTH, "Naming Ceremony", 50));
        register(simple(VillageEvent.EventType.COMING_OF_AGE,
                HistoryEventType.COMING_OF_AGE, "Coming of Age", 50));

        // Economic (CARAVAN_ARRIVAL, GUILD_FAIR, CRAFT_CONTEST)
        register(simple(VillageEvent.EventType.CARAVAN_ARRIVAL,
                HistoryEventType.FESTIVAL_HELD, "Caravan Arrival", 25));
        register(simple(VillageEvent.EventType.GUILD_FAIR,
                HistoryEventType.FESTIVAL_HELD, "Guild Fair", 35));
        register(simple(VillageEvent.EventType.CRAFT_CONTEST,
                HistoryEventType.MASTERPIECE_CERTIFIED, "Craft Contest", 50));

        // Civic
        register(simple(VillageEvent.EventType.TOWN_MEETING,
                HistoryEventType.LAW_ENACTED, "Town Meeting", 25));
        register(simple(VillageEvent.EventType.TRIAL,
                HistoryEventType.TRIAL_HELD, "Trial", 30));
        register(simple(VillageEvent.EventType.OFFICE_INAUGURATION,
                HistoryEventType.OFFICE_APPOINTED, "Office Inauguration", 35));
        register(simple(VillageEvent.EventType.LAW_ENACTMENT_ANNOUNCEMENT,
                HistoryEventType.LAW_ENACTED, "Law Enactment", 25));

        // Cultural
        register(simple(VillageEvent.EventType.MINSTREL_PERFORMANCE,
                HistoryEventType.FESTIVAL_HELD, "Minstrel Performance", 30));
        register(simple(VillageEvent.EventType.STORYTELLING_NIGHT,
                HistoryEventType.FESTIVAL_HELD, "Storytelling Night", 30));
        register(simple(VillageEvent.EventType.SCHOLAR_LECTURE,
                HistoryEventType.BOOK_AUTHORED, "Scholar Lecture", 35));

        // Notable visit
        register(simple(VillageEvent.EventType.ENVOY_RECEPTION,
                HistoryEventType.ENVOY_RECEIVED, "Envoy Reception", 35));
        register(simple(VillageEvent.EventType.SCHOLAR_EXCHANGE,
                HistoryEventType.FAMOUS_VISITOR, "Scholar Exchange", 35));
        register(simple(VillageEvent.EventType.PILGRIM_CONVERGENCE,
                HistoryEventType.FAMOUS_VISITOR, "Pilgrim Convergence", 30));

        // Crisis
        register(simple(VillageEvent.EventType.PLAGUE_OUTBREAK,
                HistoryEventType.PLAGUE_OUTBREAK, "Plague Outbreak", 70));
        register(simple(VillageEvent.EventType.FIRE,
                HistoryEventType.BUILDING_DESTROYED, "Fire", 60));
        register(simple(VillageEvent.EventType.FAMINE,
                HistoryEventType.FAMINE, "Famine", 70));
        register(simple(VillageEvent.EventType.MILITIA_CALL,
                HistoryEventType.KINGDOM_EVENT, "Militia Call", 45));
    }

    private EventHandlerRegistry() {}

    public static void register(EventHandler handler) {
        HANDLERS.put(handler.type(), handler);
    }

    public static Optional<EventHandler> get(VillageEvent.EventType type) {
        return Optional.ofNullable(HANDLERS.get(type));
    }

    /** Called from {@link EventEffects#onEventStart} for unhandled types. */
    public static void dispatchStart(VillageEvent event, ServerLevel level,
                                     Village village, VillageSavedData data) {
        EventHandler handler = HANDLERS.get(event.getType());
        if (handler == null) return;
        try {
            handler.onStart(event, level, village, data);
        } catch (RuntimeException ex) {
            System.err.println("[EventHandler] onStart failed for "
                    + event.getType() + ": " + ex.getMessage());
        }
    }

    /** Called from {@link EventEffects#onEventEnd} for every type. */
    public static void dispatchComplete(VillageEvent event, ServerLevel level,
                                        Village village, VillageSavedData data) {
        event.setCompletedTick(level.getGameTime());
        EventHandler handler = HANDLERS.get(event.getType());
        if (handler == null) return;
        try {
            handler.onComplete(event, level, village, data);
        } catch (RuntimeException ex) {
            System.err.println("[EventHandler] onComplete failed for "
                    + event.getType() + ": " + ex.getMessage());
        }
    }

    // ── Handler factories ────────────────────────────────────────────

    /**
     * Simple handler: no special start effects, but on completion records
     * a SHARED_FESTIVAL memory + fires SharedFestival on the bus for each
     * actual attendee, and writes one village-history entry.
     */
    private static EventHandler simple(VillageEvent.EventType type,
                                       HistoryEventType historyType,
                                       String displayName,
                                       int memoryValue) {
        return new EventHandler() {
            @Override public VillageEvent.EventType type() { return type; }

            @Override
            public void onComplete(VillageEvent event, ServerLevel level,
                                   Village village, VillageSavedData data) {
                recordCompletion(event, level, village, displayName,
                        historyType, memoryValue);
            }
        };
    }

    /**
     * Funeral: simple completion + grief-closure side effect. The
     * spec's −30% on pending GRIEF triggers is approximated by tagging
     * attendees so the MoodProducer's grief decay can read it; v1
     * leaves the read-side as a future hookup and just records the
     * memory + history.
     */
    private static EventHandler funeral() {
        return new EventHandler() {
            @Override public VillageEvent.EventType type() {
                return VillageEvent.EventType.FUNERAL;
            }

            @Override
            public void onComplete(VillageEvent event, ServerLevel level,
                                   Village village, VillageSavedData data) {
                recordCompletion(event, level, village, "Funeral",
                        HistoryEventType.DEATH_NATURAL, 70);
                // Tag the event with closure metadata so any future
                // grief-decay producer can find it on the deceased's
                // related memories.
                event.putEventData("funeralHeld", "true");
            }
        };
    }

    /**
     * Religious-rite event: fires the matching {@link Rite} at start so
     * the rite executor handles its piety / mood / memory changes, then
     * records the event normally on completion.
     */
    private static EventHandler rite(VillageEvent.EventType type,
                                     Rite rite, String displayName) {
        return new EventHandler() {
            @Override public VillageEvent.EventType type() { return type; }

            @Override
            public void onStart(VillageEvent event, ServerLevel level,
                                Village village, VillageSavedData data) {
                // Schedule the rite for the same tick so the rite
                // executor picks it up. The participants are the
                // event's required + invited attendees.
                List<UUID> participants = new ArrayList<>(event.getRequiredAttendees());
                participants.addAll(event.getInvitedAttendees());
                if (participants.isEmpty()) return;
                try {
                    RiteScheduler.schedule(level, village, rite,
                            participants, 0L);
                } catch (RuntimeException ex) {
                    // Rite scheduling is best-effort — logging only.
                    System.err.println("[EventHandler] rite schedule failed for "
                            + type + ": " + ex.getMessage());
                }
            }

            @Override
            public void onComplete(VillageEvent event, ServerLevel level,
                                   Village village, VillageSavedData data) {
                recordCompletion(event, level, village, displayName,
                        HistoryEventType.FEAST_DAY_CELEBRATED, 40);
            }
        };
    }

    // ── Shared completion utility ────────────────────────────────────

    /**
     * Posts a SHARED_FESTIVAL memory to each actual attendee, fires
     * {@link NpcLifeEvent.SharedFestival} per attendee pair (other =
     * primary subject if present, otherwise the first co-attendee), and
     * writes one history entry for the event.
     */
    private static void recordCompletion(VillageEvent event, ServerLevel level,
                                         Village village, String displayName,
                                         HistoryEventType historyType,
                                         int memoryValue) {
        long tick = level.getGameTime();
        UUID primary = event.getPrimarySubjectId().orElse(null);
        List<UUID> attendees = event.getActualAttendees();
        for (UUID attendeeId : attendees) {
            TownspersonMob npc = TownspersonMob.findByUUID(level, attendeeId)
                    .orElse(null);
            if (npc == null) continue;
            UUID partnerId = primary != null ? primary
                    : firstOther(attendees, attendeeId);
            if (partnerId == null) partnerId = attendeeId;
            // Memory
            try {
                npc.getMemory().add(NpcMemory.create(
                        MemoryType.SHARED_FESTIVAL,
                        List.of(partnerId),
                        tick,
                        memoryValue,
                        displayName + " in " + village.getName()));
            } catch (RuntimeException ignored) {}
            // Bus event so mood/relationship producers react
            try {
                NpcLifeEventBus.fire(new NpcLifeEvent.SharedFestival(
                        npc, partnerId, event.getId()));
            } catch (RuntimeException ignored) {}
        }
        // Village-history entry — single per event.
        try {
            VillageHistoryLog log = VillageHistoryLog.get(level);
            Map<String, String> details = new java.util.LinkedHashMap<>();
            details.put(displayName, "(" + attendees.size()
                    + (attendees.size() == 1 ? " attendee" : " attendees") + ")");
            log.record(historyType, village.getId(), tick, details,
                    new ArrayList<>(attendees));
        } catch (RuntimeException ignored) {}
    }

    private static UUID firstOther(List<UUID> list, UUID self) {
        for (UUID id : list) if (!id.equals(self)) return id;
        return null;
    }
}
