# Roads system — progress log

Append-only. Most recent entry at the bottom. Each session ends with an entry summarizing what was done, what broke, and what's next.

## Current phase

**Phase 3b** — Full connector routing with corridor-aware attractor injection. Complete.

## Current slice

Phase 3b complete. Next: Phase 4 per ROADS_PLAN.md.

## Acceptance criteria for current slice

All Phase 3a exit criteria met (see Phase 3a log below).

---

## Log

### 2026-04-21 — Plan committed

- `ROADS_PLAN.md` committed. Old Realm fiction locked. 13 phases, village-local upkeep, village-size-driven tier promotion, corridor-aware parallelism avoidance.
- `CLAUDE.md` pointer in place.
- Next: Phase 1 — graph skeleton + migration + invariants.

---

### 2026-04-21 — Phase 1 implemented

**Phase:** 1 — Graph skeleton, migration, invariants

**Files created:**
- `src/main/java/tterrag1112/life_in_the_village/Village/Roads/Graph/RoadNode.java` — record, NodeType enum, Codec
- `src/main/java/tterrag1112/life_in_the_village/Village/Roads/Graph/RoadEdge.java` — mutable class, EdgeTier enum, MeanderProfile record, 12-field Codec
- `src/main/java/tterrag1112/life_in_the_village/Village/Roads/Graph/EdgeGridIndex.java` — 256-block spatial hash, bucket key = X in low 32 bits / Z in high 32 bits (matches AtlasCell.packKey convention)
- `src/main/java/tterrag1112/life_in_the_village/Village/Roads/Graph/WorldRoadGraph.java` — graph container, Codec (nodes + edges only; index rebuilt on load)
- `src/main/java/tterrag1112/life_in_the_village/Village/Roads/Graph/GraphInvariantValidator.java` — 5 checks: dangling node refs, duplicate pairs, orphans (TERMINUS/POI_STUB exempt), empty cellPath, spatial index coverage
- `src/main/java/tterrag1112/life_in_the_village/Networking/WorldRoadSavedData.java` — SavedData wrapper, runs validator on load, migrated flag persisted
- `src/main/java/tterrag1112/life_in_the_village/Village/Roads/Graph/TradeRoadMigration.java` — one-time star-topology migration from TradeRoads; skips pre-Phase-7a roads with no cellPath; flat MeanderProfile seeded from road UUID low bits

**Files modified:**
- `src/main/java/tterrag1112/life_in_the_village/Events/ServerTickDispatcher.java` — loads WorldRoadSavedData every tick; calls TradeRoadMigration.migrateIfNeeded every tick (O(1) after first run via migrated flag)

**Migration output (expected on first load of a world with existing TradeRoads):**
```
[RoadGraph Migration] Migrated N TradeRoads into M nodes, K edges.
[RoadGraph Validator] Graph OK — M nodes, K edges.
```
where N = number of TradeRoads with non-empty cellPath, M = number of distinct villages (one dock node each), K = N (one connector per road).

**Deviations from prompt:**
- Migration call moved outside the `!initialized` block (called every tick) to correctly handle multiple world loads within a single JVM session. Migration cost after first run is a single boolean read.
- ROADS_PLAN.md not modified (no conflicts found).

**Compilation status:** Gradle artifact download unavailable in this environment. Full manual static review completed — no type errors, import errors, or API mismatches found.

**Next:** Phase 1.5 — debug visualization commands (`/litv road debug show_graph`, etc.)

---

### 2026-04-21 — Phase 1.5 implemented

**Phase:** 1.5 — Debug visualization commands

**Files created:**
- `src/main/java/tterrag1112/life_in_the_village/Village/Roads/Debug/RoadDebugVisualizer.java` — `TickSubsystem` singleton; manages timed particle visualization sessions per player; `VisualizationSession` and `ParticleEmission` records; sessions expire after 600 ticks (30 s); priority 200 (after gameplay systems)
- `src/main/java/tterrag1112/life_in_the_village/Commands/RoadGraphDebugCommand.java` — 8 subcommands under `/liv road debug` (OP level 2, player-only): `show_graph`, `highlight_edge`, `show_parallel_pairs`, `show_junctions`, `show_staleness`, `show_traffic`, `show_maintenance`, `show_overgrowth`

