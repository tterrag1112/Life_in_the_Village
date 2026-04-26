# 14 — Hobby Activities

## Purpose

Without content in LEISURE and day-off windows, NPCs just stand
around. Hobbies fill unstructured time with observable, purposeful
behavior: fishing at the pier, reading at the library, carving at a
bench, gardening, praying at the shrine, practicing sword forms.

Hobbies serve three goals:

1. **Visual liveliness.** A village during leisure hours should look
   like people doing personal things, not idle clocks.
2. **Skill drift.** Some hobbies grant small skill XP — the NPC who
   "practices swords" as a hobby slowly raises COMBAT.
3. **Mood and trait expression.** Hobby choice signals personality.
   A high-Sociability NPC hangs out at the inn; a high-Compassion
   one volunteers at the temple.

Hobbies are NPC-selected from a catalogue weighted by traits, culture,
profession, and available infrastructure. They fire during LEISURE
phase and on day-off.

## Data model

### HobbyDefinition

Static per-hobby definition:

```java
public record HobbyDefinition(
    String id,
    String displayName,
    HobbyLocation location,            // where to perform
    List<HobbyActivity> activities,    // what to actually do once there
    int durationTicks,                 // typical duration
    TraitWeight traitWeight,           // which traits prefer this
    Optional<Skill> skillGain,         // which skill (if any) gains XP
    int skillXpPerSession,
    Optional<Profession> requiresProfession,  // some hobbies are role-gated
    Optional<UUID> requiresBuildingType,      // needs access to a building
    CultureGate cultureGate                   // which cultures support this
) { ... }

public enum HobbyLocation {
    HOME,              // indoor, at NPC's house
    TOWN_SQUARE,
    INN,
    TEMPLE_OR_SHRINE,
    LIBRARY,
    MARKET,
    FIELDS_NEARBY,
    WATER_EDGE,        // lakes, rivers, ponds
    WORKSHOP_FREE,     // an unoccupied workstation
    NATURE_TRAIL;      // short walk into wilds
}

public enum HobbyActivity {
    SIT_AND_READ,
    SIT_AND_CARVE,
    CARD_GAMES,
    DRINK_AND_TALK,
    FISH,
    GARDEN,
    TEND_FLOWERS,
    PRAY,
    MEDITATE,
    SWORD_PRACTICE,
    ARCHERY_PRACTICE,
    WALK,
    COOK,
    WRITE_LETTER,
    COPY_BOOK,
    TELL_STORY,
    SHOP_AIMLESSLY,
    VISIT_FRIEND,
    VISIT_GRAVE;
}

public record TraitWeight(Map<TraitAxis, Float> weights) {
    public float score(TraitVector traits) {
        float sum = 0;
        for (var entry : weights.entrySet()) {
            sum += traits.get(entry.getKey()) * entry.getValue();
        }
        return sum;
    }
}
```

### HobbyCatalogue

Registered at mod init:

```java
public final class HobbyCatalogue {
    public static void register(HobbyDefinition hobby);
    public static Optional<HobbyDefinition> get(String id);
    public static List<HobbyDefinition> all();
    public static List<HobbyDefinition> availableFor(TownspersonMob npc, ServerLevel level);
}
```

### NpcHobbyPreference

Per-NPC cached hobby choices:

```java
public class NpcHobbyPreference {
    private final List<String> topHobbies;      // 3-5 preferred hobby IDs
    private String currentHobby;                // active this session
    private long currentHobbyStartTick;

    public Optional<HobbyDefinition> pickForSession(TownspersonMob npc,
                                                    ServerLevel level,
                                                    long tick);
}
```

Generated at adulthood and whenever traits drift significantly.

## Starter hobby catalogue

Phase 2 ships with ~15 hobbies. Phase 5 content pass expands.

