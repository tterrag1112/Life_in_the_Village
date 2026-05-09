# KINGDOM REWORK PROGRESS

Append-only log for Track D (kingdom rework). Most recent entry at
the bottom. Use this doc for per-phase detail; the table in
`UNIFIED_REWORK_PROGRESS.md` mirrors the headline state.

Status values: `Not-Started`, `In-Progress`, `Implemented`, `Tested`,
`Done`. `Done` means: implemented, tested in-world, no known issues,
spec matches reality.

---

## D1 — Phase 0 bridge: kingdom-tier scaffolding

### 2026-05-09 — Track D1 landed

**Phase:** Track D1 (Phase 0) per `UNIFIED_REWORK_PLAN.md` lines
222–241. Conservative first step on Track D — adds the scaffolding
the kingdom rework will need (sub-bundle on Culture, peer event bus,
new scalars + heraldry on Kingdom, explicit Village.kingdomId
membership pointer, estate primitive type, kingdom-tier office
stubs) without committing to any behaviour change. Existing kingdom
code (including the orphan `Kingdom/Castle/` package) stays untouched.

#### Disposition before code

Investigation confirmed:

- `Cultures.Culture` is a 14-field record at the 16-field DFU
  ceiling. Adding a 15th sub-bundle is fine.
- `Kingdom` is a mutable class (not record), codec-driven persistence.
  Already has id / name / culture / villageIds / ruler / treasury /
  relations / activeLaws / offices / history / territorialClaim. **No
  stability / legitimacy fields today** — D1 adds them cleanly.
- Membership is **list-on-Kingdom** (`villageIds: List<UUID>`); no
  reverse field on Village. Migration walks each Kingdom's
  villageIds and stamps `Village.kingdomId`.
- `NpcLifeEventBus` lives at `Npc.Events.NpcLifeEventBus` with
  synchronized DISPATCHERS list, EVENT_COUNTS, LISTENERS, and
  register / registerDefaults / fire / fireBatch surface.
  `KingdomEventBus` mirrors at `Kingdom.Events.KingdomEventBus`
  (sibling-style, NOT nested).
- Office registry already exists. `OfficeRegistry.registerKingdomOffices()`
  registers KING, CHANCELLOR, TREASURER, COUNCIL_SEAT today. D1
  adds the five missing kingdom offices: SCHOLAR, GENERAL,
  MAGISTRATE, SPYMASTER, DIPLOMAT.
- `Profession.java` already has CHANCELLOR + KINGDOM_RULER. D1 adds
  GENERAL, MAGISTRATE, SPYMASTER, DIPLOMAT (SCHOLAR already exists).
- `BuildingType.java` is a small enum; adding `ESTATE` is one line.
- `Kingdom/Castle/` is 20 files of procedural castle generation,
  entirely separate from kingdom membership / office framework /
  culture. Out of scope for D1; not touched.

#### Decisions (user-confirmed)

**a) Kingdom-tier sub-bundle (`CultureKingdomDefaults`):** record
with `nobilityRanks: List<String>` (low→high), `successionRule: enum`,
`subdivisionModel: enum`, `upkeepMix: Map<UpkeepSource, Double>`,
`requiredOffices: List<String>`. Codec at
`CultureBundles.CultureKingdomDefaults.CODEC`.

**b) Per-culture defaults:**
| Culture | Subdivision | Succession | Nobility ranks | Upkeep mix |
|---|---|---|---|---|
| default | PROVINCES | PRIMOGENITURE | Knight/Baron/Count/Duke | 40/20/20/20 |
| plainfolk | TRIBAL_CONFEDERATION | COUNCIL | Yeoman/Headman/Elder/Chief | 10/10/60/20 |
| highmarch | DUCHIES | AGNATIC_PRIMOGENITURE | Knight/Baron/Marquis/Duke | 30/40/20/10 |
| silkwood | CITY_STATE_LEAGUE | ELECTIVE | Citizen/Magnate/Senator/Archon | 20/10/10/60 |
| tidereach | PROVINCES | ELECTIVE | Steward/Reeve/Magistrate/Princeps | 30/15/15/40 |

