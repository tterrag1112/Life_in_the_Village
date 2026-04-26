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

### 2026-04-23 — Phase 0 implementation (task 06)

Implementation landed in `tterrag1112.life_in_the_village.Npc.Office`
(`OrgType`, `SelectionMethod`, `OfficePower`, `Competence`,
`OfficeDefinition`, `OfficeHolding`, `OfficeState`, `OfficeRegistry`).
Pattern parallels prior Phase 0 components.

**OfficeId shape — registry-backed strings.** Spec uses string IDs
plus a static `OfficeRegistry` (line 196). Implementation matches:
`OfficeRegistry` is a class with `Map<String, OfficeDefinition>` and
public string constants for each known id (`VILLAGE_LEADER`,
`GUILD_MASTER`, etc.) so call sites are still type-checked at compile
time. Lazy-init on first access; idempotent.

**Record name.** Spec calls the dynamic record `OfficeHolding`; the
task prompt template called it `OfficeHolder`. Used spec name. Two
factory methods on the record (`heldByNpc`, `heldByPlayer`, `vacant`)
keep call sites readable since the holder UUIDs use
`Optional<UUID>` to disambiguate vacant / NPC / player.

**Multi-seat (LOCKED for v1).** Spec line 96–98 lists
`kingdom_council_seat` as multi-seat (one seat per village leader)
but flags it "stubbed in v1". `OfficeState` uses
`Map<String, OfficeHolding>` (single-seat per id). Phase 3 extends
multi-seat by either (a) mangling per-seat ids
(`kingdom_council_seat:&lt;villageId&gt;`) or (b) promoting the
value type to `List<OfficeHolding>`; either is non-breaking because
nothing reads council seats in Phase 0. Documented on `OfficeState`
class.

**Attachment shape (per spec section "Persistence" line 308).**
Each org instance owns its own `OfficeState`:
- `Village` — instance field, codec extended with `optionalFieldOf("offices")`.
- `GuildData` — record's 5th field; codec extended; immutable wrappers
  (`withRefresh`, `withGuildmaster`) preserve the offices reference.
  A 4-arg compatibility constructor delegates to the new 5-arg
  canonical constructor so existing call sites compile unchanged.
- `Company` — instance field, codec extended with optional offices.
  Constructor pre-seeds `company_owner` with the player owner UUID.
- `Kingdom` — instance field, codec extended.

Village offices are NOT held in a `Map<UUID, OfficeState>` on
`VillageSavedData` — the spec's wording suggests that shape, but
since `Village` is itself an instance class with its own codec,
attaching directly to `Village` is cleaner and avoids a parallel
data structure. Functionally identical for queries by villageId.

**Temple offices — registered but unattached.** Spec line 100
"Temple (stubbed in v1)" — there is no per-temple data class in the
codebase (only `BuildingType.TEMPLE` and plain `Building` records).
`temple_high_priest` is registered in `OfficeRegistry` so the
definition exists, but no entity hosts an `OfficeState` for it yet.
The `findOfficesHeldBy` walker therefore never returns matches in
the TEMPLE bucket. When Phase 3 lands a per-temple instance class
(or chooses to attach to the host `Village`), point `OfficeState`
there and the office becomes live. Docs/spec/Phase3 to decide.

