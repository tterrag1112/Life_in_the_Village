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

---

## D3.1 — Kingdom plan Phase 1: Worldgen and capital generation

### 2026-05-09 — Track D3.1 landed

**Phase:** Track D3.1 (kingdom plan Phase 1) — reshapes the
seed-to-kingdom flow to produce kingdoms in D1+D2 vocabulary.
Capital-only initial state; multi-village kingdoms grow later via
D3 phase 2's vassalage / village-joining mechanism.

#### Disposition before code

- `WorldgenKingdomSeeder` (376 LOC, `@SubscribeEvent` on
  `ServerTickEvent.Post`) drives kingdom worldgen after great-road
  generation completes. Composition list pre-D3.1 produced
  multi-village kingdoms.
- `KingdomSpawner.planComposed` (~150 LOC inside the 764-LOC
  spawner) was the worldgen entry. Created Kingdom record + claim
  + planned multiple villages.
- `Kingdom/Castle/` (31 files) is generator-style — returns
  `CastleManifest` after world block mutation. Currently invoked
  only by `CastleCommand` debug. **No `castle_styles/*.json`
  exist in resources today**, so `CastleGenerator.generate`
  cannot produce visible buildings without significant content
  authoring. Per user direction: Kingdom/Castle/ stays orphan;
  may be deleted in Track E.
- `KingdomClaim` + `KingdomClaimComputer` — pure Dijkstra over
  atlas cells with hard-coded `DEFAULT_BUDGET = 1800f`. D2's
  translation table identified `claimBudgetHint` and
  `claimResistance` as forthcoming D1 sub-bundle fields; D3.1
  adds them.
- V2 has no CASTLE PlacementProfile in `PlacementDefaults.java`
  today. CASTLE is in `BuildingType` and has an existing
  `level_1.nbt` plus an inhabitant spec
  (KINGDOM_RULER + 2 GUARDs).
- V2 has no "capital" concept at the planner level. Every village
  is treated equally.
- `KingdomEventBus` had zero call sites pre-D3.1. D3.1 is the
  first place events fire.
- Existing kingdoms in saves had no `capitalVillageId`; first
  member village was implicitly the capital.

#### Decisions (user-confirmed)

**Q1 Capital castle production → V2 places existing castle NBT;
Kingdom/Castle stays orphan.** Per user clarification: "Just
use a normal castle nbt since the Kingdom/Castle system is a
WIP and may be deleted unless I can think of a good way to get
it to work better, but that is a Track E type of thing."
D3.1 ships V2 PlacementProfile entries for `CASTLE`,
`NOBLE_MANOR`, `TREASURY` so future capital-tier village types
can declare them required. The existing `level_1.nbt` is the
visible structure when V2 selects CASTLE. Kingdom/Castle/ is
not integrated in this phase; the prompt's "no more orphan
status" criterion is partially deferred to Track E.

**Q2 D1.5 follow-up fields → add the 6 fields now.**
`CultureKingdomDefaults` gains `claimBudgetHint`,
`claimResistance`, `vassalEligibleCultures`, `hostileCultures`,
`minNobilityTier`, `provinceSeatThreshold`. ~100 LOC bundle
extension. Per-culture defaults set in `CultureRegistry` for
default / plainfolk / highmarch / silkwood / tidereach. D3.1's
`CapitalGenerator` reads `claimBudgetHint` to drive the
`KingdomClaimComputer`; the other 4 fields are populated for
D3.2+ consumers (vassalage, hostile spacing, succession nobility
gates, province-seat derivation).

**Q3 Office staffing → Hybrid: fresh-spawn ruler + culture-required
offices, draft inhabitants for the rest.** A new
`KingdomOfficeBootstrap` runs every second post-realisation:
fresh-spawns a `KINGDOM_RULER` `TownspersonMob` at the capital
anchor and seats them as `kingdom_king`; for each office in the
culture's `requiredOffices` list, drafts an existing nearby
inhabitant whose profession matches the office's
`eligibleProfessions` (96-block scan radius), else fresh-spawns
the first eligible profession. Idempotent: gated on
`OfficeState.isVacant("kingdom_king")` so re-running is a no-op.

**Q4 Multi-village flow → Replace `planComposed` with new
CapitalGenerator.** `KingdomSpawner.planComposed` deleted;
`Kingdom/Worldgen/CapitalGenerator.java` (new) replaces it as
the sole worldgen entry. The remaining `KingdomSpawner.spawn` /
`spawnComposed` survive for admin /liv commands that synchronously
place full multi-village kingdoms. `WorldgenKingdomSeeder`
rewired to call `CapitalGenerator.generate` instead.

#### Files added

