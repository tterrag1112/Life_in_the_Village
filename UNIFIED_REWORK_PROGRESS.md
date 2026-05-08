# UNIFIED REWORK PROGRESS

Append-only log. Most recent entry at bottom.

Status values: `Not-Started`, `In-Progress`, `Implemented`, `Tested`,
`Done`. `Done` means: implemented, tested in-world, no known issues,
spec matches reality.

## Track A — Placement consolidation

| ID | Task | Status | Notes |
|---|---|---|---|
| A1a | Wire V2 into VillageSpawner (parallel branch) | Superseded by A4 | Flag + adapter landed; absorbed into unconditional A4 routing. |
| A1b | V1 cleanup + ZoneRegistry migration | Not-Started | Gates B/C/D. Unblocks decoration P0a-18. Reordered after A2/A3/A4. |
| A2 | Culture unification | Implemented | Sub-bundle landed; V2 Culture/CultureRegistry deleted; smoke test pending. |
| A3 | Variant unification | Implemented | VariantResolver landed; VariantPicker deleted; V1 + V2 paths route through it; smoke test pending. |
| A4 | VillageSpawner → V2 unconditional + flag deletion | Implemented | V1 branch removed; adaptive_v2 flag + V2Settings + ConfigCommand deleted; V1 caller inventory recorded; smoke test pending. |
| A5 | Measurement run vs V1 baseline | Not-Started | Depends A4. |

## Track B — Decoration finishing

| ID | Task | Status | Notes |
|---|---|---|---|
| B1-15 | HOUSE pilot NBTs (P0a-15) | Not-Started | Hard-fails placement until landed. |
| B1-16 | URBAN variant pack (P0a-16) | Not-Started | |
| B1-04 | MarketStallPlacer subbuilding migration (P0d-04) | Not-Started | |
| B1-12 | GuildHall colour fields | Not-Started | Wires P0a-12 overrides. |
| B2-pass | V2 vocabulary pass on docs 05–11 | Not-Started | Doc-only. Depends A4. |
| B2-05 | Street furniture impl | Not-Started | P1-06..08. |
| B2-06 | Signs and markers impl | Not-Started | P1-09..13. |
| B2-07 | Industry adjuncts impl | Not-Started | P2-01..06. |
| B2-08 | Herb and cottage gardens impl | Not-Started | P2-07..11. |
| B2-09 | Parks and gardens impl | Not-Started | P3-01..04. |
| B2-10 | Farm plot rework impl | Not-Started | P3-05..10. |
| B2-11 | Homesteading impl | Not-Started | P3-11..16. NPC Phase 3+4 already shipped. |
| B3-12-doc | Walls spec rewrite | Not-Started | V2 vocabulary. |
| B3-13-doc | Festivals spec rewrite | Not-Started | V2 vocabulary. |
| B3-14-doc | Cemeteries spec rewrite | Not-Started | V2 vocabulary. |
| B3-12-impl | Walls implementation | Not-Started | Depends B3-12-doc. |
| B3-13-impl | Festivals implementation | Not-Started | Depends B3-13-doc + NPC Phase 5 events (shipped). |
| B3-14-impl | Cemeteries implementation | Not-Started | Depends B3-14-doc. |

## Track C — Roads finishing

| ID | Task | Status | Notes |
|---|---|---|---|
| C1-tr | TradeRoad.java deletion | Not-Started | Migration complete; source is dead code. |
| C1-cv | TravellingGroupEngine synthetic-caravan fix | Not-Started | Carryover from Roads Phase 3b. |
| C2 | Phase 7f Slice 4 connector routing | Not-Started | Depends A4. |
| C3-11 | Phase 11 — player-initiated road construction | Not-Started | |
| C3-12 | Phase 12 — POI subroads | Not-Started | |
| C3-13 | Phase 13 — sea route unification | Not-Started | Folds SeaRoute into world graph. |

## Track D — Kingdom rework

