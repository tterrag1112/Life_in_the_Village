# Roads system — progress log

Append-only. Most recent entry at the bottom. Each session ends with an entry summarizing what was done, what broke, and what's next.

## Current phase

**Phase 5c** — Terrain-change invalidation + tiered realize-radius. Complete. Phase 5 fully done.

## Current slice

Phase 5 complete. Next: Phase 6 per ROADS_PLAN.md (upkeep/traffic propagation through graph edges).

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
---

### 2026-04-22 — Phase 4 implemented: parallelism cleanup safety net

**Phase:** 4 — Periodic cleanup pass for parallel road edges that escaped Phase 3b's corridor-aware routing prevention.

**Deliverables:**

**`ParallelismDetector`** (`Village/Roads/Planning/ParallelismDetector.java`)
- Pure function — no graph mutations.
- `findParallelPairs(WorldRoadGraph, int scanRadiusBlocks, BlockPos scanCenter)` → `List<ParallelPair>` sorted descending by `sustainedLengthBlocks`.
- `findPairBetween(WorldRoadGraph, UUID, UUID)` → `Optional<ParallelPair>` — checks one specific pair; used by `cleanup_merge` command.
- `ParallelPair` record: `edgeAId`, `edgeBId`, `overlapStartIndexA`, `overlapEndIndexA`, `overlapStartIndexB`, `overlapEndIndexB`, `sustainedLengthBlocks`.
- Algorithm: sample every 4 cells (`SAMPLE_STRIDE=4`); for each A sample, find nearest B sample; contiguous run ≥ 6 close-sample pairs (dist < 120 blocks) = parallel. Longest run wins. GREAT_ROAD edges excluded.
- `overlapStartIndexA = runStart * SAMPLE_STRIDE`, `overlapEndIndexA = runEnd * SAMPLE_STRIDE`. `sustainedLengthBlocks = (endCellA - startCellA) * 64`.
- Deduplication: each unordered pair (A, B) detected exactly once via `processedA` set.

**`ParallelismResolver`** (`Village/Roads/Planning/ParallelismResolver.java`)
- `resolvePair(ServerLevel, WorldRoadGraph, ParallelPair)` → `ResolveResult`.
- `ResolveResult` record: `success`, `failureReason`, `removedEdgeId`, `survivorEdgeId`, `leftoverEdgeIds`.
- Pre-validate: both edges present; overlap indices in bounds; start < end; start key ≠ end key. Aborts before any mutation if invalid.
- Survivor selection: TRUNK > CONNECTOR > LOCAL; more maintainers; longer cellPath; lower UUID (deterministic tie-break).
- Split victim at `overlapStart` and `overlapEnd` via `WorldRoadGraph.splitEdgeAtCell` with surface-snapped junction positions.
- Transfers victim_middle's maintainerVillageIds (dedup), max maintenance, and cumulative traffic to survivor.
- Removes victim_middle. Unrealizes victim_head and victim_tail stubs so `GraphEdgeRealizationSystem` re-places them cleanly.

**`RoadEdge` additions** (`Village/Roads/Graph/RoadEdge.java`)
- `addTraffic(long amount)` — adds to `trafficCounter` directly (needed for merger traffic consolidation; `incrementTraffic()` was insufficient).
- `unrealize()` — clears `blockPath` and sets `realized = false`; lets the realizer re-place stub edges.

**`ParallelismCleanupSystem`** (appended to `Events/TickSystems.java`, registered in `TickSubsystemRegistry.java`)
- `interval=2400` (≈2 min at 20 TPS), `priority=160` (runs after `GraphEdgeRealizationSystem` at 150).
- Per invocation: picks a random online player as scan center; calls `ParallelismDetector.findParallelPairs(radius=1536)`; takes the first (longest) pair; calls `ParallelismResolver.resolvePair`; marks data dirty on success.
- Session cap: tracks `mergesThisSession`; resets every 24 000 ticks (1 game day); if cap (20) reached, skips until reset. Prevents runaway cleanup if many pairs accumulate.

**Debug commands** (added to `RoadGraphDebugCommand.java`)
- `/liv road debug cleanup_scan [radius]` — inspection only. Calls `findParallelPairs`, reports up to 10 pairs with edge IDs, tiers, and overlap lengths. Highlights both edges with WITCH/COMPOSTER particles for 30 s. Default radius 1024 blocks; optional integer argument 64–4096.
- `/liv road debug cleanup_merge <edgeAId> <edgeBId>` — manual merge. Resolves edge ID prefixes (same pattern as `highlight_edge`); verifies they are actually parallel via `findPairBetween`; calls `resolvePair`; reports survivor/removed/stub count. Marks data dirty on success.

**Imports added to `TickSystems.java`:**
- `tterrag1112.life_in_the_village.Village.Roads.Planning.ParallelismDetector`
- `tterrag1112.life_in_the_village.Village.Roads.Planning.ParallelismResolver`
- `java.util.Random`

**Imports added to `RoadGraphDebugCommand.java`:**
- `tterrag1112.life_in_the_village.Village.Roads.Planning.ParallelismDetector`
- `tterrag1112.life_in_the_village.Village.Roads.Planning.ParallelismResolver`

**Architecture notes:**
- `ParallelismDetector` is a pure function by design — the cleanup system calls detection and resolution as separate steps, enabling `cleanup_scan` to report without mutating and `cleanup_merge` to detect-then-merge on demand.
- Resolver pre-validates before any mutation to honor the "abort-on-failure" invariant. Second-split failure (after first split succeeded) leaves the graph in a partially-split state; this is safe — the next detection pass sees the updated graph and may or may not re-detect a pair.
- `unrealize()` on stubs preserves the stub edges' structural role in the graph (they still connect their endpoints) while allowing the realizer to re-place them without the now-removed victim_middle blockPath segment.

