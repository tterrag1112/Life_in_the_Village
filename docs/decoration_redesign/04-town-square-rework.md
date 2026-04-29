# 04 — Town Square Rework (polygon-based plaza)

## Purpose

Replace the legacy `TownSquarePlacer` / prompt-14 `TownSquareComposer`
"stamp a paved square at the center" model with a **plaza-as-region**
model: the plaza is a polygonal piece of the road network, paved with
the same `PathMaterial` palette as surrounding roads, with decoration
content placed in voids inside the polygon. The plaza reads as a
widened part of the road network rather than as a separately authored
structure.

The visible failure mode this fixes — "the plaza looks stamped on top
of unaware roads" — has two underlying causes diagnosed by the
prompt-15 audit:

1. **Palette mismatch.** The legacy paving used
   `VillageBiomeStyle.stone` / `stoneSlab` / `pathState`; roads use
   `PathMaterial`. Different blocks meeting at the plaza-road boundary
   creates a visible seam.
2. **Shape mismatch.** A square plaza in a layout that organically
   wants a different shape (RADIAL → circle, RIVERINE → linear, etc.)
   reads as a separate authored object.

The redesign addresses (1) immediately and (2) over a multi-prompt
rollout.

## Multi-prompt rollout plan

The audit confirmed that fully replacing the architecture in one
prompt would require either substantial refactoring of
`OrganicRoadPlacer` or a half-baked integration. The redesign is
therefore split:

| Prompt | Scope | Visible result |
|---|---|---|
| **16 (this)** | Surgical palette switch + polygon data model + `PLAZA_ADJACENT` slot tag + doc | Plaza pavement matches surrounding road palette; no visible seam. Plaza is still a stamped axis-aligned square. Data model exists but is unpopulated. |
| **17** | Polygon plaza generator (CIRCLE/SQUARE/LINEAR/IRREGULAR), `PlazaPaver` running alongside the road network with the same `PathMaterial` source, recipe integration, sub-slot emission inside polygon | Plaza shape varies per layout; pavement still continuous because it shares the `PathMaterial` source. |
| **18** | Civic placement migration: `PLAZA_ADJACENT` slot tag emission from polygon edges, building rotation override, removal of the ring-road-as-civic-placement mechanism | Civic buildings cluster around the polygon naturally; legacy ring road retired. |

After this prompt, existing villages look noticeably better (palette
continuity), and the data structures prompts 17/18 build on are in
place.

## Core concepts

```java
public record PlazaRegion(
    UUID plazaId,
    PlazaPurpose purpose,
    PlazaShape shape,
    Polygon footprint,            // 2D outline at floor Y
    BlockPos centroid,
    int floorY,
    Set<UUID> connectedRoadIds,   // road segments treated as
                                   // plaza-internal by the paver
    float orientationRadians       // 0 for cardinal-aligned shapes
)

public enum PlazaPurpose { CIVIC, MARKET, RELIGIOUS_COURTYARD }
public enum PlazaShape   { CIRCLE, SQUARE, LINEAR, IRREGULAR }

public record VillageCenterMarker(
    BlockPos pos,
    int floorY,
    String culture
)  // HAMLET tier alternative — no plaza polygon, just a center
   // marker for a future "well + signpost" decoration
```

`PlazaContent` is intentionally not a separate concept — what fills
the polygon interior is just standard `DecorationProfile`s emitted by
the slot emitter into `PARK_FEATURE` + `PLAZA_*` sub-tag slots inside
the polygon. The plaza-content authoring lands as ordinary
`DecorationProfile` registrations; no per-tier kit registry.

## Layout-to-shape mapping (prompt 17 implements)

| Layout | Plaza shape |
|---|---|
| `RADIAL`, `PLAZA`, `CROSSROADS_RADIAL` | `CIRCLE` |
| `CROSSROADS`, `GRID`, `ENCLAVE`, `HILLTOP` | `SQUARE` |
| `RIVERINE`, `COASTAL`, `RIDGE`, `TERRACED` | `LINEAR` |
| `CLUSTERED`, `SPRAWL`, `GROVE` | `IRREGULAR` |
| `DUAL_PLAZA` | `CIRCLE` primary + `SQUARE` secondary |
| anything else | `IRREGULAR` (default) |

## Tier-based sizing (prompt 17 implements)

| Tier | Plaza | Target area |
|---|---|---|
| `HAMLET`  | none — register a `VillageCenterMarker` instead | n/a |
| `VILLAGE` | small  | ~50 blocks² |
| `TOWN`    | medium | ~150 blocks² |
| `CITY`    | large  | ~300 blocks² |

## Plaza generation algorithm (prompt 17)