| ID | Task | Status | Notes |
|---|---|---|---|
| D1-01 | Culture kingdom-tier fields | Not-Started | Depends A2. |
| D1-02 | KingdomEventBus peer | Not-Started | Mirror NpcLifeEventBus. |
| D1-03 | Stability scalars | Not-Started | |
| D1-04 | Territory vs membership split | Not-Started | |
| D1-05 | Legitimacy scalar | Not-Started | |
| D1-06 | Estate primitives | Not-Started | |
| D1-07 | Heraldry generator | Not-Started | |
| D1-08 | Office stub completion (7 offices) | Not-Started | Chancellor / Scholar / General / Magistrate / Spymaster / Treasurer / Diplomat. |
| D2 | Section 5 rewrite | Not-Started | Doc-only. Depends A4. |
| D3-1 | Phase 1 — worldgen rewrite | Not-Started | Depends A4 + D2. |
| D3-2 | Phase 2 — houses, ranks, nobility | Not-Started | Depends D3-1. |
| D3-3 | Phase 3 — provinces & offices | Not-Started | Depends D3-2. |
| D3-4 | Phase 4 — laws & intrigue | Not-Started | Depends D3-3. |
| D3-5 | Phase 5 — player experience | Not-Started | Depends D3-4. |
| D3-6 | Phase 6 — decline, conflict, religion-as-authority | Not-Started | Depends D3-5. |
| D3-7 | Phase 7 — polish, scale, longevity | Out-of-scope | Per master plan. |

## Carryover from absorbed plans (not re-tracked here)

- NPC Phase 5 task 33 (textures) — `NPC_PROGRESS.md`.
- NPC Phase 5 task 34 (content pass) — `NPC_PROGRESS.md`.
- NPC deferrals (Office tab GUI, Quest→Request migration, GuildData
  rename, Refugee leader UI, OfficeChange emission, etc.) —
  `NPC_PROGRESS.md`.

---

## Log

### 2026-05-07 — Plan committed

Unified plan written. All four tracks defined. No code changes yet.

Next: A1 — V1 cleanup + ZoneRegistry migration.

### 2026-05-08 — A1a wired

Renamed Track A1 → A1a (wire V2 in parallel) + A1b (delete V1) per
prompt direction. V1 deletion now sequenced after A2/A3 stabilize.

**Code shipped:**
- `Village/Planning/V2/V2Settings.java` — volatile boolean flag,
  default false, runtime-toggleable.
- `Village/Planning/V2/V2VillageSpawnerAdapter.java` — full adapter:
  runs V2 Layers 1-4 (V2FeatureMap → SiteAnalyzer → BuildingSelector
  → ReconciliationEngine → DependencyResolver → PhasedPlanner),
  inlines Layer-5 sub-components (OverlapAuditor, TerrainAdapter,
  ViabilityValidator, VegetationClearer, PadBuilder, RoadPainter)
  while capturing Building references from `BuildingPlacer.placeAndRegister`,
  synthesizes a minimal `VillageLayout`, registers a `Village` in
  `VillageSavedData`, and runs the V1 downstream pipeline
  (FarmPlotPlacer, VillageInhabitantPopulator, VillageDecorator,
  AdjunctPlotRealiser, DecorationPass, TradeRouteManager,
  ConnectorPlanner, VillageSimEngine, GuildBootstrap, HistoryProducer,
  initial laws). Each downstream call is `guard()`-wrapped so a single
  failure logs and does not abort the spawn.
- `Commands/ConfigCommand.java` — `/litv config adaptive_v2 <on|off|status>`.
- `Events/ModModEvents.java` — registered `ConfigCommand`.
- `Village/VillageSpawner.java` — flag-checked branch after
  `VillageTypeData` resolution; V1 path otherwise unchanged.

**Step 3 gaps the V2 path does NOT replicate:**

Items V1 produces that V2 does not (the synth VillageLayout fills
defaults; downstream consumers tolerate or are guarded):
- `Plaza` records (V1 plaza system; V2 has no plaza concept).
- V1 `RoadGraph` populated with gateway nodes / internal edges
  (V2 `RoadNetwork` is a different shape; `GatewayPopulator` and
  `InternalRoadCommitter` are NOT called on V2 path).
- `VillagePlacementEvent` firing (Roads Phase 9 network-alignment
  scoring; intentionally skipped — wire later if Roads consumers
  need it).
- `setupMerchantStalls` (V1 private helper; not callable from
  adapter; merchant stalls will not auto-claim on V2 path).
- `VillageSitePreparer.prepare` whole-village tree clearing
  (V2 does per-building `VegetationClearer` instead).
- `typeData.getTerrainStrategy().execute` whole-village terrain pass
  (V2 does per-building `PadBuilder` instead).
- `findBetterLocalSite` retry on planner failure (V2 returns empty
  on UNVIABLE; no retry).