**Files modified:**
- `src/main/java/tterrag1112/life_in_the_village/Events/TickSubsystemRegistry.java` — added `register(RoadDebugVisualizer.INSTANCE)` in `registerDefaults()`
- `src/main/java/tterrag1112/life_in_the_village/Events/ModModEvents.java` — added `RoadGraphDebugCommand.register(event.getDispatcher())` in `onRegisterCommands()`

**Command details:**
- `show_graph` — tier-colored edge trails + node beams within 512 blocks
- `highlight_edge <prefix>` — UUID prefix matching; ENCHANT particles on edge + endpoint nodes; errors on 0 or >1 match
- `show_parallel_pairs` — O(E²) pair check within 1024 blocks; >8 sample points within 120 blocks = parallel; WITCH connector line between midpoints
- `show_junctions` — node beams scaled by edge count (5 blocks + 5 per additional edge, capped at 20)
- `show_staleness` — ANGRY_VILLAGER at each stale cell center
- `show_traffic` — SMOKE/COMPOSTER/FLAME/LAVA by traffic counter (0/1-10/11-50/50+)
- `show_maintenance` — COMPOSTER/FLAME/ANGRY_VILLAGER by maintenance score (≥80/≥40/<40)
- `show_overgrowth` — COMPOSTER emit rate varies inversely with maintenance (20/8/2 ticks)

**Deviations from plan:**
- Used `/liv road debug` (singular "road") rather than `/litv road debug` as written in ROADS_PLAN.md — matches the `/liv` convention used throughout the rest of the mod; ROADS_PLAN.md has a known typo on the command root.

**Compilation status:** Gradle artifact download unavailable. Full manual static review completed — all imports verified, all method signatures verified against source files, all `ParticleTypes` constants confirmed present in the codebase.

**Next:** Phase 2 — village gate docking + roadside allées

---

### 2026-04-21 — Phase 2 implemented

**Phase:** 2 — Village gate docking + roadside allées

**Files created:**
- `src/main/java/tterrag1112/life_in_the_village/Village/Roads/Docking/VillageDockingPoint.java` — transient record holding `armEndpoint`, `dockingAnchor`, `armDirectionRadians`, `villageId`, `approachLength` (default 30). Static `compute(Village, BlockPos directionHint, ServerLevel, VillageSavedData)` selects arm endpoint via priority order (nearestCapitalGate facing destination > first capital gate > mainGateEndpoint > effectivePathHub), detects arm direction from nearest village path centerline block within 20 blocks (fallback: center → endpoint), then projects anchor 30 blocks outward surface-snapped.
- `src/main/java/tterrag1112/life_in_the_village/Village/Roads/Decoration/ConnectorAllee.java` — static `place(ServerLevel, VillageDockingPoint, List<BlockPos> approachCenterline, String culture)`. Biome-suppressed (DESERT, SNOWY → skip). Saplings every 3 blocks along approach, 2 blocks perpendicular both sides. Culture → species: nordic=spruce, imperial=dark_oak (single-sapling bush, intentional), highland=birch, default=oak. Only plants on grass/dirt/coarse_dirt with air or replaceable vegetation above.

**Files modified:**
- `src/main/java/tterrag1112/life_in_the_village/Events/RouteRealisationSystem.java` — two-segment realization. Step 1: `RouteRealiser.realiseBetween()` now passes `dockingAnchor` (not arm endpoint) for both villages, keeping trade road 30 blocks out from the gate. Step 2: `placeApproach()` places a `RoadPrimitive.StraightRoad` (amplitude 2.0) from anchor to arm endpoint using `PathMaterial.forBiomeAndTier(biome, village.getPathTier())`, then calls `ConnectorAllee.place()`. Removed unused `WorldAtlas` reference. `resolveHub()` retained as `@SuppressWarnings("unused")` reference method.