**c) `KingdomEventBus` event taxonomy:** eight initial event types —
`KingdomFounded`, `KingdomDissolved`, `RulerSucceeded`,
`OfficeFilled`, `OfficeVacated`, `VillageJoined`, `VillageLeft`,
`ScalarShifted` (combined stability + legitimacy event). Diplomatic
events (treaties, war, vassalage) deferred to D3 once relations
become interactive.

**d) Stability + legitimacy semantics:** both 0–100 ints, default
75. Bands: 0–24 CRISIS, 25–49 STRAINED, 50–74 STABLE, 75–100 SECURE.
`Kingdom.bandOf(score)` returns the band. Drivers (intent for D3):
stability ← treasury health + recent crime + neighbouring war;
legitimacy ← culturally-correct succession + held offices + holy
endorsement.

**e) Membership migration:** `KingdomMembershipMigration.migrateIfNeeded`
walks each kingdom's `villageIds` list, stamps `Village.setKingdomId`
for every member. Idempotency flag at
`VillageSavedData.kingdomMembershipMigrated`. Mirrors
`SeaRouteMigration` (Track C3.3) shape — peek + work + flag-last-write
so a partial migration reruns next load. Fresh worlds default flag
true; pre-D1 saves arrive false.

**f) Estate primitive choice:** new `BuildingType.ESTATE` enum
value (one line). Justification: `BuildingType` is the existing
"spatial role" axis. Sub-records on `Building` are reserved for
cross-cutting concerns (footprint, condition). A standalone
`Estate` peer to `Village` would create an orthogonal placement
system. ESTATE-as-BuildingType lets D3 use the existing
layout-recipe machinery to place estates the same way it places
NOBLE_MANOR.

**g) Heraldry record shape:** minimal stub — `field: Tincture`,
`chargeColour: Tincture`, `primaryCharge: Charge`, `layout: Layout`.
Tincture: 7 traditional (OR / ARGENT / GULES / AZURE / SABLE /
VERT / PURPURE). Charge: 10 stub options (LION, EAGLE, CROSS, SUN,
MOON, CROWN, SWORD, TOWER, FLEUR_DE_LIS, OAK). Layout: 4 (PLAIN,
PARTY_PER_PALE, PARTY_PER_FESS, QUARTERED). ~1960 unique
combinations per culture. `HeraldryGenerator.generate(culture,
kingdomId, foundingSeed)` is deterministic — same inputs, same
output, every load. Same-tincture-on-same-tincture results are
defensively avoided (would render invisible).

**h) Office registration:** five new
`OfficeRegistry.register(...)` calls in `registerKingdomOffices()`
(SCHOLAR, GENERAL, MAGISTRATE, SPYMASTER, DIPLOMAT) using the same
pattern as KINGDOM_CHANCELLOR / KINGDOM_TREASURER. Office IDs are
`kingdom_<role>` matching the existing namespace. Profession enum
gains four new values (GENERAL, MAGISTRATE, SPYMASTER, DIPLOMAT —
SCHOLAR + CHANCELLOR + KINGDOM_RULER + MERCHANT already existed).
ProfessionSkills gains stub entries for the four new professions
so any code that walks the map doesn't trip on missing keys.

#### Files added

- `Kingdom/Heraldry.java` — record + Tincture/Charge/Layout enums + codec.
- `Kingdom/HeraldryGenerator.java` — deterministic xorshift hash from
  `(culture, kingdomId, foundingSeed)` → Heraldry.
- `Kingdom/KingdomMembershipMigration.java` — one-shot back-fill of
  `Village.kingdomId` from each `Kingdom.villageIds`.
- `Kingdom/Events/KingdomEvent.java` — sealed taxonomy of 8 record
  subtypes.
- `Kingdom/Events/KingdomEventDispatcher.java` — listener interface.
- `Kingdom/Events/KingdomEventBus.java` — synchronous dispatch hub
  mirroring `NpcLifeEventBus`.