- `layout.getFeatures().refine(level)` Phase-4 feature-map refine
  (V2 has its own `V2FeatureMap`; the synth layout's V1 FeatureMap
  field stays null).

Field-level mismatches:
- `Style` always RURAL on V2 path. URBAN pack (P0a-16) will not
  surface through V2 until A3 unification or a culture-driven style
  hint lands.
- `villageType` argument is recorded on the resulting `Village` but
  V2 ignores it for layout, building selection, and viability —
  V2 derives those from terrain. `FarmPlotPlacer.placeAll` reads
  `village.getVillageType()` for its config; the caller must pass a
  registered type or FarmPlotPlacer will NPE under the guard.
- `TintPass.Plan.NONE` always on V2 path (no `VariantRegistry` /
  `VillagePaletteResolver` integration; deferred to A3).
- `LayoutSlot.padY` set to V2 `centre.getY()` (surface block);
  may need +1 if a downstream consumer treats padY as the building
  floor — re-evaluate on smoke-test feedback.

Adapter-design decisions worth recording:
- Did NOT call `MinimalSpawner.spawn` directly — its sub-components
  are called inline so Building references can be captured for
  `placedBuildingsAll`. `MinimalSpawner` itself drops them. No V2
  internals were modified.
- Single Random seeded `level.getSeed() ^ ((origin.hashCode() * 31L) +
  villageName.hashCode())` so V2 path is reproducible per (seed,
  origin, name) like V1.
- Static field + `/litv config` chosen over `ModConfiguration.java`
  Properties extension or NeoForge `ModConfigSpec` — runtime
  toggleability is cheapest with the static field.
- Track renumbering (A1 → A1a / A1b) deviates from
  `UNIFIED_REWORK_PLAN.md` text; plan is human-managed and that
  deviation should be reflected if it sticks.

**Smoke test:** pending user-run on superflat. Expected behavior:
- `adaptive_v2=false`: V1 path unchanged; existing world saves keep
  loading.
- `adaptive_v2=true`: village created via V2 with buildings placed,
  roads painted, NPCs spawned via VillageInhabitantPopulator;
  decoration / trade / sim / guild / history / laws guarded — log
  warnings if any of them fail rather than aborting the spawn.

Compile verification not possible in this environment (gradle
toolchain plugin requires network the sandbox blocks; same issue
recorded across NPC_PROGRESS.md).

Next: smoke-test feedback. Then A2 (Culture unification).

### 2026-05-08 — A2 + A3 landed (one cycle)

A2 Culture unification + A3 Variant unification shipped together.
Both paths still need smoke-test confirmation; failures should be
treated as bugs in the merge, not behaviour deltas.

**A2 — Culture unification.**

Sub-bundle name: `CulturePlanningBias` — matches the
`Culture<NounPhrase>` pattern. Lives in `Cultures/CultureBundles.java`
alongside the other 10 sub-bundles. Codec uses `optionalFieldOf` with
`DEFAULT` so older saves load cleanly.

Value-type relocation: `Curvature` and `PlazaShape` moved to a new
`tterrag1112.life_in_the_village.Cultures.Planning` package (separate
from V1's renderer enum `Village.Decoration.Plaza.PlazaShape`, which
keeps its `LINEAR` value and its codec).

Culture-id mapping: V2 only ever registered `default`. All four NPC
starter cultures (Plainfolk, Highmarch, Silkwood, Tidereach) gained
the new sub-bundle as `CulturePlanningBias.DEFAULT` — same dirt-path
+ NATURAL + IRREGULAR + uniform-bias as the deleted V2 default. **No
per-culture customization yet** — flagged here for later authoring.

Files created:
- `Cultures/Planning/Curvature.java`
- `Cultures/Planning/PlazaShape.java`

Files modified:
- `Cultures/Culture.java` — 14-field record (was 13); +planningBias.
- `Cultures/CultureBundles.java` — +CulturePlanningBias record.
- `Cultures/CultureRegistry.java` — 5 starter builders + neutralDefault
  pass `CulturePlanningBias.DEFAULT` as the new arg.
- `Events/ModModEvents.java` — removed the V2 `cultures` reload-listener
  registration. (NPC Phase 6 will reintroduce JSON-driven cultures.)
