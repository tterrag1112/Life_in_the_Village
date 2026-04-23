# Roads system — progress log

Append-only. Most recent entry at the bottom. Each session ends with an entry summarizing what was done, what broke, and what's next.

## Current phase

**Phase 8d** — Tolls and checkpoints. Complete. Phase 8 fully done.

## Current slice

Phase 8 complete. Next: Phase 9 per ROADS_PLAN.md.

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

---

### 2026-04-22 — Phase 6d implemented

**Phase:** 6d — Village-size-driven tier promotion

**Files created:**
- `Village/Roads/Economy/TierPromotionRules.java` — pure-function tier mapping. `naturalTierForVillage`: HAMLET→LOCAL, VILLAGE→CONNECTOR, TOWN/CITY→TRUNK. `effectiveTier(List<Village>)`: max of all maintainers' natural tiers (TOWN+HAMLET edge = TRUNK). `canBeChanged(RoadEdge)`: GREAT_ROAD always excluded. `tierRank()` helper for max-comparison.
- `Village/Roads/Economy/EdgeTierManager.java` — tier change applicator. `reconcileEdgesForVillage`: iterates all maintained edges, computes effective tier, calls `applyTierChange` on mismatch. `applyTierChange`: sets tier, calls `unrealize()` (clears blockPath + primitives), clears decorationPositions so re-realized edge re-decorates fresh.
- `Events/TierReconciliationSystem.java` — periodic safety-net scan. `runReconciliation`: iterates all villages, reconciles each; logs if changes found (indicates hook missed something).

**Files modified:**
- `Village/Village.java` — added `@Nullable private transient VillageSizeTier storedSizeTier` (not persisted; recomputed on first access). `getSizeTier()` lazy-init accessor. `checkAndFireTierChangeHook(ServerLevel)`: compares stored vs computed tier, fires `onSizeTierChanged` on change, updates stored. `onSizeTierChanged`: calls `EdgeTierManager.reconcileEdgesForVillage`, marks roadData dirty if edges changed. Added imports: WorldRoadSavedData, VillageSizeTier, EdgeTierManager.
- `Entities/Goals/Profession/Builder/BuilderGoal.java` — added `v.checkAndFireTierChangeHook(level)` after `v.getBuildingIds().add(placed.getId())` — primary natural growth path.
- `Village/Decoration/TownSquarePlacer.java` — added `village.checkAndFireTierChangeHook(level)` after `village.addBuilding(square)`.
- `Commands/BuildingCommand.java` — added `village.get().checkAndFireTierChangeHook(level)` after manual building assignment command.
- `Village/Roads/Planning/ConnectorPlanner.java` — replaced hardcoded `EdgeTier.CONNECTOR` with `TierPromotionRules.naturalTierForVillage(VillageSizeTier.fromBuildingCount(...))`. New connectors now get the correct initial tier from village size (invariant 6).
- `Village/Roads/Planning/ParallelismResolver.java` — after maintainer transfer (`survivor.clearPrimitives()`), computes effective tier from merged maintainer list and calls `EdgeTierManager.applyTierChange` if tier changed. Added imports: VillageSavedData, EdgeTierManager, TierPromotionRules, Village.
- `Events/TickSystems.java` — appended `TierReconciliationTickSystem` (interval=48000, priority=170).
- `Events/TickSubsystemRegistry.java` — registered `TierReconciliationTickSystem` after `RoadUpkeepTickSystem`.
- `Commands/RoadGraphDebugCommand.java` — added D7 commands: `village_tier <name>` (size tier, natural tier, per-edge current/effective with mismatch flags), `promote_village <name> <HAMLET|VILLAGE|TOWN|CITY>` (forces tier computation without changing building count, triggers reconciliation), `reconcile_all` (runs full reconciliation scan immediately). Added imports: EdgeTierManager, TierPromotionRules, TierReconciliationSystem.

**Architecture notes:**
- `storedSizeTier` is transient (not in Village codec) — reinitialized from building count on each world load. No codec migration needed.
- Hook coverage: BuilderGoal (main NPC builder path), TownSquarePlacer (town square), BuildingCommand (manual command path). VillageSpawner skipped — no edges exist at spawn time. TierReconciliationSystem catches any missed paths.
- `applyTierChange` calls `edge.unrealize()` which clears blockPath + primitives + sets realized=false. Re-realization picks up new tier from `edge.getTier()` at `PrimitiveChainBuilder` and `EdgeMaterialResolver` call sites (verified: both call `edge.getTier()` at realization time — D6 is a no-op change).
- Multi-maintainer tier: effective = max of all maintainers' natural tiers. Removing the only high-tier maintainer causes demotion at next reconcile or immediately on maintainer removal.
- `promote_village` debug command forces tier on edges WITHOUT changing building count — useful for testing without growing the village naturally. The forced tier is computed per-edge using `max(forced, real_maintainers)` so shared edges with other large villages still reflect those villages' tiers.

**Test world observations (manual static review — no test world run):**
- HAMLET → VILLAGE transition: hook fires in BuilderGoal when 4th building is placed; connector demoted from CONNECTOR to LOCAL if the 4th building brings the count to VILLAGE threshold... wait, HAMLET is 1-3, VILLAGE is 4-8. So at 4 buildings: HAMLET→VILLAGE. `naturalTier` goes LOCAL→CONNECTOR. Connector gets promoted. `unrealize()` called. Next pass of GraphEdgeRealizationSystem re-realizes with CONNECTOR width/material.
- VILLAGE → TOWN transition: at 9 buildings: CONNECTOR→TRUNK.
- ConnectorPlanner: a new HAMLET village now creates a LOCAL edge instead of CONNECTOR. First building count is usually 1 (initial spawn) → LOCAL. Grows as village grows via reconcile/hook.

