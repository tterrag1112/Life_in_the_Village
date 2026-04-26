# 11 — NPC↔NPC Relationship Ledger

## Purpose

The existing `NpcRelationshipComponent` tracks player→NPC deltas only.
For villages to feel alive, NPCs need real relationships *with each
other* — friendships, rivalries, grudges, feuds. These drive courting
choices, hiring preferences, apprenticeship matching, gossip targets,
office election voting, and dozens of smaller social dynamics.

Relationships are bounded (15 active per NPC). They grow through
proximity, shared events, trades, and gifts. They decay slowly without
interaction. Negative relationships aren't just low scores — rivalry,
grudge, and feud are distinct relationship *modes* with their own
behavior hooks.

## Data model

### RelationshipMode

```java
public enum RelationshipMode {
    NEUTRAL,        // default; no special behavior
    ACQUAINTANCE,   // score 10..39; mutual recognition
    FRIEND,         // score 40..74; social preference
    CLOSE_FRIEND,   // score 75..100; household-adjacent trust
    RIVAL,          // score -39..-10; competitive, not hostile
    GRUDGE,         // score -74..-40; avoid, gossip negatively
    FEUD;           // score -75..-100; open hostility, trigger for events

    public static RelationshipMode fromScore(int score) {
        if (score >= 75)  return CLOSE_FRIEND;
        if (score >= 40)  return FRIEND;
        if (score >= 10)  return ACQUAINTANCE;
        if (score > -10)  return NEUTRAL;
        if (score > -40)  return RIVAL;
        if (score > -75)  return GRUDGE;
        return FEUD;
    }

    public boolean isPositive() { return this == ACQUAINTANCE || this == FRIEND || this == CLOSE_FRIEND; }
    public boolean isNegative() { return this == RIVAL || this == GRUDGE || this == FEUD; }
}
```

### NpcRelationship

```java
public record NpcRelationship(
    UUID otherId,
    int score,                    // -100..+100
    long firstMetTick,
    long lastInteractionTick,
    int interactionCount,
    RelationshipOrigin origin     // how they first met
) {
    public static final Codec<NpcRelationship> CODEC;

    public RelationshipMode mode() { return RelationshipMode.fromScore(score); }

    public NpcRelationship withScore(int newScore) { ... }
    public NpcRelationship withInteraction(long tick) { ... }
}

public enum RelationshipOrigin {
    BORN_SAME_HOUSEHOLD,    // family — seeded at +40 automatically
    BORN_SAME_VILLAGE,      // grew up together — seeded at +15
    WORKPLACE_COLLEAGUE,    // work at same building — seeded at +5
    MET_SOCIALLY,           // default for adult-era meetings
    MET_THROUGH_TRADE,      // commercial origin
    MET_IN_CONFLICT,        // first interaction was negative (crime, attack)
    INTRODUCED_BY_PLAYER;   // player facilitated introduction
}
```

### NpcRelationshipLedger

```java
public class NpcRelationshipLedger {
    public static final int MAX_ENTRIES = 15;

    private final Map<UUID, NpcRelationship> entries = new LinkedHashMap<>();

    // Queries
    public Optional<NpcRelationship> get(UUID otherId);
    public int getScore(UUID otherId);                    // 0 if absent
    public RelationshipMode getMode(UUID otherId);        // NEUTRAL if absent
    public List<NpcRelationship> friendsAndCloser();
    public List<NpcRelationship> rivalsAndWorse();
    public List<NpcRelationship> all();

    // Mutations
    public void adjust(UUID otherId, int delta, long tick, RelationshipOrigin origin);
    public void set(UUID otherId, NpcRelationship rel);
    public void remove(UUID otherId);
    public void decayAll(float daysElapsed);

    // Persistence
    public void save(CompoundTag tag);
    public void load(CompoundTag tag);

    // Eviction strategy: on overflow, evict the entry with smallest |score|
    // (closest to neutral); ties broken by oldest lastInteractionTick.
}
```

## Growth sources

Relationships grow through specific triggering events. Most hooks are
fired from the `NpcLifeEventBus` introduced in Phase 1 — the ledger
becomes a fourth listener alongside memory/mood/trait-drift producers.

### Proximity growth during SOCIAL phase

