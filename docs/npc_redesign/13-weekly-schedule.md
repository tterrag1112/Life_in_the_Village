# 13 — Weekly Schedule

## Purpose

The current `WorkSchedule` is one `DailySchedule` per profession. Every
day looks the same; every NPC of the same profession has the same day.
This makes villages feel mechanical.

The weekly schedule extends the model: 7 different daily schedules per
profession, plus per-NPC personal overrides that vary by trait, plus a
subdivided `DayPhase` enum that gives hobbies, market runs, and errands
a place to live.

Events already override schedules (`TownspersonMob.setEventOverride`).
The new layer stacks below events:

```
personalOverride > eventOverride > weeklyVariant > professionDefault
```

## Data model

### Expanded DayPhase

The existing `DayPhase` enum (`WAKE_UP`, `WORK`, `MEAL`, `SOCIAL`,
`HOME`) subdivides:

```java
public enum DayPhase {
    WAKE_UP,
    COMMUTE,           // traveling to workplace
    WORK_PRIMARY,      // main production hours
    WORK_ERRAND,       // short away-from-workplace task (buy supplies, deliver)
    MEAL,              // midday meal
    WORK_SECONDARY,    // afternoon production
    MARKET_RUN,        // shopping for household or profession
    SOCIAL,            // talking at town square / inn
    LEISURE,           // hobby time, day-off activity
    HOME_PREP,         // returning home, preparing for night
    HOME;              // sleeping
}
```

Legacy `WORK` maps to `WORK_PRIMARY` for backward compat; existing
goals that check `isWorkTime()` include all WORK_* phases by default.

### WeeklySchedule

Seven `DailySchedule` slots, one per in-game day of the week:

```java
public record WeeklySchedule(
    DailySchedule[] days,    // length 7, indexed 0 (Monday) .. 6 (Sunday)
    int[] dayOffs            // day indices that are days off (e.g. [5] = Saturday)
) {
    public DailySchedule getForDay(long gameTick) {
        int dayOfWeek = computeDayOfWeek(gameTick);
        return days[dayOfWeek];
    }

    public boolean isDayOff(long gameTick) {
        int dayOfWeek = computeDayOfWeek(gameTick);
        for (int d : dayOffs) if (d == dayOfWeek) return true;
        return false;
    }

    public static final Codec<WeeklySchedule> CODEC;
}
```

Day of week computation: `(gameTick / 24000L) % 7`.

### DailySchedule (existing, extended)

Existing `DailySchedule` record (per `WorkSchedule`) gains fields for
the new phases:

```java
public record DailySchedule(
    TimeWindow wakeUp,
    TimeWindow commute,             // new
    TimeWindow workPrimary,         // renamed from work
    TimeWindow workErrand,          // new
    TimeWindow meal,
    TimeWindow workSecondary,       // new
    TimeWindow marketRun,           // new
    TimeWindow social,
    TimeWindow leisure,             // new
    TimeWindow homePrep,            // new
    TimeWindow home
) {
    public DayPhase phaseAt(long dayTime) { ... }
}
```

Existing schedules migrate: old `work` becomes `workPrimary`. New
fields default to empty `TimeWindow` (inactive) until the weekly
schedule populates them.

### WeeklyScheduleLibrary

Per-profession weekly schedules defined statically:

```java
public final class WeeklyScheduleLibrary {
    private static final Map<Profession, WeeklySchedule> defaults = ...;

    public static WeeklySchedule forProfession(Profession p);
    public static WeeklySchedule dayOffVariant(WeeklySchedule base,
                                               int dayOfWeek);
}
```

Every profession gets a weekly schedule with:

- 5 regular work days (monday-friday)
- 1 half-day (often saturday)
- 1 day off (typically sunday)

Variations per profession:
- FARMER: no day off during harvest season
- INNKEEPER: day off shifted to mid-week (tuesday)
- GUARD: rotates — guards don't all take the same day
- BLACKSMITH: full weekend off
- PRIEST: sunday = busiest day (services); day off monday
- SCRIBE / SCHOLAR: flexible; most intensive work midweek

Phase 2 ships with defaults for all existing professions. Phase 5
culture pass overrides per-culture (some cultures have no concept of
weekend; some have festival-days that shift the rhythm).

### PersonalScheduleOverride

