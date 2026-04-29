# Village Placement Rework — Phases

Phase-by-phase implementation guide. Each phase lists files affected, the goal, validation criteria, and a prompt estimate.

Reference: `01-PLACEMENT-ABSTRACTIONS.md` for all data types and contracts.

---

## Phase 1 — RoadGraph foundation

**Goal.** Replace `VillageLayout`'s parallel road state with a real `RoadGraph`. No behavior change; structural refactor.

**Files affected.**
- New: `Village/Planning/Graph/RoadGraph.java`, `RoadNode.java`, `RoadEdge.java` (or as nested types).
- Modified: `Village/Planning/VillageLayout.java` (replace `roadPrimitives` + `centerlines` map with `RoadGraph` field; rework `addRoad` → `addEdge` returning edge ID; preserve `getCenterline`/`getAllCenterlines`/`reserveRoads` as graph queries).
- Modified: every recipe in `Village/Planning/Primitives/Recipes/` (call-site update to capture node IDs and pass `EdgeRole`).
- Modified: `Village/Decoration/Roads/VillageRoadNetwork.buildInitialNetwork` (iterate via `graph.allEdges`).

**Validation.**
- All existing recipes still produce the same villages on the same seeds (snapshot test).
- `OrganicRoadPlacer` still receives the centerlines it expects.
- `tryCommitWithRetries` still works — it consults `feedingRoad` not `feedingEdgeId` yet.

**Prompts: 3-4.**
1. Data types + RoadGraph implementation.
2. VillageLayout migration with backwards-compatible accessors.
3. Recipe call-site sweep (most recipes only need `addRoad` → `addEdge` rename plus EdgeRole arg).
4. (If needed) VillageRoadNetwork migration + snapshot validation fix.

---

## Phase 2 — Layout debug visualizer + `show_graph`

**Goal.** Mirror `RoadDebugVisualizer` for village-internal graph. Validates Phase 1 visually.

**Files affected.**
- New: `Village/Planning/Debug/LayoutDebugVisualizer.java` (mirror `RoadDebugVisualizer` — same `TickSubsystem` shape).
- New: `Commands/LayoutDebugCommand.java` (mirror `RoadGraphDebugCommand` structure — register under `/liv layout debug`).
- Modified: `TickSubsystemRegistry` registration.

**Particle convention** (additive to existing road-debug palette):
- Spine edges: `END_ROD`. Spur edges: `FLAME`. Ring edges: `COMPOSTER`.
- JUNCTION: `END_ROD` beam. GATE: `SOUL_FIRE_FLAME`. FOCAL: `HAPPY_VILLAGER`. TERMINUS: `SMOKE`.

**Validation.** Run `/liv layout debug show_graph` near a freshly-spawned village; confirm every road and node renders with correct kind/role colors.

**Prompts: 1-2.**

---

## Phase 3 — FeatureMap (planning-pass only)

**Goal.** Hull, plaza polygons, water features, cliff features, reservations as first-class data. Single planning-pass implementation; determinism comes in Phase 4.

**Files affected.**
- New: `Village/Planning/Features/FeatureMap.java`, `PolygonXZ.java`, `WaterFeature.java`, `CliffFeature.java`, `ReservedRegion.java`.
- Modified: `PlanContext` adds `public FeatureMap features`.
- Modified: `VillagePlanner.plan` builds the FeatureMap right after terrain analysis, before recipe dispatch.
- Modified: `RiverineRecipe`, `HilltopRecipe`, `TerracedRecipe`, `ChainRecipe`, `DocksideRecipe` consume `pctx.features` for their water/cliff queries (replacing direct `terrain.waterBody()` etc.).

**Hull derivation.** Concave hull of all sector slot positions plus the village centre, expanded by a margin equal to the largest building footprint in `pctx.remaining`. In Phase 3 this is a stub (uses `terrain.flatCandidates`); in Phase 6+ it consumes sector slot positions properly.

**Validation.** Recipes that previously read `terrain.waterBody()`/`terrain.ridges()` directly now read from `pctx.features` and produce identical layouts.

**Prompts: 2-3.**

---

## Phase 4 — FeatureMap determinism + two-pass refine

**Goal.** Lock down deterministic feature polygons across reloads. This is the single highest-risk slice in the rework.

**Files affected.**
- New: `Village/Planning/Features/FeatureMap.buildPlanning(BlockPos, long, AtlasSampler)` — uses `DeepTerrainInspector` and atlas data only, no chunk loads.
- New: `FeatureMap.refine(ServerLevel)` — Y-snap pass, no polygon reshape.
- Modified: `VillagePlanner` calls `buildPlanning` at planning time. `VillageSpawner` calls `refine` after chunk load.
- New: `Village/Planning/DeterminismTest.java` — harness that spawns the same village 3× across simulated reloads and diffs `LayoutPlan.committedSlots`.

