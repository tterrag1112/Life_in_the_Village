# 03 — Knowledge System

## Purpose

Information asymmetry is a core design commitment: NPCs don't know
everything. A villager in one kingdom should not have automatic
knowledge of distant affairs; a farmer cannot read the king's decree
without a literate intermediary; a rumor about a bandit camp might be
true, garbled, or a fabrication depending on its chain of transmission.

The knowledge system tracks what each NPC knows, at what fidelity, and
from what source. It's the backbone for scribes, rumors, education,
books, and any dialogue branch that conditions on "does this NPC know
X?"

## Data model

### Knowledge categories

```java
public enum KnowledgeCategory {
    PERSONAL,    // facts about specific NPCs or places via interaction
    LOCAL,       // facts about home village — everyone born there knows
    REGIONAL,    // facts about nearby villages / the kingdom
    FOREIGN;     // facts about distant kingdoms

    public static final Codec CODEC =
            Codec.STRING.xmap(KnowledgeCategory::valueOf, KnowledgeCategory::name);
}
```

### Knowledge topic

Knowledge attaches to a topic — an identifier for "what is this fact
about?" Topics are string keys with a structured format so the same
fact from different sources converges on the same topic.

Topic format: `category:subject:detail` where:

- `category` — `village`, `npc`, `event`, `road`, `resource`, `rumor`,
  `culture`, `skill_lore`, `book`, `recipe`
- `subject` — UUID or slug of the thing
- `detail` — optional subtopic

Examples:
- `village:uuid-abc:location`
- `village:uuid-abc:leader`
- `npc:uuid-def:profession`
- `npc:uuid-def:spouse`
- `event:uuid-ghi`
- `rumor:uuid-xyz`
- `book:uuid-book1:title`
- `recipe:iron_sword`

Topic strings stay under 100 chars. Knowledge system doesn't validate
topic format — producers do.

### KnowledgeEntry

```java
public record KnowledgeEntry(
    String topic,
    KnowledgeCategory category,
    float fidelity,            // 0..1; higher = more accurate
    KnowledgeSource source,
    long acquiredTick,
    String content,            // the actual fact in text form
    UUID sourceId              // optional — who/what conveyed this
) {
    public static final Codec CODEC;

    public boolean isReliable() { return fidelity >= 0.7f; }
    public boolean isVague()    { return fidelity < 0.5f; }
}

public enum KnowledgeSource {
    WITNESS,         // direct observation (fidelity 1.0)
    EDUCATION,       // formal teaching (fidelity 0.9)
    BOOK,            // read in a book (fidelity 0.95 if literate, 0 if not)
    RUMOR_HEARD,     // first-hand rumor (fidelity depends on teller)
    RUMOR_RETOLD,    // second-hand+ rumor
    LETTER,          // received in a letter (fidelity 0.9)
    BIRTH_LOCAL,     // knew from being born there (fidelity 1.0 for local facts)
    FABRICATED       // the NPC made it up (fidelity marked 0.3 but NPC treats as higher)
}
```

### NpcKnowledgeLedger

```java
public class NpcKnowledgeLedger {
    public static final int MAX_ENTRIES = 64;

    private final Map entries = new LinkedHashMap<>();

    // Queries
    public Optional get(String topic);
    public boolean knows(String topic);
    public boolean knowsReliably(String topic);       // fidelity >= 0.7
    public List byCategory(KnowledgeCategory cat);
    public List bySource(KnowledgeSource src);
    public List recentlyAcquired(long since);

    // Mutations
    public void add(KnowledgeEntry entry);           // handles overflow eviction
    public void update(KnowledgeEntry entry);        // replace with higher-fidelity version if present
    public void remove(String topic);
    public KnowledgeEntry degradeForRetelling(String topic);  // produces a rumor-retold copy

    // Persistence
    public void save(CompoundTag tag);
    public void load(CompoundTag tag);
}
```

## Fidelity rules

Initial fidelity at creation:

| Source | Fidelity |
|---|---|
| WITNESS | 1.00 |
| BIRTH_LOCAL | 1.00 for LOCAL category, 0.60 for REGIONAL, 0.20 for FOREIGN |
| EDUCATION | 0.90 |
| BOOK (literate reader, Literacy ≥ 60) | 0.95 |
| BOOK (partial literacy, 30–59) | 0.65 |
| BOOK (illiterate, <30) | 0.00 — can't read, knowledge not added |
| RUMOR_HEARD (from eyewitness) | 0.80 |
| RUMOR_HEARD (from rumor_heard source) | 0.60 |
| RUMOR_HEARD (from rumor_retold source) | 0.40 |
| LETTER | 0.90 |
| FABRICATED | 0.30 (NPC treats as higher, others see true value) |

