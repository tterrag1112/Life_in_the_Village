# 32 — Events Expanded

## Purpose

The existing `VillageEvent` system handles a handful of scheduled
overrides (MARKET_DAY, FESTIVAL, PLAGUE from Phase 3). Phase 5
expands it into a rich catalogue of events that mark the village
calendar: cultural holy days, seasonal festivals, coming-of-age
ceremonies, weddings, funerals, harvest thanksgivings, trials,
caravan arrivals, visits from notable figures.

Events are the texture of village life. Without them the world runs
on schedule but never pauses for a birthday or a funeral. This doc
specifies the expanded catalogue, scheduling rules, attendance logic,
and mood/memory/gossip integration.

## Data model

### EventCategory

```java
public enum EventCategory {
    SEASONAL_FESTIVAL,
    RELIGIOUS_RITE,         // from `20-religion-priest.md`
    LIFE_STAGE_RITE,        // coming-of-age, wedding, funeral
    ECONOMIC,               // market day, caravan arrival
    CIVIC,                  // town meeting, trial (from Phase 3)
    CULTURAL,               // minstrel performance, storytelling night
    NOTABLE_VISIT,          // envoy, renowned scholar
    CRISIS;                 // plague, fire, attack
}

public enum EventType {
    // Seasonal
    SPRING_EQUINOX,
    HARVEST_FESTIVAL,
    WINTER_FEAST,
    SUMMER_MARKET,

    // Religious (per-culture)
    SUNSTEAD_EQUINOX,
    LOOM_THREADING,
    TIDECALL_FULL_MOON,
    FORGE_CREED_KINGDOM_DAY,

    // Life-stage
    COMING_OF_AGE,
    WEDDING,
    FUNERAL,
    NAMING_CEREMONY,

    // Economic
    MARKET_DAY,             // existing
    CARAVAN_ARRIVAL,
    GUILD_FAIR,             // guild-sponsored market
    CRAFT_CONTEST,          // guild competition

    // Civic
    TOWN_MEETING,
    TRIAL,                  // from Phase 3
    OFFICE_INAUGURATION,
    LAW_ENACTMENT_ANNOUNCEMENT,

    // Cultural
    MINSTREL_PERFORMANCE,
    STORYTELLING_NIGHT,
    SCHOLAR_LECTURE,

    // Notable visit
    ENVOY_RECEPTION,
    SCHOLAR_EXCHANGE,
    PILGRIM_CONVERGENCE,    // multiple pilgrims in one event

    // Crisis
    PLAGUE_OUTBREAK,        // from Phase 3
    FIRE,
    MILITIA_CALL,           // kingdom defense stub
    FAMINE;

    public EventCategory category();
}
```

### Event

```java
public class Event {
    private UUID eventId;
    private EventType type;
    private UUID villageId;
    private long scheduledTick;
    private long startedTick;
    private long completedTick;
    private int expectedDurationTicks;
    private EventStatus status;
    private BlockPos location;
    private UUID primarySubjectId;          // bride, teen-coming-of-age, deceased, etc.
    private List<UUID> requiredAttendees;   // must be present to run
    private List<UUID> invitedAttendees;    // optional
    private List<UUID> actualAttendees;     // who showed up
    private Map<String, Object> eventData;  // type-specific payload

    public static final Codec<Event> CODEC;
}

public enum EventStatus {
    SCHEDULED,
    ACTIVE,
    COMPLETED,
    CANCELLED,
    DISRUPTED;
}
```

### EventScheduler

Per-village or per-kingdom scheduler runs:

```java
public final class EventScheduler {
    public static void scheduleEvent(Event e, VillageSavedData data);
    public static void tickEvents(ServerLevel level, long tick, VillageSavedData data);
    public static void onLifeEventTrigger(NpcLifeEvent evt, VillageSavedData data);
}
```

Events are tick-polled daily for scheduling; active events poll per-
second for attendance and progression.

## Scheduling sources

### Calendar-driven

Recurring events on fixed schedule:

- SPRING_EQUINOX, HARVEST_FESTIVAL, WINTER_FEAST, SUMMER_MARKET —
  seasonal, fixed day-of-year.
- MARKET_DAY — weekly, typically mid-week.
- Cultural holy days — per-culture interval (from
  `31-cultures.md`).
- GUILD_FAIR — monthly or seasonal per guild.

### Life-event driven

Triggered by NPC lifecycle events:

