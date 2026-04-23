# 20 — Religion & Priest Profession

## Purpose

Adds functioning religion to villages: priests officiating rites,
per-NPC piety, a religious calendar, tithes and offerings, multi-
faith support. Links to scribal system (religious books), life-
stage arcs (coming-of-age, funerals), and office framework
(village_priest).

## Data model

### Religion

```java
public record Religion(
    String id,                            // "sunstead", "tidecall", "the_loom", "forge_creed"
    String displayName,
    List<String> coreTenets,
    List<Rite> rites,
    List<String> sacredLocations,
    ReligiousCalendar calendar,
    Optional<String> deity,
    List<BookCategory> preferredBookCategories
) { ... }

public enum Rite {
    COMING_OF_AGE, MARRIAGE, NAMING, FUNERAL,
    BLESSING, CONFESSION, OFFERING, TITHE,
    HARVEST_THANKSGIVING, FEAST_DAY;
}

public record ReligiousCalendar(Map<String, Integer> holyDaysByName) {}
```

### Starter religions (v1)

Aligned to starter cultures:

- **Sunstead** (Plainfolk): solar/agricultural deity; all standard
  rites; Spring/Harvest Equinox holy days.
- **The Loom** (Silkwood): abstract pattern-of-fate, no named deity;
  CONFESSION, BLESSING, NAMING, FUNERAL; monthly "Threadings".
- **Tidecall** (Tidereach): sea-spirit pantheon; BLESSING before
  voyages, FEAST_DAY after fishing seasons; tidal holy days.
- **The Forge Creed** (Highmarch): ancestor-worship of warrior-
  leaders; martial COMING_OF_AGE vigil, honor-recounting FUNERAL,
  battle BLESSING; kingdom founding anniversary.

### PietyComponent

```java
public class PietyComponent {
    private final Map<String, Float> beliefs;      // religion id → strength 0..1
    private long lastRiteAttendedTick;
    private int ritesAttendedThisMonth;

    public String primaryReligion();
    public float primaryStrength();
    public boolean attendsRite(Rite r);
}
```

Most NPCs have single primary religion matching culture. Foreigners
carry home religion at high strength + local at low (syncretism).

Player's piety also tracked via same component on player data
attachment.

### Priest profession

```java
public enum Profession {
    // ... existing
    PRIEST,
}
```

Requires adult + LITERACY ≥ 40 + SOCIAL ≥ 30. Skills: primary
SOCIAL, secondary LITERACY. Medicine common third.

Building: existing TEMPLE; one religion per village temple (culture-
dominant).

## Priest behavior

### Work activities

`PriestWorkGoal`:
1. Check calendar for holy day.
2. Process pending rites queue (commissions).
3. During WORK phases: officiate or temple tasks (tend altar, read
   scripture).
4. During SOCIAL: CONFESSION 1:1 and BLESSING.

### Rite officiation

```java
public record RiteExecution(
    UUID riteId,
    Rite type,
    UUID presidingPriestId,
    List<UUID> participantIds,
    BlockPos location,
    long scheduledTick,
    long completedTick,
    RiteOutcome outcome
) {}

public enum RiteOutcome { PENDING, SUCCESSFUL, DISRUPTED, SKIPPED }
```

Per-rite effects:

- **COMING_OF_AGE**: teen → adult with priest present. Subject piety
  +0.1, mood +20, rel with priest +10. Memory + village-history.
- **MARRIAGE**: at temple/square. Spouses + priest + household.
  Mood +50 (existing) + +10 from blessing. Memory OFFICIATED_BY.
- **NAMING**: on child birth; priest visits. Picks formal name.
  Household mood +15; priest rel +5.
- **FUNERAL**: on death. Household, friends, colleagues. Lessens
  grief. Memory pinned "remembered kindly." History entry.
- **BLESSING**: ~100 ticks pre-undertaking. Target +mood + 5% primary
  skill for 24h. Priest +SOCIAL XP.
- **CONFESSION**: private 1:1. Decays negative moods faster. Slight
  trait drift toward Honesty. High-Honesty priest honors privacy;
  low-Honesty risks gossip leak (major rep hit if detected).
- **OFFERING**: item into temple treasury. Piety +0.05, mood +5.
- **TITHE**: recurring weekly/monthly. Failure = piety drop, rel
  drop.
- **HARVEST_THANKSGIVING**: village-wide on harvest day. +2d mood,
  temporary food-need reduction, treasury bonus.
- **FEAST_DAY**: culture-defined scheduled event.

### Confessions

Priests learn via confession. Knowledge flagged sensitive (see
`03-knowledge-system.md`). High-Honesty priests honor; low-Honesty
risk gossip (betrayal story).

### Temple treasury