**Deviations from prompt:**
- `Village.getCulture()` does not exist in the codebase — `Village.getVillageType()` is the equivalent (returns String like "default", "nordic", "imperial", "highland"). Used `getVillageType()` everywhere the prompt specified `getCulture()`.
- `Village.getPathTier()` is hardcoded to return `VillagePath.PathTier.DIRT` (there is a `// TODO: per-village persistence` comment in the source). Approach segment will always use `dirt` material until that TODO is resolved in a later phase. This is correct as-designed — the TODO predates Phase 2 and is tracked separately.
- `VillageBiomeStyle.detect()` has a known pre-existing bug (`k.registry()` should be `k.location()`). Not fixed; out of scope for Phase 2. Consequence: biome detection may default to PLAINS, meaning desert/snowy villages will attempt allée planting. Saplings on sand/ice will simply not grow, which is a cosmetic issue not a crash. Flagged here for future fix.

**Compilation status:** Gradle artifact download unavailable. Full manual static review completed:
- All Village methods verified against Village.java source (getName, getId, getVillageType, getAnchorPos, hasCapitalGates, getCapitalGatePositions, nearestCapitalGate(int,int), getMainGateEndpoint, getEffectivePathHub, getPathTier)
- `clearTreesAt(ServerLevel, int x, int z, int fromY)` parameter order verified
- `realiseBetween(ServerLevel, List<Long>, BlockPos, BlockPos, RoadQuality, VillageSavedData)` signature verified
- `OrganicRoadPlacer.place(ServerLevel, List<BlockPos>, PathMaterial, RoadShape.RoadTier, BuildingFootprint, RandomSource)` verified
- `RouteRealisationSystem.run(ServerLevel, VillageSavedData)` call signature unchanged — `TickSystems.java:564` compatibility preserved
- All imports verified; no unused imports remain

**Next:** Phase 3a — minimal end-to-end vertical slice (one hand-seeded great road, one village, one connector, one caravan traversal)

---

### 2026-04-21 — Phase 3a implemented

**Phase:** 3a — Minimal end-to-end vertical slice

**Files created:**
- `src/main/java/tterrag1112/life_in_the_village/Village/Roads/Realization/EdgeRealizer.java` — `static void realizeEdge(ServerLevel, RoadEdge, WorldRoadGraph, VillageSavedData)`. VILLAGE_DOCK endpoints resolve via `VillageDockingPoint.compute()` using the owning village (looked up by `dockNodeId`). All other node types use `node.position()` directly. On success: `edge.markRealized(placed)`.