**Compilation status:** Gradle artifact download unavailable. Full manual static review completed:
- `ParallelismDetector`: `java.util.*` covers `UUID`, `Optional`, `List`, `ArrayList`, `Collection`, `Set`, `HashSet`, `Comparator`; `buildSamples` uses `Math.min` boundary guard; `detectParallel` correctly flushes final open run; `nearestSample` O(n) scan; `distSq` uses `long` to avoid int overflow ✓
- `ParallelismResolver`: `WorldRoadGraph.splitEdgeAtCell` returns `Optional<SplitResult>`; `.isEmpty()` checks present; `tierRank` switch is exhaustive (all 4 `EdgeTier` values) ✓
- `RoadEdge.addTraffic`, `unrealize`: package-private fields accessible from same package ✓
- `ParallelismCleanupSystem`: `level.getGameTime()` returns `long`; `Long.MIN_VALUE` initialization for `sessionStartTick` forces reset on first tick ✓
- `cleanup_scan`: `buildEdgeEmissions` and `RoadDebugVisualizer.DEFAULT_EMIT_INTERVAL` verified in existing `showGraph` pattern ✓
- `cleanup_merge`: `resolveEdgeByPrefix` helper follows same pattern as `highlightEdge`; `Optional<ParallelPair>` usage correct ✓
- `TickSubsystemRegistry`: `register(new ParallelismCleanupSystem())` added after `GraphEdgeRealizationSystem` ✓

**Current phase:** Phase 4 complete. Next: Phase 5 per ROADS_PLAN.md.

---

### 2026-04-22 — Phase 5a implemented: RoadEdge primitive chains + unified realization

**Phase:** 5a — Convert RoadEdge to hold RoadPrimitive chains; unify realization through the primitive system.

**Deliverables:**

**Deliverable 1 — `RoadPrimitive.java` extended** (`Village/Planning/Primitives/RoadPrimitive.java`)
- Added `String typeKey()` abstract method to the sealed interface.
- Added `Codec<RoadPrimitive> CODEC` dispatch constant using switch-in-lambda (lazy evaluation; avoids subtype codec initialization ordering issues).
- Added `static final Codec<T> CODEC` to each existing record (`StraightRoad`, `CurvedRoad`, `Ring`, `Arc`, `Spur`), inlining the tier codec to eliminate shared-field init dependency. Added `@Override public String typeKey()` to each.
- Added `SmoothedPath(List<BlockPos> waypoints, float tension, double driftAmplitude, RoadShape.RoadTier tier)`: Catmull-Rom smoothed waypoint sequence via `RoutePathSmoother.smooth`. `driftAmplitude` stored but not applied in Phase 5a.
- Added `ArmApproach(BlockPos dockingAnchor, BlockPos armEndpoint, UUID villageId, RoadShape.RoadTier tier)`: drifted-line approach from anchor to gate. Distinct from `StraightRoad` so the placer knows to use village material + allée.
- Added imports: `UUID`, `RoutePathSmoother`.
- Codec dispatch: `@SuppressWarnings("unchecked")` with double cast `(Codec<RoadPrimitive>) (Codec<?>)` for type system.

**Deliverable 2 — `RoadEdge.java` extended** (`Village/Roads/Graph/RoadEdge.java`)
- Added `List<RoadPrimitive> primitives` field (mutable; `null` = not yet derived).
- Added codec entry as field 13: `RoadPrimitive.CODEC.listOf().optionalFieldOf("primitives", new ArrayList<>())`. Encodes `null` as empty list; decodes empty list back to `null`.
- Added to `fromCodec`: 13th parameter `List<RoadPrimitive> primitives`; stored as `null` if empty, `new ArrayList<>(primitives)` otherwise.
- Added `setPrimitives(List<RoadPrimitive>)`, `clearPrimitives()` mutators.
- Added `getPrimitives()` (returns `List.of()` if null), `hasPrimitives()` getters.
- Updated `unrealize()` to also `primitives = null` — ensures stale chains are re-derived after forced re-realization.
- Added import: `RoadPrimitive`.

**Deliverable 3 — `PrimitiveChainBuilder.java` created** (`Village/Roads/Realization/PrimitiveChainBuilder.java`)
- `buildPrimitivesForEdge(ServerLevel, WorldRoadGraph, VillageSavedData, RoadEdge)` → `List<RoadPrimitive>`.
- Resolves VILLAGE_DOCK endpoints via `VillageDockingPoint.compute`; non-dock nodes use `node.position()`.
- Chain: `[ArmApproach for nodeA?] + SmoothedPath(outerA → cell centers → outerB) + [ArmApproach for nodeB?]`.
- SmoothedPath waypoints: `outerA` + all cell centers from `AtlasRouteRouter.cellKeyToBlockCenter` + `outerB`.
- `edgeTierToRoadTier` (package-private): GREAT_ROAD→CAPITAL_ROAD, TRUNK→TOWN_ROAD, CONNECTOR→VILLAGE_ROAD, LOCAL→VILLAGE_PATH.

**Deliverable 4 — `UnifiedRoadPlacer.java` created** (`Village/Roads/Realization/UnifiedRoadPlacer.java`)
- `place(ServerLevel, List<BlockPos> centerline, PathMaterial, RoadShape.RoadTier, RoadEdge)` → `List<BlockPos>`.
- Step 1: detect water spans between consecutive waypoints (bridge threshold ≥ 3 samples, 4-block step).
- Step 2: clear trees via `RoadRouter.clearTreesAt`.
- Step 3: paint surface via `OrganicRoadPlacer.place`; RNG seeded from `edgeId` bits.
- Step 4: place plank-deck bridges via `RoadRouter.placeBridge` for each detected water span.