| ID | Activity | Location | Traits | Skill |
|---|---|---|---|---|
| `read_at_library` | SIT_AND_READ | LIBRARY | +Temperance, +Compassion | LITERACY |
| `carve_at_home` | SIT_AND_CARVE | HOME | +Temperance, -Sociability | CRAFTING |
| `inn_drink_talk` | DRINK_AND_TALK | INN | +Sociability | SOCIAL |
| `cards_at_inn` | CARD_GAMES | INN | +Sociability, -Temperance | - |
| `fishing` | FISH | WATER_EDGE | +Temperance, -Sociability | SURVIVAL |
| `tend_garden` | GARDEN | HOME | +Compassion, +Industry | FARMING |
| `flowers` | TEND_FLOWERS | HOME | +Compassion, -Courage | - |
| `pray_shrine` | PRAY | TEMPLE_OR_SHRINE | +Compassion | - |
| `meditate` | MEDITATE | TEMPLE_OR_SHRINE | +Temperance | - |
| `sword_practice` | SWORD_PRACTICE | TOWN_SQUARE | +Courage, +Ambition | COMBAT |
| `archery` | ARCHERY_PRACTICE | NATURE_TRAIL | +Courage | COMBAT |
| `long_walk` | WALK | NATURE_TRAIL | +Temperance | - |
| `home_cooking` | COOK | HOME | +Compassion | - |
| `write_letter` | WRITE_LETTER | HOME | +Sociability, +Literacy(skill≥30) | LITERACY |
| `visit_friend` | VISIT_FRIEND | (target NPC's home) | +Sociability, +Compassion | - |
| `shop_aimless` | SHOP_AIMLESSLY | MARKET | +Sociability | - |
| `visit_grave` | VISIT_GRAVE | (graveyard) | +Compassion | - |

Culture-specific hobbies (Phase 5):
- Highmarch: `sword_practice`, `archery` weighted higher
- Silkwood: `read_at_library`, `write_letter`, `meditate`
- Tidereach: `fishing`, `tell_story`, `cook`
- Plainfolk: generic mix

## Hobby selection

At LEISURE phase start:

1. `HobbyCatalogue.availableFor(npc, level)` filters hobbies by
   accessibility (needed building present? profession requirement
   met? culture supports?).
2. Each candidate scored:
   ```
   score = traitWeight.score(traits)
         + cultureBonus
         + recencyPenalty  // recently-used hobbies scored lower
         + skillInterestBonus  // if NPC's primary skill matches, small +
   ```
3. Softmax over top 5 candidates; pick one.
4. Assign to `NpcHobbyPreference.currentHobby` for the session.

Recency: hobbies used in the last 3 days are down-weighted by 0.5
per recent use. Produces variety across days.

## Execution: HobbyGoal

```java
public class HobbyGoal extends Goal {
    private final TownspersonMob entity;
    private Phase phase = Phase.IDLE;
    private BlockPos targetPos;
    private long startTick;

    enum Phase { IDLE, WALKING_TO_LOCATION, PERFORMING, LEAVING }

    @Override public boolean canUse() {
        DayPhase cur = ScheduleResolver.phaseAt(entity, level.getGameTime());
        return (cur == DayPhase.LEISURE || ScheduleResolver.isDayOff(entity, tick))
            && entity.getHobbyPreference().currentHobby() != null;
    }

    @Override public void start() { resolveLocation(); phase = WALKING_TO_LOCATION; }
    @Override public void tick() { /* walk, arrive, perform, finish */ }
    @Override public boolean canContinueToUse() { return phase != IDLE; }
    @Override public void stop() { /* cleanup, award skill xp */ }
}
```

Goal priority: low — below production, above pure wander. Registered
for all NPCs via `ProfessionGoalFactory`.

### Activity animation

Each `HobbyActivity` has a minimal activity handler:

- `SIT_AND_READ`: look down, hold a book item (written_book), small
  occasional head bobs.
- `FISH`: stand at shore, hold fishing rod, cast animation every
  200 ticks.
- `GARDEN`: walk between home-adjacent crop blocks, crouch at each.
- `PRAY`: kneel pose at shrine, hold head down, no movement.
- `SWORD_PRACTICE`: swing iron sword, turn, swing, repeat.
- `WALK`: scenic pathing toward a chosen point, then back.
- `VISIT_FRIEND`: navigate to target NPC's house, enter (via
  existing `WanderInBuildingGoal`), chat for a few cycles.
- `TELL_STORY`: at inn, face other patrons, occasional gesture.

Animation implementations reuse existing goal patterns (pathing,
hand-held item, pose). No new animation assets required for v1.

## Skill XP from hobbies

Per-session XP award on hobby completion:

- `read_at_library`: +2 LITERACY
- `carve_at_home`: +1 CRAFTING
- `inn_drink_talk`: +1 SOCIAL
- `fishing`: +2 SURVIVAL
- `tend_garden`: +1 FARMING
- `sword_practice`: +2 COMBAT
- `archery`: +2 COMBAT
- `meditate`: +1 TEMPERANCE trait drift chance (very small, rare)
- `write_letter`: +3 LITERACY (Literacy-gated)

