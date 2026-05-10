# V2 layout — current-state overview

Track E1 setup. Read-only introspection scaffolding for V2 village
generation. Authoritative source: code, not skills.

---

## Skill drift surfaced during this read

The `litv-layout-recipe`, `litv-layout-primitive`, and (to a lesser
degree) `litv-road-primitive` skills assume V1 vocabulary
(`ShapeRecipe`, `LayoutPrimitive`, `forShape` dispatch). Reality:

- **`ShapeRecipe` is V1-only.** No V2 caller dispatches through
  `forShape`. `V2VillageSpawnerAdapter` accepts the
  `villageType` argument purely for record-keeping; the comment
  block at lines 63–80 says explicitly *"V2 derives layout,
  building set, and viability from terrain."* The V1 recipes
  (TownSquare, Grid, Ring, etc.) are parked code with no live
  callers from V2.
- **`LayoutPrimitive` is V1-only.** V2 doesn't emit any
  `LayoutPrimitive` instances. Layer 4 emits concrete
  `PlacedBuilding` records directly.
- **`RoadPrimitive` is shared.** V2 still composes spines from
  `RoadPrimitive` instances (held on `SpinePath.segments`); the
  `Skeleton.allSegments()` view returns concrete `RoadSegment`s
  (sealed: `SpineSegment` + `CrossStreet`) used for
  frontage / corridor checks.
- **No `culture → recipe` registry.** V2 has no per-culture
  recipe selection. Culture influences naming / road material /
  trait bias but not layout shape.

**Skills should be updated post-artifact.** Treat the skill text
as historical reference until then.

---

## 1. Pipeline (5 layers)

Entry point: `Village/Planning/V2/V2VillageSpawnerAdapter.spawn(level, origin, ...)`.

| Layer | Class | Consumes | Produces | Side-effects |
|-------|-------|----------|----------|--------------|
| 1 — Feature scan | `V2FeatureMap.scan` | level + origin + radius | `V2FeatureMap` (terrain grid, water/cliff/forest metadata) | read-only |
| 2 — Site analysis | `SiteAnalyzer.analyze` | feature map + culture + seed | `SiteContext` (anchor, originalAnchor, primary axis, spine path, viability tier, inclination, hubs) | read-only |
| 3 — Building selection + placement | `BuildingSelector.select` → `ReconciliationEngine.reconcile` → `DependencyResolver.topoSort` → `PhasedPlanner.run` (Phase-3 part) | site context + feature map + inclination profile | `SelectionResult`, `ReconciliationResult`, `PlacementResult` (`placed` / `dropped` / `unavailable` + `placedCounts` + `villageViable`) | read-only |
| 4 — Road planning | `PhasedPlanner.run` (Phase-4 part) | site context + sorted types + unavailable list | `RoadNetwork(skeleton, frontageOwners)` + `List<PhaseEvent>` | read-only |
| 5 — Adapt + finalise | `OverlapAuditor.audit` → `TerrainAdapter.decide` → `ViabilityValidator.validate` → `VegetationClearer` → `PadBuilder` → NBT placement | placed buildings + skeleton + level | survivor list + placed structures | **mutates the world** (vegetation removed, pads placed, structures spawned) |

