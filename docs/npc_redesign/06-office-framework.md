# 06 — Office Framework

## Purpose

Every organization (kingdom, village, guild, company, temple) has a
defined slate of offices. Filling offices with competent holders grants
mechanical bonuses to the organization and unlocks management features
for whoever owns or leads the org. Vacant or incompetent offices quietly
degrade things.

Offices are the answer to "why do skills matter?" at the political and
organizational level. A village without a treasurer loses money to
mis-accounting; with a skilled one, tax collection is more efficient.
A kingdom council seat requires ascending through village leadership.
An elective guildmaster means campaigns, votes, and politics.

Warfare is out of scope for v1, so Marshal and military offices are
stubbed but not wired. Kingdom-level offices beyond King / Chancellor /
Treasurer are also deferred.

## Data model

### Organization types

```java
public enum OrgType {
    KINGDOM, VILLAGE, GUILD, COMPANY, TEMPLE;

    public static final Codec CODEC = ...;
}
```

### OfficeDefinition

Static per-office-type definition, shared across all instances:

```java
public record OfficeDefinition(
    String id,                           // "village_treasurer", "guild_master", etc.
    OrgType orgType,
    String displayName,
    List eligibleProfessions,// empty = any profession
    Map minSkillReqs,    // skill → min level required
    SelectionMethod defaultSelection,    // culture may override
    int termDays,                        // 0 = indefinite
    List powers,
    Competence competence,               // effect curve
    List vacancyPenalties        // see below
) { ... }
```

### Offices (v1 slate)

**Village:**
- `village_leader` — eligible: `[VILLAGE_LEADER]`, skills: `SOCIAL 30`;
  default selection: `HEREDITARY` (monarchic cultures),
  `ELECTIVE` (republic), `ASCENSION` (meritocratic);
  term: 365 days (elective) / indefinite (hereditary).
- `village_treasurer` — eligible: `[MERCHANT, SCRIBE]`, skills:
  `COMMERCE 30, LITERACY 30`; default: `APPOINTED` by leader;
  term: indefinite while competent.
- `village_constable` — eligible: `[GUARD]`, skills: `COMBAT 30, SOCIAL 20`;
  default: `APPOINTED` by leader; term: indefinite.
- `village_bailiff` — eligible: `[GUARD, SCRIBE]`, skills:
  `SOCIAL 25, LITERACY 25`; default: `APPOINTED`;
  term: indefinite. Enforces day-to-day laws.
- `village_scribe` — eligible: `[SCRIBE]`, skills: `LITERACY 60`;
  default: `APPOINTED`; term: indefinite.
- `village_priest` — eligible: `[PRIEST]`, skills: `SOCIAL 30, LITERACY 30`;
  default: `ASCENSION` (religious hierarchy); term: indefinite.
  Only present if village has a temple.