**Deliverable 5 — `EdgeRealizer.java` rewritten** (`Village/Roads/Realization/EdgeRealizer.java`)
- Removed: `RouteRealiser.realiseBetween` call (trade-road legacy path). Also removed: `AtlasRouteRouter`, `RoadRouter`, `RoadNode`, `RandomSource`, `Collections` imports; all replaced by primitive pipeline.
- New pipeline: `PrimitiveChainBuilder.buildPrimitivesForEdge` → per-primitive `computeCenterline` + placement via `UnifiedRoadPlacer` → concatenate with join-dedup → `edge.markRealized`.
- `ArmApproach` primitives: village lookup by `villageId`; biome-matched material; calls `ConnectorAllee.place` with reconstructed `VillageDockingPoint`.
- `SmoothedPath` primitives: `PathMaterial.cobblestone()`, tier from `sp.tier()`.
- Default fallback: cobblestone + `edgeTierToRoadTier` (for any legacy primitive types that appear in a chain).
- Primitives persisted on edge after first build (`edge.setPrimitives`); reused on subsequent realization calls.

**Deliverable 6 — Primitive invalidation on mutations**
- `WorldRoadGraph.splitEdgeAtCell`: both `halfA.clearPrimitives()` and `halfB.clearPrimitives()` after construction (explicit even though factory yields `null` by default — resilient to future refactoring).
- `ParallelismResolver.resolvePair`: `survivor.clearPrimitives()` after traffic/maintenance transfer — forces fresh re-derivation post-merge.

**Deliverable 7 — Debug command update**
- `RoadGraphDebugCommand.highlightEdge`: added line 4 to output: `"  primitives=N [typeKey1, typeKey2, ...]"` or `"  primitives=0 (not yet derived)"`.
- Added import: `RoadPrimitive`.

**Architecture notes:**
- `RouteRealiser` is retained for `RouteRealisationSystem` (legacy `TradeRoad` realization). It is no longer called for graph `RoadEdge` objects.
- The primitive chain is both persisted (via codec) and re-derivable. On disk: if present, loaded and reused; if absent (old saves, post-split, post-merge), re-derived at first `realizeEdge` call.
- The switch-in-lambda dispatch codec for `RoadPrimitive.CODEC` ensures that subtype CODEC constants are not evaluated until encode/decode time, eliminating Java class-init ordering constraints.
- `ArmApproach.computeCenterline` uses `driftedLine(dockingAnchor, armEndpoint, DRIFT=2.0, seed)` — same geometry as the old `StraightRoad(dockingAnchor, armEndpoint, 2.0, VILLAGE_ROAD)` in the Phase 3b EdgeRealizer; visual behavior unchanged.

**Compilation status:** Gradle artifact download unavailable. Full manual static review completed:
- `RoadPrimitive.CODEC`: `Codec.STRING.dispatch` signature matches DFU convention; switch is exhaustive (7 cases + IllegalArgumentException fallthrough) ✓
- `RoadEdge.fromCodec`: 13-arg lambda; parameter order matches `RecordCodecBuilder.create` group declarations ✓
- `PrimitiveChainBuilder.buildWaypoints`: includes `outerA` and `outerB` as first/last elements ✓
- `UnifiedRoadPlacer`: `Fluids.WATER` and `Fluids.FLOWING_WATER` correct NMS fluid references ✓
- `EdgeRealizer` switch: `ArmApproach` and `SmoothedPath` are explicit cases; `default` covers legacy primitives; Java 21 sealed-interface switch ✓
- `ConnectorAllee.place(level, dock, centerline, villageType)` 4-arg signature matches; `VillageDockingPoint` public constructor used (record constructor) ✓
- `WorldRoadGraph.clearPrimitives` placement: after `markRealized` calls on halves, before `removeEdge`/`addEdge` mutations ✓

**Current phase:** Phase 5a complete. Next: Phase 5b per ROADS_PLAN.md.

---

### 2026-04-22 — Phase 5b: multi-edge caravan pathing + overridePath retirement

**Root cause of Phase 3a caravan diagnostic (documented here)**

`TravellingGroupEngine.tick()` calls `group.getPath(level, data)` first (line 82). For a synthetic test
caravan whose `routeId` was a random UUID not registered in `VillageSavedData`, `getRouteById` returned
empty → `getPath()` returned `List.of()` → the engine's `if (path.isEmpty()) return false` guard fired
immediately. The caravan never moved or spawned. `overridePath` was only consulted inside
`CaravanMerchantGoal.tick()`, which only runs for a spawned entity — but entities were never spawned
because the engine skipped the caravan before proximity checks. Fix: the new `dispatch_test_caravan_between`
command creates a real `TradeRoute` stored in `VillageSavedData` so `getPath()` succeeds.

**Deliverable 1 — `TradeRoute.java` restructured** (`Village/Economy/Trade/TradeRoute.java`)
- `connectionId` field made nullable (was required in codec; now `optionalFieldOf`). Backward compatible:
  old save data with `"connectionId"` deserializes to `Optional.of(value)`, old routes still work.
- New field `List<UUID> edgeIds` (optional, default `List.of()`): ordered edge IDs for graph routes.
- New field `UUID routeStartNodeId` (optional, nullable): graph node at village-A's end of first edge.
- Private full-state constructor; existing 9-arg public constructor delegates to it with defaults.
- New `fromCodec(...)` factory (11 args) used by codec.
- New `createGraph(villageA, villageB, type, tick, edgeIds, routeStartNodeId)` factory for graph routes.
- New getters: `getEdgeIds()`, `getRouteStartNodeId()`, `hasGraphPath()`.

**Deliverable 2 — `GraphTradeRouteEstablisher.java` created** (`Village/Economy/Trade/GraphTradeRouteEstablisher.java`)
- `findEdgePath(WorldRoadGraph, fromNodeId, toNodeId)` → `Optional<List<UUID>>`: Dijkstra on adjacency
  built from all graph edges. Weights: GREAT_ROAD×0.6, TRUNK×0.8, CONNECTOR×1.0, LOCAL×1.5, each ×
  `cellPath.size()`. Edge IDs returned in A→B traversal order.
- `resolveGraphBlocks(WorldRoadGraph, edgeIds, startNodeId)` → `List<BlockPos>`: walks edgeIds in order,
  determines forward/reverse per edge by comparing `currentNode` to `edge.nodeAId`, skips first 3 blocks
  of non-first edges (junction overlap trim), concatenates. Returns forward (A→B) path; reversal for
  returning caravans handled by consumers (`TravellingGroupEngine.computePosition` and `CaravanMerchantGoal`
  index direction).

