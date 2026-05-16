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
| 2 — Site analysis | `SiteAnalyzer.analyze` | feature map + culture + seed + (optional) inclination / tier overrides | `SiteContext` (anchor, originalAnchor, primary axis, network, derived spine path, viability tier, inclination, anchors, strategy) | read-only |
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

### 1a. Layer-2 internal order (Track E1 prompt 3 fix-up)

Within `SiteAnalyzer.analyze`, the order is now:

1. `computeTier(fmap)` → terrain-derived tier.
2. `computeInclination(fmap, culture, seed, tier)` → terrain-derived inclination.
3. **Apply spawn-command overrides** (if present) → `effectiveTier`,
   `effectiveInclination`. The terrain-derived values are kept in
   `Diagnostics` for the dump; everything downstream sees only the
   effective values.
4. `computeAnchor`, `choosePrimaryAxis`, `adjustAnchor` — terrain-
   only, inclination-independent.
5. `AnchorDetector.detect` → permissive `Anchor` list.
6. `StrategySelector.select(ctx, anchors)` → sees the **effective**
   inclination + tier.
7. `NetworkPlanner.plan(ctx, fmap, seed)` → grown from the selected
   strategy.
8. `NetworkPlanner.deriveSpinePath(network, axis, anchor)` →
   **derived, deprecated** linear view. Retained on the SiteContext
   for backwards-compat readers (commands, dump serializer). No
   planning code in Layers 3–5 consumes `spinePath` any more.

Pre-fix-up the adapter applied overrides post-analysis, after the
network was already planned against the terrain-sampled inclination
— producing `/spawn AGRICULTURAL CITY` → CIVIC angerdorf network
with AGRICULTURAL building selection mismatches.

### 1b. Network vs spine path (Track E1 prompt 3 fix-up)

The `Skeleton` (Layer 4) is now constructed from the
`NetworkSpec` directly, not from a `SpinePath`. The
chord-decomposed view is exposed as
`Skeleton.primarySegments()` (was `spineSegments()`); cross-
streets continue to be added by Phase 4a planning.
`Skeleton.spinePath()` remains as a lazy-derived view computed
on-demand via `NetworkPlanner.deriveSpinePath`. `RoadPainter`
(Layer 5) paints from `Skeleton.edges()` (the network's
primitives) rather than `spinePath().segments()`.

### 1c. Topology↔primitive requirements (Track E1 prompt 3 fix-up 2)

`LayoutStrategyRegistry.validateTopologyPrimitives` runs at
class load (eager, fail-fast) and asserts hard topology↔primitive
requirements:

| Topology | Required primitive(s) |
|---|---|
| HAUFENDORF | Ring |
| ANGERDORF | Ring |
| RUNDLING | Ring |
| EINZELHOF | — |
| REIHENDORF | — |
| CLUSTER | — |

A strategy declaring HAUFENDORF/ANGERDORF/RUNDLING without
`PRIM_RING` in `primitives` causes class init to throw
`IllegalStateException` naming the strategy, topology, and
missing primitive(s). The mod fails to load — a silent auto-fix
would hide authoring mistakes (this is exactly the bug that
made `industrial_haufendorf` and `residential_haufendorf` ship
without Ring in prompt 3 and degenerate into 30-block
straight-spine villages).

### 1e. Centre derivation contract (Track E1 prompt 3 fix-up 3)

`PhasedPlanner.findBestCandidate` treats the sampled cell `pos`
as the **building's front-edge cell**, not a generic probe. Two
rules:

1. **Frontage zone.** `pos` is eligible only if
   `nr.distance ∈ [road_half_width + 1, road_half_width + 1 +
   FRONTAGE_BAND_WIDTH]` (currently a 3-cell-wide strip per side
   per segment). Cells outside this band are rejected — they
   wouldn't be a valid frontage cell anyway, and pre-fix-up they
   produced redundant centres back at the canonical road
   setback.
2. **Centre = pos + footprint-depth/2 × local perpendicular.**
   The derived building centre sits one footprint-depth/2
   blocks behind `pos` along the segment's local normal. On any
   chord angle (axis-aligned or diagonal), `pos` and `centre`
   are within ~`fp.length / 2` blocks of each other. The
   centre-side admissibility re-check is now essentially a
   sanity check on a nearby cell — same terrain class on
   reasonable ground.