**Guild:**
- `guild_master` — eligible: matches guild type (e.g. `BLACKSMITH`,
  `CARPENTER` for craftsmen's guild); skills: primary skill of guild
  at 70+; default: `MERITOCRATIC` for most, `ELECTIVE` for adventurer;
  term: 730 days (2 in-game years) for elected, indefinite otherwise.
- `guild_treasurer` — eligible: `[MERCHANT, SCRIBE]`, skills:
  `COMMERCE 30`; default: `APPOINTED` by guildmaster.
- `guild_registrar` — eligible: `[SCRIBE]`, skills: `LITERACY 50`;
  default: `APPOINTED` by guildmaster.
- `master_of_apprentices` — eligible: matches guild profession at 60+
  skill; default: `MERITOCRATIC`; term: indefinite.

**Company:** (existing Company structure)
- `company_owner` — self-explanatory; no skill requirement (ownership
  is by investment, not appointment).
- `company_foreman` — eligible: matches company's primary producer
  profession, skill 50+; default: `APPOINTED` by owner.
- `company_bookkeeper` — eligible: `[MERCHANT, SCRIBE]`, skills:
  `COMMERCE 40, LITERACY 40`; default: `APPOINTED` by owner.

**Kingdom (stubbed in v1):**
- `kingdom_king` — selection varies by government form.
- `kingdom_chancellor` — appointed by king; coordinates other offices.
- `kingdom_treasurer` — appointed; runs kingdom-wide tax and budget.
- `kingdom_council_seat` — one per eligible village leader; used only
  for `FEUDAL_COUNCIL` government form.

**Temple (stubbed in v1):**
- `temple_high_priest` — eligible: `[PRIEST]`; selection by religion.

### Selection methods

```java
public enum SelectionMethod {
    MERITOCRATIC,   // highest eligible skill wins
    ASCENSION,      // must hold subordinate office first (see prereqs)
    COUNCIL,        // named pool elects internally
    ELECTIVE,       // all citizens vote; relationship-weighted
    HEREDITARY,     // passes to named heir; succession crisis if none
    APPOINTED,      // superior office-holder chooses
    DICTATORIAL;    // one office appoints all others; stability cost

    public static final Codec CODEC = ...;
}
```

### OfficePower

Granular capabilities the office grants its holder (player or NPC):

```java
public enum OfficePower {
    VIEW_BUDGET,
    SET_BUDGET,
    APPOINT_SUBORDINATE,
    ENACT_LAW,
    REPEAL_LAW,
    SET_TAX_RATE,
    ISSUE_DECREE,
    COMMAND_CITIZENS,
    ACCESS_TREASURY,
    INVESTIGATE_CRIME,
    TRY_ACCUSED,
    OFFICIATE_RITE,
    DISPATCH_CARAVAN,
    POST_REQUEST,
    ACCEPT_REQUEST,
    RECRUIT_APPRENTICE,
    CERTIFY_MASTERPIECE,
    BLESS,
    CURSE;
    // powers granted per-office via OfficeDefinition
}
```

### Competence curve

Each office defines how skill maps to effective contribution:

```java
public record Competence(
    Skill primarySkill,
    int minLevel,              // below this, office is "vacant quality"
    int effectiveAtLevel,      // full bonus kicks in at this level
    float maxBonus,            // max multiplier (e.g. 1.30 = +30%)
    float vacancyPenalty       // e.g. -0.10 = -10% when vacant or below min
) {
    public float computeMultiplier(int skillLevel) {
        if (skillLevel < minLevel) return 1f + vacancyPenalty;
        float t = (skillLevel - minLevel)
                / (float)(effectiveAtLevel - minLevel);
        t = Math.min(1f, t);
        return 1f + (maxBonus - 1f) * t;
    }
}
```

### OfficeHolding

Dynamic record of who holds an office right now:

```java
public record OfficeHolding(
    String officeId,
    UUID orgId,
    UUID holderNpcId,          // null if vacant
    UUID holderPlayerId,       // non-null if player holds
    long termStartTick,
    long termEndTick,          // 0 if indefinite
    SelectionMethod actualSelection  // recorded at election time
) {
    public boolean isVacant() {
        return holderNpcId == null && holderPlayerId == null;
    }
    public boolean holderIsPlayer() { return holderPlayerId != null; }
}
```

### OfficeRegistry and data

```java
public class OfficeRegistry {
    // Static — all office definitions registered at mod init
    public static OfficeDefinition get(String officeId);
    public static List forOrgType(OrgType type);
}

// Attached to VillageSavedData / KingdomSavedData / GuildData /
// CompanyData — one OfficeState per org instance.
public class OfficeState {
    private final Map holdings;

    public Optional get(String officeId);
    public void set(String officeId, OfficeHolding holding);
    public void vacate(String officeId);
    public Set allOffices();
    public boolean isHeldBy(UUID npcOrPlayerId);

    // Codec
}
```

## Selection algorithms (Phase 0 stubs)

Phase 0 implements the data structures and a naive `MERITOCRATIC`
fallback that picks the highest-skilled eligible NPC. The other
selection methods are implemented in Phase 3 where political behavior
actually runs.

Phase 0 selection stub:

```java
public static OfficeHolding selectMeritocratic(OfficeDefinition def,
                                               UUID orgId,
                                               List candidates,
                                               long now) {
    TownspersonMob best = candidates.stream()
        .filter(npc -> eligible(npc, def))
        .max(Comparator.comparingInt(npc -> relevantSkill(npc, def)))
        .orElse(null);
    if (best == null) {
        return new OfficeHolding(def.id(), orgId, null, null, now, 0, MERITOCRATIC);
    }
    long termEnd = def.termDays() == 0 ? 0 : now + def.termDays() * 24000L;
    return new OfficeHolding(def.id(), orgId, best.getUUID(), null,
                             now, termEnd, MERITOCRATIC);
}
```

## Player office rules

- Player can hold **one office per organization**, no more.
- Player can hold offices across different orgs simultaneously.
- Player holding an office unlocks its `OfficePower` set in the
  management UI (Phase 3 implements UI).
- Player can be **nominated** for elective offices and can **accept**
  appointment offers via a notification UI (Phase 3).

## Ascension prerequisites

For `ASCENSION` selection, offices define an ordered chain:

- `kingdom_king` ← `kingdom_council_seat` or `village_leader`
- `village_leader` ← (no prerequisite; ASCENSION via village politics)
- `guild_master` ← `master_of_apprentices` (for meritocratic guilds)
- `temple_high_priest` ← `village_priest`

Prerequisites stored in `OfficeDefinition`:

```java
List ascensionPrereqs;  // any of these offices held for termDays suffices
int ascensionMinHoldDays;       // must have held prereq for this long
```

## Competence effects by office (samples)

| Office | Primary skill | Min | Effective | Max bonus | Vacancy |
|---|---|---|---|---|---|
| village_leader | SOCIAL | 30 | 70 | 1.15 | −0.10 |
| village_treasurer | COMMERCE | 30 | 75 | 1.20 | −0.08 |
| village_constable | COMBAT | 30 | 70 | 1.15 | −0.12 |
| village_scribe | LITERACY | 60 | 90 | 1.25 | −0.05 |
| guild_master | (profession primary) | 70 | 95 | 1.30 | −0.15 |
| guild_treasurer | COMMERCE | 40 | 80 | 1.20 | −0.08 |
| company_bookkeeper | COMMERCE | 40 | 80 | 1.15 | −0.05 |

What the bonus actually modifies is office-specific and lives in the
office's effect hook (Phase 3). Examples:

- `village_treasurer` bonus → tax collection efficiency
- `village_leader` bonus → reputation decay rate, diplomatic speed
- `guild_master` bonus → cross-village request success rate
- `company_bookkeeper` bonus → reduction in accounting-loss events

Vacancy penalties are negative multipliers on the same effects.

## Vacancy-specific incidents

Certain offices produce scripted incidents when vacant or held by an
incompetent (level just above minimum):

- `village_treasurer` vacant → 1–3% of treasury "lost to accounting"
  monthly (ghost expense, not routed to any NPC)
- `village_constable` vacant → crime detection rate 50%, rumors of
  lawlessness spread, reputation hit
- `village_scribe` vacant → no new letters produced by village NPCs
  unless they travel to another village
- `village_priest` vacant → religious rites can't be officiated,
  festivals lose bonuses

These incidents live in their respective subsystem docs (crime,
religion, etc.) and are referenced here for context.

## Persistence

Offices state attaches to the owning org's saved data:

- Village offices → `VillageSavedData` adds `Map<UUID, OfficeState>`
  keyed by village ID.
- Guild offices → `GuildData` / future `AbstractGuild` gains
  `OfficeState` field.
- Company offices → `Company` gains `OfficeState` field (coexists with
  existing worker/role data).
- Kingdom offices → `Kingdom` gains `OfficeState` field.

NBT keys under `offices` on each org:
offices: {
holdings: [
{
officeId: "village_treasurer",
orgId: "uuid-of-village",
holderNpcId: "uuid-of-npc",
holderPlayerId: null,
termStartTick: 100000L,
termEndTick: 0L,
actualSelection: "APPOINTED"
},
...
]
}

## Integration points

### Phase 0 integration (storage + static definitions)

- Create `OfficeRegistry` populated at mod init with all v1 office
  definitions.
- Add `OfficeState` field to `Village`, `Kingdom`, `Guild`,
  `Company`. Persist via extension of existing codecs (backward
  compatible with `optionalFieldOf`).
- Implement `selectMeritocratic` stub for initial population.
- On village/guild/company creation, run meritocratic auto-selection
  to fill offices. Most fill immediately since candidates exist.
- `/office` debug command:
    - `/office list <orgType> <orgId>`
    - `/office holder <officeId>`
    - `/office force-select <officeId>` — recompute via meritocratic

Phase 0 does not wire any `OfficePower` or competence effects — the
state exists but doesn't affect gameplay yet. A village treasurer
is identified but doesn't yet change tax behavior.

### Phase 3 integration

- Implement the remaining selection methods (`ASCENSION`,
  `ELECTIVE`, `COUNCIL`, `HEREDITARY`, `APPOINTED`, `DICTATORIAL`).
- Wire competence multipliers into each office's effect hook (tax
  collection, request processing, etc.).