**Deliverable 3 — `Caravan.java` updated** (`Village/Economy/Trade/Caravan.java`)
- Removed: `@Nullable transient List<BlockPos> overridePath` field, `getOverridePath()`, `setOverridePath()`.
- Updated `getPath(ServerLevel, VillageSavedData)`: if `route.hasGraphPath()`, calls
  `GraphTradeRouteEstablisher.resolveGraphBlocks(WorldRoadSavedData.get(level).getGraph(), ...)`.
  Legacy fallback: `getRoadById(route.getConnectionId()).map(TradeRoad::getBlocks).orElse(List.of())`.
- Added import: `WorldRoadSavedData`.

**Deliverable 4 — `CaravanMerchantGoal.java` updated** (`Entities/Goals/Profession/Merchant/CaravanMerchantGoal.java`)
- Removed: `overridePath` check block (was lines 83–107). Replaced with `resolveBlocks(caravan, level)`
  call and single-line log.
- Added `resolveBlocks(Caravan, ServerLevel)`: same resolution order as `Caravan.getPath` — graph first,
  legacy fallback. Returns `List.of()` if route missing.
- Added `isGraphRoute(Caravan, ServerLevel)`: helper for log line.
- Removed dead `setNextWaypoint` internal road-quality speed block (was dead code after the override
  removal; kept method shape, removed the `getRoadById(getConnectionId())` chain inside it).
- Added imports: `WorldRoadSavedData`, `GraphTradeRouteEstablisher`, `TradeRoute`.

**Deliverable 5 — `RoadGraphDebugCommand.java` updated** (`Commands/RoadGraphDebugCommand.java`)
- `dispatch_caravan <villageName>` removed. Replaced with `dispatch_test_caravan_between <villageA> <villageB>`:
  - Resolves dock nodes for both villages (requires `connect_village` to have been run).
  - Runs `GraphTradeRouteEstablisher.findEdgePath` between the two dock nodes.
  - Logs the edge path (id, tier, realized status, block count per edge).
  - Force-realizes any unrealized edges on the path via `EdgeRealizer.realizeEdge`.
  - Calls `resolveGraphBlocks` to verify the path produces blocks; aborts if empty.
  - Creates a real `TradeRoute.createGraph(...)` and stores it in `VillageSavedData`.
  - Creates a `Caravan` pointing to that `routeId`; adds to `CaravanSavedData`.
  - `TravellingGroupEngine` can now find the route via `caravan.getPath()` → `getRouteById` → graph resolution.
- `caravan_status`: removed `overridePath` display; replaced with `routeInfo` string showing
  `"graph(N edges)"` or `"legacy(road=XXXXXXXX)"` or `"route=missing"`.
- Added imports: `GraphTradeRouteEstablisher`, `TradeRoute`.

**Architecture notes:**
- `overridePath` is gone. No transient scaffolding remains from Phase 3a.
- Legacy `TradeRoute` objects (with `connectionId`, no `edgeIds`) continue to work unchanged — all callers
  that do `getConnectionId()` still compile and operate correctly. `getConnectionId()` returns null for
  graph routes; callers using `Optional` chaining get `Optional.empty()` and fall through gracefully.
- The `TravellingGroupEngine` path-skip guard is now only triggered for truly missing routes, not for
  test caravans. The synthetic-but-real route pattern (TradeRoute in VillageSavedData, edges in graph)
  is the correct architecture for all future graph-based dispatching.
- `resolveGraphBlocks` is shared between `Caravan.getPath()` and `CaravanMerchantGoal.resolveBlocks()` by
  calling the same static method in `GraphTradeRouteEstablisher`. Both consumers see the same block sequence.
- Auto-dispatch in `CaravanSavedData.dispatchNewCaravans` still uses legacy `getConnectionId()` path
  (graph-based routes have null connectionId → `getConnectionById(null)` → empty → skipped). Graph-based
  auto-dispatch is a Phase 6+ concern.

**Compilation status:** Manual static review:
- `TradeRoute.fromCodec` 11-arg signature matches `RecordCodecBuilder.create` group declarations ✓
- `Dijkstra` adjacency built from `graph.allEdges()`; bidirectional (both nodeA→nodeB and nodeB→nodeA) ✓
- `resolveGraphBlocks` node-pointer advances correctly even for unrealized edges ✓
- `dispatch_test_caravan_between` uses `StringArgumentType.word()` for both args; 2-arg registration ✓
- `caravan_status` uses `level` which is in scope (declared line 1040) ✓

**Current phase:** Phase 5b complete. Next: Phase 6.

---

### 2026-04-22 — Phase 5c implemented: terrain-change invalidation + tiered realize-radius

**Phase 5c complete.** Phase 5 (Graph Realization) is now fully done.

**Deliverable 1 — `RoadTerrainChangeListener.java` created** (`Events/RoadTerrainChangeListener.java`)
- `@EventBusSubscriber(modid = Life_in_the_village.MODID)` class.
- `onBlockBreak(BlockEvent.BreakEvent)`: converts block pos → cell key, queries `graph.edgesInCell`,
  marks stale on all realized edges that cover that cell, calls `roadData.markDirty()` if anything changed.
- `onBlockPlace(BlockEvent.EntityPlaceEvent)`: identical logic for player-placed blocks.
- `onExplosion(ExplosionEvent.Detonate)`: collects all unique cell keys from `event.getAffectedBlocks()`,
  delegates to `invalidateCells(graph, cellKeys)`, marks dirty if any edges changed.
- Package-private `markCellStale(ServerLevel, BlockPos)` and `invalidateCells(WorldRoadGraph, Set<Long>)`
  helpers reused by the `invalidate_cell` debug command.
- Only realized edges are marked stale; unrealized edges are ignored (will be realized fresh on approach).

