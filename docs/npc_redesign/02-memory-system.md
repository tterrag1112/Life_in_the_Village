# 02 — Memory System

## Purpose

Each NPC remembers situational events involving the player or other
NPCs. Memories are value-weighted — important events persist for years,
trivial ones fade within weeks. Memories surface in dialogue, affect
mood and relationship scores, and are refreshed by reminders.

This system does **not** duplicate data already persisted elsewhere.
Marriages, household assignments, employment, and family relationships
live on their existing components. Memory is for events that would
otherwise vanish: a trade, a gift, a rescue, a witnessed crime, an
insult, a kindness, a shared festival moment, a letter received.

## Data model

### Memory types

```java
public enum MemoryType {
    // Player ↔ NPC events
    TRADED_WITH,
    RECEIVED_GIFT,
    GAVE_GIFT,
    SAVED_BY,              // someone saved this NPC
    SAVED,                 // this NPC saved someone
    INSULTED_BY,
    COMPLIMENTED_BY,
    WITNESSED_CRIME_BY,    // saw this person commit a crime
    VICTIM_OF_CRIME_BY,    // was stolen from / attacked by
    DEFENDED_BY,           // someone took their side in a dispute
    RECEIVED_LETTER,
    COMMISSIONED_WORK,     // asked to craft/gather something
    FAVOR_DONE_FOR,        // did a favor
    FAVOR_RECEIVED_FROM,
    PROMISED_BY,           // was promised something (tracks for breach)

    // Multi-participant events
    SHARED_FESTIVAL,       // attended same event
    SHARED_HARDSHIP,       // survived famine/raid together
    WITNESSED_DEATH_OF,    // saw someone die (kin, friend, rival)
    TAUGHT_BY,             // learned a skill from
    TAUGHT,                // taught a skill to
    OFFICIATED_BY,         // priest/scribe did a rite for them
    OFFICIATED_FOR;        // performed a rite for

    public static final Codec CODEC =
            Codec.STRING.xmap(MemoryType::valueOf, MemoryType::name);
}
```

Types are chosen so each is *situational* — none duplicates existing
family/employment/household persistence. List is complete for v1; Phase
1+ may add types as new event sources come online.

### Memory record

```java
public record NpcMemory(
    UUID memoryId,                    // stable ID for refresh lookups
    MemoryType type,
    List participantIds,        // typically 1 (player/NPC), sometimes more
    long tick,                        // when it happened
    int initialValue,                 // 1..100
    float currentValue,               // decays from initialValue
    boolean pinned,                   // true if initialValue >= 90
    String summary                    // short text for dialogue reference
) {
    public static final Codec CODEC; // standard record codec

    public boolean evictable() {
        return !pinned && currentValue < 5f;
    }

    public boolean isPositive() {
        return type.polarity() >= 0;
    }
}
```

`MemoryType.polarity()` returns +1 for positive memories (gift, rescue,
compliment), −1 for negative (insult, theft, witnessed crime), 0 for
neutral (trade, shared festival). Used for mood/relationship routing.

### NpcMemoryLog

```java
public class NpcMemoryLog {
    public static final int MAX_ENTRIES = 32;

    private final List entries = new ArrayList<>();

    // Queries
    public List all();
    public List involving(UUID participantId);
    public List ofType(MemoryType type);
    public Optional findById(UUID memoryId);
    public boolean hasMemoryOf(MemoryType type, UUID participantId);

    // Mutations
    public void add(NpcMemory memory);       // handles eviction on overflow
    public void remove(UUID memoryId);
    public void refresh(UUID memoryId, float boost);  // applied at reminder
    public void decayAll(float daysElapsed);          // daily tick

    // Persistence
    public void save(CompoundTag tag);
    public void load(CompoundTag tag);
}
```

## Initial values by memory type

Value determines lifespan. Higher = longer-lasting. Ranges given where
context modifies the value.