The dump command runs Layers 1–4 only and stops before Layer 5's
mutators. `TerrainAdapter.decide` and `ViabilityValidator.validate`
are read-only and *could* be included; v1 dump skips them so the
output is the planner's first-pass plan, not the post-terrain
filtered survivors. (The artifact can choose to render dropped
buildings to surface why a slot didn't survive.)

A "synth `VillageLayout`" is built (Phase 19) at end of layer 5 so
V1 downstream consumers (lore, kingdom claim, debug visualisers)
have something to read. **It carries strictly less information
than the V2 records.** Don't dump from it.

---

## 2. RoadPrimitive types in use

Defined in `Village/Planning/Primitives/RoadPrimitive.java`. Sealed
interface; nine variants:

- **`StraightRoad`** — nearly-straight with perpendicular drift; primary exits and direct plaza links.
- **`CurvedRoad`** — quadratic Bezier from A to B; secondary connectors.
- **`Ring`** — circular at centre + radius; plaza perimeter / fortress encirclement.
- **`Arc`** — partial circle (start angle + arc span + radius); terrain-adaptive turns.
- **`Spur`** — short perpendicular branch from an existing centerline; dead-end feeder.
- **`SmoothedPath`** — smoothed polyline (via `RoutePathSmoother`); trade routes with waypoints.
- **`ArmApproach`** — adaptive curve from gateway to fixed endpoint; multi-gateway spiral approach.
- **`Bridge`** — water-capable straight/curved; carries `isWaterCapable() = true`.
- **`Stairway`** — steep vertical-step road; cliff/terrace transitions with Y jumps.

Common API: `tier()` (`RoadShape.RoadTier`), `intendedLength()`,
`computeCenterline(PrimitiveContext)`, `typeKey()`,
`isWaterCapable()` (default false). Each has a codec for
persistence.

`PrimitiveContext.basic(level, seed)` is the recommended constructor
for centerline computation in read-only contexts.

---

## 3. Slot model

`Village/Planning/LayoutSlot.java` — V1 building-placement record. V2
doesn't synthesize one until the V2-to-V1 adapter wraps `PlacedBuilding`
into `LayoutSlot` (`V2VillageSpawnerAdapter.synthSlot`). For dump
purposes V2's `PlacedBuilding` is the canonical record:

| Field | Type | Notes |
|-------|------|-------|
| `type` | `BuildingType` | enum |
| `centre` | `BlockPos` | world-space block coords; Y is the building's centre Y at placement time |
| `footprint` | `Footprint(width, length)` | unrotated dimensions |
| `rotation` | `Rotation` | NONE / CLOCKWISE_90 / etc. |
| `priority` | `Priority` | enum (FOUNDATION / ITERATIVE / etc.) |
| `variantId` | `String` | nullable; defaults to type lowercased |
| `frontage` | `FrontageStrip` | building front pos + outward direction |
| `facingRoad` | `RoadSegment` | the segment the building fronts (`SpineSegment` or `CrossStreet`) |
| `adjunct` | `PlannedAdjunct` | nullable; reserves an adjunct plot (B2.1) |

The dump emits centre + width × length + rotation; rotated dimensions
are derivable client-side (swap when CLOCKWISE_90 / COUNTERCLOCKWISE_90).

V2 does **not** populate the V1 25-tag set (`SlotTag.PRIME_CIVIC` etc.).
Tags exist in `Village/Planning/Zoning/SlotTag.java` and are read by
the V1 placement matcher; V2 uses building-type + frontage adjacency
instead. The artifact should not expect tags in V2 dumps.

---

## 4. Gateway model

Post-C2 multi-gateway. V2 derives gate positions in
`V2VillageSpawnerAdapter.buildSynthLayout` from:

- `Skeleton.spineEnd()` → PRIMARY (mainGateEndpoint)
- `Skeleton.spineStart()` → SIDE (if not equal to spineEnd)
- Each `CrossStreet`'s `start` and `end` → SIDE

`GatewayPopulator` (Village/Roads/Planning) later turns these into
`VillageRoadNode.gateway()` entries with arm endpoints (32 blocks
outward, pulled back if water/cliff). The dump emits the raw
positions + role + source tag; arm-endpoint computation is a
realisation-time concern not captured here.

---

## 5. Density profile

`Village/Planning/LayoutDensityProfile.java`. Constructed via
`forLevel(int)` (1–10). Drives ring radii, spacing, jitter, farm
offset. V2 uses `BUILDING_LEVEL = 4` constant in the adapter, so
the density profile is fixed — terrain inclination + tier are the
real layout variation knobs. Dump records the level + label so the
artifact can show which profile the planner used; per-field values
are also dumped when present.

---

## 6. Terrain inspection

`Village/Planning/Terrain/TerrainAnalyzer.java` (V1 path) is **not**
the V2 terrain reader. V2 reads terrain via `V2FeatureMap` and
synthesises a `TerrainProfile` only at synth-layout-build time
(line 453 of the adapter). The dump captures the V2 site analysis
outputs (tier, inclination, axis) which are the planner's actual
terrain-driven inputs.

---

## 7. Coordinate frame

**World-space block coordinates throughout.** All positions in the
dump are absolute world XYZ. Y is the snapped centre (or for
centerline points, the surface Y at sample time). The artifact
projects to top-down by ignoring Y.

---

## 8. JSON dump schema (v1)

Top-level fields:

| Field | Type | Notes |
|-------|------|-------|
| `schemaVersion` | int | always `1` for this version |
| `command` | string | `"dump"` (named village) or `"dump_at"` (player position) |
| `tick` | long | server tick at dump time |
| `worldSeed` | long | level seed |
| `planSeed` | long | mixed seed used for V2 layers |
| `dimension` | string | dimension key (e.g. `"minecraft:overworld"`) |
| `origin` | `{x,y,z}` | dump origin (village anchor or player pos) |
| `village` | object | `{name?, id?, culture}`; only `culture` for `dump_at` |
| `siteContext` | object | see below |
| `aborted` | bool? | present + true when tier=UNVIABLE; subsequent fields absent |
| `abortReason` | string? | one-line abort note |
| `buildings` | object | placed / dropped / unavailable / counts / reconciliation |
| `roads` | object | skeleton + frontageOwners summary |
| `phaseEvents` | array | PhasedPlanner diagnostics |
| `gateways` | array | gate positions + role + source |

**`siteContext`:**
- `anchor`, `originalAnchor`: `{x,y,z}` — anchor before/after adjustment
- `primaryAxis`: enum string (e.g. `"NORTH_SOUTH"`)
- `tier`: viability tier (`"VIABLE"` / `"UNVIABLE"` / etc.)
- `inclination`: enum string
- `cultureId`, `seed`: scalar
- `spinePath`: `{primaryAxis, totalLength, start, end, segments[]}`
- `hubs`: array of `{position, dirX, dirY, dirZ, openness}`

**`spinePath.segments[]`** (and **`roads.skeleton.spinePathPrimitives[]`**):
each is a `RoadPrimitive` view:
- `type`: typeKey string (`"StraightRoad"` / `"Arc"` / etc.)
- `tier`: `RoadShape.RoadTier` enum string
- `intendedLength`: int
- `waterCapable`: bool
- `centerline`: array of `{x,y,z}` — surface-snapped points
- `centerlineError`: string? — present when `computeCenterline` threw

**`buildings`:**
- `villageViable`: bool
- `placed[]`: array of building entries (see below)
- `dropped[]`: `{type, reason, detail?}` — reason is `DropReason` enum name
- `unavailable[]`: `{type, reason}` — reason is free-form (no NBT, etc.)
- `placedCounts`: `{<TYPE>: int, ...}`
- `reconciliation`: `{selectedCount, dropCount, tradeFulfilledCount}`

**Each placed building:**
- `type`: BuildingType enum name
- `centre`: `{x,y,z}`
- `footprintWidth`, `footprintLength`: int (unrotated)
- `rotation`: `Rotation` enum name
- `priority`: `Priority` enum name
- `variantId`: string?
- `facingRoad`: `{kind, start, end, width}` — kind is `"SPINE_SEGMENT"` or `"CROSS_STREET"`
- `frontage`: `{buildingFront, frontDirX, frontDirZ}`
- `hasAdjunct`: bool? — present + true when an adjunct plot was reserved

**`roads`:**
- `skeleton.spineStart`, `skeleton.spineEnd`: `{x,y,z}`
- `skeleton.segments[]`: every `RoadSegment` (concrete, post-frontage)
  - `kind`: `"SPINE_SEGMENT"` / `"CROSS_STREET"`
  - `start`, `end`, `width`
- `skeleton.spinePathPrimitives[]`: same shape as `spinePath.segments[]`
- `skeleton.crossStreets[]`: `{start, end, width}`
- `skeleton.junctions[]`: `{pos, segmentCount}`
- `frontageOwners`: `{count, sample[]}` — full map omitted for size; first 24 entries sampled

**`phaseEvents[]`:**
- `kind`: `PhaseEvent.Kind` enum name
- `type`: `BuildingType` enum name (nullable)
- `detail`: string (often multi-line for capacity-plan events)
- `score`: stringified `ScoreBreakdown` (nullable)

**`gateways[]`:**
- `position`: `{x,y,z}`
- `role`: `"PRIMARY"` or `"SIDE"`
- `source`: `"spineEnd"` / `"spineStart"` / `"crossStreet.start"` / `"crossStreet.end"`

---

## 9. Important invariants

- **`LayoutSlot.snapY` is Y-only.** `TerrainStep` is the only legal
  caller (see `Village/Planning/Steps/TerrainStep.java`).
- **`PlacedBuilding.centre` is the building centre.** Realisation
  converts to pivot via `centreToPivot` with +1 Y. Don't pass
  centre directly to placement APIs that expect pivot.
- **`Footprint(width, length)` is unrotated.** The artifact /
  visualiser must rotate when `rotation` is `CLOCKWISE_90` or
  `COUNTERCLOCKWISE_90`.
- **`PhasedPlanner.run` is read-only** but takes `level` to sample
  heights. Don't add mutating calls inside.
- **`computeCenterline` may throw** on degenerate inputs (rare;
  primitives with adaptive curves can fail when no valid path
  exists). The dump catches and records `centerlineError` instead
  of failing the whole dump.

---

## 10. Output location

`<worldSave>/litv-debug/layouts/<slug>-<tick>.json`

- For `dump <villageName>`: slug = sanitised village name.
- For `dump_at <radius>`: slug = `dump_at_<x>_<z>`.
- Filename-hostile characters in the village name are replaced
  with `_`.
- The absolute path prints to chat on success.

---

## 11. Sample dumps

Maven build is blocked in the Track E1 setup environment. **Real
samples will be added once the user runs the dump command on a
populated world.** Schema above is the contract; the command
produces JSON matching it.

To produce samples:

```
/litv layout debug dump <existingVillageName>
/litv layout debug dump_at 96
```

Paste the resulting JSON into `docs/v2-dump-samples/<slug>.json`.