- 5 V2 callsites + 4 /litv commands: import switched from
  `V2.Culture.Culture` / `V2.Culture.CultureRegistry` to
  `Cultures.Culture` / `Cultures.CultureRegistry`. `culture.biasFor(inc)`
  → `culture.planningBias().biasFor(inc)`. `culture.roadMaterial()`
  → `culture.planningBias().roadMaterial()`.
  `CultureRegistry.INSTANCE.getDefault()` →
  `CultureRegistry.getOrDefault(CultureRegistry.DEFAULT_ID)`.

Files deleted:
- `Village/Planning/V2/Culture/Culture.java`
- `Village/Planning/V2/Culture/CultureRegistry.java`
- `Village/Planning/V2/Culture/Curvature.java`
- `Village/Planning/V2/Culture/PlazaShape.java`
- `data/life_in_the_village/cultures/default.json`

Verification: `git grep V2.Culture` returns only doc comments inside
`CultureBundles.java` referencing the deleted record by name. No
`import` or symbol references survive.

**A3 — Variant unification.**

Per user direction: build `VariantResolver` as a clean combined class;
migrate `VariantSelector` features into it where they fit; leave
unused `VariantSelector` methods alone for V1 (dies in A1b). Both V1
and V2 paths now pull from `VariantResolver`. `VariantPicker` deleted.

`VariantResolver` interface:
```java
class VariantResolver {
    // Per-village state for diminishing returns + maxPerVillage.
    String pickVariantIdForV2(BuildingType, BlockPos pos, BlockPos anchor,
                              int villageRadius, String culture, Style,
                              Random, BuildingAvailability);
    static BuildingVariant findById(String culture, Style, BuildingType,
                                     String variantId);
    static TintPass.Plan planTint(VillageTypeData, BuildingVariant,
                                   Random, Set<DyeColor> neighborColors);
}
```

Capabilities migrated **into** `VariantResolver`:
- From `VariantSelector`: same-culture preference + default fallback;
  `maxPerVillage` cap; diminishing returns (× pow(0.7, count));
  weighted-random pick; synthetic-default fallback.
- From the deleted `VariantPicker`: HOUSE distance-banded preference
  (large near anchor, cottage at edge, with cap-skipping); explicit
  availability check via `BuildingAvailability`.