- COMING_OF_AGE — teen→adult transition.
- WEDDING — courtship completion.
- FUNERAL — NPC death.
- NAMING_CEREMONY — child birth.
- OFFICE_INAUGURATION — new office holder selected.

Scheduler hooks into life-event bus; auto-schedules the event for the
next available slot (typically 1-3 days out).

### Crisis-driven

Triggered by simulation state:

- PLAGUE_OUTBREAK — existing trigger.
- FAMINE — village food stockpile below threshold for N days.
- FIRE — rare random + flammable-building density.
- MILITIA_CALL — kingdom-level stub.

### Player-initiated

Player with appropriate office can schedule:

- TOWN_MEETING — village leader.
- GUILD_FAIR — guild master.
- CRAFT_CONTEST — guild master.
- SCHOLAR_LECTURE — high scholar.

### Visit-driven

Triggered by visitor arrival:

- CARAVAN_ARRIVAL — on caravan entering village.
- ENVOY_RECEPTION — on envoy visitor spawn.
- SCHOLAR_EXCHANGE — on scholar-visiting spawn.

## Attendance

### Required vs. invited

Each event specifies required attendees (event can't run without them)
and invited attendees (should attend but event continues if absent).

Examples:
- WEDDING: required = bride, groom, priest; invited = both households,
  close friends.
- TRIAL: required = accused, presiding officer; invited = witnesses,
  village at large.
- CRAFT_CONTEST: required = at least 2 contestants, guild master;
  invited = guild members, public.

### Attendance decision

For each invited NPC:

```java
boolean willAttend(TownspersonMob npc, Event event) {
    // Base probability from event type
    float base = attendanceBaseline(event.type());
    // Relationship to primary subject
    float rel = npc.getRelationships().getScore(event.primarySubjectId()) / 100f;
    // Schedule conflict
    if (inCritialWorkPhase(npc, event.scheduledTick())) base *= 0.3f;
    // Mood
    if (npc.getMood().category() == DISTRESSED) base *= 0.5f;
    // Cultural expectation (law FESTIVAL_MANDATORY)
    if (lawMandatesAttendance(village, event.type())) return true;

    return rng.nextFloat() < clamp(base + rel * 0.4f, 0, 1);
}
```

### Event override

Active events can set `eventOverride` on all attendees (via existing
mechanism), pulling them from normal schedule for the event duration.

Required attendees get hard override; invited get soft override
(they'll attend if able but fall back to normal schedule).

## Event execution

### Common phases

Every event has:

1. **Gathering** — attendees navigate to event location.
2. **Core** — main activity runs.
3. **Dispersal** — attendees return to normal schedule.

Duration depends on event type (wedding ~400 ticks core, market day
~10000, trial ~600).

### Type-specific handlers

Each `EventType` has a handler class:

```java
public interface EventHandler {
    EventType type();
    void onStart(Event event, ServerLevel level, VillageSavedData data);
    void onTick(Event event, ServerLevel level, long tick);
    void onComplete(Event event, ServerLevel level);
    int defaultDuration();
}
```

Handlers:
- Apply mood triggers to attendees at appropriate phases.
- Produce memory entries for attendees (SHARED_FESTIVAL,
  ATTENDED_WEDDING, OBSERVED_TRIAL).
- Generate rumor seeds.
- Route coin flows (weddings: family gifts; market days: sales taxes).
- Trigger side effects (wedding creates marriage memory + relationship
  seed; funeral updates village history).

### Example: HARVEST_FESTIVAL

Handler behavior:
- Gathering: attendees walk to town square during afternoon.
- Core: 6000-tick festival with music, food distribution, speeches.
- Priest officiates HARVEST_THANKSGIVING rite if present.
- All attendees gain SHARED_FESTIVAL memory, mood +10.
- Village food-need -20% for next 2 days (festival bounty).
- Minstrel visitor auto-attracted (high probability).
- Rumor seeds: "Harvest was [good/poor] this year", "Leader gave [a
  great / hollow] speech".
- Village history records the festival yearly.

### Example: FUNERAL

Handler behavior:
- Scheduled 1-2 days after death.
- Gathering: household + close friends + priest at temple or
  cemetery.
- Core: priest officiates (if present); attendees stand solemn.
- Duration 400 ticks.
- Mood effects: attendees −20 mood short-term (grief) but also −30%
  on the pending GRIEF memory triggers (closure effect).
- Deceased's pending Unfinished Business (if any) from
  `15-child-elderly-arcs.md` resolves as "unresolved" with village
  history note.
- Village history entry.

### Example: CRAFT_CONTEST

Handler behavior:
- Scheduled by guild master.
- Contestants submit an item within submission window (1 day).
- Judging day: attendees view submissions; judges (guild officers)
  evaluate.
- Winner declared; prestige + bronze reward + relationship bonus
  with guild master.
- Losers: small mood drop if low Temperance; no effect otherwise.
- Guild prestige increases on well-attended contest.
- Rumor seeds: who won, whose work was surprising.

## Mood and memory integration

Every event fires appropriate events on the life-event bus:

- `EventAttended(eventId, eventType)` for each attendee.
- `EventHostedBy(officerId)` for officiator.
- Type-specific events: `WeddingWitnessed`, `FuneralAttended`, etc.

Producers (memory, mood, relationship, trait-drift) consume as usual.

## Player participation

Player sees events in a new `Upcoming Events` panel accessible from
village leader or notice board.

Attendance:
- Player can attend any event they're aware of by being at the
  location during the event.
- Attending fires player-side mood / memory / relationship effects
  identical to NPC attendance.
- Some events allow player-active roles (speak at TOWN_MEETING,
  submit to CRAFT_CONTEST, preside at TRIAL if office-holder).

### Hosting

Player with office can schedule and host events. UI lets them pick
event type, scheduled tick, invitees, and special options (contest
theme, meeting agenda).

## Persistence

Events persist per-village on `VillageSavedData`:

```
villageEvents: {
    active: [... Event records ...],
    upcoming: [... Event records ...],
    pastYear: [... Event records (truncated) ...]
}
```

Past events keep for 365 in-game days then summarized into village
history (`30-village-history.md`).

## Integration points

### Phase 5 integration

- `EventType` enum filled out.
- `Event` record + `EventStatus` + handlers per type.
- `EventScheduler` added; daily calendar tick + life-event
  subscription.
- Crisis detection (FIRE, FAMINE) added to village daily tick.
- Player office UI: "Schedule Event" action.
- `UpcomingEventsScreen` for player.
- Event notification system (gossip seed on scheduling).
- `/event` debug commands:
  - `/event schedule <village> <type> [subject]`
  - `/event list <village>`
  - `/event start <eventId>`
  - `/event cancel <eventId>`

### Phase 5 cross-wiring

- `31-cultures.md` — cultural holy days.
- `20-religion-priest.md` — religious rites as events.
- `15-child-elderly-arcs.md` — life-stage ceremonies.
- `19-crime-justice.md` — trials as events.
- `30-village-history.md` — event history.

## Behavior contract

### Does

- Provide a catalogue of event types with standard structure.
- Schedule events calendar-, life-event-, crisis-, and player-driven.
- Manage attendance with required/invited split.
- Execute events with type-specific handlers.
- Fire mood/memory/rumor effects uniformly.

### Does not

- Model every possible ceremony variant (Phase 6 content expansion).
- Generate procedural event content beyond templates.
- Support multi-village simultaneous events (single-village scope
  except for specific kingdom events).
- Replace existing event override mechanism — extends it.

## Edge cases

- **Scheduled event with required attendee who dies before start.**
  Cancelled; notification fires.
- **Two events scheduled for same tick.** Lower-priority event auto-
  bumps to next available slot.
- **Crisis event during another active event.** Active event
  DISRUPTED; crisis takes priority.
- **Player scheduled event with no attendees.** Runs with just
  officiator; small reputation hit for unpopular event.
- **Event duration exceeds scheduled block** (e.g. trial runs long).
  Event continues past; late attendees may leave; event completes
  when content resolves.

## Ordering dependencies

Phase 5 depends on:
- Most phase 0-4 systems (life events, trials, rites, festivals).
- Cultures (same phase) — cultural holy days.
- Village history (Phase 4) — post-event archival.

## Open decisions

- Event attendance reminders / notifications to player? **Proposed:
  yes — 1 day before via dialogue greeting "don't forget the wedding
  tomorrow".**
- Event duration scaling for small vs. large villages? **Proposed:
  yes — small villages run shorter versions of festivals; scales
  with population.**
- Failed-attendance penalty — does missing a family wedding hurt
  relationships? **Proposed: yes — -15 relationship with primary
  subject if required-tier attendee misses.**

## Does-not-include

- Event ticketing or paid attendance (festivals are free; trials
  open).
- Cross-village joint events (festivals shared between villages).
  Future.
- Procedural story arcs spanning multiple events.
- Event replay / commemoration mechanics.

## Revision Notes

(changes recorded here as the spec evolves after testing)