### Degradation on retelling

When NPC A tells NPC B a fact, B's copy has lower fidelity than A's:
B.fidelity = max(0.1f, A.fidelity - 0.20f)

At each retelling, fidelity drops by 0.2. After 4 hops, fidelity is
below 0.5 (vague). After 5, it's at the 0.1 floor (basically
unreliable).

### Content mutation during retelling

Low-fidelity knowledge has mutated content. When a rumor is degraded,
simple perturbations apply with probability proportional to
(1 − fidelity):

- Number fields off by ±50%
- Names substituted with plausible alternates from the same culture
  (draws from `NpcNameRegistry`)
- Event order swapped
- Magnitude words adjusted ("dozen" ↔ "several", "huge" ↔ "big")

Mutation is deterministic given (topic, acquiredTick, mutator UUID) so
the same chain always mutates the same way — prevents save/load drift.

## Knowledge acquisition sources

### At spawn (Phase 0)

An NPC spawned in village V gets:

- All `village:V:*` topics as `BIRTH_LOCAL` with fidelity 1.0.
- All adjacent village `village:adjacent:*` name and location facts
  at fidelity 0.7 (BIRTH_LOCAL).
- Their own kingdom's top-level facts (name, ruler, capital) at
  fidelity 1.0 if adult, 0.6 if child.

Phase 0 implements this as a spawn-time population pass.

### Runtime sources (deferred to later phases)

- Witness (Phase 1+): events within sight range (~32 blocks) produce
  WITNESS knowledge for that event.
- Education (Phase 2): scholar profession actively teaches.
- Book reading (Phase 2): `onBookRead` event adds knowledge for each
  topic covered.
- Rumor hearing (Phase 2): during SOCIAL phase, NPCs exchange knowledge
  with some probability per topic per conversation.
- Letter delivery (Phase 2): letter content produces knowledge entries.

## Query semantics for NPC behavior

When a system (dialogue, goal, etc.) asks "does this NPC know X?":

- `knows(topic)` — returns true if any entry exists, regardless of
  fidelity. Good for "will they mention it?"
- `knowsReliably(topic)` — returns true only if fidelity ≥ 0.7. Good
  for "is the information accurate enough to act on?"

Dialogue systems should prefer `knowsReliably` for factual statements
and use `knows` for rumors ("I heard something about…").

## Handling "don't know" responses

When an NPC is asked about an unknown topic, their trait vector
determines the response style (implemented in Phase 1 dialogue):

- High Honesty (> +0.4) → "I don't know"
- Low Honesty (< −0.4) + high Sociability (> +0.4) → fabricate. Adds
  `FABRICATED` entry at fidelity 0.3. The NPC treats it as known; other
  NPCs receiving the rumor see real fidelity.
- High Compassion (> +0.4) → suggests another NPC who might know
  ("try asking the innkeeper")
- Default → "Couldn't tell you"

Phase 0 stores the ledger; the response logic lives in dialogue (Phase
1). But the `FABRICATED` source is in the Phase 0 enum so producers
can use it later without schema changes.

## Persistence

NBT structure on entity tag, rooted at `npcKnowledge`:
npcKnowledge: {
entries: [
{
topic: "village:uuid-abc:leader",
category: "REGIONAL",
fidelity: 0.8f,
source: "RUMOR_HEARD",
acquiredTick: 123456L,
content: "Leader is Marta the Stern",
sourceId: "uuid-of-teller"
},
...
]
}

Maximum serialized size: 64 × ~180 bytes = ~11.5KB uncompressed. This
is the largest per-NPC store.

Eviction on overflow: remove the entry with the lowest fidelity first;
among equal fidelity, oldest `acquiredTick`. Pinned fidelity-1.0
entries (usually LOCAL) are protected from eviction unless all 64
entries are fidelity 1.0 (astronomically rare; fall back to oldest).

## Integration points

### Phase 0 integration

- `TownspersonMob` gets `private final NpcKnowledgeLedger knowledge`
  field.
- `addAdditionalSaveData` / `readAdditionalSaveData` persist it.
- Spawn-time population pass in `TownspersonMob.finalizeSpawn` (or
  equivalent) populates LOCAL and adjacent REGIONAL facts.
- `/npc knowledge <uuid>` command prints the full ledger.
- `NpcProfileSnapshot` does **not** display knowledge in Phase 0 —
  there's no good UI for it yet. Debug command only.