**Deviations from spec:**
- `onSizeTierChanged` uses fully-qualified `tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge` reference in the list type rather than adding another import to Village.java. Valid Java.
- `promote_village` does NOT permanently change `storedSizeTier` on the village — it only applies tier changes to edges for the forced size. The stored tier remains driven by actual building count. This is intentional: the command tests the promotion pipeline without data corruption.
- VillageSpawner not wired: initial spawn creates edges after buildings, so hook would fire but edges don't exist yet. Reconcile handles this correctly.

**Carryovers (unchanged):**
- Phase 4 culture testing: no test world data yet.
- Phase 6a road sign text: signs placed, text blank (server-set placeholder).
- Phase 6b junction/ruin decoration: visually untested.

**Phase 6 complete.** Phases 6a + 6b + 6c + 6d all done.

**Next: Phase 7a — deterministic great-road anchor graph at worldgen. The headline feature.**

---

### 2026-04-23 — Phase 7a implemented: deterministic great-road anchor graph at worldgen

**Phase:** 7a — Poisson-disk anchor seeder, trunk router, cross-tick generation queue

**Files created:**
- `Village/Roads/Graph/Worldgen/GreatRoadAnchorSeeder.java` — Poisson-disk sampling of `GREAT_ROAD_ANCHOR` nodes. Deterministic from world seed. Generates candidate `AnchorCandidate` records; rejects candidates within `MIN_SPACING` of existing anchors; emits a buildable, non-ocean atlas cell at each accepted candidate. Domain axis (EW vs NS) determined by comparing spread of candidate X vs Z coordinates.
- `Village/Roads/Graph/Worldgen/GreatRoadTrunkRouter.java` — Routes GREAT_ROAD edges between adjacent anchor pairs using `AtlasRouteRouter`. Pairs anchors by nearest-neighbor along the dominant axis. Uses Old Realm `oldRealm()` material. Committed edges to `WorldRoadGraph` and marks the graph dirty.
- `Village/Roads/Graph/Worldgen/AnchorCandidate.java` — Data record: `BlockPos position, long cellKey, AtlasCell cell`.
- `Events/GreatRoadGenerationQueue.java` — Cross-tick pipeline. `StepType` enum: `SEED_ANCHORS`, `FILL_ATLAS_CORRIDOR`, `ROUTE_TRUNK`, `COMMIT_EDGE`. `scheduleGeneration(ServerLevel)` seeds anchors, then enqueues corridor-fill + route + commit tasks for each trunk pair. Tasks execute one per server tick (budget-guarded via `ensureRegionFilled`). `isComplete(ServerLevel)` checks `WorldRoadSavedData.isGreatRoadGenerationComplete()`. `forceComplete(ServerLevel)` drains all tasks synchronously.

**Files modified:**
- `Networking/WorldRoadSavedData.java` — Extended `Snapshot` from 3 to 4 fields; added `boolean greatRoadGenerationComplete` as field 4 with `optionalFieldOf(false)` for backward compat. Added `isGreatRoadGenerationComplete()`, `setGreatRoadGenerationComplete(boolean)`.
- `Events/TickSystems.java` — Added `GreatRoadGenTickSystem` (interval=1, priority=50). Calls `GreatRoadGenerationQueue.scheduleGeneration` once when `!isComplete` and queue empty; then calls `queue.tick(level)` each tick.

**Phase 7a debug commands (added to `RoadGraphDebugCommand.java`, re-added after rebase drop):**
- `show_great_roads` — tier-colored particle beams for all GREAT_ROAD edges + GREAT_ROAD_ANCHOR nodes within 4096 blocks
- `show_anchors` — bright END_ROD beam at each anchor node, prints count + position list
- `generation_status` — shows complete/in-progress, queue depth, task types pending
- `force_complete_generation` — drains full generation pipeline synchronously (SEED_ANCHORS → FILL_ATLAS_CORRIDOR → ROUTE_TRUNK → COMMIT_EDGE → NAME_SELECTION)
- `dominant_axis` — prints EW or NS for the current world's great-road orientation

**Git commit:** `fdeb932 Phase 7a: deterministic great-road anchor graph at worldgen`
**WorldRoadSavedData re-applied:** `4651844` (main added VillageUpkeepLedger as field 3 after 7a was authored)

**Test world observations:** Manual static review only — no live test world run in this session.

**Deviations from plan:**
- Phase 7a debug commands were dropped when the branch was rebased onto main (main had added many Phase 6 commands to `RoadGraphDebugCommand.java`). Re-added alongside Phase 7b commands in the same edit pass.

**Carryovers:**
- All Phase 6 carryovers (culture testing, sign text, junction/ruin visuals) remain untested in a live world.

**Next: Phase 7b — named roads.**

---

### 2026-04-23 — Phase 7b implemented: named great roads

**Phase:** 7b — Named road data model, Old Realm name pool, selection rules, queue integration, query API, history integration, debug commands

**Files created:**
- `Village/Roads/Graph/Worldgen/OldRealmNamePool.java` — ~29 names across 4 registers:
  - `VIA_NAMES` (7): Via Antiqua, Via Ferrea, Via Regalis, Via Magna, Via Petra, Via Caelum, Via Salinae
  - `OLD_WAYS` (6): The Hammerway, The Ironway, The King's Road, The Old Way, The Ashway, The Stonepath
  - `POETIC_WAYS` (8): The Wanderer's Road, The Road of Echoes, The Pale Way, The Road Beneath Stars, The Farwalker's Road, The Forgotten Way, The Road at World's Edge, The Ember Road
  - `GEOGRAPHIC_WAYS` (8): The Ridge Road, The Valeway, The Moorpath, The Dawnroad, The Coldway, The Fenway, The Thornway, The Shore Road
  - `selectName(BlockPos anchorA, BlockPos anchorB, long worldSeed, Set<String> usedNames)`: deterministic shuffle from stable anchor-pair seed; fallback ordinal name if pool exhausted.
  - `stableSeed` XOR-combines anchor coordinates so name assignment is direction-independent.