Sketch — full implementation in prompt 17:

1. Resolve `floorY` from the heightmap at the target center.
2. Generate the shape per `PlazaShape`:
   - `CIRCLE`: regular ~16-gon at radius `sqrt(targetArea / π)`.
   - `SQUARE`: 4-corner polygon at `side = sqrt(targetArea)`,
     rotation 0° or 45° from a deterministic seed.
   - `LINEAR`: rectangle whose long axis follows the layout's
     `majorAxis` hint, area-matched.
   - `IRREGULAR`: sample expanding rings from the target center,
     accept flat / unoccupied positions, simplify the boundary
     via Douglas-Peucker (the `Polygon.simplify` helper landed in
     prompt 16).
3. Terrain accommodation: deform the polygon away from water,
   committed buildings, and slopes outside tolerance. Up to ~30%
   shrink is acceptable; below 60% of target area, log warning and
   accept the smaller plaza.
4. Identify connected roads — endpoints inside the polygon land in
   `connectedRoadIds`; the paver treats those segments as
   plaza-internal.
5. Emit decoration sub-slots inside the polygon (`PARK_FEATURE` +
   `PLAZA_*` sub-tags). Position rules per the doc 04
   "Sub-slot emission" section.
6. Emit `SlotTag.PLAZA_ADJACENT` building slots just outside the
   polygon edge (the slot tag landed in prompt 16; emission lands
   in prompt 18 alongside the civic ring removal).

## Plaza-road integration

In prompt 17, plaza paving runs in a new `PlazaPaver` class that
sits alongside `VillageRoadNetwork.buildInitialNetwork`. It uses
the same `PathMaterial` source the road realiser uses. Because the
palette matches, the road realiser overwriting plaza interior
blocks (or vice versa) at the polygon edges produces no visible
seam. No changes to `OrganicRoadPlacer` are required —
prompt-15 audit's strict "extend the road realiser" path was
classified substantial; the parallel-pass approach is moderate and
visually equivalent.

## Plaza-adjacent buildings (prompt 18)

`SlotTag.PLAZA_ADJACENT` is appended in prompt 16 (no emitters
yet). Prompt 18 wires:
- The polygon generator emits PLAZA_ADJACENT-tagged slots along
  the polygon edge during compose().
- `PlanContext.tryCommitBuilding` adds a rotation override: if a
  slot is within ~8 blocks of a `PlazaRegion` polygon edge, the
  building rotates to face the polygon centroid instead of the
  feeding road. Layered on top of the existing rotation logic;
  slots not near a plaza fall through to the existing
  `rotationFacingRoad` behaviour.

## Multi-plaza support (DUAL_PLAZA)

Prompt 16 leaves DUAL_PLAZA's existing brittle workaround in
place — the second `LayoutPrimitive.TownSquare` overwrites
`civicRingRadius`, which the recipe compensates for by saving
`sq1Ring` locally. Prompt 17 fixes this by registering two
`PlazaRegion`s with different purposes (CIVIC + MARKET) on
`PlanContext` and reading per-region geometry; the workaround
goes away then. Comment marker added to
`DualPlazaRecipe.compose()` pointing at prompt 17.

## Sub-slot emission

The plaza sub-slot emission added in prompt 14 (
`DecorationSlotEmitter.emitPlazaSubSlots` reading
`village.getTownSquareRadius()`) carries forward unchanged in
prompt 16. Prompt 17 will replace its radius-based footprint
calculation with polygon-aware position sampling, but the
mechanism (PARK_FEATURE + sub-role tag, deterministic UUIDs,
gathering-point parity) stays.

## Open-air market integration

`PlazaPurpose.MARKET` regions reserve a vendor sub-region inside
the polygon. The decoration framework leaves the sub-region empty;
the festival decorator (subsystem 13) populates it with stalls
during MARKET_DAY events. The reservation lives on the polygon
itself — interior sub-slot positions for stalls are sampled
during MARKET_DAY effects rather than persisted on the plaza.

## Behavior contract

### Does

- Pave the plaza with the village's road palette (prompt 16
  surgical fix).
- Define a polygon-based geometry that prompts 17/18 populate.
- Provide a `PLAZA_ADJACENT` slot tag for prompt-18 civic
  placement.
- Persist plaza data on `Village` so saves round-trip.

### Does not

- Generate polygon plazas yet (prompt 17).
- Replace civic-building placement around the plaza (prompt 18).
- Modify `OrganicRoadPlacer` or any other road realiser logic.
- Touch the legacy `LayoutPrimitive.TownSquare` ring-road or
  `civicRingRadius` mechanism — that's prompt 18.

## Replaces

