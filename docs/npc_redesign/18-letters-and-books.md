# 18 — Letters & Books

## Purpose

Scribes produce letters and books (`17-scribal-professions.md`); this
doc specifies the items themselves, how they're delivered, what they
contain, and how reading them affects NPCs.

Letters are a per-instance written item addressed to a specific NPC or
player, carrying a mood/memory/knowledge payload at receipt. Books are
shared artifacts representing authored works, village history,
knowledge guides, or procedural kingdom records.

Both tie into the knowledge system — readers gain knowledge entries on
read (gated by LITERACY), and written contents can be gossiped onward.

## Data model

### WrittenLetter item

Custom item extending Minecraft's writing system:

```java
public class WrittenLetterItem extends Item {
    // Data components: letter content, author, recipient, timestamp,
    // optional sealed flag, optional contract flag
}

public record LetterContent(
    UUID letterId,
    UUID authorId,
    String authorName,
    UUID recipientId,       // target NPC/player UUID
    String recipientName,
    long writtenTick,
    List<String> pages,     // page text with optional placeholders
    boolean sealed,         // true = private; breaking seal visible
    Optional<LetterSpecial> special
) {
    public static final Codec<LetterContent> CODEC;
}

public enum LetterSpecial {
    LOVE_LETTER,
    THREAT,
    APOLOGY,
    ANNOUNCEMENT,     // mass letter (e.g. kingdom decree)
    CONTRACT,         // apprenticeship or other formal agreement
    INTRODUCTION;     // "I commend the bearer to you"
}
```

Letters are delivered as items — they occupy inventory space, can be
dropped, stolen, or lost.

### Book content system

Books reuse Minecraft's `WrittenBookContent` component but with
extended metadata:

```java
public record ExtendedBookContent(
    UUID bookId,
    String title,
    String author,
    BookCategory category,
    List<String> pages,
    List<String> topicsCovered,   // knowledge topics granted to readers
    Optional<SkillBuff> skillBuff,
    int pageCount,
    long authoredTick
) {
    public static final Codec<ExtendedBookContent> CODEC;
}

public enum BookCategory {
    HISTORY,              // kingdom/village history excerpts
    GUIDE,                // skill-buff "textbook"
    LITERATURE,           // authored fiction/poetry
    RELIGIOUS,            // scripture, rites
    MEDICAL,              // healing lore
    TRAVELOGUE,           // foreign knowledge
    LEDGER,               // village/guild records
    LETTERS;              // published correspondence
}

public record SkillBuff(
    Skill skill,
    int xpOnRead,              // XP granted on reading
    float rateMultiplier,      // optional: xp-rate multiplier while "studying"
    int studyMultiplierDays    // for how long
) {}
```

### Letter lifecycle

#### Creation

Letters are produced by scribes (NPC or player with LITERACY ≥ 60).

Via scribe commission (see `17-scribal-professions.md`):

1. Client approaches scribe, places commission with:
   - Recipient (another NPC)
   - Content message (selected from dialogue prompts or, for player,
     via a small UI)
   - Special flag (optional)
2. Scribe queues, processes, produces `WrittenLetter` item.
3. Letter handed to client on completion.

Direct write (for literate NPCs/players):

- LITERACY ≥ 60 NPCs can self-author letters during LEISURE if at a
  writing surface (desk, or held quill+book).
- Player with LITERACY skill can craft letters via a crafting recipe
  or a UI.

#### Delivery

Once letter is produced, delivery paths:

1. **Self-delivered**: client/player walks to recipient and gives
   them the letter (existing right-click-with-item interaction).
2. **Courier**: give the letter to a merchant/caravan going toward
   the recipient's village. Caravan delivers on arrival.
3. **Scribe's postal round**: if village has multiple scribes, a
   `PostalGoal` runs during SOCIAL phase delivering commissioned
   letters locally.

Each delivery path has failure modes:
- Courier loses letter (low probability from caravan events).
- Recipient unreachable (deceased, migrated far away) — letter
  returns to origin or goes dead-letter.
- Theft during delivery possible (Phase 3 crime).

#### Reading

When an NPC receives a letter:

1. Letter added to NPC's personal inventory.
2. NPC picks it up during next LEISURE or MEAL phase.
3. If NPC has LITERACY ≥ 30: reads it → triggers
   `onLetterReceived(content)`.
4. If NPC has LITERACY < 30 and village has a scribe: may request
   scribe to read it aloud (creates a commission). Partial privacy
   tension — sealed letters may not be read this way by strangers.
5. If no scribe and illiterate: letter sits unread in inventory
   indefinitely.

#### onLetterReceived effects