- `Kingdom/Worldgen/CapitalGenerator.java` (~190 LOC) —
  worldgen orchestrator. Takes `(level, origin, kingdomName,
  culture, capitalVillageType)`; creates Kingdom record with
  foundingTick + regenerated heraldry; computes
  `KingdomClaim` with culture-keyed `claimBudgetHint`; plans
  the capital village via `ClaimVillagePlacer` with composition
  size 1; wires `Village.kingdomId` and `Kingdom.capitalVillageId`;
  fires `KingdomEvent.KingdomFounded`.
- `Kingdom/Worldgen/KingdomOfficeBootstrap.java` (~190 LOC) —
  post-realisation office staffing. Fresh-spawns the founding
  ruler; drafts / fresh-spawns culture-required offices; fires
  `RulerSucceeded` + `OfficeFilled` events.

#### Files modified

- `Cultures/CultureBundles.java` — `CultureKingdomDefaults`
  gains 6 fields (D1.5). DEFAULT, plainfolk, highmarch,
  silkwood, tidereach all set per-culture values.
- `Cultures/CultureRegistry.java` — per-culture D1.5 field
  values. Plainfolk wide+open (budget=2400, resistance=0.15,
  vassal-eligible=[default]); Highmarch tight+hostile
  (budget=1500, resistance=0.85, hostile-to=[plainfolk]);
  Silkwood compact+resistant (budget=1200, resistance=0.55);
  Tidereach maritime (budget=2100, trade-heavy upkeep).
- `Village/Planning/V2/Layer3/PlacementDefaults.java` — new
  CASTLE / NOBLE_MANOR / TREASURY profiles. CASTLE provides
  `CIVIC_AUTHORITY=5` + `DEFENSE=3` + `EMPLOYMENT=3`;
  NOBLE_MANOR provides `CIVIC_AUTHORITY=1` + housing;
  TREASURY provides `COMMERCE`. Inert until village types
  declare them required (D3.2+ work).
- `Kingdom/Kingdom.java` — `capitalVillageId: UUID?` and
  `foundingTick: long` fields with codec entries
  (optionalFieldOf default empty / 0L). 16-field codec arity
  at the DFU cap.
- `Kingdom/KingdomMembershipMigration.java` — second migration
  pass back-fills `capitalVillageId` from the first villageId
  for pre-D3.1 saves. Independent flag
  (`kingdomCapitalMigrated`) so the back-fill runs even on
  saves where the D1 pass already completed.
- `Networking/VillageSavedData.java` — new
  `kingdomCapitalMigrated` flag with codec wiring. 16-field
  codec arity at the DFU cap.
- `Kingdom/WorldgenKingdomSeeder.java` — calls
  `CapitalGenerator.generate` instead of
  `KingdomSpawner.planComposed`. Composition list's first
  entry becomes `capitalVillageType`; remaining entries are
  ignored at worldgen.
- `Kingdom/KingdomSpawner.java` — `planComposed` body removed
  (~150 LOC). The remaining `spawn` / `spawnComposed` admin
  paths are unchanged.
- `Events/TickSystems.java` + `Events/TickSubsystemRegistry.java`
  — `KingdomOfficeBootstrapTickSystem` registered (interval =
  20). Idempotent.
- `Commands/KingdomDebugCommand.java` — `describe` extended
  with capital + foundingTick + 6 D1.5 fields; `list`
  extended with capital name + king-seated state.

#### Wired but inert at D3.1 close

- CASTLE / NOBLE_MANOR / TREASURY PlacementProfiles. V2 doesn't
  yet select them because no village type declares them
  required. D3.2 (or earlier) will introduce a "capital-tier"
  village type that lists CASTLE as required.
- D1.5 fields beyond `claimBudgetHint`. `claimResistance`,
  `vassalEligibleCultures`, `hostileCultures`, `minNobilityTier`,
  `provinceSeatThreshold` are populated and queryable but
  no D3.1 code reads them (consumers ship in D3.2+).
- Kingdom/Castle/ orphan stays untouched per user direction.
  Track E is the natural place to either integrate it (with
  authored castle_styles JSONs) or delete the package.

#### Test plan (manual)

1. **Fresh world.** Spawn three different worlds (different
   seeds). For each:
   - `WorldgenKingdomSeeder` schedules N kingdoms after
     great-road generation completes.
   - Each kingdom gets the new D3.1 path via
     `CapitalGenerator`. Log line:
     `Founding capital-tier kingdom 'X' (culture) at ...`.
   - `KingdomFounded` event fires (visible in
     `/litv kingdom debug events_stats` once a subscriber
     ever registers; D3.1 has no subscribers, but the count
     would increment).
   - `/litv kingdom debug list` shows N kingdoms with
     capital=$name + king=vacant.
