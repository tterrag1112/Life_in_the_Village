# Village Placement Rework — Deferred Items

Items intentionally out of scope, with one-line reasons. Read once before scope expansion.

## From the decoration rework's requirements

- **Building demolition cleanup cascade** — Needs cross-system coordination (AdjunctPlot, SubBuilding, FarmTerritory all hold building-id refs). Decoration rework's job. `LayoutPlan.anchors()` and the codec on `Sector` make the cleanup hook easier when it lands.
- **AdjunctPlot probe geometry helpers** — `LayoutSlot` now exposes enough (rotation, footprint W/L, `feedingEdgeId`) for AdjunctPlot to derive face positions. The probe logic itself is decoration's job.
- **WallPlan compute-then-realize pipeline** — Walls rework. The placement rework emits `WALL_ADJACENT` and `GATE_ADJACENT` slot tags from `DEFENSIVE_FRINGE` sectors and registers `WALL_GATE`/`WALL_TOWER` anchors. The realiser pass that lays actual wall blocks is downstream.
- **Cemetery temple-or-edge logic** — Cemeteries rework. Placement emits `CEMETERY_RESERVATION` reservations near the village edge (and near `TEMPLE` if one is anchored); decision logic for which to use is downstream.
- **Festival ground reservation logic** — Festival rework. Placement emits `FESTIVAL_RESERVATION` for capitals; festival-tick-time logic is downstream.

## From the kingdoms rework's requirements

- **Computing kingdom claims** — Kingdom Phase 1.3-1.4. Placement only produces seeds + slots + named anchors; claiming is later.
- **Membership decisions** — Kingdom Phase 1. A village seed has a culture; whether it joins a kingdom is decided later.
- **Province seats** — Kingdom Phase 3.1. The `provinceSeatEligible` schema flag is reserved (Phase 21) but no behavior.
- **Territory polygons** — Produced during claim emission. Placement does not need to know about polygons.
- **Spacing varies by culture** — The spacing-cost-function gets a `culture` parameter that this rework reserves but does not consume.

## Outside both reworks

- **NPC spawning changes** — `VillageInhabitantPopulator` keeps its current contract. It reads `placedBuildings` from the spawner; whether that comes from `VillageLayout` or `LayoutPlan` is a wiring detail.
- **Market stall placement** — Existing `MarketStallPlacer` keeps working; if it needs `LayoutPlan` access, it gets it through the `Village` accessor.
- **Trade route attachment** — `TradeRouteManager.establishRoutes` reads gate positions, which are now on `LayoutPlan.anchors()`. One-line change at the seam, no logic change.
- **World road graph join** — Where village `RoadGraph` connects to `WorldRoadGraph` is a separate seam. The village exposes `mainGate` node position via `LayoutPlan.anchors().get(AnchorKind.MAIN_GATE)` and the connector planner attaches there. No graph-type merge.
- **Atlas integration** — `WorldAtlas` answers "is this cell coastal?"; `FeatureMap` answers "where exactly is the shoreline within this village's footprint?" They consume each other but don't merge.
- **Player-modified terrain post-spawn** — `FeatureMap` is a planning-time snapshot, refined once at realization. Player digging out a hill later does not retrigger reanalysis.
- **`StructureSizeCache` rotation iteration** — Works as-is. If a sector growth strategy ever wants to rotate-search to fit a building, that's a future extension.

## Suggested-but-rejected for v1

- **`hardCapacity` distinct from `softCapacity`** — Collapsed into one `capacity` field plus `canGrow`. Soft/hard distinction is invented complexity until a use case demands it.
- **Six `EdgeRole` values** — Kept three (`SPINE`, `SPUR`, `RING`). `ARC` is a `RING` with curvature in the primitive; `RAMP` is a `SPUR` with elevation delta; `BRIDGE` is a primitive type, not a role.
- **All six debug subcommands** — Phase 2-9 ship `show_graph`, `show_sectors`, `show_hull`. The other three (`show_features` per-feature filters, `show_growth` history, `show_slots` per-tag filter) land later only if the first three aren't enough.
- **Five-step `BaseRecipe` lifecycle** — Three steps (`prepareFeatures`, `composeSectors`, `registerAnchors`). The original five-step had recipes overriding empty stubs; the three-step lets recipes that don't fit override `compose()` directly.
- **Stringly-typed `Map<String, BlockPos>` anchors** — Moved to `Map<AnchorKind, Integer>` referencing graph node IDs, so anchors and graph nodes can't drift.
- **Causeway primitive in Phase 14** — Optional; only land it if marsh/shallow-water terraces actually need it. The Bridge/Stairway primitives cover the common cases.

## Hard "no" — do not add to this rework

- Atlas-level village placement decisions. That's the kingdom rework's `ClaimVillagePlacer`.
- Inter-village political relationships. Kingdom rework.
- NPC profession assignment. Already its own system.
- Building inhabitant population. Already its own system.
- Economy / trade route logic beyond the gate-anchor seam. Economy rework.
- Worldgen-time atlas rebuilds. Atlas rework.

The rule for "should this be in scope" is: does it dictate the *shape* of the village layout (in the sense that buildings are placed, roads are routed, sectors are sized)? If yes, in scope. If it merely consumes the layout, out of scope.
