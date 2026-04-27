# 30 — Village History

## Purpose

Village-scale narrative exists only in fleeting memories of individual
NPCs today. When an NPC dies, their memories die with them. The
village itself has no long-term recollection of its own past.

Village history is a persistent, village-scoped log of notable events
— births, deaths, office changes, famous visitors, plagues, founded
companies, completed masterpieces, trials, laws enacted, marriages.
Scribes can record the log into physical ledger books. Players can
browse village history as a chronicle. NPCs can reference village
history in dialogue ("our grandparents remembered the great plague of
year 17").

Village history is also the foundation for kingdom-level history and
for named-NPC emergent legends (the apprentice who became a famous
scholar is remembered by name for generations).

## Data model

### HistoryEntry

```java
public record HistoryEntry(
    UUID entryId,
    HistoryEventType type,
    long tick,                     // when it happened
    long recordedTick,             // when it was written
    UUID villageId,
    List<UUID> relatedNpcIds,      // principals (victim, accused, spouses, etc.)
    List<UUID> relatedBuildingIds,
    Map<String, String> details,   // free-form structured data
    String renderedSummary,        // cached human-readable summary
    HistoryImportance importance
) {
    public static final Codec<HistoryEntry> CODEC;
}

public enum HistoryEventType {
    // Life events
    BIRTH,
    DEATH_NATURAL,
    DEATH_ACCIDENT,
    DEATH_VIOLENT,
    MARRIAGE,
    COMING_OF_AGE,
    APPRENTICESHIP_STARTED,
    APPRENTICESHIP_COMPLETED,

    // Office and politics
    LEADER_ELECTED,
    LEADER_DEPOSED,
    LEADER_DIED_IN_OFFICE,
    OFFICE_APPOINTED,
    OFFICE_VACATED,
    LAW_ENACTED,
    LAW_REPEALED,

    // Crime and justice
    CRIME_REPORTED,
    TRIAL_HELD,
    EXILE_PASSED,
    EXECUTION_CARRIED_OUT,

    // Economy and infrastructure
    BUILDING_CONSTRUCTED,
    BUILDING_DESTROYED,
    COMPANY_FOUNDED,
    COMPANY_DISSOLVED,
    MASTERPIECE_CERTIFIED,
    BOOK_AUTHORED,

    // Religion
    TEMPLE_CONSECRATED,
    HIGH_PRIEST_ANOINTED,
    FEAST_DAY_CELEBRATED,

    // Village-wide
    FESTIVAL_HELD,
    PLAGUE_OUTBREAK,
    PLAGUE_RESOLVED,
    FAMINE,
    SEASON_FIRST_HARVEST,
    VILLAGE_FOUNDED,

    // Visitors and diplomacy
    FAMOUS_VISITOR,
    ENVOY_RECEIVED,
    TREATY_SIGNED,
    CARAVAN_LOST,

    // Kingdom-linked
    KINGDOM_EVENT;                // reference to kingdom-level event

    public HistoryImportance defaultImportance();
}

public enum HistoryImportance {
    MINOR,           // routine: standard births, minor trials
    NOTABLE,         // memorable: office changes, masterpieces
    MAJOR,           // consequential: plague, famine, leader death
    LEGENDARY;       // generational: village founding, major disaster
}
```

### VillageHistoryLog

Per-village persistent log:

```java
public class VillageHistoryLog {
    private final UUID villageId;
    private final List<HistoryEntry> entries;           // chronological
    private final Map<HistoryEventType, Integer> typeCount;
    private final Map<UUID, List<UUID>> entriesByNpc;   // npcId -> entry IDs

    public void add(HistoryEntry entry);
    public List<HistoryEntry> recentEntries(int n);
    public List<HistoryEntry> byType(HistoryEventType type);
    public List<HistoryEntry> byNpc(UUID npcId);
    public List<HistoryEntry> inRange(long startTick, long endTick);
    public List<HistoryEntry> byImportance(HistoryImportance min);

    // Eviction: keep all LEGENDARY and MAJOR; evict older MINOR after
    // village age > 365 days if exceeding cap.
    public void evictIfOverCap();

    // Codec, save / load
}
```

Stored on `VillageSavedData` (one log per village).

## Capacity and eviction

Logs grow continuously. Eviction policy:

- Always retain: LEGENDARY, MAJOR.
- Retain NOTABLE: last 2 in-game years.
- Retain MINOR: last 1 in-game year, or last 200 entries, whichever
  fewer.
- Total cap: 1000 entries per village. Excess trimmed oldest-MINOR
  first.

Pre-eviction, minor entries can still be referenced by other systems
for recent queries. Post-eviction, NPCs refer only to preserved
entries.

## Entry creation

### Automatic producers

Each relevant subsystem fires history events at appropriate moments:

| Subsystem | Event types fired |
|---|---|
| Life-stage transitions | BIRTH, DEATH_*, COMING_OF_AGE, MARRIAGE |
| Office framework | LEADER_*, OFFICE_* |
| Village laws | LAW_ENACTED, LAW_REPEALED |
| Crime/justice | CRIME_REPORTED (MAJOR+), TRIAL_HELD, EXILE_PASSED, EXECUTION_CARRIED_OUT |
| Construction | BUILDING_CONSTRUCTED, BUILDING_DESTROYED |
| NPC companies | COMPANY_FOUNDED, COMPANY_DISSOLVED |
| Apprenticeship | APPRENTICESHIP_*, MASTERPIECE_CERTIFIED |
| Scribal | BOOK_AUTHORED (for prestigious authors) |
| Religion | TEMPLE_CONSECRATED, HIGH_PRIEST_ANOINTED, FEAST_DAY_CELEBRATED |
| Events | FESTIVAL_HELD, PLAGUE_*, FAMINE, SEASON_FIRST_HARVEST |
| Visitor flux | FAMOUS_VISITOR (by notability threshold), ENVOY_RECEIVED |

Each producer constructs a `HistoryEntry` with appropriate
`renderedSummary` via a summary-template library (keeping translation
simple).

### Summary rendering

Templates:

```
LEADER_ELECTED: "{npc_name} became leader of {village_name}"
MASTERPIECE_CERTIFIED: "{apprentice_name} certified their masterpiece — a {item_type} — under Master {master_name}"
PLAGUE_RESOLVED: "The plague of {village_name} ended after {duration_days} days. {death_count} perished."
LAW_ENACTED: "{leader_name} enacted the law of {law_name}."
BOOK_AUTHORED: "Scholar {npc_name} finished '{book_title}'."
FAMOUS_VISITOR: "The renowned {visitor_role} {visitor_name} visited {village_name}."
```

Templates live in a `HistorySummaryTemplates` registry; cultures and
languages can override in Phase 5.

## Scribal archive

A village_scribe office holder (from Phase 3) periodically writes a
ledger book recording recent history. See `17-scribal-professions.md`
for the scribal side.

Process:

1. Every 30 in-game days, village_scribe checks for unwritten entries.
2. Selects all NOTABLE+ entries since last ledger entry.
3. Spends authorship time (week-scale) producing a ledger book.
4. Ledger book cataloged in village library (if present).
5. Readers of ledger book gain knowledge entries about village events
   (REGIONAL category).

Ledger books can be lost, copied, traded — physical artifacts of
history. Reading an ancestor-era ledger is the player-facing way to
learn deep village lore.

## Kingdom history

Village-history entries marked with kingdom-affecting flags propagate
up:

- Village founding, dissolution
- Leadership changes in kingdom-member villages
- Major crimes involving cross-village actors
- Plague outbreaks
- Treaties / envoy events
- Legendary individuals (prestigious authors, master craftsmen)

Kingdom-level `KingdomHistoryData` collects these; scholars at
kingdom-level (Phase 4 stub, full Phase 5) author kingdom chronicles
that read as multi-village compendia.

## Player and NPC queries

### Player queries

- **Village chronicle UI** (new screen at town hall or library): browse
  history entries filtered by type/importance/year.
- Read ledger books (standard book reading; history entries rendered
  page-by-page).
- Dialogue: ask NPCs "tell me about this village's history". NPCs
  respond with highlights from their era's knowledge.

### NPC queries

- Dialogue tree predicates:
  - `HasWitnessedHistoryEvent(type)` — was NPC alive when this
    happened?
  - `VillageHadPlague(yearsAgo)` — weather reference to past events.
  - `KnewPerson(name)` — mentions deceased notables.
- Rumor system references old entries ("my grandfather said...").
- Children's schooling lessons reference village history events when
  LITERACY skill taught.

## Legendary NPCs

Some NPCs, via accomplishments, reach legendary status:

- Prestigious authors (scholar prestige ≥ 40).
- Master craftsmen with multiple certified masterpieces.
- Long-serving leaders (held office ≥ 10 in-game years).
- Rescuers from plague, famine, or disaster.
- Founders (first leader or key foundational figure).

Legendary NPCs have dedicated history entries pinned at LEGENDARY or
MAJOR importance. Their names persist in history even after their
memory from living villagers has faded.

Post-death, legendary NPCs:
- Have named graves at graveyard (see hobby `visit_grave`).
- Occasionally invoked by dialogue ("in the days of old Master
  Elric...").
- May have memorial buildings or plaques (Phase 5 stretch).

## Persistence

`VillageHistoryLog` persists via codec on `VillageSavedData`:

```
villageHistory: {
    entries: [
        {
            entryId: "uuid",
            type: "LEADER_ELECTED",
            tick: 100000L,
            recordedTick: 100100L,
            villageId: "uuid",
            relatedNpcIds: ["uuid"],
            relatedBuildingIds: [],
            details: {
                "leader_name": "Elara",
                "predecessor_name": "Varik"
            },
            renderedSummary: "Elara became leader of Ashford",
            importance: "NOTABLE"
        }
    ]
}
```

Expected size: ~500 bytes per entry; eviction policy caps log.

## Integration points

### Phase 4 integration

- `VillageHistoryLog` on `VillageSavedData`.
- `HistorySummaryTemplates` registered at mod init.
- Producer hooks added in each relevant subsystem (lifestages, office,
  laws, crime, construction, companies, religion, events, visitors).
- Scribal ledger authorship (integrates with scribe work goal).
- Player UI: village chronicle screen (accessible from town hall).
- Dialogue predicate / effect extensions for history references.
- `/history` debug commands:
  - `/history list <village> [type] [importance]`
  - `/history recent <village> <count>`
  - `/history legendary <village>`
  - `/history add <village> <type> <params>` (for debugging)

### Phase 4 dependencies

- Every prior-phase subsystem producing notable events.
- Scribal professions (Phase 2).
- Office framework (Phase 3).

## Behavior contract

### Does

- Record notable events per village with structured metadata.
- Maintain evictable log under capacity.
- Support queries by type, importance, range, NPC.
- Enable scribes to author ledger books from log.
- Feed kingdom-level history.
- Flag legendary NPCs for long-term memory.
- Provide UI and dialogue references.

### Does not

- Provide a complete simulation log (only notable events; routine
  cycles not logged).
- Guarantee factual accuracy — entries reflect what was recorded,
  which may differ from truth (historiography as Phase 5+ feature).
- Enforce real-time viewing. Player queries history on demand.

## Edge cases

- **Event relating to unloaded village.** Log updates via saved data
  regardless; no live entity reference needed.
- **Related NPC dies and eviction removes their file.** History
  entry retained; NPC reference points to UUID with a cached name
  in `details`. NPC name cached at record time.
- **Village dissolves.** Log persists as archive; queryable for
  historical interest.
- **Duplicate producer fires.** Dedup by entry hash (type + tick +
  relatedNpcIds).
- **Template missing for event type.** Fallback template "A notable
  event occurred: {type}"; warning logged.

## Ordering dependencies

Phase 4 depends on:
- Every other subsystem producing events; most simple producer hooks.
- Scribal professions — ledger authoring.
- Office framework — office change events.
- Save data / codec infrastructure.

## Open decisions

- How aggressive should MINOR eviction be? **Proposed: 1-year retention
  with total-cap override at 1000.**
- Should translated/localized templates be supported? **Proposed:
  plumb through vanilla translation keys; Phase 5 provides culture-
  localized templates.**
- Ledger books — how do they differ from procedural history books
  (Phase 2)? **Proposed: ledger book = village-specific by time
  range; history book = topical summary (authored with editorial
  voice by scholar). Both exist.**
- Can players edit history (via dishonest scribe)? **Proposed: no
  in v1 — history is truthful. Phase 5 stretch for propaganda/
  revisionism.**

## Does-not-include

- Full event replay (no cinematic recall).
- Time-travel or retroactive editing.
- Inter-kingdom history correlations (stub; Phase 5+).
- Alternate-history branches.
- NPC-authored memoirs (separate from ledger — Phase 5 content).

## Revision Notes

### Phase 4 task 30 implementation pass

Things-to-flag responses:

1. **Importance assignment per event type.** Defaults
   live on `HistoryEventType.defaultImportance` and match
   the spec's stated examples (PLAGUE_OUTBREAK = MAJOR,
   LAW_ENACTED = NOTABLE, VILLAGE_FOUNDED = LEGENDARY).
   Producers can override per-instance — TrialExecutor
   bumps TRIAL_HELD to LEGENDARY when the punishment is
   EXECUTION or EXILE. The remaining ambiguous types
   (FEAST_DAY_CELEBRATED MINOR, BUILDING_CONSTRUCTED
   MINOR) lean light to keep the log lean; Phase 5
   tuning may adjust.
2. **NotablePerson criteria.** v1 ships the registry +
   API but **not** the auto-promotion rules. The spec's
   "prestige ≥ 40 author" / "10-year leader" / "master
   craftsman with multiple masterpieces" each depend on
   source systems that haven't fully shipped (Phase 2
   AuthorStatus prestige + apprenticeship masterpiece
   certification). Producers call
   `NotablePersonRegistry.recordNotable` directly when
   they identify a candidate; auto-promotion lands when
   the source signals are reliable.
