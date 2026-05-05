# Layout Overview

Reference document for recipe authors and downstream consumers of the
village layout pipeline. Describes the contract that the placement
rework (Phases 16b–23) leaves in place. Descriptive — what shipped,
not what could ship.

For the strategic narrative behind these decisions, see
`docs/zoningandlayout_redesign/PLACEMENT-REWORK-STATE.md`. For the
phase-by-phase log, see `ROADS_PROGRESS.md`.

---

## Pipeline

The end-to-end flow from "spawn requested" to "structures placed":

1. **Site selection.** Atlas / kingdom layer chooses a candidate
   centre. Out of scope for this document; the kingdom rework owns
   it. Phase 21 added the `VillageTypeData` schema fields the
   kingdom rework will consume (`settlementTier`, `biomeAffinity`,
   `kingdomRoles`, `tradePriority`, `canBeCapital`, `maxPerKingdom`)
   but does not wire them.

2. **Site preparation.** `VillageSitePreparer.prepare(level, centre,
   villageLevel)` runs synchronously, mutates the world (tree clear,
   hole fill, height smoothing). Skipped by the measurement harness
   (`/litv measure`) because Phase 23's success criterion is
   planner-only.

3. **Recipe composition.** `VillagePlanner.plan(...)` invokes
   `ShapeRecipe.forShape(primaryShape).compose(pctx)` inside a
   cascade-attempt loop. Per recipe:
   - **Probe primary spine** — `RoadResult` from
     `RoadPrimitive.computeCenterline`. Records truncation reasons
     without committing geometry.
   - **`checkPrimarySpine` → `RecipeStatus`** — `OK`, `RETRY`,
     `FALLBACK`, or `ABORT`. Probe-then-commit discipline: no layout
     mutation before the status comes back OK.
   - **Compose roads, plaza, sectors, slots.** Each emission lands
     on `PlanContext` (offered slots, sectors, plaza regions) and
     `VillageLayout` (the mutable scratch).
   - **Cascade on non-OK status.** `BaseRecipe.runWithCascade` walks
     the schema-declared fallback chain (Phase 22) when status is
     `FALLBACK`, advancing `cascadeChainPosition` on `PlanContext`
     and resetting layout state (`VillageLayout.resetForFallback`,
     `PlanContext.resetForFallback`) before re-composing.

4. **Matcher.** `pctx.runMatcher()` commits buildings into slots.
   Slots feed back which buildings claimed which positions; rejected
   slots remain in the offered pool for downstream queries.

5. **Validator.** `VillagePlanner.validatePlan(...)` checks each
   committed building's distance to its feeding road (Chebyshev
   ≤ 13 + slack). Failure with `passed/total < 0.6` triggers a
   second cascade path (Phase 22 validator-driven fallback): chain
   advances, `VillageLayout` and `PlanContext` reset, recipe
   re-composes from the next chain entry.

6. **Farm plot pass.** `RecipeHelpers.emitFarmPlotSlots(...)` adds
   farm plots into appropriate sectors after validation succeeds.

7. **Plan build.** `LayoutPlanBuilder.build(layout, pctx)` produces
   the immutable `LayoutPlan` (Phase 19). The plan is the contract
   handed to spawner and decorator; the mutable `VillageLayout` is
   no longer load-bearing past this point.

8. **Spawner.** Consumes `LayoutPlan` via `village.getPlan()`
   (persisted in Phase 20a). Places structures, marks chunks for
   Forge structure book-keeping.

9. **Decorator.** Reads the same `LayoutPlan`. Paints roads,
   plazas, gathering points, decoration features.

---

## Recipe contract

Recipe authors write `compose(pctx)`. Inputs and guarantees:

### Inputs (read-only on `PlanContext`)

- `pctx.typeData` — the village type record (immutable).
- `pctx.terrain` — `TerrainProfile` from analysis (immutable).
- `pctx.density` — `LayoutDensityProfile` (immutable).
- `pctx.features()` — `FeatureMap` queries: cliffs, water, ridges.
  Built lazily; recipe code calls `pctx.features()` to trigger.
- `pctx.rng` — `Random` derived from origin + seed; deterministic.
- `pctx.styleSelection`, `pctx.sizeTier`, `pctx.ageCategory`,
  `pctx.variantSelector` — variant context.
- `pctx.cascadeChain()`, `pctx.cascadeChainPosition()` — read-only
  cascade-state visibility (Phase 22). Position-0 is primary;
  position > 0 means a fallback fired.

### Outputs (write on `PlanContext` and `VillageLayout`)

- `pctx.offerSlot(slot, sector)` — emit a placement slot into a
  sector. Slots carry `SlotTag`s that the matcher's tier-preference
  table consumes.
- `pctx.commitSector(sector)` — commit a sector to the layout.
- `pctx.registerPlazaRegion(region)` — polygon plaza ownership
  (Phase 18).
- `layout.setMainGateEndpoint(pos)`, `layout.setTownSquarePos(pos)`,
  `layout.setVillageCenterMarker(marker)` — anchor positions.

### Cascade integration

Recipes that probe-then-commit and need cascade behaviour wrap
their composition body in:

```java
runWithCascade(pctx, (status, reason) -> {
    // re-emit decision goes here, returning RecipeStatus
});
```