- Implement vacancy-specific incidents.
- Management UI: each office grants its holder (player or NPC) a
  page in a management screen. Player sees their held offices and
  can take `OfficePower` actions.
- Ascension tracking: days-held-prereq accumulates and triggers
  eligibility.

### Phase 3 + culture (government forms)

Cultures define default selection methods for top offices. A
`TRADITIONAL_MONARCHY` culture uses `HEREDITARY` for king; a
`MERIT_REPUBLIC` uses `ELECTIVE`. When a village/kingdom changes
culture dominance (Phase 4+), pending office terms complete under the
old method; new terms use the new method.

## Behavior contract

### Does

- Define static office slates per org type.
- Store which NPC/player currently holds each office.
- Run meritocratic auto-selection at org creation (Phase 0 stub).
- Support term end detection for elected offices.
- Expose `OfficeState` queries for other subsystems.

### Does not

- Drive gameplay effects in Phase 0. Competence and powers wire in
  Phase 3.
- Implement elections, campaigning, ascension logic, or inheritance
  in Phase 0.
- Generate office-holder change events in Phase 0 — just silent
  auto-fill.
- Support custom offices per org instance. All orgs of a type share
  the same slate.

## Edge cases

- **No eligible NPC for an office.** Holding stays vacant. Vacancy
  penalties apply. Player can sometimes take the office if eligible
  (profession + skill requirements).
