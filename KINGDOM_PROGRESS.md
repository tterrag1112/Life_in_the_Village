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

### 2026-05-10 — Track D3.6 Slice 3 landed; Phase 6 complete

Final of three Phase 6 slices. Religion-as-political-authority +
kingdom age cycles. With this slice, Phase 6 ships at 100%: a
world running for many in-game years now produces realistic
political churn — secessions, collapses, mergers, wars,
religious rebellions, age-cycle transitions — all driven from
the locked-design substrate.

#### User-confirmed locks honoured

- **Soft religion model** — declaration of an official religion
  causes immediate stability dip on dissenting provinces (via
  `religion.tension.<provinceId>` modifier); CONVERT_PROVINCE
  charter slowly clears tension; no auto-rebellion unless the
  province crosses the standard rebellion threshold from Slice 1
  (the lower stability gets it there sooner — that's the linkage).

#### CC's calls (design pass e/g locked here)

- **(e) Age cycle culture tuning.** v1 ships single defaults
  (FOUNDING_ERA→MATURE at 90d + 1 succession; MATURE→DECADENT
  at 360d + 4 successions + legitimacy ≤ 45; DECADENT→MATURE
  renewal at legitimacy ≥ 75 + sanctified). Per-culture
  overrides flagged for follow-up; the `RebellionThresholds`
  pattern from Slice 1 is the template.
- **(g) Determinism seeds for Phase 6 stochastic outcomes:**
  - Sanctification roll: `(kingdomId, "sanctify", rulerId, gameDay)`.
  - Conversion completion: deterministic from start tick + 30d.
  - Age-cycle transitions: deterministic from kingdom state (no
    RNG).

#### What this slice ships

- **`KingdomEventsData.successionCount`** — 5th codec field on
  the existing record; `KingdomHistoryData.recordSuccession()`
  + `getSuccessionCount()` accessors. Hooked into
  `NobilityEventDispatcher.runSuccession` (after heir seated)
  so successions feed age-cycle transitions automatically.
- **`Kingdom/AgeCycle/KingdomAgeState` enum** — three states
  with per-state modifier id + stability + legitimacy deltas:
  - FOUNDING_ERA: +3 stab, +5 leg (rulers carry weight; laws
    cost more — but the per-law cost mechanic isn't gated yet,
    so the modifier alone represents the "young institutions"
    flavor).
  - MATURE: +5 stab, +2 leg.
  - DECADENT: −8 stab, −5 leg (instability rising; rebellion
    fires sooner because total stability is lower so province
    rebellion thresholds get crossed faster — the linkage to
    Slice 1's threshold mechanism).
- **`Kingdom/AgeCycle/AgeCycleDriver`** — daily evaluator;
  computes target from current state + age + successions +
  effective legitimacy + sanctified status. Transitions swap
  modifiers + fire `AgeCycleTransition` event.
- **`Kingdom/Religion/ReligionAuthorityEngine`** with three
  responsibilities:
  - `officialReligion(kingdom)` reads D3.4a's
    `official_religion` EnumLaw.
  - `applyReligiousTension` daily stamps
    `religion.tension.<provinceId>` modifier (−3 stab, 30d) on
    dissenting provinces; first-detection of a new declared
    religion fires `OfficialReligionDeclared` once via
    `religion.declared.<religionId>` permanent marker
    (re-fires on choice change).
  - `attemptSanctification` called from succession hook:
    deterministic seeded roll (70% baseline + legitimacy
    modulation, clamped 20-95%); GRANTED stamps permanent
    `religion.sanctified` (+10 leg) + Sanctified event;
    REFUSED stamps 60-day expiring
    `religion.sanctification_refused` (−10 leg) +
    SanctificationRefused event; 10% of refusals additionally
    fire ReligiousRebellion + 30-day kingdom-wide pressure
    modifier.
- **`Kingdom/Religion/ConvertProvinceDriver`** — `launch`
  validates `KingdomCapability.CONVERT_PROVINCE` + 100b
  treasury cost; stamps permanent
  `religion.conversion_campaign.<provinceId>` modifier whose
  appliedAtTick = start. Daily sweep removes the campaign
  modifier after 30 days (auto-completes; tension lifts on
  next ReligionAuthorityEngine tick).
- **`KingdomCapability.CONVERT_PROVINCE`** + satisfier table
  entry (King OR Diplomat OR Treasurer; OR-semantics matches
  D3.3b).
- **`WarEngine.declareWar`** extended with HOLY_WAR
  validation: refuses unless attacker has an official religion
  declared. (Slice 2 reserved this hook; Slice 3 lights it up.)
- **Sanctification-on-succession hook** in
  `NobilityEventDispatcher.runSuccession`: clears any prior
  reign's sanctified marker + calls
  `attemptSanctification` if an heir was seated.
- **6 new KingdomEvent subtypes**: Sanctified,
  SanctificationRefused, ReligiousRebellion,
  ConversionCampaignStarted, OfficialReligionDeclared,
  AgeCycleTransition.
- **3 new tick systems** (ReligionAuthority 202,
  ConvertProvince 203, AgeCycle 204).
- **3 new debug commands**: `/litv kingdom debug sanctify`,
  `convert`, `age_cycle`. Describe extended.

#### Decisions worth recording

- **Per-province religion = culture default in v1.** The
  prompt's "soft religion" model expects per-province dominant
  religion data; v1 doesn't carry per-province religion state.
  Fallback: `ReligionRegistry.dominantReligionFor(culture)`
  serves as the dominant religion for every province. Means
  tension only fires when the kingdom OVERRIDES its culture
  default. Future: a `Province.dominantReligionId` field +
  per-NPC adherence aggregation pipeline.
- **Sanctification doesn't simulate per-NPC priest
  disposition.** v1 uses ruler legitimacy as a proxy for
  "would the senior priest grant?". Real per-NPC PIETY trait
  + ruler-priest relation analysis is a polish target.
- **Renewal path requires explicit sanctification.** A
  DECADENT kingdom can't renew just from high legitimacy alone
  — it needs the sanctified marker. This is intentional;
  renewal requires a religious investiture, mirroring
  historical "blessed restoration" narratives.
- **OfficialReligionDeclared event fires lazily.** Rather than
  hooking the law-enacting path, the religion engine detects
  the choice on its daily tick via the marker modifier
  pattern. One-tick delay between law enactment and event
  fire; acceptable.
- **CONVERT_PROVINCE OR-semantics.** Per the prompt's
  "Diplomat + Treasurer + ruler" wording, strict AND would
  require all three offices simultaneously. v1 follows D3.3b's
  established OR-semantics convention; tighter AND-semantics
  is a Phase 6 polish target.

#### Out-of-scope, flagged for warfare session / Phase 7 / Track E

- **In-world combat units** — warfare session via
  `WarEngine.setResolver` swap from Slice 2.
- **Per-province religion state** — v1 derives from culture
  default; future Province record extension.
- **Per-NPC priest disposition for sanctification** — v1 uses
  ruler legitimacy proxy.
- **ORDINATION_RIGHTS as priest-production mechanism** — the
  D3.4b charter exists; productive consumer is the
  religious-orders system in Phase 7+. v1: charter recorded,
  doesn't gate priest spawning.
- **LAND_GRANT to RELIGIOUS_ORDER grantee binding to actual
  estate** — D3.4b accepts the grantee kind; data flow exists.
  Phase 6+ would extend to real estate transfer / structure
  spawn.
- **HOLY_WAR's score boost during HOLY periods** — Slice 2
  reserved this; v1 doesn't track religious calendar HOLY
  periods at the war-engine level.
- **Per-culture age-cycle tuning overrides** — v1 single
  defaults; per-culture overrides via CultureBundles is a
  follow-up matching Slice 1's RebellionThresholds pattern.
- **Voluntary union counter-offer with VASSALAGE** — Slice 1
  shipped voluntary union and conquest-vassalage; the
  counter-offer mid-petition path is polish.
- **Per-event newsfeed importance levels** — accumulating
  scope; Phase 7 polish target.

#### Phase 6 retrospective (S1 + S2 + S3)

- **20+ new files; ~30 modified.** Span: rebellion / collapse /
  merger (Slice 1), war substrate + engine (Slice 2),
  age-cycle + religion (Slice 3).
- **Locked design fully landed:** culture-per-default
  rebellion thresholds, war shell (B) stub handoff with
  Battle record, war-wins precedence on merger conflict, soft
  religion model, all enacted.
- **No new top-level Kingdom CODEC fields.** Phase 6 added one
  KGD field (wars list in Slice 2) and one KingdomEventsData
  field (successionCount in Slice 3); everything else rides
  D1's modifier infrastructure with namespaced ids.
- **Determinism throughout.** Every stochastic outcome
  documented seed tuple: rebellion outcome, vassal rebellion
  fire, intrigue, sanctification, battle resolution. Same
  world seed produces same political-churn trajectory.
- **Pre-D3.6 migration:** built into Slice 1's
  CollapseEngine. Pre-D3.6 saves with low-stability kingdoms
  get a 30-day grace period via `pre_d36_grace.applied`
  marker + `pre_d36_grace.buff` modifier before the collapse
  clock starts.
- **No build verification.** Maven blocked locally;
  cross-references reviewed manually across slices.

---

### 2026-05-10 — Track D3.6 Slice 2 landed (war system v2, political shell)

Second of three Phase 6 slices per the user-locked split. Politics
of war: declaration with capability gating, levy aggregation
through fealty chain, scheduled battles via a stub-handoff
resolver, scoring + threshold-driven resolution, victory-goal
application including conquest absorption via Slice 1's merger
engine, peace-offer flow through the audience loop.

#### User-confirmed locks honoured

- **War shell (B)** — Battle record carries every input the
  warfare session will need (attacker/defender levies,
  leadership multipliers, terrain modifier, supply modifier,
  deterministic seed). `BattleResolver.DEFAULT` ships the
  scoring formula; `WarEngine.setResolver` lets the warfare
  session swap implementations without touching the substrate.
- **War wins precedence on merger** — already enforced in Slice
  1 via `MergerEngine.canMerge`; `WarEngine.applyVictoryGoals`
  bypasses the precedence check by calling
  `triggerConquestMerger` directly when the war is already
  resolved.

#### CC's calls for this slice

- **Battle interval = 5 game days.** Tunable in
  `WarEngine.BATTLE_INTERVAL_DAYS`. With STALEMATE_TICKS=120d
  this gives ~24 battles per war ceiling — sufficient for
  scoring to reach victory thresholds without dragging on
  forever.
- **Casus-belli legitimacy table.** TERRITORIAL_CLAIM=−3,
  RECLAIM_VASSAL=0, HONOUR=−8, HOLY_WAR=+2 (positive!),
  SUCCESSION_DISPUTE=−2, RECONQUEST=−4. Reflects relative
  diplomatic legitimacy of each justification. HOLY_WAR's +2
  is anchored on Slice 3's religious-authority mechanic.
- **Score thresholds.** Attacker victory ≥60, defender
  victory ≤−40 (asymmetric — defender gets edge from
  defending, matches the prompt's "defending against
  aggression produces legitimacy bonus"). Stalemate timeout
  120 days.
- **Stub terrain + supply modifiers.** v1: defender +0.10
  terrain, attacker −0.15 supply, fixed values. The warfare
  session's resolver swap reads these from real terrain /
  claim-distance data.
- **Conquest path detection.** When a WON war's goals include
  BOTH Territory + Vassalize (full conquest signal), the
  defender absorbs into attacker via Slice 1's
  `MergerEngine.triggerConquestMerger`. Pure Territory-only
  is just transfer; pure Vassalize is just treaty creation.
- **Aggressive-war legitimacy malus.** HONOUR + RECONQUEST
  trigger `AGGRESSIVE_WAR_MALUS=−8` on top of the casus-belli
  base. NON_AGGRESSION-broken declaration also adds the
  `BROKEN_TREATY_MALUS=−20`.

#### What this slice ships

- **`Kingdom/War/CasusBelli`** — 6-value enum with per-cb
  metadata (legitimacyOnDeclaration, defaultWarScoreSeed,
  allowsRegimeChange / allowsVassalization / allowsTerritory).
- **`Kingdom/War/WarGoal`** — sealed union with
  Codec.STRING.dispatch:
  - `Territory(villageIds)` — village-list transfer on win.
  - `Tribute(bronzeAmount, recurringDays)` — one-shot or
    placeholder for recurring (v1 logs only on recurring).
  - `Vassalize` — VASSALAGE treaty creation on win.
  - `RegimeChange` — clears defender's ruler so next succession
    picks a new ruler with `legitimacy=35` (usurped path).
- **`Kingdom/War/Battle`** record — 15 fields covering every
  input the warfare session will read. Codec uses
  optionalFieldOf throughout for back-compat. `withOutcome`
  copy-helper for the resolver to stamp.
- **`Kingdom/War/BattleResolver`** interface +
  `BattleResolver.DEFAULT` impl. Default formula:
  `advantage = 0.5 × (levyRatio − 1) + 0.25 × leadershipDiff
  − terrainModifier + supplyModifier`; Gaussian roll with
  std 0.35; outcome bands: > 0.35 attacker, < −0.35 defender,
  middle is DRAW. Score deltas scale with the absolute roll
  size.
- **`Kingdom/War/War`** record — 11 fields, status enum
  ACTIVE/WON/LOST/WHITE_PEACE/STALEMATE. Persisted on the
  attacker's KGD `wars` list (15th field; KGD still 16/16
  after Slice 2). Defender side queries via per-kingdom scan
  (`getActiveWars()` + `findWar(warId)`).
- **`Kingdom/War/LevyComputer.computeLevy`** —
  Σ_villages(currentLevel × 8) × kingdomEfficiency (0.5..1.2
  scaled from stability+legitimacy combined) × war-exhaustion
  (1.0 − 0.10 × activeWars).
- **`Kingdom/War/WarEngine`** — declare/dailyTick/resolveWar
  with full outcome dispatch. Conquest path triggers
  Slice 1's MergerEngine.
- **`PetitionKind.PEACE_OFFER`** + `PetitionPayload.PeaceOffer`
  + AudienceDriver dispatch path that finds the war on
  either party's list and resolves as WHITE_PEACE.
- **4 new KingdomEvent subtypes**: WarDeclared, BattleResolved,
  WarConcluded, PeaceTreatyOffered.
- **Kingdom API additions**: `getWars`, `getActiveWars`,
  `findWar`, `addWar`, `replaceWar`.
- **`WarTickSystem`** — daily tick at priority 201.
- **Debug commands**: `/litv kingdom debug war_declare`,
  `war_resolve`, `war_list`. Describe extended.

#### Decisions worth recording

- **War records live on attacker only.** Eliminates the
  two-side mirror sync problem; defender just scans every
  kingdom's war list for participation. O(n × m) per scan
  but n (kingdoms) and m (wars per kingdom) are both small.
- **Conquest absorbs only on Territory+Vassalize+RegimeChange
  goals concurrently.** Pure Territory transfer doesn't
  collapse the defender; pure Vassalize creates a vassal but
  preserves the kingdom record. Conquest = "I'm taking
  everything" detected by the goal combo.
- **WAR_DECLARATION petition** (D3.4b) and `WarEngine.declareWar`
  coexist. Petition is the player→ruler path (NPC ruler
  approves and triggers declareWar internally); declareWar is
  the actual mechanism. Slice 3 wires the petition's approval
  through declareWar.
- **PEACE_OFFER doesn't validate offering side.** Either party
  can submit a PEACE_OFFER petition to the OTHER party's
  audience; the recipient's ruler approves to end the war.
  v1: any player can submit; capability check is on the
  receiving side via the audience-resolve ruler check.
- **HOLY_WAR's positive declaration legitimacy** is the only
  positive entry — pre-Slice 3 it's symbolic; Slice 3's
  ORDINATION_RIGHTS / sanctification mechanics will gate
  HOLY_WAR's actual usability.

#### Out-of-scope, flagged for Slice 3 / warfare session

- **In-world combat units / armies as entities** — warfare
  session via BattleResolver swap; Phase 6 ships the input
  shape only.
- **Real terrain modifier from per-province terrain data** —
  v1 fixed +0.10 defender bonus.
- **Supply lines from actual claim-distance computation** —
  v1 fixed −0.15 attacker penalty.
- **Recurring-tribute TRADE_DEAL wiring** — v1 logs only.
  Future: create a TRADE_DEAL treaty with embedded tribute
  flow.
- **Regime-change reseat path through NobilityEventDispatcher** —
  v1 clears ruler so next succession picks a new one with
  legitimacy 35; cleaner explicit reseat lands later.
- **HOLY_WAR religious-authority validation** — Slice 3 wires
  this with the priesthood mechanics.
- **Slice 1's WAR_DECLARATION petition payload approval-side
  wiring** — currently AudienceDriver's WarDeclaration handler
  sets WAR via setRelation directly; should call
  WarEngine.declareWar with proper goals. Polish target.

---

### 2026-05-10 — Track D3.6 Slice 1 landed (fragmentation: rebellion + collapse + merger)

First of three Phase 6 slices per the user-locked split. Six
mechanics ship together: per-culture rebellion thresholds,
province-rebellion engine with three-way ruler resolution,
secession executor, vassal rebellion specialization, kingdom
collapse engine with pre-D3.6 grace-period migration, and three
merger paths (marriage-union / voluntary-union / conquest stub).

#### User-confirmed locks honoured

- **War shell (B)** — stub handoff with Battle record reserved
  for Slice 2; this slice ships the conquest-merger hook
  (`MergerEngine.triggerConquestMerger`) that Slice 2's war
  engine will call on victory.
- **Culture-per-default thresholds** — `RebellionThresholds`
  record on `CultureKingdomDefaults`; defaults match prompt
  recommendation (grumble<40 / threat<20 / secession<10 /
  collapse<15 for 60d); per-culture overrides via
  CultureBundles.
- **War wins precedence** — `MergerEngine.canMerge` refuses
  merger when active WAR relation exists between the two
  kingdoms. Marriage-union deferred until war resolves.
- **Soft religion** — Slice 3 lands the religion mechanics.

#### CC's calls (design pass e/f/g/h documented here)

- **(e) Age cycle culture tuning** — deferred to Slice 3 with
  the age-cycle implementation.
- **(f) Successor heraldry** — secession generates FRESH
  heraldry seeded by the new kingdom's UUID + culture + tick;
  marriage-union QUARTERED with survivor's field +
  merged-away's chargeColour + survivor's primaryCharge.
  Voluntary-union and conquest retain SURVIVOR's heraldry
  unchanged.
- **(g) Determinism seeds:**
  - Rebellion outcome: `(kingdomId, "rebellion",
    provinceId, gameDay)`.
  - Vassal rebellion: `(vassalKingdomId, "vassal_rebellion",
    overlordId, gameDay)` with 30% daily fire chance.
  - Collapse + secession + merger: deterministic from input
    order (no stochastic outcome).
- **(h) Newsfeed importance** — v1's newsfeed has no
  importance level (Slice 3/4 polish target). Per-event tags
  encode the kind for future filtering: `rebellion.*` /
  `collapse.*` / `kingdom.merged` / `union.*` / `vassal.*`.

#### What this slice ships

- **`Cultures/RebellionThresholds`** — 5-field record (grumble,
  secessionThreat, secession, kingdomCollapse,
  collapseDurationTicks). DEFAULT matches the prompt
  recommendation. `stageFor(stability)` returns the rebellion
  stage enum. CultureKingdomDefaults adds the 13th field with
  back-compat optionalFieldOf.
- **`Kingdom/Rebellion/RebellionEngine`** — daily entry that
  iterates every province, computes its stage, applies per-stage
  effects:
  - GRUMBLE → `rebellion.grumble.<provinceId>` modifier (-2
    stab, 14d expiry) + RebellionGrumble event + newsfeed.
  - SECESSION_THREAT → if player ruler, creates a
    REBELLION_THREAT petition + chat hint with debug-resolve
    command; if NPC ruler, deterministic auto-resolve via
    seeded RNG (45% negotiate if treasury allows, 35% crush,
    20% accept).
  - SECESSION → immediate SecessionExecutor invocation.
  Three-way resolution effects: NEGOTIATE = 50b cost +
  stability floor 35; CRUSH = -10 legitimacy + stability
  floor 30 + 14-day kingdom-wide -3/-5 modifier; ACCEPT =
  immediate secession.
- **`Kingdom/Rebellion/SecessionExecutor`** — creates a new
  Kingdom record with the province's name + fresh heraldry +
  governor-as-ruler with USURPED_LEGITIMACY=35. Transfers
  villages (`Village.setKingdomId`), removes the province from
  parent, schedules parent claim recompute via
  `setLastProvinceRecomputeTick(-1L)`, applies parent
  malus (-10 stab, -15 leg), fires Secession event +
  newsfeed on both kingdoms.
- **`Kingdom/Rebellion/VassalRebellionDriver`** — vassalage
  loyalty = vassal stability (with modifiers) +/- cultural
  compatibility (hostileCultures -30, vassalEligibleCultures
  +15). Below threshold 20 with 30% daily roll → breaks
  VASSALAGE treaty on both sides + VassalRebelled event +
  newsfeed.
- **`Kingdom/Rebellion/CollapseEngine`** — sustained-below-
  threshold tracker via `collapse.tracker` expiring modifier.
  First detection of below-threshold stamps a permanent
  `pre_d36_grace.applied` marker + a 30-day
  `pre_d36_grace.buff` modifier (+5 stab) BEFORE starting the
  collapse clock — this is the locked pre-D3.6 migration
  grace path, built into the engine so legacy saves don't
  auto-collapse on day 1. After the grace period, sustained
  below threshold past `collapseDurationTicks` triggers
  `collapse(...)`: provinces secede individually; if capital
  province stability ≥ 25 the kingdom persists as a rump with
  permanent `collapse.rump` modifier; otherwise all villages
  orphan (kingdomId=null for D3.1 claim re-resolution) and
  the kingdom is marked with permanent `kingdom.collapsed`
  modifier (history-only record). All charters + treaties
  cascade-break.
- **`Kingdom/Merger/MergerEngine`** — three paths:
  - **Marriage-union**: auto-detected in
    `NobilityEventDispatcher.runSuccession` when the newly-
    seated heir's spouseId matches another kingdom's
    rulerEntityId. Survivor = larger kingdom by village
    count. Heraldry QUARTERED with survivor's field +
    merged-away's chargeColour + survivor's primaryCharge.
  - **Voluntary-union**: `dailyTick` scans for weak kingdoms
    (stab+leg+modSum ≤ 80) and strong neighbours
    (stab+leg ≥ weak + 40); creates a VOLUNTARY_UNION
    petition with 30-day retry cooldown. AudienceDriver
    approval dispatches to `approveVoluntaryUnion`.
  - **Conquest**: `triggerConquestMerger` API reserved for
    Slice 2's war engine.
  `absorbInto` transfers villages + houses + heraldry
  preservation, breaks merged-away's charters and treaties,
  marks merged-away with `kingdom.collapsed` permanent
  modifier.
- **Marriage-union hook** in `NobilityEventDispatcher.runSuccession`:
  after heir is seated, scans all other kingdoms for one whose
  ruler UUID equals the heir's spouseId; on match triggers
  marriage-union (canMerge gates on active WAR).
- **Four new tick systems** registered at priorities 197-200:
  RebellionTickSystem / VassalRebellionTickSystem /
  KingdomCollapseTickSystem / VoluntaryUnionTickSystem.
- **PetitionKind extensions**: REBELLION_THREAT (rules a
  three-way resolve path) + VOLUNTARY_UNION (standard
  approve/deny via audience).
- **PetitionPayload variants**: `RebellionThreat(provinceId)`
  + `VoluntaryUnion(petitioningKingdomId, reason)`. Dispatched
  codec extended.
- **KingdomPetitionPacket.Action additions**: RESOLVE_NEGOTIATE
  / RESOLVE_CRUSH / RESOLVE_ACCEPT. Handler routes to
  `RebellionEngine.resolveThreat` with ruler-only check.
- **9 new KingdomEvent subtypes**: RebellionGrumble,
  SecessionThreat, SecessionThreatNegotiated,
  SecessionThreatCrushed, Secession, VassalRebelled,
  KingdomCollapsed, KingdomMerged, UnionRequest.
- **Kingdom API additions**: `hasModifierWithId(id)`,
  `replaceProvinceStability(provinceId, newStability)`,
  `applyLegitimacyDelta(delta)`, `applyStabilityDelta(delta)`.
- **Debug commands**:
  - `/litv kingdom debug rebel <kingdom> <provinceName> <choice>` —
    direct-resolve a province's rebellion (NEGOTIATE/CRUSH/ACCEPT).
  - `/litv kingdom debug collapse <kingdom>` — force a collapse
    immediately.
  - `/litv kingdom debug merge <survivor> <mergedAway> <path>` —
    force merger (marriage_union / voluntary_union / conquest).

#### Decisions worth recording

- **Grace period is engine-embedded, not a top-level migration.**
  CollapseEngine stamps the permanent grace-applied marker the
  first time it sees a kingdom below threshold; the grace's
  +5 buff is a 30-day expiring modifier. This means brand-new
  kingdoms that drop below threshold also get the grace once
  in their lifetime — slightly more lenient than pure
  pre-D3.6-only migration. Trade-off accepted: simpler
  engine; no first-tick scan; idempotent.
- **No top-level Kingdom CODEC fields added.** Kingdom CODEC
  is at 16/16 (the DFU group cap). All Phase 6 transient state
  rides existing modifier infrastructure (`collapse.tracker`,
  `collapse.rump`, `kingdom.collapsed`, `rebellion.grumble.X`,
  `rebellion.crushed.X`, `pre_d36_grace.*`). KingdomGovernanceData
  was at 14/16 post-D3.5D; rebellion-state petitions ride the
  existing audience-petition list, no new fields needed.
- **REBELLION_THREAT three-button GUI is deferred.** v1 player
  path: chat notification with debug-command hint. NPC path:
  deterministic auto-resolution. The infrastructure
  (RESOLVE_NEGOTIATE/CRUSH/ACCEPT packet actions, AudienceDriver
  dispatch, engine resolve method) is all in place; only the
  GUI buttons are deferred.
- **Marriage-union detection is succession-time only in v1.**
  Cross-house arranged marriages that don't go through
  succession aren't auto-detected. Debug command + future
  Slice 3 (or polish) can extend.
- **Vassal rebellion 30% daily fire chance** — keeps rebellion
  from firing instantly on the first day below threshold. Tunable.
- **Rump capital threshold = 25 stability.** Below that, the
  capital becomes an independent village too. Above, the
  original kingdom name + heraldry persists as a small
  successor with permanent legitimacy malus.

#### Out-of-scope, flagged for Slice 2 / 3 / polish

- **REBELLION_THREAT GUI three-button panel** — Slice 2 or 3
  polish. AudienceScreen extension.
- **Cross-house arranged marriage auto-detection** —
  succession-time only in v1.
- **Per-province culture inheritance on secession** — v1
  inherits parent culture. Future: a Province record could
  carry its own `culture` field.
- **War-conquest merger wiring** — Slice 2 calls
  `MergerEngine.triggerConquestMerger` on war victory.
- **Orphan-village re-resolution as automatic D3.1 trigger** —
  v1 sets `Village.kingdomId = null`; D3.1's claim resolution
  may need an explicit re-entry call. Verified the kingdomId
  clearing works; full re-resolution path verification is a
  follow-up.
- **Newsfeed importance levels** — Slice 3 / polish.
- **Vassal-rebellion stochasticity per culture** — v1 uses
  fixed 30% daily fire chance; could be culture-tuned in a
  future polish slice.

---

### 2026-05-10 — Track D3.5D landed; Phase 5 complete

Final quarter of the user-confirmed Phase 5 four-way split. Six
deliverables pinned by the Slice C out-of-scope list. **Phase 5
ships at 100% with this commit.**

#### Locks honoured

- **Standing kingdom-side authoritative** still holds — Slice D
  bundles the audience state but doesn't move authority.
- **Heir-traits hybrid roll** unchanged from Slice B.
- **PIETY + SCHOLARSHIP traits** still used by NPC ruler AI.

#### CC's calls

- **Submit-rate cooldown = 1000 ticks (1 in-game hour).** Trusted
  petitioners halved (~30 min); hostile petitioners doubled (~2h);
  neutral baseline. Prevents trivial petition spam without
  blocking thoughtful play.
- **Newsfeed coloring scheme.** Red for hostile-context tags,
  green for positive, grey otherwise. Three buckets is the
  smallest scheme that's scannable; richer per-tag colors are
  a polish target.
- **KGD bundling drops legacy fields.** The codec group cap is
  16; supporting BOTH legacy fields AND the bundle would land
  KGD at 17, over the cap. Dev saves lose audience-loop state
  on first load. Documented in `KingdomAudienceData` javadoc.

#### What this slice ships

- **`Kingdom/Audience/KingdomAudienceData`** record bundling
  petitions + playerStandings + playerNobles. Single codec field
  on `KingdomGovernanceData`. KGD now 14/16 (was 16/16 cap-locked
  end of Slice C); two slots free for future work.
- **`Kingdom.fromCodec`** restores audience-bundle fields into
  the existing `petitions` / `playerStandings` / `playerNobles`
  in-memory containers (unchanged in shape).
- **`Kingdom.grantCharter` / `revokeCharter` / `ratifyTreaty` /
  `breakTreaty`** all append a newsfeed entry on success.
  Charter grant: "charter.granted"; revoke: "charter.revoked";
  ratify: "treaty.ratified"; break: "treaty.broken".
- **`IntrigueDriver.sowDiscontent`** appends "intrigue.success"
  or "intrigue.failed" on the source kingdom; "intrigue.discovered"
  on the target if discovery rolled true.
- **`AudienceLoopDriver.dailyTick`** appends "petition.expired"
  for each pending → EXPIRED transition.
- **`Kingdom.revokeCharter`** TITLE_GRANT-with-PLAYER-grantee
  auto-strip path: detects the type + grantee combo and calls
  `stripPlayerNobility(charter.grantee().id())` before firing
  the revocation modifier. LAND_GRANT survives (separate
  charter lifecycle).
- **`PlayerStanding`** extended:
  - `lastSubmitTick` field (default 0L on legacy reads).
  - `SUBMIT_COOLDOWN_TICKS = 1000L` (1 in-game hour).
  - `canSubmitAt(currentTick)` predicate with trust-band scaling.
  - `withSubmitAt(tick)` copy-helper.
- **`Kingdom.stampPlayerSubmit(playerUuid, tick)`** writes the
  stamped record back.
- **`AudienceDriver.submit`** returns `null` when rate-limited.
  `KingdomPetitionSubmitPacket.handle` displays "Slow down — wait
  before submitting another petition." Debug command shows
  "Rate-limited; wait before submitting again."
- **`KingdomBookScreen.CHARTER_REQUEST` section:**
  - Cycle button "Type: <kind>" rotates through TOLL_RIGHTS /
    TAX_EXEMPTION / MARKET_MONOPOLY / ORDINATION_RIGHTS.
  - `charterParamBox` text input (max 64 chars) with kind-
    specific hint label.
  - Submit button parses `input` via `parseCharterParams` into
    the right `CharterParams` variant (with sensible default
    on parse failure: 5% toll rate, 50% tax exempt, "MARKET"
    monopoly type, self-UUID for religious order). Goes
    through the existing `KingdomPetitionSubmitPacket` flow.
- **`KingdomBookScreen.NEWSFEED` section:**
  - Renders `kingdom.getHistory().getNewsfeed()` newest-first.
  - Per-tag color tint (red / green / grey).
  - Two-line per-entry layout: tag + summary on one line,
    tick on the next.
- Two new sidebar entries ("Charter Req" + "Newsfeed") and
  matching nav entries.

#### Decisions worth recording

- **Bundling resets the field cap, doesn't move authority.**
  `Kingdom.playerNobles` is still a LinkedHashMap on the Kingdom
  instance; `KingdomAudienceData` is just the codec round-trip
  shape. In-memory access patterns unchanged.
- **TITLE_GRANT revocation only strips nobility, not land.**
  Players keep their manor coords in `PlayerNobility.landGrantBlockX/Z/size`
  even after the title is revoked. LAND_GRANT charter is
  separately revocable and would call its own strip path
  (Track E polish target).
- **Charter builder is text-input pragma.** Per-kind ranges /
  scopes / dropdowns are richer GUI but text-input + parse +
  fallback is a clean v1. The wire format is the same as the
  full-builder would produce; future polish swaps the input
  widget without touching the submission path.
- **Newsfeed is read-only client surface.** No client → server
  newsfeed mutation; entries flow through `KingdomNewsfeed.append`
  on the server side and round-trip through KGD.
- **Cooldown is on the petitioner, not the petition kind.** A
  player who just submitted an AUDIENCE_GRIEVANCE can't
  immediately submit a CHARTER_REQUEST either. This matches
  the audience-chamber metaphor (one audience per visit, not
  one of each kind).

#### Out-of-scope, flagged for later

- **LAND_GRANT manor structure spawn** — Phase 6+ (castle-
  builder integration). Coords still data-only.
- **Pre-D3.5D save migration** — codec group cap forced the
  drop of legacy fields. Real-world impact bounded since the
  slice window was 24h.
- **PLAYER grantee in non-titled charters as gameplay rules**
  — TOLL_RIGHTS / TAX_EXEMPTION etc. accept PLAYER grantees in
  the data, but no consumer code reads "is this player toll-
  exempt" yet.
- **Per-tag newsfeed pagination** — 64-entry buffer fits in
  one screen; if it grew, the GUI would need scroll. Today
  shown is `min(maxRows, 64)`.
- **Public newsfeed for non-rulers** — currently any player
  who can open the kingdom book can see the newsfeed.
  Sensitive entries (intrigue) leak to non-rulers. Polish
  target: per-tag visibility filter.
- **Cross-kingdom newsfeed bridging** — when two kingdoms
  enter / leave a treaty, both sides' newsfeeds get an entry
  via the mutual `setRelation` cascade in D3.4b. Beyond that,
  there's no automatic cross-kingdom news propagation.
- **TITLE_GRANT charter expiry / auto-renewal** — charters
  don't have an expiry today. Phase 6's decline mechanics may
  add ones for fiscal pressure.

#### Phase 5 retrospective (A + B + C + D)

- **12 new files; ~25 modified.** Span of work covers data
  layer (Petition / PlayerStanding / PlayerNobility /
  KingdomAudienceData / NewsfeedEntry), driver layer
  (AudienceDriver / AudienceLoopDriver / NpcRulerAuditor /
  HeirTraitRoll / KingdomNewsfeed), wire layer
  (KingdomPetitionPacket / KingdomPetitionSubmitPacket), GUI
  (3 new sections in KingdomBookScreen), tick scheduling
  (priorities 195-196), and 9 new KingdomEvent subtypes
  (PetitionSubmitted/Resolved, StandingChanged, PlayerEnnobled,
  PlayerLandGranted, NewsfeedAppended, etc.).
- **Locked design fully landed:** kingdom-side standing
  authoritative; heir-traits hybrid roll; PIETY + SCHOLARSHIP
  trait axes; four-way phase split.
- **Player UX surface:** AUDIENCE section (pending list +
  approve/deny/withdraw + grievance submit + title/land
  request); CHARTER_REQUEST section (full builder for niche
  charter types); NEWSFEED section (color-tagged event log).
  All flows server-authoritative with capability gating.
- **No build verification.** The harness blocks maven; cross-
  references reviewed manually across slices.

---

### 2026-05-10 — Track D3.5C landed (titled grants flow + newsfeed surface + charter request GUI)

Third quarter of the user-confirmed Phase 5 four-way split. Five
deliverables — all pinned by the Slice B out-of-scope list. The
centerpiece is the titled-grants flow: a player can now actually
become a noble of a kingdom via a CHARTER_REQUEST petition that
the ruler approves through the audience screen.

#### User-confirmed locks honoured

- **Standing kingdom-side authoritative** (Slice A) extended to
  ennoblement: `Kingdom.playerNobles` is the single source per
  granting kingdom. A player ennobled by kingdom A is NOT
  automatically noble in kingdom B.

#### CC's calls (deferred to user if surprising)

- **PLAYER GranteeKind value.** Cleaner than overloading
  NOBLE_NPC. Five existing values + PLAYER = six. Backwards-
  compatible — old saves never serialized PLAYER, so the codec
  enum dispatch doesn't see it on legacy reads.
- **Charter request GUI = two flagship buttons.** "Request title
  (rank 1)" + "Request land grant" cover the primary
  player-facing flow; the full builder (with kind selector,
  per-kind input forms for TOLL_RIGHTS / TAX_EXEMPTION /
  MARKET_MONOPOLY / ORDINATION_RIGHTS) is deferred to Slice D
  polish since these charter types are niche for player play.
- **Newsfeed lives on KingdomHistoryData.** That class is
  already routed through `KingdomGovernanceData.history` and
  has its own codec. Adding a 4th field to KHD's record codec
  keeps KGD at exactly 16/16 — the DFU `RecordCodecBuilder`
  group cap. Newsfeed != events: newsfeed is transient
  one-line tags ("petition.approved", "treaty.broken"); events
  is curated narrative.
- **Newsfeed buffer = 64 entries.** ~30 in-game days at typical
  1-2 events/day cadence. FIFO trim on append.
- **LAND_GRANT data shape only.** v1 stores manor coords +
  size on `PlayerNobility`; doesn't actually place manor
  blocks. Phase 6 / castle-builder integration lands the
  structure spawn.

#### What this slice ships

- **`Kingdom/Charters/GranteeRef`** — `PLAYER` enum value +
  `ofPlayer(UUID)` factory. Adds a sixth grantee kind alongside
  NOBLE_NPC / HOUSE / GUILD / VILLAGE / RELIGIOUS_ORDER.
- **`Kingdom/Audience/PlayerNobility`** — record (playerUuid,
  rankIndex, ennobledTick, dynastyHouseId, landGrantBlockX/Z/size,
  titleCharterId). All optional fields except playerUuid +
  rankIndex + ennobledTick. Codec uses `optionalFieldOf` with
  defaults so partially-noble records (rank but no land) and
  vice versa serialize cleanly. `freshEnnoblement` factory;
  `withRank` / `withLandGrant` / `withoutLandGrant` /
  `asStripped` copy-helpers; `hasLandGrant()` predicate.
- **`Kingdom.playerNobles`** — LinkedHashMap on
  `KingdomGovernanceData` (16/16; cap reached). API:
  `getAllPlayerNobles` / `getPlayerNobility` / `isPlayerNoble` /
  `ennoblePlayer(playerUuid, rankIndex, charterId, tick)` /
  `grantPlayerLand(playerUuid, blockX, blockZ, sizeCells, tick)` /
  `stripPlayerNobility(playerUuid)`.
- **`AudienceDriver.applyApproval`** — extended for
  `PetitionPayload.CharterRequest` with
  `kind == GranteeKind.PLAYER`. TITLE_GRANT params →
  `ennoblePlayer` + `KingdomEvent.PlayerEnnobled`; LAND_GRANT
  params → `grantPlayerLand` + `KingdomEvent.PlayerLandGranted`.
  Other charter types granted but no nobility shim.
- **`Kingdom/Audience/NewsfeedEntry`** — record (tag, summary,
  tick) with codec; `BUFFER_CAPACITY = 64`.
  **`Kingdom/Audience/KingdomNewsfeed.append`** — helper +
  fires `KingdomEvent.NewsfeedAppended`. Wired into
  AudienceDriver approve/deny paths.
- **`KingdomHistoryData`** — extended with a 4th codec field
  (`newsfeed`) + `getNewsfeed()` / `appendNewsfeed(entry)` API.
- **`KingdomBookScreen` AUDIENCE section** — extended with:
  - "Request title (rank 1)" button — sends CHARTER_REQUEST
    with TITLE_GRANT params, granteeKind PLAYER, rank 1.
  - "Request land grant" button — sends CHARTER_REQUEST with
    LAND_GRANT params at player's current block position,
    default 4-cell footprint.
  - Player nobility status line ("Noble of [Kingdom]: Rank N ·
    manor X,Z (N cells)" or "Commoner") between the petition
    list and submit forms.
- **Three new `KingdomEvent` subtypes:** `PlayerEnnobled`
  (kingdomId, playerUuid, rankIndex, titleCharterId, tick) /
  `PlayerLandGranted` (kingdomId, playerUuid, blockX, blockZ,
  sizeCells, landCharterId, tick) / `NewsfeedAppended`
  (kingdomId, tag, summary, tick).
- **Debug commands:**
  - `/litv ennoblement list <kingdom>` — lists all player
    nobles with rank + manor.
  - `/litv ennoblement grant <kingdom> <rank>` — fast-path
    grants TITLE_GRANT charter + `ennoblePlayer` for the
    calling player, bypassing audience approval.
  - `/litv newsfeed list <kingdom>` — last 20 entries
    newest-first.
- `/litv kingdom debug describe` extended with player-nobles
  count + newsfeed-entries count.

#### Decisions worth recording

- **KGD is now at the codec cap (16/16).** DFU's
  RecordCodecBuilder maxes at 16 group elements. Slice D needs
  to bundle to add new fields — natural targets: bundle
  petitions+playerStandings+playerNobles into a single
  AudienceData sub-record, freeing 2 slots.
- **Player ennoblement uses the same charter system as NPCs.**
  TITLE_GRANT charter with PLAYER grantee writes to
  `Kingdom.playerNobles`; TITLE_GRANT with NOBLE_NPC grantee
  writes to the NPC's existing `NobilityComponent` (D3.2). One
  charter type, two grantee kinds, two storage paths — but the
  charter records are uniform.
- **LAND_GRANT alone implies rank 0.** Per
  `Kingdom.grantPlayerLand`, if a player gets a LAND_GRANT
  without ever having a TITLE_GRANT, they get auto-ennobled at
  rank 0 (lowest noble). Lands a manor for someone "without"
  needs the holder to be at least gentry.
- **Charter request GUI is minimal-but-real.** Two buttons that
  generate proper PetitionPayload.CharterRequest payloads with
  full CharterParams. Not a UI placeholder — the wire format
  is production-grade. Future Slice D builder can call the
  same helper methods.
- **Submitter UUID in PetitionPayload.CharterRequest doesn't
  have to match the petitioner.** The granteeId is whoever the
  charter targets; the petitioner is the player who SUBMITTED
  the petition. v1: GUI buttons always set granteeId =
  petitioner UUID (request-for-self), but the data shape
  supports request-on-behalf-of-other for Phase 6+ political
  flows.

#### Out-of-scope, flagged for Slice D / later

- **Full charter-builder GUI** — Slice D. Per-kind input forms
  for TOLL_RIGHTS / TAX_EXEMPTION / MARKET_MONOPOLY /
  ORDINATION_RIGHTS via a kind cycle button + parameter fields.
- **LAND_GRANT manor structure spawn** — Phase 6+ (castle-
  builder integration). v1 only stores coords.
- **In-screen newsfeed panel** — Slice D. Buffer flows to
  client; debug command surfaces it; the GUI panel rendering
  it as a side-panel under the audience-petition list lands
  next slice.
- **TITLE_GRANT charter revocation auto-strips nobility** —
  polish target. Currently `Kingdom.revokeCharter` just marks
  the charter inactive; the player's `PlayerNobility` record
  needs explicit `stripPlayerNobility`. The revocation hook
  is one-liner away.
- **Per-petitioner submit-rate limit** — Slice D polish.
  Currently no per-player throttle on `submit`.
- **Kingdom newsfeed event subscriptions for non-audience
  events** — Slice D / Track E. Slice C wires AudienceDriver's
  approve/deny only; charter grants / treaty changes / intrigue
  still need to call `KingdomNewsfeed.append` from their fire
  sites.
- **PLAYER grantee in non-titled charters** — TOLL_RIGHTS /
  TAX_EXEMPTION / MARKET_MONOPOLY to player UUIDs work at the
  data level (PLAYER kind accepted) but no code path consumes
  them as gameplay rules. Future polish.

---

### 2026-05-10 — Track D3.5B landed (audience screen + NPC ruler AI + heir-traits + stale-treaty GC)

Second quarter of the user-confirmed Phase 5 four-way split. Five
deliverables — all pinned by the Slice A out-of-scope list except
the heir-traits roll (locked design) and stale-draft GC (slice
polish target promoted to here).

#### User-confirmed locks honoured

- **Heir-traits hybrid roll.** Slice A added the PIETY +
  SCHOLARSHIP axes; this slice ships the actual hybrid formula:
  `child = 0.6 × parent_avg + 0.4 × fresh_roll`. Locked-design
  framing was "hybrid"; CC's call on the 0.6/0.4 split + Gaussian
  std-dev 0.4. NPC-rulers' resolution AI uses the same trait pool.

#### CC's calls (deferred to user if surprising)

- **0.6 inherited / 0.4 fresh-roll for heirs.** Pure inheritance
  produces dynastic stagnation; pure re-roll erases lineage. The
  60-40 split lands a heir who feels like family but not a
  Xerox copy. Tunable in `HeirTraitRoll.INHERITED_WEIGHT`.
- **Stale-draft TTL = 60 in-game days.** A treaty drafted but
  never fully ratified is GC'd. 60 days is long enough for a
  multi-party cross-kingdom flow to complete, short enough that
  forgotten drafts don't accumulate. Fires `TreatyBroken` once
  with reason `"stale draft"`.
- **NPC-ruler approve / deny thresholds = ±25.** Score below
  ±25 leaves the petition pending; expiry sweep handles it. The
  thresholds match the typical signal-floor for trait-weighted
  scoring with 8-axis input + ±15 standing nudge.
- **Per-kind trait weights.** Five hand-tuned recipes (see
  `NpcRulerAuditor.WEIGHTS`); not derived from existing data.
  WAR_DECLARATION default-deny baseline −20; BREAK_TREATY −10;
  AUDIENCE_GRIEVANCE +5 (default-approve); CHARTER_REQUEST −5
  (default-skeptical); TREATY_RATIFICATION 0 (purely
  trait-driven).

#### What this slice ships

- **`Networking/KingdomPetitionSubmitPacket`** — client → server.
  Carries a `byte[]` payload encoded via
  `PetitionPayload.CODEC → NBT → NbtIo.write` (with a wrapper
  CompoundTag for non-compound roots so NbtIo can serialize
  them). Decode mirror in `decodePayload`. Supports all five
  kinds from the client; the GUI uses it for AUDIENCE_GRIEVANCE
  in this slice. Other kinds reachable via debug commands.
  Registered in `ModModEvents.registerPayloads`.
- **`Gui/KingdomBookScreen` AUDIENCE section** — new
  `SectionType.AUDIENCE` enum value + sidebar entry + nav entry.
  `buildAudienceWidgets` adds:
  - Per-pending-petition row buttons: `✓` Approve + `✗` Deny
    visible only when local player UUID = `Kingdom.getRulerPlayerId`;
    `Withdraw` button visible only when local player UUID =
    `petition.playerUuid`.
  - `grievanceBox` (StyledEditBox, 256-char limit) +
    `Submit grievance` button at page bottom.
  `drawAudience` renders header + standing summary
  ("Your standing: N [TRUSTED/HOSTILE/neutral]") + scrollable
  pending list. Reads petitions + standings off
  `Kingdom.ClientKingdomCache` — no new sync packet because
  D3.5A's KingdomGovernanceData CODEC already syncs petitions +
  playerStandings. Page handles up to ~9 pending rows; "(+N
  more)" indicator for overflow.
- **`Kingdom/Audience/NpcRulerAuditor`** — daily AI tick that
  scores every pending petition in NPC-ruled kingdoms and
  auto-resolves entries past the threshold. Per-kind
  `KindWeights(Map<TraitAxis, Double>, baseline)` recipe drives
  the scoring formula
  `score = baseline + Σ(axis_weight × axis_value × 30) +
   standing × 0.15`. Player-ruled kingdoms skipped (player is
  ruler; resolves via the audience screen). Vacant / offline
  NPC ruler also skipped — expiry sweep handles those.
- **`Events/TickSystems` + `TickSubsystemRegistry`** — new
  `NpcRulerAuditTickSystem` registered at priority 196.
- **`Npc/Nobility/HeirTraitRoll`** — the locked-design hybrid
  roll. `apply(child, parentA, parentB, random)` writes the
  blended TraitVector; `tryApplyAtBirth(child, parent, level)`
  resolves the spouse via `parent.getSpouseId()` then dispatches.
  Only applies when at least one parent is noble. Wired into
  `ChildBirthGoal.spawnChild` post-family-registration,
  pre-`level.addFreshEntity`.
- **`Kingdom/Audience/AudienceLoopDriver`** — added stale-draft
  treaty cleanup. `STALE_DRAFT_TICKS = 60 × 24000`. Each
  kingdom's daily sweep scans its treaty list for drafts past
  the TTL whose `isActive() == false` (waiting for a party
  signature) and breaks them across every party. Idempotent —
  multiple parties' daily ticks all run the same check; first
  one to fire `breakTreaty` wins, rest no-op.

#### Decisions worth recording

- **Submit packet is byte-encoded NBT.** A simpler stringly
  approach would only support AUDIENCE_GRIEVANCE; the
  byte-encoded path supports the full PetitionPayload union via
  the existing dispatched codec. Trade-off: client + server now
  do an extra NbtIo round-trip per submission. Acceptable —
  petitions are user-paced events, not per-tick traffic.
- **Submission is unauthenticated.** Any player can submit any
  petition kind to any kingdom they know about. The server-side
  effect (when approved) is gated by the kingdom's own
  capability checks. This avoids client-side capability
  mirroring drift; the worst a hostile petitioner can do is
  spam — and the standing-decay-on-deny path naturally
  attenuates that.
- **GUI section reuses existing kingdom sync.** No new sync
  packet because D3.5A's KingdomGovernanceData CODEC already
  flows petitions + playerStandings to the client cache. This
  keeps the network surface minimal.
- **Trait-weighted scoring uses every axis.** The user-confirmed
  PIETY + SCHOLARSHIP axes from Slice A finally become
  load-bearing — PIETY heavy on WAR_DECLARATION (anti) and
  BREAK_TREATY (anti); SCHOLARSHIP heavy on TREATY_RATIFICATION
  (pro) and CHARTER_REQUEST (pro). The full 10-axis pool is
  reflected somewhere in the recipe table.

#### Out-of-scope, flagged for Slice C / D

- **CHARTER_REQUEST submit verb in GUI** — Slice C. Needs a
  charter-builder panel for the params (currently only debug
  commands construct CharterRequest payloads).
- **Player ennoblement via TITLE_GRANT charter** — Slice C.
  Player gets a NobilityRecord shim when an approved
  TITLE_GRANT charter targets them.
- **LAND_GRANT manor placement** — Slice C / D. Data shape
  exists; actual structure spawn lands later.
- **Newsfeed surface for resolved petitions** — Slice D. The
  `PetitionResolved` event fires today but isn't surfaced
  anywhere except chat / debug logs.
- **Per-kingdom petition-throttle** — polish target. v1 has no
  per-petitioner submit rate limit; the per-target petition TTL
  + standing-decay-on-deny attenuates abuse but doesn't
  prevent it.
- **NPC ruler audit's daily run cap** — polish. v1 resolves
  every pending petition that crosses threshold every daily
  tick; no per-day decision budget. With APPROVE_THRESHOLD=±25
  and Gaussian-distributed scores this lands at ~3-5
  resolutions per kingdom per day in practice.

---

### 2026-05-10 — Track D3.5A landed (audience loop + standing + trait-pool extension)

First quarter of the user-confirmed Phase 5 four-way split. Three
substrate items arrive together: a server-authoritative petition
queue replacing ad-hoc player→kingdom RPC; per-kingdom player
standing with a clean band-trigger model; the trait-pool extension
that the locked design pinned to this slice.

#### User-confirmed locks honoured

- **Standing kingdom-side authoritative.** `Kingdom.playerStandings`
  LinkedHashMap is the single source. Players carry no mirror; a
  player's reputation with kingdom X is whatever X remembers.
- **Trait pool extended with PIETY + SCHOLARSHIP axes.** `TraitAxis`
  now lists 10 axes; `TraitVector` constructor + codec extended;
  old saves load neutral on the new axes.
- **Four-way phase split.** Slices A/B/C/D as previously sequenced;
  this is Slice A.

#### CC's call (deferred to user if surprising)

- **Standing band thresholds.** TRUSTED=+50, HOSTILE=−50, range
  ±100. AUDIENCE_GRIEVANCE auto-resolves at the band boundary
  (trusted → auto-approve; hostile → auto-deny). Other kinds
  always require explicit ruler resolution.
- **Standing deltas.** APPROVE=+5 (CHARTER_REQUEST=+10 since the
  grant has more weight); DENY=−3 (diplomatic asks
  WAR_DECLARATION/BREAK_TREATY=−1 since they're not personal
  slights); EXPIRE / WITHDRAW = 0.
- **Decay rate.** 1 step toward 0 every 7 in-game days. Applied
  unconditionally each daily tick; partial-day catchup is in
  `applyDecay`.
- **Petition TTL.** 7 in-game days before a PENDING petition
  auto-EXPIREs.
- **Resolved-petition retention.** 30 in-game days before GC.

#### What this slice ships

- `Kingdom/Audience/Petition` record — id, playerUuid, kingdomId,
  kind, payload, submittedTick, status, resolvedTick, resolvedBy,
  resolutionNote. Lifecycle factories
  (`asApproved` / `asDenied` / `asExpired` / `asWithdrawn`) +
  query helpers (`isPending` / `isResolved` / `isExpiredAt`).
- `Kingdom/Audience/PetitionKind` enum — five kinds covering the
  player→kingdom verbs that previously had no political path
  (TREATY_RATIFICATION / CHARTER_REQUEST / AUDIENCE_GRIEVANCE /
  WAR_DECLARATION / BREAK_TREATY).
- `Kingdom/Audience/PetitionPayload` sealed union — variant
  records per kind; `Codec.STRING.dispatch` follows the existing
  `RouteSegment` / `CharterParams` pattern. CHARTER_REQUEST
  carries the full `CharterParams` so approval just calls
  `Kingdom.grantCharter` without re-parsing.
- `Kingdom/Audience/PlayerStanding` record — score, lastDecayTick,
  lifetimeFavour, lifetimeWrath; clamped at ±100; thresholds
  TRUSTED=+50 / HOSTILE=−50.
  `applyDecay(currentTick)` does multi-step partial-day catchup.
- `Kingdom/Audience/AudienceDriver` — server-side resolver:
  `submit` / `approve` / `deny` / `withdraw` returning
  `ResolutionResult` records. Approval dispatches to
  kind-specific effect handlers; failures (missing target,
  vassal-blocked WAR, stale treaty) return "FAIL:..." and the
  caller treats as denial.
- `Kingdom/Audience/AudienceLoopDriver.dailyTick` — petition
  expiry sweep + resolved-entry GC + standing decay across every
  kingdom in the world. Wired as `AudienceLoopTickSystem`
  (priority 195, immediately after province daily).
- `Kingdom.petitions` (List<Petition>) +
  `Kingdom.playerStandings` (LinkedHashMap<UUID,PlayerStanding>)
  on `KingdomGovernanceData` (now 15/16 fields). Top-level
  `Kingdom.CODEC` stays at 16/16.
- `Kingdom` API: `getPetitions` / `getPendingPetitions` /
  `findPetition` / `submitPetition` / `replacePetition` /
  `removePetition` / `sweepPetitions` for the queue;
  `getAllPlayerStandings` / `getPlayerStanding` (auto-creates
  fresh on first lookup) / `adjustPlayerStanding` /
  `decayPlayerStandings` for standing.
- `KingdomEvent` extended with three new subtypes:
  `PetitionSubmitted` / `PetitionResolved` (outcome string +
  standingDelta) / `StandingChanged` (delta + newScore).
- `KingdomPetitionPacket` (APPROVE / DENY / WITHDRAW) — server
  enforces ruler-only for resolve verbs (player-as-ruler check
  via `Kingdom.getRulerPlayerId`) and petitioner-only for
  withdraw. Note string clamped at 256 chars. Registered in
  `ModModEvents.registerPayloads`.
- `TraitAxis` enum extended with PIETY (Worldly / Devout) and
  SCHOLARSHIP (Unlettered / Scholarly). `TraitVector` 8-arg
  constructor → 10-arg; codec adds two `optionalFieldOf` entries
  with default 0f so old saves load with neutral PIETY +
  SCHOLARSHIP scores.
- Top-level debug commands:
  - `/litv petition list <kingdom>`
  - `/litv petition submit_grievance <kingdom> <text>` (player
    only — uses sender's UUID)
  - `/litv petition approve|deny <kingdom> <petitionUuid>`
  - `/litv standing get <kingdom>` (sender's standing)
  - `/litv standing set <kingdom> <delta>` (debug; fires
    StandingChanged)
- `/litv kingdom debug describe` extended with pending-petition
  count + tracked-standing count.

#### Decisions worth recording

- **PetitionPayload.CharterRequest carries CharterParams
  directly.** Saves the approval handler from re-parsing; keeps
  the petition self-contained. `granteeKindName` is a string
  rather than the enum because the codec dispatch field is
  already taken; round-tripping through `valueOf` at apply time.
- **WAR_DECLARATION approval mirrors WAR on the target kingdom.**
  AudienceDriver calls `setRelation(target, WAR)` on both sides;
  the cascade-break of cooperative treaties from D3.4b runs
  twice (once per side) and converges because `breakTreaty` is
  idempotent.
- **TREATY_RATIFICATION is per-party.** Each party's ruler
  ratifies via their own audience chamber; the approve handler
  checks if all parties have signed and only fires
  `TreatyRatified` then. A drafted-but-stale treaty (one party
  ratified, other never showed up) sits dormant until the
  draft-side party breaks it. Cleanup of dormant treaties is a
  Slice B+ polish target.
- **PlayerStanding tracked-on-first-lookup.** `getPlayerStanding`
  creates a zero-baseline record on first call so callers don't
  branch null. The map stays bounded by player count per
  kingdom; in practice ~10s of entries.
- **No GUI surface yet.** KingdomBookScreen unchanged. Slice A
  ships infrastructure; Slice B's audience screen will surface
  the queue + Approve/Deny buttons + a submit panel for the
  three remaining kinds. Debug commands carry the surface for
  v1 and exercise every code path.
- **Heir-traits hybrid roll deferred to Slice B.** The locked
  design ("heir traits hybrid roll") is succession-side; it
  belongs with the Slice B nobility extension, not with the
  audience-loop substrate.

#### Out-of-scope, flagged for later slices

- **AudienceScreen GUI** — Slice B. Includes pending-petitions
  list, Approve/Deny buttons (ruler-only), submit form for all
  five kinds, standing display.
- **CHARTER_REQUEST submission verb in `KingdomPetitionPacket`** —
  Slice B. Needs a charter-builder GUI for the params (currently
  only debug commands construct CharterRequest payloads).
- **NPC-ruler petition resolution AI** — Slice B. NPC ruler
  evaluates pending petitions on a daily AI tick using
  trait-weighted scoring (where PIETY + SCHOLARSHIP feed in).
- **Heir-traits hybrid roll** — Slice B (succession extension).
  PIETY + SCHOLARSHIP axes added in Slice A so the Slice B roll
  has the full pool to draw from.
- **Titled-grants flow with player ennoblement** — Slice C.
  Player gets ennobled via a TITLE_GRANT charter approval; needs
  a player NobilityRecord shim in the existing nobility data
  structures.
- **LAND_GRANT manor placement** — Slice C / D. Data shape
  exists from D3.4b; actual structure spawn lands later.
- **Stale-draft treaty cleanup** — Slice B+ polish.

---

### 2026-05-09 — Track D3.4b landed (charters + treaties + Spymaster intrigue)

Second half of the user-confirmed D3.4 split. Three new mechanics
arrive together: persistent charters with age-scaled revocation
costs; first-class treaties replacing the DiplomaticRelation-as-
authority model with a derived view; Spymaster intrigue with
deterministic outcomes and counter-intelligence.

#### User-confirmed design

- **Charter catalogue:** six types (TOLL_RIGHTS / TAX_EXEMPTION /
  MARKET_MONOPOLY / TITLE_GRANT / ORDINATION_RIGHTS / LAND_GRANT)
  with revocation cost formula `base + 0.5 × ageInDays`
  legitimacy hit + `base × 2` 7-day stability dip. Bases:
  TITLE_GRANT=10, LAND_GRANT=8, MARKET_MONOPOLY=6, TOLL_RIGHTS=5,
  ORDINATION_RIGHTS=4, TAX_EXEMPTION=3.
- **Treaty precedence:** treaties authoritative for cooperation;
  WAR / COLD_WAR residual; declaring WAR while ALLIANCE active
  cascade-breaks the alliance first.
- **Grantee diversity** via tagged-union `GranteeRef` rather than
  forking PowerGrant.

#### Decided here (CC's call per the prompt)

- **Decree disposition** (design pass d): kept as peer concept.
  ISSUE_DECREE remains a stateless chat broadcast from D3.3b;
  doesn't fold into ToggleLaws. Reason: decrees are one-shot
  pronouncements with no lifecycle / parameter state, so the
  laws machinery doesn't fit.
- **Spymaster competence inputs** (design pass e): primary skill
  SOCIAL, secondary LITERACY (matches the Spymaster
  `OfficeDefinition` already registered in D1). Counter-intel
  reads target's same-office competence.
- **Intrigue cooldowns + budget** (design pass f): per-kingdom
  cooldown 7 in-game days between any sow attempts; per-target
  cooldown 21 days; treasury cost 50b per attempt. Counter-intel
  reduction factor 0.5 — a fully-effective target Spymaster
  halves source success probability. Discovery probability
  `0.4 × target competence` when target Spymaster competently
  held.

#### What this slice ships

- `Kingdom/Charters/Charter` record — id, name, grantee,
  granterKingdomId, grantedTick, grantedRulerId, type, params,
  active, revokedTick. `freshGrant` factory; `revoke(tick)`
  copy-helper sets active=false.
- `Kingdom/Charters/CharterType` enum — six types each with
  `revocationBaseLegitimacy` int. `legitimacyHit(ageInDays)`
  applies the formula; `stabilityDip()` returns base × 2.
- `Kingdom/Charters/CharterParams` sealed interface — six
  variant records (`TollRights`, `TaxExemption`, `MarketMonopoly`
  with KINGDOM/PROVINCE/VILLAGE scope, `TitleGrant`,
  `OrdinationRights`, `LandGrant` with block-coords + sizeCells).
  Dispatched codec via `Codec.STRING.dispatch("type", ...)`
  matching the existing `RouteSegment` pattern.
- `Kingdom/Charters/GranteeRef` — `(GranteeKind, UUID)` record;
  GranteeKind = NOBLE_NPC / HOUSE / GUILD / VILLAGE /
  RELIGIOUS_ORDER. Convenience factories per kind.
- `Kingdom/Treaties/Treaty` record — id, type, parties, drafter,
  draftedTick, per-party ratifiedTicks map, termsSummary,
  broken / brokenBy / brokenTick / brokenReason.
  `freshDraft` / `autoMigrated` factories;
  `withRatification(party, tick)` / `asBroken(by, tick, reason)`
  copy-helpers; `isActive` / `isAwaitingRatificationFrom` /
  `involves` / `overlordOf` / `vassalOf` queries.
- `Kingdom/Treaties/TreatyType` enum — ALLIANCE / NON_AGGRESSION /
  TRADE_DEAL / VASSALAGE each with `legitimacyHitOnBreak` and
  `stabilityDeltaOnBreak` numbers. ALLIANCE = −15 leg, −10 stab;
  VASSALAGE = −20 leg, −15 stab; NON_AGGRESSION = −5 leg, −3 stab;
  TRADE_DEAL = −8 leg, −5 stab.
- `Kingdom/Intrigue/IntrigueAttempt` record — rolling buffer
  capacity 28 (~12 in-game weeks). Carries source / target /
  province / spymaster ids, success / discovered flags, stability
  hit applied.
- `Kingdom/Intrigue/IntrigueDriver.sowDiscontent` — full mechanic.
  Deterministic seed `(sourceKingdomId, "intrigue",
  targetKingdomId, gameDay)`. Cooldown checks (per-kingdom +
  per-target). Treasury cost 50b. Source competence × 0.5
  baseline success probability, reduced by counter-intel.
  Stability dip −8 to target province via 14-day expiring
  `KingdomModifier` tagged
  `intrigue.discontent.<sourceKingdomName>`.
- `Kingdom.lawInstances` already at 10 fields in
  `KingdomGovernanceData`; this slice adds three more
  (`charters`, `treaties`, `intrigueHistory`) bringing it to
  13/16. Top-level `Kingdom.CODEC` stays at 16/16.
- `Kingdom.getRelation(otherKingdomId)` rewritten as derived
  view per the user-confirmed precedence:
  1. Active VASSALAGE / ALLIANCE treaty → ALLIANCE.
  2. Active TRADE_DEAL → TRADE.
  3. Residual WAR / COLD_WAR map wins over NEUTRAL-from-NON_AGG.
  4. Active NON_AGGRESSION → NEUTRAL.
  5. Otherwise NEUTRAL.
- `Kingdom.setRelation(WAR)` cascade-breaks any active
  cooperative treaty involving the target, applies legitimacy /
  stability hits.
- `Kingdom` API for charters: `getCharters` / `findCharter` /
  `chartersFor` / `activeChartersOfType` / `grantCharter` /
  `revokeCharter` / `removeCharter`. Revoke applies the
  age-scaled legitimacy hit and 7-day stability-dip modifier.
- `Kingdom` API for treaties: `getTreaties` / `findTreaty` /
  `activeTreatiesWith` / `addTreaty` / `replaceTreaty` /
  `ratifyTreaty(treatyId, tick)` / `breakTreaty(id, by, tick,
  reason)` / `isVassal()` / `overlordKingdomId()`. Break applies
  legitimacy + stability deltas per `TreatyType`.
- `Kingdom` API for intrigue: `getIntrigueHistory` /
  `recordIntrigueAttempt` (FIFO trim) / `lastAttempt` /
  `lastAttemptAgainst`.
- `KingdomCapabilityEvaluator.evaluate` extended: vassal
  kingdoms (active VASSALAGE treaty as vassal side) get DENIED
  on DECLARE_WAR with reason "vassal kingdoms cannot declare
  war independently".
- `KingdomTaxEvent.collectTaxes` extended with VASSALAGE
  tribute outflow before EDUCATION_STIPEND outflow. Default rate
  `DEFAULT_VASSAL_TRIBUTE_RATE = 0.0` preserves net flow per the
  kingdom-plan constraint.
- One-shot migration in `Kingdom.fromCodec`: when
  `governance.treaties` is empty AND legacy
  `relations` has cooperative entries (ALLIANCE / TRADE),
  auto-migrate each into a `Treaty.autoMigrated` record.
  Treaty UUID derived deterministically from
  `(thisKingdomId, otherKingdomId)` so both sides produce the
  same id independently.
- Six new `KingdomEvent` subtypes: `CharterGranted` /
  `CharterRevoked` / `TreatyDrafted` / `TreatyRatified` /
  `TreatyBroken` / `IntrigueLaunched` / `IntrigueDiscovered`.
- Top-level debug commands:
  - `/litv charter list <kingdom>`
  - `/litv charter grant <kingdom> <type> <granteeUuid>`
    (debug fast-path with default params per type)
  - `/litv charter revoke <kingdom> <charterUuid>`
  - `/litv treaty list <kingdom>`
  - `/litv treaty propose <type> <kingdomA> <kingdomB>` (debug
    fast-path: drafts + immediately ratifies both parties)
  - `/litv treaty break <kingdom> <treatyUuid>` (mirrors break
    across every party's copy)
  - `/litv intrigue test_sow <source> <target>`
- `/litv kingdom debug describe` extended with charter/treaty/
  intrigue counts + vassal-status display.

#### Decisions worth recording

- **Treaty UUIDs for migration are deterministic per pair.** A
  pre-D3.4b save with kingdom A having an ALLIANCE relation to
  kingdom B produces the same treaty UUID whether A's Kingdom
  loads first or B's, computed from
  `MSB(A) ^ rotate(MSB(B), 17), LSB(A) ^ rotate(LSB(B), 13)`.
  Both sides converge on a single record, so the
  `replaceTreaty` / `breakTreaty` mirror calls Just Work.
- **Cascade-break only mutates this kingdom's copy.**
  `Kingdom.setRelation(WAR)` cascading to `breakTreaty` only
  touches its own treaty list. The mirror happens because
  `KingdomActionPacket.SET_RELATION` already calls
  `setRelation` on both kingdoms (D3.3b enforcement). Both
  sides' setRelation cascades fire; both sides break their
  copies.
- **VASSALAGE encoded by parties order, not a flag.**
  `parties.get(0)` = vassal, `parties.get(1)` = overlord.
  Treaty constructor doesn't enforce; debug commands ensure the
  order; future audience-loop ratification will validate.
- **Counter-intel = 0.5 reduction at full target competence.**
  Source success probability `0.5 × sourceComp − 0.5 ×
  max(0, targetComp − 1)`. Equilibrium: equally-skilled
  Spymasters give source ~25% success. Tunable.
- **Sown stability hits land on kingdom modifier list.**
  Province-scoped stability is read off the kingdom modifier
  list with the per-province subset filtered in (the modifier
  ID encodes the source kingdom). Phase 5 newsfeed surfaces
  this. Per-province modifier list (Province carries its own
  KingdomModifier list per D3.3a) is the eventual home; this
  slice keeps the modifier on the kingdom for cross-province
  fanout legibility — a polish target.
- **No GUI surface for charters / treaties yet.** Debug
  commands carry the surface for v1; Phase 5 audience loop +
  newsfeed are the natural visual home. Wiring a
  charter-list / treaty-list panel into KingdomBookScreen now
  would land work that gets re-touched immediately by Phase 5.

#### Out-of-scope, flagged for Phase 5 / 6 / Track E / 7

- **Audience-loop ratification** — Phase 5.1. Debug fast-path
  `treaty propose` stands in for v1.
- **Charter / treaty GUI panels in KingdomBookScreen** —
  Phase 5 audience loop + newsfeed cover the surface.
- **TOLL_RIGHTS toll deduction from caravans** — data shape
  only; road-economy follow-up wires the actual deduction.
- **MARKET_MONOPOLY enforcement** — data shape only; merchant
  logic doesn't yet check the active monopoly list.
- **ORDINATION_RIGHTS gating** — Phase 6 religion-as-authority
  pass.
- **Player-as-treaty-party** — VASSALAGE between a player-led
  kingdom and an NPC kingdom needs the player audience-loop UX
  from Phase 5.
- **Provincial newsfeed surfacing of intrigue events** —
  Phase 5.5.
- **Per-province KingdomModifier subset for sown discontent** —
  v1 applies the modifier kingdom-wide; ideally the dip
  attaches to the targeted province only. Polish target.

---

### 2026-05-09 — Track D3.4a landed (law typology refactor + GUI rewrite)

First half of the user-confirmed D3.4 split. The flat
`KingdomLaw` enum becomes one of two coexisting things: the
legacy enum at `Kingdom.KingdomLaw` (kept for save compat + as
the keying namespace for non-refactored call sites) and a new
sealed interface at `Kingdom.Laws.KingdomLaw` (the typology) with
three archetype implementations. All eight existing laws migrate
1:1 into ToggleLaw instances; two new example laws exercise the
ScalarLaw and EnumLaw archetypes.

#### User-confirmed design

- **Phase split:** slice 1 ships 4.1+4.2 (laws); slice 2 ships
  4.3+4.4+4.5 (charters + intrigue + treaties). Each ~1 commit,
  one cohesive testable surface.
- **Example laws:** EDUCATION_STIPEND (ScalarLaw, 0..32 bronze/
  day, default 0) and OFFICIAL_RELIGION (EnumLaw, choices
  none/state_religion/toleration). Locked in alongside the
  framework so the GUI has something to drive.

#### What this slice ships

- `Kingdom/Laws/` package with:
  - Sealed `KingdomLaw` interface — id, displayName, description,
    category, scope, enactmentCost, enactmentCapability,
    requiredActiveLaws, archetype.
  - `ToggleLaw` / `ScalarLaw` / `EnumLaw` records.
  - `KingdomLawCategory` enum (ECONOMY / CRIME / EDUCATION /
    DIPLOMACY / RELIGION / MILITARY / LAND / FAMILY).
  - `KingdomLawScope` enum (KINGDOM / PROVINCE / VILLAGE).
  - `KingdomLawState` enum (DRAFT / PROPOSED / ACTIVE).
  - `EnactmentCost` record (treasury / stability / legitimacy /
    prestige debits).
  - `KingdomLawInstance` record — per-kingdom dynamic state.
    Carries lawId, state, drafterUuid, proposerUuid, enactedTick,
    scalarValue, enumChoice, stateChangedTick. Codec-persisted.
  - `KingdomLawRegistry` — static catalogue. Eight legacy
    ToggleLaws + 1 ScalarLaw + 1 EnumLaw at v1.
- `Kingdom`:
  - `lawInstances: LinkedHashMap<String, KingdomLawInstance>` is
    the canonical storage; `activeLaws Set<KingdomLaw enum>` stays
    as a derived legacy mirror auto-synced by
    `syncLegacyActiveLaws()`.
  - Legacy `enactLaw(KingdomLaw)` / `repealLaw(KingdomLaw)` /
    `hasLaw(KingdomLaw)` keep working as one-line shims.
  - New id-based accessors: `hasActiveLaw(String)`,
    `lawScalar(String)`, `lawChoice(String)`, `findLawInstance`,
    `getLawInstances`.
  - Lifecycle methods: `draftLaw(id, drafter, tick)`,
    `updateDraftScalar`, `updateDraftChoice`, `proposeLaw(id,
    proposer, tick)`, `enactLaw(String, long)`, `repealLaw(String,
    long)`. Enactment applies the law's `EnactmentCost` to
    treasury/stability/legitimacy; repeal refunds half the
    treasury cost and reverses half the stability delta (no free
    rebound).
- One-shot migration in `Kingdom.fromCodec`: if `governance
  .lawInstances` is empty AND legacy top-level `activeLaws` has
  entries, translate each enum to an ACTIVE ToggleLaw instance
  with id = `enumName.toLowerCase()`. Pre-D3.4 saves load
  unchanged at the data level.
- `KingdomGovernanceData` extended with `lawInstances` field
  (now 10/16). Top-level Kingdom codec stays at 16/16; no
  field-count crisis.
- `KingdomLawEffects` unchanged (still reads via legacy
  `kingdom.hasLaw(KingdomLaw)` shim — which now derives from
  `lawInstances`). All existing effect call sites in
  `KingdomLawEffects`, `Company`, `HousePurchaseManager`
  continue to work.
- `KingdomTaxEvent.collectTaxes` extended with the EDUCATION_
  STIPEND outflow: drains kingdom treasury once per tax cycle by
  `(active scalar value) × (number of villages hosting a Scholar)`.
  First observable hot-path effect of a parameterized kingdom-
  tier law.
- `KingdomActionPacket` gained six new verbs:
  - `DRAFT_LAW` (lawId) — gated by ISSUE_DECREE.
  - `PROPOSE_LAW` (lawId) — gated by archetype's enactment
    capability.
  - `ENACT_LAW` (lawId) — gated by archetype's enactment
    capability.
  - `REPEAL_LAW` (lawId) — gated by archetype's enactment
    capability.
  - `UPDATE_DRAFT_SCALAR` (lawId:value) — DRAFT-state only.
  - `UPDATE_DRAFT_CHOICE` (lawId:choice) — DRAFT-state only.
  Legacy `TOGGLE_LAW` deprecated but kept one phase as fallback.
- `KingdomBookScreen` Laws panel rewritten:
  - Per-row state badge: `[available]` / `[draft]` / `[proposed]`
    / `[active]`.
  - Parameter display for ScalarLaw (value + unit) and EnumLaw
    (choice).
  - Single lifecycle action button cycling
    `Draft → Propose → Enact → Repeal` (server enforces gates).
  - ± buttons on ScalarLaw drafts; ▶ cycle button on EnumLaw
    drafts.
  - X cancel button on DRAFT/PROPOSED rows.
- Four new `KingdomEvent` subtypes: `LawDrafted` / `LawProposed`
  / `LawEnacted` / `LawRepealed`. Fired by every server-side
  packet handler.
- New debug command `/litv kingdom debug laws <name>` listing
  every law's archetype, state, parameter, category, gate
  capability, plus active/touched/total counts.

#### Decisions worth recording

- **Two `KingdomLaw` types coexist by design.** The prompt asked
  for "the flat KingdomLaw enum is replaced with a sealed
  KingdomLaw interface". A literal in-place rename would have
  broken the codec persistence (DFU enum codecs serialize via
  `.name()`; the JSON shape encodes `"OPEN_BORDERS"` etc.) plus
  every call site that imports `Kingdom.KingdomLaw` as a static
  enum reference. The two-type compromise: legacy enum stays at
  `Kingdom.KingdomLaw` (used by save format + 9 existing call
  sites); new sealed interface lives at `Kingdom.Laws.KingdomLaw`
  (used by new code, the registry, the GUI, the packet verbs).
  Conversion is symmetric via lowercased id. Future phases can
  remove the legacy enum once all call sites migrate; this slice
  doesn't force that churn.
- **Archetype-authority rule.** ToggleLaws gate on King's
  PASS_LAW capability; ScalarLaws / EnumLaws gate on Chancellor's
  ISSUE_DECREE capability per the prompt's "ruler proposes,
  Chancellor enacts" rule for parameterized laws. Drafting always
  gates on ISSUE_DECREE (Scholar is the literate ministerial
  capacity; v1's capability table doesn't include a dedicated
  DRAFT slot; using ISSUE_DECREE as a proxy keeps the gate at the
  right "literate office" granularity). Repeal uses the same
  capability as enactment per the prompt.
- **Lifecycle without `Effects` registry.** The kingdom-tier
  laws use direct `hasActiveLaw(String)` / `lawScalar(String)` /
  `lawChoice(String)` queries against `Kingdom`. The village-tier
  `LawEffect` interface + registry pattern from `Npc.Laws` is
  rich and reusable, but kingdom-tier hot paths today are sparse
  (5 helpers in `KingdomLawEffects`) and the laws-as-flags model
  beats laws-as-pluggable-effects for a first pass. Slice 2's
  charters + intrigue add new hot paths that may justify a
  shared effect registry; deferred until a concrete need.
- **No category sidebar in the rewrite (yet).** The prompt asks
  for a category-sidebar layout; the rewrite kept the flat list
  (10 laws fit comfortably in the existing book page height).
  The categorisation lives on each law's `KingdomLawCategory`
  field and surfaces in the debug output. Slice 2 or a polish
  pass adds the sidebar UI; it's not load-bearing for the
  done-criterion "drafting / proposing / enacting / repealing
  works through the GUI".
- **Prerequisite enforcement is a stub.** Each law's
  `requiredActiveLaws` list is wired through the codec but no v1
  law sets it and the proposer-side check isn't enforced. Deferred
  to slice 2 (charters introduce dependency chains that will
  stress-test the prerequisite system).
- **EnactmentCost ships at zero across all v1 laws.** Per the
  "Net economic flow at default parameters preserved" constraint.
  ScalarLaw active values produce ongoing flow (EDUCATION_STIPEND
  drains daily) but the one-shot enactment debit is zero. Phase 5
  / playtest data may add costs to politically-significant laws
  (e.g. CONSCRIPTION takes a stability hit on enactment).

#### Out-of-scope — Phase 4 slice 2 / Phase 5+ deferrals

- **Charters and privileges (4.3)** — slice 2.
- **Spymaster intrigue (4.4)** — slice 2.
- **Treaties (4.5)** — slice 2.
- **Decree disposition (design pass d)** — staying as peer
  concept; ISSUE_DECREE remains a stateless chat broadcast,
  unchanged from D3.3b. Decision recorded as "decree as one-
  shot ruler pronouncement, separate from law lifecycle".
- **Spymaster competence inputs (design pass e)** — slice 2.
- **Intrigue cooldowns / budgets (design pass f)** — slice 2.
- **Category sidebar polish** — see decisions.
- **Effect-preview tooltips on laws panel rows** — Phase 5
  audience-loop UI.
- **Lifecycle history per instance** — `stateChangedTick` exists
  on `KingdomLawInstance`; Phase 5 surfaces a "drafted 3 days
  ago by Magistrate X" timeline.
- **Server-side prerequisite enforcement** — see decisions.
- **`KingdomLaw` legacy enum removal** — once all call sites
  migrate to id strings (likely Track E).

---

### 2026-05-09 — Track D3.3b landed (offices + capability gating + office-grant ennoblement)

Second half of the D3.3 split. Wires the kingdom's seven Phase 0 office stubs (Chancellor / Treasurer / Scholar / General / Magistrate / Spymaster / Diplomat) into actual capability gating, both server-side enforcement and client-side grey-out.

#### User-confirmed design

- **Capability gates: OR semantics.** A capability unlocks if EITHER the King OR the named delegated office is competently held. King is sovereign — implicitly satisfies every capability.
- **Ennoblement: SQUIRE — lowest noble rank.** Commoner appointees to kingdom-tier offices (except King) auto-promote to rank index 1 in the culture's nobility table. No auto-house-founding (D3.2b's gate still applies).
- **Competence floor: multiplier ≥ 1.0.** Holder must clear the office's `minLevel` (uses existing `Competence.computeMultiplier`). Vacancy fails the gate; player-held King is sovereign regardless; offline NPCs are assumed competent at minLevel to avoid chunk-load flicker.

#### What this slice ships

- `Kingdom/Capabilities/KingdomCapability` enum — 8 entries covering the operative kingdom-tier actions: PASS_LAW, ISSUE_DECREE, DECLARE_WAR, LEVY_TROOPS, DRAFT_TREATY, INTRIGUE_FOREIGN, INVESTIGATE_CRIME, ISSUE_CURRENCY.
- `Kingdom/Capabilities/KingdomCapabilityEvaluator.evaluate` — pure server-side function returning `Result(allowed, satisfyingOfficeId, reason, allReports)`. Walks the satisfier list per capability, first competent satisfier wins. Returns rich per-office report state (skill level, multiplier, "vacant" / "under-skilled (LITERACY 35 < 50)" / "competent (LITERACY 75)") for tooltip surfaces.
- `Kingdom/Capabilities/ClientCapabilityCheck` — coarse client-side mirror: tests "is at least one satisfier office held" without skill check (client lacks NPC skill data). Builds tooltip strings: "Authorised by Chancellor" / "Requires King or Magistrate (all vacant)".
- Capability table (OR semantics):
  - PASS_LAW          → King OR Magistrate
  - ISSUE_DECREE      → King OR Chancellor
  - DECLARE_WAR       → King OR General
  - LEVY_TROOPS       → King OR General
  - DRAFT_TREATY      → King OR Diplomat
  - INTRIGUE_FOREIGN  → King OR Spymaster
  - INVESTIGATE_CRIME → King OR Magistrate
  - ISSUE_CURRENCY    → King OR Treasurer
  Royal Scholar grants no capability of its own — Phase 4 will read its competence as a multiplier into Chancellor / Magistrate effectiveness.
- `KingdomActionPacket` server-side enforcement layered on top of the existing `PowerGrant.hasPower(ENACT_LAW)` check:
  - `TOGGLE_LAW` → `PASS_LAW`
  - `ISSUE_DECREE` → `ISSUE_DECREE`
  - `SET_RELATION` with `WAR` → `DECLARE_WAR`; with `ALLIANCE` / `TRADE_PACT` → `DRAFT_TREATY`
  Denial sends a chat message: "Cannot pass a law — no competent satisfier — King: vacant, Magistrate: under-skilled (LITERACY 35 < 50)".
- `KingdomBookScreen`:
  - Law-toggle buttons grey out + show capability tooltip when no PASS_LAW satisfier is held.
  - Issue Decree button greys out + shows tooltip when no ISSUE_DECREE satisfier is held.
  - Relation-change button left always-active (next-relation type isn't known until click); server enforces.
- `Npc/Nobility/OfficeAppointmentEnnoblement.onSeat` — called from `OfficeElection.runElection` and `OfficeElection.seatNpc`. No-op for non-kingdom orgs, the King office, NPCs already at rank ≥ 1, non-monarchy cultures, and unloaded NPCs. Sets `nobility.rankIndex = 1`, logs an "ennoblement.<officeId>" 3-day kingdom modifier (+1 legitimacy) for legibility.
- Debug: `/litv kingdom debug capabilities <name>` walks all 8 capabilities, prints ALLOW/DENY + per-office report lines.

#### Decisions worth recording

- **Layered server-side gate.** PowerGrant authorizes the *player* (does this player hold an office that grants this power?). KingdomCapability authorizes the *kingdom* (does the kingdom have anyone competent enough to perform this action?). Both must pass. PowerGrant predates capability and stays. The two checks address different questions and overlap cleanly without conflict.
- **Coarse client gate.** The client doesn't have NPC skill levels (we don't sync the SkillComponent over packets), so client-side grey-out tests only "office held" — the server still does the real competence check at action time. Net effect: a slightly-permissive client gate avoids flickering on chunk load while the server enforces truth and surfaces the actual reason via chat. We considered syncing a per-kingdom capability cache (server computes, attaches to Kingdom record, ships over the existing CODEC sync) but the Kingdom record is already at the 16-field DFU codec cap; bundling into KingdomGovernanceData adds churn for one screen's grey-out logic. The coarse-then-server approach is the smaller blast radius.
- **Offline-NPC assumption.** When a holder NPC isn't loaded, the evaluator assumes competent-at-minLevel rather than denying. Reason: capability state would otherwise flicker as chunks load and unload — a reload of the world chunk holding the Magistrate would briefly forbid PASS_LAW even though no real change happened. The assumption errs on the permissive side; players whose office holders are persistently broken will notice via the daily provincial / kingdom UI ("Magistrate vacant") and the chat denial when they try to act.
- **King-only capabilities not modeled.** The "Tiered" option from the design pass (King-reserved capabilities like PASS_LAW that even a Magistrate can't substitute for) was rejected per the user's "OR semantics" choice. Phase 4 / 6 (laws-and-intrigue, decline-and-conflict) may layer kingdom-stability gates on specific capabilities (e.g. DECLARE_WAR requires stability ≥ N) that are stricter than the office check; those go in the evaluator's reason chain when added.
- **Ennoblement as smallest mutation.** Setting `rankIndex = 1` is the minimal change: it doesn't auto-found a House (D3.2b's gate still applies), doesn't change profession, doesn't move the NPC. The kingdom modifier "office.ennoblement.<id>" expires after 3 days, so it doesn't pile up. Holders who continue serving competently can later auto-found a House through the normal D3.2b path; holders who get vacated drop back to commoner only on rank-reset by Phase 4 mechanics (none today — once ennobled, always ennobled).
- **Tooltip composition.** Tooltips read off the capability table directly; no string-table localisation today (matches the rest of the kingdom UI).

#### Out-of-scope, flagged for Phase 4 / 5 / 6 / 7

- **Royal Scholar competence boost into Chancellor / Magistrate** — registered in OfficeRegistry today but not read by the evaluator. Phase 4 will multiply its competence into law / decree effectiveness.
- **Capability table re-balance** — the OR semantics + minLevel floor were chosen for the v1 sense of "is this kingdom plausibly able to act". Playtest data may push toward stricter gates (multiplier ≥ 1.05 to require a meaningfully effective holder, or AND semantics for capstone capabilities like ISSUE_CURRENCY).
- **Stability / legitimacy gates layered on capabilities** — Phase 4 / 6's rebellion + decline mechanics may add "DECLARE_WAR requires stability ≥ 25" type gates on top of the office check.
- **Office competence display in KingdomBookScreen** — today the tooltip shows "Authorised by Chancellor" but doesn't show the Chancellor's skill level / effectiveness multiplier. Phase 5's audience UI surface is the natural home for that.
- **Capability denial logging in kingdom history** — denied actions today only chat to the player; future legibility could record "Player X attempted to declare war but no General was seated" as a kingdom history event.
- **Player rank handling.** Player-held King is sovereign and bypasses the skill check entirely (no client-side player skill on the server), but a player holding a delegate office (e.g. Magistrate) currently registers as "held by player (capability check requires NPC skill)" and fails the gate. Phase 5's player-experience pass needs to decide whether players holding delegate offices should auto-satisfy the gate, ramp via in-game progression, or be forbidden from holding delegate offices.

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