- `Commands/KingdomDebugCommand.java` —
  `/litv kingdom debug describe <name>`, `list`, `events_stats`.
- `KINGDOM_PROGRESS.md` (this file).

#### Files modified

- `Cultures/CultureBundles.java` — `CultureKingdomDefaults` record
  + DEFAULT + codec; `SuccessionRule`, `SubdivisionModel`,
  `UpkeepSource` enums.
- `Cultures/Culture.java` — 15th field `kingdomDefaults` with codec
  optionalFieldOf default.
- `Cultures/CultureRegistry.java` — per-culture
  `CultureKingdomDefaults` instances for plainfolk / highmarch /
  silkwood / tidereach (default culture uses `DEFAULT`).
- `Kingdom/Kingdom.java` — `stability` + `legitimacy` ints,
  `heraldry` field, `Kingdom.ScalarBand` enum, `bandOf` helper,
  constructor generates heraldry deterministically; `fromCodec`
  back-fills heraldry for pre-D1 kingdoms whose codec value is
  `Heraldry.UNKNOWN`.
- `Village/Village.java` — `kingdomId: Optional<UUID>` field +
  codec `optionalFieldOf("kingdomId")` + `getKingdomId` /
  `setKingdomId` / `clearKingdomId` accessors.
- `Networking/VillageSavedData.java` — `kingdomMembershipMigrated`
  flag + accessors + codec wiring; new `peekLegacySeaRoutes` /
  `clearLegacySeaRoutes` pattern reused for the kingdom migration
  via `getAllKingdoms()` (no clear needed; the migration just
  stamps Village.kingdomId in place).
- `Profession/Profession.java` — four new values: GENERAL,
  MAGISTRATE, SPYMASTER, DIPLOMAT.
- `Npc/Skills/ProfessionSkills.java` — stub skill mappings for the
  four new professions.
- `Npc/Office/OfficeRegistry.java` — five new office IDs +
  `register(...)` calls in `registerKingdomOffices()`.
- `Village/Buildings/BuildingType.java` — `ESTATE` enum value.
- `Events/ServerTickDispatcher.java` — calls
  `KingdomMembershipMigration.migrateIfNeeded` once on first tick
  (alongside the existing `SeaRouteMigration.migrateIfNeeded` call).
- `Events/ModModEvents.java` — `KingdomEventBus.registerDefaults()`
  (no-op in D1) called alongside `NpcLifeEventBus.registerDefaults()`.

#### Wired but inert

Everything new in D1 is **wired but inert**:
- Culture defaults persist on the record but no consumer reads them.
- Stability / legitimacy persist on Kingdom but no driver loops.
- Heraldry is stored and back-filled but nothing renders it.
- KingdomEventBus accepts subscribers and fires events but D1 ships
  zero subscribers (no behaviour driven by a kingdom event yet).
- Village.kingdomId is back-filled at load but no D1 code queries
  it (legacy `Kingdom.villageIds` remains canonical).
- `BuildingType.ESTATE` exists but no spawn rule, no inhabitant
  populator, no structure JSON.
- Five new kingdom offices registered but no goal class, no
  workplace binding, no NPC ever spawns with one of the four new
  professions.

#### What's deferred to D2 / D3

- **D2 (Section 5 rewrite):** kingdom-tier schema fields,
  villageSlot integration, capital-emits-claim wiring. Reads
  CultureKingdomDefaults and writes to `Kingdom.subdivisionModel` etc.
- **D3:** stability / legitimacy decay drivers; kingdom-tier
  office population; estate placement; succession-rule-driven
  ruler transitions; KingdomEventBus subscribers (rep / history /
  scalar drivers); culture-required-offices enforcement.

#### Items deferred to Track E

- Heraldry rendering pipeline (banners, shield items, GUI display).
- Treaties / war / vassalage event types and their drivers.
- Kingdom-tier UI (book screen for the ruler, like Phase 11's
  `ROAD_ENGINEER_PLANS`).