Pre-fix-up the centre was anchored to `nr.point + side *
requiredOffset * perp` regardless of where `pos` was sampled —
on a permissive soft cap `villageRadius × 2.0`, sample cells
20+ blocks from the road still iterated, derived centres at the
canonical setback, and required BOTH `pos` AND that far-flung
centre to pass `slope ≤ MAX_SLOPE = 3`. Two independent terrain
rolls; on Rings crossing varied relief, the second roll
rejected wholesale and produced the post-prompt-3 sacred-TOWN
abort pattern (1 of 12 buildings placed, "no positive-scoring
cell" drop reason).

### 1d. Terrain-adaptation thresholds (Track E1 prompt 3 fix-up 2 — stopgap)

`TerrainAdapter.LARGE_PLATFORM_THRESHOLD` was 10; raised to 16.
A per-priority `LOAD_BEARING_THRESHOLD_MULT = 1.5` bump
multiplies the ceiling for `Priority.CIVIC` and
`Priority.INFRASTRUCTURE` buildings (→ 24), so primary-bound
load-bearing types (TOWN_HALL, MARKET, INN, CHAPEL, WELL, …)
get a stronger effort before dropping.

**This is an explicit stopgap, not the real terrain-adaptation
rework.** No new adaptation modes (retaining walls, excavation,
terracing, cantilever). The richer rework lands in a separate
future prompt. Platforms on steeper ground will look rough for
now — the alternative is the village aborting with `missing
TOWN_HALL after terrain drops`, which is worse.

### 1f. Composition profiles (Track E1 prompt 5 rebalance)

`InclinationProfile` holds per-inclination `Map<BuildingType,
int[4]>` tables indexed by `ViabilityTier.ordinal()` (CITY=0,
TOWN=1, HAMLET=2, OUTPOST=3). Pre-rebalance the inclination
rosters were FARMHOUSE-dominant everywhere (CITY AGRICULTURAL:
25 FARMHOUSEs to 12 HOUSEs); post-rebalance they reflect the
ACOUP-flavoured medieval economy:

- **Farmhouses scale sub-linearly with tier.** CITY has more
  FARMHOUSEs than HAMLET in absolute terms but proportionally
  far fewer — cities import food from outlying agricultural
  villages rather than growing it within their walls. CITY
  AGRICULTURAL flipped from FARMHOUSE:HOUSE ≈ 2:1 to ≈ 1:6.
- **Houses scale super-linearly with tier.** Most growth from
  HAMLET → TOWN → CITY is non-farm population — artisans,
  merchants, civic workers, day labourers. CITY HOUSE counts
  are 3–6× HAMLET counts.
- **Civic buildings scale roughly linearly.** A CITY has ~3×
  HAMLET's civic count with diminishing returns.
- **Sacred sites get pilgrim infrastructure.** SACRED gets
  extra INNs scaled with religious importance — pilgrims need
  somewhere to sleep.
- **Resource buildings only ride resource-anchored
  strategies.** MINE / WOODCUTTER appear in INDUSTRIAL rosters
  but `industrial_haufendorf` strips them via
  `LayoutStrategy.excludedBuildings` (see 1g below).
- **MILLER tracks BAKERY everywhere.** Pre-rebalance 5 of 6
  inclinations had BAKERY without MILLER, causing the
  reconciliation cascade to trade-fulfil FLOUR on every site.
  Adding MILLER counts equal to BAKERY counts removes the
  cascade at its source.

Profile values are the *target initial selection* before
reconciliation drops, trade-fulfillment, and the
`BuildingSelector.sampleCount` ±25% triangular variance. Tune
individual numbers freely; the *principles* are what should
stay stable.

### 1g. Strategy↔composition coupling (Track E1 prompt 5)

`LayoutStrategy` gained an `excludedBuildings: Set<BuildingType>`
field. `BuildingSelector` applies the per-strategy exclusion
filter before NBT availability. Pre-prompt-5 a TOWN INDUSTRIAL
site with weak cliffs picked `industrial_haufendorf` (the
fallback) but the composition still asked for MINE / WOODCUTTER
— and those dropped at placement time with "no anchor binding."
Post-prompt-5 the strategy declares what it cannot place
(`{MINE, WOODCUTTER, STONEMASON}` for `industrial_haufendorf`)
and the composition selector filters them out cleanly.

Strategy-selection scoring (`StrategySelector`) also lowered the
primary-quality floors for the four resource-anchored strategies
(`industrial_mining` / `industrial_woodcutter` 0.5 → 0.3,
`agricultural_reihendorf` 0.5 → 0.3, `agricultural_marschhufendorf`
0.6 → 0.4) and added a `RESOURCE_PRESENCE_BONUS = 80` flat bonus
when the chosen primary anchor type is a *resource* anchor
(CLIFF_FACE / FOREST_EDGE / WATER_EDGE / RIVER_BEND — not
FLAT_FERTILE). A q=0.3 cliff therefore scores 30 + bonus 80 = 110
and reliably beats `industrial_haufendorf`'s 80-something from a
high-quality FLAT_FERTILE primary. The fallback only wins on
sites with no usable resource anchor.

### 1i. Network capacity scaling (Track E1 prompt 6)

Each topology's recipe in `NetworkPlanner.java` carries a tier-keyed
table of *secondary edge counts*. Prompt 3 delivered tier-scaled
primary-feature size (Ring radius, spine length); prompt 6 adds
tier-scaled COUNT of additional arterial edges so CITY-tier networks
host the CITY-tier composition that prompt 5 locked in.

Numbers per topology (count of *arterial* radials / cross-streets /
spokes, in addition to the existing per-secondary-anchor Spurs which
still emit unchanged):

| Topology | HAMLET | TOWN | CITY |
|---|---|---|---|
| HAUFENDORF radials (out from Ring) | 0 | 3 | 5 |
| REIHENDORF cross-streets (alternating sides of spine) | 0 | 3 | 5 |
| ANGERDORF radials (out from Ring) | 0 | 3 | 5 |
| RUNDLING inter-Ring radials | 0 | 2 | 5 |
| RUNDLING outer Spurs (beyond outer Ring) | 0 | 0 | 3 |
| CLUSTER spokes (out from primary) | 0 | 4 | 5 |
| CLUSTER cross-streets (between spoke endpoints) | 0 | 1 | 2 |
| EINZELHOF | (unchanged — hardcoded cap 3) |

Total CITY-tier centerline: HAUFENDORF ~380, REIHENDORF ~370,
ANGERDORF ~370, RUNDLING ~390, CLUSTER ~350. At the post-prompt-3-
fix-up-3 ~15-block slot spacing, ~380 blocks of frontage supports
~50 buildings cleanly — enough for the 60-65 CITY composition with
drops in single digits.

**Directional selection** (`distributeRadialAngles` in
`NetworkPlanner.java`): given `M` desired radials and a list of
filtered secondary anchors:
1. Sort filtered secondaries by Euclidean distance from primary.
2. `K = min(M, N)` closest secondaries get a radial each, at their
   bearing direction.
3. Remaining `M - K` radials are placed at the midpoints of the
   currently-largest angular gaps. When K=0, pure even spacing
   `360°/M` with a deterministic phase offset from `rng.nextDouble()`
   so two similar sites differ.
4. Any pair within `MIN_RADIAL_SEPARATION_RAD = 30°` is symmetrically
   pushed apart (4 relaxation passes max).
5. Phase offset is consumed even at K=0 so the RNG sequence stays
   stable independent of anchor count for the same site.

**Topology distinctions preserved.** HAUFENDORF gets a starburst (no
outer Ring — that's RUNDLING's signature). ANGERDORF gets radials
WITHOUT outer Spurs (the inner Arc already handles decorative
flourish). RUNDLING gets inter-Ring radials reinforcing the
concentric-Ring + compass-rose feel rather than breaking it.

**CLUSTER footnote.** The compass-rose spoke pattern is a rough fit
for `industrial_mining`; the proper shape is a two-node graph (mine
site + residential cluster connected by an arterial). Introducing a
DUMBBELL or RESOURCE_LINK topology variant for that case is a
future refinement; for now CLUSTER's expanded spokes provide the
frontage capacity at the cost of geometric authenticity.

**Stairway not yet paintable** — known limitation flagged for a
future fix-up. `RoadPainter.paintAll` returns 0 for Stairway
primitives (line ~115, comment: "Stairway is not produced by
NetworkPlanner recipes today (the Y-jumping centerline needs a
dedicated painter)"). No prompt-6 recipe emits Stairway, so this
doesn't affect current spawns. It will matter when (a) the proper
two-cluster industrial_mining topology lands and wants a Stairway
from a high cliff mine down to a low residential cluster, or (b) a
future prompt adds steep-terrain topology variants. Worth tracking.

### 1h. Primary binding contract (Track E1 prompt 5)

Pre-prompt-5 `PrimaryBinding` was a soft scoring nudge only — a
20-block centrality bonus that buildings could legitimately
ignore if other scoring components pulled harder. A MARKET bound
to anchor `a1` could land 130+ blocks away.

Post-prompt-5 primary bindings are **strict-with-small-fallback**:

1. The placer's `findBestCandidate` accepts an optional
   `boundPos`. When set, cells outside `BINDING_AFFINITY_RADIUS =
   20` blocks of the binding's anchor centre are skipped.
2. If the strict pass finds no admissible cell, the caller
   (`placeOne`) records the type in `state.droppedBindings` and
   re-runs `findBestCandidate` unrestricted. The general selector
   places the building wherever frontage scoring picks.
3. The dump surfaces this on each binding via
   `siteContext.network.primaryBindings[].bindingDropped`.

A 20-block cutoff means placed bindings are within 20 blocks of
their anchor centre OR the binding is marked as dropped — never
both silent. Large-footprint buildings (MARKET 21×42, big
TOWN_HALL variants) will drop bindings more often because their
geometric setback `fp.length / 2` alone approaches the cutoff;
the general selector picks a placement by frontage scoring.
Acceptable — the strategy still produces a coherent village
without those specific bindings honored. (c-i decision: single
20-block constant rather than separate scoring + cutoff radii.)

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

## 8. JSON dump schema (v4)

**Schema v4** — additive over v3. v3 readers ignoring unknown
fields still parse v4 dumps without error. Note: `roads.skeleton
.spinePathPrimitives[]` no longer guarantees `StraightRoad`-only
content — the network grower may emit any RoadPrimitive type
into the derived spine path (Ring, Spur, ArmApproach, …).

### v4 additions (Track E1 prompt 5 — composition + bindings)

- New `siteContext.composition` object — building-type → count
  map AFTER `BuildingSelector` runs (which already applies the
  per-strategy `excludedBuildings` filter). Surfaces what the
  strategy filter actually chose so dumps don't need to
  cross-reference the per-inclination roster + strategy.
  Example for `industrial_haufendorf`: `{HOUSE: 14, MARKET: 1,
  BLACKSMITH: 2, …}` with no MINE / WOODCUTTER / STONEMASON.
- New `bindingDropped: boolean` field on each entry of
  `siteContext.network.primaryBindings[]`. True when the strict
  near-anchor placement search found no admissible cell within
  `BINDING_AFFINITY_RADIUS = 20` blocks of the anchor centre and
  fell back to the unrestricted general selector. Placed
  position may still be sensible, but the strategy's anchor
  intent was not honoured.

### v4 additions (Track E1 prompt-4 — secondary placement)

- New `siteContext.strategy.nucleusRules` object — echo of the
  strategy's nucleus configuration: `civicNucleus` (and optional
  `resourceNucleus` / `sacredNucleus`) as `{kind, anchorId?,
  buildingType?}` refs; `ruralNucleusTypes[]` building-type names;
  per-building `affinities` (each `{preferred, weight,
  idealDistance, maxDistance, fallback?}`); per-pair
  `penalties[]` (`{a, b, minDistance, penaltyWeight}`).
- New per-placed-building `nucleusContext` object:
  `{primaryNucleusKind, primaryNucleusAnchorId?,
  primaryNucleusBuildingType?, distanceToPrimaryNucleus}`.
  Indicates which nucleus pulled the placement. Absent when the
  building had no matching affinity (placed by base terrain
  residual only).

**Per-inclination default nucleus rules** (applied when a strategy
passes `null` for its `nucleusRules`):
| Inclination | Civic nucleus | Rural nuclei | HOUSE pull | Penalties |
|-------------|---------------|--------------|------------|-----------|
| AGRICULTURAL | primary anchor | `{FARMHOUSE}` | RURAL (12, 30) → CIVIC fallback | HOUSE↔BLACKSMITH min 8 |
| RESIDENTIAL  | primary anchor | — | CIVIC (8, 25) | — |
| CIVIC        | primary anchor | — | CIVIC (18, 35) | HOUSE↔BLACKSMITH min 6; CHAPEL↔BLACKSMITH min 12 |
| INDUSTRIAL   | primary anchor | — | CIVIC (10, 28) | HOUSE↔MINE min 20 |
| SACRED       | primary anchor | — | SACRED (18, 36) | CHAPEL↔BLACKSMITH min 15; SHRINE↔* min 8 |
| DEFENSIVE    | primary anchor | — | GATEWAY (6, 18) → CIVIC fallback | HOUSE↔TOWN_HALL min 12 |

The placer scores each cell as
`terrain + adjacency + spatial_fit + binding_affinity`, where
`spatial_fit` =
`0.70 × nucleus_score + 0.15 × road_bonus − 0.30 × proximity_penalty`.

### v4 additions (Track E1 prompt-3 — network grower)

- New `siteContext.network` object — the road-network spec
  produced by `NetworkPlanner`:
  - `topology`: `LayoutTopology` enum name (`"HAUFENDORF"` /
    `"REIHENDORF"` / `"ANGERDORF"` / `"RUNDLING"` /
    `"EINZELHOF"` / `"CLUSTER"`)
  - `nodes[]`: array of `{id, kind, pos}` where `kind` is one of
    `ANCHOR` / `GATEWAY` / `JUNCTION` / `SYNTHETIC`
  - `edges[]`: array of `{id, from, to, primitive, width,
    primitive_raw}` where `primitive` is the RoadPrimitive
    `typeKey()` and `primitive_raw` is the full centerline view
    (same shape as a `spinePath.segments[]` entry)
  - `primaryBindings[]`: array of `{type, position, anchorId,
    reason}` declaring lead-building anchor bindings
- `siteContext.spinePath` is now **derived** from
  `network.edges[]`; kept for backwards-compat readers.

### v3 additions (Track E1 anchor detection)

- New `siteContext.anchors[]` array — permissive list of feature
  anchors detected by `AnchorDetector` (flat zones, peaks,
  cliffs, water edges, etc.). Each anchor carries
  `{id, type, centre, quality, extent, dirX?, dirZ?, metadata?}`.
  See [§13 Anchor types](#13-anchor-types-track-e1).

### v2 additions (E1 follow-up)

### Three `command` modes

- **`"dump"`** — on-demand, named village (`/litv layout debug dump <name>`).
  Runs Layers 1-4 from scratch. `realization` section ABSENT
  (Layer 5 didn't run).
- **`"dump_at"`** — on-demand, player position
  (`/litv layout debug dump_at <radius>`). Same shape as `"dump"`.
- **`"auto"`** — auto-dump on V2 spawn (success or abort). Includes
  the `realization` section. Set by
  `V2VillageSpawnerAdapter.spawn`.

The `auto` path triggers on every successful V2 spawn AND every
abort (six abort branches: proximity check, UNVIABLE tier, no
placement, fatal overlap, post-terrain unviable, no NBT success).
Toggleable via JVM property `-Dlitv.debug.autoDumpLayouts=false`
or the runtime command `/litv layout debug autodump <on|off|status>`.
Default-on.

### Top-level fields

| Field | Type | Notes |
|-------|------|-------|
| `schemaVersion` | int | `4` for current version |
| `command` | string | `"dump"` / `"dump_at"` / `"auto"` |
| `tick` | long | server tick at dump time |
| `worldSeed` | long | level seed |
| `planSeed` | long | mixed seed used for V2 layers |
| `dimension` | string | dimension key (e.g. `"minecraft:overworld"`) |
| `origin` | `{x,y,z}` | dump origin (village anchor or player pos) |
| `village` | object | `{name?, id?, culture}` |
| `siteContext` | object | see below; absent when proximity-check aborted before Layer 2 ran |
| `aborted` | bool? | present + true when V2 aborted |
| `abortReason` | string? | one-line abort note when aborted |
| `buildings` | object | placed / dropped / unavailable / counts / reconciliation; absent at very-early aborts |
| `roads` | object | skeleton + frontageOwners summary |
| `phaseEvents` | array | PhasedPlanner diagnostics |
| `gateways` | array | gate positions + role + source |
| `realization` | object | **schema v2** — Layer 5 deltas (`"auto"` only); absent for `"dump"`/`"dump_at"` |

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
- **schema v2 fields (present only when `command="auto"`):**
  - `realizationMode`: `"LEVEL"` / `"PLATFORM"` / `"DROP"` — TerrainAdapter's call for this building
  - `padTargetY`: int — pad top Y (LEVEL = ground fill target; PLATFORM = solid pad top)
  - `realizationReason`: string? — TerrainAdapter's reason string when present

> **Why no `adjustedFrom`?** V2's `PlacedBuilding` is an immutable
> record; Layer 5 never moves a building's `centre` or `rotation`.
> The only "adjustment" is the pad height, captured by the flat
> `padTargetY` field. If a future planner mutates positions,
> schema v3 will add `adjustedFrom`.

**Track E1 follow-up — `buildings.viabilityDropped[]`** (schema v2):
plan-survivors that Layer 5 dropped. Each entry:
- `type`: BuildingType enum name
- `plannedCentre`: `{x,y,z}` — where the planner intended to place it
- `source`: currently always `"terrain_adapter"` (Track E1F catches TerrainAdapter `DROP` mode). Overlap-audit and viability-validator drops surface via `realization.overlapConflicts[]` and `realization.viabilityFailureReasons[]` respectively.
- `reason`: string?

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

### Realization section (schema v2, `"auto"` only)

`realization` captures Layer 5's actual effects on the world.
Present only when the dump was triggered by
`V2VillageSpawnerAdapter.spawn`; on-demand `dump` / `dump_at`
runs only Layers 1-4 so this section is omitted.

| Field | Type | Notes |
|-------|------|-------|
| `overlapConflicts[]` | array | `OverlapAuditor.Conflict` entries: `{description, pos, aDesc, bDesc}`. Pre-Layer-5b decisions; non-fatal overlaps are still recorded for diagnosis. |
| `overlapFatal` | bool? | present + true when `OverlapAuditor` triggered the fatal abort |
| `terrainAdaptation` | object | `{levelCount, platformCount, dropCount}` — count of each TerrainAdapter mode |
| `pads[]` | array | Per-pad entry for each non-DROP TerrainAdapter decision: `{type, centre, footprintWidth, footprintLength, mode, padTargetY, reason?}` |
| `vegetationCleared` | object | Aggregate: `{totalBlocks, byBuildingCount, byRoadSegmentCount}` — sum of `VegetationClearer.clearForBuilding/forRoadSegment` int returns + call counts |
| `placementErrors[]` | array | NBT placement failures: `{type, variantId?, reason}` — populated from `BuildingPlacer.placeAndRegister` returning empty or throwing |
| `viabilityFailureReasons[]` | array | Kingdom-level strings from `ViabilityValidator.ViabilityCheck.failureReasons` when post-terrain validation failed |

---

## 12. Auto-dump on spawn (Track E1 follow-up)

V2 auto-dumps every spawn via `V2VillageSpawnerAdapter.spawn`'s
hook. Triggered by:

- Success — full success path completed.
- Six abort branches:
  1. Proximity check failed (minimal: origin + reason).
  2. Site tier UNVIABLE (siteContext + reason).
  3. Layer 3-4 produced no viable village (Layers 1-4 + reason).
  4. Overlap audit fatal (Layers 1-4 + overlap report + reason).
  5. Post-terrain not viable (Layers 1-4 + terrain decisions + viability reasons).
  6. NBT placement failed for all survivors (Layers 1-4 + everything Layer 5 logged + reason).

The dump call is wrapped in try/catch; a serialization or IO
failure logs a warning and does not block the spawn.

### Toggle

| Mechanism | When to use |
|-----------|-------------|
| JVM property `-Dlitv.debug.autoDumpLayouts=false` | Disable from server launch (e.g. production servers) |
| `/litv layout debug autodump <on\|off\|status>` | Toggle at runtime without restart |

The on-demand `dump` and `dump_at` commands are **not** affected
by this toggle; they always run.

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

## 13. Anchor types (Track E1)

`SiteAnalyzer` produces a permissive list of feature anchors via
`AnchorDetector`. Anchors are NOT consumed by the existing spine
planner / placer / road builder — they're scaffolding for the
strategy / network-growth prompts that come next. Detection
returns every anchor of quality ≥ 0.2, sorted by quality
descending per type.

### Anchor record shape

| Field | Type | Notes |
|-------|------|-------|
| `id` | string | sequential `"a0"`, `"a1"`, … in detection order |
| `type` | enum string | one of the 11 anchor types below |
| `centre` | `{x,y,z}` | world-space "point" for this anchor |
| `quality` | float | `[0.0, 1.0]`; 1.0 = ideal example |
| `extent` | `{originX, originZ, width, length}` | axis-aligned bbox in world blocks |
| `dir` | `{dirX, dirZ}`? | normalised orientation vector for linear types only; the field is **always present and non-null** for `RIDGE_LINE`, `VALLEY_FLOOR`, `CLIFF_FACE`, `WATER_EDGE`, `RIVER_BEND`, `FOREST_EDGE`; **always absent** for non-linear types |
| `metadata` | object? | type-specific extras (see below) |

Orientation is a 2D unit vector rather than an enum bucket so
strategies can do continuous alignment comparisons (dot product)
or cardinal snapping (atan2 + round) as needed.

**Bidirectional canonical form** (Track E1A) — a ridge running
east-west is the same physical feature whether expressed as
`(1, 0)` or `(-1, 0)`. The `Anchor` record's compact constructor
canonicalises every direction to the non-negative-`dirX`
representative; if `dirX == 0`, the non-negative-`dirZ`
representative. Same feature → same serialized `dir` every time.

### Types

**Topographic / geometric:**
- `FLAT_FERTILE` — connected `{OPEN, SHORE}` cells with `localSlope ≤ 1`. Quality = area_ratio × flatness_factor. Metadata: `usableCellCount`, `averageFlatness`, `biomeHint?` (sampled at centre when `level` is available).
- `DEFENSIBLE_PEAK` — `HighGround` peak with ≥3 cardinal-side drops of ≥5 blocks. Quality = prominence/30 × steepSides/4. Metadata: `relativeElevation`, `steepSides`, `topCellCount`.
- `DEFENSIBLE_RING` — `HighGround` region with flat top + ≥80% perimeter drops ≥6. Quality = topAreaRatio × perimeterCompleteness. Metadata: `relativeElevation`, `flatTopCells`, `perimeterCompleteness`.
- `RIDGE_LINE` — cells locally maximal along one axis (perpendicular neighbours drop ≥4) chained into runs ≥5 cells. Linear. Metadata: `lengthCells`, `prominence`.
- `VALLEY_FLOOR` — cells locally minimal along one axis (perpendicular neighbours rise ≥4) chained into runs. Linear. Metadata: `lengthCells`, `depth`.
- `CLIFF_FACE` — `StoneRegion` with `avgSlope ≥ 4`. Quality = areaFactor × slopeFactor. Linear; orientation = longer bbox axis. Metadata: `avgSlope`, `area`, `coreCells`.

**Hydrology:**
- `WATER_EDGE` — flat-landside cells adjacent to WATER, flood-filled into segments. Linear; orientation = longer bbox axis. Metadata: `boundaryLength`, `landsideCellCount`, `flatRatio`.
- `RIVER_BEND` — water-edge components with aspect ratio ≥ 0.5 + length ≥ 8 cells (skinny rectangles = straight river; square-ish = bend). Linear; orientation = bbox diagonal. Metadata: `aspectRatio`, `landsideCellCount`.
- `ISLAND` — OPEN/SHORE component that touches no scan boundary AND has ≥85% perimeter bordering WATER. Metadata: `cellCount`, `surroundCompleteness`.

**Vegetation:**
- `FOREST_EDGE` — bbox-perimeter FOREST cells of a `ForestRegion` with at least one open-flat neighbour. Linear; orientation = longer bbox axis. Metadata: `boundaryLength`, `clearAdjacentCount`, `forestArea`.
- `NATURAL_CLEARING` — OPEN/SHORE component not touching scan boundary AND ≥75% perimeter bordering FOREST. Metadata: `clearingArea`, `wallCompleteness`.

**Cultural (reserved):**
- `CROSSROADS` — placeholder; detection returns empty until prompt-2+ wires the cross-village road graph.

### Detection guarantees

- **Permissive threshold** — every anchor with `quality ≥ 0.20` is returned; below that filtered out.
- **De-duplication** — same-type anchors merge when extents overlap by ≥50% OR centres are within 8 blocks. Cross-type anchors never merge. Survivor is the higher-quality copy; survivor's extent is unioned.
- **Determinism** — pure function of `V2FeatureMap`; same scan → same anchors.
- **Same scan window** as the rest of Layer 2 — radius 100 (200×200 blocks) at 2-block cell resolution.
- **Linear direction always present** (Track E1A) — every linear-type anchor in a dump carries a non-null `dir` object. Non-linear types omit `dir` entirely (not `null`).

### Strategy selection (Track E1B)

After anchor detection, `SiteAnalyzer` runs `StrategySelector` to
pick the best `LayoutStrategy` for the site's inclination + tier +
anchor mix. The result is attached to `SiteContext` and serialised
into `siteContext.strategy`. **Nothing downstream consumes the
strategy yet** — spine planner / building selector / road planner
all run unchanged. Prompt 3 wires consumption.

#### Topology enum

`LayoutTopology` — six glumbosch-style village forms:

| Topology | Shape | Used by |
|----------|-------|---------|
| `HAUFENDORF` | Organic heap; nucleus + radial spurs | AGRICULTURAL / RESIDENTIAL / CIVIC / INDUSTRIAL fallbacks |
| `REIHENDORF` | Linear along a feature axis | AGRICULTURAL / RESIDENTIAL on ridge / water / valley sites |
| `ANGERDORF` | Oval-plaza with concentric ring | CIVIC / SACRED at TOWN+ |
| `RUNDLING` | Circular defensive ring + radial gates | DEFENSIVE TOWN / CITY |
| `EINZELHOF` | Single compound + handful of support | DEFENSIVE HAMLET / SACRED HAMLET / isolated outposts |
| `CLUSTER` | Generic fallback; loose StraightRoad + Spur | Every inclination's `*_cluster_fallback` |

#### Strategy record shape

```java
record LayoutStrategy(
    String id,                       // "agricultural_haufendorf"
    Inclination inclination,         // AGRICULTURAL etc.
    LayoutTopology topology,
    AnchorPreferences anchorPrefs,   // primaryTypes / secondaryTypes / requireLinearFeature / minPrimaryQuality
    Set<String> primitives,          // RoadPrimitive.typeKey() strings
    BuildingBindings bindings,       // BuildingType → preferred AnchorType list
    StrategyConditions conditions,   // tierMin / tierMax / incompatibleAnchors
    String description               // human-readable
)
```

Sub-records:
- `AnchorPreferences(primaryTypes, secondaryTypes, requireLinearFeature, minPrimaryQuality)`
- `BuildingBindings(Map<BuildingType, List<AnchorType>>)` — declarative preference lists, not assignments
- `StrategyConditions(tierMin, tierMax, incompatibleAnchors)`

#### Selection algorithm

`StrategySelector.select(ctx, anchors)`:
1. Pull candidates from `LayoutStrategyRegistry.byInclination(inc)`.
2. Process each in registry order:
   - Reject if `tier` outside `[tierMin, tierMax]`.
   - Reject if any `incompatibleAnchors` type is present at quality ≥ 0.5.
   - Reject if no anchor of `primaryTypes` exists at quality ≥ `minPrimaryQuality`.
   - Reject if `requireLinearFeature` and primary isn't linear.
   - Otherwise score and continue.
3. Highest score wins; ties broken by registry order.
4. Zero scored candidates → fall back to the matching `<inclination>_cluster_fallback` strategy with score 0.

**Scoring formula** (locked design):
```
score = primary.quality × 100
      + 20 × min(3, count of matched secondary types within 96 blocks of primary)
      + 25 if (requireLinearFeature && primary.isLinear)
```

Penalty path is the incompatible-anchor reject above.

#### Registered strategies (21 default)

| Inclination | Strategies (in order) |
|-------------|------------------------|
| AGRICULTURAL | `agricultural_reihendorf`, `agricultural_marschhufendorf`, `agricultural_haufendorf`, `agricultural_cluster_fallback` |
| INDUSTRIAL | `industrial_mining`, `industrial_woodcutter`, `industrial_haufendorf`, `industrial_cluster_fallback` |
| CIVIC | `civic_angerdorf` (TOWN+), `civic_haufendorf`, `civic_cluster_fallback` |
| RESIDENTIAL | `residential_reihendorf`, `residential_haufendorf`, `residential_cluster_fallback` |
| SACRED | `sacred_isolated` (HAMLET only), `sacred_angerdorf`, `sacred_cluster_fallback` |
| DEFENSIVE | `defensive_keep` (CITY only), `defensive_rundling` (TOWN only), `defensive_einzelhof` (HAMLET only), `defensive_cluster_fallback` |

#### Adding a new strategy

One-entry change inside `LayoutStrategyRegistry.buildDefaults`:

```java
list.add(new LayoutStrategy(
    "agricultural_terraced",
    Inclination.AGRICULTURAL,
    LayoutTopology.HAUFENDORF,
    new AnchorPreferences(
        Set.of(AnchorType.RIDGE_LINE),
        Set.of(AnchorType.FLAT_FERTILE),
        true,
        0.5),
    Set.of("CurvedRoad", "Stairway", "Spur"),
    new BuildingBindings(Map.of(
        BuildingType.TOWN_HALL, List.of(AnchorType.FLAT_FERTILE),
        BuildingType.FARMHOUSE, List.of(AnchorType.FLAT_FERTILE))),
    new StrategyConditions(ViabilityTier.HAMLET, ViabilityTier.TOWN, Set.of()),
    "Hillside-terraced agricultural village"));
```

No class extension, no dispatch wiring, no parallel lookup tables. The `AnchorType` and `BuildingType` enums must already include the values referenced.

#### Schema v3 `strategy` section

Emitted under `siteContext` when a strategy was selected (every successful or post-anchor-detection-abort spawn):

```json
"strategy": {
  "id": "agricultural_haufendorf",
  "inclination": "AGRICULTURAL",
  "topology": "HAUFENDORF",
  "description": "Organic farming-heap village around a flat-fertile nucleus",
  "score": 105.0,
  "primaryAnchorId": "a0",
  "secondaryAnchorIds": ["a14"],
  "intendedPrimitives": ["StraightRoad", "Spur", "Ring"],
  "intendedBindings": {
    "TOWN_HALL": ["FLAT_FERTILE"],
    "MARKET":    ["FLAT_FERTILE"],
    "FARMHOUSE": ["FLAT_FERTILE"],
    "HOUSE":     ["FLAT_FERTILE"]
  },
  "selectionLog": [
    "Candidate agricultural_reihendorf: rejected, no primary anchor of types [...]",
    "Candidate agricultural_marschhufendorf: rejected, no primary anchor of types [...]",
    "Candidate agricultural_haufendorf: SELECTED, score 105.0",
    "Candidate agricultural_cluster_fallback: eligible (no primary required), score 0.0"
  ]
}
```

`primaryAnchorId` is `null` only for the fallback path; non-fallback strategies always have one.

#### Inspector commands

- `/litv layout debug strategy <villageName>` — runs the analyzer at the village's anchor and prints the strategy + selection log to chat. Useful when triaging "why did this site pick reihendorf instead of haufendorf?"
- `/litv layout debug dump <villageName>` — full JSON dump; includes the `strategy` section plus INFO-level summary in the server log on completion.

---

### Abort dumps (Track E1A clarification)

Auto-dumps fire on every V2 spawn, success or abort. The `assemble`
serializer is null-defensive: each Layer 1–5 section is emitted
when its input is non-null, and omitted when null. So abort dumps
carry whatever state existed when the abort fired:

| Abort branch | Layers complete | Sections present in dump |
|--------------|------------------|---------------------------|
| Proximity check (line 122) | — | header only (`schemaVersion`, `command`, `tick`, `origin`, `village`, `aborted`, `abortReason`) |
| UNVIABLE tier (line 150) | 1, 2 | header + `siteContext` (including `anchors[]`) |
| No viable village (line 168) | 1-4 | header + `siteContext` + `buildings` + `roads` + `phaseEvents` |
| Overlap fatal (line 178) | 1-4 + overlap audit | header + above + `realization.overlapConflicts[]` + `overlapFatal: true` |
| Post-terrain unviable | 1-4 + terrain decide + viability | above + `realization.viabilityFailureReasons[]` + per-pad detail |
| No NBT success | 1-5 | full content; `placementErrors[]` populated |

A one-line INFO log fires per abort summarising completed layers
for grep-ability: `"V2: <abortReason> — layers complete: fmap=true siteCtx=true anchors=42 selection=false placement=false roads=false"`.

The proximity abort is intentionally kept sparse — by the time
it fires, no terrain scanning has happened so there's no Layer 1+
state to serialize.

### Edge case behaviours

- **Superflat** — dedup merges into one large `FLAT_FERTILE` anchor of high quality. No other types.
- **Pure mountain** — `CLIFF_FACE` + `DEFENSIBLE_PEAK` + `DEFENSIBLE_RING` + `RIDGE_LINE`. No `FLAT_FERTILE` or water types. If no anchor passes the 0.2 threshold, the list is empty; downstream `ViabilityTier` evaluation handles the unspawnable case.
- **All-forest** — `FOREST_EDGE` + `NATURAL_CLEARING`. Small interior clearings may also fire `FLAT_FERTILE`.
- **All-water** — no land anchors; empty list. `SiteAnalyzer`'s existing UNVIABLE tier already blocks the spawn.

### Inspector commands

- `/litv layout debug anchors <villageName>` — runs the analyzer at the village's anchor and prints the anchor list to chat (no JSON output).
- `/litv layout debug dump <villageName>` — full JSON dump; includes anchors as part of the `siteContext` section + INFO-level summary in the server log on completion.

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