Each temple has `BuildingEconomy` treasury. Income from offerings/
tithes/donations. Used for priest wages, maintenance, alms
(Compassionate priest prioritizes), religious books for library.

High-piety villages accumulate significant treasury; low-piety temples
go into deficit → temple decay → priest abandonment.

## Player participation

### Verbs

- **Attend rite**: piety +0.02 + mood boost.
- **Make offering**: piety gain proportional to value.
- **Request blessing**: pay fee; get blessing effect.
- **Confess** (LITERACY ≥ 30): private dialogue from memory options.
  Mood boost.
- **Pay tithe**: weekly auto-deduct if flag set.
- **Commission rite**: marriage, naming, funeral. Priest fee.

### Piety tiers

- 0.0-0.2 Unaffiliated
- 0.2-0.5 Faithful
- 0.5-0.8 Devout
- 0.8-1.0 Pious

Effects: religious NPC rel bonuses per tier; blessing bonuses at
higher tiers. High piety unlocks future religious offices.

## Offices

- **village_priest** (from `06-office-framework.md`): primary priest.
  Rite-officiation powers, temple treasury access.
- **temple_high_priest** (v1 stub, Phase 4): regional priest via
  ASCENSION chain.

## Cultural variants

### No-temple villages

Religion exists as NPC attribute; rites performed at makeshift
locations (shrine landmark, household, wilderness). Priest present
if culture demands; absent otherwise. Missing-temple rites have
25%-reduced effects.

### Multi-faith villages

Multi-culture village may have multiple religions in NPC pop. One
temple per village v1 (dominant culture). Minority-faith NPCs:
- Lower piety strength in local religion.
- Private practice at home.
- Occasional pilgrimage to distant village with their home faith.

Phase 5 may allow secondary shrines.

## Persistence

On entity: `npcPiety` = `{ beliefs: {...}, lastRiteAttendedTick,
ritesAttendedThisMonth }`.

Temple: treasury via existing `BuildingEconomy`.

Per-village `RiteSchedule` on `VillageSavedData`.

## Integration points

### Phase 3 integration

- PRIEST added to Profession.
- `PietyComponent` on TownspersonMob.
- Religions registered at mod init.
- `PriestWorkGoal` registered.
- Rite scheduler per village on triggers:
  - COMING_OF_AGE on teen→adult.
  - MARRIAGE on courtship completion.
  - NAMING on child birth.
  - FUNERAL on death.
  - BLESSING/OFFERING on demand.
  - Calendar rites on holy-day tick.
- Holy-day events in existing event system.
- Player verbs listed.
- `/religion list|set|rite` debug commands.

### Phase 4+ integration

- Cross-village pilgrimage via visitor flux.
- Kingdom-level high priest office.
- Religious conflict between opposed villages.

## Behavior contract

### Does

- Define religions with tenets/rites/calendars.
- PRIEST profession with rite officiation.
- Track per-NPC piety.
- Schedule and execute rites.
- Run temple treasury.
- Enable player participation.

### Does not

- Magic / divine intervention.
- Persecution / inquisition v1.
- Secret religions / underground practice.
- Validate theological doctrine consistency.

## Edge cases

- **No priest, rite needed.** Skip (COMING_OF_AGE) or defer up to
  14d (MARRIAGE).
- **Priest dies mid-rite.** DISRUPTED outcome; mood penalty.
- **Subject missing at own rite.** Cancelled; gossip.
- **Temple destroyed during rite.** Abandoned; priest relocates.
- **Two simultaneous requests.** Priest processes oldest first.
- **Atheist NPC (piety 0).** Doesn't attend; blessings have no effect.

## Ordering dependencies

Phase 3 depends on:
- Office framework (Phase 3) — village_priest.
- Memory (Phase 1) — OFFICIATED_BY memories.
- Mood (Phase 1) — rite triggers.
- Scribal (Phase 2) — religious books, scripture.
- Child/elderly arcs (Phase 2) — coming-of-age.
- Letters/books (Phase 2) — religious texts.
- Existing TEMPLE building type.

## Open decisions

- Cross-culture religion transmission. **Proposed: religion follows
  village-of-birth, not culture; migration preserves original at
  declining strength.**
- Confession privacy leak rate. **Proposed: 10% per confession per
  month if priest Honesty < −0.5.**
- Fundamentalism as trait? **Proposed: no separate trait; emerges
  from high piety + Honesty + culture.**
- Culture-gated execution for CAPITAL: religious or political?
  **Proposed: cultural, not religious. Religion can influence via
  priest advising.**

## Does-not-include

- Divine miracles / chosen one.
- Religious factions / schisms v1.
- Holy war / religious combat.
- Food restrictions beyond gift-preference modifiers.
- Prayer as explicit gameplay beyond rite structure.

## Revision Notes

(changes recorded here as the spec evolves after testing)
