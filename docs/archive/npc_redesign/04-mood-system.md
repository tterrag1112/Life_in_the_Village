# 04 — Mood System

## Purpose

An NPC's mood is their short-term emotional state, distinct from
personality (traits) and relationships. Mood reacts to recent events
— a death in the family, a successful harvest, an insult, a festival —
and decays toward a trait-influenced baseline over days.

Mood affects dialogue tone, trade prices, schedule flexibility, and
social behavior without being a permanent change to the NPC. A grieving
blacksmith isn't *sad* as a trait; they're *sad right now*, and will
recover.

## Data model

### Mood categories

```java
public enum MoodCategory {
    ELATED,     // +75..+100
    CONTENT,    // +25..+74
    NEUTRAL,    //  -24..+24
    TROUBLED,   // -74..-25
    DISTRESSED; // -100..-75

    public static MoodCategory fromValue(float value) {
        if (value >= 75f)  return ELATED;
        if (value >= 25f)  return CONTENT;
        if (value > -25f)  return NEUTRAL;
        if (value > -75f)  return TROUBLED;
        return DISTRESSED;
    }

    public String displayLabel(TraitVector traits) {
        // Optional variant based on traits: e.g. DISTRESSED Compassionate
        // NPC shows "Grieving"; DISTRESSED Callous NPC shows "Bitter"
        // Implementation in code, not data.
    }
}
```

### MoodTrigger

Events that change mood. Tagged with category and default magnitude;
actual applied magnitude modulated by traits at apply time.

```java
public enum MoodTrigger {
    GIFT_RECEIVED      (+8, 0.4f),   // (default magnitude, max stack per day)
    GIFT_FAVORITE      (+20, 0.3f),
    INSULT_RECEIVED    (-12, 0.4f),
    COMPLIMENT_RECEIVED(+5, 0.3f),
    FAMILY_DEATH       (-60, 0.0f),  // one-time, decays normally
    CLOSE_FRIEND_DEATH (-35, 0.0f),
    RIVAL_DEATH        (+8, 0.0f),
    BIRTH_IN_FAMILY    (+35, 0.0f),
    MARRIAGE           (+50, 0.0f),  // applied once on wedding
    FESTIVAL_ATTENDED  (+15, 0.2f),
    CRIME_VICTIM       (-30, 0.3f),
    CRIME_WITNESSED    (-8,  0.2f),
    RESCUED            (+45, 0.0f),
    RESCUED_SOMEONE    (+25, 0.0f),
    PROMOTION          (+30, 0.0f),
    DEMOTION           (-25, 0.0f),
    FIRED_FROM_JOB     (-30, 0.0f),
    HIRED              (+15, 0.0f),
    GOAL_COMPLETED     (+25, 0.0f),
    GOAL_FAILED        (-15, 0.0f),
    WEATHER_PLEASANT   (+2,  0.1f),  // daily, very small
    WEATHER_HARSH      (-3,  0.1f),
    FAMINE             (-10, 0.1f),  // while active
    SEASON_BELOVED     (+5,  0.05f), // NPC's favorite season
    LETTER_RECEIVED    (+6,  0.2f),
    LETTER_FROM_FRIEND (+12, 0.2f),
    RUMOR_POSITIVE_ABOUT_SELF(+6, 0.15f),
    RUMOR_NEGATIVE_ABOUT_SELF(-10, 0.15f);

    private final int defaultMagnitude;
    private final float dailyStackCap;  // max cumulative per day; 0.0f = no cap (event is rare/unique)
}
```

### NpcMoodState

```java
public class NpcMoodState {
    private float value = 0f;                // current mood, -100..+100
    private final List recent;    // last ~5 events for display

    public float value();
    public MoodCategory category();
    public String displayLabel(TraitVector traits);

    public void apply(MoodTrigger trigger, TraitVector traits, long tick);
    public void decay(TraitVector traits, float daysElapsed);

    public List recentEvents();

    public void save(CompoundTag tag);
    public void load(CompoundTag tag);
}

public record MoodEvent(MoodTrigger trigger, int magnitude, long tick) {
    public static final Codec CODEC;
}
```