| Type | Value |
|---|---|
| TRADED_WITH | 8 (15 if significant haggling / large amount) |
| RECEIVED_GIFT | 15–35 (by appropriateness; favorite gift = 35, generic = 15) |
| GAVE_GIFT | 10 |
| SAVED_BY | 95 (auto-pinned) |
| SAVED | 85 (auto-pinned) |
| INSULTED_BY | 25 (50 if in public) |
| COMPLIMENTED_BY | 15 |
| WITNESSED_CRIME_BY | 30–70 (by severity) |
| VICTIM_OF_CRIME_BY | 60–95 (by severity; murder of kin = 100 pinned) |
| DEFENDED_BY | 55 |
| RECEIVED_LETTER | 12 per letter (stacks up to 40) |
| COMMISSIONED_WORK | 20 |
| FAVOR_DONE_FOR | 25 |
| FAVOR_RECEIVED_FROM | 30 |
| PROMISED_BY | 35 (decays to 90 quickly if unfulfilled, to 10 if kept) |
| SHARED_FESTIVAL | 18 |
| SHARED_HARDSHIP | 65 |
| WITNESSED_DEATH_OF | 80 (of kin = 100 pinned) |
| TAUGHT_BY | 30 |
| TAUGHT | 20 |
| OFFICIATED_BY | 25 |
| OFFICIATED_FOR | 15 |

Values at creation may be adjusted by trait (a Compassionate NPC
receiving help gets higher FAVOR_RECEIVED_FROM value; a Temperate NPC
rates insults lower). Trait adjustment happens at producer time, not
storage time; see Phase 1 producer doc.

## Decay formula

Applied once per in-game day to every memory:
decayPerDay = 0.02 * (100 - currentValue) / 100
currentValue -= decayPerDay

This is a floor-seeking decay — memories at high `currentValue` barely
move, memories at low `currentValue` fade quickly. A memory with
`currentValue = 90` loses ~0.002/day; at `currentValue = 20`, it loses
~0.016/day.

Practical lifespans with no reminders:

| Initial value | Half-life | Evictable (< 5) after |
|---|---|---|
| 10 | ~35 days | ~70 days |
| 25 | ~100 days | ~180 days |
| 50 | ~280 days | ~500 days |
| 75 | ~650 days | never decays that low without help |
| 90 | pinned — no eviction |
| 95+ | pinned |

Pinned memories are excluded from eviction but still decay cosmetically
(slow drift in `currentValue`). The `pinned` flag is set at creation
when `initialValue >= 90`.

## Refresh on reminder

When something reminds the NPC of a memory, the memory is refreshed:
refreshBoost = 10 + 0.2 * (initialValue - currentValue)
currentValue = min(initialValue, currentValue + refreshBoost)

A fully-faded memory bounces back closer to its initial value. Can
never exceed `initialValue`. Sources of reminder in v1:

- Dialogue that references the memory
- A letter that mentions it
- Meeting the participant in person (weak reminder, +3 flat instead of
  formula)
- Attending a festival that triggered the memory type

Consumer subsystems (dialogue, letters, etc.) call
`NpcMemoryLog.refresh(memoryId, boost)`.

## Eviction on overflow

On adding entry 33:

1. Find the entry with the lowest `currentValue` among non-pinned.
2. Remove it.
3. Add the new entry.

If all 32 entries are pinned (very rare — NPC would have to have
experienced 32 profound events), the new memory is stored with a
flag that marks it as "displaced" and will evict the next time an
existing entry drops below the pinning threshold. In practice this
edge case is astronomically unlikely and can be treated as "drop the
new memory" in v1.

## Persistence

NBT structure on entity tag, rooted at `npcMemory`:
npcMemory: {
entries: [
{
id: "uuid-string",
type: "TRADED_WITH",
participants: ["uuid-string", ...],
tick: 123456L,
initialValue: 15,
currentValue: 12.4f,
pinned: false,
summary: "Traded 3 bread for 2 wheat"
},
...
]
}

Maximum serialized size per NPC: ~32 × 200 bytes = ~6KB uncompressed.

## Integration points

### Phase 0 integration (storage only)