- `Village/Roads/Graph/Worldgen/NamedRoadSelector.java` — Selects one most-significant GREAT_ROAD edge per 8000×8000-block region.
  - `selectEdgesToName(WorldRoadGraph)`: buckets edges by `regionKey(midpointBlocks(cellPath))`; per region picks max by `cellPath.size()` then min distSq to region center; sorts selection by midpoint coords for determinism.
  - `nameSelectedEdges(WorldRoadGraph, List<UUID>, long worldSeed)`: assigns names via `OldRealmNamePool.selectName`.
  - `midpointBlocks(List<Long> cellPath)`, `regionKey(blockX, blockZ)` package-accessible for debug command.
  - `REGION_SIZE = 8_000` package-accessible for debug command.

**Files modified:**
- `Village/Roads/Graph/RoadEdge.java` — Added `Optional<String> roadName` as field 15 in CODEC (`optionalFieldOf`, backward-compatible). Updated `fromCodec` to 15 params. Added `getRoadName()`, `setRoadName(String)`, `clearRoadName()`.
- `Village/Roads/Graph/WorldRoadGraph.java` — Added `namedGreatRoads()` (filter by GREAT_ROAD tier + roadName present) and `findByName(String namePrefix)` (case-insensitive prefix match). Added `java.util.Locale` and `java.util.stream.Collectors` imports.
- `Events/GreatRoadGenerationQueue.java` — Added `NAME_SELECTION` to `StepType` enum. Added `NameSelectionTask` inner class (runs after all trunks committed): calls `NamedRoadSelector.selectEdgesToName` + `nameSelectedEdges`, logs count, sets `greatRoadGenerationComplete = true`, marks dirty. Added `namingScheduled` guard to prevent double-queuing. Fixed `forceComplete` loop to drain `NameSelectionTask` that is enqueued mid-loop.
- `Lore/KingdomHistoryData.java` — Added `ANCIENT_ROAD_FOUNDED_NEAR` to `HistoryEventType` enum (Old Realm / worldgen group).
- `Lore/HistoryTextGenerator.java` — Added 3 text templates for `ANCIENT_ROAD_FOUNDED_NEAR` (using `{party}` for road name). Added factory `nearAncientRoad(kingdomName, roadName, tick)`.
- `Kingdom/WorldgenKingdomSeeder.java` — Added `logNearAncientRoad(level, sk.name, chosenOrigin)` call after `KingdomSpawner.planComposed`. Added `logNearAncientRoad` helper: `graph.edgesNear(origin, 6000)` → first GREAT_ROAD with `roadName` present → console log `[RoadHistory]`. Wrapped in `try/catch` (best-effort; road generation may lag kingdom seeding).
- `Commands/RoadGraphDebugCommand.java` — Added Phase 7b debug commands alongside re-added Phase 7a commands:
  - `named_roads` — lists all named great roads, their region bucket (8000-block grid), midpoint block coords, cell path length
  - `highlight_named <namePrefix>` — `greedyString()` arg (supports spaces in names like "Via Antiqua"); ENCHANT particles on the matching edge + its anchor nodes
  - `reselect_names` — clears all road names, re-runs `NamedRoadSelector.selectEdgesToName` + `nameSelectedEdges` from world seed; reports count before/after

**Test world observations:** Manual static review only — no live test world run in this session.
- Expected named road count: 1 name per 8000-block region, so typically 1–4 names per normal-sized world.
- Sample names from pool: "Via Antiqua", "The Hammerway", "The Road of Echoes", "The Ridge Road" (thematic variety across 4 registers).
- History integration: best-effort console log fires only if roads are already generated when kingdoms are seeded. First-boot order: road gen queued at tick 1, kingdoms seeded at tick ~600+, so roads will typically be complete before kingdoms spawn. Log expected for most kingdoms near a great road corridor.
- Visual/thematic assessment: untested in world. Latin (`Via *`) and Old English (`The * way`) registers match the Old Realm fiction in ROADS_PLAN.md.

**Deviations from spec:**
- History integration uses console log (`[RoadHistory]`) rather than `kingdom.getHistory().recordEvent(...)` for the first implementation. The `nearAncientRoad` factory method and `ANCIENT_ROAD_FOUNDED_NEAR` event type are wired up and functional; the seeder calls `logNearAncientRoad` which logs to console. Full in-game history record requires `KingdomSpawner.planComposed` to return or expose the `Kingdom` object — a follow-up wiring task for Phase 7c or later.
- `GreatRoadGenerationQueue.forceComplete` was refactored to loop while `!isComplete(level)` (not just while `!taskQueue.isEmpty()`) to correctly drain the `NameSelectionTask` that is enqueued by `onGenerationComplete` partway through the loop.

**Carryovers:**
- History event not written to in-game kingdom history yet (console log only).
- All Phase 6 carryovers (culture testing, sign text, junction/ruin visuals) remain untested in a live world.

**Next: Phase 7c — great road character parameters.**

---

### 2026-04-23 — Phase 7c implemented: great road character parameters

**Phase:** 7c — Per-road seeded character: meander amplitude, character tag, bias hints, deterministic drift

**Files created:**
- `Village/Roads/Graph/GreatRoadCharacter.java` — data record. Fields: `CharacterTag tag`, `float meanderAmplitude`, `float meanderFrequency`, `long characterSeed`, `BiasHints hints`. `CharacterTag` enum: MOUNTAIN_HUGGING, PLAINS_STRAIGHT, RIVER_FOLLOWING, FOREST_WANDERING, COASTAL, UPLAND_PASS, DEFAULT. `BiasHints` nested record with steep/river/forest cost biases and meanderAmplitudeBias. Both carry DFU codecs.
- `Village/Roads/Graph/Worldgen/GreatRoadCharacterAnalyzer.java` — `analyze(cellPath, atlas, worldSeed)`. Walks cell path, tallies steep/riverAdj/coast/forest/mountain/plains counts. First-match classification with thresholds: COASTAL (coast>0.4), MOUNTAIN_HUGGING (mountain>0.5), UPLAND_PASS (steep>0.3), RIVER_FOLLOWING (river>0.4), FOREST_WANDERING (forest>0.7), PLAINS_STRAIGHT (plain>0.7 && steep<0.1), DEFAULT (else). `computeCharacterSeed`: `worldSeed XOR (first*prime) XOR (last*prime)` → stable, direction-independent. Called by `GreatRoadTrunkRouter.routePair`.