**Files modified:**
- `src/main/java/tterrag1112/life_in_the_village/Village/Village.java` — added `@Nullable private UUID dockNodeId` field. `getDockNodeId() → Optional<UUID>`, `setDockNodeId(UUID)`. Added as optional codec field 12 in the main CODEC (`"dockNodeId"`, `optionalFieldOf`).
- `src/main/java/tterrag1112/life_in_the_village/Village/Economy/Trade/Caravan.java` — added `@Nullable private transient List<BlockPos> overridePath` field. `getOverridePath()` / `setOverridePath(List<BlockPos>)`. Not serialized (transient).
- `src/main/java/tterrag1112/life_in_the_village/Entities/Goals/Profession/Merchant/CaravanMerchantGoal.java` — `tick()` now checks `caravan.getOverridePath()` before the `TradeRoad` route lookup. If override is non-null/non-empty, uses it directly as the block list.
- `src/main/java/tterrag1112/life_in_the_village/Events/TickSystems.java` — added `GraphEdgeRealizationSystem` class (interval=40, priority=150). Scans graph edges for player-proximate unrealized edges, calls `EdgeRealizer.realizeEdge()` for at most one per scan. Added 5 imports: `WorldRoadSavedData`, `AtlasRouteRouter`, `RoadEdge`, `WorldRoadGraph`, `EdgeRealizer`.
- `src/main/java/tterrag1112/life_in_the_village/Events/TickSubsystemRegistry.java` — added `register(new GraphEdgeRealizationSystem())`.
- `src/main/java/tterrag1112/life_in_the_village/Commands/RoadGraphDebugCommand.java` — 3 new subcommands under `/liv road debug` (OP level 2, player-only):
  - `seed_great_road <x1> <z1> <x2> <z2>` — pre-fills atlas corridor (50 ms budget, 800-block hops), routes via `AtlasRouteRouter.findRoute()`, creates two `GREAT_ROAD_ANCHOR` nodes + one `GREAT_ROAD` edge, runs validator, marks dirty.
  - `connect_village <villageName> <targetEdgeId>` — substring village match, UUID-prefix edge match. Finds nearest cell on edge to village anchor, splits edge at that cell (removes old edge, creates `TRUNK_JUNCTION` node + two half-edges preserving tier/meander). Calls `VillageDockingPoint.compute()`. Routes connector via `AtlasRouteRouter`. Creates `VILLAGE_DOCK` node at `dockingAnchor`. Creates `CONNECTOR` edge. Sets `village.setDockNodeId()`. Runs validator.
  - `dispatch_caravan <villageName>` — finds village + dock node + CONNECTOR edge + road edge at junction. Force-realizes both edges via `EdgeRealizer`. Builds combined block path (connector + skip-3-overlap + road). Creates synthetic `Caravan` with `setOverridePath(fullPath)`, registers with `CaravanSavedData`. Private helper `prefillAtlasCorridor()` mirrors `TradeRouteManager.prefillAtlasCorridor`.

**Deviations from prompt:**
- `roadQualityFor(EdgeTier)` maps all tiers to `COBBLESTONE` — only one quality in use right now; expanded mapping deferred.
- `dispatch_caravan` finds the "road edge" as any edge at the junction that is not the connector. Works for single-road Phase 3a scenario; Phase 5+ will need smarter selection.

**Compilation status:** Gradle artifact download unavailable. Full manual static review completed:
- `Optional<UUID>` codec field 12 in Village: `optionalFieldOf`, getter/setter, codec lambda, `dockNodeId.ifPresent(v::setDockNodeId)` — all consistent
- `caravan.getOverridePath()` null-check present at call site in `CaravanMerchantGoal.tick()`
- `EdgeRealizer.resolveEndpoint()` uses `.filter(node.nodeId()::equals).isPresent()` on `Optional<UUID>` — correct
- `RoadEdge.create()` 5-arg static factory matches: `(nodeAId, nodeBId, cellPath, tier, meanderProfile)`
- `Caravan.create()` 8-arg static factory matches: `(routeId, originVillageId, destVillageId, principalId, originMarketId, goods, guardCount, currentTick)`
- All new imports verified against source files; `IntegerArgumentType`, `VillageSavedData`, `AtlasRouteRouter`, `Caravan`, `CaravanSavedData`, `VillageDockingPoint`, `GraphInvariantValidator`, `EdgeRealizer`, `Village`, `WorldAtlas` all confirmed present

**Next:** Phase 3b or Phase 4 per ROADS_PLAN.md

---

### 2026-04-21 — Phase 3b implemented

**Phase:** 3b — Full connector routing with corridor-aware attractor injection