**Validation.**
- Three different seeds, three reloads each, identical committed slot lists.
- A polygon that drifts >2 blocks between planning and refine logs a warning but does not change shape.

**Prompts: 2-3.** Includes building the test harness, which is reused in Phase 23.

---

## Phase 5 — `show_features` / `show_hull` debug

**Goal.** Visualize FeatureMap polygons for sanity-check during Phases 6+.

**Files affected.** Extend `LayoutDebugCommand` from Phase 2.

**Particle convention.**
- Hull: `END_ROD` traced along polygon edges with center beam.
- Water polygons: `FALLING_WATER`. Cliff polygons: `LAVA`. Plaza polygons: `HAPPY_VILLAGER`.
- Reservations: `COMPOSTER` along edges with center beam.

**Prompts: 1.**

---

## Phase 6 — Sector + GrowthPolicy strategy interface

**Goal.** Land the data types. No recipe consumes them yet.

**Files affected.**
- New: `Village/Planning/Sectors/Sector.java`, `SectorRole.java`.
- New: `Village/Planning/Sectors/GrowthPolicy.java` (sealed interface), `FixedGrowth.java`, `ExtendAlongEdge.java`, `AddRing.java`, `AddSpur.java`.
- Modified: `PlacementSlot` — add `feedingEdgeId`, `footprintBudgetW`, `footprintBudgetL`, `forcedRotation`, `terrainPenalty`. Existing constructors stay (delegate to new with default values) so Phase 1-5 code keeps compiling.

**Validation.** Compile-only for the strategies; no recipe uses them yet.

**Prompts: 2.**

---

## Phase 7 — BaseRecipe abstract

**Goal.** Three-step lifecycle. Recipes opt in.

**Files affected.**
- New: `Village/Planning/Primitives/BaseRecipe.java`.
- Modified: `ShapeRecipe.forShape` unchanged; `BaseRecipe implements ShapeRecipe` so the dispatcher works either way.

**Prompts: 1.**

---

## Phase 8 — Convert RADIAL to BaseRecipe + sectors

**Goal.** Proof of concept. RADIAL is the most-tested recipe; converting it first surfaces the abstraction's real cost.

**Files affected.**
- Modified: `RadialRecipe.java` — extends `BaseRecipe`, splits into `prepareFeatures`/`composeSectors`/`registerAnchors`.
- Modified: `PlacementMatcher.run` — accepts `List<Sector>` via a new entry point, but keeps the flat `List<PlacementSlot>` path for Phases 9-15 backward compat. Internal: matcher fills sectors greedily, growth invocation is stubbed (always returns empty list — Phase 10 wires it).

**Validation.**
- RADIAL produces villages comparable to the pre-conversion version (same seed, same building set, ≥95% slot overlap).
- `/liv layout debug show_sectors` lights up RADIAL's civic ring, spur clusters, residential infill, agri fringe.

**Prompts: 3-4.** Mostly because matcher dual-mode (sector + flat) is a real chunk of code.

---

## Phase 9 — `show_sectors` debug

**Goal.** Visualize sector boundaries, capacities, fill state.

**Files affected.** Extend `LayoutDebugCommand`.

**Particle convention.**
- Sector AABB: `COMPOSTER` (unfilled), `FLAME` (at capacity), `ANGRY_VILLAGER` (overflow attempted), `ENCHANT` (grew).
- Slot positions: `END_ROD` (committed) / `SMOKE` (uncommitted) / small `ANGRY_VILLAGER` cluster (burned).

**Prompts: 1.**

---

## Phase 10 — Growth integration into matcher

**Goal.** Real overflow handling. Delete `fallbackPlaceRemaining` and `rescueOrphans`.

**Files affected.**
- Modified: `PlacementMatcher.run` — implements the originator → role-peer → zone-peer → drop ordering described in `ABSTRACTIONS.md#growth-round-semantics`.
- Modified: `VillagePlanner.plan` — removes the `fallbackPlaceRemaining` and `rescueOrphans` calls.
- Deleted: `VillagePlanner.fallbackPlaceRemaining`, `rescueOrphans`.

**Validation.**
- RADIAL with intentionally over-budget building counts grows correctly. `/liv layout debug show_sectors` shows the grown sectors in `ENCHANT`.
- 100-village randomized stress test produces no buildings further than 32 blocks from any sector position.

**Prompts: 2-3.**

---

