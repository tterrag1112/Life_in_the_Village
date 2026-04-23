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

(changes recorded here as the spec evolves after testing)