```java
public void onLetterReceived(TownspersonMob recipient, LetterContent content) {
    // 1. Memory creation
    MemoryProducer.record(recipient, MemoryType.RECEIVED_LETTER,
                          content.authorId(),
                          summarize(content));

    // 2. Mood trigger
    MoodTrigger trigger = content.special().map(...)
        .orElse(MoodTrigger.LETTER_RECEIVED);
    // LOVE_LETTER from friend → extra boost; THREAT → negative; etc.
    MoodProducer.apply(recipient, trigger);

    // 3. Memory refresh on sender
    recipient.getMemory().findAnyOf(content.authorId())
        .ifPresent(m -> recipient.getMemory().refresh(m.memoryId(), 8f));

    // 4. Knowledge gain — any topics mentioned in letter become
    //    knowledge entries with source=LETTER, fidelity=0.9
    // (Parsing letter for knowledge topics is a Phase 5 content job;
    // Phase 2 only attaches topics if letter was authored with
    // explicit topic tags by a scholar.)

    // 5. Relationship adjustment (small positive for any letter)
    recipient.getRelationships().adjust(
        content.authorId(), +2, tick, MET_SOCIALLY);

    // 6. Dialogue tree: if recipient next talks to player, mentions
    //    the letter.
}
```

Sealed letters: if broken by someone other than the recipient, that's
a PRIVACY_VIOLATION event → reputation hit + possible crime (Phase 3).

### Book lifecycle

#### Authoring

Scholars author books over weeks. See `17-scribal-professions.md` for
the workflow. On completion:

1. Book item created with full `ExtendedBookContent`.
2. Scholar receives authoring reward (LITERACY XP, prestige +5).
3. Book stored in library catalogue or scholar's study.
4. Village history records the authoring.

#### Copying

Scribes copy existing books. Produces a duplicate book item with
different `bookId` but same content. Copies can differ only in minor
attribution details (scribe credits themselves).

#### Acquisition / circulation

Books move between NPCs and locations via:

- Library lending (returns after reading period).
- Caravan trade (books as trade goods in Phase 4).
- Gifts (via `give_gift` verb).
- Player purchase from scribe/library.

#### Reading effects

`onBookRead`:

```java
public void onBookRead(TownspersonMob reader, ExtendedBookContent content) {
    if (reader.getSkills().get(Skill.LITERACY) < 30) return; // skip silently

    // 1. Knowledge gain — each topic becomes an entry
    for (String topic : content.topicsCovered()) {
        reader.getKnowledge().add(new KnowledgeEntry(
            topic, /* category */, /* fidelity */ 0.95f,
            KnowledgeSource.BOOK, tick,
            /* content */, content.bookId()));
    }

    // 2. LITERACY XP
    reader.getSkills().addXp(Skill.LITERACY, 15);

    // 3. Skill buff if applicable
    content.skillBuff().ifPresent(buff -> {
        reader.getSkills().addXp(buff.skill(), buff.xpOnRead());
        if (buff.rateMultiplier() > 1f) {
            reader.getActiveSkillBuffs().add(new ActiveBuff(
                buff.skill(), buff.rateMultiplier(),
                tick + buff.studyMultiplierDays() * 24000L));
        }
    });

    // 4. Memory creation
    if (content.category() == BookCategory.LITERATURE) {
        // Possible mood boost for beloved works
        MoodProducer.apply(reader, MoodTrigger.LETTER_RECEIVED); // reuse small positive
    }
}
```

Partial literacy (30..59): reader gains only 50% of effects.

#### Skill-buff books ("textbooks")

Special books with a non-empty `SkillBuff`. Examples:

- "A Treatise on Forges" — CRAFTING +50 XP, +15% XP-gain on CRAFTING
  for 7 days.
- "Herbal Remedies of the Lowlands" — MEDICINE +50 XP, +10% rate
  for 14 days.
- "Swordsmanship Primer" — COMBAT +30 XP.

Phase 5 content pass writes textbook library. Phase 2 ships with 3-4
starter textbooks tied to scholar-authored content.

### Procedural books

Some books are generated procedurally from game state:

- **Kingdom History Books**: generated by scholars authoring, pull
  from `KingdomHistoryData`. Pages = summarized events in the target
  time range. Reading them populates REGIONAL/FOREIGN knowledge.
- **Village Ledger**: generated by village_scribe office holders.
  Records of births, deaths, marriages, office changes, notable
  events.
- **Guild Ledger**: generated by guild registrar. Records apprenticeship
  completions, guild member list.

Procedural books are authored over time, not instantly. The scholar
or scribe spends sessions writing; each session adds a page of
current events. Once "complete" (author decides or page cap), it
becomes a finished book with topic tags reflecting the events
included.

## Chiseled bookshelves as storage

Libraries use chiseled bookshelves (vanilla) for display; data-side
`LibraryCatalogue` is authoritative. Books in chiseled bookshelves
display normally; interacting with a bookshelf lets NPCs (via
librarian goal) shelf/unshelf.