Uses Phase 1's skill system and optional trait-drift hook. Rates are
low (~1-3 XP/session, sessions happen ≤2x/week). Over months, an NPC
with a skill-relevant hobby accumulates meaningful skill in their
primary-hobby skill.

## Location resolution

`HobbyLocation` resolves to a concrete `BlockPos`:

- `HOME`: NPC's house building origin
- `TOWN_SQUARE`: village square center (exists from planning)
- `INN`: inn building (if present) — tavern fallback
- `TEMPLE_OR_SHRINE`: temple building, else a shrine landmark
- `LIBRARY`: library building
- `MARKET`: market building
- `FIELDS_NEARBY`: nearest farmhouse/field
- `WATER_EDGE`: nearest water block within village bounds
- `WORKSHOP_FREE`: any unoccupied CRAFTING workstation
- `NATURE_TRAIL`: a chosen outdoor point ~40 blocks from village
  center, along a road if one exists

Resolution can fail (no library in this village); that hobby is
filtered from availability.

## Persistence

Per-NPC hobby state on entity tag under `npcHobby`:

```
npcHobby: {
    topHobbies: ["inn_drink_talk", "cards_at_inn", "long_walk"],
    currentHobby: "long_walk",
    currentHobbyStartTick: 123000L,
    recentUses: {
        "inn_drink_talk": 120000L,
        "long_walk": 115000L
    }
}
```

## Integration points

### Phase 2 integration

- `HobbyCatalogue` populated at mod init with 15 starter hobbies.
- `HobbyGoal` registered for all NPCs via `ProfessionGoalFactory`
  at low priority.