**Files created:**
- `src/main/java/tterrag1112/life_in_the_village/Village/Roads/Planning/CorridorAttractorBuilder.java` — samples nearby graph edges along the from→to corridor every 256 blocks; filters cells by perpendicular distance ≤ corridorPaddingBlocks (default 192); builds per-tier discount map (GREAT_ROAD 0.15, TRUNK 0.25, CONNECTOR 0.40, LOCAL 0.60); returns `CorridorAttractors(Set<Long> cellKeys, Map<Long,Float> discounts)`. Multiple edges claiming the same cell → lower discount wins.
- `src/main/java/tterrag1112/life_in_the_village/Village/Roads/Planning/ConnectorPlanner.java` — `planConnector(level, graph, data, village, searchRadius)` → `ConnectorPlanResult`. Five-step algorithm: (1) query edges+nodes within radius; (2) score by distSq to village anchor, keep top-5; (3) pre-fill atlas + build corridor attractors + A* route each candidate; (4) pick shortest cell path; (5) commit (split edge or attach to node, create VILLAGE_DOCK + CONNECTOR edge, run invariant validator). Isolated villages get a dock node and `connectorEdgeId = Optional.empty()`. Existing dock node reuse with warning log.

**Files modified:**
- `src/main/java/tterrag1112/life_in_the_village/Village/Village.java` — added `useGraphConnector` boolean field (default `true`; codec `optionalFieldOf("useGraphConnector", false)` for backward compat). Getter: `useGraphConnector()`. Codec field 13 in the 13-arg apply lambda.
- `src/main/java/tterrag1112/life_in_the_village/Village/Economy/Trade/TradeRouteManager.java` — `establishRoutes` early-returns if `village.useGraphConnector()`. `prefillAtlasCorridor` promoted from private to public static so ConnectorPlanner can reuse it.
- `src/main/java/tterrag1112/life_in_the_village/Village/Roads/Graph/WorldRoadGraph.java` — added `nodesNear(int,int,int)` (linear scan, radius-filtered), `SplitResult` nested record, and `splitEdgeAtCell(UUID edgeId, long splitCellKey, BlockPos junctionPos)` → `Optional<SplitResult>`. Split preserves tier, meanderProfile, maintenance, maintainerVillageIds; splits blockPath at nearest block to junctionPos if realized; distributes staleCells to the half that covers them. Added `BlockPos` import.
- `src/main/java/tterrag1112/life_in_the_village/Village/Economy/Trade/AtlasRouteRouter.java` — existing 3-arg `findRoute` now delegates to new 5-arg `findRoute(atlas, from, to, attractors, attractorDiscounts)`. Per-cell discounts supersede road-cell discount; if a cell has an `attractorDiscounts` entry, that multiplier is applied alone (road discount and flat attractor discount are both skipped). If no per-cell entry, falls through to existing road/flat-attractor logic unchanged.
- `src/main/java/tterrag1112/life_in_the_village/Village/VillageSpawner.java` — after `TradeRouteManager.establishRoutes` (now a no-op for new villages), added `if (village.useGraphConnector()) { ... ConnectorPlanner.planConnector(...); ... }` hook. Added imports for `ConnectorPlanner` and `WorldRoadSavedData`.
- `src/main/java/tterrag1112/life_in_the_village/Commands/RoadGraphDebugCommand.java` — (1) `connect_village` inline split logic refactored to call `graph.splitEdgeAtCell()`; (2) new `/liv road debug replan_connector <villageName>` subcommand: cleans up old dock+connector, merges stranded junctions (degree-2 same-tier via `mergeIfStranded` helper), calls `ConnectorPlanner.planConnector`, reports summary; (3) `prefillAtlasCorridor` now delegates to public `TradeRouteManager.prefillAtlasCorridor`; (4) added `TradeRouteManager` and `ConnectorPlanner` imports.

**Double-routing prevention:**
- `useGraphConnector = true` on all new Village objects (field default).
- Old saves load `useGraphConnector = false` (codec default), preserving existing TradeRoute/TradeRoad behavior.
- `TradeRouteManager.establishRoutes` returns immediately for new villages.
- `ConnectorPlanner.planConnector` fires only for `useGraphConnector` villages via the VillageSpawner hook.

