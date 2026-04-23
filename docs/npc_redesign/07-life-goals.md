# 07 — Life Goals

## Purpose

Every NPC has 1–3 active life goals — concrete aspirations they're
working toward. Goals drive behavior in ways passive scheduling can't:
an NPC saving for a dowry takes risky caravan work; one who wants to
become guildmaster studies and campaigns; one who wants to see their
son apprenticed lobbies nearby masters.

Goals are the answer to "why does this NPC do what they do?" beyond
basic needs. Without them, NPCs are clocks. With them, NPCs are people
with stories in progress.

Goals surface to the player through dialogue, gossip, and observable
behavior. The player can choose to help, hinder, or ignore them. This
inverts the typical questgiver relationship — instead of the NPC
handing the player a task, the player discovers what NPCs care about
and decides whether to intervene.

## Data model

### LifeGoalType

```javapublic enum LifeGoalType {
// Wealth and property
SAVE_AMOUNT,                 // accumulate N bronze
BUY_HOUSE,                   // purchase a specific building
BUY_BUSINESS,                // purchase a specific business
PAY_OFF_DEBT,                // clear a named debt// Career
REACH_SKILL_LEVEL,           // get a specific skill to N
REACH_PROFESSION_TIER,       // NpcProfessionXp tier target
WIN_OFFICE,                  // hold a specific office
FOUND_BUSINESS,              // start a new company
MASTERPIECE,                 // complete a signature commission// Family
MARRY_TARGET,                // specific NPC
MARRY_ANY,                   // marry someone courtable
HAVE_CHILD,                  // produce offspring
SEE_CHILD_APPRENTICED,       // child gets master
SEE_CHILD_MARRIED,           // child marries
ARRANGE_CHILD_MATCH,         // arrange betrothal for child// Social
BEFRIEND_TARGET,             // reach +60 relationship with named NPC
BEFRIEND_COUNT,              // reach +40 with N NPCs
AVENGE_WRONG,                // resolve a grudge via crime/trial
RESTORE_FAMILY_HONOR,        // clear family of a recorded disgrace// Spiritual / cultural
PILGRIMAGE,                  // visit a specific shrine/temple
AUTHOR_BOOK,                 // write and publish a book
TEACH_APPRENTICE,            // fully train an apprentice to journeyman// Location
MOVE_TO_VILLAGE,             // settle in a specific village
VISIT_VILLAGES,              // see N villages before death// Survival / dark
OUTLIVE_RIVAL,               // survive longer than a named NPC
DIE_WITH_HONOR;              // elderly ambition — major deed before deathpublic static final Codec<LifeGoalType> CODEC =
        Codec.STRING.xmap(LifeGoalType::valueOf, LifeGoalType::name);
}

This list is the v1 palette. Not every NPC pursues every type; goal
selection (below) weights types by trait and circumstance. New types
can be added in later phases without schema breakage.

### LifeGoal record

```javapublic record LifeGoal(
UUID goalId,
LifeGoalType type,
long startTick,
long targetTick,                  // 0 if no deadline
String targetParam,               // type-specific: "iron_sword", UUID of target NPC, etc.
int targetCount,                  // for count-based goals (SAVE_AMOUNT, BEFRIEND_COUNT)
int progressCount,                // current progress toward target
GoalStatus status,
int importance,                   // 1..10; affects pursuit intensity
String narrative                  // one-line why-this-matters text for dialogue
) {
public static final Codec<LifeGoal> CODEC;public float progressFraction() {
    if (targetCount <= 0) return status == COMPLETED ? 1f : 0f;
    return Math.min(1f, (float) progressCount / targetCount);
}public boolean isExpired(long now) {
    return targetTick > 0 && now > targetTick;
}
}public enum GoalStatus {
ACTIVE,       // pursuing
COMPLETED,    // achieved, retained for history
ABANDONED,    // gave up (trait drift / life event)
FAILED;       // deadline passed or prerequisite lost
// Terminal statuses stay in the log briefly before eviction
}

### LifeGoalSet

Per-NPC container:

```javapublic class LifeGoalSet {
public static final int MAX_ACTIVE = 3;
public static final int MAX_HISTORY = 10;   // completed/abandoned kept brieflyprivate final List<LifeGoal> active;
private final List<LifeGoal> history;// Queries
public List<LifeGoal> active();
public List<LifeGoal> history();
public Optional<LifeGoal> primaryGoal();     // highest importance among active
public boolean has(LifeGoalType type);
public Optional<LifeGoal> getById(UUID goalId);// Mutations
public void add(LifeGoal goal);              // rejects if MAX_ACTIVE reached
public void replace(UUID goalId, LifeGoal updated);
public void updateProgress(UUID goalId, int delta);
public void complete(UUID goalId, long tick);
public void abandon(UUID goalId, long tick);
public void fail(UUID goalId, long tick);
public void evictStaleHistory(long tick);    // older than 90 days// Persistence
public void save(CompoundTag tag);
public void load(CompoundTag tag);
}

## Goal selection

### When goals are selected

- At NPC adulthood (teen → adult life-stage transition) — initial goal
  assignment.
- When an active goal completes, fails, or is abandoned, and
  `active.size() < MAX_ACTIVE`.
- Periodically (every 30 in-game days) if NPC has fewer than 2 active
  goals and circumstances suggest a new one is warranted (trait roll,
  life event, etc.).

### Selection algorithm

1. Compute a candidate set: all `LifeGoalType` values filtered by
   circumstance eligibility (e.g. can't `SEE_CHILD_APPRENTICED` with
   no children; can't `MARRY_TARGET` with no candidate + no courtship
   pool).

2. Score each candidate by:
   - **Trait alignment**: each goal type has a preferred trait vector
     (e.g. `FOUND_BUSINESS` prefers high Ambition + Industry;
     `BEFRIEND_COUNT` prefers high Sociability). Score = dot product
     of goal vector and NPC traits.
   - **Culture pull**: culture weights certain goal types higher
     (scholarly culture raises `AUTHOR_BOOK` and `REACH_SKILL_LEVEL
     (literacy)`). Added to score.
   - **Life circumstance**: age, profession, household state, wealth,
     recent life events add contextual weight.
   - **Avoid duplication**: goal types already in active set get -infinity.

3. Select 1–3 goals from the weighted top via weighted random draw.
   `MAX_ACTIVE` is capped at 3; typically 1–2 at adulthood, with more
   added over life.

4. Fill in `targetParam`, `targetCount`, `importance`, `narrative`
   from templates (Phase 5 content pass writes the full template set;
   Phase 1 ships with stubs for each goal type).

### Trait-goal alignment (examples)SAVE_AMOUNT:           +Industry, +Temperance, -Generosity
FOUND_BUSINESS:        +Ambition, +Industry, +Commerce (skill bonus)
WIN_OFFICE:            +Ambition, +Sociability, +Social (skill bonus)
MARRY_TARGET:          +Sociability, +Compassion
BEFRIEND_COUNT:        +Sociability, +Compassion
AUTHOR_BOOK:           +Literacy (skill bonus), neutral traits
AVENGE_WRONG:          +Courage, -Temperance, -Compassion
PILGRIMAGE:            culture-gated; neutral traits
OUTLIVE_RIVAL:         -Compassion, -Temperance
DIE_WITH_HONOR:        +Courage, +Ambition (elderly stage only)

### Importance

`importance` (1–10) determines pursuit intensity. High-importance goals
cause the NPC to take risks, neglect other activities, or become more
visible in dialogue. Sources:

- Trait: high Ambition + high alignment with the goal's traits → +2
- Life event: spouse died (drives REVENGE/HONOR goals to 8+)
- Deadline proximity: goals with short `targetTick` auto-raise importance

Most goals sit at importance 3–5. A 9–10 goal dominates the NPC's
behavior.

## Progress tracking

Goals advance via event hooks from other subsystems. Phase 1
implementation wires each goal type to its trigger.

| Type | Progress event | Delta |
|---|---|---|
| SAVE_AMOUNT | Wallet balance polled daily | set to current balance |
| BUY_HOUSE | Property purchase event | target reached or not (0/1) |
| REACH_SKILL_LEVEL | Skill XP gain | polled on add |
| WIN_OFFICE | Office holding change | set to 1 if held |
| MARRY_TARGET | Marriage event | 1 on wedding |
| HAVE_CHILD | Child birth event | +1 per child up to target |
| BEFRIEND_TARGET | Relationship score cross +60 | 0/1 |
| BEFRIEND_COUNT | Relationships cross +40 | sum across NPCs |
| AUTHOR_BOOK | Book publish event | +1 per book |
| TEACH_APPRENTICE | Apprentice reaches journeyman | +1 |
| PILGRIMAGE | Visit event (arrive at shrine) | +1 |
| VISIT_VILLAGES | Visit event (enter new village) | +1 per distinct |
| OUTLIVE_RIVAL | Rival death event | 0/1 |

Progress updates call `LifeGoalSet.updateProgress(goalId, delta)`.
When `progressCount >= targetCount`, the system fires
`onGoalCompleted` (mood boost, memory entry, dialogue unlock).

## Goal behavior integration

Goals influence existing goals/behavior with soft biases, not hard
overrides:

- **Schedule**: a `WIN_OFFICE` NPC with high importance chooses
  campaigning activities during SOCIAL phase (seek out potential
  supporters). `PILGRIMAGE` NPC has a scheduled shrine-visit insertion.
- **Trade**: `SAVE_AMOUNT` NPC is less generous (prices slightly higher);
  accepts risky caravan contracts.
- **Profession**: `FOUND_BUSINESS` NPC actively accumulates capital
  and looks for opportunities; will resign from current role when
  capital ready.
- **Dialogue**: goal's `narrative` field surfaces in dialogue when
  player asks "what are you up to?" (new player verb in
  `09-player-verbs.md`).

Phase 1 wires the minimum set: dialogue surfacing, mood reactions on
completion/failure. Deeper behavioral integration comes per subsystem
as that subsystem is built (scheduling Phase 2, trade Phase 3, etc.).

## Player interaction with goals

The player can:

- **Learn about goals**: via the "Ask about their life" player verb
  (see `09-player-verbs.md`). Response depends on relationship and
  Honesty trait.
- **Help**: e.g. gift coins toward `SAVE_AMOUNT`, craft the item for
  `MASTERPIECE`, introduce the NPC to the BEFRIEND target. Each
  provides a recognized helper action that generates a positive
  memory and relationship delta for the NPC.
- **Hinder**: sabotage, deceive, or block goals. Generates very
  negative memory and can start a grudge.
- **Complete on their behalf**: for certain goals (PILGRIMAGE delivery
  of a relic, MASTERPIECE ingredient gathering), player can substitute.

Player-helps-goal events fire `mood.apply(RESCUED, ...)` equivalent
magnitudes (configured per goal type) and create a high-value memory
(RECEIVED_GIFT or FAVOR_RECEIVED_FROM with appropriateness multiplier).

## Abandonment and failure

A goal is abandoned when:
- Prerequisite lost (e.g. `MARRY_TARGET` target married someone else,
  or died; `BUY_HOUSE` house was destroyed or purchased by another).
- NPC trait drift moves values far from goal alignment (e.g. Ambition
  drops to −0.6 → high-ambition goal abandons).
- Player or event actively blocked the goal.

A goal fails when deadline passes (`targetTick < now`). Failure applies
`mood.apply(GOAL_FAILED, ...)` and triggers a possible trait drift
(Ambition −0.02 for repeated failure).

Completion, abandonment, and failure all move the goal to `history`
list, which keeps the last 10 for dialogue reference. Older entries
evict.

## Narrative text

Each goal stores a one-line `narrative` describing the "why." This
surfaces in dialogue. Examples (templates):

- SAVE_AMOUNT: "I need {amount} to {reason}." Reason picked from
  context: for a dowry, for a house, to flee my father's debts, to
  buy my freedom, for retirement, to buy my apprentice their tools.
- BUY_HOUSE: "I want the house on {street} — grew up near it."
- WIN_OFFICE: "I can do better than the current {office_name}."
- MARRY_TARGET: "{target_name}. There's no one else."
- PILGRIMAGE: "The {shrine} at {location} — once before I die."

Phase 5 content pass writes the full template set with trait-specific
flavor. Phase 1 ships with basic strings.

## Persistence

NBT structure on entity tag, rooted at `npcGoals`:npcGoals: {
active: [
{
goalId: "uuid-string",
type: "SAVE_AMOUNT",
startTick: 100000L,
targetTick: 0L,
targetParam: "",
targetCount: 500,
progressCount: 217,
status: "ACTIVE",
importance: 5,
narrative: "I need 500 bronze to move my aunt to Oakford."
}
],
history: [ ... ]
}

## Integration points

### Phase 1 integration

- `TownspersonMob` gets `private final LifeGoalSet goals` field.
- `addAdditionalSaveData` / `readAdditionalSaveData` persist.
- Goal selection runs at teen→adult life-stage transition (hook in
  existing age-up code path).
- Daily tick path runs progress updates for goals that poll (wealth,
  skill level) and fires deadline checks.
- Event hooks wire into existing systems: marriage, birth, purchase,
  office change, etc. Each existing event handler gets a
  `LifeGoalProgress.onX(...)` call.
- `NpcProfileSnapshot` gains a list of active goals (up to 3) with
  type, narrative, progress fraction, importance.
- New GUI panel "Aspirations" in the NPC profile, shown when
  relationship ≥ +20 or always for the player's own NPCs.
- `/npc goals <uuid>` command — list active and history.
- `/npc goals set <uuid> <type> <...>` — force-set for testing.

### Phase 3+ integration

- Office goals resolve via office framework events.
- `Found business` hooks into NPC-company system (Phase 4).
- Crime/justice integration for `AVENGE_WRONG`.

## Behavior contract

### Does

- Store up to 3 active goals per NPC.
- Select goals at adulthood and on vacancy.
- Track progress via event hooks.
- Surface goals to the player via dialogue and profile.
- Handle abandonment and failure.

### Does not

- Drive behavior globally — goals bias behavior, they don't override.
- Allow more than 3 active goals at once.
- Support player-authored goals in v1. Player has their own goal
  system (future work).
- Handle goals that span multi-NPC coordination (shared goals,
  family goals). Each NPC has independent goals.

## Edge cases

- **NPC has no eligible goals at adulthood.** Rare (palette is wide).
  Assign a default `SAVE_AMOUNT` with low importance.
- **Goal completes on same tick as NPC dies.** Goal records
  completion in history; NPC dies with a recent success. Dialogue
  history preserves for memorial.
- **Player repeatedly helps with same goal.** Each help creates a
  memory; memory system's daily-stack-cap prevents abuse.
- **Goal's target NPC dies.** Goal auto-abandons. NPC's mood drops
  via `GOAL_FAILED` trigger.
- **Conflicting goals selected simultaneously.** `AVENGE_WRONG` and
  `BEFRIEND_TARGET` on same target — should not happen (eligibility
  check excludes this), but if detected, lower-importance goal
  abandons.

## Ordering dependencies

Phase 1 depends on:
- Trait vector (Phase 0) — for alignment scoring.
- Mood state (Phase 0) — for GOAL_COMPLETED / GOAL_FAILED triggers.
- Skill component (Phase 0) — for skill-based goal progress.
- Memory system (Phase 0 storage + Phase 1 producers) — for goal
  completion memory entries.

Doesn't depend on dialogue tree directly but surfaces best once
dialogue tree exists.

## Open decisions

- Should goal completion be broadcast in any form (gossip, history)?
  A notable goal (became guildmaster, published a book) feels like
  village-history material. **Proposed: major goals (importance ≥ 7
  on completion) produce a gossip entry and a kingdom-history log
  event.**
- Should children have "proto-goals"? (Apprenticeship desire, future
  profession preference.) **Proposed: no formal child goals in v1;
  their preferences are expressed in dialogue only.**
- Can two goals target the same NPC? (`BEFRIEND_TARGET` and
  `MARRY_TARGET` both on Anna.) **Proposed: allow — they're natural
  progression. BEFRIEND completion often precedes MARRY.**

## Does-not-include

- Player-authored goals. Out of scope for v1.
- Goal sharing between NPCs (couple pursuing same target). Independent.
- Auto-generated quest-giver goals. Goals are intrinsic, not
  player-facing quests (though player can intervene).
- Goal difficulty scaling based on player level. Goals are NPC-centric.

## Revision Notes

(changes recorded here as the spec evolves after testing)