- **Current holder dies or leaves profession.** Office vacates; next
  tick, meritocratic stub reselects. In Phase 3, the proper selection
  method runs.
- **Multiple eligible candidates tied on skill.** Tiebreak: higher
  Ambition, then older age, then UUID order. Deterministic.
- **Player moves out of profession (e.g. resigns).** Office vacates.
  Triggers notification to the org.
- **Culture changes mid-term.** Current holder completes term; next
  selection uses new culture's method.

## Ordering dependencies

Phase 0 scope depends on:
- `Skill` enum (`05-skill-system.md`) — for eligibility queries.
- Existing `Profession` enum — for eligibility.
- Existing org saved data classes (`Village`, `Kingdom`, `Guild`,
  `Company`) — for state attachment.

Phase 3 consumers depend on this subsystem stable + mood / relationship
for elective voting weights.

## Open decisions

- Should office state be visible in the NPC profile GUI? E.g. if an
  NPC is the village treasurer, their profile shows "Treasurer of
  Oakford". **Proposed: yes, minor field in Work panel.** Implement
  in Phase 0.
- Should offices pay salaries? Currently wages are per-profession in
  `VillageTreasury`. Offices could grant an additional stipend. **Proposed:
  defer to Phase 3 where budget UI is wired.**
- For ascension, should time held in prereq office reset when the NPC
  leaves? **Proposed: reset to 0. "Clean break" semantic.**

## Does-not-include

- Military offices (Marshal, captain, etc.). Deferred with warfare.
- Inter-kingdom offices (ambassador, etc.). Deferred.
- Offices that can be held by institutions rather than individuals.
  Offices are always held by an NPC, player, or vacant.
- Impeachment / removal mechanics. Phase 3 political drama may add
  this; not in v1.
- Player-designed custom offices. Fixed slate.

## Revision Notes

(changes recorded here as the spec evolves after testing)