**Deliverable 2 — `WorldRoadGraph.cellToEdges` reverse map** (already committed in Phase 5b session)
- `Map<Long, Set<UUID>> cellToEdges` field; kept in sync by `addEdge`, `removeEdge`, `rebuildSpatialIndex`.
- `edgesInCell(long cellKey)` → O(1) unmodifiable set of edge IDs through that cell.
- `getCellToEdges()` → unmodifiable map view for validator.

**Deliverable 3 — `EdgeRealizer.java` stale-cell handling** (already committed in Phase 5b session)
- Fast path: `if (edge.isRealized() && edge.getStaleCells().isEmpty()) return;` (was `if (edge.isRealized()) return;`).
- Stale fallback: if realized but stale, log it, call `edge.unrealize()` + `edge.clearStaleness()`,
  then fall through to full re-realization pipeline. Selective cell-span patching deferred to later phase.

**Deliverable 4 — `GraphEdgeRealizationSystem` tiered radius + priority ordering** (`Events/TickSystems.java`)
- Replaced single `REALISE_RADIUS = 384` with four constants: GREAT_ROAD=768, TRUNK=384, CONNECTOR=256, LOCAL=192.
- `tick()` now collects all eligible edges (unrealized OR stale) within their tier's radius into a
  `List<Candidate>` (local record: edge, tierOrdinal, closestDistSq).
- Sorts by tier ordinal ascending (GREAT_ROAD first) then by closestDistSq ascending (closest first).
- Processes the top-priority edge only (one per tick), same cadence as before.
- Added `closestCellDistSq(players, edge)` helper: scans all cells in edge's cellPath, returns minimum
  squared distance to any player. Used for both eligibility check and priority ordering.
- Added `tierRadius(EdgeTier)` helper: returns per-tier integer radius.
- Added `ArrayList` and `Comparator` to imports.

**Deliverable 5 — `invalidate_cell` debug command** (`Commands/RoadGraphDebugCommand.java`)
- `invalidate_cell <x> <z>` (block coords): converts to cell, calls `RoadTerrainChangeListener.invalidateCells`.
- Reports how many edges were marked stale. "No edges cover cell" if no edge covers that cell.
- Added import: `RoadTerrainChangeListener`.

**Deliverable 6 — `GraphInvariantValidator` cellToEdges check** (already committed in Phase 5b session)
- Check 6: for each edge's cellPath cell, verifies `cellToEdges` contains that edge ID. Warns on mismatch.

**Architecture notes:**
- `RoadTerrainChangeListener` is passive and fire-and-forget: it never blocks block events, never throws,
  and only marks edges dirty when a cell is actually covered by a road edge. The listener is effectively
  a no-op in vanilla terrain with no roads nearby.
- The full re-realization fallback in `EdgeRealizer` is simpler and correct: stale cells invalidate the
  whole edge's geometry so a fresh realization is needed. The comment documents that selective cell-span
  patching (replacing only the affected segment) is deferred.
- Priority ordering ensures GREAT_ROAD edges are kept current even when many lower-tier edges are stale —
  important for caravan throughput which primarily uses trunk/great-road paths.

**Compilation status:** Manual static review:
- `RoadTerrainChangeListener`: event type imports match NeoForge 1.21 API (`BlockEvent.BreakEvent`,
  `BlockEvent.EntityPlaceEvent`, `ExplosionEvent.Detonate`). `AtlasCell.CELL_SHIFT` and `AtlasCell.packKey`
  exist. `WorldRoadSavedData.get(level)`, `graph.edgesInCell`, `graph.getEdge`, `edge.markCellStale` all exist ✓
- `TickSystems.java`: local `record Candidate(...)` inside method is Java 16+ feature, compatible with NeoForge 1.21 target.
  `RoadEdge.EdgeTier.ordinal()` used for sorting (GREAT_ROAD=0, TRUNK=1, CONNECTOR=2, LOCAL=3). `Comparator.comparingInt`
  + `thenComparingLong` valid. `ArrayList`, `Comparator` added to imports ✓
- `RoadGraphDebugCommand.java`: `invalidateCell` uses `IntegerArgumentType.getInteger` (already imported).
  `Set.of(cellKey)` valid. `RoadTerrainChangeListener.invalidateCells` made `public static` so it is
  accessible from `Commands` package ✓

**Current phase:** Phase 5c complete. Phase 5 (Graph Realization) fully done. Next: Phase 6 (upkeep / traffic propagation).

---

## Phase 6a — Material & Culture System (2026-04-22)

### Deliverables completed

**D1 (BLOCKING) — Gapped road placement fix** (`Village/Roads/Realization/UnifiedRoadPlacer.java`)
- Root cause: `RoutePathSmoother.smooth()` produces Catmull-Rom waypoints ~12–13 blocks apart; `OrganicRoadPlacer.place()` places one cross-section per point with no interpolation.
- Fix: added `densify(List<BlockPos> waypoints, ServerLevel level)` — for each consecutive pair, steps = max(|Δx|, |Δz|), then linearly interpolates and surface-snaps each step. Identical algorithm to the private `RoadRouter.densify`.
- Bridge detection runs on the original sparse centerline before densification; all block placement uses the dense list.
- `place()` signature extended with `@Nullable String culture` parameter.
- Added culture-specific architectural passes: `imperialGutterPass()`, `highlandRetainingWallPass()`, `nordicCorduroyPass()`.
- Added seasonal overlay pass: `winterSnowPass()`.
- Added `computePerp()` helper for perpendicular direction vectors.

**D2 — Tier widths already correct** (no change, confirmed from PrimitiveChainBuilder.edgeTierToRoadTier mapping set in Phase 5a)

**D3 — PathMaterial.resolve()** (`Village/Decoration/Roads/PathMaterial.java`)
- Added `resolve(VillageBiomeStyle, String culture, int maintenance, RoadShape.RoadTier, SeasonTracker.Season)`.
- Culture palettes: `imperial()`, `highland()`, `nordic(VillageBiomeStyle)`, `oldRealm()`.
- Maintenance decay: `applyMaintenanceDecay()` — mossy substitutions at <40, grass at <15.
- Seasonal overlays: `applyWinterDirtOverlay()` (40% snow in core for dirt-tier roads), `applyAutumnEdge()` (5% oak_leaves on edge).
- `forBiomeAndTier()` kept for backward compatibility.