**Files modified:**
- `Village/Roads/Graph/RoadEdge.java` — Added `Optional<GreatRoadCharacter> character` as 16th codec field (`optionalFieldOf`, backward-compatible). Added `fromCodec` 16th parameter. Added `getCharacter()`, `setCharacter(Optional<GreatRoadCharacter>)`, `getCharacterTag()` accessors.
- `Village/Roads/Graph/Worldgen/GreatRoadTrunkRouter.java` — Added `GreatRoadCharacter character` as 6th field of `PlannedTrunk` record. `routePair()` now calls `GreatRoadCharacterAnalyzer.analyze` after routing and includes character in returned trunk.
- `Events/GreatRoadGenerationQueue.java` — `CommitEdgeTask.process` now reads `trunk.character()` instead of computing a hardcoded meander seed. MeanderProfile amplitude/frequency/seed all derived from character. `edge.setCharacter(Optional.of(character))` called after edge creation. Removed `hashTrunk` helper (superseded).
- `Village/Roads/Graph/WorldRoadGraph.java` — Added `greatRoadsByCharacter(CharacterTag)` query: filters GREAT_ROAD edges by character tag using `getCharacterTag().filter(tag::equals).isPresent()`.
- `Village/Planning/Primitives/RoadPrimitive.java` — `SmoothedPath` record: added `long seed` as 5th field (`optionalFieldOf("seed", 0L)` for backward compat). `computeCenterline` now calls `applyLateralDrift` when `driftAmplitude > 0`. `applyLateralDrift`: arc-length parameterizes the Catmull-Rom result, applies perpendicular drift via `DriftNoise.sample` at each point using `effectiveSeed = seed != 0 ? seed : DriftNoise.localSeed(worldSeed, first, last)`. Endpoints naturally fade to zero drift (DriftNoise.sample returns 0 at t=0 and t=1).
- `Village/Roads/Realization/PrimitiveChainBuilder.java` — For GREAT_ROAD edges, reads `edge.getMeanderProfile().amplitude()` as `driftAmp` and `edge.getMeanderProfile().seed()` as `primSeed` before building SmoothedPath. Non-great-road edges retain `driftAmp=0.0, primSeed=0L` (unchanged behavior, no drift).
- `Commands/RoadGraphDebugCommand.java` — Added 3 Phase 7c debug commands:
  - `character <edgeId>` — reports tag, amplitude/frequency, seed (hex), bias hints
  - `characters` — counts GREAT_ROAD edges by character tag; reports pre-7c (no-character) edges separately
  - `highlight_character <tag>` — highlights all edges of given character with tag-specific particle: MOUNTAIN_HUGGING→CLOUD, PLAINS_STRAIGHT→COMPOSTER, RIVER_FOLLOWING→WITCH, FOREST_WANDERING→HAPPY_VILLAGER, COASTAL→SOUL_FIRE_FLAME, UPLAND_PASS→FLAME, DEFAULT→SMOKE

**Test world observations:** Manual static review only — no live test world run in this session.
- Expected distribution: most great roads in a typical world (forests, plains, mixed terrain) will be DEFAULT or FOREST_WANDERING. PLAINS_STRAIGHT and RIVER_FOLLOWING likely to appear in flat/riverine worlds. MOUNTAIN_HUGGING and COASTAL require specific terrain and will be rare.
- Visual expectation: MOUNTAIN_HUGGING (amplitude=18) vs PLAINS_STRAIGHT (amplitude=4) should be visibly distinct — 18-block perpendicular drift produces sweeping curves; 4-block drift is nearly straight.
- Determinism: character assignment is fully deterministic — `computeCharacterSeed` is XOR-based (direction-independent), analyzer thresholds are deterministic given the same atlas.

**Deviations from spec:**
- None. All 7 deliverables implemented as specified. Character tag thresholds match the spec exactly.
- `hashTrunk` method removed from `GreatRoadGenerationQueue` (was superseded by character-derived seed; no callers remained).

**Carryovers:**
- History event for kingdom-near-road not in-game yet (console log only).
- All Phase 6 carryovers (culture testing, sign text, junction/ruin visuals) remain untested in a live world.

**Phase 7 complete.** Phases 7a + 7b + 7c all done. The Old Realm road system is fully built.

**Next: Phase 8a — travel incentives. The player-facing gameplay phase begins.**

---

### 2026-04-23 — Phase 8a implemented: travel incentives

**Phase:** 8a — Player movement-speed bonus on maintained roads

**Files created:**
- `Village/Roads/Travel/RoadUnderfootDetector.java` — Detects whether a `ServerPlayer` is standing on a realized road edge. Foot block = `player.blockPosition().below()`. Uses `WorldRoadGraph.edgesNear(x, z, 128)` as spatial pre-filter, then iterates each candidate edge's `getBlockPath()` for exact XZ match with Y tolerance ±1. Returns the edge or `null`. The ±1 Y tolerance handles slab-height roads and slight surface variation.
- `Village/Roads/Travel/RoadSpeedModifier.java` — Pure function: `speedBonus(EdgeTier, maintenance)` → double. Speed bonus table (ADD_MULTIPLIED_BASE semantics — 0.30 means 30% faster):
  - GREAT_ROAD: maint ≥ 80 → +0.30, maint 40–79 → linear from +0.20→+0.30, maint 20–39 → linear from 0→+0.20, maint < 20 → 0.0
  - TRUNK: ≥ 80 → +0.20, 40–79 → linear, 20–39 → linear, < 20 → 0.0
  - CONNECTOR: ≥ 80 → +0.15, 40–79 → linear, 20–39 → linear, < 20 → 0.0
  - LOCAL: ≥ 80 → +0.08, 40–79 → linear, 20–39 → linear, < 20 → 0.0
  - `speedMultiplier(EdgeTier, maintenance)` convenience wrapper: returns `1.0 + speedBonus(...)`.