Once per in-game day, `NpcRelationshipTicker` scans each loaded NPC
during their SOCIAL phase. For every other NPC within 6 blocks for at
least half the SOCIAL phase duration, add +1 to each other's score in
the other's ledger. Caps at +20 from this source alone (acquaintance
ceiling). Beyond +20, proximity no longer adds score — requires active
gifting, trade, or shared events.

Rationale: repeated proximity builds familiarity but doesn't create
deep friendship by itself.

### Shared events and trades

Per `NpcLifeEvent` (from Phase 1 integration):

| Event | Delta to target |
|---|---|
| Trade (successful, nonzero value) | +2 |
| GiftReceived (favorite) | +15 |
| GiftReceived (normal) | +8 |
| GiftReceived (off-base) | +3 |
| GiftReceived (insulting) | -8 |
| Complimented (matching) | +5 |
| Complimented (hollow) | +1 |
| Insulted (private) | -12 |
| Insulted (public) | -20 |
| Rescued | +40 (auto-promote to FRIEND minimum) |
| SharedFestival | +3 per event (capped +15/season) |
| SharedHardship | +20 |
| WitnessedDeath (of rival of this NPC) | small positive; rival's death is bittersweet not clean |
| WitnessedDeath (of friend of this NPC) | +10 to all present (shared grief) |
| TaughtSkill (teacher-student) | +8 for student, +5 for teacher |
| DefendedBy | +25 |
| PlayerFacilitated introduction | +5 starting relationship, origin INTRODUCED_BY_PLAYER |

Some relationship deltas also trigger memory creation — Phase 1's
MemoryProducer is the source of truth; the ledger just mirrors the
relational state.

### Seeded relationships

At NPC spawn / life events:

- **Born into household**: +40 to each existing household member,
  origin `BORN_SAME_HOUSEHOLD`.
- **Born in village, reaches adulthood**: +15 to ~5 randomly-selected
  peers of similar age, origin `BORN_SAME_VILLAGE`.
- **Assigned to workplace**: +5 to existing workers at the same
  building, origin `WORKPLACE_COLLEAGUE`.
- **Married**: +60 between spouses on marriage (on top of existing
  courtship score).

## Decay

Called once per in-game day via `NpcRelationshipLedger.decayAll`:

```
For each relationship:
    daysSinceInteraction = (now - lastInteractionTick) / 24000
    if daysSinceInteraction > 7:
        // Decay toward neutral at 0.1/day
        decayDelta = 0.1 * min(1.0, (daysSinceInteraction - 7) / 30.0)
        if score > 0: score = max(0, score - decayDelta)
        if score < 0: score = min(0, score + decayDelta)
```

Practical effect: relationships fade to neutral over ~12 months
without interaction, but regular contact (at least once a week)
holds them steady. Friends and rivals who see each other during
SOCIAL phase barely decay.

Close friends (score ≥ 75) decay at half rate — deep bonds are
sticky.

## Rivalries and grudges

Negative relationships aren't passive opposites of friendships. They
have their own behavior.

### Rivalry (score -39..-10)

- Competitive, not hostile. Prefer to outdo rather than harm.
- If two NPCs are rivals and both in a goal space (both pursuing
  WIN_OFFICE, both ambitious merchants), the loser's goal-failure
  mood trigger is amplified by the rival's success (−5 extra).
- Rivals may accept `challenge` verb contests readily.
- No explicit hostility in dialogue.

### Grudge (score -74..-40)

- Active avoidance. During SOCIAL phase, grudged NPCs choose paths
  that move away from each other if within 8 blocks.
- Gossip system (see `12-gossip-rumor.md`) biases negative: grudged
  NPC passes on unflattering knowledge about the target preferentially.
- Dialogue tone is curt; will refuse trade if mood is TROUBLED or worse.
- Grudges accumulate memory anchors: at least one
  `VICTIM_OF_CRIME_BY` / `INSULTED_BY` memory is expected.

### Feud (score -75..-100)

- Open hostility. Will verbally accost the target if both are in
  the same area during a public event.
- Refuses all trade, commission, and social verbs.
- Feuds generate events for the justice system (Phase 3): public
  altercation triggers a minor crime report.
- Very rare. Requires sustained negative interactions or a major
  trigger (witnessed murder of kin).

## Inherited grudges (family feuds)

A limited form of feud inheritance: if an NPC has a household relative
(parent, sibling) with a feud at score ≤ -85 against another NPC, the
related NPC inherits a starting relationship of -30 toward that target
upon adulthood. Single generation only — grandchildren don't inherit
automatically.