Per-NPC overrides stored alongside the weekly base:

```java
public record PersonalScheduleOverride(
    Map<DayPhase, TimeWindow> phaseShifts,  // e.g. WAKE_UP shifted later
    Set<Integer> extraDayOffs,              // additional day-off indices
    Set<Integer> overrideDayOffs            // remove profession-default days off
) {
    public static final Codec<PersonalScheduleOverride> CODEC;
    public static final PersonalScheduleOverride NONE;
}
```

### Schedule layering

```java
public final class ScheduleResolver {
    public static DayPhase phaseAt(TownspersonMob npc, long gameTick) {
        // 1. Event override (existing)
        if (npc.hasEventOverride()) return fromEvent(npc.getEventOverride(), gameTick);

        // 2. Personal override
        // 3. Weekly variant
        // 4. Profession default
        DailySchedule schedule = resolveSchedule(npc, gameTick);
        return schedule.phaseAt(gameTick % 24000);
    }

    public static boolean isDayOff(TownspersonMob npc, long gameTick) { ... }
}
```

The existing `TownspersonMob.getCurrentPhase` routes through
`ScheduleResolver` instead of directly consulting `WorkSchedule`.

## Personal overrides by trait

At adulthood, each NPC is assigned a `PersonalScheduleOverride`
generated from their trait vector:

| Trait pattern | Override |
|---|---|
| Industry > +0.5 | Wake 500 ticks earlier; `leisure` reduced by 40% |
| Industry < -0.5 | Wake 500 ticks later; `workErrand` shifts to avoid heavy work |
| Sociability > +0.5 | `social` extended; `leisure` reduced |
| Sociability < -0.5 | `social` minimal; extra `leisure` at home |
| Ambition > +0.5 | Extra `workErrand` slot; shorter meal |
| Temperance > +0.5 | Regular `commute` buffer; never works into night |
| Temperance < -0.5 | `workErrand` and `marketRun` disorganized (random) |
| High Compassion | Extra weekday `leisure` slot for household visits |

Overrides are generated at adulthood and mostly stable for life, with
minor drift when traits drift significantly (Phase 1 trait-drift
integration).

## Day-off behavior

On a day-off, `WORK_PRIMARY`, `WORK_ERRAND`, `WORK_SECONDARY` phases
collapse to `LEISURE`. `MARKET_RUN` still runs — errands don't stop.
`MEAL` and `HOME`/`HOME_PREP` also run normally.

NPC goals during `LEISURE` pick from hobby activities (see
`14-hobby-activities.md`). Without hobbies, NPCs wander the village,
visit friends, or sit at home.

Hooks:
- `isWorkTime()` returns false on day-off for all WORK_* phases.
- Existing production goals exit cleanly; no partial work at start
  of shift.
- Wages still pay — day off doesn't reduce pay. This is realism flex;
  in v1 wages are flat daily.

## Shift rotation (guards, innkeepers)

Some professions need rotating schedules to maintain coverage:

- GUARDS: 3 shifts. Each guard NPC assigned to one shift at spawn
  based on seniority (skill COMBAT). Shift 1 = day (existing default),
  Shift 2 = evening, Shift 3 = night.
- INNKEEPERS: 2 shifts if ≥2 innkeepers in the village; else single.

Shift rotation stored per NPC as a `shiftIndex` field on the
profession-specific role data. `WeeklyScheduleLibrary` factors shift
into the returned schedule: `forProfession(GUARD, shiftIndex=2)` shifts
all time windows by ~6000 ticks.

## Market Run phase

New concrete phase: `MARKET_RUN` runs 1-2 days per week (per profession
or household; varies). During this window:

- Household NPCs head to market (existing `BuyFromMarketGoal`)
- Profession NPCs restock business supplies (existing workstation
  buy-inputs logic)

Market-day event (existing `MARKET_DAY` event override) expands
`MARKET_RUN` to 50% of the day for all NPCs in that village.

## Commute phase

New phase: `COMMUTE` is a short window (~500 ticks, 25 in-game
minutes) between `WAKE_UP` and `WORK_PRIMARY`. During this window,
NPCs walk from home to workplace without performing work tasks. If
the distance is short, phase ends early and NPC arrives before
`WORK_PRIMARY` begins.

