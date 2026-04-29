# Village Placement Rework — Overview

## Why this rework exists

Three concrete pain points in the current system:

1. **Layouts are statically sized.** Each `ShapeRecipe` picks ring radii and spur counts up front from `density`/`totalBuildings`. When terrain or matcher pressure forces buildings off the planned slots, the only recourse is `fallbackPlaceRemaining` (perpendicular along any centerline) or `rescueOrphans` (emergency spurs). Both produce the "buildings off in random directions" symptom and silently waste designer intent.
2. **Slot semantics are flat.** `PlacementSlot` is a positional record with tags. There is no concept of *cluster* (a region with N candidates filled in order), *sector* (a region you can grow when overflow happens), or *reservation* (a region that excludes other claims). The legacy `BuildingZone` enum tries to encode this but is mostly dead code; the new `SlotTag` matcher does not consult it.
3. **Terrain features are detected but barely consumed.** `TerrainProfile` knows about water, ridges, and slope. Only RIVERINE/HILLTOP/TERRACED/CHAIN use them, each via ad-hoc geometry. There is no shared "road stops at shore," "sector clamped to hull," or "spur respects cliff edge" abstraction.

This rework also has to land robustness work that the kingdoms rework was originally going to do (fallback layout chains, iterative expansion, water/cliff-tolerant primitives, 90% placement success). Doing it here is much cheaper than retrofitting after.

## The four core abstractions

The whole rework hangs off these four data types. Everything else slots into them.

**`RoadGraph`** — Promotes `VillageLayout`'s parallel `List<RoadPrimitive>` + `Map<RoadPrimitive, List<BlockPos>>` into a real graph with typed nodes (`JUNCTION`, `GATE`, `TERMINUS`, `FOCAL`, plus kingdom-rework anchors like `CASTLE_ANCHOR`/`MANOR_ANCHOR`) and edges with roles (`SPINE`, `SPUR`, `RING`). Becomes the single source of truth for "what roads does this village have, where do they meet, what are they used for." Required for sector growth, expansion, debug rendering, and the kingdom rework's named anchors.

**`Sector`** — The missing middle layer between recipe and slot. A sector is a region of intent ("civic ring," "shore strip," "residential cluster NE") that owns an ordered slot pool, a capacity, a growth strategy object, and a parent road edge. Recipes emit sectors instead of bare slots. The matcher fills sectors greedily; on overflow it asks the sector to grow before falling back to peer sectors of the same role. Slot reservations (gardens, festival grounds) are sectors with capacity=0 and exclusion polygons.

**`FeatureMap`** — Computed once at planning time, then refined once at realization time. Holds the village hull, plaza polygons, water features, cliff features, and reservations. Every recipe, sector growth call, road primitive, decoration phase, and wall planner reads from this single source of truth. Removes the three-different-hull-implementations drift the decoration doc flagged.

**`LayoutPlan`** — Immutable result of planning. Carries the road graph, sector list, committed slots, feature map, and named anchors (enum-keyed references to graph nodes). Replaces the mutable `VillageLayout` as the contract that the spawner, decorator, kingdom planner, and wall planner consume.

## Build order

Foundation first, behavior second, conversions third. Each phase ships with debug visualization so failures surface visually instead of as silent slot drops.

| # | Phase | Prompts |
|---|---|---|
| 1 | RoadGraph data model + VillageLayout migration | 3-4 |
| 2 | Layout debug visualizer + `show_graph` | 1-2 |
| 3 | FeatureMap (planning-pass only) | 2-3 |
| 4 | FeatureMap determinism + two-pass refine | 2-3 |
| 5 | `show_features` / `show_hull` debug | 1 |
| 6 | Sector record + GrowthPolicy strategy interface | 2 |
| 7 | BaseRecipe abstract (3-step lifecycle) | 1 |
| 8 | Convert RADIAL to BaseRecipe + sectors (proof) | 3-4 |
| 9 | `show_sectors` debug | 1 |
| 10 | Growth integration into matcher | 2-3 |
| 11 | Convert PLAZA / LINEAR / CLUSTERED / ROADSIDE | 4 |
| 12 | Convert RIVERINE + Bridge primitive | 2-3 |
| 13 | Convert HILLTOP + Stairway primitive | 2 |
| 14 | Convert TERRACED + Causeway primitive | 2 |
| 15 | Convert remaining 9 recipes | 3-4 |
| 16 | `RoadPrimitive` signature change + water/cliff truncation | 2 |
| 17 | Farm plot sector integration | 2 |
| 18 | Plaza polygon ownership consolidation | 2 |
| 19 | LayoutPlan + AnchorKind + spawner/decorator wiring | 2-3 |
| 20 | BuildSiteFinder migration to graph + feature queries | 2-3 |
| 21 | VillageTypeData kingdom-rework schema additions | 1 |
| 22 | Recipe fallback chains | 1-2 |
| 23 | 90% success measurement + persistent-failure polish | 3-5 |

**Total estimate: 45-55 Claude Code prompts.** Wide variance because debugging passes are not predictable; the 90% measurement phase in particular may surface edge cases that require dedicated follow-up prompts.

## Exit criteria

Required to ship:

- A randomized 100-village test across a representative biome mix achieves ≥90% successful placement.
- `fallbackPlaceRemaining` and `rescueOrphans` are deleted; overflow is handled exclusively by sector growth.
- `BuildSiteFinder` consumes the new graph + feature infrastructure for expansion. The ring/spiral fallback path is gone.
- The same world seed produces the same `LayoutPlan` across reloads (deterministic). A test harness validates this on at least three seeds.
- Recipes that consume terrain features (RIVERINE, HILLTOP, TERRACED, CHAIN, DOCKSIDE) read from `FeatureMap` and not from ad-hoc per-recipe geometry.
- A road that would walk into water terminates at the shore with a `GATE` node, optionally handing off to a `Bridge` primitive.
- A road that would walk over a cliff edge clamps to the edge or hands off to a `Stairway` primitive.
- `VillageTypeData` carries the kingdom-rework schema fields (`capital_emits_claim`, `claim_resistance`, `vassal_types`, etc.) with default values and no behavior.
- The new debug commands (`/liv layout debug show_graph|show_sectors|show_hull`) are functional.

## How to use these documents

- `00-PLACEMENT-REWORK-OVERVIEW.md` (this file) — read first, then refer back as the table of contents.
- `01-PLACEMENT-ABSTRACTIONS.md` — the data types, APIs, and contracts. Self-contained; reference it from every phase prompt.
- `02-PLACEMENT-PHASES.md` — phase-by-phase implementation guide. Each phase lists files affected, validation criteria, and a prompt estimate.
- `03-PLACEMENT-DEFERRED.md` — items intentionally out of scope, with one-line reasons. Read once; consult before adding scope.

When invoking Claude Code, attach the relevant phase from `02` plus the abstractions doc. The phase doc references the abstractions doc by anchor (e.g. `see ABSTRACTIONS.md#sector`), so Claude Code can pull both into context without redundancy.