`recent` is capped at 5 entries. Older events drop off the display but
their mood contribution is baked into `value` already.

## Mood application rules

When a trigger fires:

1. Compute modulated magnitude:
   magnitude = trigger.defaultMagnitude * traitMultiplier(trigger, traits)
   Trait multiplier logic (illustrative):
    - `INSULT_RECEIVED` — higher Temperance dampens (+0.5 at Temperance=+1),
      lower Temperance amplifies (−0.5 at Temperance=−1). Formula:
      `1.0 - 0.5 * traits.get(TEMPERANCE)`.
    - `GIFT_RECEIVED` — Generosity increases joy from giving and
      receiving: `1.0 + 0.3 * traits.get(GENEROSITY)`.
    - `FAMILY_DEATH` — Compassion amplifies: `1.0 + 0.5 * traits.get(COMPASSION)`.

2. Check daily stack cap:
   if trigger.dailyStackCap > 0 and today's accumulated mag
> trigger.defaultMagnitude * trigger.dailyStackCap:
reduce magnitude until cap is hit, or skip if already at cap
Prevents spam (10 gifts in one day don't maxe out mood).

3. Apply: `value += magnitude`, clamped to [−100, +100].

4. Record in `recent` list, evicting oldest if at cap.

## Decay

Called once per in-game day:
baseline = 10 * (traits.get(GENEROSITY) + traits.get(TEMPERANCE)) / 2
+ 5 * traits.get(SOCIABILITY)
  // Baseline is typically in range [-5, +10]; generous temperate
  // sociable NPCs have a mildly positive baseline.
  delta = (baseline - value) * 0.15f  // 15% of gap closed per day
  value += delta

A mood-swinging event pushes value far from baseline; decay slowly
pulls it back. Half-life of a +50 mood bump with neutral baseline is
about 4 days.

Life-event flags (famine active, recent family death) can suppress
decay: while a grieving period is active (30 days after a family
death), decay toward baseline is halved.

## Mood effects

Cross-system effects gated by mood category:

| Category | Dialogue tone | Trade price modifier | Schedule |
|---|---|---|---|
| ELATED | warm, verbose | −5% (gives discount) | normal |
| CONTENT | friendly | −2% | normal |
| NEUTRAL | standard | 0 | normal |
| TROUBLED | curt, shorter lines | +3% | shorter social phase |
| DISTRESSED | withdrawn or angry | +8% (or refuses trade 30%) | skips social, goes home early |

Phase 0 stores mood; these effects wire in with their consumer systems
(dialogue Phase 1, trade Phase 3, schedule Phase 2).

## Persistence

NBT structure on entity tag, rooted at `npcMood`:
npcMood: {
value: 23.4f,
recent: [
{ trigger: "GIFT_RECEIVED", magnitude: 10, tick: 123456L },
{ trigger: "FESTIVAL_ATTENDED", magnitude: 18, tick: 120000L },
...
]
}

Default on load: if `npcMood` tag missing, value = 0.0, recent = empty.

## Integration points

### Phase 0 integration

- `TownspersonMob` gets `private final NpcMoodState mood` field.
- `addAdditionalSaveData` / `readAdditionalSaveData` persist it.
- Daily decay tick hooked into the existing daily tick path (same
  hook that drives `NeedsUpdateEvent` / `VillageEventScheduler`).
- `NpcProfileSnapshot` gains a mood label field (derived from category
    + traits), displayed in Identity panel.
- `/npc mood <uuid>` command prints value, category, recent events.

Phase 0 does **not** wire triggers. The mood stays at 0 for all NPCs
until Phase 1 producers fire.

### Phase 1 producers (deferred)

Every trigger needs a code path that fires it. Examples:
- Gift action → `mood.apply(GIFT_RECEIVED, ...)`
- Family death → death handler calls `apply(FAMILY_DEATH, ...)` on all
  household members
- Festival attended → `AttendEventGoal` end → `apply(FESTIVAL_ATTENDED, ...)`

Full trigger-to-producer mapping lives in each subsystem's doc.

## Behavior contract

### Does

- Store a single mood value per NPC, persisted across save/load.
- Decay mood toward a trait-influenced baseline daily.
- Cap per-event stacking via `dailyStackCap`.
- Modulate magnitude by traits at apply time.
- Expose category and display label for UI.
- Track recent events for UI display.

### Does not

- Apply triggers automatically in Phase 0 — storage and decay only.
- Affect dialogue, trade, schedule in Phase 0 — consumers integrate later.
- Model complex moods (happy-and-angry simultaneously). Single scalar.
- Persist beyond 5 recent events. Older ones live only in value.

## Edge cases

- **Value at clamp boundary.** Value clamped to exactly [−100, +100];
  further triggers in the same direction have no effect.
- **Decay with value already at baseline.** Delta is ~0; no change.
- **Baseline computation produces value outside [−100, +100].** Clamp
  the baseline itself before using it in decay.
- **NPC wakes from long unload (30+ days).** Decay applies for up to
  30 days; beyond that, snap to baseline. Prevents absurd values from
  long-abandoned NPCs.

## Ordering dependencies

Phase 0 scope depends on: `TraitVector` (`01-trait-axes.md`) being
implemented first, for baseline and modulation computations. Without
traits, decay uses 0.0 baseline and 1.0× modulation.

## Open decisions

- Should there be a separate "grief" state that overrides normal decay
  for 30 days after family death? Spec says yes, implemented as a flag.
  Alternatively, handle via a very high initial magnitude that decays
  slowly. **Proposed: use the flag approach for clarity; revisit after
  testing.**
- Should players see mood as a bar or only as a label? **Proposed:
  label only in v1; bar visible only for player-owned NPCs.**

## Does-not-include

- Player mood. Player feelings are player's business.
- Long-term emotional scars. Repeated distress does not leave a mark
  that persists after decay — that's trait drift's job.
- Mood contagion across NPCs. A distressed NPC doesn't lower nearby
  NPCs' moods. Not needed for v1.

## Revision Notes

### 2026-04-23 — Phase 0 implementation (task 04)

Implementation landed in `tterrag1112.life_in_the_village.Npc.Mood`
(`MoodCategory`, `MoodTrigger`, `MoodEvent`, `NpcMoodState`). Pattern
parallels prior Phase 0 components.

**Persistence API.** Same deviation as 01–03: spec uses
`save(CompoundTag)` / `load(CompoundTag)`; implementation uses
`save(ValueOutput)` / `load(ValueInput)` to match the NeoForge 1.21
entity API. Stored as a single `npcMood` subtree with the spec's
nested shape (value, baseline, lastUpdateTick, griefStartTick,
recent[]). The spec record has only `value` + `recent`; implementation
adds `baseline` (cached so trait changes don't silently shift it) and
`griefStartTick` (the spec calls for a grief flag — stored as a tick
so the 30-day window is purely a derived check).

**Class name.** `NpcMoodState`, matching the spec's section header
"NpcMoodState". The task prompt template called it `MoodState`;
followed the spec.

**Daily-stack-cap unit (locked).** The spec field
`MoodTrigger.dailyStackCap` is given as a float (e.g. `0.4f`) without
units. Implementation interprets the cap as the **fraction of the
mood scalar range**: effective cap in mood points is
`100 * dailyStackCap`. So `GIFT_RECEIVED` at `0.4f` caps at 40
cumulative mood/day (~5 stacks of the +8 default). `0.0f` disables
the cap (rare/unique events). Documented on
`MoodTrigger.cumulativeDailyCap`. If a future reading prefers the
literal `mag * cap` formula (which yields tiny per-event caps), only
that one method changes.

**Today's-accumulated tracking.** No new state added — `apply` reads
from the existing `recent` event list (already capped at 5 by spec)
and sums same-trigger entries within the last 24000 ticks. Limitation:
if more than 5 events of the same trigger fire in a single day, only
the last 5 count toward the cap. Phase 0 has no producers, so this is
academic; Phase 1 may revisit if real producers can spam past 5/day.

**Trait modulation table.** Spec gives three illustrative rules
(`INSULT_RECEIVED` × `TEMPERANCE`, `GIFT_RECEIVED` × `GENEROSITY`,
`FAMILY_DEATH` × `COMPASSION`). Implementation extends naturally:

| Trigger              | Multiplier                                 |
|----------------------|--------------------------------------------|
| INSULT_RECEIVED      | `1 - 0.5 * TEMPERANCE` (spec)              |
| GIFT_RECEIVED        | `1 + 0.3 * GENEROSITY` (spec)              |
| GIFT_FAVORITE        | `1 + 0.3 * GENEROSITY`                     |
| FAMILY_DEATH         | `1 + 0.5 * COMPASSION` (spec)              |
| CLOSE_FRIEND_DEATH   | `1 + 0.5 * COMPASSION`                     |
| COMPLIMENT_RECEIVED  | `1 + 0.2 * SOCIABILITY`                    |
| FESTIVAL_ATTENDED    | `1 + 0.3 * SOCIABILITY`                    |
| GOAL_COMPLETED       | `1 + 0.3 * AMBITION`                       |
| GOAL_FAILED          | `1 + 0.3 * AMBITION`                       |
| (all others)         | `1.0` (no modulation)                      |

These are tuning calls; the table lives in one place
(`MoodTrigger.traitMultiplier`) for easy adjustment. Not all 28
triggers have explicit rules — defaults to 1.0.

**Daily-tick wiring.** The existing `NpcMemoryDecayTickSystem` was
extended in place rather than creating a parallel system; per the
task prompt's "Wire decay into the same daily-tick hook used by
memory. Do NOT create a new tick path." The subsystem now reports its
name as `npc_daily_decay` and decays both memory and mood on the same
sweep over loaded TownspersonMob. Class file name unchanged for now
(internal only).

**Long-unload guard.** When `decay(daysElapsed)` is called with
`daysElapsed > 30`, the value snaps to `baseline` directly per the
spec's edge case "NPC wakes from long unload (30+ days)". Below that
threshold, day-by-day geometric decay is applied so the integration
matches a continuous 15%/day rate.

**Spawn baseline.** New NPCs run `mood.initializeFromTraits(traits)`
in `finalizeSpawn` immediately after `traits.randomize`. Baseline =
`10 * (GENEROSITY + TEMPERANCE) / 2 + 5 * SOCIABILITY` (spec
formula); current value is set to the baseline (no jitter at spawn,
per task prompt).

**Not implemented in this session (deferred per spec/Phase 0 scope):**
- Production trigger firings (Phase 1 producers).
- Mood UI surfaces beyond the debug command (NpcProfileSnapshot
  mood label is in the spec's Phase 0 integration but UI changes
  have been deferred consistently across 01–03 too).
- Trait drift from sustained mood (Phase 1).
- Cross-system effects (dialogue tone, trade prices, schedule) are
  consumer concerns landing in their own phases.

**Prompt↔spec naming mismatches (kept for the prompt-template
maintainer):**
- Prompt's category enum uses `EUPHORIC`; spec uses `ELATED`. Used
  spec.
- Prompt calls the component `MoodState`; spec uses `NpcMoodState`.
  Used spec.
- Prompt asks for `applyTrigger(MoodTrigger, TraitVector)`; spec's
  signature is `apply(MoodTrigger, TraitVector, long tick)`. Used
  spec — currentTick is needed for the daily-cap check and the grief
  timer.