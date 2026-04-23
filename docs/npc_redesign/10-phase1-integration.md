markdown# 10 — Phase 1 Integration

## Purpose

Phase 0 built storage for memory, mood, knowledge, and traits. Phase 1
builds the producers and transitions — the code paths that *write* to
those stores from real game events.

This doc covers three interlocking integrations:

1. **Memory producers** — every game event that should create or
   refresh a memory.
2. **Mood triggers** — every event that should shift an NPC's mood.
3. **Trait life-event drift** — the rare, tiny personality changes
   driven by major life events.

These three share most event sources (a crime victimization creates
a memory AND shifts mood AND may drift traits), so documenting them
together avoids duplication.

## Event source inventory

Every event that produces personhood-system writes is listed below
with its producer(s). The table is the canonical contract: if code
fires any of these, it must also fire the listed producer calls.

### Combat and rescue

| Event source | Memory | Mood | Trait drift |
|---|---|---|---|
| NPC attacked by player | `VICTIM_OF_CRIME_BY(player)` v=60 | `CRIME_VICTIM` | none |
| NPC attacked by NPC | `VICTIM_OF_CRIME_BY(attacker)` v=60 | `CRIME_VICTIM` | none |
| NPC saved from death (HP ≤ 1 → full heal by player) | `SAVED_BY(player)` v=95 pinned | `RESCUED` | +0.05 Compassion (once) |
| Player saves NPC by killing hostile mob attacking them | `SAVED_BY(player)` v=95 pinned | `RESCUED` | — |
| NPC saves another NPC or player | `SAVED(target)` v=85 pinned | `RESCUED_SOMEONE` | +0.03 Courage (once per rescue, max 3 times) |
| NPC witnesses combat death of kin | `WITNESSED_DEATH_OF(kin)` v=100 pinned | `FAMILY_DEATH` | +0.02 Compassion, −0.02 Sociability |
| NPC witnesses combat death of close friend | `WITNESSED_DEATH_OF(friend)` v=80 pinned | `CLOSE_FRIEND_DEATH` | — |
| NPC witnesses combat death of rival | `WITNESSED_DEATH_OF(rival)` v=60 | `RIVAL_DEATH` | — |
| NPC survives battle themselves | — | — | +0.05 Courage (first time), +0.01 (subsequent) |

Hook points:
- Attack events: existing `LivingHurtEvent` and `LivingDeathEvent` on
  NeoForge bus. Extend handlers in `TaskCompletionEvents` or a new
  `NpcCombatEvents` class.
- Rescue detection: tick-level check after `LivingHurtEvent` — was the
  NPC at HP ≤ 1 and now healed by a nearby player? Window of ~5 ticks.
- Death witnessed: `LivingDeathEvent` scans nearby NPCs within 16
  blocks; each witnessing NPC fires `WITNESSED_DEATH_OF`.

### Trade and economic

| Event source | Memory | Mood | Trait drift |
|---|---|---|---|
| NPC completes trade with player | `TRADED_WITH(player)` v=8 (15 if notable) | — | — |
| Player pays NPC wage | — (employment is already persisted) | `HIRED` if first time | — |
| NPC receives gift — favorite match | `RECEIVED_GIFT(giver)` v=35 | `GIFT_FAVORITE` | — |
| NPC receives gift — appropriate | `RECEIVED_GIFT(giver)` v=20 | `GIFT_RECEIVED` | — |
| NPC receives gift — off-base | `RECEIVED_GIFT(giver)` v=12 | (half magnitude of `GIFT_RECEIVED`) | — |
| NPC receives gift — insulting | — (no positive memory) | `INSULT_RECEIVED` | — |
| NPC gives gift to someone | `GAVE_GIFT(recipient)` v=10 | (small positive, +3) | — |
| NPC completes commission on time | — | `GOAL_COMPLETED` proportional | — |
| NPC misses commission deadline | — | `GOAL_FAILED` proportional | −0.02 Industry (if repeated) |
| NPC is fired from job | — | `FIRED_FROM_JOB` | −0.03 Ambition |
| NPC promoted | — | `PROMOTION` | +0.02 Ambition |
| NPC loses significant wealth (robbed, tax seizure, bankruptcy) | `VICTIM_OF_CRIME_BY(perp)` if applicable | `CRIME_VICTIM` or equivalent | −0.02 Generosity (if repeated) |