Existing `NavigateToWorkGoal` (if it exists) or workstation-goal
start logic covers this. Phase 2 just names the window for clarity.

## Persistence

Per-NPC schedule overrides stored on entity tag under `npcSchedule`:

```
npcSchedule: {
    personalOverride: {
        phaseShifts: { ... },
        extraDayOffs: [3],
        overrideDayOffs: []
    },
    shiftIndex: 0
}
```

Default (all zeros / empty) on missing tags.

## Integration points

### Phase 2 integration

- Existing `WorkSchedule.getSchedule(Profession)` augmented/replaced
  with `WeeklyScheduleLibrary.forProfession(Profession)` and
  `ScheduleResolver.phaseAt(npc, tick)`.
- Existing callers of `WorkSchedule.getSchedule` migrate to use
  `ScheduleResolver` — this is a small but broad change; ensure no
  direct reads remain.
- `TownspersonMob` gets `private PersonalScheduleOverride scheduleOverride`
  field with save/load.
- Adulthood path (life-stage transition) generates override from
  traits.
- `NpcProfileSnapshot` gains current phase and next phase info.
- Profile WorkPanel displays current phase with weekly context ("Day 3
  of 7, currently WORK_PRIMARY, next: MEAL at 12:00").
- `/npc schedule <uuid>` command prints full weekly schedule.
- `/npc schedule set <uuid> day <0-6> phase <name> <start> <end>`
  for testing overrides.

### Phase 3+ integration

- Hobby activities (Phase 2 same-phase) fill `LEISURE`.
- Event attendance system (Phase 5) overrides phases for event day.
- Culture rules (Phase 5) modify default schedules per culture.
- Religion (Phase 3) adds `WORSHIP` phase slots or folds into existing
  social/leisure.

## Behavior contract

### Does

- Store a weekly schedule per profession, day-off aware.
- Apply per-NPC personal overrides generated from traits.
- Resolve current phase via layered lookup.
- Migrate existing single-day profession schedules to weekly
  equivalents.
- Handle shift-rotation for professions that need coverage.

### Does not

- Change wage mechanics; day-off still pays.
- Implement hobby content (that's `14-hobby-activities.md`).
- Persist per-day schedule differences — the weekly schedule is
  periodic, not calendar-aware. Season and festival overrides are
  separate layers.
- Auto-adjust schedules based on weather. Weather affects mood, not
  schedule.

## Edge cases

- **NPC changes profession.** New weekly schedule applies; personal
  override re-generated since traits are stable.
- **Legacy save with only old `DailySchedule`.** Migration converts
  old `work` window into 7 identical days with `workPrimary`. Personal
  override set to NONE. Over time, adulthood re-generation updates.
- **Day-off during a festival.** Festival override wins; NPC attends
  festival, not a leisure-day hobby.
- **Shift worker on day off.** Day-off still applies; replacement
  worker from another shift doesn't automatically cover — may produce
  a coverage gap. Accepted for v1; later polish.
- **Dead NPC's schedule accessed.** Return a safe default phase; never
  crash.

## Ordering dependencies

Phase 2 depends on:
- Trait vector (Phase 0) — for override generation.
- Existing `WorkSchedule`, `DailySchedule`, `TimeWindow`, `DayPhase`
  — extended in place.
- Existing schedule consumers (goals, `isWorkTime`) that need
  migration to `ScheduleResolver`.

## Open decisions

- Day count: 7 days matches real-world expectation. Some cultures
  might use different week lengths. **Proposed: 7 days fixed in v1;
  culture day-count variation is Phase 6 JSON work.**
- Should personal overrides be visible in the NPC profile GUI?
  **Proposed: show only the "primary deviation" line (e.g. "Early
  riser", "Takes Tuesdays off") as a trait-adjacent label. Full
  schedule via debug command.**
- Should the player be able to ask an NPC "when are you free?" to
  schedule meetings? **Proposed: new player verb candidate for Phase
  5; not in Phase 2.**

## Does-not-include

- Seasonal schedule variants (harvest vs winter for farmers). Phase
  5 adds; stubbed here.
- Calendar-aware special days (birthdays, anniversaries). Deferred.
- Schedule planning UI for player-owned NPCs. Deferred.
- Work-week concept for player's profession. Player is player.

## Revision Notes

(changes recorded here as the spec evolves after testing)