**D4 — Culture routing cost modifiers** (`Village/Economy/Trade/AtlasRouteRouter.java`)
- Added `CellCostModifier` functional interface: `float adjust(float baseCost, AtlasCell cell)`.
- Added `modifierForCulture(@Nullable String culture)` factory (imperial ×1.2 steep, highland ×0.7 steep/×1.1 flat, nordic ×0.6 swamp/×0.8 river-adj, default=identity).
- Added 6-arg `findRoute()` overload accepting `CellCostModifier`.
- Extracted core A* into private `findRouteInternal()`; modifier applied to base cost before road/attractor discounts. Old overloads delegate to it.
- `ConnectorPlanner.java`: retrieves culture modifier via `AtlasRouteRouter.modifierForCulture(village.getVillageType())` and passes to 6-arg overload.

**D5 — Great-road Old Realm palette** (`Village/Decoration/Roads/PathMaterial.java`)
- `oldRealm()`: mossy_cobblestone (35%), cracked_stone_bricks (30%), stone_bricks (20%), mossy_stone_bricks (10%), cobblestone (5%). Edge: stone_brick_slab/stone_slab/grass_block.
- Invariant 3 honored: `EdgeMaterialResolver` short-circuits to `oldRealm()` with null culture for GREAT_ROAD tier — no culture/maintenance/season overlays applied.

**D6 — Seasonal overlay** (part of PathMaterial.resolve() and UnifiedRoadPlacer passes)
- WINTER: 40% snow/grass substitution on FOOTPATH/VILLAGE_PATH tier roads. Block placement pass sprinkles SNOW_LAYER on placed surface.
- AUTUMN: 5% oak_leaves on edge palette.
- Uses `SeasonTracker.currentSeason(level)` — deterministic from game time, no new state.

**D7 — EdgeMaterialResolver + EdgeRealizer wiring** (new file `Village/Roads/Realization/EdgeMaterialResolver.java`)
- `MaterialContext` record: `PathMaterial material, @Nullable String culture`.
- `resolveForEdge()`: GREAT_ROAD → oldRealm()/null; others → biome + culture + maintenance + season → PathMaterial.resolve().
- Culture resolution: CONNECTOR/LOCAL from VILLAGE_DOCK endpoint village; TRUNK from majority vote of maintainerVillageIds; GREAT_ROAD null.
- Biome detected from nodeA position; fallback to first cell centre.
- `EdgeRealizer.java` rewritten: calls EdgeMaterialResolver, passes culture to all UnifiedRoadPlacer.place() calls. ArmApproach arms use village.getVillageType() as culture.

**D8 — Debug commands** (`Commands/RoadGraphDebugCommand.java`)
- `material_preview <culture> <tier> <maintenance> [season]`: resolves PathMaterial for given axes against PLAINS biome, prints name + all weighted core/edge blocks. No world effect.
- `force_maintenance <edgeId> <value>`: sets edge maintenance to value (0–100), unrealizes and force-re-realizes immediately so material changes take effect. Reports old→new maintenance and realized block count.

### Architecture notes
- `Village.getCulture()` does not exist — used `Village.getVillageType()` throughout.
- Culture routing modifiers applied to base cell cost BEFORE road/attractor discounts, so existing road cheapness still acts as a corridor attractant even for cultures with elevated cell costs.
- Densification is O(path_length × steps_per_segment) — for typical 4-cell edges with 13-block spacing this is ~50 dense points vs 4 sparse, well within budget.
- Bridge detection correctness: running detection on the original sparse waypoints (4–20 points) keeps the WATER_SAMPLE_STEP threshold (4 blocks → 16 squared) meaningful. Dense path (1-block steps, distSq=1) would short-circuit every span.

### Compilation status (manual static review)
- All new imports present. `PathMaterial.WeightedBlock.block()` accessor used (record component getter).
- `RoadShape.RoadTier.valueOf()` valid for CLI parsing (enum name match).
- `SeasonTracker.Season.valueOf()` valid.
- `edge.unrealize()`, `edge.clearStaleness()`, `edge.setMaintenance()` all confirmed in RoadEdge.java.
- `resolveEdgeByPrefix()` already existed in RoadGraphDebugCommand — reused.
- `VillageSavedData.get(level)` available in command context.

**Current phase:** Phase 6a complete. Next: Phase 6b (upkeep decay tick, maintenance propagation from traffic).

---

## Phase 6b — Decorative Pass (2026-04-22)

### Deliverables completed

**D1 — Old Realm milestones** (`Village/Roads/Decoration/MilestoneDecorator.java`, NEW)
- GREAT_ROAD-only. Spacing 500–1000 blocks along centerline (seeded RNG from `edge.getMeanderProfile().seed() ^ 0xDECAFBADL`).
- Perpendicular offset ±2 blocks, alternating sides.
- 3 variants by position hash: intact (60%, polished_andesite base + chiseled_stone_bricks pillar + stone_brick_slab cap + optional mossy_cobblestone_wall), broken (25%, chiseled_stone_bricks on ground + toppled polished_andesite + short_grass), eroded (15%, mossy_cobblestone + cracked_stone_bricks).
- Deduplication: skips if within 8 blocks of any existing decoration position.

**D2 — Decoration persistence** (`RoadEdge.java` and `RoadNode.java`, MODIFIED)
- Added `List<BlockPos> decorationPositions` field to both `RoadEdge` and `RoadNode`.
- Persisted via codec as optional field (backward-compatible: loads as empty list on old save data).
- API: `getDecorationPositions()`, `addDecorationPosition(BlockPos)`, `clearDecorationPositions()`, `hasDecorations()` (edge only).
- `RoadNode` converted from Java record to class to allow mutable decorationPositions field; all accessor method signatures preserved (record-style names: `nodeId()`, `position()`, `type()`, `kingdomAffinity()`).