Hook points:
- Trade completion: existing `TradeHandler.handleTrade` — add producer
  calls at the successful-trade path.
- Gift action: new player verb `give_gift` invocation handler (see
  `09-player-verbs.md`).
- Commission completion: existing crafting order fulfillment path.
- Firing: existing `WorkplaceAssignmentManager` firing path.

### Social

| Event source | Memory | Mood | Trait drift |
|---|---|---|---|
| NPC compliments NPC (matching trait) | `COMPLIMENTED_BY(complimenter)` v=15 | `COMPLIMENT_RECEIVED` | — |
| NPC receives compliment (hollow / mismatch) | `COMPLIMENTED_BY(complimenter)` v=8 | small positive | — |
| NPC is insulted | `INSULTED_BY(insulter)` v=25 (50 if public) | `INSULT_RECEIVED` | — |
| NPC defended in a dispute (by player or another NPC) | `DEFENDED_BY(defender)` v=55 | +15 flat | +0.02 Compassion (rarely) |
| NPC is betrayed by someone they had ≥+40 relationship with | `VICTIM_OF_CRIME_BY(betrayer)` v=70 or matching type | `CRIME_VICTIM` heavy magnitude | −0.05 Honesty (if repeated pattern) |
| Shared hardship: both NPCs survived a famine, plague, or other village-wide crisis | `SHARED_HARDSHIP(other)` v=65 | — | — |
| Shared festival: two NPCs both attended same festival | `SHARED_FESTIVAL(other)` v=18 | `FESTIVAL_ATTENDED` | — |
| NPC taught by another NPC (skill XP via teaching) | `TAUGHT_BY(teacher)` v=30 | small positive | — |
| NPC taught someone | `TAUGHT(student)` v=20 | small positive | — |

Hook points:
- Compliment / insult verbs: handled at verb invocation.
- Defense in dispute: Phase 3 crime system fires this (Phase 1 stubs
  only if player uses a "defend" verb; full system comes Phase 3).
- Shared hardship: new event in Phase 2 famine/plague systems; Phase 1
  hooks exist as no-ops waiting for the producers.
- Shared festival: event-attendance system (Phase 5); Phase 1 stub.
- Teaching: skill-XP transfer event (new event type — fired when
  skill XP gained with a teacher NPC nearby).

### Life events (family, household)

These mostly don't produce memory entries (data lives in `FamilyComponent`)
but do fire mood and trait drift:

| Event source | Memory | Mood | Trait drift |
|---|---|---|---|
| NPC marries | — | `MARRIAGE` (once) | +0.02 Sociability |
| NPC's spouse dies | — | `FAMILY_DEATH` | +0.02 Compassion, −0.02 Sociability |
| NPC has child | — | `BIRTH_IN_FAMILY` | +0.01 Compassion |
| NPC's parent dies | — | `FAMILY_DEATH` | +0.02 Compassion |
| NPC's child dies | — | `FAMILY_DEATH` (heavier: ×1.5 magnitude) | +0.03 Compassion, −0.03 Sociability |
| NPC's child reaches adult | — | `GOAL_COMPLETED` (if goal SEE_CHILD_APPRENTICED matches) | — |

Hook points:
- Marriage: existing `CourtingGoal` success path.
- Birth: existing `ChildBirthGoal` completion.
- Death: existing NPC death path; check family role and fire for all
  related household members.

### Goal lifecycle

From the life-goal system (`07-life-goals.md`):

| Event source | Memory | Mood | Trait drift |
|---|---|---|---|
| Goal completed, importance < 7 | — | `GOAL_COMPLETED` proportional | — |
| Goal completed, importance ≥ 7 | creates a one-line history note in village history (Phase 4) | `GOAL_COMPLETED` heavy | +0.01 Ambition |
| Goal abandoned | — | small negative (−10 flat) | — |
| Goal failed (deadline or prerequisite lost) | — | `GOAL_FAILED` | −0.02 Ambition (if repeated) |

### Letters and scribal (Phase 2 but hooked from Phase 1)