The cascade engine (Phase 16b/22) handles RETRY, FALLBACK, ABORT
transitions; recipes only declare what status to return per check.

### Fallback chain declaration (Phase 22)

Village type JSON / datagen declares a fallback chain:

```java
.fallbackChain(ShapeType.RADIAL)
```

The chain is `[primary, ...fallbacks]`. When a recipe `FALLBACK`s
or the validator triggers below 60% pass-rate, the next entry is
tried. RADIAL is the universal terminal fallback for fragile
recipes (LINEAR, PLAZA, DUAL_PLAZA today).

---

## Available primitives

### Road primitives (`RoadPrimitive` sealed interface)

- `StraightRoad` — point-to-point with truncation.
- `Spur` — short branch off another edge.
- `Arc` — curved partial circle.
- `Ring` — closed loop (often used as civic ring).
- `CurvedRoad` — Bézier-style curve.

Future (decoupled from any current recipe):

- `Bridge` — water-crossing variant.
- `Stairway` — vertical traversal.
- `Causeway` — optional, if Bridge + Stairway prove insufficient.

### Layout primitives (`LayoutPrimitive` sealed interface)

- `BuildingCircle` — N buildings around a centre.
- `LinearRow` — sequence along a road.
- `RingBand` — concentric placement (inner/outer radii; supports
  `useShared` for civic ring alignment, see microfix list).

### Plaza model (Phase 18)

- `Plaza` — civic centre with capacity, anchor.
- `PlazaRegion` — polygon-based ownership for plaza-aware recipes.
- `PlazaPaver` — emits the plaza floor onto the FeatureMap.

---

## SlotTag vocabulary

The matcher consumes `SlotTag`s during scoring. Tags are flat —
ordering is irrelevant; presence/absence is what matters. Authoritative
list lives in `Village/Planning/Zoning/SlotTag.java`. Common tags:

- `CIVIC` — town hall, market, guild hall, temple. Centre-bias.
- `RESIDENTIAL` — houses, inns, civic-residential mixed.
- `PRODUCTION` — workshops (blacksmith, carpentry, stonemason,
  etc.). Often ring-band.
- `AGRICULTURAL` — farmhouses, mills, bakeries.
- `DEFENSIVE` — guard towers, watchtowers, barracks.
- `WATERFRONT` — fisheries, piers, docks. Restricted by
  TerrainStrategy.
- `RING_INNER` / `RING_OUTER` — explicit ring tier hints.
- `FIELD_EDGE` — perimeter slot for farms.

The matcher's per-building tier preferences live in
`BuildingProfileRegistry`. Recipes shouldn't try to second-guess the
matcher: emit slots with appropriate tags, let the matcher score.

---

## Validator rules

Phase 16b set the structural validator. It's deliberately small and
unchanged through Phases 17–22:

- **Distance to feeding road.** Chebyshev distance from building
  centre to the closest point on its parent road edge ≤ `13 +
  footprintSlack`. Footprint slack is a function of building size.
- **Hull containment.** Building footprint must fit inside the
  village hull plus `VALIDATOR_HULL_SLACK` (= 8 blocks).
- **Failure threshold.** When `passed / total < 0.6`, planner
  re-composes via cascade chain (Phase 22). Above 0.6 but with
  individual rejected buildings, the failures are recorded but the
  plan still ships — orphans are dropped, not rescued (Phase 10
  retired the rescue passes).

---

## Cascade engine

### `RecipeStatus` (post-Phase 16b)

| Status     | Meaning                                                     |
|------------|-------------------------------------------------------------|
| `OK`       | Probe succeeded; commit and continue.                       |
| `RETRY`    | Re-probe with mutated parameters (axis rotation, etc.).     |
| `FALLBACK` | Walk the schema fallback chain to the next shape.           |
| `ABORT`    | Site is unplannable; mark and bail.                         |

### `ReEmitReason` (sealed interface)

`SevereTruncation`, `SlotsDropped`, `SectorStarved`, `ValidationFailed`
(Phase 22). Each carries enough detail for the recipe's `reEmit`
hook to decide on a status transition.

### Per-village summary log

`VillagePlanner` emits two log lines per spawn:

```
[VILLAGE-SUMMARY] type=X shape=Y validated=Vp/Vt truncations=N retries=M
[LAYOUT-PLAN] shape=X status=OK ...  cascade: started=PRIMARY chain=[A,B] position=N fallback-fired
```

The `cascade:` line appears only when the chain is non-trivial
(declared fallback chain present).

---

## Measurement harness

`/litv measure <count> [seed] [commit]` (Phase 23.1) spawns N
candidate sites in dry-run mode (planner-only, no world commits)
and writes per-spawn outcomes to
`<gameDir>/logs/litv-measure-<timestamp>.jsonl` plus a console
summary report. Used to validate Phase 23's exit criterion (≥ 90%
spawn success on default Minecraft, ≥ 75% on rougher terrain mods).

The harness runs a structural-integrity reflection check before
spawning. If a future refactor accidentally undoes a rework piece
(LayoutPlan persistence, cascade chain state, kingdom schema field,
runWithCascade method), the harness aborts loudly with a clear
"rework integrity failed" message.