**Spawn-time pre-population.** Per task prompt (and consistent with
prior sessions' empty-on-spawn pattern) — every org's
`OfficeState` is pre-populated as vacant via `OfficeState.emptyFor(orgType, orgId)`
when the org is constructed. The spec's "auto-fill via meritocratic"
behaviour at construction is deferred to Phase 3 along with the
selection-engine logic; this avoids needing to query candidate NPCs
at construction time, which is awkward inside codec deserialisation.

**Cross-entity walker (`findOfficesHeldBy`).** Iterates every
Village (via `VillageSavedData.getAllVillages`), GuildData (via
`getAllGuilds` — newly added accessor), Kingdom (via
`getAllKingdoms`), and Company (via `CompanySavedData.getAllCompanies`).
At v1 scale (low hundreds of orgs total) this is sub-millisecond
work; documented on the method.

**Profession eligibility substitutions.** Spec eligibility lists
reference `SCRIBE` and `HEALER` professions that aren't in the
current `Profession` enum (those land in Phase 2). Substitutions:
- `village_treasurer` — spec `[MERCHANT, SCRIBE]` → impl
  `[MERCHANT, SCHOLAR]`.
- `village_bailiff` — spec `[GUARD, SCRIBE]` → impl `[GUARD]`.
- `village_scribe` — spec `[SCRIBE]` → impl `[SCHOLAR]`.
- `guild_treasurer` — spec `[MERCHANT, SCRIBE]` → impl `[MERCHANT]`.
- `guild_registrar` — spec `[SCRIBE]` → impl `[SCHOLAR]`.
- `company_bookkeeper` — spec `[MERCHANT, SCRIBE]` → impl `[MERCHANT]`.

Offices with empty eligibility (e.g. `guild_master` —
guild-type-specific, decided in Phase 3) just stay vacant in v1.

**Migration on load.** Each entity's codec deserialiser checks for
a stored `OfficeState`; if absent (legacy save), creates an empty
state and seeds the appropriate office from the legacy field:
- `Village.villageLeaderId` → `village_leader` NPC holding,
  `actualSelection = ASCENSION`.
- `GuildData.guildmasterId` (skipping the `00000000-...` sentinel) →
  `guild_master` NPC holding, `actualSelection = MERITOCRATIC`.
- `Kingdom.rulerPlayerId` (player) or `rulerEntityId` (NPC) →
  `kingdom_king` holding, `actualSelection = HEREDITARY`.
- `Company.ownerPlayerId` is seeded by the constructor (so it's
  populated for both fresh and loaded companies). The codec's
  `fromCodec` overrides with stored offices when present.

Migration tick fields are zeroed (`termStartTick = 0L`, `termEndTick = 0L`)
since the original tick of appointment is unknown. Phase 3 selection
logic should treat `termStartTick == 0L` as a migration-marked
holding if it cares.

**Player vs NPC holder.** `OfficeHolding` carries
`Optional<UUID> holderNpcId` AND `Optional<UUID> holderPlayerId` —
exactly one set when held, both empty when vacant. The
`/office set` debug command picks player vs NPC by checking whether
the supplied UUID matches an online player at command time;
documented on the handler. Phase 3 will likely add an explicit
`/office set-player` / `/office set-npc` form once selection methods
ship.

**Debug command split.** `/npc offices <uuid>` lives on the
shared `/npc` root (consistent with `/npc traits|memory|knowledge|mood|skills`).
The org-side commands (`info / set / vacate / list-all`) live under
a new `/office` root in `OfficeDebugCommand`, registered in
`ModModEvents.onRegisterCommands` alongside the other commands.

**Not implemented in this session (deferred per spec / Phase 0 scope):**
- Selection-method logic for everything except a stubbed
  `MERITOCRATIC` placeholder. Phase 3 implements all 7 methods.
- Office-power consumption (no behaviour reads `OfficePower`).
- Term-end firing / auto-vacate. Phase 3.
- Vacancy-specific incidents (e.g. treasurer vacant → tax
  losses). Phase 3.
- Profile-GUI display of "Treasurer of Oakford"-style office tags.
  UI deferred consistent with prior Phase 0 sessions.
- Wiring existing leadership-driven code (pay flows, kingdom
  mandates, etc.) to read from `OfficeState`. Legacy fields
  stay readable; Phase 3 cuts over.

**Prompt↔spec naming mismatches (for the prompt-template
maintainer):**
- Prompt's `OfficeId` enum vs spec's string IDs + registry.
  Used spec.
- Prompt's `OfficeHolder` vs spec's `OfficeHolding`. Used spec.
- Prompt's `OfficeSelectionMethod` enum vs spec's
  `SelectionMethod` enum. Used spec.
- Prompt's `village_healer` office vs spec's slate (no
  `village_healer`). Used spec slate.
- Prompt's `OfficeState.emptyFor(entityType)` vs my
  `OfficeState.emptyFor(orgType, orgId)`. The orgId is needed so
  pre-populated holdings carry the correct `orgId` reference;
  documented on the static factory.

### 2026-04-26 — Phase 3 wiring session (task 06)

Selection / lifecycle / power-grant layer landed in
`tterrag1112.life_in_the_village.Npc.Office.Selection`,
`tterrag1112.life_in_the_village.Npc.Office.Powers`, plus
`OfficeElection` and `CultureSelectionResolver` at the package root.

**Council composition (spec "Things to flag" #2 resolved).**
- `village_leader` → `FamilyRole.HEAD` of NPCs assigned to the village.
- `guild_master` / `master_of_apprentices` → adults assigned to the
  guild's village whose profession is GUILDMASTER, GUILDWORKER, or
  ADVENTURER. Phase 2 task 27's `AbstractGuild` refactor will replace
  this with explicit membership records.
- `kingdom_king` / `kingdom_council_seat` → village leaders of every
  village in the kingdom. Reads `OfficeState.village_leader` and falls
  back to legacy `villageLeaderId` during the migration window.
- All other office IDs route to MERITOCRATIC fallback when COUNCIL is
  selected.

**Ascension chains (spec "Things to flag" #1 resolved).** v1 only
populates `guild_master ← master_of_apprentices` (registry already
carried that). `village_leader` ASCENSION with an empty prereq list
falls back to MERITOCRATIC. `bailiff → leader` chain is a one-line
registry change in a follow-up if playtesting shows it's needed.

**Cultural rules stub (spec "Things to flag" #3 resolved).**
`CultureSelectionResolver.cultureSelectionFor(culture, officeId)`
always returns `Optional.empty()` in Phase 3. The call site
(`OfficeElection.resolveMethod`) is permanent; Phase 5 only has to
populate the resolver.

**Legacy migration cutover (spec "Things to flag" #4 resolved).** The
*gates* migrated this session are
- `KingdomActionPacket.TOGGLE_LAW` → `PowerGrant.hasPower(...,
  OfficePower.ENACT_LAW, kingdom)`,
- `KingdomLawEffects.isCitizen` → `kingdom.getOffices().isHeldBy(
  playerId)`.

The legacy fields (`Kingdom.rulerPlayerId`, `Village.villageLeaderId`,
`GuildData.guildmasterId`, `Company.ownerPlayerId`) stay populated for
the one-release window but no longer drive any permission decision.
Other read sites (UI labels, history-text generators) keep using the
legacy field — they're cosmetic and the values stay synced via the
codec migration.

**MeritocraticSelection trait nudges.** `+5 * Industry +
3 * Honesty` per spec line "scores candidates by relevant skill +
traits". Per-office trait preferences (Courage for constable, Honesty
for treasurer, Compassion for priest, Ambition for guild_master /
kingdom_king / kingdom_chancellor, Sociability for village_leader)
live in `ElectiveSelection.officeTrait()` instead — meritocratic stays
generic so its score remains predictable across every office.

**Vote weight formula (CouncilSelection / ElectiveSelection).** Each
voter casts `max(1, npcRelScore + 50)` for each candidate (range
−100..+100 → 1..150). Floor at +1 keeps unfamiliar candidates from
getting a 0 vote; relationship still dominates above the floor.
Tiebreak adds `+0.001 × primarySkillLevel` so a tied vote picks the
more competent candidate.

**HereditarySelection succession crisis.** When no eligible adult
child exists, falls back to COUNCIL → MERITOCRATIC. Recorded
`actualSelection` becomes the actual fallback used (not HEREDITARY)
so history reads correctly.

**AppointedSelection appointer chain.** village_leader → treasurer /
constable / bailiff / scribe; guild_master → guild_treasurer /
guild_registrar; company_owner → company_foreman /
company_bookkeeper; kingdom_king → kingdom_chancellor /
kingdom_treasurer. When the appointer is a player, the silent engine
declines and waits for `appoint_to_office` verb.

**Founding-elections queue.** `VillageSavedData.addVillage` enqueues
the village id; the daily `office_elections` tick subsystem drains
the queue at the start of each pass, then runs the standard vacancy
/ term-end sweep. NBT load uses `villages.addAll` directly,
bypassing `addVillage`, so reload paths don't re-run founding
elections.

**Office Tab UI deferral.** v1 ships `/office me` as the menu
surface — a real GUI tab needs new `OpenOfficeStatus` packet + screen
plumbing, which is heavier than the per-power UIs that are
themselves Phase 3 follow-up sessions. The proper inventory tab is a
follow-up so this session stayed focused on the selection /
lifecycle work the rest of Phase 3 depends on.

**Resign verb scope.** `resign_from_office` vacates ONLY the office
on the targeted NPC's village, not every office the player holds.
The first cut called `OfficeElection.vacateAllHeldBy` directly —
that was too aggressive (kingdom + guild offices wiped via a village
dialogue). Current implementation manually vacates one slot and
immediately re-elects.

**Build verification deferred.** The sandbox can't reach
`maven.neoforged.net` (HTTP 403, `host_not_allowed`). Code review
covered imports / signatures / null-paths but the exit-criteria
scenario (founding election, leader-death refill, term-end vacate,
`/office powers` listing, save/reload) needs to run on a dev box
before the wiring is considered validated.