## Phase 11 — Convert PLAZA / LINEAR / CLUSTERED / ROADSIDE

**Goal.** Most-similar-to-RADIAL recipes. Establishes the conversion pattern.

**Per recipe.** Move geometry into `composeSectors`; emit sectors instead of `tryCommitWithRetries` loops; pick `GrowthPolicy` (typically: PLAZA → AddRing, LINEAR → ExtendAlongEdge, ROADSIDE → ExtendAlongEdge, CLUSTERED → AddSpur).

CLUSTERED may need to override `compose()` directly because its "no central organization" structure doesn't fit the 3-step model cleanly.

**Validation.** Each converted recipe produces villages comparable to its pre-conversion version.

**Prompts: 4** (one per recipe; CLUSTERED slightly larger).

---

## Phase 12 — Convert RIVERINE + Bridge primitive

**Goal.** First feature-aware conversion. Validates the road-stops-at-shore pipeline.

**Files affected.**
- New: `RoadPrimitive.Bridge` record — straight-line over water with deck rendering hint, registered in the dispatch codec.
- Modified: `OrganicRoadPlacer` / `UnifiedRoadPlacer` — `Bridge` primitives skip surface paint, hand off to existing bridge rendering in `RoadRouter.placeBridge`.
- Modified: `RiverineRecipe` — uses `pctx.features.nearestShoreEdge` to lay the spine; spur primitives that hit water terminate with a `SHORE_HEAD` node; Bridge primitives connect across deliberately.

**Validation.**
- A riverine village correctly places its main road parallel to shore.
- A spur that would walk into water terminates at a `SHORE_HEAD` node, no road blocks placed in water.
- A bridge primitive in the recipe deliberately spans water with deck.

**Prompts: 2-3.**

---

## Phase 13 — Convert HILLTOP + Stairway primitive

**Goal.** First cliff-aware conversion.

**Files affected.**
- New: `RoadPrimitive.Stairway` record — multi-segment with vertical step annotation.
- Modified: `OrganicRoadPlacer` — `Stairway` primitives place stair-step blocks per annotation.
- Modified: `HilltopRecipe` — uses `pctx.features.cliffFeatures` for the peak and ridge edges; switchback main road becomes `Stairway`.

**Validation.** HILLTOP villages place sensibly on real terrain instead of falling back to PLAZA the moment terrain variance is non-trivial.

**Prompts: 2.**

---

## Phase 14 — Convert TERRACED + Causeway primitive

**Goal.** Slope-aware conversion.

**Files affected.**
- New: `RoadPrimitive.Causeway` record (optional — only if marsh/shallow-water terraces actually need it; may be deferred).
- Modified: `TerracedRecipe` — sectors per terrace, Stairway ramps between, FeatureMap clamps terraces inside hull.

**Validation.** TERRACED produces visible stepped rows on slopes.

**Prompts: 2.**

---

## Phase 15 — Convert remaining 9 recipes

**Goal.** Finish CROSSROADS, CHAIN, GROVE, SPRAWL, DOCKSIDE, DUAL_PLAZA, OUTPOST, ENCLAVE, DUMBELL.

These mostly follow Phase 11's pattern; DOCKSIDE benefits from Phase 12's water work; ENCLAVE needs careful handling of its wall-formation case (defer wall-block placement to the wall rework, just emit the slot tags).

**Prompts: 3-4** (batches of 3 recipes per prompt, with shared patterns).

---

## Phase 16 — RoadPrimitive signature change + water/cliff truncation

**Goal.** Threading `PlanContext` through `computeCenterline`. Today `Bridge`/`Stairway` recipes pass features as constructor args; this generalizes.

**Files affected.**
- Modified: `RoadPrimitive.computeCenterline(ServerLevel, long)` → `computeCenterline(PlanContext)`.
- Modified: every primitive's implementation.
- Modified: every callsite (most pass `pctx` already).
- Modified: `StraightRoad` and `Spur` — when their centerline crosses water/cliff, they truncate at the boundary and the caller (recipe) is responsible for handling the truncation (insert a `SHORE_HEAD`/`BRIDGE_HEAD` node, optionally chain a `Bridge`/`Stairway` primitive).

**Validation.** No primitive blindly walks into water or off a cliff. Truncation is clean.

**Prompts: 2.**

---

## Phase 17 — Farm plot sector integration

**Goal.** Farm plots become sectors (`AGRICULTURAL_FRINGE` role) emitted during compose, replacing post-hoc `FarmPlotPlacer.placeAll`.