Phase 1 doesn't build letters yet. But the memory system stores
`RECEIVED_LETTER`, and the event hook is reserved for Phase 2
implementation. Phase 1 producers simply don't fire this type yet.

### Crime (Phase 3)

Phase 3 wires full crime. Phase 1 reserves memory types
(`WITNESSED_CRIME_BY`, `VICTIM_OF_CRIME_BY`) for future use — they
don't fire outside attack/rescue paths in Phase 1.

### Mood ambient sources

These fire on their own schedule, not from events:

| Source | Mood | Cadence |
|---|---|---|
| Weather pleasant (clear, mild season) | `WEATHER_PLEASANT` | +2 once per day |
| Weather harsh (storm, deep winter) | `WEATHER_HARSH` | −3 once per day |
| Season beloved (NPC's preferred season, from culture or trait) | `SEASON_BELOVED` | +5 once per season |
| Rumor positive about self heard | `RUMOR_POSITIVE_ABOUT_SELF` | +6 per instance, cap per day |
| Rumor negative about self heard | `RUMOR_NEGATIVE_ABOUT_SELF` | −10 per instance, cap per day |
| Village-wide famine | `FAMINE` | −10 ongoing, capped |

Ambient mood runs in the daily tick path. Weather / season sources fire
once per day per NPC. Rumor-about-self is handled inline during
rumor-hearing in Phase 2.

## Producer API

Each subsystem exposes a thin producer API. Other code calls these
rather than reaching into the storage directly.

```javapublic final class MemoryProducer {
public static NpcMemory create(TownspersonMob npc,
MemoryType type,
List<UUID> participants,
String summary);
public static void record(TownspersonMob npc, MemoryType type,
UUID participant, String summary);
public static void refresh(TownspersonMob npc, UUID memoryId, float boost);// Internal: trait-modulated value adjustment
private static int modulateValue(MemoryType type, int baseValue,
                                 TraitVector traits);
}

```javapublic final class MoodProducer {
public static void apply(TownspersonMob npc, MoodTrigger trigger);
public static void applyWithMagnitude(TownspersonMob npc,
MoodTrigger trigger,
int overrideMagnitude);
}

```javapublic final class TraitDrifter {
public static void drift(TownspersonMob npc, TraitAxis axis, float delta);
public static void driftOnceInLifetime(TownspersonMob npc,
TraitAxis axis, float delta,
String lifetimeEventKey);
// lifetimeEventKey is stored to prevent repeated drift from same event
}

These are package-private utilities; normal callers reach them via
higher-level event-handler code in each subsystem.

## Trait-modulated memory values

When a memory is created, the target NPC's traits adjust the stored
`initialValue`:modulatedValue = baseValue * (1 + traitBonus)

Trait bonus table (examples):

| Memory type | Axis | Contribution |
|---|---|---|
| RECEIVED_GIFT | Generosity | +0.2 per unit (range ±0.2) |
| INSULTED_BY | Temperance | −0.3 per unit (temperate NPCs shrug off insults; impulsive ones magnify) |
| SAVED_BY | Compassion | +0.2 per unit |
| VICTIM_OF_CRIME_BY | Temperance | −0.2 per unit |
| DEFENDED_BY | Compassion | +0.3 per unit |
| COMPLIMENTED_BY | Sociability | +0.2 per unit |
| TRADED_WITH | Commerce (skill, not trait) | — (trait has no effect) |

Modulation happens inside `MemoryProducer.create`, so callers don't
need to know the table. Store the final modulated value as
`initialValue`; decay proceeds normally from there.

## Mood-modulated magnitudes

Already covered in `04-mood-system.md` — magnitudes modulated at
`apply` time by traits. No new logic here; just noting that both
systems independently consult traits.

## Trait drift rules

Drift is deliberately small and bounded to prevent personality
collapse over long playthroughs. Rules:

1. Per-event drift delta ≤ 0.05.
2. Total lifetime drift on any axis capped at ±0.40. Once an NPC has
   drifted ±0.40 from their starting value on an axis, further drift
   on that axis has zero effect.
3. Some drift events fire only once per NPC lifetime (e.g. "first
   survived battle" = +0.05 Courage; subsequent battles = +0.01).
   Tracked via `TraitDrifter.driftOnceInLifetime` with a unique key.
4. Drift decays slightly toward the generated value during long
   periods of calm (no drift events for 180+ days drifts 0.01/year
   back toward origin). Keeps personality stable-ish.

Per-NPC drift log (for auditing + the cap):

```javapublic class TraitDriftLog {
private final Map<TraitAxis, Float> totalDrift;  // ±0.40 max
private final Set<String> lifetimeKeysFired;public boolean canDrift(TraitAxis axis, float delta);
public void recordDrift(TraitAxis axis, float delta);
public boolean hasFired(String lifetimeKey);
public void markFired(String lifetimeKey);
// save / load to compound tag
}

Persisted on entity tag under `npcTraitDrift`. Phase 1 adds this
alongside trait vector on `TownspersonMob`.

## Event dispatch pattern

Rather than scattering producer calls across dozens of files, Phase 1
introduces a single event dispatch hub:

```javapublic final class NpcLifeEventBus {
public static void fire(NpcLifeEvent event);
}public sealed interface NpcLifeEvent {
record Trade(TownspersonMob npc, UUID partnerId, long amount) implements NpcLifeEvent {}
record GiftReceived(TownspersonMob npc, UUID giverId, Item item,
GiftAppropriateness appropriateness) implements NpcLifeEvent {}
record Insulted(TownspersonMob npc, UUID insulterId, boolean inPublic) implements NpcLifeEvent {}
record Complimented(TownspersonMob npc, UUID complimenterId, boolean matchedTrait) implements NpcLifeEvent {}
record Rescued(TownspersonMob npc, UUID rescuerId) implements NpcLifeEvent {}
record WitnessedDeath(TownspersonMob witness, UUID deceasedId, RelationshipType relation) implements NpcLifeEvent {}
record GoalCompleted(TownspersonMob npc, LifeGoal goal) implements NpcLifeEvent {}
record GoalFailed(TownspersonMob npc, LifeGoal goal) implements NpcLifeEvent {}
record Married(TownspersonMob npc, UUID spouseId) implements NpcLifeEvent {}
record FamilyDeath(TownspersonMob npc, UUID deceasedId, FamilyRole relation) implements NpcLifeEvent {}
record Hired(TownspersonMob npc, UUID employerId) implements NpcLifeEvent {}
record Fired(TownspersonMob npc, UUID employerId) implements NpcLifeEvent {}
record SharedFestival(TownspersonMob a, TownspersonMob b, UUID eventId) implements NpcLifeEvent {}
record TaughtSkill(TownspersonMob teacher, TownspersonMob student, Skill skill) implements NpcLifeEvent {}
// ... one record per event category
}

A single dispatcher fans out to the three producer systems:

```javapublic final class NpcLifeEventDispatcher {
public static void dispatch(NpcLifeEvent event) {
MemoryDispatcher.onEvent(event);
MoodDispatcher.onEvent(event);
TraitDriftDispatcher.onEvent(event);
}
}

Each dispatcher has a switch (or pattern-match) that consumes its
relevant events. Adding a new event type means:
1. Add the record to `NpcLifeEvent`.
2. Add a case in each dispatcher that should react.
3. Fire `NpcLifeEventBus.fire(new NpcLifeEvent.X(...))` from the
   code path that detects the event.

This pattern keeps producer logic centralized and testable, and makes
adding new events (e.g. when Phase 3 adds crime) a contained change.

## Integration into existing handlers

Existing code that must call `NpcLifeEventBus.fire`:

- `TradeHandler.handleTrade` — fire `Trade` on success.
- `WorkplaceAssignmentManager` — fire `Hired`, `Fired`.
- `CourtingGoal.forceCouple` — fire `Married`.
- `ChildBirthGoal` completion — fire `BirthInFamily`.
- NPC death path in `TownspersonMob.die` or equivalent — fire
  `FamilyDeath` on each family member, `WitnessedDeath` on each
  nearby NPC.
- `LivingHurtEvent` handler (new or existing combat events) — fire
  `CrimeVictim` variant events.
- Gift verb handler — fire `GiftReceived`.
- Compliment / insult verb handlers — fire `Complimented` / `Insulted`.
- Rescue detection tick (new) — fire `Rescued`.
- Event attendance end (Phase 5 stub in Phase 1) — fire
  `SharedFestival` for pairs.

Every existing file that will add a `NpcLifeEventBus.fire` call is
listed in the Phase 1 progress tracker under the relevant task.

## Ambient-source scheduler

Weather, season, and similar ambient mood sources run in a new
`NpcAmbientMoodTicker`:

```javapublic final class NpcAmbientMoodTicker {
public static void onDailyTick(ServerLevel level) {
// Once per day, for each loaded NPC:
//   apply weather-based mood trigger
//   check season transitions and apply SEASON_BELOVED
//   apply ongoing FAMINE if village is in famine state
}
}

Hooks into the existing daily tick path (same one that drives
`VillageEventScheduler`).

## Persistence additions

Phase 1 adds one new persistent field per NPC beyond Phase 0:

- `npcTraitDrift` — the `TraitDriftLog` contents.

Everything else (memory, mood, knowledge, goals) already persists from
Phase 0.

## Behavior contract

### Does

- Wire every listed event source to memory / mood / trait drift
  producers.
- Centralize event dispatch through `NpcLifeEventBus`.
- Cap trait drift at ±0.40 lifetime per axis.
- Fire once-per-lifetime events correctly via drift log keys.
- Run ambient mood ticks daily.

### Does not

- Handle Phase 2+ event sources (crime details, scribal, gossip).
  Those add to the bus when their subsystems arrive.
- Backfill memories for events that occurred before Phase 1
  implementation. Old saves have empty memory logs; memories
  accumulate from new events only.
- Replace existing event handlers — augment them with bus fires.

## Edge cases

- **Event fires but NPC is unloaded.** Bus drops the event (no
  persistence of pending events in v1). Rare for player-adjacent
  events; possible for NPC-to-NPC. Acceptable loss.
- **Rescue detection false positive** (NPC healed by potion they
  drank themselves). Rescue producer checks whether the healer is
  external; skip if self-healed.
- **Cascade from death event.** One death fires many `WitnessedDeath`
  events. Batch through dispatcher; no tick budget issue expected at
  realistic witness counts (≤20 nearby NPCs).
- **Trait drift cap reached.** `canDrift` returns false, drift is
  silently skipped. Logged at debug level.
- **Same event fired twice due to bug.** Memory system accepts
  duplicates (designed for it). Mood has daily-stack-cap. Trait drift
  uses lifetime keys to dedupe idempotently.

## Ordering dependencies

Phase 1 integration depends on:
- All Phase 0 storage docs complete and working.
- Life goals (`07-life-goals.md`) — for `GoalCompleted` / `GoalFailed`
  event handling.
- Dialogue tree (`08-dialogue-tree.md`) — doesn't block but strongly
  enables player-visible effects.
- Player verbs (`09-player-verbs.md`) — for gift / compliment / insult
  event sources.

Must land in this order within Phase 1:
1. `NpcLifeEventBus` + dispatchers (infrastructure).
2. Memory producers wired via dispatcher.
3. Mood producers wired via dispatcher.
4. Trait drift wired via dispatcher.
5. Existing handlers updated to fire events.

## Open decisions

- Should the bus be synchronous or queued? **Proposed: synchronous
  for v1 — simpler, event producers are all cheap. Reconsider if
  profiling shows issue.**
- Should ambient mood effects be suppressed for NPCs inside buildings
  (weather doesn't hit them)? **Proposed: yes for weather; no for
  season. Check `BuildingPresenceTracker` (Phase 3) once available;
  for Phase 1, apply uniformly.**
- Should `Shared Festival` produce memories between all pairs of
  attendees? For a 30-NPC festival, that's 435 memory entries. **Proposed:
  only create memories for pairs that already have relationship ≥ +20
  or were within 8 blocks for ≥ half the event. Limits entries.**

## Does-not-include

- Persistence of pending events for unloaded NPCs.
- Cross-server-tick event batching.
- Event listeners outside the three core producers (memory, mood,
  trait drift). Phase 2 may add relationship ledger as a fourth
  listener.
- Event filtering or conditional firing based on game difficulty or
  options. Uniform behavior.

## Revision Notes

(changes recorded here as the spec evolves after testing)