- Kingdom-merge / kingdom-split workflows.
- Renaming / restructuring `kingdom_king` (left as-is).

#### Test plan (manual)

1. **Existing-save migration.** Load a save with active kingdoms.
   On first tick:
   - `[KingdomMembershipMigration] complete — kingdoms=N stamped=M
     collisions=0 heraldryBackFilled=K` log line appears.
   - `/litv kingdom debug list` shows all kingdoms.
   - `/litv kingdom debug describe <name>` lists members under both
     "legacy list" and "Track D1 reverse pointer" sections; counts
     match.
   - Heraldry no longer reads `UNKNOWN` for any kingdom (back-fill
     succeeded).
2. **Fresh-world test.** Found a new kingdom via existing
   gameplay flow:
   - Kingdom record gets generated heraldry (constructor path).
   - Stability + legitimacy default to 75 (SECURE band).
   - `/litv kingdom debug describe <name>` lists the eight registered
     offices (KING, CHANCELLOR, TREASURER, COUNCIL_SEAT,
     SCHOLAR, GENERAL, MAGISTRATE, SPYMASTER, DIPLOMAT).
3. **Codec round-trip.** Save → restart server → load → save. The
   second save's NBT diff against the first is empty (modulo
   tick counters).
4. **No behaviour change.** Caravans, road maintenance, NPC
   schedules, decoration cycles, V2 village spawning all work
   exactly as before D1. Watch a fresh village spawn end-to-end;
   no new log entries beyond the migration line.
5. **`/litv kingdom debug events_stats`** prints "no dispatchers
   registered (D1: bus is live, no subscribers)". Confirms the bus
   exists and is reachable.

#### Compile status

Gradle network-blocked in this sandbox. Static review confirmed:
- Culture record arity = 15 (within DFU 16 cap).
- Kingdom codec arity = 14 (within DFU 16 cap).
- VillageSavedData codec adds one field (within DFU cap).
- `BuildingType.ESTATE` doesn't break any existing exhaustive
  switch; all `switch (BuildingType)` sites use `default ->`
  fallthrough.
- `Profession` switches use `default ->` so new enum values get
  default-cased display names ("General", "Magistrate", etc.).
- `OfficeRegistry.registerKingdomOffices` arity unchanged at the
  switch level; just five additional `register(...)` statements.
- All new types have codecs with `optionalFieldOf` defaults so
  pre-D1 saves load cleanly.
- `KingdomEventBus` is a peer (sibling-level) of
  `NpcLifeEventBus` at `Kingdom.Events.KingdomEventBus`, NOT
  nested or subclass.
- Migration is idempotent: gated by
  `VillageSavedData.kingdomMembershipMigrated`, runs at first
  server tick, sets the flag last so a partial migration reruns.

---

## D2 — Section 5 rewrite for V2 vocabulary

### 2026-05-09 — Track D2 landed (doc-only)

**Phase:** Track D2 per `UNIFIED_REWORK_PLAN.md`. KINGDOM_PLAN
Section 5 (lines 248–300 pre-D2) is rewritten in V2 vocabulary
so D3 phases 1–7 can reference it directly without further
translation.

**No code changes.** Repo `git diff` for this commit touches
Markdown / text files only: `docs/KINGDOM_PLAN.md` (Section 5
body + appendix), `UNIFIED_REWORK_PROGRESS.md` (D2 row + activity
log), `KINGDOM_PROGRESS.md` (this entry).

#### Disposition before code

- KINGDOM_PLAN Section 5 lives at `docs/KINGDOM_PLAN.md` lines
  248–300 (pre-D2). Boundaries clean: opens "What the placement
  rework MUST include for kingdoms"; closes before Section 6.
  Sub-headings: `Slot primitives`, `VillageTypeData schema
  reservations`, `Spacing parameter`, `Determinism contract`,
  `Robustness slices`. Seven slots and seven schema fields
  exactly match the prompt's scope list.