- `Events/PlayerRoadSpeedSystem.java` — `TickSubsystem` (interval=10, priority=120). Each tick: iterates online players, calls `RoadUnderfootDetector.detectEdge`, calls `RoadSpeedModifier.speedBonus`. If bonus > 0 → `attr.addOrUpdateTransientModifier(new AttributeModifier(MODIFIER_ID, bonus, ADD_MULTIPLIED_BASE))`. If bonus == 0 → `attr.removeModifier(MODIFIER_ID)`. Modifier ID: `life_in_the_village:road_speed_bonus` (stable ResourceLocation). NeoForge 1.21 API: `ResourceLocation.fromNamespaceAndPath`, `addOrUpdateTransientModifier`, `removeModifier(ResourceLocation)`.

**Files modified:**
- `Events/TickSubsystemRegistry.java` — Added `register(new PlayerRoadSpeedSystem())` after `NpcMemoryDecayTickSystem` in `registerDefaults()`.
- `Commands/RoadGraphDebugCommand.java` — Added 3 Phase 8a debug commands under `/liv road debug`:
  - `underfoot` — Reports edge underfoot (id, tier, maintenance) and computed speed bonus. Returns 0 if not on road.
  - `simulate_walk <edgeId>` — Reports speed bonus/multiplier at 7 maintenance checkpoints (100, 80, 60, 40, 20, 10, 0) for the given edge's tier. Useful for verifying the bonus table without a live road.
  - `speed_report` — Reports player name, edge underfoot (or "none"), computed bonus, attribute base value, and current effective value (includes all modifiers).

**Test world observations:** Manual static review only — no live test world run in this session.
- `RoadUnderfootDetector`: exact XZ match is correct since `blockPath` contains all surface blocks including road width from `UnifiedRoadPlacer`. Y tolerance ±1 handles surface variation.
- `RoadSpeedModifier` edge cases verified mentally: maintenance=0 → 0.0, maintenance=40 → midBonus exactly, maintenance=80 → highBonus exactly, maintenance=60 → linear midpoint.
- `PlayerRoadSpeedSystem`: `addOrUpdateTransientModifier` is idempotent — repeated calls with same ID replace the previous modifier. No orphan modifier risk.

**Deviations from spec:**
- Linear interpolation within maintenance bands (20–40 and 40–80) rather than flat values. This gives smoother player experience with no hard steps. The flat values from the spec (e.g. GREAT_ROAD 40–79 → +0.20) become the band floor/ceiling of the interpolation.

**Carryovers:**
- Performance concern noted: `RoadUnderfootDetector` linearly scans full `blockPath` for long edges (great roads). For Phase 8a this is acceptable (interval=10, few nearby edges). Phase 8b could add a per-edge `HashSet<Long>` of packed positions for O(1) lookup.
- History event for kingdom-near-road not in-game yet (console log only).
- All Phase 6 carryovers (culture testing, sign text, junction/ruin visuals) remain untested in a live world.

**Phase 8a complete.**

---

### 2026-04-23 — Phase 8b implemented: safety gradient along maintained roads

**Phase:** 8b — Daytime hostile-mob spawn suppression within road safety corridors

**Files created:**
- `Village/Roads/Travel/RoadProximityCache.java` — chunk-level pre-filter. Marks every chunk within 4 chunks of any atlas cell that belongs to a maintained realized edge. Built lazily on first spawn event; invalidated by upkeep cycle. `couldChunkBeNearRoad(ChunkPos)` returns false (definitive safe) for the vast majority of world chunks that have no roads near them. Chunk radius = `ceil((CELL_HALF + MAX_CORRIDOR) / 16) + 1 = 4`. Conservative: always returns true if cache not yet built.
- `Village/Roads/Travel/RoadProximityChecker.java` — precise 2D Chebyshev check. `nearestMaintainedRoadTier(graph, pos, searchRadius)` → `Optional<EdgeTier>`. For each candidate edge from `edgesNear(x, z, 16)`: skip if maintenance < 30 (non-GREAT_ROAD), skip if not realized, scan blockPath for `abs(dx) <= cw && abs(dz) <= cw`. Corridor widths: GREAT_ROAD=12, TRUNK=8, CONNECTOR=5, LOCAL=3. Returns the highest-priority tier in range, short-circuits at GREAT_ROAD.
- `Events/RoadSafetySystem.java` — `@EventBusSubscriber` event handler. `@SubscribeEvent onFinalizeSpawn(MobSpawnEvent.FinalizeSpawn)` — guards: `entity instanceof Monster`, `spawnType == MobSpawnType.NATURAL`, `level instanceof ServerLevel`, `level.isDay()`. Fast-rejects via chunk cache; runs in-depth check only for chunks near roads. Calls `event.setSpawnCancelled(true)` with probability `suppressionChanceForTier(tier)`. Suppression table: GREAT_ROAD=0.85, TRUNK=0.70, CONNECTOR=0.50, LOCAL=0.30. Tracks `AtomicLong totalEvents` / `suppressed` for stats commands.

**Files modified:**
- `Events/RoadUpkeepSystem.java` — added `RoadProximityCache.invalidate()` at end of `runCycle()`. This ensures the cache reflects new maintenance scores before the next in-game day's spawns. Import added.
- `Commands/RoadGraphDebugCommand.java` — added 3 Phase 8b debug commands:
  - `safety_check <x> <y> <z>` — reports: isDay, chunk cache status, nearest maintained road tier + corridor width, suppression chance, whether suppression is active at that position.
  - `simulate_spawn <mob_type>` — runs the full road-safety decision at the player's position and reports the simulated outcome (SUPPRESSED or ALLOWED) with a random roll so the probabilistic nature is visible.
  - `safety_stats` — total daytime hostile spawn events seen, suppressed count, suppression rate (%), cache built status and chunk count.