## Breakups

Positive relationships can reverse. Triggers that cause rapid score
drops:

- Discovered infidelity (Phase 3+): −60 delta.
- Betrayal memory created (e.g. `VICTIM_OF_CRIME_BY` from a FRIEND):
  −40 delta.
- Repeated lying (Honesty-driven, via dialogue): gradual −10 per
  detected lie.

A score drop that crosses the FRIEND→NEUTRAL or FRIEND→RIVAL boundary
fires a "breakup" moment — mood hit on both sides, memory entry,
possible gossip. These are narrative moments; Phase 1 memory producers
watch for these transitions.

## Behavior integration

### Courtship (Phase 2 CourtingGoal update)

`CourtingGoal.findCourtingTarget` currently picks the nearest eligible
NPC. Updated logic:

1. Filter to `canBeCourted` NPCs (existing check).
2. Weight by current relationship score: candidates with score ≥ +40
   are preferred 10×, score 10..39 preferred 3×, score ≥ 0 preferred
   1×, score < 0 excluded.
3. Pick via weighted random from top 5 candidates.

Result: NPCs marry people they already like, producing narrative
consistency.

### Hiring (Phase 2 JobPosting update)

When multiple applicants exist for a `JobPosting`, the employer NPC
weights by their own relationship score toward each applicant (friends
get preference, rivals rejected unless no alternative).

### Apprenticeship (Phase 2 apprenticeship)

Masters select apprentices partly by relationship — a master's friend's
child has an advantage. See `16-apprenticeship.md`.

### Price adjustment (Phase 3 economy)

Trades between NPCs with positive relationships apply a small discount
(−3% at FRIEND, −5% at CLOSE_FRIEND). Negative relationships add
surcharge (+5% at RIVAL, +8% at GRUDGE, refuse at FEUD).

### Office election (Phase 3 elective offices)

Voting NPCs weight candidates heavily by personal relationship. An
elected office can be dominated by whoever has the widest positive
relationship network, which creates political drama and campaign
incentive (campaigning means building relationships).

### Gossip target selection (Phase 2 gossip)

NPCs are more likely to share knowledge with their friends (positive
context) and to share unflattering knowledge about their rivals.

## Persistence

NBT structure on entity tag, rooted at `npcRelationships`:

```
npcRelationships: {
    entries: [
        {
            otherId: "uuid-string",
            score: 32,
            firstMetTick: 100000L,
            lastInteractionTick: 123000L,
            interactionCount: 14,
            origin: "MET_SOCIALLY"
        },
        ...
    ]
}
```

Up to 15 entries × ~80 bytes = ~1.2KB per NPC.

## Integration points

### Phase 2 integration

- `TownspersonMob` gets `private final NpcRelationshipLedger relationships` field.
- `addAdditionalSaveData` / `readAdditionalSaveData` persist.
- `NpcRelationshipTicker` runs during daily tick for proximity growth
  and decay.
- `NpcLifeEventBus` dispatcher fans events to ledger via a new
  `RelationshipDispatcher`.
- Seeding hooks in: child-birth goal, spawn-in-village path,
  workplace-assignment path, marriage path.
- `NpcProfileSnapshot` gains a list of top 5 relationships (by
  |score|) with other-NPC name, mode, and score. Visible when viewing
  NPC profile.
- New "Relationships" panel in NPC profile GUI.
- `/npc relationships <uuid>` command prints full ledger.

### Phase 3+ integration

- Price adjustment in trade handler.
- Office voting.
- Feud-as-crime in justice system.

## Behavior contract

### Does

- Store up to 15 relationships per NPC, persisted across save/load.
- Grow relationships through events via the life-event bus.
- Proximity growth during SOCIAL phase, capped at acquaintance.
- Seed starting relationships at life events.
- Decay idle relationships toward neutral.
- Expose queries for other subsystems.

### Does not

- Replace the existing player→NPC relationship tracking
  (`NpcRelationshipComponent`). That system persists for player-NPC
  relationships; this is NPC-NPC only.
- Auto-generate relationship events — requires the life-event bus.
- Track relationship history or breakup events; those live in the
  memory system.
- Support family-tree-wide inheritance beyond the single-generation
  rule.

## Edge cases