- V2 vocabulary surveyed:
  - `Inclination` enum (`Village/Planning/V2/Inclination.java`)
    — six values (AGRICULTURAL, INDUSTRIAL, DEFENSIVE, CIVIC,
    RESIDENTIAL, SACRED). Capped at 6 deliberately. **No
    AUTHORITY axis.**
  - `Category` enum (`Village/Planning/V2/Layer3/Category.java`)
    — sixteen values including `CIVIC_AUTHORITY`. The
    "AUTHORITY" the prompt referenced maps to this Category at
    the manifest layer, not to a new Inclination axis.
  - `Provides` and `Requires` records on `PlacementProfile`.
    `(category, capacity)` and `(category, amount, tradeable)`
    shapes. Already used by TOWN_HALL → `Provides(CIVIC_AUTHORITY,
    1)`.
  - `AdjunctPlotRegistry` bindings (3×3 to 8×8 plots attached
    to a parent building; existing example: `NOBLE_MANOR →
    [FORMAL_GARDEN, STABLE_PADDOCK]`).
  - `SubBuildingType` enum (`docs/decoration_redesign/03-subbuildings.md`)
    — eight current values (STALL, APARTMENT, SHOP, ARCHIVE,
    INN_ROOM, WORKSHOP, CHAPEL_ROOM, CELLAR). **No
    AUDIENCE_CHAMBER / TREASURY_VAULT today.**
  - D1 `CultureKingdomDefaults`
    (`Cultures/CultureBundles.java`) — ships with five fields
    (`nobilityRanks`, `successionRule`, `subdivisionModel`,
    `upkeepMix`, `requiredOffices`). The Section 5 rewrite
    identifies six additional fields needed before D3 phase 1.
- `BuildingType` already has CASTLE, NOBLE_MANOR, TREASURY,
  CHANCELLERY, ESTATE (D1). **Missing**: PALACE, AUDIENCE_HALL,
  CEMETERY, FESTIVAL_GROUND.

#### Decisions (user-confirmed)

**Q1 TREASURY → keep `BuildingType.TREASURY`.** The "queryable
position in capital-tier villages" V1 semantic is met by
`VillageSavedData.getBuildingsOfType(TREASURY)`. Capital-tier
village layouts declare TREASURY as required.
`SubBuildingType.TREASURY_VAULT` interior detail inside the
CASTLE NBT is reserved for stylistic refinement; not required
for D3.

**Q2 PALACE / AUDIENCE → CASTLE variant + deferred SubBuilding.**
PALACE is a stylistic NBT pack consumed by `BuildingType.CASTLE`
— culture-specific castle NBTs already exist via the structure
availability registry. AUDIENCE becomes a deferred
`SubBuildingType.AUDIENCE_CHAMBER`; D3 phase 3 (provinces and
offices wired) ships the SubBuildingType extension and the
ruler-audience behaviour together.

**Q3 CEMETERY / FESTIVAL_GROUND → both deferred BuildingType
extensions.** Two single-enum-value additions plus matching
PlacementProfile entries. Both scale naturally as primary
buildings; AdjunctPlot's 3×3 to 8×8 footprint cap doesn't fit
royal cemeteries that grow with lineage. CEMETERY ships in D3
phase 2; FESTIVAL_GROUND ships in D3 phase 5.

**Q4 "AUTHORITY inclination" → `Inclination.CIVIC` +
`Category.CIVIC_AUTHORITY`.** The prompt's loose wording about
an "AUTHORITY inclination" maps cleanly to existing V2
mechanism: `Inclination.CIVIC` for layout-level civic-anchor
bias; `Provides(CIVIC_AUTHORITY, N)` on individual buildings
for capital-emission and province-seat derivation. No new
Inclination axis added (Inclination is deliberately capped at
6 per its source-code comment).

#### Translation outcome — 14 V1 concepts

**Mapped (4)** — direct V2 equivalent in current use:
- `capital_emits_claim` → `Inclination.CIVIC` + aggregated
  `Provides(CIVIC_AUTHORITY)`.
- `CASTLE_SLOT` → `BuildingType.CASTLE` (already in
  `BuildingType.java`).