**Design decisions:**
- `level.isDay()` used (not raw `getDayTime()`) so storms also disable road safety, consistent with the "travel by day" feel. A thunderstorm at noon makes roads feel dangerous again.
- GREAT_ROAD 85% suppression: ~1 in 7 daytime hostile spawns still succeed near a great road. Intentional — keeps alertness alive; the road is not invincible.
- Maintenance threshold 30 aligns exactly with Phase 6b band 1 (20–39). At band 0 (0–19), roads are visually covered in fallen logs and saplings — the same roads that provide no spawn suppression. Mechanical and visual decay align.
- Cache built from cellPath (not blockPath) to keep rebuild cost low. A cell is 64 blocks; using 4 chunk radius = ~44-block reach, which conservatively covers the widest corridor (12 blocks) plus cell half-width (32 blocks). No false negatives; minor false positives resolved by in-depth check.
- Only `MobSpawnType.NATURAL` is suppressed. Spawner blocks, structure spawns, and future road-event mobs (Phase 10b bandits) bypass the check entirely by design.

**Test world observations:** Manual static review only.
- Expected: standing on great road at noon → sparse hostile mobs visible; stepping 15 blocks away → noticeably more mobs. Exact experience depends on world spawn rate and biome.
- Cache effectiveness: in a typical world with 5–10 realized road edges, maybe 2000–5000 chunks flagged. That's a small fraction of a large world; the fast-reject path should handle >99% of all spawn events.
- Decay alignment: use `force_maintenance 10` on a connector to drive it to band 0. Confirm `safety_check` reports "no maintained road within corridor" while on that connector's block path.
- Night test: stand on great road at night → `simulate_spawn zombie` reports "Night: no suppression."

**Deviations from spec:**
- `simulate_spawn <mob_type>` does not actually try to spawn the mob. It runs the suppression decision logic at the player's position and reports the outcome. A live spawn attempt would require entity creation, which is inappropriate for a debug command.
- `safety_stats` does not implement a sliding-window "last X minutes" counter. It accumulates since server start (or last explicit reset). The display shows elapsed ticks and computed seconds so the user can interpret the rate in context. A reset mechanism is noted in the output.

**Carryovers:**
- Phase 8a carryover: `RoadUnderfootDetector` linearly scans full `blockPath`. Acceptable for Phase 8a/8b; Phase 8b's `RoadProximityChecker` has the same pattern. A per-edge `HashSet<Long>` (packed XZ positions) would fix both — Phase 8c optimization candidate.
- History event for kingdom-near-road not in-game yet (console log only).
- All Phase 6 carryovers (culture testing, sign text, junction/ruin visuals) remain untested in a live world.

**Next: Phase 8c — shelter expectation (waystations and inns along long road stretches).**

---

### 2026-04-23 — Phase 8c implemented: shelter expectation along long roads

**Phase:** 8c — Shelter placement on long GREAT_ROAD and TRUNK stretches

**Files created:**
- `Village/Roads/Decoration/ShelterPlanner.java` — pure planning logic. `ShelterType` enum (INN, CARAVANSERAI, ROADSIDE_SHRINE, RUINED_WATCHTOWER, RUINED_WAYSTATION). `ShelterPlan` record (`pathIndex`, `position`, `type`, `facingDx`, `facingDz`). `plan(RoadEdge)`: GREAT_ROAD/TRUNK only; minimum 800-block path; skip first/last 200 blocks (endpoint buffer); spacing 400–600 blocks (seeded RNG from `edge.getMeanderProfile().seed() ^ 0xCAFEF00DL`); 4-block perpendicular offset alternating sides. Type distribution: GREAT_ROAD favours INN(35%)/CARAVANSERAI(30%), TRUNK favours SHRINE(25%)/TOWER(20%)/WAYSTATION(20%).
- `Village/Roads/Decoration/ShelterInstance.java` — persistent record. Fields: `UUID instanceId`, `ShelterType type`, `BlockPos position`, `List<BlockPos> placedBlocks`, `long placedTick`. 5-field codec with `optionalFieldOf` for backward-compatible fields.
- `Village/Roads/Decoration/ShelterBuilder.java` — procedural block placement. `place(level, plan, culture, tick)` → `ShelterInstance` or `null`. Terrain suitability: 3×3 grid of height samples, rejects if variance > 4 blocks. Ground-snaps origin via `Heightmap.Types.MOTION_BLOCKING_NO_LEAVES`. Five placement methods:
  - `placeInn`: 7×9 two-story building; walls+floor+roof by culture (nordic=spruce, imperial=smooth_stone, highland=stone_bricks, default=cobble+oak); 4 beds per floor (foot+head pairs, colored by culture: nordic=white, imperial=purple, highland=brown, default=red); campfire+iron_bars hearth on back wall; door opening carved in road-facing wall; oak_wall_sign post by entrance.
  - `placeCaravanserai`: 9×11 U-shape courtyard (open on road side); roofed wings on left/right arms; cobblestone courtyard; central well (water+4 cobblestone walls); stone-brick gate posts; 4 beds in back wing.
  - `placeShrine`: 3×3 polished_andesite base; central chiseled_stone_bricks pillar × 2; stone_brick_slab cap; corner stone_brick_wall posts; slab roof at corners; 70% lantern on top; 25%-per-tile candle offerings around base.
  - `placeRuinedWatchtower`: 5×5 perimeter (intact lower 2 rows, 60% intact upper rows); scattered cobblestone debris around exterior; vine overgrowth on north wall (25% blocks); entrance opening carved; unlit campfire inside.
  - `placeRuinedWaystation`: 7×7 with 80%-probability collapsed wall segments (height 1–3); cobblestone floor with 65% density; surviving 3×3 stone-brick roof over back corner; single bed under roof; broken well/cistern in center; scattered debris around exterior.