- Adulthood hook generates `NpcHobbyPreference.topHobbies`.
- Daily tick: on LEISURE start, pick session hobby.
- `setCurrentActivity` updated to display hobby label ("Fishing at
  the pier", "Reading at the library").
- `/npc hobby <uuid>` command shows preferences and current hobby.
- Profile GUI: minor field in Identity panel ("Hobbies: fishing,
  carving, long walks").

### Phase 3+ integration

- Culture-weighted hobby availability (Phase 5).
- Hobby-based rumor seeding ("the baker's kid was seen at the
  shrine every sunset this week" — hint at piety or grief).
- Player can join some hobby activities (Phase 5 verb): play cards,
  go fishing together, attend religious rite.

## Behavior contract

### Does

- Assign 3-5 preferred hobbies to each NPC at adulthood.
- Execute the chosen hobby during LEISURE and day-off phases.
- Award skill XP per session for applicable hobbies.
- Resolve locations based on village infrastructure.
- Show hobby activity as current-activity text.

### Does not

- Provide new art assets or animations; reuses existing poses/items.
- Dynamically generate hobbies. Fixed catalogue.
- Force hobby execution. If no hobby is available, NPC falls back to
  wandering.
- Allow NPC-to-NPC shared hobby sessions in v1 (except VISIT_FRIEND
  which explicitly targets another NPC). Multiplayer hobbies (cards
  with 3 NPCs at same table) are Phase 5 polish.

## Edge cases

- **No buildings present for a given hobby location.** Hobby filtered
  out; NPC picks another.
- **Target building occupied** (market crowded, workshop in use).
  NPC waits briefly, then picks another hobby or defaults to WALK.
- **NPC is in LEISURE but village is under attack.** Combat goals
  supersede hobby; hobby ends, NPC resumes after resolution.
- **Visit Friend target is asleep or unavailable.** NPC stands outside
  briefly, leaves, picks different hobby next session.
- **Hobby session interrupted by event.** Clean exit; no partial state
  stuck.

## Ordering dependencies

Phase 2 depends on:
- Weekly schedule (Phase 2, same phase) — for LEISURE phase.
- Trait vector (Phase 0) — for preference generation.
- Skill component (Phase 0) — for XP routing.
- Existing building/village infrastructure for location resolution.

## Open decisions

- Hobby activity animations — reuse existing or author new? **Proposed:
  reuse in v1; custom animations as a Phase 5 polish pass.**
- Should children have hobbies? **Proposed: yes — simplified set
  (playing with friends, running around, "helping" at workplace).
  Stubbed in v1; content in Phase 5.**
- Elderly hobby weighting — should they prefer sedentary hobbies?
  **Proposed: yes — add life-stage weight to selection (elderly +30%
  weight for SIT_AND_*, -50% for physical).**

## Does-not-include

- Player-taught hobbies. Player doesn't influence NPC hobby choice
  directly (they can gift hobby items, but that's a Phase 5 detail).
- Hobby mastery titles ("Master Angler"). Not a player goal in v1.
- Hobby-based rivalries. Competitive hobbies (contests) exist via
  the challenge verb; hobby system itself is non-competitive.
- Seasonal hobby availability (ice-fishing in winter). Phase 5 polish.

## Revision Notes

(changes recorded here as the spec evolves after testing)

### Phase 2 implementation notes

- **17 starter hobbies, not 15.** Spec table (line 127) lists 15;
  the prose elsewhere also calls out `visit_grave` and
  `tell_story` which the table omits. Catalogue ships both as
  full entries (`visit_grave` and `tell_story_inn`).
- **CultureGate dropped from `HobbyDefinition`.** Spec line 42
  reserves a `cultureGate` field for Phase 5 culture-weighted
  availability. Phase 2 doesn't ship `Culture` integration;
  to avoid an unused field that would force migration later,
  the record omits it. The scoring path
  (`NpcHobbyPreference#generate`) still has the spec's
  cultureBonus stub at zero and inline-comments where Phase 5
  plugs in.
- **HOBBY locations expanded by two enum values:**
  `FRIEND_HOUSE` (resolves to a top-friend's home, used by
  `visit_friend`) and `GRAVEYARD` (used by `visit_grave`).
  Spec line 142 spells out "(target NPC's home)" and
  "(graveyard)" as ad-hoc resolution hints; encoded as proper
  enum values so the resolver can fail cleanly when no friend
  / no graveyard exists. `GRAVEYARD` always returns empty in
  Phase 2 (no graveyard infrastructure yet) — `visit_grave`
  is therefore filtered out for v1, and the hobby will
  start firing once a graveyard building type lands.
- **NATURE_TRAIL bound at 40 blocks.** Spec line 250 calls for
  "~40 blocks". `HobbyLocationResolver.NATURE_TRAIL_DISTANCE
  = 40` matches verbatim. Direction is hashed off village
  name plus a small per-roll jitter so NPCs in the same
  village don't all walk identical lines but stable enough
  that the same NPC tends to revisit familiar trails.
  Pathing budget acceptable: vanilla mob navigation handles
  40-block targets within the loaded chunk radius. If
  realised distance proves too pricey in profiling, scope
  down here.
- **Animation reuse map (spec line 215).** `equipForActivity`
  swaps the held item per `HobbyActivity` (book / fishing rod
  / iron sword / bow / paper / etc.); `performTick` adds a
  swing every 30 ticks (sword / archery) or 200 ticks (fish)
  per spec example. SIT_AND_*, MEDITATE, PRAY, and the
  social variants are pose-only, looking at the target.
  Cards / stories / shopping / visit-friend / visit-grave
  use no held-item change — the look-pose carries them.
- **Skill XP only on PERFORMING completion.** Spec line 220
  says "per-session XP award on hobby completion". The goal
  awards XP only when the LEAVING phase is reached after a
  full performing duration; goals stopped early
  (interrupted by combat, schedule shift, or canUse failure)
  grant nothing.
- **`hobbyPreference.clearCurrent()` fires on stop.** Each
  LEISURE entry re-rolls a session hobby. Spec doesn't
  specify whether the same hobby can persist across LEISURE
  phases on the same day; clearing on stop is simpler and
  produces variety. If the spec's "(LEISURE) phase start"
  pick should be sticky, just stop calling `clearCurrent`
  and let the existing same-hobby-still-known check
  short-circuit.
- **Recent-use map self-prunes.** Entries older than
  `(RECENCY_WINDOW_DAYS + 7) * 24000` ticks are dropped at
  the next `noteUsed`, so the map can't grow unbounded over
  years of play. The exact choice (7-day grace) is mild
  insurance — the recency window itself is 3 days.
- **Pathing budget (NATURE_TRAIL).** Standard mob navigation
  with `WALK_SPEED = 0.7` reaches ~40 blocks comfortably in
  ~30 in-game seconds. Per-tick navigation cost sits well
  under the budget already paid for `SocializeGoal` /
  `ReturnHomeGoal`. A stuck path is bailed at 600 sub-tics
  (~30s) into LEAVING, so a misplaced trail point can't
  pin the goal.