2. **Capital realisation.** Walk a player into a capital
   village's trigger radius. `VillageRealisationSystem`
   realises the village; `BuildingInhabitantRegistry`
   spawns CASTLE inhabitants if a CASTLE building landed.
   Within ~1 second, `KingdomOfficeBootstrap` runs:
   - Fresh-spawns a `KINGDOM_RULER` NPC at the capital anchor.
   - `OfficeRegistry.KINGDOM_KING` becomes seated.
   - `RulerSucceeded` + `OfficeFilled` events fire.
   - `/litv kingdom debug list` now shows king=seated.
3. **Determinism.** Spawn the same seed in two separate world
   slots. Confirm:
   - Same kingdoms in the same positions.
   - Same heraldry per kingdom (CapitalGenerator regenerates
     heraldry from `(culture, kingdomId, foundingTick)`;
     since foundingTick is `level.getGameTime()` at the
     scheduling moment, this depends on tick ordering being
     deterministic — which it is for a freshly-seeded world).
   - Same office assignments once both worlds reach the same
     tick.
4. **Existing-save migration.** Load a save with pre-D3.1
   kingdoms (D1 already ran):
   - `[KingdomMembershipMigration] complete — kingdoms=N
     ... capitalsStamped=N` log line on first tick.
   - `Kingdom.capitalVillageId` is populated for every
     kingdom (= first entry of `villageIds`).
   - `/litv kingdom debug describe <name>` shows non-empty
     capital + foundingTick=0 (legacy kingdoms didn't track
     this).

#### Compile status

Gradle network-blocked in this sandbox. Static review confirmed:
- `CultureKingdomDefaults` codec arity = 11 (5 + 6 D1.5);
  under DFU cap.
- `Kingdom` codec arity = 14 (was 11 + 3 D1) + 2 D3.1 = 16,
  AT the DFU cap. No more room without consolidation.
- `VillageSavedData.fromCodec` arity = 14 + 2 migration flags
  = 16, AT the DFU cap.
- `CapitalGenerator.generate` returns Optional, mirrors the
  contract of the deleted `planComposed` — only difference
  is composition is always size 1.
- `KingdomOfficeBootstrap.runOnce` is idempotent — gated on
  `OfficeState.isVacant(KINGDOM_KING)`. Fresh-spawn paths
  use try/catch; failed spawns log + skip.

#### Out-of-scope, flagged for later

- **Kingdom/Castle/ integration** — moved to Track E. Per user:
  "WIP and may be deleted unless I can think of a good way to
  get it to work better."
- **Visibly distinct capital settlements**: the prompt's done
  criterion "capitals are visibly proper capital settlements
  (not ordinary villages)" is **partially deferred**. Capitals
  today look like normal villages because no village type lists
  CASTLE as required. The CASTLE PlacementProfile is wired and
  ready; D3.2+ ships a capital-tier village type that uses it.
- **`/litv kingdom debug regen <seed>`** — not shipped this
  phase. Determinism is verifiable by spawning two fresh
  worlds with the same seed and comparing
  `/litv kingdom debug list` output.
- **Office NPC behaviour goals**. Fresh-spawned kingdom-tier
  NPCs (chancellor, scholar, etc.) idle today; D3 phase 3
  wires their behaviours.
- **Heraldry uniqueness enforcement**. Two kingdoms with the
  same culture + similar foundingTick could produce similar
  heraldry. The xorshift hash makes this unlikely but not
  impossible; D3 phase 2+ can add a uniqueness pass if it
  surfaces in play.
- **Multi-village kingdom regrowth**. D3.1 produces capital-only
  kingdoms; vassalage / village-joining is D3 phase 2.

---

### 2026-05-09 — Track D3.2a landed (nobility data substrate)

Phase 2 of the kingdom rework was originally specified as one
large prompt covering houses, ranks, estate income, fealty-based
tax flow, marriage, succession, and the dynasty-tree GUI. Per
user direction we split it cleanly:

- **D3.2a — data substrate** (this entry): records, codecs,
  generators, helpers, and debug commands. Inert in-game beyond
  persistence.
- **D3.2b — behaviour** (separate phase): succession + fealty
  tax + marriage + dynasty-tree GUI + KingdomModifier emitters.
  Reads the D3.2a substrate as authoritative.

#### What this phase ships