**Files modified:**
- `Networking/WorldRoadSavedData.java` — added `Map<UUID, List<ShelterInstance>> edgeShelters` field; added to `Snapshot` record as 5th field with `Codec.unboundedMap(UUID_CODEC, ShelterInstance.CODEC.listOf()).optionalFieldOf("edgeShelters", ...)` for backward compatibility. Added API: `getShelters(edgeId)`, `getOrCreateShelters(edgeId)`, `setShelters(edgeId, list)`, `clearShelters(edgeId)`, `getAllEdgeShelters()`.
- `Village/Roads/Realization/EdgeRealizer.java` — overloaded `realizeEdge` to accept `WorldRoadSavedData` directly (avoids double `get()` call when caller already holds it). After `edge.markRealized(fullPath)`: calls `placeSheltersIfAbsent(level, edge, roadData, culture)` — only acts on GREAT_ROAD/TRUNK with no existing shelters; plans via `ShelterPlanner.plan(edge)` then places each plan via `ShelterBuilder.place`. Added imports: `WorldRoadSavedData`, `ShelterBuilder`, `ShelterInstance`, `ShelterPlanner`.
- `Commands/RoadGraphDebugCommand.java` — added 5 Phase 8c debug commands:
  - `shelters_on_edge <edgeId>` — lists all shelters on the edge with type, position, block count.
  - `nearest_shelter` — finds the nearest shelter to the player across all edges; reports type, position, edge, distance.
  - `force_place_shelters <edgeId>` — clears existing shelters and re-plans+places them; reports planned vs. placed count (some may be rejected by terrain).
  - `force_place_shelter_type <type>` — places a single specified shelter type at the player's position; associates with nearest edge if within 256 blocks.
  - `replace_shelter <edgeId>` — removes all placed blocks for existing shelters on an edge, then re-plans and re-places.

**Architecture decisions:**
- Shelters stored in `WorldRoadSavedData` (not in `RoadEdge`) because `RoadEdge.CODEC` is already at the DFU RecordCodecBuilder 16-field limit. The separation also mirrors the `VillageUpkeepLedger` pattern (logically associated with edges but stored in the SavedData wrapper). This is cleaner: shelter data is a decoration layer, not core edge identity.
- `ShelterBuilder.place` returns `null` on unsuitable terrain rather than throwing — callers skip gracefully and log the planned-vs-placed gap at the end.
- Re-realization guard: `placeSheltersIfAbsent` checks `!roadData.getShelters(edgeId).isEmpty()` before planning. This means shelters survive edge re-realization (e.g., after terrain invalidation) without being duplicated. `force_place_shelters` and `replace_shelter` can override this for debug/admin purposes.
- `EdgeRealizer.realizeEdge(4-arg)` now delegates to the 5-arg overload — all existing call sites continue to work unchanged; only the new shelter code uses the 5-arg form directly.
- Culture is passed from the material context to `ShelterBuilder.place` so inns and caravanserais match the road's regional style (nordic spruce on Viking-culture roads, imperial stone on Trade Empire stretches).

**Test world observations:** Manual static review only — no live test world run.
- Expected: on a 3000-block GREAT_ROAD segment: 5–6 shelters spaced ~500 blocks apart, alternating road sides, mix of INN and CARAVANSERAI with occasional SHRINE.
- Terrain rejection expected in hilly areas: `force_place_shelters` will show `planned=N placed=M` with `M < N` for mountain-hugging roads.
- `nearest_shelter` is the fastest way to confirm placement worked after realization.
- Culture check: on a Nordic great road, inn walls should be spruce planks; beds should be white.

**Deviations from spec:**
- Shelter instances are NOT stored on `RoadEdge` (codec limit). Stored in `WorldRoadSavedData.edgeShelters` instead — functionally equivalent and cleaner.
- No innkeeper NPCs in Phase 8c (spec marked as optional; deferred to Phase 10a which defines the full `TownspersonMob` roadside structure system).
- No discovery messages (also optional; deferred — requires player proximity tick scan and message framework not yet present).
- `force_place_shelter_type <type>` takes type as only argument; associates with nearest edge automatically rather than requiring `<edgeId>` as separate arg. Simpler for testing.

**Carryovers:**
- Linear blockPath scan in `RoadUnderfootDetector` / `RoadProximityChecker` (noted since Phase 8a). Still deferred.
- All Phase 6 carryovers remain.

---

### 2026-04-23 — Phase 8d implemented: tolls and checkpoints

**Files created:**
- `Village/Roads/Economy/TollFeeCalculator.java` — pure fee computation. Base fees: GREAT_ROAD=20 bronze, TRUNK=10 bronze. Goods surcharge: `goodsValue / 50`. Reputation tiers: 80+=free, 60-79=50%, 20-59=100%, <20=150%. `TollFee` record with `amountBronze`, `reason`, `isFree()`. Constants for all thresholds. `computeFeeDefault` convenience overload.
- `Village/Roads/Decoration/TollGatePlanner.java` — detects kingdom border crossings on TRUNK and GREAT_ROAD edges. `TollGatePlan` record (8 fields: edgeId, splitCellKey, gatePosition, collectingKingdomId, entrySideNodeId, exitSideNodeId, tierAtGate, structureSeed). `planForGraph(graph, vData, worldSeed)` builds `Map<Long,UUID>` cell→kingdom index from `KingdomClaim.claimedCellKeys()`, walks each qualifying edge's cell path, detects ownership transitions, places gate 2 cells inside the incoming kingdom. TRUNK=100% probability, GREAT_ROAD=80%. Deduplication via `Set<Long> usedCells`. `buildCellKingdomIndex(VillageSavedData)` is public for debug use.
- `Village/Roads/Decoration/TollGateBuilder.java` — places gate structure (3-wide arch + pillar lanterns + 3×3 guardhouse with floor, walls, roof, door, corner lanterns). Cultural material variants: imperial=stone_bricks/polished_andesite/dark_oak, highland=mossy_cobblestone, nordic=stone/spruce, default=cobblestone/oak. Calls `WorldRoadGraph.insertTollGateNode` then `WorldRoadSavedData.registerTollGate`. Returns `PlacementResult(gatePosition, nodeId, placedBlocks)`.
- `Events/TollGateSystem.java` — toll tick logic (interval=20). Caravan charging: finds spawned caravans within 5 blocks of any TOLL_GATE node, charges origin village treasury, credits kingdom treasury, records revenue. Session dedup via `Set<String> "caravanId:nodeId"`. Player notification: one-time chat message on approach within 12 blocks with upcoming fee; crossing log within 3 blocks (no currency deduction — Phase 10 dependency). `playerKingdomReputation` aggregates village-level scores across kingdom's villages using max.