- **Target NPC dies.** Relationship entry persists briefly; queries
  for the target return the stored state. Eviction happens normally
  when ledger fills. Memories about the deceased persist.
- **Overflow with 15 meaningful relationships.** Oldest-neutral-
  tiebreaker evicts. Pinned relationships (spouse, household) are not
  special in v1 — they decay like any other. In practice they stay
  high from frequent interaction.
- **Mutual vs one-sided score.** Scores are stored per-direction —
  A's ledger can hold different score for B than B's ledger holds
  for A. Most events fire for both NPCs symmetrically, but asymmetry
  is possible and deliberate (one-sided crush, etc.).
- **Seeding duplicate.** If a seeding event fires for a pair that
  already has an entry, the seed is ignored (existing relationship
  is more accurate than defaults).

## Ordering dependencies

Phase 2 depends on:
- `NpcLifeEventBus` (Phase 1) — the event source.
- Memory system (Phase 1) — for memory-driven relationship shifts.
- Trait vector (Phase 0) — for future trait-based weighting.
- Existing family/household/workplace code for seeding hooks.

## Open decisions

- Should proximity growth require the other NPC to also be in SOCIAL
  phase, or does one-way work? **Proposed: require both to be in
  SOCIAL or similar non-work phase.** A farmer working while his
  neighbor walks by doesn't build relationship meaningfully.
- Grudge inheritance — one generation only, or propagates further?
  **Proposed: one generation, document for later expansion.**
- Should player's active verb usage (compliment, insult) affect
  nearby NPCs' relationship to the player's target? E.g. if player
  insults an NPC the witness likes, that drops witness→player
  relationship. **Proposed: yes, small effect (-3 for witnessed
  insult of their FRIEND or better); part of Phase 2 player-NPC
  reputation extension.**

## Does-not-include

- Relationship history log (use memory system for that).
- Multi-directional coalitions / cliques. Emergent from pairwise
  scores; no group-level data.
- Player-visible relationship graph UI. Profile panel only.
- Relationship effects on combat targeting. NPCs don't attack friends
  regardless of score drop to feud; they verbally harass, refuse trade,
  but don't murder. Combat escalation is Phase 3 crime system.

## Revision Notes

### 2026-04-23 — Phase 2 implementation (task 11)

Implementation landed in
`tterrag1112.life_in_the_village.Npc.Relations`:
`RelationshipMode`, `RelationshipOrigin`, `NpcRelationship`,
`NpcRelationshipLedger`, `RelationshipDispatcher`,
`RelationshipSeeder`. The bus event surface gained
`NpcLifeEvent.RelationshipBoundaryCrossed` (memory + mood
producers extended to react). The daily-decay tick subsystem
extended with relationship decay + a SOCIAL-phase proximity sweep.

**Locked decisions:**

- **Field name on TownspersonMob: `npcRelationships`** (accessor
  `getNpcRelationships()`). Spec line 317 calls for
  `relationships`, but the existing Phase 0
  `NpcRelationshipComponent` (player→NPC) already owns the
  `relationships` / `getRelationships()` slot. Renaming the
  legacy field would touch every existing call site (NpcDialogue,
  reputation events, dialogue's `RelationshipAtLeast` predicate,
  etc.). The new field keeps the legacy intact and reads cleanly
  on its own.

- **Symmetric pair-bumps.** The dispatcher writes the same delta
  to both A→B and B→A ledgers when it can resolve the other side
  to a loaded TownspersonMob. Player UUIDs always bypass the NPC
  ledger — the player→NPC path stays on
  `NpcRelationshipComponent`. Asymmetric one-sided cases (crush,
  unrequited grudge) come from explicit one-sided callers via
  `RelationshipDispatcher.applyOne` (the `/npc relationships
  adjust` debug path uses this) and from per-event rules where
  the spec calls for asymmetric magnitudes (none in the v1
  table — every entry is symmetric).

- **`RelationshipBoundaryCrossed` event.** Added as a 24th
  `NpcLifeEvent` record. Fired from
  `RelationshipDispatcher.applyOneAndFireBoundary` whenever an
  adjust crosses a mode bucket, AND from the daily-decay path
  when decay slides a score across a boundary. Memory +
  mood producers listen for the "breakup" case
  (positive→non-positive); other dispatchers (TraitDrift, Lifegoal
  selector / progress, schedule generator) declare an explicit
  no-op case so the sealed switch stays exhaustive.