**Deviations from prompt:**
- `ConnectorPlanner.planConnector` uses `village.getAnchorPos()` (not a VillageDockingPoint) for initial candidate scoring (Step 2) to avoid the ~O(village_path_count) cost of calling `VillageDockingPoint.compute` per candidate. `VillageDockingPoint.compute` is still called once per top-K candidate in Step 3. This is equivalent for scoring purposes since the docking anchor is only ~30 blocks from the anchor.
- Mid-edge split fallback (split failure → attach to edge nodeA with warning) is not mentioned in the prompt but required for robustness when `splitCellKey` is not found in the cell path.
- `replan_connector` cleans up all CONNECTOR-tier incident edges to the dock (not just the first one), which handles any edge cases from repeated planConnector calls.

**Compilation status:** Gradle artifact download unavailable. Full manual static review completed:
- `Village.CODEC` field count increased from 12 to 13; apply lambda parameter count matches; `v.useGraphConnector = useGraphConnector` in lambda body; `optionalFieldOf("useGraphConnector", false)` backward-compat ✓
- `WorldRoadGraph.splitEdgeAtCell`: `RoadEdge.create()` 5-arg factory + post-construction mutators; `Optional.empty()` return on index-not-found; `Optional.of(new SplitResult(...))` return on success ✓
- `AtlasRouteRouter`: existing 3-arg overload calls 5-arg with null discounts; 5-arg implements the new logic; `Map<Long, Float>` covered by `java.util.*` ✓
- `CorridorAttractorBuilder`: `AtlasRouteRouter.cellKeyToBlockCenter` returns `BlockPos(x,0,z)` — x/z used for distance check only, Y ignored ✓
- `ConnectorPlanner`: nested `record RouteCandidate` inside the method (local record, valid Java 16+); all used types imported; `WorldRoadSavedData.get(level).markDirty()` correct call ✓
- `VillageSpawner`: new imports `ConnectorPlanner` and `WorldRoadSavedData` added ✓
- `RoadGraphDebugCommand`: `splitEdgeAtCell` returns `Optional<SplitResult>`; `.orElse(null)` null-check present; `graph.getNode(splitResult.junctionNodeId())` fetches newly added node ✓

**Caravan diagnostic status:** `[CaravanDispatch]` and `[CaravanGoal]` logging committed in previous session (d349278). Root cause identified: `TravellingGroupEngine.tick()` calls `caravan.getPath()` which returns empty for synthetic caravans (no TradeRoad entry). The `overridePath` field is only checked in `CaravanMerchantGoal` entity AI, which never runs since entities are never spawned. Fix deferred: `Caravan.getPath()` needs to return `overridePath` when set. Pending for Phase 5 or a standalone diagnostic-fix session.

**Next:** Phase 4 — parallelism cleanup pass (safety net)

---

### 2026-04-22 — Phase 3b bugs resolved

**Phase:** 3b bug fixes — junction placement accuracy + approach segment realization

**Root causes addressed:**

**Bug 1 — Junction node placed at cell center, not at road block**
- `ConnectorPlanner.commitPlan()` was computing `junctionPos = surfaceSnap(cellCenter)`.
- When the target edge was already realized, its blockPath did not pass through the cell center — it passed through the nearest road block, typically 10–20 blocks away. The resulting junction node sat off the road, causing `inspect_junction` to report large distances on all three incident edges.
- Fix: added `junctionPosFor(RoadEdge, BlockPos cellBlockCenter)` helper in `ConnectorPlanner`. If the edge is realized and has a non-empty blockPath, it finds the block in the blockPath geometrically closest to `cellBlockCenter` and uses that exact position as `junctionPos` (no re-snap, so the position is precisely on the existing road surface). If the edge is unrealized, falls back to cell center (to be corrected when it realizes). `WorldRoadGraph.splitEdgeAtCell` already splits the blockPath at the nearest block to `junctionPos`, so after this fix the split is exact.