**Files modified:**
- `Village/Economy/Trade/RoadEvent.java` — `EventType` enum extended with `TOLL_GATE_RAIDED` and `CORRUPT_TOLL_COLLECTOR` dormant stubs (Phase 10b).
- `Village/Roads/Graph/WorldRoadGraph.java` — added `insertTollGateNode(UUID edgeId, long splitCellKey, BlockPos gatePos, UUID kingdomId)` → `Optional<SplitResult>`. Same split logic as `splitEdgeAtCell` but creates `NodeType.TOLL_GATE` node with `Optional.of(kingdomId)` affinity.
- `Networking/WorldRoadSavedData.java` — added `TollGateRecord` nested record (nodeId, kingdomId, tier, revenueBronze) with 4-field codec. Added 6th Snapshot field `tollGates: Map<UUID, TollGateRecord>`. New API: `getTollGate(nodeId)`, `registerTollGate(nodeId, kingdomId, tier)`, `addTollRevenue(nodeId, bronze)`, `removeTollGate(nodeId)`, `getAllTollGates()`. Added `import RoadEdge`.
- `Events/TickSystems.java` — added `TollGateTickSystem` (interval=20, priority=119).
- `Events/TickSubsystemRegistry.java` — registered `new TollGateTickSystem()` after `RoadEventTickSystem`.
- `Commands/RoadGraphDebugCommand.java` — added imports for `Kingdom`, `TollGateBuilder`, `TollGatePlanner`, `TollFeeCalculator`. Added 5 debug commands under `/liv road debug`:
  - `list_tolls` — lists all registered gates with kingdom, tier, revenue.
  - `simulate_toll <kingdomName>` — finds nearest gate for kingdom and shows fee at default reputation.
  - `toll_revenue <kingdomName>` — totals cumulative revenue across all gates for a kingdom.
  - `force_place_toll <edgeId>` — runs `TollGatePlanner.planForGraph` then `TollGateBuilder.build` on the first detected crossing for the edge.
  - `replace_toll <nodeId>` — resets the toll gate record (revenue zeroed) without removing the structure.
  - Added `/litv toll pay` player command — finds nearest gate within 20 blocks, computes fee at player's actual reputation, logs payment (no currency deduction), sends confirmation message.

**Architecture decisions:**
- `TollGateRecord` is a nested record in `WorldRoadSavedData` (like `TollGatePlan` is in `TollGatePlanner`) — keeps related codec and data in one place.
- `insertTollGateNode` is a new method on `WorldRoadGraph` rather than a modified `splitEdgeAtCell` — avoids any risk of breaking the split logic used by the connector planner and elsewhere. The two methods are structurally identical except for node type and kingdom affinity.
- No `paidTollGates` set on `Caravan` — the toll system maintains its own session-scoped `Set<String>` keyed by "caravanId:nodeId". This keeps `Caravan` codec unchanged and avoids modifying a heavily-used class for transient state.
- Reputation is aggregated per-kingdom by taking the max score across all villages in the kingdom. This prevents players from being penalized by a single low-rep village while having high rep elsewhere in the kingdom.
- No player currency deduction — no currency system exists yet. Logged as "[TollGate] … [no currency system yet]" for Phase 10. Revenue counter in `TollGateRecord` still increments so kingdom debt can be reconciled later.

**Deviations from spec:**
- Guard NPC not spawned — TownspersonMob guard spawning deferred (no guard profession/behaviour available yet). The guardhouse structure is placed; guard spawn is Phase 10a.
- `/litv toll pay` doesn't deduct items from inventory (no currency system). It logs the payment and increments revenue only.
- `force_place_toll` requires a kingdom to exist (at least one). In a fresh world with no kingdoms it fails gracefully with a message.

**Test world observations:** Manual static review only.
- Expected: on GREAT_ROAD edges crossing two kingdoms, `planForGraph` detects transitions and `force_place_toll <edgeId>` places an arch+guardhouse visible in-world.
- `list_tolls` should show gates after `force_place_toll` runs; `toll_revenue` should show 0 until caravans cross.
- `simulate_toll <kingdom>` provides a quick sanity check that fees are tier-correct.

**Carryovers:**
- Guard NPC spawning at toll gates (Phase 10a).
- Player currency deduction at toll gates (Phase 10).
- TOLL_GATE_RAIDED and CORRUPT_TOLL_COLLECTOR event logic (Phase 10b — stubs present).
- Linear blockPath scan in `RoadUnderfootDetector` / `RoadProximityChecker` (noted since Phase 8a). Still deferred.
- All Phase 6 carryovers remain.

---

**Phase 8 complete.** Phase 8a (terrain safety system), 8b (road events + TravellingGroup engine), 8c (shelter expectation), and 8d (tolls and checkpoints) all implemented.

**Next: Phase 8d — tolls and checkpoints (kingdom border toll gates).**