- `NOBLE_RESIDENCE` → `BuildingType.NOBLE_MANOR` + existing
  `AdjunctPlotRegistry` binding.
- `TREASURY` → `BuildingType.TREASURY`.

**Subsumed (2)** — V2 mechanism covers V1 intent without a
dedicated equivalent:
- `province_seat_eligible` — derived at runtime from `Inclination`
  + aggregated `Provides(CIVIC_AUTHORITY)` ≥ threshold.
- `PALACE_SLOT` — stylistic NBT pack on `BuildingType.CASTLE`.

**Distributed → D1 follow-up (5)** — V1 concept moves to a
forthcoming `CultureKingdomDefaults` field:
- `claim_budget_hint` → `claimBudgetHint: int`
- `vassal_types` → `vassalEligibleCultures: List<String>`
- `hostile_types` → `hostileCultures: List<String>`
- `min_nobility_tier` → `minNobilityTier: int`
- `claim_resistance` → `claimResistance: float`

Plus one new field the rewrite adds for § 5.2:
- (new) `provinceSeatThreshold: int`

D1 didn't ship these to keep the bridge phase minimal; the
extension lands as a D1.5 / D1 follow-up before D3 phase 1
opens. Single-record-field additions with codec
`optionalFieldOf` defaults — same shape as the five existing
fields.

**Deferred (3)** — no V2 mechanism yet; minimum extension named:
- `AUDIENCE` → `SubBuildingType.AUDIENCE_CHAMBER` (D3 phase 3
  ships the enum value alongside the ruler-audience behaviour).
- `CEMETERY` → `BuildingType.CEMETERY` (D3 phase 2 ships).
- `FESTIVAL_GROUND` → `BuildingType.FESTIVAL_GROUND` (D3 phase 5
  ships).

#### Why this passes all the constraints

- **Doc-only.** No `.java`, no `.json`, no `.nbt` touched. Diff
  is restricted to three Markdown files.
- **No V1 vocabulary in the rewrite body.** The "(formerly X)"
  parentheticals appear only in the appendix table headers
  ("Schema fields (formerly `VillageTypeData`)"). Section
  5.1–5.8 reads as native V2 prose.
- **All V1 intent preserved.** Each of the 14 V1 concepts has
  an explicit destination — Mapped / Subsumed / Distributed /
  Deferred. No silent drops.
- **D1 sub-bundle is the home for kingdom-wide rules.** Five
  V1 fields nominally on `VillageTypeData` were really
  kingdom-wide; § 5.5 relocates them onto
  `CultureKingdomDefaults` and flags the missing fields as a
  D1 follow-up.
- **No `"default"` literal.** Per-culture defaults remain in
  `CultureBundles`.
- **Inclination not extended.** AUTHORITY maps to the existing
  CIVIC_AUTHORITY Category; no seventh Inclination axis added.
- **`Kingdom/Castle/` not referenced.** The rewrite does not
  mention the package; V1 Section 5 didn't reference it either,
  so no doc bridge is needed.

#### What's deferred for the D1 follow-up extension (D1.5)

Six fields on `CultureKingdomDefaults`, each with codec
`optionalFieldOf` default matching the existing five fields'
shape. Per-culture defaults set in `CultureRegistry` for
default / plainfolk / highmarch / silkwood / tidereach.
Estimated ≈ 100 LOC across `CultureBundles.java` (add fields
+ codec) and `CultureRegistry.java` (per-culture values).

#### What's deferred for D3 (alongside feature shipping)

- `SubBuildingType.AUDIENCE_CHAMBER` enum value + anchor-block
  authoring in CASTLE NBTs (D3 phase 3).
- `BuildingType.CEMETERY` enum value + PlacementProfile +
  layout-recipe entry (D3 phase 2).
- `BuildingType.FESTIVAL_GROUND` enum value + PlacementProfile
  + festival-tick gating (D3 phase 5).

D3 phases 1–7, when drafted, can now reference Section 5.1–5.8
directly — no V1-vocabulary translation step in those prompts.