- `Npc/Nobility/NobilityComponent` — kingdom-tier overlay on
  `TownspersonMob`. Holds `dynastyHouseId` (UUID? — the noble
  dynasty the NPC belongs to, distinct from
  `FamilyComponent.houseId`), `rankIndex` (index into the NPC's
  culture's `CultureKingdomDefaults.nobilityRanks` list), and
  `prestige` (0..100 scalar). `FOUNDING_THRESHOLD = 30` ships
  as a constant for D3.2b's house-founding gate. Codec-driven
  save/load matches `SkillComponent` / `TraitVector` idiom.
- `TownspersonMob` wiring — new `private final
  NobilityComponent nobility` field + `getNobility()` accessor;
  `nobility.save(output)` / `nobility.load(input)` delegated
  alongside `npcRelationships`.
- `Kingdom/Houses/House` — record carrying `(id, name,
  kingdomId, founderUuid, foundingTick, headUuid, heraldry,
  prestige, motto)` plus `withHead`, `withoutHead`,
  `withPrestige`, `withMotto` copy-helpers. Bundled into the
  existing `KingdomGovernanceData` sub-record (now 7 fields,
  well under the 16-field DFU cap) — no new top-level slot on
  `Kingdom.CODEC` (which sits at the cap). New accessors:
  `getHouses` / `findHouse(id)` / `findHouseByName` / `addHouse`
  / `removeHouse(id)` / `replaceHouse(replacement)`.
- `Kingdom/KingdomModifier` — wires the stability/legitimacy
  modifier system D1 declared aspirational. Record is
  `(id, description, stabilityDelta, legitimacyDelta,
  appliedAtTick, expiresAtTick)`; `expiresAtTick == 0L` means
  permanent. `Kingdom.addModifier` / `removeModifier(id)` /
  `pruneExpiredModifiers(tick)` plus
  `stabilityModifierSum` / `legitimacyModifierSum` summarise
  active deltas. Bundled into `KingdomGovernanceData`
  alongside houses.
- `HeraldryGenerator.forHouse(houseId, founderUuid)` — same
  xorshift mixer as the kingdom variant, seeded from the house
  id + founder UUID. House heraldry is independent of culture
  seed so a single kingdom can carry visually distinct house
  arms even when all houses share its culture.
- `Npc/Nobility/NobilityRanks` — pure helper resolving
  `(cultureId, rankIndex)` to a display name from
  `CultureKingdomDefaults.nobilityRanks`. Out-of-range indices
  clamp; non-monarchy cultures (empty rank list) return
  `COMMONER_FALLBACK = "Commoner"`. Companion helpers:
  `rankCount`, `maxRankIndex`, `isMonarchyCulture`, `namesFor`.
- `/litv kingdom debug describe <name>` extended with
  `── Noble houses (D3.2a) ──` and `── Active modifiers (D3.2a) ──`
  sections. Two new subcommands: `/litv kingdom debug houses
  <name>` (full house records) and `/litv kingdom debug
  modifiers <name>` (base scalars + modifier sum + per-modifier
  detail with applied/expires ticks).

#### Decisions worth recording

- **Naming clarification.** `FamilyComponent.houseId` is a
  `BuildingType.HOUSE` UUID (the residential building an NPC
  physically lives in, used by `HouseholdManager`).
  `NobilityComponent.dynastyHouseId` is a different concept
  entirely — the UUID of a `Kingdom.Houses.House` record (a
  noble dynasty). The two never share a value. The investigation
  agent's "move houseId to NobilityComponent" recommendation
  was based on a conflation; after reading
  `FamilyComponent.java` directly, we kept the building-side
  field unchanged and introduced a new dynastyHouseId on the
  nobility component. Documented in
  `NobilityComponent`'s class javadoc and on `House`.
- **Where to store houses + modifiers.** `Kingdom.CODEC` is at
  16 fields after D3.1, the DFU group cap. We extend the
  existing `KingdomGovernanceData` sub-record (5 → 7 fields)
  rather than introducing a new top-level slot. Save compat
  preserved with `optionalFieldOf(... , new ArrayList<>())`
  defaults.
- **Phase split rationale.** The D3.2 prompt explicitly
  invited a split if 2.1 + 2.2 (data) cleaved cleanly from
  2.3-2.7 (behaviour). They do: nothing in this phase reads
  any new field as authoritative — the entire substrate is
  inert until D3.2b's tick subsystems and event handlers
  wire up. Shipping data first keeps the diff focused and
  makes save format the easy thing to review independently.

#### Out-of-scope, flagged for D3.2b

- **Marriage flow** — gendered or otherwise. D3.2a doesn't
  touch `FamilyComponent` beyond reading existing fields.
- **Succession + fealty tax flow** — the underlying records
  exist; the tick subsystem that drives them is D3.2b.
- **Dynasty-tree GUI** — `House.headUuid` is the entry point;
  the actual screen is D3.2b.
- **Modifier emitters** — the substrate exists; the systems
  that *add* modifiers (succession turbulence, recent crime,
  active war, etc.) are D3.2b.
- **House-founding gate** — `FOUNDING_THRESHOLD = 30` ships
  as a constant; the gate that consumes it is D3.2b.

---

### 2026-05-09 — Track D3.2b landed (nobility behaviour)

D3.2b wires behaviour on top of D3.2a's data substrate per the
user-confirmed design pass:

- **Succession scope:** interpret `SuccessionRule` inside
  `HereditarySelection` (no SelectionMethod enum changes).
- **Fealty chain:** two-tier (village → noble-overlord → kingdom)
  with default skim rate = 0 to preserve net flow.
- **House founding:** daily tick subsystem auto-founds eligible NPCs.
- **Dynasty tree GUI:** new "Houses" tab in `KingdomBookScreen`.

#### What this phase ships

- `Npc/Nobility/NobilityEventDispatcher` — single
  `EventDispatcher` registered in `NpcLifeEventBus.registerDefaults`
  that handles two events:
  - `Married` — adopts dynasty house (the unaffiliated spouse joins
    the affiliated one's dynasty; on a cross-house marriage, the
    higher-rank spouse's house wins); `marryUp` advances the
    lower-rank spouse one step toward the higher rank, capped at
    `higher − 1`; both spouses gain +3 prestige; if either is at
    rank ≥ 2, emits `marriage.high_rank` legitimacy +3 modifier
    (one-week expiry). Idempotent: only acts when
    `subject.uuid < spouse.uuid` because `CourtingGoal` fires
    `Married` for both partners.
  - `FamilyDeath` — walks every kingdom's houses; for any house
    whose `headUuid == deceasedId`, runs `SuccessionResolver`
    against the kingdom's culture rule; on success, replaces the
    house head and the heir inherits the predecessor's rank
    (capped at culture max); on failure, marks the house extinct
    via `House.withoutHead`; either way, emits `succession.transition`
    legitimacy −5 modifier (one-day expiry).

- `Npc/Nobility/SuccessionResolver` — pure helper, no state.
  `pickHeir(level, predecessor, rule)` returns the heir per rule:
  PRIMOGENITURE (eldest child, any gender), AGNATIC_PRIMOGENITURE
  (eldest male), SEMI_SALIC (eldest son first; falls through to
  eldest grandson via daughter-line). ELECTIVE / COUNCIL /
  DIVINE_DESIGNATION return empty so callers fall through to the
  existing `CouncilSelection` / `MeritocraticSelection` engines —
  D3.3 wires real electors when provinces land. `isHereditary(rule)`
  inspection helper for callers that want to choose the path
  upfront.

- `HereditarySelection.pickHeir` extension — consults the resolver
  first; retains the prior "any eligible adult child, oldest first"
  fallback for cultures whose rule is electoral (so non-king
  hereditary offices on Silkwood/Tidereach still resolve via the
  legacy path).

- `Npc/Nobility/HouseFoundingDriver` + `HouseFoundingTickSystem`
  (priority 196, interval 24000) — daily scan walks every kingdom's
  villages, collects loaded adult NPCs, and founds a house for each
  NPC with no `dynastyHouseId`, prestige ≥
  `NobilityComponent.FOUNDING_THRESHOLD`, and `rankIndex ≥
  culture.kingdomDefaults().minNobilityTier`. Names derive from the
  NPC's surname ("John Stoneblossom" → "House Stoneblossom"); fresh
  UUID; founder = current NPC, head = current NPC, heraldry from
  `HeraldryGenerator.forHouse(houseId, founderUuid)`. Emits
  `house.founded` (+1 stability, +1 legitimacy, one-week expiry).

- `Npc/Nobility/FealtyChain` — two-tier tax flow infrastructure.
  `lordOfVillage(level, data, village)` returns the highest-rank
  loaded NPC assigned to the village who has a dynasty house
  (rank > 0 + dynastyHouseId set), tie-broken by prestige.
  `split(taxOwed, lord)` returns a `TaxSplit(kingdomShare, lordShare,
  lord)` per `DEFAULT_LORD_SKIM_RATE`. `payLord` credits the
  lord's personal purse via `CoinHelper.giveCoins`. **Default skim
  rate = 0.0** so net kingdom revenue is unchanged from the
  pre-D3.2b shape; the chain is wired and observable, ready for
  D3.3 to lift the rate per culture.

- `KingdomTaxEvent.collectTaxes` extension — per village, after the
  per-NPC collection loop, splits `collectedFromVillage` between
  the lord (if any) and the kingdom; pays the lord; remits the
  kingdom share to the treasury. Logs include the lord's name
  + share when present.

- `KingdomBookScreen.DYNASTY_TREE` ("Houses" tab) — new section
  reading `Kingdom.governance.houses` from the existing
  `SyncKingdomPacket` flow. Lists each house with name, heraldry
  blazon, prestige, head + founder UUIDs (truncated), and motto.

- `/litv kingdom debug fealty <name>` — prints the lord-of-village
  resolution for each village in the kingdom plus the default
  skim rate, so verifying the chain doesn't require waiting for
  the daily tax tick.

#### Decisions worth recording

- **Marriage idempotency.** `CourtingGoal` fires `Married` twice
  (subject = entity, spouse = target; then subject = target,
  spouse = entity). Without protection, every marriage would
  double-apply rank/prestige changes and stack the high-rank
  modifier. Solution: gate the dispatcher's processing on
  `subject.uuid < spouse.uuid`. Documented in the dispatcher.
- **Family-death fan-out.** `FamilyDeath` fires once per surviving
  relative. Without protection, succession would run N times. The
  fix is implicit: after the first run, `house.headUuid` is no
  longer the deceased, so the per-house filter rejects the rest.
  No explicit gate needed.
- **Heir rank cap.** Heir inherits predecessor's rank, capped at
  the culture's `maxRankIndex` (=`nobilityRanks.size() - 1`).
  Avoids overflow if the culture's rank list shrinks between
  saves.
- **Succession-during-death predecessor lookup.** When
  `FamilyDeath` fires, the deceased entity is gone — `findByUUID`
  returns empty. We use the kingdom's culture as a proxy
  succession rule (members typically share their kingdom's
  culture). For mixed-culture houses, this still picks a coherent
  rule rather than crashing on a null lookup.
- **GUI member-name resolution deferred.** "Head + spouse +
  children + member list" was the user-confirmed scope for the
  Dynasty Tree GUI. NPC names live on `TownspersonMob` entities,
  not on synced records. Resolving a UUID to a name client-side
  requires either (a) syncing per-village name indices via a new
  packet, or (b) a server roundtrip when the user opens a house.
  D3.2b ships the data-visible variant (truncated UUIDs);
  follow-up work — likely D3.3 alongside the province GUI panel —
  adds the detail roundtrip.
- **Net economic flow constraint.** The original D3.2 prompt
  required net flow preserved within tolerance under default
  parameters. With `DEFAULT_LORD_SKIM_RATE = 0.0`, the lord
  receives 0 bronze and the kingdom receives the full collected
  amount — exactly the pre-D3.2b shape. Constraint satisfied
  exactly, not just within tolerance.

#### Out-of-scope, flagged for D3.3 / Phase 4

- **Province subdivision** + **provincial governance** — D3.3.
- **Capability gating** + **office competence** — D3.3.
- **Live name resolution** in the Houses GUI tab — flagged above.
- **Modifier emitters beyond the three D3.2b ships** —
  `crime.homicide`, `war.active`, `religion.blessing` etc. are
  Phase 4 / 6 territory per the original prompt's deferral list.
- **Heir refusal mechanics** — the resolver picks an heir; the
  heir always accepts. Refusal + cousin-walking is D3.3.
- **Kingdom-tier laws driven by Chancellor** — capability gating
  is D3.3; today the king can pass any law per the existing
  flow.

---

### 2026-05-09 — Track D3.3a landed (provinces + provincial governance + map overlay)

D3.3 was split per the kingdom plan's "If ambiguous" guidance:
3.1 + 3.4 (subdivision + governance) ships first as **D3.3a**;
3.2 + 3.3 (offices + capability gating) ships separately as
**D3.3b**.

#### User-confirmed design

- **Polygon timing:** C — hybrid (weekly recompute baseline +
  event-driven invalidation on noble-head changes).
- **SubdivisionModel mapping:** all 5 enum values map.
  PROVINCES + DUCHIES → TERRITORIAL (DUCHIES prefers house-head
  governors; PROVINCES picks top-ranked); TRIBAL_CONFEDERATION
  → TRIBAL; CITY_STATE_LEAGUE → FUNCTIONAL; UNITARY → NONE.
- **Voronoi:** cell-set Voronoi at atlas-cell granularity. Each
  kingdom-claim cell is assigned to the nearest seed by Manhattan
  distance; ties break by seed UUID. No Fortune's algorithm —
  the existing claim model is already cell-based.
- **Manor fallback:** when a kingdom has no NOBLE_MANOR
  buildings, village centroids seed the Voronoi instead. Fresh
  kingdoms subdivide immediately.

#### What this slice ships

- `Kingdom/Provinces/Province` record — id (derived from
  `(kingdomId, seedUuid)` for stable identity), name, kingdomId,
  governor (Optional UUID), member villages, cell-set, stability
  (default 60, clamped 0..100), treasury, createdTick, per-province
  modifiers list (`KingdomModifier` shape), rolling
  `ProvincialReport` buffer. Copy-helpers: `withGovernor`,
  `withoutGovernor`, `withMembership`, `withStability`,
  `withTreasury`, `withName`, `appendReport`, `withModifiers`.
  `Province.fresh` constructs with default stability + empty
  buffers.
- `Kingdom/Provinces/ProvincialReport` record + codec — daily
  snapshot of (tickGenerated, taxCollected, taxRemitted,
  withhold, stabilityDelta, summary). Capacity constant
  `BUFFER_CAPACITY = 28` (~4 in-game weeks).
- `KingdomGovernanceData` extended (now 9/16 fields) with
  `provinces` + `lastProvinceRecomputeTick`. `Kingdom.CODEC` stays
  at 16 top-level fields (the cap).
- `Kingdom` accessors: `getProvinces`, `findProvince(id)`,
  `findProvinceByName(name)`, `findProvinceForVillage`,
  `findProvinceForCell`, `addProvince`, `removeProvince`,
  `replaceProvince`, `replaceAllProvinces` (atomic swap from
  recompute), `getLastProvinceRecomputeTick`,
  `setLastProvinceRecomputeTick`, `isProvinceRecomputeDue`.
- `Kingdom/Provinces/ProvinceComputer.recompute` — single entry
  point. Skips kingdoms with `< MIN_VILLAGES_FOR_SUBDIVISION = 4`
  villages. Dispatches on `culture.kingdomDefaults().subdivisionModel()`:
  - `PROVINCES` / `DUCHIES` → `territorial()` — collect
    NOBLE_MANOR seeds (or village centroids if none); for each
    cell in the kingdom's claim, assign to nearest seed; villages
    join the province whose seed is nearest to their centroid.
  - `TRIBAL_CONFEDERATION` → `tribal()` — group villages by
    dominant noble house id (looked up via village leader →
    house head/founder); cross-group cell partition by nearest
    member centroid. Singleton groups → no subdivision.
  - `CITY_STATE_LEAGUE` → `functional()` — group villages by
    primary `VillageTag` role (TRADE > DEFENSIVE > INDUSTRIAL >
    RELIGIOUS > AGRICULTURAL > REMOTE; default AGRICULTURAL).
    Same cross-group cell partition.
  - `UNITARY` → empty list.
  Identity preservation: same seed UUIDs across recomputes
  produce same province UUIDs, so `mergeWithExisting` carries
  over treasury / reports / modifiers / governor.
- `Kingdom/Provinces/GovernorSelector.selectFor` — walks loaded
  NPCs assigned to the province's villages; filters by
  `nobility.hasDynastyHouse() && rankIndex > 0`; sorts by
  rank desc → prestige desc → UUID asc; for `DUCHIES` prefers
  candidates who are heads of dynasty houses, falling back to
  the top-ranked candidate.
- `ProvinceRecomputeTickSystem` (priority 191, interval 24000,
  but the per-kingdom `isProvinceRecomputeDue` check uses
  `RECOMPUTE_INTERVAL_TICKS = 7 * 24000` so the cost is one
  comparison per kingdom per day).
- `ProvinceDailyTickSystem` (priority 194) + `ProvincialDailyDriver`
  — per province per day: consumes the `FealtyChain` ledger
  entry; computes stability delta from `(ungoverned ? -2 :
  remitted ? +1 : 0) + (withhold > 0 ? -1 : 0) + drift`;
  composes a one-line summary; appends `ProvincialReport`.
- Event-driven recompute invalidation:
  `NobilityEventDispatcher.runSuccession` resets the kingdom's
  `lastProvinceRecomputeTick = -1L` (proxy for "manor changes
  hands"); `HouseFoundingDriver.tryFound` does the same on a
  fresh house. Next weekly tick fires immediately on the
  affected kingdom.
- `Npc/Nobility/FealtyChain` extended:
  - `DEFAULT_GOVERNOR_SKIM_RATE = 0.0` (constraint: net flow
    preserved at default parameters).
  - `governorOfVillage(level, data, kingdom, village)` returns
    `Optional<GovernorRef>`.
  - `splitForGovernor(kingdomShare, governor)` returns
    `GovernorSplit(crownShare, governorShare, governor)`.
  - `payGovernor(GovernorRef, bronze)` credits the governor's
    purse via `CoinHelper.giveCoins`.
  - Static `PROVINCE_LEDGER` map keyed by province id;
    `recordProvinceLedger` / `consumeProvinceLedger`. Built up
    by the daily tax tick, consumed by the daily provincial tick.
- `KingdomTaxEvent.collectTaxes` — three-tier flow:
  village → lord (D3.2b) → governor (D3.3) → kingdom. Per-village
  log line includes both lord and governor cuts when present.
- `Gui/Map/Kingdom/Layer/ProvinceLayer` — sits between
  `KingdomTerritoryLayer` and `RouteLayer`. Per-province cell
  fill with UUID-derived colour, stability-tinted (low stability
  redder); border outline; governor labels at cell centroids.
- `KingdomMapData.ProvinceMarker` + builder population — provinces
  ride the existing `Kingdom.CODEC` sync (no new packet); builder
  computes cell-set centroid for label anchoring.
- Debug commands:
  - `/litv kingdom debug provinces <name>` — list with
    governor + stability per province.
  - `/litv kingdom debug province_recompute <name>` — force
    recompute (skips weekly cadence).
  - `/litv province debug describe <provinceName>` — full
    detail including last 5 reports.
  - `/litv kingdom debug describe` extended with a "Provinces"
    section.

#### Decisions worth recording

- **Cell-set Voronoi over polygon Voronoi.** The existing
  `KingdomClaim` is `List<Long> claimedCellKeys` (Dijkstra
  output, not a polygon). Building Fortune's algorithm to
  produce vertex polygons would have been ~500 LOC of fresh
  geometry code; the cell-assignment pass is ~50 LOC and
  produces visually equivalent results at the 64×64-block cell
  resolution. Rendering is identical to existing kingdom
  territory rendering, just per-province.
- **Province identity preservation across recomputes.** Province
  UUIDs derive deterministically from `(kingdomId, seedUuid)` —
  for TERRITORIAL, the seedUuid is the manor's building UUID
  (or the village's UUID when manors don't exist yet); for
  TRIBAL it's the dynasty house id; for FUNCTIONAL it's a
  hashed (role, idx) tuple. This means treasury balances,
  ongoing modifiers, and the rolling report buffer survive
  recomputes — losing them on every weekly recompute would have
  thrown away the whole point of having them.
- **Manor-event invalidation hook.** "Manor changes hands" was
  the prompt's canonical event-driven trigger. We wired it via
  proxy events: `NobilityEventDispatcher.runSuccession`
  (head-of-house death → recompute) and
  `HouseFoundingDriver.tryFound` (new house formed → recompute).
  Direct manor-ownership-change events would require building
  a manor-ownership ledger which D3.2 doesn't have; the
  succession + founding hooks cover the same ground for D3.3a.
- **Net flow constraint.** Both the lord skim
  (`DEFAULT_LORD_SKIM_RATE = 0.0`, D3.2b) and the governor skim
  (`DEFAULT_GOVERNOR_SKIM_RATE = 0.0`, D3.3a) ship at zero so
  net kingdom revenue at default parameters is unchanged from
  the pre-D3.2b shape. Both rates are class constants — D3.3b
  / Phase 4 can lift them per culture or per province.
- **Per-province ledger via static state.** The ledger is a
  static `Map<UUID, ProvinceLedgerEntry>` on `FealtyChain`, not
  persisted to SavedData. Reasoning: it's a within-day
  accumulator consumed end-of-day. A server crash mid-day
  drops the day's tax record, but the actual coin movements
  already happened; the report buffer just misses one entry.
  Persisting the ledger would add a serialization channel for
  data that's transient by design.
- **Voronoi tie-break by seed UUID.** Manhattan-distance ties
  in the cell assignment break by `seedId.compareTo(otherSeedId)`
  ascending. Stable across reloads since seed UUIDs are stable.
- **Stability formula.** Daily delta = `+1` (governor remitted
  cleanly) OR `-2` (ungoverned) OR `-1` (withhold > 0); plus
  `±1` drift toward `DEFAULT_STABILITY = 60`. Clamped 0..100.
  Numbers will be revisited after playtest data; phase 4's
  rebellion driver wants stability < 25 to mean something
  observable.

#### Out-of-scope, flagged for D3.3b / Phase 4 / 5 / 6

- **`KingdomCapability` enum + evaluator** — D3.3b. Eight
  capabilities: ISSUE_DECREE / DRAFT_TREATY / DECLARE_WAR /
  LEVY_TROOPS / PASS_LAW / INVESTIGATE_CRIME / ISSUE_CURRENCY /
  INTRIGUE_FOREIGN. GUI grey-out + server-side enforcement.
- **Office wiring** (Chancellor / Scholar / General /
  Magistrate / Spymaster / Treasurer / Diplomat) — D3.3b.
- **Office competence formula** — D3.3b.
- **Office-grant ennoblement** — D3.3b.
- **Heir refusal in governor selection** — Phase 4 / 5.
- **Kingdom-tier laws** — Phase 4.
- **Charters and privileges** — Phase 4.
- **Spymaster intrigue** — Phase 4.
- **Treaties** — Phase 4.
- **Audience-loop UI for `ProvincialReport`** — Phase 5. Data
  exists, no UI yet.
- **Real Voronoi at sub-cell resolution** — Phase 7 polish if
  the cell-set approximation's blocky borders bother players.
- **Weekly polygon recompute interval tuning** — currently
  hard-coded at 7 days; if Phase 6 rebellion mechanics churn
  manors fast, the event-invalidation hook does the heavy
  lifting and the weekly cadence becomes a safety net.