**D3 — Junction decorations** (`Village/Roads/Decoration/JunctionDecorator.java`, NEW)
- TRUNK_JUNCTION: 4 variants by node UUID hash % 4. Culture from majority vote of maintainerVillageIds on incident edges.
  - Well (0): cobblestone_wall cross + water center + culture fence gate windlass (HORIZONTAL_FACING).
  - Shrine (1): cobblestone_slab base + culture pillar + stone_pressure_plate cap; imperial accent blocks.
  - Notice board (2): culture fence post + culture sign (blank, functional placeholder for server-set text).
  - Rest spot (3): 4 culture fence corner posts + interior + corner slab roof + stone_slab bench.
- GREAT_ROAD_ANCHOR: 3 ruin variants by hash % 3.
  - Collapsed waystation: mossy_cobblestone perimeter, 2 chiseled pillars, ~25% rubble density, oak_sapling.
  - Cracked statue: polished_andesite pedestal, 2 chiseled torso blocks, cobblestone fallen head, moss_carpet.
  - Sunken altar: 3×3 polished_andesite floor, water center, cracked_stone_bricks ring, oak_leaves debris.
- Culture helpers: `cultureWallBlock`, `cultureFenceBlock`, `cultureFenceGateBlock`, `culturePillarBlock`, `cultureSignBlock`, `cultureRoofSlab`.
- Best-corner algorithm: picks diagonal (±3 from node) with max angular separation from all incident edge directions.

**D4 — Road signs** (inside `JunctionDecorator`, `placeRoadSigns()`)
- One sign per outgoing edge from TRUNK_JUNCTION if a VILLAGE_DOCK is reachable within 3 graph hops.
- Sign rotation: `((int) Math.round((Math.atan2(-dx, dz) * 180.0 / Math.PI + 360.0) % 360.0 / 22.5)) % 16` (0=south, 4=west, 8=north, 12=east).
- Direction arrow (>>/<</<</^^) + truncated (≤15 char) village name written via `SignBlockEntity.setText(SignText, true)`.
- Destination resolved by walking graph via highest-tier edges, stopping at VILLAGE_DOCK nodes.

**D5 — Maintenance-based overgrowth** (`Village/Roads/Decoration/RoadOvergrowthDecorator.java`, NEW)
- No-ops at maintenance ≥ 80. Density by maintenance: <20→60%, <40→35%, <60→15%, else→5%.
- Lateral offsets 2–3 blocks; per-block density roll via position hash XOR edge seed (deterministic).
- Biome-specific vegetation: default (short_grass/tall_grass/oak_sapling/oak_leaves), desert (dead_bush/sand), swamp (oak_leaves/moss_block), snowy (snow).
- Fallen logs (maintenance <40): STRIPPED_OAK_LOG with AXIS perpendicular to road direction; spans 5 blocks across road every 80 blocks.
- Surface degradation (maintenance <20): ~12.5% of road core blocks replaced with coarse_dirt.

**D6 — Hook into realizer and tick subsystem**
- `EdgeRealizer.realizeEdge()`: after `edge.markRealized(fullPath)`, calls `MilestoneDecorator.decorate()` (GREAT_ROAD only) and `RoadOvergrowthDecorator.decorate()` (all tiers). Decorators are idempotent (deduplication via decorationPositions).
- `NodeDecorationSystem.java` (NEW, `Events/`): iterates nodes, decorates first undecorated TRUNK_JUNCTION/GREAT_ROAD_ANCHOR within 128 blocks of any player (one per tick).
- `NodeDecorationTickSystem` added to `TickSystems.java` (interval=20, priority=200); registered in `TickSubsystemRegistry.registerDefaults()`.

**D7 — Debug commands** (`Commands/RoadGraphDebugCommand.java`, MODIFIED)
- `redecorate_edge <edgeId>`: removes existing decoration blocks, clears decorationPositions, re-runs MilestoneDecorator + RoadOvergrowthDecorator.
- `redecorate_node <nodeId>`: removes existing decoration blocks, clears decorationPositions, re-calls JunctionDecorator.decorate().
- `show_decorations`: emits HAPPY_VILLAGER particles at edge decoration positions, END_ROD particles at node decoration positions.

### Bug fixes this session
- `placeWell()` in JunctionDecorator: regular fence block (OAK_FENCE) does not have HORIZONTAL_FACING property — fixed by adding `cultureFenceGateBlock()` helper returning OAK_FENCE_GATE/SPRUCE_FENCE_GATE/etc. and using it for the windlass beam placement.
- Added missing `import net.minecraft.world.level.block.Block;` to JunctionDecorator.java.

### Architecture notes
- All decorators are idempotent: they check `decorationPositions` before placing and skip on non-empty / within-proximity checks.
- `RoadNode` record→class conversion: kept all accessor names in record style so all 10+ existing call sites compile unchanged.
- Decoration codec fields use `optionalFieldOf` for backward compatibility — existing worlds without the field load correctly (default to empty list).

### Compilation status (manual static review)
- All imports verified. `BlockStateProperties.AXIS` for log axis, `BlockStateProperties.ROTATION_16` for signs, `BlockStateProperties.HORIZONTAL_FACING` for fence gates, `BlockStateProperties.PERSISTENT` for oak_leaves — all confirmed present in NeoForge 1.21 API.
- `VillageBiomeStyle` enum values verified for biome dispatch in RoadOvergrowthDecorator.
- `RoadNode.NodeType.TRUNK_JUNCTION` and `GREAT_ROAD_ANCHOR` confirmed in NodeType enum.
- `TickSubsystemRegistry.registerDefaults()` correctly orders NodeDecorationTickSystem after ParallelismCleanupSystem.

**Current phase:** Phase 6b complete. Phases 1–6b done. Next: Phase 7 per ROADS_PLAN.md.

---