Capabilities **not** migrated (don't fit V2's flow; corresponding
methods on `VariantSelector` retained, unused by the new resolver):
- Slot-tag scoring (V2 has no slots).
- Village-preferred-tag scoring (V2 doesn't propagate the tag set).
- Style/age preference scoring (V2 is always RURAL/FRESH).

Wiring:
- `V2/Layer4/PhasedPlanner.java` — `State` gains a `VariantResolver`
  instance. Per-building `VariantPicker.pick(...)` → `state
  .variantResolver.pickVariantIdForV2(..., StructureAvailabilityRegistry.INSTANCE)`.
- `Village/Planning/V2/V2VillageSpawnerAdapter.java` — per-PlacedBuilding
  `TintPass.Plan.NONE` replaced with `VariantResolver.findById` +
  `VariantResolver.planTint` against a `NeighborColorIndex`. V2 villages
  now get tinted variants like V1's.
- `Village/VillageSpawner.java` (V1 path) — inline `VariantRegistry.find`
  chain + `VillagePaletteResolver.planFor` replaced with
  `VariantResolver.findById` + `VariantResolver.planTint`. Behaviour
  unchanged; same registry chain runs inside `findById`, same logic
  inside `planTint` (delegates to `VillagePaletteResolver.planFor`).

Files created:
- `Village/Decoration/Variants/VariantResolver.java`

Files modified:
- `Village/Planning/V2/Layer4/PhasedPlanner.java` — import swap +
  `State.variantResolver` field + one call-site change.
- `Village/Planning/V2/V2VillageSpawnerAdapter.java` — imports +
  `NeighborColorIndex` + per-building tint planning.
- `Village/VillageSpawner.java` — V1 spawn loop inline-chain replaced.
- `Village/Planning/V2/Layer3/BuildingAvailability.java` — javadoc
  updated to reference `VariantResolver#pickVariantIdForV2`.

Files deleted:
- `Village/Planning/V2/Layer3/VariantPicker.java`

Verification: `git grep VariantPicker` returns nothing.

**Capability gaps documented (per user note "neither system does
exactly what's needed"):**

The combined `VariantResolver` is V1∪V2 capability-wise (color
planning + scoring + diversity + HOUSE distance-banding +
availability) but does NOT add new capability beyond either source.
Specifically, the user note left undefined what's missing — when
that's known, it lands as a follow-up resolver method or a new
`Resolution` shape without re-touching every call site.

**Carryover left for A1b cleanup:**
- `VillagePaletteResolver.planFor` is now only invoked through
  `VariantResolver.planTint`. After A1b's V1 deletion, the delegating
  call can be inlined and `VillagePaletteResolver` retired.
- `VariantSelector` still has `select()` (used by V1's `PlanContext`)
  and `Fallback.syntheticDefault` (used by `VariantResolver.findById`).
  The first goes when V1 dies in A1b; the second can move into
  `VariantResolver` at that point or stay where it is.
- Unused imports in `VillageSpawner.java` (now-orphaned `VariantRegistry`
  / `VariantSelector` references at the top) left in place for the V1
  matcher path that still uses them.

**Smoke test (pending user-run):**
- `adaptive_v2=false`: V1 spawn produces visually identical villages
  pre-A2/A3 vs post-A2/A3. Same road material, same plaza shape,
  same building variants, same colours.
- `adaptive_v2=true`: V2 spawn now applies tints (was always-NONE
  pre-A3) — colour exclusion / forced TEMPLE-white / TOWN_HALL
  signature should kick in. Same building set per (seed, origin, name)
  modulo the new diminishing-returns weighting on non-HOUSE picks.
- Save / restart / reload with both flag states: cultures persist;
  the new `planningBias` field round-trips through codec.

Differences beyond RNG-from-seed-plumbing on the V2 path are
regressions to investigate.

Next: smoke-test feedback for A1a + A2 + A3. Then A1b — V1 deletion
+ ZoneRegistry migration.

### 2026-05-08 — A4 landed (V2 default; V1 branch removed)

User explicitly accepted the smoke-test risk: A1a / A2 / A3 had not
yet been run against a live world before A4. If a regression hides
in those tracks, A4 amplifies it from "flip the flag back" to
"revert several commits." Recorded so the eventual smoke run knows
to test all of A1a + A2 + A3 + A4 together.

**Code changed:**
- `Village/VillageSpawner.java` — body shrunk from 549 lines to 208.
  `spawnVillage` now performs the unknown-type guard then unconditionally
  returns `V2VillageSpawnerAdapter.spawn(...)`. The 350-line V1
  spawn loop (V1 planner call, refinement retry, V1 placement loop,
  downstream pipeline) was deleted from this file. V1 source files
  (`VillagePlanner`, recipes, `Adaptive` package, `ZoneRegistry`,
  `PlacementMatcher`, etc.) remain on disk pending Track A1b.
- Helpers in `VillageSpawner` are now mostly unused: `findBetterLocalSite`,
  `setupMerchantStalls`, `deriveVillageLevel`, `findSurface` are
  private/package-private and dead. `isFarEnoughFromExistingVillages`
  remains public — still called by `V2VillageSpawnerAdapter`.

**Files deleted:**
- `Village/Planning/V2/V2Settings.java` — flag holder.
- `Commands/ConfigCommand.java` — `/litv config adaptive_v2 <on|off>` toggle.

**Files modified:**
- `Events/ModModEvents.java` — removed `ConfigCommand.register` line;
  left a one-line comment noting where it used to live.

**Verification (Step 4 done criteria):**
- `git grep adaptive_v2 -- src/` → empty. ✓
- `git grep V2Settings -- src/` → only the historical-reference comment
  in `ModModEvents.java`. ✓
- `git grep ConfigCommand -- src/` → only the historical-reference
  comment in `ModModEvents.java`. ✓
- Doc files (UNIFIED_REWORK_PLAN, UNIFIED_REWORK_PROGRESS,
  CONSOLIDATION_INVENTORY, ADAPTIVE-VILLAGE-DESIGN) still mention
  the flag in historical sections — left as-is per the
  "don't restructure docs" constraint.

**Step 2 — V1 caller inventory (for A1b decisions):**

*Spawn entry callers (all auto-route to V2 via spawnVillage):*
- `Events/VillageRealisationSystem.java:265` — normal worldgen
  realisation of planned villages.
- `Kingdom/KingdomSpawner.java:173, 535, 568` — three sites for
  kingdom-driven spawns (capital, vassal, fallback).

These are NOT V1-specific; they go through the renamed entry point
and now hit V2 unconditionally. Safe.

*Direct V1 planner callers (bypass spawnVillage, will compile-break
when V1 dies in A1b):*
- `Commands/MeasureCommand.java` — `/litv measure` (Phase 23.1
  measurement harness) calls `VillagePlanner.plan` directly to
  dry-run plans without spawning. **A1b decision needed:** rewrite
  for V2's pipeline, retire the harness, or replace with a V2
  measurement command. After A4 this is the only thing actively
  exercising V1 plans in production.

*ZoneRegistry consumers (the four call sites listed in
UNIFIED_REWORK_PLAN's A1 plan, plus a fifth):*
- `Village/BuildSiteFinder.java:104, 312` — TWO consumer calls
  (`ZoneRegistry.zoneOf(buildingType)`). Used by post-spawn
  expansion code, see below.
- `Village/Planning/Rules/RuleContext.java` — V1 shape-rule context.
- `Village/Planning/Zoning/BuildingProfileRegistry.java` — V1 placement
  matcher tier defaults.
- `Village/Decoration/Plaza/PlazaGenerator.java:452` — fifth caller
  not in the original four. Decoration-side query of a building
  type's zone for civic-ring placement. **Either decoration migrates
  off ZoneRegistry or the migration absorbs PlazaGenerator.**
- `Village/Planning/Zoning/PlacementMatcher.java`,
  `Village/Planning/Primitives/PlanContext.java` — both V1-internal,
  die with V1.

*Expansion / growth code (post-spawn V1 dependency):*
- `Entities/Goals/Profession/Builder/BuilderGoal.java:540` — calls
  `BuildSiteFinder.findSite(...)` for the BUILDER profession to find
  new building sites in existing villages. **Critical for A1b:**
  `BuildSiteFinder` uses `ZoneRegistry`, so when V1 dies the
  BUILDER profession's expansion path stops compiling unless A1b
  migrates `BuildSiteFinder` first or retires the BUILDER goal.
- `Village/Village.java` — only references `BuildSiteFinder` in
  comments (Phase 20 / Phase 20a documentation). No code path.

*VillagePlanHelper (false alarm):*
- `Village/Planning/VillagePlanHelper.java` — javadoc mentions
  `VillagePlanner.plan` but the code only creates planned-village
  records (`Village` + `setPlannedOrigin` + `setRealised(false)`).
  Does NOT actually call V1 planning. Safe to keep.

**Categorized for A1b:**

Must rewrite or retire BEFORE deleting V1 (or the codebase won't
compile after V1 deletion):
1. `MeasureCommand` — direct `VillagePlanner.plan` caller.
2. `BuildSiteFinder` — uses `ZoneRegistry`; consumed by `BuilderGoal`.
3. `Planning/Rules/RuleContext` — uses `ZoneRegistry`.
4. `Planning/Zoning/BuildingProfileRegistry` — uses `ZoneRegistry`.
5. `Decoration/Plaza/PlazaGenerator` — uses `ZoneRegistry`.

Dies with V1 in A1b (no external consumer):
- `Village/Planning/Primitives/Recipes/*` (17 recipe classes).
- `Village/Planning/Primitives/{LayoutPrimitive, BaseRecipe,
  ShapeRecipe, PlanContext, ...}` and the slot/intention/anchor
  machinery.
- `Village/Planning/Adaptive/*` (Phase A-D adaptive package).
- `Village/Planning/Sectors/*` (V1 sector machinery — verify before
  delete; some sector references survive in V2 layouts).
- `Village/Planning/Zoning/PlacementMatcher.java`.
- `Village/Planning/VillagePlanner.java`.
- `Village/Planning/ZoneRegistry.java` (after the five callers
  above migrate).

**Step 5 — smoke test:** pending user-run. The expansion path
(BUILDER profession) is currently still live and uses V1's
`BuildSiteFinder` — **expansion will continue working after A4**
because `BuildSiteFinder` and `ZoneRegistry` were not deleted by A4.
A1b is the breaking change for that path.

**Open questions for A1b sequencing:**
- Should `BuildSiteFinder` be ported onto V2 vocabulary (recommend),
  or should `BuilderGoal`'s site-finding be rewritten?
- Is `PlazaGenerator` zoning use a real dependency or a stale
  reference? Decoration is meant to be V1-decoupled.
- Does `/litv measure` matter for A1b, or is the V2 measurement
  command in `LayoutCommand` / `PlaceCommand` enough?

Next: smoke-test feedback for A1a + A2 + A3 + A4 together. Then A1b
when ready.