### Phase 2+ consumers

- Rumor propagation (Phase 2)
- Dialogue predicates: `knows(topic)`, `knowsReliably(topic)`
- Letter content extraction: letters add knowledge entries on receipt
- Scribe/scholar profession behaviors

## Behavior contract

### Does

- Store up to 64 knowledge entries per NPC, persisted across save/load.
- Populate LOCAL and adjacent REGIONAL facts at spawn.
- Evict lowest-fidelity entry on overflow.
- Expose queries for existence and reliability of knowledge.
- Support content mutation during retelling deterministically.

### Does not

- Drive behavior directly in Phase 0 — storage and spawn population only.
- Update knowledge dynamically as the world changes. If a village
  leader changes, existing knowledge entries don't auto-update; they
  become stale until refreshed.
- Block actions based on knowledge ("can't trade because you don't know
  them"). NPCs can still interact with unknown others.
- Support search-by-content. Queries are by topic string.

## Edge cases

- **Two entries for the same topic from different sources.** `add()`
  keeps the higher-fidelity one. If equal, keeps the more recent one.
- **Fabricated knowledge treated as known by the NPC.** The NPC's own
  queries see fidelity as stored (0.3). But in dialogue, the NPC
  speaks with confidence — the fidelity is a liar's-tell, visible to
  other NPCs via rumor transmission but not to the speaker's own
  behavior. This is a deliberate asymmetry.
- **Topic string too long.** Truncate to 100 chars and log a warning.
- **Load encounters corrupt entry.** Log warning, skip, continue.

## Ordering dependencies

Phase 0 scope depends on: `Village` / `Kingdom` data being accessible
at NPC spawn (already true). No new subsystem dependencies.

Phase 2+ consumers depend on ledger being stable.

## Open decisions

- Should child NPCs have reduced knowledge at spawn compared to adults?
  Spec currently says yes (fidelity 0.6 on kingdom facts for children).
  May need tuning — children might know *less* or different things than
  listed. Revisit after testing.
- Should the knowledge ledger be visible in the NPC profile GUI at
  all, or debug-command-only? Argument for visible: players want to
  see what NPCs know. Argument against: clutter, not narratively
  interesting. **Proposed default: not visible. Add later if testing
  demands it.**

## Does-not-include

- Player knowledge ledger. The player learns things by in-world signals
  and their own notes. Not tracked mechanically.
- Knowledge "forgetting" (active decay). Entries persist at their
  acquired fidelity until evicted.
- Cross-language knowledge gating. Language families are a Phase 5
  culture feature; knowledge works uniformly across languages in v1.

## Revision Notes

### 2026-04-23 — Phase 0 implementation (task 03)

Implementation landed in `tterrag1112.life_in_the_village.Npc.Knowledge`
(`KnowledgeCategory`, `KnowledgeSource`, `KnowledgeEntry`,
`NpcKnowledgeLedger`, `RumorMutator`). Parallel structure to
`Npc.Traits` and `Npc.Memory` from tasks 01–02.

**Mutation seed formula — LOCKED.** The spec says mutation is
"deterministic given (topic, acquiredTick, mutator UUID)" but does
not prescribe a combination method. Phase 0 locks this as:

```
seed = splitmix64((long) topic.hashCode())
     ^ splitmix64(acquiredTick)
     ^ splitmix64(mutatorUuid.getMostSignificantBits())
     ^ splitmix64(mutatorUuid.getLeastSignificantBits())
```

All four inputs are JDK-spec-stable (`String.hashCode`, `UUID.*Bits`,
primitive longs). The seed feeds `RandomSource.create(seed)`. The
`splitmix64` finaliser is a well-known mixer (Steele/Lea/Flood) and
is reproduced inline in `RumorMutator` with the canonical constants
`0xff51afd7ed558ccdL` and `0xc4ceb9fe1a85ec53L`. **Changing this
formula will re-roll every existing rumor chain** — Phase 2 gossip
will depend on it being stable, so any revisit must come with a save
migration.

**Persistence API.** Same deviation as tasks 01–02: spec says
`save(CompoundTag)`/`load(CompoundTag)`; NeoForge 1.21 drives entity
save/load through `ValueOutput`/`ValueInput`. Implementation uses the
entity API and a single `output.store("npcKnowledge", CODEC, this)`
call, preserving the spec's nested `{ entries: [...] }` shape.

**Record shape: no `entryId`.** The spec keys entries by `topic` (the
ledger is a `Map<String, KnowledgeEntry>`) — one entry per topic with
the upgrade rule deciding which wins. The prompt template mentioned
an `entryId: UUID` field (carryover from the memory record shape);
trusted the spec and omitted it, so all lookups / removes go by
topic string. All callers in spec-described Phase 2+ consumer code
are by topic so there is no API loss.

**`sensitive` flag.** Persisted now per the prompt instruction even
though no consumer reads it. Default `false`, `Codec.BOOL.optionalFieldOf`
with default so pre-flag saves load cleanly.

**Query set.** Shipped the spec's named queries (`get`, `knows`,
`knowsReliably`, `byCategory`, `bySource`, `recentlyAcquired`) plus
`all()` for debug surfaces. The prompt mentioned `findByTopic(String)`;
that is just a synonym for `get`, so only `get` is exposed to avoid
a second name for the same thing.