- `TownspersonMob` gets `private final NpcMemoryLog memory` field.
- `addAdditionalSaveData` / `readAdditionalSaveData` persist it.
- `NpcProfileSnapshot` gains a list of displayed memories (top 5 by
  `currentValue`) for the profile GUI. Each displayed memory has:
  type label, participant name(s), summary, relative age ("weeks ago"),
  current intensity.
- A new "Memories" panel in the profile GUI (sibling to Family / Work /
  Reputation panels). Renders the top 5.
- `/npc memory <uuid>` command prints the full log for debug.

Phase 0 does **not** add producers. The log will be empty in-world
until Phase 1. This is deliberate — storage must be solid before we
write to it from many code paths.

### Phase 1 producers (deferred)

See `02-memory-system.md` Phase 1 section when expanded. Quick list:
trade handler, gift action, crime-witness handler, rescue detector,
letter delivery, festival attendance, skill-teaching event.

### Downstream consumers (deferred)

- Dialogue predicates: "has positive memory of player", "has been
  wronged by X", etc. (Phase 1)
- Mood transitions based on significant new memories (Phase 1)
- NPC relationship score adjustment (Phase 2)
- Rumor content sourced from recent memories (Phase 2)

## Behavior contract

### Does

- Store up to 32 memory entries per NPC, persisted across save/load.
- Decay entries daily based on the floor-seeking formula.
- Evict lowest-value non-pinned entry on overflow.
- Auto-pin memories with `initialValue >= 90`.
- Refresh on reminder calls via stable `memoryId`.
- Expose queries for other subsystems (by participant, by type).

### Does not

- Produce memories automatically in Phase 0 — storage only.
- Surface memories in dialogue in Phase 0 — profile GUI only.
- Track memories between the player and player-owned NPCs. Player
  workers have their own data path.
- Store emotions or judgments — just event facts. Emotional response
  is computed elsewhere from trait + mood + memory polarity.
- Compress, archive, or roll up old memories. Eviction is final.

## Edge cases

- **NPC dies.** All memories with this NPC as `participantId` in
  *other* NPCs' logs become stale but are not retroactively edited.
  They decay normally. Queries that resolve participant names fall
  back to a stored `summary` string.
- **Memory with multiple participants.** `involving(uuid)` matches if
  any participant matches. Stored once, queryable by any participant.
- **Duplicate memory types within a short window.** v1 allows duplicates
  — if the player trades with an NPC 5 times in a day, 5 TRADED_WITH
  entries accumulate (though they evict each other quickly at value 8).
  A smarter merge (stacking, value accretion) is a future improvement.
- **Adding a memory at a `tick` before `tick` of an existing entry.**
  Allowed; entries are not stored in time order.
- **Load encounters corrupt entry.** Log warning, skip entry, continue
  loading others. Never fail the entire load over one bad memory.
- **`participantIds` empty.** Allowed for memories that are about a
  place or event rather than a person. Participants list is a hint for
  queries, not a requirement.

## Ordering dependencies

This doc's Phase 0 scope depends on: nothing new (uses existing
`TownspersonMob` component pattern and `NpcProfileSnapshot` infra).

Phase 1 producers depend on: the memory log being stable (Phase 0
complete), and on the mood system (`04-mood-system.md`) being stable
for routing polarity to mood changes.

## Open decisions

- Should the profile GUI "Memories" panel be visible to the player by
  default, or gated by high relationship with the NPC? Argument for
  gating: makes high relationships feel more rewarding. Argument
  against: players want to see game state at all times. **Proposed
  default: always visible, but show only positive-polarity memories
  until relationship reaches +10; negative memories always visible.**
  Revisit after testing.

## Does-not-include

- Cross-NPC memory sharing (see Phase 2 rumor system).
- Compressed long-term memories ("you've been friends for years" as a
  single entry). Not needed for v1.
- Memory search in-game UI. The profile panel shows top 5; power users
  use the debug command.
- Analytics on memory patterns. Not a player-facing feature.

## Revision Notes

(changes recorded here as the spec evolves after testing)