**Files affected.**
- Modified: every recipe's `composeSectors` adds an `AGRICULTURAL_FRINGE` sector with `GARDEN_RESERVATION` per planned plot.
- Modified: `FarmPlotPlacer.placeAll` becomes a realisation-time consumer reading `LayoutPlan.features.reservations` filtered by `GARDEN`.

**Validation.** Farm plots land in the same places they used to.

**Prompts: 2.**

---

## Phase 18 — Plaza polygon ownership consolidation

**Goal.** Recipe emits the plaza polygon; `TownSquarePlacer` consumes from `FeatureMap`.

**Files affected.**
- Modified: `BaseRecipe.preparePlaza()` (new helper) emits one or more `PolygonXZ` into `pctx.features.plazaPolygons`.
- Modified: `TownSquarePlacer.place` — if `village.layoutPlan.features.plazaPolygons` non-empty, use first polygon; else fall back to current circular default.
- Modified: `PlazaGenerator` (if separate from TownSquarePlacer) reads from FeatureMap.

**Validation.** Plaza geometry is identical pre/post.

**Prompts: 2.**

---

## Phase 19 — LayoutPlan + AnchorKind + spawner/decorator wiring

**Goal.** Immutable result contract. Everything downstream consumes `LayoutPlan` not `VillageLayout`.

**Files affected.**
- New: `Village/Planning/LayoutPlan.java`, `AnchorKind.java`.
- New: codecs for `RoadGraph`, `Sector`, `LayoutPlan` (persistence is a kingdom-rework requirement).
- Modified: `VillagePlanner.plan` returns `Optional<LayoutPlan>`.
- Modified: `VillageSpawner.spawnVillage` consumes `LayoutPlan`.
- Modified: `VillageDecorator.decorateVillage` consumes `LayoutPlan`.
- Modified: `Village.applyLayout` accepts `LayoutPlan` (or stores subset of fields).

**Validation.** End-to-end spawn works. Anchors are queryable via `plan.anchors().get(AnchorKind.MAIN_GATE)`.

**Prompts: 2-3.**

---

## Phase 20 — BuildSiteFinder migration

**Goal.** Expansion uses graph + features instead of ring/spiral. Closes the loop on the user's "expandable village" requirement.

**Files affected.**
- Modified: `BuildSiteFinder.findSite` — new implementation that asks `village.layoutPlan` for a clear footprint along an existing edge in the requested zone.
- Deleted: `findSiteOnRing`, `findSiteSpiral`, ring-radius math.
- Modified: callers in `BuilderGoal` (no signature change needed).

**Validation.** Expansion buildings land on sensible road-adjacent positions, never in farmland or plazas.

**Prompts: 2-3.**

---

## Phase 21 — VillageTypeData kingdom-rework schema additions

**Goal.** Schema reservation for the kingdom rework. No behavior.

**Files affected.**
- Modified: `VillageTypeData` adds: `boolean capitalEmitsClaim`, `int claimBudgetHint`, `List<String> vassalTypes`, `List<String> hostileTypes`, `int minNobilityTier`, `boolean provinceSeatEligible`, `float claimResistance`. All default to safe values.
- Modified: `VillageTypeBuilder` exposes setters.
- Modified: existing village type JSONs unchanged (defaults handle it).

**Prompts: 1.**

---

## Phase 22 — Recipe fallback chains

**Goal.** Every recipe declares a fallback chain. Planner walks it on hard failure.

**Files affected.**
- Modified: `VillageShapeProfile` — adds `List<ShapeType> fallbacks`.
- Modified: `VillagePlanner.plan` — on recipe hard failure, walks the chain in order before giving up.
- Modified: every existing recipe-internal "fall back to X" call (HILLTOP→PLAZA, RIVERINE→RADIAL, TERRACED→LINEAR) — these become declarations, not in-recipe code.

**Prompts: 1-2.**

---

## Phase 23 — 90% success measurement + persistent-failure polish

**Goal.** Hit the exit criterion. Use the determinism harness from Phase 4 expanded to a 100-village random-seed test.

**Files affected.** Test harness only, plus targeted polish to whichever recipe(s) fail most often.

**Validation.**
- Randomized 100-village test across representative biome mix achieves ≥90% successful placement.
- No silent drops; every failure has a structured `PlacementFailureRecorder` entry.

**Prompts: 3-5.** Highly variable; this is where edge cases surface.

---

## Total

**Sum: 45-55 prompts.** Realistic midpoint: 50.

Per-phase prompt counts include iteration on debugging — a phase that lists "2 prompts" usually means one substantive prompt plus one to fix what broke. If a phase consistently overshoots, it's a sign the abstraction isn't carrying its weight; pause and revise `ABSTRACTIONS.md` rather than push through.