## Player reading books

Player's LITERACY skill determines read effects — same gating as NPCs.
Reading a book in player inventory:

- Opens as vanilla written-book screen.
- Effects applied via `onBookRead` using player's skill component.
- If player's LITERACY < 30, show a UI message: "The script is
  difficult to parse. You catch only fragments."

Partial literacy still gains some LITERACY XP — reading trains reading.

## Persistence

Letters and books persist as items in inventories/containers — no
separate saved data structure. Their content is serialized via the
extended book/letter components.

Library catalogues persist in the `LIBRARY` building data.

`AuthorStatus` persists on authoring NPCs (see
`17-scribal-professions.md`).

## Integration points

### Phase 2 integration

- `WrittenLetterItem` class created, registered with items registry.
- `ExtendedBookContent` component registered; interop with vanilla
  `WrittenBookContent` for existing saves.
- Letter delivery hooks: caravan extension for letter transport,
  `PostalGoal` for local delivery.
- `onLetterReceived` and `onBookRead` hooks wired into relevant NPC
  goal transitions (NPC picks up letter from inventory during LEISURE
  and processes).
- Player verbs:
  - "Write letter" (LITERACY ≥ 60) — opens UI, selects recipient and
    composes.
  - "Send letter" — hands letter to NPC for delivery.
  - "Read book" — processes book in inventory.
- Procedural book generation hooks added to scholar authorship
  pipeline.
- Knowledge ledger integration: book reading fires
  `onBookRead` producer pathway.
- Event hooks: letter-author gains SOCIAL XP per sent letter;
  letter-recipient gains LITERACY XP when reading (minor).

### Phase 3+ integration

- Crime: sealed-letter violations, letter forgery by low-Honesty
  scribes.
- Event: caravan arrival may include "letter delivered" sub-event.

### Phase 4 integration

- Caravan routes carry letters across villages/kingdoms.
- Book trade: books as Phase 4 trade goods with pricing.
- Prestigious authors' books circulated kingdom-wide.

## Behavior contract

### Does

- Produce letters and books as physical items with structured content.
- Deliver letters via multiple paths (self, courier, postal).
- Handle receiving and reading with proper literacy gating.
- Grant knowledge and skill buffs on read.
- Support procedural generation of history/ledger books.
- Fire appropriate memory/mood/knowledge events.

### Does not

- Model language differences in v1 (all content treated as
  uniformly readable by literate characters).
- Provide a rich letter-composing editor (menu-driven in v1).
- Auto-generate literature content. Authored books use templates;
  Phase 5 content pass writes them.
- Preserve copy fidelity differences in v1 (copies are perfect).

## Edge cases

- **Letter delivered to dead recipient.** Dead-letter; scribes can
  return to sender or mark unread. Expires after 30 days in dead-
  letter storage.
- **Sealed letter intercepted.** Interceptor can break seal but now
  has a flagged violation. If intercepted by close friend of
  recipient and NOT read, no issue.
- **Book read by illiterate NPC.** No effects applied; book sits in
  inventory.
- **Partial literacy on a complex book.** Random 50% chance each
  topic grants knowledge; LITERACY XP still applies fully.
- **Copy of a copy.** Copies produce same content; no degradation.
- **Two scholars authoring the same topic.** Both books exist
  independently; no conflict. May be gossiped about comparatively.
- **Book stored in chiseled bookshelf → bookshelf broken.** Book
  item drops normally; catalogue updates.

## Ordering dependencies

Phase 2 depends on:
- Scribal professions (Phase 2, same phase) — producers.
- Knowledge ledger (Phase 0 + producers) — target of book reading.
- Skill component (Phase 0) — LITERACY gating.
- Memory system (Phase 1) — `RECEIVED_LETTER` memory creation.
- Mood system (Phase 1) — mood triggers.
- Existing caravan system — for long-distance delivery.

## Open decisions

- Letter-writing UI for player: free-form text box or template?
  **Proposed: template with a list of pre-written greeting/body/
  closing selections; free-form bodies gated behind LITERACY ≥ 80.
  Keeps scope contained.**
- Should old books appreciate in value (first editions)? **Proposed:
  not in v1. Books have flat value.**
- Should sealed letters be tamper-evident via seal break?
  **Proposed: yes — `sealed` flag flips to `broken` on open, visible
  to recipient and flags the opener. Witness-based crime detection
  (Phase 3) uses this.**

## Does-not-include

- Custom chiseled-bookshelf art. Reuse vanilla.
- Animated writing art. NPC standing at desk with item-in-hand is
  sufficient.
- Multi-page layered book formatting (images, fancy fonts). Plain
  text pages.
- Book banning/censorship mechanics. Phase 3+ perhaps.
- Magical/enchanted books. Out of scope.

## Revision Notes

(changes recorded here as the spec evolves after testing)