3. **Pruning on legendary entries.** Confirmed never
   pruned. `HistoryImportance.LEGENDARY.retentionTicks`
   = `Long.MAX_VALUE`; `isPrunable()` returns false; the
   prune loop short-circuits.
4. **Cross-village query performance.**
   `VillageHistoryLog.kingdomCompilation` walks every
   village's list once and filters by
   `propagatesToKingdom`. At v1 scale (≤ 30 villages,
   ≤ 1000 entries each) this is fine. The 1000-entry
   per-village cap (spec line 144) keeps the worst-case
   bounded; profiling can drive a per-kingdom mirror
   index later.

Spec deviations:
- **Single per-world `VillageHistoryLog` SavedData**
  with a `byVillage` map instead of one SavedData per
  village. The shape is identical from the API surface
  (queries take a village UUID); fewer save slots
  = simpler load path.
- **History viewer screen + "Read village history"
  player verb deferred** to Phase 5 GUI polish. Debug
  commands cover the query surface today.
- **Procedural ledger book authoring** (spec lines
  192-208) deferred. Phase 2 didn't ship a finished
  scribal-book generator path, so the village_scribe's
  ledger-write goal needs that wire first.
- **Lifecycle archival uses existing NpcLifeEvent
  records** instead of firing dedicated history-only
  events. The Married / BirthInFamily / FamilyDeath /
  LifeStageAdvanced events on `NpcLifeEventBus` already
  carry the right participants; HistoryProducer
  translates them on the bus.
- **No archival hooks for masterpiece, festival, famine,
  caravan-loss, harvest, building construction.** Source
  events don't fire today (Phase 2 apprenticeship not
  shipped; Phase 5 festivals / famine; doc 26 caravan
  failure deferred). HistoryEventType slots exist so the
  eventual producers route via the same API.
- **Kingdom-history compilation** is a query-side
  aggregator (`VillageHistoryLog.kingdomCompilation(villageIds)`)
  that walks the supplied villages' logs. No separate
  `KingdomHistoryData` SavedData; entries stay village-
  scoped and the kingdom view is computed on demand.

Deferrals:
- History viewer / chronicle screen.
- "Read village history" player verb.
- Scribal ledger-book auto-authoring.
- Auto-promotion of NotablePerson based on prestige /
  tenure / masterpiece criteria.
- Dialogue predicate extensions for history references
  (`HasWitnessedHistoryEvent`, `KnewPerson`, etc.).
- Festival / masterpiece / famine / harvest / caravan-loss
  / building-construction archival hooks (source events
  not shipped).
- Cross-kingdom history correlation (per Does-not-include).
- Translatable templates (Phase 5 culture pass).