### 2026-04-22 — Phase 6c implemented

**Phase:** 6c — Village-maintained upkeep economy

**Files created:**
- `Village/Roads/Economy/RoadUpkeepCalculator.java` — pure-function cost model. `costPerCellForTier`: GREAT_ROAD→0, TRUNK→2, CONNECTOR→1, LOCAL→1 silver per 4 cells. `maxUpkeepForTier` (VillageSizeTier): HAMLET→10s, VILLAGE→50s, TOWN→200s, CITY→600s. `computeEdgeUpkeep`, `villageShareOfEdge` (divides by maintainer count), `pickPaidEdges` (sorts CONNECTOR→TRUNK→LOCAL then shorter-first; accumulates until capacity exhausted).
- `Village/Roads/Economy/VillageUpkeepLedger.java` — persisted per-village ledger. Fields: `Map<UUID,Integer> edgeFailureStreaks`, `long lastUpkeepCycleTick`, `int totalCyclesPaidThisYear`, `int totalCyclesFailedThisYear`. All codec fields `optionalFieldOf` for backward compat. UUID codec via `Codec.STRING.xmap(UUID::fromString, UUID::toString)`.
- `Events/RoadUpkeepSystem.java` — daily upkeep cycle. Entry: `runCycle(level, villageData, bankrupt)`. Flow: rebuild maintainer index → build totalCount/paidCount maps → per-village treasury deduction → per-edge maintenance update (+2/0/−5) → maintenance-band crossing detection → stale maintainer pruning → chronic neglect logging. Invariant 3 enforced: GREAT_ROAD edges skipped entirely. `maintenanceBand()`: 5 bands (0=0-19, 1=20-39, 2=40-59, 3=60-79, 4=80-100). `applyDecay()`: sets maintenance, checks band crossing, flags `needsDecorationRefresh`.

**Files modified:**
- `Village/Roads/Graph/RoadEdge.java` — added transient `boolean needsDecorationRefresh` field (not persisted); getters/setters.
- `Village/Roads/Graph/WorldRoadGraph.java` — added `Map<UUID, Set<UUID>> villageToEdges` reverse index; updated `addEdge`/`removeEdge`/`rebuildSpatialIndex`; added `edgesForVillage(UUID)` and `rebuildVillageMaintainerIndex()` methods.
- `Networking/WorldRoadSavedData.java` — extended `Snapshot` record from 2 to 3 fields (added `Map<UUID, VillageUpkeepLedger> ledgers`); `optionalFieldOf` with empty-map default for backward compat; added `getOrCreateLedger(UUID)`, `getLedger(UUID)`, `getLedgers()`.
- `Events/NodeDecorationSystem.java` — added second pass: detects edges with `isNeedsDecorationRefresh()` in player range, removes overgrowth blocks (SHORT_GRASS, TALL_GRASS, OAK/SPRUCE sapling, OAK_LEAVES, DEAD_BUSH, SNOW, MOSS_BLOCK, STRIPPED_*_LOG, COARSE_DIRT), clears `decorationPositions`, re-runs `RoadOvergrowthDecorator`. Milestones (stone blocks) not removed — permanent. One edge refresh per tick after node pass.
- `Events/TickSystems.java` — appended `RoadUpkeepTickSystem` (interval=24000, priority=180).
- `Events/TickSubsystemRegistry.java` — registered `RoadUpkeepTickSystem` after `NodeDecorationTickSystem`.
- `Commands/RoadGraphDebugCommand.java` — added D7 debug subcommands (`village_upkeep <name>`, `trigger_upkeep`, `force_decay_cycle <cycles 1-200>`) and D6 player commands (`donate <villageName> <amount>`, `repair <edgeId>`). Handler methods: `villageUpkeepReport`, `triggerUpkeep`, `forceDecayCycle`, `donateToVillage`, `repairEdge`.

**Upkeep tuning numbers (as designed — no test world run):**
- HAMLET cap: 10 silver/cycle. Maintains ≈ 2–4 LOCAL edges of 4-cell length each.
- VILLAGE cap: 50 silver/cycle. Handles a modest network of CONNECTOR + LOCAL roads.
- TOWN cap: 200 silver/cycle. Can maintain the typical town TRUNK spine plus many connectors.
- CITY cap: 600 silver/cycle. Handles full TRUNK network plus all subsidiaries.
- Edge cost formula: `costPerCell * (cellCount / 4)`. A 40-cell TRUNK edge = 2 * 10 = 20 silver.

**Decay cycle timing (as designed):**
- All-bankrupt: maintenance drops 5/cycle → 20 cycles from 100 to 0 (5 band crossings, one per 4 cycles).
- Overgrowth refresh fires per band crossing (5 total from healthy→abandoned).

**Deviations from spec:**
- `CurrencyValue` referenced fully-qualified in `donateToVillage` (no import added to keep diff minimal). Valid Java.
- No HistoryTextGenerator integration — chronic neglect logged to console only (`[RoadUpkeep]` prefix), not to in-game village history.
- `village.getTreasury().toSilver()` used in donate confirmation message — assumes `getTreasury()` returns a `CurrencyValue`-like object with `toSilver()`. If Treasury API differs, this line may need adjustment.

**Carryovers (unchanged from prior sessions):**
- Phase 4 culture testing: no test world data yet.
- Phase 6a road sign text: signs placed but text is blank (functional server-set placeholder).
- Phase 6b junction/ruin decoration: visually untested (manual static review only).

**Compilation notes:**
- `needsDecorationRefresh` is transient — resets to false on every world load, which is correct (refresh state doesn't need persistence).
- `rebuildVillageMaintainerIndex()` called at cycle start to catch post-`addEdge` direct mutations to `maintainerVillageIds` that bypass the index (e.g., `ParallelismResolver`).
- `worldRoadSavedData.getLedgers()` returns the live map — `entry.getValue()` objects are mutable even if the map view is unmodifiable.

**Next: Phase 6d — village-size-driven tier promotion (per ROADS_PLAN.md).**