Eventually (across prompts 16–18):
- `TownSquarePlacer` (the legacy 1-radius well placer; mostly
  dead code now, retained for event-decoration helpers).
- `TownSquareComposer` (prompt 14's stamped-square model;
  retained in prompt 16 with palette switch, removed in prompt
  17).
- `TownSquareKit` / `TownSquareKitRegistry` /
  `DefaultTownSquareKits` (replaced by direct
  `DecorationProfile` registrations in prompt 17).
- `LayoutPrimitive.TownSquare`'s ring road and
  `civicRingRadius` (replaced by `PLAZA_ADJACENT` slot tag in
  prompt 18).

Already kept by prompt 16:
- `DecorationTag.PLAZA_*` sub-role values (extended enum from
  prompt 14).
- `GatheringPoint` / `GatheringPointKind` (prompt 14).
- `DecorationSlotEmitter.emitPlazaSubSlots` (prompt 14).

## Open decisions

- **Polygon edge softening.** Should the polygon's outermost
  ring use `sampleEdge` (with no per-block noise, since the plaza
  is deterministically flat) or apply organic noise like
  `OrganicRoadPlacer.shouldPlaceEdge`? Proposed: `sampleEdge`
  without noise for now; revisit during prompt 17 visual
  validation.
- **DUAL_PLAZA primary purpose.** CIVIC is the natural pick;
  the secondary plaza could be MARKET or `RELIGIOUS_COURTYARD`
  depending on village type. Proposed: MARKET as the default;
  village-type rules override.
- **Polygon simplification tolerance.** Default 1.5 blocks for
  IRREGULAR plaza simplification — drops vertices collinear
  within ~1 block of neighbours, keeping the polygon recognisable
  but not jagged.

## Does-not-include

- NPC behaviour at gathering points — NPC Phase 2 work.
- Festival activation of `MARKET` vendor sub-regions — subsystem
  13 work.
- Cross-village plaza-to-plaza road styling — Trade Route
  subsystem.

## Revision notes

### Prompt 16 — palette fix + data model + slot tag

- **Plaza paving palette switched to `PathMaterial`.** The legacy
  paving used `VillageBiomeStyle.stone`/`stoneSlab`/`pathState` —
  a different palette than the road realiser's `PathMaterial`,
  which created the visible plaza-road seam the prompt-15 audit
  identified. `TownSquareComposer.pavePlaza` now resolves the
  same `PathMaterial.forBiomeAndTier(style, village.getPathTier())`
  the road realiser uses, samples the interior from
  `material.sampleCore(random)` and the outer ring from
  `material.sampleEdge(random)`, and **drops the legacy slab
  ring** so the plaza no longer reads as a separately authored
  square pattern.
- **Polygon utility added** at
  `Utilities/Geometry/Polygon.java` — minimal API
  (`contains` / `distanceToEdge` / `area` / `boundingBox` /
  `simplify` via Douglas-Peucker / `centroid`) sized for plaza
  polygons (4-32 vertices). 2D in XZ; Y handled by callers.
- **Plaza data model** in `Village/Decoration/Plaza/`:
  `PlazaPurpose`, `PlazaShape` (both StringRepresentable, codec-
  stable), `PlazaRegion` (RecordCodecBuilder + UUID +
  Polygon-valued footprint), `VillageCenterMarker` (HAMLET
  alternative).
- **PlanContext extension** — `addPlazaRegion`,
  `getPlazaRegions`, `getPlazaRegionContaining`,
  `getPlazaRegionNear`, `setVillageCenter`, `getVillageCenter`.
  No producers yet (prompt 17); accessors exist so subsequent
  prompts have stable integration points.
- **Village codec extension** — new `VillagePlazaMeta` sub-record
  added as the 16th group field on `Village.CODEC` (the existing
  `VillageLayoutMeta` was already at 14 of 16 fields after
  prompt 14). `optionalFieldOf` defaults so pre-prompt-16 saves
  load with empty plaza state.
- **`PLAZA_ADJACENT` slot tag** appended to `SlotTag` (plain
  Java enum, no codec).
- **DUAL_PLAZA workaround flagged**, not fixed —
  `DualPlazaRecipe.compose` gains a `TODO(prompt 17)` comment
  pointing at the polygon-based fix.
- **Civic ring NOT touched.** Prompt 18 work.
- **Polygon NOT generated.** Prompt 17 work — plaza is still a
  stamped square, but the palette is now continuous with roads.

### Prompt 17 — polygon generator + paver + recipe integration