- **Proximity sweep — simplified vs spec.** Spec line 117 wants
  per-pair accumulation: NPCs within 6 blocks for at least half
  the SOCIAL-phase duration. A faithful implementation needs
  sub-second sampling + per-pair tick counters (a transient
  Map<UUID, Map<UUID, Integer>> keyed off pair-of-UUIDs and
  reset at end-of-day). Phase 2 ships a simpler approximation:
  once per in-game day, walk loaded NPCs; for each pair both
  currently in SOCIAL within 6 blocks, +1 each direction.
  Produces the right qualitative behaviour (chronic neighbours
  become acquaintances) at meaningfully lower cost. Phase 5
  polish can replace with accumulation if testing demands it.

- **Proximity ceiling.** Spec line 119 says proximity caps at
  +20 from this source. Implementation interpretation: when the
  current score is already ≥ +20, proximity-source increments
  are silently dropped via
  `NpcRelationshipLedger.adjustFromProximity`. This is "score
  must be < 20 to receive proximity bumps" rather than tracking
  per-source contribution per relationship — accepted simplification,
  documented.

- **Decay formula.** Direct port of spec line 169:
  `daysSinceInteraction > 7` triggers a ramp-up over 30 days to
  0.1/day. Close friends (|score| ≥ 75) decay at half rate.
  Decay is rounded — tiny per-day ramps may round to 0, so
  decay can plateau briefly between ramp days; matches spec's
  ~12-month-to-neutral ballpark.

- **Boundary detection on adjust.** When the dispatcher applies
  a delta, `applyOneAndFireBoundary` snapshots the prior mode
  before calling `ledger.adjust`, then compares against the new
  mode. If they differ, fires `RelationshipBoundaryCrossed`. New
  entries (no prior) are treated as crossing from `NEUTRAL`.

- **Inherited grudges.** Single-generation only per spec line
  227. Triggered at adulthood inside `RelationshipSeeder` when
  any household relative has a relationship at score ≤ -85.
  Skips entries the heir already has (existing entry wins per
  spec line 372).

- **Marriage seed.** Symmetric +60 between spouses fires from
  `RelationshipDispatcher`'s `Married` case (the spec table at
  line 165 also lists this; redirecting through the dispatcher
  keeps the boundary-crossing event firing too). Stacks on top
  of any prior courtship score.

- **Snapshot/UI.** Top 5 by |score| populated into
  `NpcProfileSnapshot.topRelationshipIds / topRelationshipScores
  / topRelationshipModes`. Three parallel arrays kept the
  StreamCodec read/write trivially symmetric. The Profile GUI
  panel that renders these arrays is deferred to a follow-up
  session along with the goals/mood UI panels — the wire data
  is ready.

**Spec↔prompt naming notes:**
- Spec: `decayAll(float daysElapsed)`. Implementation:
  `decayAll(float daysElapsed, long currentTick)` because
  decay-rate calculation needs the current tick to compute
  `daysSinceInteraction`. Backward-compat-equivalent — caller
  passes 1f and the current tick from the daily sweep.
- Spec: `adjust(otherId, delta, tick, origin)`. Implementation
  adds `adjustFromProximity(otherId, delta, tick)` as a
  ceiling-aware variant the proximity ticker calls. Origin is
  always `MET_SOCIALLY` for proximity entries.
- Spec line 144 (witness-of-friend's-death + group fan-out) is
  not implemented in this session — it requires scanning all
  nearby NPCs at the moment of death to compute "shared grief".
  Documented as a Phase 2 follow-up; trivial to add inside the
  existing `LivingDeathEvent` handler.

**Deferred to follow-up:**
- Profile GUI "Relationships" panel rendering (data shipped on
  the snapshot; a UI session adds the panel).
- Workplace seeding hook from existing assignment paths (the
  `RelationshipSeeder.seedWorkplaceColleagues` static helper is
  exposed; assignment paths haven't been touched yet —
  documented for Phase 2 task 16 / a workplace-pass).
- Relationship-aware modifications in courtship, hiring,
  apprenticeship, price adjustment, office voting (each is a
  separate subsystem; the ledger query surface is in place).

**Not implemented in this session per prompt's DO NOT list:**
gossip biasing (next session, doc 12), price adjustments (Phase
3), office voting (Phase 3), crime/justice integration (Phase 3).