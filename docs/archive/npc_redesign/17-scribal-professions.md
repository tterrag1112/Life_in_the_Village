# 17 — Scribal Professions

## Purpose

Three new professions handle writing, reading, curation, and research:
SCRIBE, LIBRARIAN, SCHOLAR. They produce letters, books, and contracts;
run schooling; provide literacy services to illiterate NPCs; and
function as offices in their own right (registrar, scribe-to-leader).

Scribes enable the letter system (`18-letters-and-books.md`), the
apprenticeship contract system (`16-apprenticeship.md`), and serve as
the inter-village knowledge relay (scholars learn and teach foreign
facts). They're the thread connecting literacy, information asymmetry,
and several office roles.

AUTHOR is not a profession but an emergent status granted to any NPC
who has published a book. Tracked separately.

## Data model

### Profession additions

Extend existing `Profession` enum:

```java
public enum Profession {
    // ... existing entries
    SCRIBE,
    LIBRARIAN,
    SCHOLAR,
    // ...
}
```

Extend `Profession.getDisplayName()`, XP sources,
`WeeklyScheduleLibrary`, `SkillComponent` mapping:

- SCRIBE: primary LITERACY, secondary COMMERCE
- LIBRARIAN: primary LITERACY, secondary SOCIAL
- SCHOLAR: primary LITERACY, secondary MEDICINE

(Medicine as scholar's secondary captures scholar-as-physician archetype
and scholar-as-natural-philosopher both; scholars contribute to the
medical body of knowledge.)

### Building types

New building types used by these professions:

```java
public enum BuildingType {
    // ... existing entries
    SCRIBE_WORKSHOP,       // where scribes write
    LIBRARY,               // where librarians curate
    SCHOLARS_RETREAT,      // where scholars study
    // ...
}
```

Each building type has standard placement integration via the building
profile registry (handled by the existing LITV building profile
system).

### AuthorStatus

Emergent attribute tracked on NPC:

```java
public class AuthorStatus {
    private final List<UUID> publishedBookIds;
    private int prestige;           // 0..100, grows with notable books
    public boolean isAuthor() { return !publishedBookIds.isEmpty(); }
    // save / load
}
```

Prestige rises with published books; major prestige (≥40) unlocks the
"renowned author" tag visible in profile. Phase 4 kingdom history
records prestigious authors.

## Scribe profession

### Role

Scribes are the village's literate middle-class. They:

- Write letters on commission (for illiterate or busy NPCs).
- Copy existing books.
- Draft contracts (apprenticeship, property transfer, marriage — v1
  focuses on apprenticeship).
- Maintain village ledgers (when paired with `village_scribe` office).

### Workshop and tools

A SCRIBE_WORKSHOP building contains:

- A writing desk (custom block or repurposed job-site block).
- Storage chest for paper, ink, books in progress.
- Nearby bookshelf for reference.

Tools: `WRITING_QUILL` item (custom or mapped to vanilla feather +
ink sack). Paper and books as expected.

### Work behavior

New goal: `ScribeWorkGoal` replaces generic workstation production:

1. Check order queue for active commissions.
2. Walk to desk, begin writing.
3. Consume paper/ink per order; produce the letter/contract/book
   copy as physical item.
4. Deliver item to client (nearby pickup) or to workshop storage.
5. Award LITERACY XP per completed commission.

Scribes maintain a `CommissionQueue` of pending orders:

```java
public record ScribeCommission(
    UUID commissionId,
    UUID clientId,              // requester
    ScribeProductType product,
    String content,             // text to write (for letters)
    UUID targetId,              // for letters: recipient
    long requestedTick,
    long dueTick,
    long bronzeFee,
    CommissionStatus status
) { ... }

public enum ScribeProductType {
    LETTER,
    CONTRACT,
    BOOK_COPY,
    DECREE;       // for village leader / office use
}
```

### Commission pricing

Base fees in bronze:

| Product | Base fee | Per-page/length modifier |
|---|---|---|
| Letter (short) | 5 | flat |
| Letter (long) | 15 | +5 per extra page |
| Contract | 25 | standard |
| Book copy | 100 | +20 per page |
| Decree | 40 | by leader order, often village-funded |

Scribe skill reduces consumption — higher LITERACY means less paper
wasted.

## Librarian profession

### Role

Librarians curate the village LIBRARY:

- Catalog books and keep them on shelves.
- Lend books to villagers who want to read.
- Organize schooling sessions (for children — see
  `15-child-elderly-arcs.md`).
- Archive village records (death certificates, kingdom decrees).

### Library building

LIBRARY contains:

- Multiple bookshelves (vanilla and chiseled) storing books.
- Reading tables/benches.
- A lecturn (custom or vanilla) for reading events.
- Archive chest for sensitive documents.

Library has a "catalogue" — data-side record of every book in the
library:

```java
public record LibraryCatalogue(
    UUID libraryBuildingId,
    Map<UUID, BookRecord> books,        // by bookId
    Map<UUID, UUID> lendings           // bookId -> borrowerId (one copy rule)
) { ... }

public record BookRecord(
    UUID bookId,
    String title,
    String author,
    List<String> topicsCovered,         // knowledge topics from 03-knowledge-system
    Optional<Skill> skillBuff,          // skill bonus on read
    int pageCount,
    long acquiredTick
) { ... }
```

### Work behavior

`LibrarianWorkGoal`:

1. Stock new books (arrive from scribes, scholars, or donations).
2. Attend borrower requests (NPC approaches library during open
   hours).
3. Run schooling session during daily schooling window.
4. Re-shelf returned books.
5. Archive notable events when delivered by scribe (village clerk
   records).

Librarians gain LITERACY XP per lending transaction and SOCIAL XP
during schooling sessions.

### Lending protocol

- NPC wants to read a book → approach library during open hours.
- Library has the book + copy available → lent (one copy at a time).
- Borrower reads for ~3 days (in-game) → returns.
- Late return: small fine (1 bronze per day late) → librarian's wage
  or library treasury.
- Lost book: larger fine.

Existing LITV containers/inventories may be used for the physical
shelf representation; data-layer catalogue is authoritative.

## Scholar profession

### Role

Scholars research, teach, and author original works:

- Study existing books to extract knowledge topics.
- Travel (rarely — via caravan) to gather foreign knowledge.
- Author original books on their specialty.
- Teach advanced lessons — serve as tutor to literate NPCs beyond
  basic schooling.
- Serve as expert witnesses in political/justice matters.

### Building

SCHOLARS_RETREAT contains:

- Multiple bookshelves.
- A study desk.
- An alchemy/medicine table (scholar's secondary expertise).
- Small garden for herbs (if MEDICINE-inclined).

Often integrated into or adjacent to LIBRARY. Scholars may work out
of the library if no retreat exists.

### Work behavior

`ScholarWorkGoal`:

1. **Research phase**: pick a topic (from knowledge ledger's gaps or
   scholar's interests), study relevant books in nearby library,
   gain knowledge entries at higher fidelity.
2. **Authorship phase**: after enough research, begin writing a new
   book (takes weeks in-game). Output is a physical book with
   authored content.
3. **Teaching phase**: during schooling windows, join librarian or
   run advanced sessions.
4. **Consultation**: respond to other NPCs' `ask_about` queries with
   higher-fidelity knowledge.

Scholars gain significant LITERACY and MEDICINE XP from authoring.

### Authorship

When scholar completes a book:

- Book stored in library catalogue (or scholar's retreat).
- Scholar NPC gains `AuthorStatus` entry; prestige +5 for first
  book, +10 for well-received (high topic-coverage).
- Village history records "Scholar X published 'Y'".
- Gossip seed generated: news of the book propagates.

Prestigious authors (prestige ≥ 40) become named notables in the
kingdom, remembered for generations (Phase 4 kingdom history).

## Literacy and profession gating

From `05-skill-system.md`: LITERACY skill gates:

- ≥ 30: Can read simple books (gain basic knowledge).
- ≥ 60: Can read complex books, can write letters, can serve as
  scribe apprentice.
- ≥ 80: Can write books, can serve as full scholar.

Profession entry requirements:

- SCRIBE: LITERACY ≥ 60 at hiring.
- LIBRARIAN: LITERACY ≥ 50 at hiring.
- SCHOLAR: LITERACY ≥ 80 at hiring. Usually reached through
  scholar apprenticeship first.

Player pursuing scribal professions progresses through LITERACY skill
via reading books, taking scholar lessons, or apprenticing under a
scribe. Existing player-profession system extends with these three
new profession options.

## Cultural literacy baselines

Phase 5 culture pass sets literacy baselines per culture. Phase 2
ships with a uniform 15-baseline for all NPCs. Adjust:

- If village has LIBRARY + LIBRARIAN active for ≥ 90 days, all
  children in village get +1 LITERACY per month via schooling.
- If village has SCHOLAR active, adult NPCs can occasionally receive
  tutoring bumping LITERACY +2 per session.

Without scribal infrastructure, literacy stagnates — reinforcing the
value of these professions.

## Offices held by scribal professions

- **Village Scribe** (office in `06-office-framework.md`): a SCRIBE
  promoted to village clerk. Records births, deaths, marriages;
  produces decrees for the leader; archives village history.
- **Village Registrar** (guild version): SCRIBE-track, tracks guild
  members and apprenticeships.
- **High Scholar** (future — Phase 4 kingdom level): advisor to king
  on matters of knowledge.

Office appointment follows the framework; typically APPOINTED by
village leader for Village Scribe.

## Integration points

### Phase 2 integration

- New professions: SCRIBE, LIBRARIAN, SCHOLAR added to
  `Profession` enum. Update all profession lookups.
- New building types: SCRIBE_WORKSHOP, LIBRARY, SCHOLARS_RETREAT.
  Add to `BuildingType`, create `BuildingProfile` entries, add to
  relevant village types (particularly scholarly-culture villages
  in Phase 5).
- New goals: `ScribeWorkGoal`, `LibrarianWorkGoal`, `ScholarWorkGoal`.
  Register via `ProfessionGoalFactory`.
- `ScribeCommission` queue attached to workshop building data.
- `LibraryCatalogue` attached to LIBRARY building data.
- `AuthorStatus` component added to `TownspersonMob`.
- Knowledge ledger gains ability to be populated via book reading
  (scholar side) and schooling (librarian side) — connects to
  `03-knowledge-system.md`'s knowledge producers.
- Player verbs:
  - "Commission a letter" (on SCRIBE NPC) — queues a letter.
  - "Borrow a book" (on LIBRARIAN) — borrow from catalogue.
  - "Commission a book copy" (on SCRIBE or LIBRARIAN).
  - "Take a lesson" (on SCHOLAR or LIBRARIAN for children).
- Debug commands:
  - `/scribe commissions <npc>`
  - `/library catalogue <building>`
  - `/scholar books <npc>`

### Phase 3+ integration

- Village Scribe office wires in.
- Religious rites documentation by temple scribe variant.
- Contracts used in crime/justice system (evidence).

### Phase 4 integration

- Prestigious authors recognized in kingdom history.
- Cross-village scholar correspondence (letters between scholars).

## Behavior contract

### Does

- Introduce three new professions with distinct workflows.
- Enable literate NPCs to produce letters, books, and contracts.
- Curate a per-library book catalogue with lending.
- Author original books with knowledge-topic coverage.
- Gate literate actions by LITERACY skill.
- Gate professions by minimum LITERACY at hiring.

### Does not

- Replace existing profession architecture — these are additional.
- Implement custom writing-desk block visuals in v1 (reuse existing
  placeholders).
- Handle multi-language books. Language families are Phase 5+.
- Model book copying errors (fidelity loss). Copies are faithful in
  v1 — only rumor content degrades.
- Generate procedural book content in v1 — templates only.
  (`18-letters-and-books.md` covers book content systems.)

## Edge cases

- **SCRIBE workshop without desk.** Production halts; workshop marked
  incomplete.
- **Library with no books.** Librarian still runs schooling and
  archives; lending is empty.
- **Scholar with no library access.** Works out of retreat; research
  throughput halved.
- **Book lent while library catalogue changes hands.** Lending record
  stays attached to the bookId; if book goes missing, fine applied.
- **Player attempts scribe commission without enough coin.** Rejected
  with dialogue; no commission queued.
- **Scholar finishes a book while village being attacked.** Book
  completes at safe moment after threat; no content lost.

## Ordering dependencies

Phase 2 depends on:
- Skill component (Phase 0) — LITERACY skill.
- Knowledge ledger (Phase 0 + producers) — scholar knowledge
  population.
- Memory system (Phase 1) — `TAUGHT_BY` memory creation during
  schooling.
- Existing profession architecture — to add new professions.
- Existing building profile system — new building types.
- Letters and books system (`18-letters-and-books.md`, Phase 2
  same-phase) — item spec.

Apprenticeship (`16`) and scribal (`17`) reference each other but
can be implemented in either order; scribal contracts are a
degradable feature (works without written contracts).

## Open decisions

- Should scribes produce contracts for marriages, land deeds,
  apprenticeships all in v1 or just apprenticeships? **Proposed: just
  apprenticeships in v1. Other contracts stubbed — scribe capable,
  but no wiring from those systems yet.**
- Can a single NPC hold multiple scribal professions
  (scribe-and-librarian)? **Proposed: no — one profession per NPC
  (existing rule). But scribal professions can rotate as same NPC
  retrains.**
- Written-content language differs by culture — v1 handles all as
  "common". **Proposed: confirm; language families are Phase 5+.**

## Does-not-include

- Custom scribe-workshop block art (reuses existing).
- Writing minigame for player-scribes. Auto-completes on time.
- Illuminated manuscripts as separate product type. Collapse into
  book copy with higher fee variant (Phase 5).
- Scholar debates/public lectures as events. Phase 5 expanded events.

## Revision Notes

(changes recorded here as the spec evolves after testing)

### Phase 2 implementation notes

- **SCHOLAR was already in the Profession enum** (left over from
  an earlier flavor pass). The spec's three-profession set ships
  by adding SCRIBE + LIBRARIAN; SCHOLAR is rewired to the new
  `SCHOLARS_RETREAT` building rather than `LIBRARY`. The
  `professionFor(BuildingType)` mapping changed accordingly:
  LIBRARY → LIBRARIAN, SCRIBE_WORKSHOP → SCRIBE,
  SCHOLARS_RETREAT → SCHOLAR. Existing villages with a LIBRARY
  will now spawn a librarian on next populate; old SCHOLAR NPCs
  still load fine and execute the new ScholarWorkGoal (which
  falls back to the village library if no retreat is assigned —
  spec line 244).
- **LIBRARY already existed in BuildingType**, so only
  SCRIBE_WORKSHOP + SCHOLARS_RETREAT were added. Both got
  explicit `BuildingProfileRegistry` entries (workshop
  civic-adjacent, retreat landmark civic). `BuildingType.LIBRARY`
  was already landmark-civic from before.
- **Hire gate + bootstrap top-up.** `ProfessionRequirements.literacyRequired`
  returns 50/60/80 for the three professions per spec lines
  286-291. The populator now calls `ensureLiteracyForBootstrap`
  after `setProfession`, bumping bootstrap NPCs to the gate so
  spawn-time literacy already meets the bar. Player-facing hire
  paths (future verb / mid-game re-profession) consult
  `ProfessionRequirements.meets(npc, target)`.
- **Workspace lookup falls back to village LIBRARY for
  scholars.** Spec line 244 says "Scholars may work out of the
  library if no retreat exists." `ScholarWorkGoal.canUse` first
  checks the assigned building (must be SCHOLARS_RETREAT or
  LIBRARY), then falls back to scanning the assigned village
  for any LIBRARY. No half-rate throttle yet — Phase 5 polish
  could add the spec's "research throughput halved" effect.
- **Item layer: placeholders via `ScribalItems`.** Spec line
  410-411 calls out doc 18 as the canonical letter/book item
  spec. Phase 2 ships `ScribalItems` as a single-swap-point
  factory: letter/contract/decree wrap vanilla `Items.PAPER`
  with content in `DataComponents.ITEM_NAME`; book wraps
  `Items.WRITTEN_BOOK` with `WrittenBookContent`. Doc 18
  replaces the factory bodies; every caller stays put.
- **Custom scribe-workshop / writing-desk blocks.** Spec line
  431-432 notes "reuses existing placeholders". Phase 2 doesn't
  add a custom block; the desk is the building's
  `getShape().getOrigin()`, the held item during writing is
  vanilla `Items.FEATHER`. Phase 5 polish replaces with a
  proper desk model.
- **Lending one-copy rule.** Spec line 213 says "one copy at a
  time". `LibraryCatalogue` keys loans by `(bookId, borrowerId)`
  composite — same book lent serially to different borrowers
  works; the same borrower trying to re-borrow the same book
  replaces the prior loan record (effectively a "renew"). Per
  spec the cap is per copy: `isAvailableForLending` checks
  `outstanding < copyCount`, so a 3-copy book can be out to 3
  different borrowers concurrently.
- **Late fines and lost books.** `LibraryLending.lateFineFor`
  returns 1 bronze/day late per spec line 216. `LOST_BOOK_FINE`
  is set to 25 bronze; the spec says "larger" without a number,
  so this is open to retuning.
- **Overdue sweep auto-recovers.** v1 `LibrarianWorkGoal`
  sweeps overdue loans every 1200 ticks and force-returns the
  book; the recovered-loan path doesn't yet send a "return your
  book" letter (that's a doc 18 letter-system follow-up).
- **Scholar publish cycle.** `ScholarProgress.PUBLISH_THRESHOLD
  = 30` research points + a 30-day cooldown between books.
  `ScholarWorkGoal` adds 1 research point per 1200-tick
  research interval, so a scholar at the desk through normal
  work hours produces a book roughly once per in-game month.
  Open to retuning.
- **Schooling stub.** `LibrarianWorkGoal.grantSchoolingTrickle`
  hands +1 LITERACY to nearby children every 24000 ticks per
  spec line 305; the proper schooling system (doc 15) when it
  lands will replace this with structured lesson sessions.
- **Letters as PAPER.** `ScribalItems.letter` ships as
  `Items.PAPER` with the body in `DataComponents.ITEM_NAME` —
  vanilla doesn't have a "letter" item, so paper is the
  closest stand-in. Doc 18's `WrittenLetterItem` will be the
  proper home; this is the explicit swap point.
- **Decree path stubbed.** `ScribeProductType.DECREE` is fully
  carried through ScribeWorkGoal but no production code calls
  the verb path yet — Phase 3 office wiring (Village Scribe
  office, Phase 3 task) creates decrees from the leader's
  side. The plumbing is ready.
- **Children-only "Take a lesson"** is loose in v1 — the verb
  is available against any SCHOLAR or LIBRARIAN, and the
  player's "lesson" awards the NPC a SOCIAL tick + nearby
  children a LITERACY tick. The spec's children-only gate is
  doc 15 territory; tightening this verb to children-only
  lands when child arcs do.