- **`PlazaGenerator` lands** at
  `Village/Decoration/Plaza/PlazaGenerator.java`. Single entry
  point `generate(PlanContext, PlazaSpec) → Optional<PlazaRegion>`.
  Produces CIRCLE / SQUARE / LINEAR / IRREGULAR polygons via
  shape-specific algorithms:
  - CIRCLE: 16-gon at `radius = sqrt(targetArea / π)`.
  - SQUARE: 4-corner polygon, deterministic 70/30 cardinal/diagonal
    rotation roll seeded from `worldSeed XOR center.hashCode()`.
  - LINEAR: rectangle with `width = 5`, `length = targetArea / 5`,
    rotated to the recipe-supplied `majorAxis` cardinal.
  - IRREGULAR: 16-gon with per-vertex radial perturbation in
    [0.7×, 1.3×] base radius, simplified via `Polygon.simplify`
    at tolerance 1.0. Seed-derived determinism, visually distinct
    polygons per village.
- **Terrain accommodation is single-pass per-vertex.** Vertices
  on water (heightmap surface block is liquid) or steep slope
  (>2 block delta over 3-block radius) are nudged 2 blocks
  toward the centroid. After nudging, the polygon is simplified
  again (DP tolerance 0.5) in case multiple vertices collapsed
  onto the same XZ. Building-recession is **deferred to prompt
  18** because committed buildings don't exist at compose time.
- **`connectedRoadIds` left empty in prompt 17.** Road UUIDs are
  assigned at realisation time inside `VillageRoadNetwork.
  buildInitialNetwork`, not during recipe compose. Prompt 18
  backfills these post-realisation when the matching is
  unambiguous.
- **`PLAZA_ADJACENT` slot emission landed in
  `PlazaGenerator`.** Slots emitted along the polygon edge
  every ~6 blocks of perimeter, just outside the polygon
  (outset 2 blocks). For a CITY plaza (~75-block perimeter)
  that's ~12 PLAZA_ADJACENT slots — enough variety for prompt
  18's civic placement to choose from. No layout consumes
  these tags in prompt 17.
- **`PlazaPaver` lands** at
  `Village/Decoration/Plaza/PlazaPaver.java`. Runs after the
  road network in `VillageDecorator.decorateVillage`. Uses
  `PathMaterial.sampleCore` for polygon interior and
  `sampleEdge` for the edge transition (2-block outset with
  position-seeded coverage noise). Same `PathMaterial` source
  the road realiser uses, so polygon-road overwrite is
  visually benign.
- **Recipe integration via `RecipeHelpers.installPlaza`.**
  Single-line call per recipe; the helper handles HAMLET
  (registers `VillageCenterMarker`, skips polygon),
  tier→targetArea mapping (VILLAGE=50, TOWN=150, CITY=300),
  and dispatch to `PlazaGenerator`. 17 recipes updated:
  RADIAL/PLAZA→CIRCLE; CROSSROADS/ENCLAVE/HILLTOP/OUTPOST
  →SQUARE; LINEAR/CHAIN/RIVERINE/DOCKSIDE/ROADSIDE/TERRACED
  →LINEAR (with cardinal axis); CLUSTERED/SPRAWL/GROVE/DUMBELL
  →IRREGULAR; DUAL_PLAZA→CIRCLE primary + SQUARE secondary
  (purpose=CIVIC primary, purpose=MARKET secondary).
- **PlanContext ↔ VillageLayout ↔ Village plaza plumbing.**
  `pctx.addPlazaRegion` mirrors onto `VillageLayout`;
  `Village.applyLayout` carries plaza regions + center marker
  from layout to persisted village. Codec round-trips work
  (the `VillagePlazaMeta` sub-record landed in prompt 16).
- **DUAL_PLAZA `civicRingRadius` workaround stays in place.**
  The ring road still does civic placement work; per-region
  radii arrive in prompt 18 with the ring road retirement.
  Both plazas are now registered as separate `PlazaRegion`s
  (CIVIC + MARKET) on the `Village`, so the polygon side of
  the architecture is no longer brittle.
- **`DecorationSlotEmitter.emitPlazaSubSlots` is polygon-aware.**
  When a `PlazaRegion` is registered, the emitter derives an
  effective centre + radius from the polygon's centroid +
  `sqrt(area/π)`. Otherwise it falls through to the legacy
  `townSquareRadius` path. Existing sub-slot positions and
  tags unchanged; only the dimension source differs.
- **Civic ring NOT touched.** Prompt 18 work.
- **`TownSquareComposer` retained.** Still paves a stamped
  square at village centre via the prompt-16 palette path;
  the polygon paves the polygon. They overlap at centre with
  the same palette — no visible duplication. Composer removal
  is part of prompt 18 alongside the civic ring retirement.