**`update` vs `add`.** `add` handles the full upgrade + eviction
path. `update` is the narrower spec-described method: replace the
topic's entry only if it already exists AND the new fidelity is
strictly higher. `update` never creates new entries, never evicts,
never fires on equal fidelity. Callers pick based on whether they
want "learn it if you didn't already" (add) vs. "strengthen what
they already have" (update).

**Eviction order.** Spec rule: non-pinned first, lowest fidelity,
oldest on tie; fall back to oldest overall if every entry is
fidelity 1.0. Implemented in `NpcKnowledgeLedger.evictOne()` via
`min(Comparator.comparingDouble(fidelity).thenComparingLong(acquiredTick))`
over the non-pinned subset with a fallback to the full set.

**No spawn-time population.** The spec's "Phase 0 integration"
section calls for seeding every new NPC with `village:V:*` BIRTH_LOCAL
facts, adjacent-village name/location facts at 0.7, and kingdom
top-level facts at 1.0/0.6 by life stage. The task prompt's exit
criteria says "new NPC shows empty ledger" and its DO NOT includes
"any knowledge producers" — the ledger therefore ships empty,
mirroring how `NpcMemoryLog` ships empty with no producers in Phase
0. If the spawn-time pass is wanted for Phase 1 it should pull from
`Village` / `Kingdom` data at `finalizeSpawn`-time; no architectural
blocker.

**Daily tick: none.** Knowledge does not decay with time per the
spec's "Does not" section. No `TickSubsystem` was registered for
knowledge — fidelity only drops on retelling (Phase 2 concern).

**Debug command.** `/npc knowledge <uuid>` prints entries grouped by
category, each group sorted by fidelity descending, with topic,
fidelity tag, source, age, and content snippet (sensitive entries
tagged). `/npc knowledge add <uuid> <topic> <category> <fidelity>
<source> <content…>` takes a greedy-string content and constructs
via `KnowledgeEntry.create(...)` (which clamps fidelity and
truncates topic to 100 chars). `/npc knowledge mutate <text>
<mutatorUuid>` is a determinism test harness — stable defaults for
`topic` and `acquiredTick` (`"debug"` / `0L`) so the output depends
only on `(text, mutatorUuid)` for easy repeat-run verification. The
command also prints the derived 64-bit seed so callers can verify
determinism across runs.

**Prompt↔spec naming mismatches (for the prompt-template maintainer):**
- Prompt's `KnowledgeSource` enum set (DIRECT_WITNESS, RUMOR_HEARD,
  BOOK, LETTER, TAUGHT, FABRICATED) differs from the spec's
  (WITNESS, EDUCATION, BOOK, RUMOR_HEARD, RUMOR_RETOLD, LETTER,
  BIRTH_LOCAL, FABRICATED). Used spec.
- Prompt proposes `entryId (UUID)` as an entry field; spec's record
  has no such field (topic is the key). Used spec.
- Prompt proposes `findByTopic(String)` query; the spec's `get(topic)`
  already does this. Used spec.
- Prompt's mutation-seed formula ("content.hashCode() +
  mutatorUuid.hashCode() + topic.hashCode()") differs from the
  spec's "(topic, acquiredTick, mutator UUID)". Used spec's inputs;
  combined via splitmix64-XOR (see above).
- Prompt's mutation ops include "subject swap" (not in spec) and
  "addition (low-Honesty teller)" (not in spec). Subject swap is
  ambiguous without more context and was omitted; addition was
  implemented as `appendFlavor` since the user was explicit.
- Prompt's exit criterion ("empty ledger on spawn") overrides the
  spec's Phase 0 spawn-population section — documented above.