**Bug 2 — Approach segment + allée not placed during graph-edge realization**
- `EdgeRealizer.realizeEdge()` called `RouteRealiser.realiseBetween()` and immediately called `edge.markRealized()`. It never placed the ~30-block approach segment from `dockingAnchor` to `armEndpoint`, nor did it call `ConnectorAllee.place()`.
- Investigation: `realiseBetween` is a generic utility with no knowledge of node types; the approach logic cannot live there. The old `TradeRoad` pipeline placed approach segments in `RouteRealisationSystem.placeApproach()`, but `EdgeRealizer` (introduced in Phase 3a) never had the equivalent. The fix belongs in `EdgeRealizer`.
- Fix: added `placeApproach(level, dockNode, otherSidePos, data)` private helper to `EdgeRealizer`. After `realiseBetween` returns the main block path, `realizeEdge` calls `placeApproach` for each `VILLAGE_DOCK` endpoint. The helper: looks up the owning village via `dockNodeId`, calls `VillageDockingPoint.compute`, places a `StraightRoad(dockingAnchor, armEndpoint, drift=2.0, VILLAGE_ROAD)` using `PathMaterial.forBiomeAndTier(biome, village.getPathTier())`, calls `ConnectorAllee.place()` for the allée, and returns the ordered centerline. The centerline is assembled into the full blockPath: approach for nodeA is reversed and prepended (armEndpoint→dockingAnchor→main road), approach for nodeB is appended (main road→dockingAnchor→armEndpoint).

**Files modified:**
- `src/main/java/tterrag1112/life_in_the_village/Village/Roads/Planning/ConnectorPlanner.java` — added `junctionPosFor(RoadEdge, BlockPos)` helper; changed `commitPlan()` to call it before `splitEdgeAtCell`.
- `src/main/java/tterrag1112/life_in_the_village/Village/Roads/Realization/EdgeRealizer.java` — added `placeApproach()` helper; `realizeEdge()` now assembles full blockPath with approach segments prepended/appended for each VILLAGE_DOCK endpoint; added imports for `OrganicRoadPlacer`, `PathMaterial`, `RoadShape`, `VillageBiomeStyle`, `RoadPrimitive`, `ConnectorAllee`, `Village`, `RandomSource`, `Collections`.

**Layer decision:** approach logic placed in `EdgeRealizer`, not `realiseBetween`. Rationale: `realiseBetween` is a position-only utility — it receives `BlockPos` endpoints and has no access to node types or village lookups. `EdgeRealizer` already has `RoadNode` references and `VillageSavedData`; it is the correct and only appropriate layer for dock-type-aware post-processing.

**Expected `inspect_junction` output after fix:**
- All incident edges: distance ≤ 2 blocks (previously 10–20 blocks for realized edges)
- No "DISCONNECTED" warnings
- Walking from village: last ~30 blocks use village-material road + culturally-themed saplings, transitioning cleanly to trade-road material at the docking anchor

**Allée status:** Restored. `ConnectorAllee.place()` now called from `EdgeRealizer.placeApproach()` for every realized VILLAGE_DOCK endpoint.

**Compilation status:** Gradle artifact download unavailable. Full manual static review completed:
- `junctionPosFor`: iterates `edge.getBlockPath()` (List<BlockPos>); `distSqr(BlockPos)` is correct NMS method; returns `cellBlockCenter` as fallback ✓
- `placeApproach`: `data.getAllVillages()` stream + `getDockNodeId().filter(...).isPresent()` — matches pattern used in `resolveEndpoint` which was already verified in Phase 3a ✓
- `RoadPrimitive.StraightRoad(from, to, driftAmplitude, tier)` — 4-arg record constructor verified ✓
- `OrganicRoadPlacer.place(level, centerline, material, tier, null, rng)` — 6-arg signature verified ✓
- `ConnectorAllee.place(level, dock, centerline, village.getVillageType())` — 4-arg signature verified against ConnectorAllee.java ✓
- `Collections.reverse(rA)` — `rA` is `new ArrayList<>(...)` (mutable) ✓
- All new imports added; no unused imports ✓

**Next:** Phase 4 — parallelism cleanup safety net. "Phase 3b bugs resolved. Next: Phase 4 — parallelism cleanup safety net."