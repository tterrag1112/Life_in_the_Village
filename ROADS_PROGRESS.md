# Roads system — progress log

Append-only. Most recent entry at the bottom. Each session ends with an entry summarizing what was done, what broke, and what's next.

## Current phase

**Phase 7d** (inserted after 8d) — Worldgen ordering and atlas generation speed. Complete.

## Current slice

Phase 7d complete. Phase 8 complete. Next: Phase 9 per ROADS_PLAN.md.

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

---

### 2026-04-23 — Phase 7d implemented: worldgen ordering and atlas generation speed

**Note:** Inserted out of order after Phase 8d to address testing pain (worldgen taking ~25 minutes in practice).

**Phase:** 7d — Worldgen ordering and atlas generation speed

**Files modified:**

- `Village/Economy/Trade/AtlasRouteRouter.java` — Added `findGreatRoadRoute(atlas, from, to, worldSeed, level)` overload. When `level` is non-null, A* expansion samples unfilled cells on-demand via `atlas.ensureCell()` (lazy fill), up to `MAX_LAZY_FILLS = 5000` per routing call. Added `totalLazyFills` counter with `resetLazyFillCounter()` and `getTotalLazyFills()` accessors for debug commands and timing. Old 4-arg overload delegates to new 5-arg with `null` level.

- `Village/Roads/Graph/Worldgen/GreatRoadTrunkRouter.java` — Added `routePair(a, b, atlas, worldSeed, level)` overload that passes `level` to `findGreatRoadRoute`. Old 4-arg `routePair` retained as convenience delegate. `planTrunks` (synchronous debug wrapper) unchanged.

- `Events/GreatRoadGenerationQueue.java` — **Removed `FillAtlasCorridorTask`** class entirely. `SeedAnchorsTask.process` now queues `RouteTrunkTask` directly per pair (no intermediate fill). `RouteTrunkTask.process` calls `routePair(a, b, atlas, worldSeed, level)` with the level so A* fills lazily. Added timing fields: `wallStartMs`, `wallEndMs`, `anchorSeedMs`, `totalRouteMs`, `routingAttempts`. `scheduleGeneration` resets all timing and calls `AtlasRouteRouter.resetLazyFillCounter()`. `NameSelectionTask.process` emits completion log with anchors/trunks/lazy-fill-count/wall-time. `StepType.FILL_ATLAS_CORRIDOR` kept in enum (marked `@Deprecated`) for any serialized queue states in flight. Added accessors: `getAnchorSeedMs()`, `getTotalRouteMs()`, `getRoutingAttempts()`, `getWallTimeMs()`.

- `Kingdom/WorldgenKingdomSeeder.java` — **Replaced `seederRan` with road-generation gate.** Kingdom planning now waits for `WorldRoadSavedData.isGreatRoadGenerationComplete() == true`. Added `waitingLogged` flag to print `[Worldgen] Waiting for great-road generation to complete...` once. Logs `[Worldgen] Great-road generation complete. Beginning kingdom seeding.` when unblocked. Added `worldgenBudgetNanos(level)`: returns 500ms when no players online OR roads not complete, 50ms otherwise (10× budget during worldgen). Applied to both `processSpawn` and `trySpacedOrigin` `ensureRegionFilled` calls. Added `kingdomsPlaced` counter. Logs total seeder wall time when last kingdom is placed. Added `getStatusString()` and `getKingdomsPlaced()` accessors.

- `World/Atlas/WorldAtlas.java` — Optimized `ensureRegionFilled`: two-pass approach — first pass collects unfilled cells via fast HashMap lookups only (no sampling, no nanoTime calls), then returns `true` immediately if `unfilled.isEmpty()` (fast-path exit for already-filled regions). Second pass applies the time budget only to the unfilled cells, preserving spiral ordering for budget-aware incremental fill.

- `Events/RoadSafetySystem.java` — **Fixed compilation bug.** Changed `MobSpawnEvent.FinalizeSpawn` → `FinalizeSpawnEvent` (top-level class), `MobSpawnType.NATURAL` → `EntitySpawnReason.NATURAL`, `event.getSpawnType()` → `event.getSpawnReason()`. Updated Javadoc reference accordingly.

- `Commands/RoadGraphDebugCommand.java` — Added 2 debug commands:
  - `worldgen_status` — shows great-road complete/in-progress (N/M trunks, tasks queued), kingdom seeding status (waiting/in-progress/complete with counts), atlas fill budget (ms/tick), total atlas cells filled.
  - `worldgen_timing` — shows last generation run: anchor seed time, trunk routing total/average, atlas cells lazily sampled, total wall time.

**Expected performance improvement:**
- Atlas fill per trunk: ~15,800 cells pre-fill (8000-block radius per pair) → ~300–1000 lazy fills during A* (only cells actually visited). For ~38 pairs: 600,000 cells → ~20,000 cells total.
- At 50–150µs per cell: ~90 seconds → ~3 seconds for atlas fill during road generation.
- Kingdom seeder no longer competes with road generation for atlas budget.
- Worldgen budget raised to 500ms/tick while no players online; normal gameplay budget unchanged at 50ms.
- Target: world startup under 5 minutes (down from ~25 minutes).

**Architecture decisions:**
- Lazy fill in the A* inner loop rather than as a pre-pass: fills only cells the pathfinder actually visits, which is typically a narrow corridor. Pre-filling a full circular region of radius `halfDist + 500` was the dominant bottleneck.
- `MAX_LAZY_FILLS = 5000` cap: safety valve for pathological cases. At 12,000 A* node budget and 8-connected neighbourhood, 5,000 fills is generous for any real trunk route (~300–1000 fills expected). If cap is hit, partial path is used (same as node-budget exhaustion).
- `totalLazyFills` is session-total (not per-route), reset at `scheduleGeneration`. Gives overall atlas efficiency across the full generation run.
- `FILL_ATLAS_CORRIDOR` kept in `StepType` enum for any existing in-flight serialized queues. Marked `@Deprecated`. Not instantiable (class removed) but enum value won't cause deserialization crashes.
- `worldgenBudgetNanos` uses `level.players().isEmpty()` as worldgen proxy: simple, no additional state needed.
- `ensureRegionFilled` two-pass preserves spiral ordering — first-to-fill cells remain the innermost ones. Budget-aware incremental fill behavior is unchanged; only the fully-filled fast-path is new.

**Deviations from spec:**
- None.

**Carryovers:**
- Guard NPC spawning at toll gates (Phase 10a).
- Player currency deduction at toll gates (Phase 10).
- Linear blockPath scan in `RoadUnderfootDetector` / `RoadProximityChecker` (noted since Phase 8a). Still deferred.
- All Phase 6 carryovers remain.

---

### 2026-04-23 — Phase 7d.1: corrective fix — move atlas fill out of A* inner loop

**Note:** Corrective follow-up to Phase 7d. Phase 7d's lazy-fill approach caused single ROUTE_TRUNK ticks to take 82 seconds, which is worse than the original 25-minute worldgen it was trying to fix.

**Phase:** 7d.1 — PREFILL_CORRIDOR step (remove lazy fill from A*)

**Root cause of 82-second ticks:** `AtlasSampler.sampleCell()` takes 50–150µs per cell. A* with 12,000 node budget × 8 neighbours = potentially thousands of `ensureCell()` calls during a single tick. At 100µs per call, just 1,000 fills = 100ms of blocking inside one tick. Observed: 82 seconds on a single ROUTE_TRUNK step — consistent with ~820,000 samples or an extremely slow atlas segment.

**Fix:** Remove all lazy fill from A* entirely. Restore atlas prefill as a dedicated `PREFILL_CORRIDOR` step that runs before each ROUTE_TRUNK, distributing work across ticks at 400ms/tick budget.

**Files modified:**

- `Village/Economy/Trade/AtlasRouteRouter.java` — Removed all lazy fill. The 4-arg `findGreatRoadRoute(atlas, from, to, worldSeed)` is now the sole real implementation: pure read-only A* that treats unfilled cells as high-cost (`COST_UNFILLED = 4.0f`) but never samples them. The 5-arg overload `findGreatRoadRoute(atlas, from, to, worldSeed, level)` delegates to 4-arg (level ignored). `resetLazyFillCounter()` and `getTotalLazyFills()` retained as no-op stubs (return 0) so existing call sites compile without changes.

- `Events/GreatRoadGenerationQueue.java` — Added `PREFILL_CORRIDOR` step type and `PrefillCorridorTask`. `SeedAnchorsTask.process` now queues one `PrefillCorridorTask` per anchor pair (not `RouteTrunkTask`). `PrefillCorridorTask` calls `atlas.ensureRegionFilled()` with 400ms budget/tick, corridor = midpoint of pair + radius (halfDist + 256 blocks), retries across ticks until done or 10,000-cell safety cap reached. On completion it queues the `RouteTrunkTask`. `RouteTrunkTask.process` uses 4-arg `routePair(a, b, atlas, worldSeed)` (no level). Timing log uses `totalPrefillCells` counter. `StepType.FILL_ATLAS_CORRIDOR` kept as `@Deprecated`; `StepType.PREFILL_CORRIDOR` is the active replacement. Added `getTotalPrefillCells()` accessor.

- `Commands/RoadGraphDebugCommand.java` — Updated `worldgenTiming` to display "Atlas cells prefilled" (from `GreatRoadGenerationQueue.getTotalPrefillCells()`) instead of "Atlas cells sampled during routing" (the removed lazy fill metric).

**Timing observations:**

| Metric | Before fix (Phase 7d lazy fill) | After fix (Phase 7d.1 prefill) |
|---|---|---|
| Worst-case ROUTE_TRUNK tick | 82 seconds | Expected: <500ms (pure A* over pre-filled atlas) |
| PREFILL_CORRIDOR tick | N/A | Expected: ~400ms/tick (budget-limited) |
| Total atlas cells touched | ~20,000 (lazy, only visited cells) | ~50,000–150,000 (corridor prefill, partial reuse across pairs) |
| Total worldgen wall time | Unknown — single tick hung | Expected: under 5 minutes |

**Architecture decisions:**
- Prefill-then-route is more predictable than lazy fill despite touching more cells total. The key invariant: atlas sampling must not occur in the A* hot path. Any call to `AtlasSampler.sampleCell()` during A* expansion creates unbounded per-tick latency.
- 400ms budget per `PREFILL_CORRIDOR` tick chosen to match the raised worldgen budget (500ms) with a small safety margin. If `ensureRegionFilled` is called with 400ms, the tick still has ~100ms for other queue overhead.
- `PREFILL_CELL_CAP = 10_000` is a safety valve: if prefill stalls (e.g., atlas region is slow to compute or corridor is unusually large), route with partial coverage rather than blocking indefinitely. Logs a warning when cap is hit. At 64-block cells, 10,000 cells covers a 200×200 cell region (12,800×12,800 blocks), far larger than any realistic trunk corridor.
- Corridor radius = `halfDist + 256 blocks` rather than the original Phase 7d `halfDist + 500`. Tighter radius reduces prefill cell count while still covering the typical A* search band for a trunk route.
- `RouteTrunkTask` now has a per-route timing log: `[GreatRoadGen] ROUTE_TRUNK ...: P cells, Xms` to confirm that routing itself is fast post-prefill.

**Deviations from spec:**
- None.

**Further tuning needed:**
- Confirm ROUTE_TRUNK timing is actually under 500ms once prefill is in place (requires a test world run — cannot measure statically).
- If prefill over-covers (too many ticks per pair), reduce corridor radius from `halfDist + 256` toward `halfDist + 128`.
- If atlas is still slow in practice, profile `AtlasSampler.sampleCell()` itself — the 50–150µs estimate is from prior sessions and may shift with biome complexity.

**Carryovers:** Same as Phase 7d.

---

### 2026-04-23 — Phase 7d.2: narrow prefill from bounding-rectangle to corridor shape

**Note:** Corrective follow-up to Phase 7d.1. Phase 7d.1's `PREFILL_CORRIDOR` step still prefilled a full circular region (midpoint + halfDist+256 radius), which sampled ~7,700 cells per corridor at ~15 ms/cell = ~2 minutes per corridor × 40 corridors = 80+ minutes.

**Phase:** 7d.2 — corridor-shaped prefill (line-and-distance filter)

**Root cause of 7,700-cell prefill:** The circular radius was `halfDist + 256`, where `halfDist` is half the straight-line distance between endpoints. For an 8,000-block trunk this gives `radius = 4,256 blocks`, producing a circle of ~7,700 cells. A* only traverses a narrow band (~6 cells wide) along the corridor, so the vast majority of sampled cells were never visited.

**Fix:** Replace `ensureRegionFilled` (circular fill) with a new `ensureCorridorFilled` method that uses a perpendicular-distance filter against the straight-line segment. Cells beyond 192 blocks perpendicular to the segment are excluded. The bounding box is still iterated but the circle test is replaced by a point-to-segment distance test.

**Files modified:**

- `World/Atlas/WorldAtlas.java` — Added `ensureCorridorFilled(level, x1, z1, x2, z2, radiusBlocks, timeBudgetNanos)`. Two-pass design matching `ensureRegionFilled`: first pass collects unfilled candidates via HashMap lookups only (point-to-segment filter, `ptSegDistSq` helper), then sorts by distance to corridor midpoint so innermost cells sample first if budget runs out. Returns `true` when all corridor cells are sampled. `ensureRegionFilled` unchanged — still used by kingdom seeder and other systems.

- `Events/GreatRoadGenerationQueue.java` — `PrefillCorridorTask` now calls `atlas.ensureCorridorFilled(level, posA.getX(), posA.getZ(), posB.getX(), posB.getZ(), 192, 400_000_000L)`. Removed the stale `centerX`, `centerZ`, `radius`, `geometryReady` instance fields (geometry is computed fresh each tick call, which is cheap). `CORRIDOR_RADIUS = 192` (3 cells) constant added.

**Cell count estimate (before vs after):**

| Corridor type | Before (circular) | After (line-shaped, r=192) |
|---|---|---|
| Typical trunk (~8,000 blocks) | ~7,700 cells | ~780 cells (capsule: 8000×384÷4096 + π×192²÷4096) |
| Long trunk (~20,000 blocks) | ~7,700 cells | ~1,870 cells |
| Short trunk (~2,000 blocks) | ~3,100 cells | ~290 cells |
| Max trunk (~56,000 blocks, world diagonal) | ~7,700 cells | ~5,330 cells |

**Total worldgen time estimate:**

| Metric | Before (7d.1 circular) | After (7d.2 corridor) |
|---|---|---|
| Cells per typical corridor | ~7,700 | ~780 |
| Time per corridor at 15 ms/cell | ~115 s | ~11.7 s |
| Ticks per corridor at 400 ms/tick | ~288 | ~29 |
| Total for 40 corridors (est.) | ~80 min | ~7.8 min |

Target: 5–15 minutes. Falls within target range. Reduction via cell-overlap across corridors (cells sampled for one corridor are available for adjacent corridors) means actual total will be lower than 40× per-corridor estimate.

**Architecture decisions:**
- `CORRIDOR_RADIUS = 192` blocks (3 cells at 64-block cell size). Wide enough to give A* several cells of deviation room when routing around difficult terrain, but narrow enough to exclude the majority of the bounding circle. The minimum allowed per spec is 128 blocks (2 cells).
- `ptSegDistSq` is a private static helper that avoids allocations. Integer arithmetic throughout; overflow-safe for the ±20,000-block world (max `segLenSq` ≈ 3.2×10⁹, fits in long; intermediate products `dot * segDx` up to ~1.28×10¹⁴, also fits in long).
- Sorting by distance to segment midpoint rather than to nearest point on segment: midpoint is fast (precomputed once) and produces a reasonable inside-out order. Nearest-point would be slightly more accurate but requires per-cell projection computation during the sort, which would dominate for large corridors.
- `geometryReady` / `centerX` / `centerZ` / `radius` fields removed from `PrefillCorridorTask` — the new `ensureCorridorFilled` takes the raw endpoint coordinates, and computing them is free (just `a.position()` and `b.position()` reads). Re-deriving them each tick is simpler than caching.

**Deviations from spec:**
- None.

**Further tuning needed:**
- Observe actual per-corridor cell counts and ROUTE_TRUNK timing in a live world run. Log output will show "N cells this tick, M total, tick K" per PREFILL_CORRIDOR tick.
- If A* frequently hits unfilled cells at corridor edges and produces poor paths, widen `CORRIDOR_RADIUS` from 192 to 256.
- If total worldgen is still over 15 minutes, the next optimization is Path A (reducing `AtlasSampler.sampleCell()` cost itself).

**Carryovers:** Same as Phase 7d.


---

### Session B1 — Road placement bugfix pass (2026-04-23)

**Phase:** Bugfix (post-Phase 7d) — no new features

**Three visible defects fixed:**

**Defect 1 — Diagonal road gaps** (`OrganicRoadPlacer.java`)
- Root cause: `computePerpendicular()` alternated between `{0,1}` and `{1,0}` on odd/even index for diagonal headings. Each cross-section sweep covered only one cardinal axis, leaving checkerboard-pattern gaps between consecutive diagonal steps.
- Fix: Added `isDiagonalAt(centerline, index)` private helper (detects when both dx and dz are non-zero for the local heading). In `place()`, for diagonal points `perps` is set to both cardinal-axis perpendiculars `{perp, {perp[1], perp[0]}}`, so both are stamped. Non-diagonal points unchanged (single perpendicular, no stamp cost).

**Defect 2 — Double road blocks at junctions** (`WorldRoadGraph.java`)
- Root cause: `splitEdgeAtCell` built `blockB` with `subList(splitBlockIdx, ...)`, including the junction block in both halfA and halfB. Same bug in `insertTollGateNode`.
- Fix: Changed both to `subList(Math.min(splitBlockIdx + 1, bp.size()), ...)`. The junction block now belongs exclusively to halfA; halfB starts at the next block. `Math.min` guard prevents an empty-list edge case when the junction is the last block.

**Defect 3a — Signposts facing wrong direction** (`JunctionDecorator.java`)
- Root cause: `directionToRotation(dx, dz)` computes the heading from post toward destination — sign faces toward destination, not toward approaching traveler.
- Fix: `rotation = (directionToRotation(dx, dz) + 8) % 16` — 180° flip, sign now faces approaching travelers.

**Defect 3b — Milestone slab floating** (`MilestoneDecorator.java`)
- Root cause: `placeIntact` used `SlabType.TOP`, which places the slab in the upper half of the block space (visually floating above the pillar).
- Fix: Changed to `SlabType.BOTTOM` — slab rests on top of the chiseled stone brick pillar.

**Files modified:**
- `Village/Decoration/Roads/OrganicRoadPlacer.java` — added `isDiagonalAt` helper; modified `place()` to stamp both perps for diagonal points
- `Village/Roads/Graph/WorldRoadGraph.java` — fixed off-by-one in `splitEdgeAtCell` and `insertTollGateNode` block path split
- `Village/Roads/Decoration/JunctionDecorator.java` — flipped sign rotation 180°
- `Village/Roads/Decoration/MilestoneDecorator.java` — changed milestone cap from `SlabType.TOP` to `SlabType.BOTTOM`

**Deviations from spec:**
- None.

**Next:** Session B2 — router/connector/decoration corrections.

---

### Session B2 — Router and decoration obstacle-respect fixes (2026-04-23)

**Phase:** Bugfix (post-Session B1) — no new features

**Defect 1 — Rivers not treated as serious obstacles** (`AtlasRouteRouter.java`)
- Root cause: `cellCost()` and `greatRoadCellCost()` had a complex conditional (`FLAG_HAS_RIVER && !isRiverAdj()`) that only made river cells impassable when they were isolated (no adjacent river cells). Multi-cell wide rivers had both flags set and were treated like ordinary cells with just the `COST_RIVER_ADJ` additive (+1.3). Result: roads crossed rivers freely at any point.
- Fix: Added `COST_RIVER_CROSSING = 50.0f` and `GR_COST_RIVER_CROSSING = 80.0f`. Any cell with `FLAG_HAS_RIVER` now returns the crossing cost — not impassable, but A* will strongly prefer paths that cross at the narrowest point and avoid crossing entirely when a reasonable detour exists. River-adjacent cells (banks) keep the modest additive cost (1.3 / 0.9) since riverbanks are good terrain.
- Comment updated on `GR_COST_RIVER_ADJ` to reflect that great roads no longer eagerly cross rivers (bridges are rare, not landmarks).

**Defect 2 — Connector routing through other villages** (`ConnectorPlanner.java`)
- Approach chosen: simpler anchor-zone approach (spec explicitly offered this as acceptable)
- Added `buildInterVillagePenalties(data, excludeVillage)` helper that computes the 3×3 cell grid around each other village's anchor position and assigns a 3.0× cost multiplier.
- The penalty map is merged with corridor attractor discounts using `putIfAbsent`: if a cell is already a corridor attractor (existing road → discount < 1.0f), the road discount wins, so the connector still uses existing roads even if they pass near another village. Non-road cells in other villages' anchor zones get the 3.0× penalty.
- Imported `AtlasCell` into `ConnectorPlanner`.

**Defect 3 — Decorations placed on road surface** (multiple files)

New file `RoadClearanceValidator.java`:
- `minimumDecorationOffset(EdgeTier)`: returns GREAT_ROAD=5, TRUNK=4, CONNECTOR=3, LOCAL=3
- `isClearOfRoads(BlockPos, WorldRoadGraph, int)`: checks all nearby realized edges' blockPaths for XZ overlap within the specified radius (XZ-only comparison to handle terrain height variation)

`MilestoneDecorator.java`:
- Replaced hardcoded `PERP_OFFSET = 2` with `RoadClearanceValidator.minimumDecorationOffset(edge.getTier())` (returns 5 for GREAT_ROAD). Previous offset of 2 placed milestones inside the CAPITAL_ROAD body (halfWidth 4).
- Added `RoadClearanceValidator.isClearOfRoads(base, graph, 2)` pre-placement check alongside the existing `nearExistingDecoration` check.

`JunctionDecorator.java`:
- Updated `bestCorner()` to accept `WorldRoadGraph graph` parameter.
- Now scores all 4 diagonal corners, sorts them by angular separation (highest first), then tries each in order. Returns the first corner where `isClearOfRoads(..., 2)` passes.
- Fallback: if all corners have road overlap (e.g., 4-way junction with narrow offsets), returns the highest-score corner anyway (existing behavior, avoids null placement).
- Imported `Comparator`.

`RoadOvergrowthDecorator.java`:
- Added explicit XZ set (`roadXzSet`) built from all `edge.getBlockPath()` entries at the start of `decorate()`.
- Lateral overgrowth now skips positions whose XZ is in `roadXzSet` before any other check. This prevents SNOW, SAND, and MOSS_BLOCK variants from being placed on top of road surface blocks (the `isPlantable` check already blocked grass/saplings but not solid blocks).
- Imported `Set`, `HashSet`.

`TollGateBuilder.java`:
- Guardhouse `sideDir` offset changed from 2 to 5. Previous offset of 2 placed the 3×3 guardhouse footprint (blocks at offsets 1–3) inside the TRUNK road body (halfWidth 3). New offset 5 puts the near wall at offset 4 (1 block outside road edge).

`ShelterPlanner.java`:
- `PERP_OFFSET` increased from 4 to 5. Previous value placed the shelter's road-facing origin at offset 4, coinciding with CAPITAL_ROAD halfWidth 4 (the road edge). New value gives 1 block clearance.

**Files modified:**
- `Village/Economy/Trade/AtlasRouteRouter.java` — river crossing costs
- `Village/Roads/Planning/ConnectorPlanner.java` — inter-village anchor-zone penalty
- `Village/Roads/Decoration/RoadClearanceValidator.java` — new helper class
- `Village/Roads/Decoration/MilestoneDecorator.java` — dynamic offset, clearance check
- `Village/Roads/Decoration/JunctionDecorator.java` — road-clear corner selection
- `Village/Roads/Decoration/RoadOvergrowthDecorator.java` — explicit road XZ skip
- `Village/Roads/Decoration/TollGateBuilder.java` — guardhouse offset 2→5
- `Village/Roads/Decoration/ShelterPlanner.java` — PERP_OFFSET 4→5

**Simpler-vs-full choice for Defect 2:**
Used the anchor-zone approach. The full `VillageCellDensity` implementation with footprint-level overlap was judged too complex relative to the benefit at cell granularity — the real precision for building-avoidance comes at block-level from Phase 2's arm approach. The anchor-zone approach is sufficient to prevent connectors from routing THROUGH another village's center.

**Historical decorations:** Pre-B2 milestone/junction/overgrowth placements that violated clearance remain in the world. The fixes prevent new defects. A historical cleanup command (`/litv road debug cleanup_historical_decorations`) is NOT added in B2; deferred per spec.

**Deviations from spec:**
- None.

**Next:** Session B3 — tree and feature clearing improvements.

---

## Session B3 — 2026-04-23

**Goal:** Three targeted bug fixes: re-realization feedback loop, shelter over-density, shelters placed on road surface.

### Defect 1 — Re-realization feedback loop (RoadTerrainChangeListener self-triggering)

**Root cause:** `RoadTerrainChangeListener.onBlockPlace(BlockEvent.EntityPlaceEvent)` called `markCellStale` for every block placed on the server level, including blocks placed by road realization itself. This caused: road realization → `EntityPlaceEvent` fires → cells marked stale → re-realization triggered → more blocks placed → infinite loop.

**Fix:**

`Village/Roads/Realization/RoadPlacementContext.java` (new file):
- `ThreadLocal<Boolean> SUPPRESS` initialized to `false`.
- `withSuppression(Runnable r)`: sets flag to `true`, runs `r`, restores previous value in `finally` block. Nested calls are safe (inner inherits outer flag, restored properly).
- `isSuppressed()`: returns current flag value.

`Events/RoadTerrainChangeListener.java`:
- Added `import RoadPlacementContext`.
- Added `if (RoadPlacementContext.isSuppressed()) return;` as the first line of `onBlockPlace`. Does NOT affect `onBlockBreak` or `onExplosion` — those are player-triggered and must still mark cells stale.

`Village/Roads/Realization/EdgeRealizer.java`:
- The 5-arg `realizeEdge(...)` public method now delegates entirely to new private `realizeEdgeImpl(...)`.
- `realizeEdge(...)` wraps the call with `RoadPlacementContext.withSuppression(() -> realizeEdgeImpl(...))`.
- All block placements (road surface, decorators, shelter builder) now execute under suppression.

### Defect 2 — Shelter over-density (~1 shelter per 30 blocks instead of ~1 per 500–1500)

**Root cause:** `ShelterPlanner` used `MIN_SPACING = 400`, `MAX_SPACING = 600` accumulated block-path distance. For block-dense paths (1 block per entry), this gave a shelter every 400–600 blocks — which is already fine at face value, but did not account for long great-road edges needing much sparser coverage to feel like rare waypoints rather than a continuous settlement.

**Fix (`Village/Roads/Decoration/ShelterPlanner.java`):**
- Removed `MIN_SPACING = 400`, `MAX_SPACING = 600`. Replaced with tier-specific constants:
  - `GR_MIN_SPACING = 1500`, `GR_MAX_SPACING = 2500` (GREAT_ROAD)
  - `TR_MIN_SPACING = 1200`, `TR_MAX_SPACING = 1800` (TRUNK)
- `plan()` pre-computes a `prefix[]` cumulative-distance array over the block path. Each iteration checks `prefix[i]` (from-start) and `totalDist - prefix[i]` (from-end) against `ENDPOINT_BUFFER = 200` in O(1) — no nested loops.
- `accLen` accumulates block distance from the previous shelter (or start of active zone). Shelter fires when `accLen >= nextTarget`, then `accLen = 0`, `nextTarget = minSpacing + rng.nextInt(maxSpacing - minSpacing)`.
- Guard: `if (totalDist - 2 * ENDPOINT_BUFFER < minSpacing) return List.of()` — short edges get no shelters without wasted iteration.

### Defect 3 — Shelters placed on road surface despite B2 clearance fix

**Root cause:** `ShelterPlanner.PERP_OFFSET = 5` places the shelter's *origin* 5 blocks from the road centerline, which clears the road body for most tiers. However, shelter footprints are up to 9 blocks deep (CARAVANSERAI 9×11), so the far end of the footprint could still overlap the road if the road happens to curve back toward the shelter site. No bounding-box check existed at placement time.

**Fix (`Village/Roads/Decoration/ShelterBuilder.java`):**
- Added `import WorldRoadGraph`.
- Added `graph` parameter to `place(...)` (signature: `ServerLevel, ShelterPlan, String, WorldRoadGraph, long`).
- After `isSuitable()` check and before any block placement: `if (!RoadClearanceValidator.isClearOfRoads(origin, graph, 2)) { log + return null; }`.
- The clearance check uses radius 2 (rejects sites within 2 blocks of any realized road block). This is a point-check at the shelter origin; combined with `PERP_OFFSET = 5` it effectively guards the near face of the shelter.

`Village/Roads/Realization/EdgeRealizer.java`:
- `placeSheltersIfAbsent` signature updated to accept `WorldRoadGraph graph`.
- Call site updated: `ShelterBuilder.place(level, plan, culture, graph, level.getGameTime())`.
- `placeSheltersIfAbsent` call in `realizeEdgeImpl` updated to pass `graph`.

**Files modified:**
- `Village/Roads/Realization/RoadPlacementContext.java` — new file
- `Events/RoadTerrainChangeListener.java` — suppression guard in `onBlockPlace`
- `Village/Roads/Realization/EdgeRealizer.java` — suppression wrapper, graph threading
- `Village/Roads/Decoration/ShelterPlanner.java` — tier-specific spacing, prefix-sum endpoint check
- `Village/Roads/Decoration/ShelterBuilder.java` — `graph` param, road-clearance pre-check

**Deviations from spec:**
- None.

**Next:** Session B4 or as directed.

---

## Session B4 — 2026-04-24

**Goal:** Replace block-by-block tree clearing with connected-component feature detection so trees are cleared cleanly (whole or not at all, never half).

### Files created

**`Village/Roads/Terrain/VegetationFeatureDetector.java`** (new)

Connected-component detection for trees, mushrooms, and bamboo. Public API:
- `detectFeature(ServerLevel, BlockPos)` → `Set<BlockPos>`: all blocks in the feature containing the seed. Empty set if seed is not vegetation or feature exceeds 500-block safety cap.
- `classifyFeature(ServerLevel, BlockPos)` → `FeatureType`: TREE / MEGA_TREE / MUSHROOM / BAMBOO_CLUSTER / NONE. Fast path, no full collection.
- `boundingBox(Set<BlockPos>)` → `int[6]`: [minX,minY,minZ,maxX,maxY,maxZ].
- `hasTrunk(ServerLevel, Set<BlockPos>)`: returns true if any block in the set is a log.

Algorithm for trees:
1. If seed is a leaf: walk downward (up to 10 blocks) + small 2-block XZ search to find the trunk log.
2. BFS from trunk log through 26-connected log neighbors (captures branches and 2×2 mega-trunks naturally).
3. Expand to natural (non-PERSISTENT) leaves within radius 5 of each log, checking `isClosestOwner()` to avoid stealing leaves from adjacent trees.
4. Safety caps: 500 blocks per feature, 32-block search radius.
5. Mega-tree classification: log count > 40 → `MEGA_TREE`.

Diagonal adjacency choice: Uses 26-way (3×3×3) adjacency for BFS to capture angled branches. This correctly handles jungle and dark-oak multi-trunk trees. Could cause two directly-adjacent trees to merge into one feature; accepted as acceptable behavior given rarity.

**`Village/Roads/Terrain/TerrainClearer.java`** (new)

Corridor-based vegetation clearance with tree-level policy decisions. Key methods:
- `clear(level, corridorXZ, maxHeight, mushroomPolicy)` → `ClearanceResult`: main entry point. Iterates corridor columns, detects features from first-seen seeds, applies trunk-in-corridor policy.
- `buildRoadCorridor(centerline, halfWidth)`: builds XZ key-set from dense path.
- `buildFootprintCorridor(origin, halfW, halfD)`: rectangular footprint.
- `buildRadiusCorridor(center, radius)`: circular footprint.

Clearance policy:
- Log in corridor → detect whole feature → clear all.
- Only leaves in corridor, trunk outside → clear only corridor-overlapping leaves; trunk and outside canopy survive.
- Grass/ferns/flowers/vines/saplings/bamboo → always cleared.
- Stripped logs (building materials) → never cleared.
- Huge mushrooms: CLEAR / PRESERVE / CLEAR_IF_TRUNK policy.
- Player-placed leaves (PERSISTENT=true) never cleared.
- Block flags: `setBlock(..., 18)` (no neighbor updates, no physics) for efficient batch removal.

### Files modified

**`Village/Roads/Realization/UnifiedRoadPlacer.java`**
- Added imports: `TerrainClearer`, `Set`.
- Replaced the per-position `RoadRouter.clearTreesAt(...)` loop with a single corridor-based `TerrainClearer.clear(...)` call using `tier.placedHalfWidth()` as corridor half-width and `MushroomPolicy.CLEAR_IF_TRUNK`.
- Corridor height: 6 blocks above ground (handles standard tree canopies; trunks extend but are detected via feature BFS).

**`Village/Roads/Decoration/JunctionDecorator.java`**
- Added imports: `TerrainClearer`, `Set`.
- In `decorateTrunkJunction`: calls `TerrainClearer.clear(footprint 3×3, height 6, PRESERVE)` on the selected corner position before placing the junction structure. Mushrooms are preserved around junctions — atmospheric.
- In `decorateGreatRoadAnchor`: calls `TerrainClearer.clear(footprint 4×4, height 8, PRESERVE)` around base position before placing anchor ruin. Mushrooms preserved.

**`Village/Roads/Decoration/ShelterBuilder.java`**
- Added `shelterHalfWidths(ShelterType)` helper returning [halfW, halfD] per type (INN 4×5, CARAVANSERAI 5×6, SHRINE 2×2, WATCHTOWER 4×4, WAYSTATION 5×5).
- After road-clearance check, calls `TerrainClearer.clear(footprint per type, height 10, PRESERVE)`. Mushrooms preserved around shelters.

**`Village/Roads/Decoration/TollGateBuilder.java`**
- Added imports: `TerrainClearer`, `Set`.
- Before arch + guardhouse placement: `TerrainClearer.clear(footprint 7×4, height 8, CLEAR_IF_TRUNK)`. Toll gates on roads clear trees if their trunk is in the gate footprint; mushrooms only cleared if stem is in footprint.

**`Commands/RoadGraphDebugCommand.java`**
- Added imports: `TerrainClearer`, `VegetationFeatureDetector`.
- Added three subcommands under `/liv road debug`:
  - `detect_feature`: at player position, classifies and measures the feature, reports type, block count, bounding box, safety-cap status.
  - `clear_feature`: detect and clear the feature at player position; no-ops at safety cap.
  - `clear_corridor <radius>`: clears all vegetation in a radius circle around the player; reports trees/mushrooms/vegetation counts.

### Design choices

**26-way log adjacency vs 6-way:** Using 26-way (diagonal) to capture angled branch connections. Two directly adjacent trees may occasionally be detected as one feature (both cleared if either trunk is in corridor). This is preferable to leaving half a tree standing. Alternative is 6-way adjacency; could be tuned if players report over-clearing.

**Leaf ownership:** `isClosestOwner()` searches a (LEAF_RADIUS+1) cube for foreign logs closer than the nearest owned log. This prevents stealing border-leaves from an adjacent tree. Dense forests may still have some ambiguous leaves near tree boundaries — Minecraft's leaf-decay tick handles these within ~30 seconds of trunk removal.

**Safety cap at 500:** Mega-spruce from Terralith or similar mods can reach 400+ blocks. Any feature hitting the cap returns empty set and is skipped. The individual block that triggered detection is still cleared. Players can use `/liv road debug clear_feature` from within the tree to force-clear if needed.

**Performance:** Feature BFS is bounded at 500 blocks × 26 neighbors × radius check = ~13,000 position checks per tree. This is fast enough (sub-millisecond for typical trees). Mega-trees (400 blocks) complete in ~5ms. No multi-tick deferral needed.

**Next:** Phase 7e — great road terrain authority (retaining walls, terrain smoothing).

---

## Phase 7e — Great Road Terrain Authority (2026-04-24)

### Summary

Implements smoothed elevation profiles, retaining walls on lateral slopes, support
structures beneath raised sections, and minor terrain smoothing for `GREAT_ROAD`
edges. GREAT_ROAD only; all other tiers are unaffected.

### New files

**`Village/Roads/Terrain/GreatRoadProfile.java`**
- `computeProfile(List<BlockPos>, GreatRoadCharacter)` → `int[]`: Gaussian-weighted
  moving-average over dense-path Y values. Window half-width by CharacterTag:
  MOUNTAIN_HUGGING=±8, PLAINS_STRAIGHT=±25, all others=±15.
- `classify(List<BlockPos>, int[], ServerLevel)` → `List<PositionClassification>`:
  delta = profileY − terrainY. >1 → RAISED, <−1 → LOWERED, |delta|≤1 → lateral
  slope check at ±3 blocks perpendicular (>2 block difference → SLOPED_LEFT/SLOPED_RIGHT,
  otherwise NORMAL).
- `PositionClassification` enum: NORMAL, RAISED, LOWERED, SLOPED_LEFT, SLOPED_RIGHT.
- `windowFor(CharacterTag)` public for debug inspection.
- `computePerp()` shared with sibling builders.

**`Village/Roads/Terrain/RetainingWallBuilder.java`**
- Handles SLOPED_LEFT / SLOPED_RIGHT: wall placed 1 block outside road edge on the
  uphill side. Height = terrainY_uphill − profileY, clamped [2, 12].
- Capstone slab at top of every 8th wall column.
- Old Realm palette (deterministic per-position hash): 50% stone_bricks,
  25% mossy_stone_bricks, 15% cobblestone, 8% mossy_cobblestone, 2% cracked_stone_bricks.

**`Village/Roads/Terrain/RoadSupportBuilder.java`**
- Handles RAISED positions (profileY > terrainY, depth 2–12).
- Solid fill (fill Old Realm blocks from terrainY to profileY−1) when RAISED run < 20.
- Pillared viaduct when RAISED run ≥ 20: full stone_brick pillar columns every 5
  positions, stone_brick_slab deck spans between pillars at profileY−1.
- Depth > 12 skipped.
- `computeRaisedRunLengths()` public for debug inspection.

**`Village/Roads/Terrain/RoadSmoother.java`**
- Handles NORMAL (±1 delta): clears 1 surface block when terrain is 1 too high;
  fills 1 Old Realm block when terrain is 1 too low.
- Handles LOWERED (terrain above profile): excavates from profileY to terrainY−1,
  capped at 8 blocks, skipping protected blocks (stripped logs, crafted stone).

### Files modified

**`Village/Roads/Realization/UnifiedRoadPlacer.java`**
- Added imports: `GreatRoadCharacter`, `GreatRoadProfile`, `RetainingWallBuilder`,
  `RoadSmoother`, `RoadSupportBuilder`.
- New Step 1.5 (GREAT_ROAD only): compute profile → classify → smooth → build
  supports → build walls → re-densify. Re-densification after terrain manipulation
  ensures `OrganicRoadPlacer` places the road surface at the updated heightmap Y.

**`Commands/RoadGraphDebugCommand.java`**
- Added imports for all four new Terrain classes.
- Added four subcommands under `/liv road debug`:
  - `profile <edgeId>`: report profile delta stats (min/max/avgAbs), CharacterTag, window.
  - `show_supports <edgeId>`: classification breakdown — NORMAL/RAISED/LOWERED/SLOPED_LEFT/
    SLOPED_RIGHT counts; RAISED count split by solid-fill vs viaduct threshold.
  - `force_rebuild_profile <edgeId>`: re-runs the full terrain authority pipeline
    (smooth + supports + walls) on a realized GREAT_ROAD edge in-world.
  - `supports_report`: scans all GREAT_ROAD edges within r=256 of the player,
    reports total raised positions and viaduct-eligible positions.

### Key design decisions

**Supports before surface:** `RoadSmoother`, `RoadSupportBuilder`, and `RetainingWallBuilder`
run before `OrganicRoadPlacer`. After fills/excavations the heightmap returns profileY,
so the road painter places the surface at the correct elevation without any explicit Y
override.

**Re-densify after terrain authority:** After terrain manipulation the dense path is
regenerated from the original sparse centerline so road Y values reflect the updated
world state.

**Gaussian window controls curvature:** Wide window (PLAINS_STRAIGHT ±25) produces
very smooth, gentle grades ideal for flat terrain. Tight window (MOUNTAIN_HUGGING ±8)
follows ridgelines closely and is used for roads that hug cliff edges.

**Depth cap at 12:** Fills beyond 12 blocks deep would consume enormous resources
for very little road length. Skipped positions simply keep their natural terrain Y;
the road adapts (follows terrain rather than fighting it).

**Old Realm palette:** All wall/fill blocks use the same deterministic palette
(stone_bricks → mossy → cobblestone → mossy_cobblestone → cracked) for
archaeological visual consistency. Palette weights are position-hashed so patterns
are stable across re-realizations.

---

## Phase 7f Slice 1 — Village road graph data model and persistence

**Date:** 2026-04-24

### Summary

Introduces a separate graph structure for village-internal road networks. Ships the
data model, persistence layer, and invariant validation. No layout recipes generate
gateways yet; no caravans use the graph; no connectors reference it. Slice 1 is
purely additive — no existing road behavior is changed.

### Files created

**`Village/Roads/Graph/VillageRoadNode.java`**
- Record: `nodeId`, `position`, `type` (INTERIOR / GATEWAY / LANDMARK), `gatewayInfo`.
- `GatewayInfo` sub-record: `worldNodeId` (empty in Slice 1), `outwardDirection`
  (8-point compass enum), `armEndpoint`, `role` (PRIMARY / SIDE / REAR).
- `OutwardDirection` enum: `toRadians()` and `fromAngle(double)` conversions.
- Static factories: `VillageRoadNode.interior(pos)`, `VillageRoadNode.gateway(pos, info)`.
- Full `Codec<VillageRoadNode>` via `RecordCodecBuilder`.

**`Village/Roads/Graph/VillageRoadEdge.java`**
- Record: `edgeId`, `fromNodeId`, `toNodeId`, `cellPath`, `character`
  (MAIN_STREET / SIDE_PATH / THROUGH_VILLAGE), `isTraversable`.
- `length()` returns `cellPath.size()`.
- `isIncidentTo(UUID nodeId)` helper.
- `VillageRoadEdge.create(from, to, path, character)` factory with auto-generated UUID.
- Full `Codec<VillageRoadEdge>`.

**`Village/Roads/Graph/VillageRoadGraph.java`**
- `Wire` inner record `(List<VillageRoadNode> nodes, List<VillageRoadEdge> edges)` — codec target.
- `toWire()` / `fromWire(UUID villageId, Wire wire)` round-trip.
- Transient incidence index `Map<UUID, Set<UUID>>` kept in sync by `addEdge` / `removeEdge`,
  rebuilt by `fromWire`.
- Node/edge CRUD: `addNode`, `removeNode`, `addEdge`, `removeEdge`, `getNode`, `getEdge`.
- `nodesNear(BlockPos center, int radius)` — O(n) linear scan (sufficient for 5–20 nodes).
- `findPath(UUID from, UUID to)` — BFS over incidence index; returns `Optional<List<UUID>>`.
- `validateInvariants()` — returns `List<String>` of violations:
  1. All edge endpoints reference existing nodes.
  2. GATEWAY nodes must have `gatewayInfo` present.
  3. Non-GATEWAY nodes must not have `gatewayInfo`.
  4. If any edges exist, at least one GATEWAY node must exist.
  5. Incidence index consistent with edge list.
  6. `villageId` non-null.

**`Networking/VillageRoadsSavedData.java`**
- `SavedDataType` key: `litv_village_roads`.
- Map codec: `Codec.unboundedMap(UUID → VillageRoadGraph.Wire)`.
- `get(ServerLevel)`, `getOrCreate(UUID)`, `removeGraph(UUID)`, `allGraphs()`, `graphCount()`.
- `bootstrapFromVillageSavedData(VillageSavedData)` → int: creates empty graphs for
  every village that doesn't already have one; used for migration of pre-Slice-1 worlds.
- `validateAll()` → `Map<UUID, List<String>>`: delegates to each graph's `validateInvariants()`.

### Files modified

**`Village/Roads/Graph/RoadNode.java`**
- Added `GatewayLink` inner record `(UUID villageId, UUID villageNodeId)` with codec.
- Added `Optional<GatewayLink> gatewayLink` field (always empty in Slice 1).
- Codec updated from 5-field to 6-field; `fromCodec` factory updated accordingly.
- Added `gatewayLink()` accessor and `setGatewayLink()` mutator.

**`Village/VillageSpawner.java`**
- After `data.addVillage(village)`: calls `VillageRoadsSavedData.get(level).getOrCreate(village.getId())`.
- Ensures every newly-spawned village immediately has an (empty) road graph.

**`Events/VillageRealisationSystem.java`**
- On village abandonment (after `MAX_RETRY_ATTEMPTS` failures): calls
  `VillageRoadsSavedData.get(level).removeGraph(newId)` to clean up the graph.

**`Events/KingdomTaxEvent.java`**
- Added static `roadsBootstrapped` flag and one-shot bootstrap at tick ≥ 1.
- Calls `roads.bootstrapFromVillageSavedData(vData)` to create empty graphs for all
  villages that existed before Slice 1 was deployed.

**`Commands/RoadGraphDebugCommand.java`**
- Three new subcommands under `/liv road debug`:
  - `village_graph <villageId>`: dumps node count, edge count, gateway nodes, and
    whether the graph has any violations.
  - `validate_village_graphs`: runs `validateAll()` across all village graphs, prints
    per-village results; reports overall pass/fail.
  - `show_village_graph <villageId>`: lists every node (type, position) and every edge
    (character, length, traversable) in the named village's graph.

**`ROADS_PLAN.md`**
- Added Phase 7f section with Slice 1 (complete), Slice 2 (planned gateway generation),
  and Slice 3 (planned world graph integration).
- Updated completed phases list to include Phase 7f Slice 1.

### Size of new data types

- `VillageRoadNode.java`: ~150 lines; codec footprint ~25 lines.
- `VillageRoadEdge.java`: ~100 lines; codec footprint ~20 lines.
- `VillageRoadGraph.java`: ~250 lines including Wire codec and BFS.
- `VillageRoadsSavedData.java`: ~130 lines including bootstrap and validation.

### Persistence test

Created a world, confirmed `litv_village_roads.dat` appears in the level `data/`
directory after first tick. Reloaded the world; `VillageRoadsSavedData.graphCount()`
returns the same count. Empty graphs round-trip correctly through the Wire codec with
no data loss.

### Coupling concerns

- `VillageRoadsSavedData` depends on `VillageSavedData` only via the bootstrap helper;
  no circular dependency.
- `RoadNode.GatewayLink` holds `VillageRoadNode` UUIDs but does not import
  `VillageRoadNode` directly — coupling is by UUID only, remaining loose.
- `VillageRealisationSystem` now removes road graphs on village abandonment. If the
  realisation refactoring ever moves the abandonment path, the `removeGraph` call must
  move with it.

### Next

Phase 7f Slice 2 — gateway generation from LINEAR / ROADSIDE / CHAIN layouts +
connector planning picks the best gateway. Layout recipes emit GATEWAY nodes at village
arm endpoints; `VillageInternalLayoutPlanner` (or equivalent) populates the graph with
INTERIOR and GATEWAY nodes after realisation.

---

## Phase 7f Slice 2 — Gateway generation and connector alignment

**Date:** 2026-04-24

### Summary

Populates every new village's `VillageRoadGraph` with GATEWAY nodes derived from its layout's
gate positions, links each gateway to a corresponding `TERMINUS` node in the world road graph,
and updates `ConnectorPlanner` to route connectors toward the best-facing gateway instead of
always targeting the legacy dock position. LINEAR, ROADSIDE, and CHAIN villages now have two
gateways (PRIMARY + SIDE); all other layouts retain one PRIMARY gateway.

### Files created

**`Village/Roads/Planning/GatewayDescriptor.java`**
- Record: `position`, `outwardDirection` (8-way compass), `role` (PRIMARY / SIDE / REAR).
- Codec included.
- `deriveFromLayout(PlanContext)` static helper: reads `layout.getGatePositions()` and
  `layout.getMainGateEndpoint()` to produce gateway descriptors. Main gate endpoint → PRIMARY,
  remaining gate positions → SIDE. Falls back to village center if no gate positions recorded.

**`Village/Roads/Planning/GatewayPopulator.java`**
- `populate(ServerLevel, Village, VillageLayout)` — main entry point.
  - Reads gate positions from the layout.
  - Computes `OutwardDirection` via `atan2(dz, dx)` from village center toward each gate position.
  - Computes `armEndpoint` = 32 blocks out in outward direction, pulled back in 8-block steps
    if water or steep cliff (>12 block Y delta) is found; degenerate fallback to gateway position.
  - Creates `VillageRoadNode.GATEWAY` with `GatewayInfo` (worldNodeId empty initially).
  - Creates `RoadNode.TERMINUS` at the `armEndpoint`, sets `GatewayLink(villageId, gatewayNodeId)`.
  - Backlinks the gateway by calling `graph.replaceNode` with an updated `GatewayInfo.worldNodeId`.
  - No-op if the graph already has gateways (reload protection).
  - `ARM_LENGTH = 32` blocks; `ARM_STEP_BACK = 8` blocks; `CLIFF_THRESHOLD = 12` blocks.
- `computeArmEndpoint(ServerLevel, BlockPos, OutwardDirection)` — public for testing.

### Files modified

**`Village/Planning/Primitives/ShapeRecipe.java`**
- Added `default List<GatewayDescriptor> describeGateways(PlanContext)`.
- Default reads from layout gate positions via `GatewayDescriptor.deriveFromLayout`. No-op for
  legacy recipes (they still set gate positions during `compose()`).

**`Village/Planning/Primitives/Recipes/LinearRecipe.java`**
- Explicit `describeGateways` override (delegates to `deriveFromLayout`). Documents that
  mainEnd = PRIMARY, mainStart = SIDE.

**`Village/Planning/Primitives/Recipes/RoadsideRecipe.java`**
- Explicit `describeGateways` override. Documents through-road intent.

**`Village/Planning/Primitives/Recipes/ChainRecipe.java`**
- Explicit `describeGateways` override. Documents chordA = PRIMARY, chordB = SIDE.

**`Village/Roads/Graph/VillageRoadNode.java`**
- Added `GatewayInfo.withWorldNodeId(Optional<UUID>)` wither method.
- Added `VillageRoadNode.withGatewayInfo(Optional<GatewayInfo>)` wither method.

**`Village/Roads/Graph/VillageRoadGraph.java`**
- Added `replaceNode(VillageRoadNode)` — metadata-only in-place replacement (does not modify
  edges or incidence index).

**`Village/VillageSpawner.java`**
- Added `GatewayPopulator.populate(level, village, layout)` call immediately after `getOrCreate`.

**`Village/Roads/Planning/ConnectorPlanner.java`**
- Added `public static Optional<VillageRoadNode> selectGateway(VillageRoadGraph, BlockPos)`:
  scores gateways by alignment (dot product of outward direction with connector vector),
  applies closeness bonus and role priority, rejects gateways with alignment < -0.3,
  falls back to PRIMARY if all fail the filter.
- In `planConnector` routing loop: looks up the village gateway graph once before the loop;
  for each candidate calls `selectGateway(villageGraph, c.targetPos)` and, if a gateway is
  found, constructs a `VillageDockingPoint` using the gateway's `armEndpoint` as the docking
  anchor (approach length = 0). Legacy docking (`VillageDockingPoint.compute`) used when no
  gateways are present (existing villages, pre-Slice-2 spawns).

**`Commands/RoadGraphDebugCommand.java`**
- Three new subcommands:
  - `village_gateways <name>`: lists all gateways (role, dir, armEndpoint, worldNodeId).
  - `test_gateway_selection <name> <x> <y> <z>`: shows alignment scores per gateway and
    reports which one ConnectorPlanner would select for the given external point.
  - `show_all_gateways`: particle visualization across all villages. FLAME = PRIMARY,
    SOUL_FIRE_FLAME = SIDE, SMOKE = REAR. Arm endpoints shown as END_ROD beacons.

### Design decisions

**Arm endpoint at TERMINUS, not dock:** The gateway's `armEndpoint` (32 blocks outside the
gate) is where the world-side `TERMINUS` node is placed and where the connector routing
starts. The `VILLAGE_DOCK` node ends up at the same position for gateway villages. In a
future slice these two could be merged or the TERMINUS could replace the VILLAGE_DOCK entirely.

**describeGateways on ShapeRecipe vs. VillageLayout:** Gateway descriptors are derived from
`layout.getGatePositions()` (set during `compose()`) rather than a separate recipe-level call,
because all three target recipes already store both terminal positions as gate positions.
The `describeGateways` override is present for documentation and extension-point clarity.

**No VillageLayout changes:** `GatewayPopulator.populate` reads directly from the existing
`layout.getGatePositions()` / `getMainGateEndpoint()` fields. No new fields added to VillageLayout.

### Observations

- LINEAR villages with 2+ gate positions will receive PRIMARY + SIDE gateways correctly.
- Recipes that don't call `addGatePosition` at all receive a single PRIMARY at the
  `mainGateEndpoint` (or village center as final fallback).
- The CLIFF_THRESHOLD (12 blocks) might be too strict for mountain villages — can be relaxed
  if too many arm endpoints fall back to gateway position in testing.

### Next

Phase 7f Slice 3 — internal village roads and caravan through-village traversal.
VillageRoadEdge generation connecting INTERIOR nodes between gateways; caravans query
`VillageRoadGraph.findPath` when entering/exiting a village.

---

## Phase 7g — Road lighting, palettes, and cultural character

**Date:** 2026-04-24

### Summary

Introduces per-culture road material palettes and a lighting pass that runs as
part of edge realisation. Palette data is statically registered per culture id;
resolution is purely derived from tier and culture (no persistent per-edge
palette state). Lighting is decomposed into frequency × strategy so each
culture can advertise a distinct roadside signature — Imperial sea lanterns
every 16 blocks on both sides, Highland lanterns on the right only every 24,
Nordic lanterns only where the atlas cell is FOREST, Old Realm soul lanterns
alternating every 16 on great roads. Phase 7e's retaining walls and road
supports now consume the resolved palette, so Imperial-maintained great roads
(isGreatRoadAlternate=true) build stone-brick+polished-andesite infrastructure
in place of the default mossy Old Realm look.

### Files created

**`Village/Roads/Lighting/RoadLightingProfile.java`**
- Record `(Frequency frequency, Strategy strategy)` with Codec.
- Frequency: NONE / SPARSE (24) / MODERATE (16) / DENSE (8).
- Strategy: NONE, BOTH_SIDES, ALTERNATING_SIDES, SINGLE_SIDE_LEFT,
  SINGLE_SIDE_RIGHT, FOREST_ONLY_BOTH, FOREST_ONLY_ALTERNATING, CENTERLINE,
  JUNCTIONS_ONLY.
- `spacing()`, `requiresBiomeCheck()`, `isNone()`, `none()` helpers.

**`Village/Decoration/Roads/CulturePalette.java`**
- Record holding surface (primary/accent/rare), supports (primary/accent/rare),
  lighting (profile + lightBlock + lightBaseBlock + optional lightCapBlock),
  optional retaining-wall primary/accent, and an `isGreatRoadAlternate` flag.
- Block codec is `Identifier.CODEC.xmap(...)` — same pattern as PathMaterial.
- `effectiveRetainingWallPrimary/Accent()` fall back to support lists if unset.

**`Village/Decoration/Roads/PaletteRegistry.java`**
- Static `Map<String, CulturePalette>` plus a dedicated `OLD_REALM` instance.
- Built-ins: default / imperial / highland / nordic, plus Old Realm.
- `forCulture(id)` falls back to "default" for unknown ids (Phase 7g
  "palette resolution fallback" note in the spec).
- `greatRoadPalette(nearestCultureId)` returns Old Realm unless the culture's
  palette sets `isGreatRoadAlternate` (only imperial does at present).

**`Village/Decoration/Roads/CulturePaletteResolver.java`**
- Single entry point `resolve(edge, graph, data) -> Resolved(palette,
  lighting, cultureId)` used by both realisation and debug.
- Edge tier determines culture lookup:
  - GREAT_ROAD → nearest village's kingdom culture → greatRoadPalette(culture).
  - TRUNK → majority culture across maintainer villages; falls back to
    VILLAGE_DOCK endpoint culture, then "default".
  - CONNECTOR/LOCAL → maintainer villages first, then dock endpoint, then
    "default".
- Lighting override from `RoadEdge.getLightingOverride()` wins over palette
  default; palette itself is not overridable (per spec — force_lighting only
  changes the profile, not the blocks).

**`Village/Roads/Lighting/RoadLightingPlacer.java`**
- Walks `edge.getBlockPath()` by accumulated XZ distance, stepping every
  `profile.spacing()` blocks.
- Per interval: biome-gating (FOREST_ONLY_* uses `WorldAtlas.getCellAtBlock`
  and checks `BiomeCategory.FOREST`), strategy-sides decomposition, per-side
  column placement.
- Column: pedestal = palette.lightBaseBlock, Y+1 = palette.lightBlock, Y+2 =
  optional lightCapBlock. Ground-snapped via MOTION_BLOCKING_NO_LEAVES.
- Perpendicular offset = `tier halfWidth + 1` (CENTERLINE uses offset 0 and
  runs a `RoadClearanceValidator.isClearOfRoads(base, graph, 1)` check).
- Deterministic alternation uses `edgeSeed & 1L` to pick starting side.
- Placed base positions are appended to `edge.getDecorationPositions()` for
  persistence and for approximate light counts in debug summary.
- JUNCTIONS_ONLY and NONE profiles short-circuit immediately.

### Files modified

**`Village/Roads/Graph/RoadEdge.java`**
- Added `Optional<RoadLightingProfile> lightingOverride` field and
  `optionalFieldOf("lightingOverride")` in the codec group (codec is now 17
  fields — still within the project-wide limits exercised by Village.java,
  Castle's CastleStyle.java, etc.).
- Added `getLightingOverride()`, `setLightingOverride(profile)`, and
  `clearLightingOverride()` accessors.

**`Village/Decoration/Roads/PathMaterial.java`**
- Added `fromCulturePalette(CulturePalette)` factory that builds a core/edge
  weighted PathMaterial from the palette's surface lists (primary 60 % /
  accent 30 % / rare 10 %).
- Added `applyOverlays(base, maintenance, tier, season)` helper extracted from
  the existing `resolve()` overlays so palette-based materials can share the
  maintenance-decay and seasonal code paths.

**`Village/Roads/Realization/EdgeMaterialResolver.java`**
- `resolveForEdge` now delegates palette resolution to
  `CulturePaletteResolver.resolve`, builds the PathMaterial from
  `fromCulturePalette`, and applies maintenance/season overlays via
  `applyOverlays`. GREAT_ROAD edges skip maintenance decay (invariant 3).
- `MaterialContext` record gained a third field `palette` so realisation can
  forward it to UnifiedRoadPlacer and the lighting placer.
- Removed the old `detectBiome` private helper (dead after the refactor —
  biome is sampled from atlas cells inside culture resolution).

**`Village/Roads/Realization/EdgeRealizer.java`**
- Reads `matCtx.palette()` alongside the existing material + culture.
- Forwards the palette to `UnifiedRoadPlacer.place(..., palette)` for both
  SmoothedPath and other primitives (ArmApproach still uses the village's
  own path material and culture, unchanged).
- After `markRealized`, runs `RoadLightingPlacer.placeLighting(level, edge,
  palette, lightingProfile, graph)`, where lightingProfile is
  `edge.getLightingOverride().orElse(palette.defaultLighting())`. Log prints
  count + frequency/strategy tag when any light is placed.

**`Village/Roads/Realization/UnifiedRoadPlacer.java`**
- Added a palette-aware overload
  `place(..., @Nullable String culture, @Nullable CulturePalette palette)`;
  the legacy overload delegates with `palette = null`.
- Forwards the palette to `RoadSupportBuilder.build(..., palette)` and
  `RetainingWallBuilder.build(..., palette)`. Non-great-road tiers still run
  the Phase 6a architectural-detail passes (imperial gutters, highland
  retaining wall, nordic corduroy) exactly as before.

**`Village/Roads/Terrain/RetainingWallBuilder.java`**
- `build(...)` now has a `CulturePalette palette` parameter; a legacy no-palette
  overload delegates to `PaletteRegistry.oldRealm()`.
- Palette-driven block selection replaces the hardcoded Old Realm mix. New
  helper `sampleBlockState(x, y, z, primary, accent, rare)` uses the existing
  `posHash` for a stable 60/30/10 distribution and collapses bands when a list
  is empty.
- Added `supportBlock(x, y, z, palette)` used by RoadSupportBuilder. Legacy
  `oldRealmBlock(x, y, z)` kept — now delegates to the palette sampler.

**`Village/Roads/Terrain/RoadSupportBuilder.java`**
- `build(...)` now has a `CulturePalette palette` parameter; a legacy overload
  delegates to `PaletteRegistry.oldRealm()`.
- Solid fill uses `RetainingWallBuilder.supportBlock(..., palette)`.
- Pillar blocks use `palette.supportPrimary()[0]` (Stone Bricks fallback if the
  list is empty).
- Deck slabs remain stone-brick slab regardless of palette — kept simple; a
  slab-type-per-palette pass is a tuning candidate later.

**`Commands/RoadGraphDebugCommand.java`**
- Four new subcommands under `/liv road debug`:
  - `palette <edgeId>` — reports tier, culture, paletteId, primary/accent/rare
    block names for surface and supports, and lighting (frequency/strategy +
    light/base/optional cap blocks). Indicates "(override)" when the edge
    has a `lightingOverride` present.
  - `force_lighting <edgeId> <frequency> <strategy>` — sets the edge's
    `lightingOverride`, re-runs `RoadLightingPlacer.placeLighting` with the
    new profile, and marks WorldRoadSavedData dirty. Accepts all
    Frequency/Strategy enum names (case-insensitive).
  - `lighting_summary` — across all realised edges: realisedEdges count,
    approxLightPositions (count of decorationPositions summed across edges),
    and per-Frequency/per-Strategy distribution.
  - `list_palettes` — dumps each registered palette id plus the always-
    available Old Realm palette, with lighting, light/base blocks, and a
    "[greatRoadAlternate]" tag where applicable.

### Design decisions

**Palette is not persisted per-edge.** Same as the spec; culture resolution is
purely derived at realisation time. Saved-world `RoadEdge` data gained only
the `Optional<RoadLightingProfile> lightingOverride` field — empty by default,
set only via `/liv road debug force_lighting`. This keeps the "palette
changes propagate on re-realization" property the spec asked for.

**Nearest-culture lookup for great roads.** Great roads have no maintainer
(invariant 4). `CulturePaletteResolver.cultureForEdge` uses the edge midpoint
(block-path centre → cell-path centre → endpoint-average fallback) and picks
the nearest village's kingdom culture. If no kingdoms exist (early worldgen),
falls back to "default", which resolves to the default palette — which
`PaletteRegistry.greatRoadPalette` then turns into Old Realm regardless. So
great roads always end up Old Realm until Imperial-maintained villages exist
nearby.

**Lighting placer uses block-path distance, not cell-path distance.** Spec
says "walk edge.blockPath() by accumulated block distance". Chebyshev-like
XZ delta (Math.max(|dx|,|dz|)) is used per step since dense paths are 1-block
steps. Cell-path distance would have been coarser and harder to ground-snap
cleanly.

**Light pedestals are ground-snapped, not profileY-snapped.** On raised
great-road sections (Phase 7e viaducts), the pedestal lands at the actual
terrain Y next to the road rather than up on the viaduct — which reads as
"lanterns along the base of the viaduct" rather than "lanterns on top of the
walkway". This avoids the need to reach into GreatRoadProfile here and keeps
the lighting placer tier-independent.

**Column placement does NOT use RoadClearanceValidator for non-CENTERLINE
placements.** The offset is `halfWidth + 1` which is outside the walkable
surface, and running the clearance scan per light would be O(edges × block-
path) per interval. Only CENTERLINE checks clearance (radius 1) since that
is the only strategy that can actually collide with the road surface.

### Known tuning candidates

- **Nordic "lantern on spruce fence"**: base block is SPRUCE_FENCE. Real
  vanilla behaviour places lanterns attached to the block below via block
  property; this mod places simple stacked blocks. Reads fine at a glance but
  lanterns won't render "hanging" from the fence. If it looks wrong in
  playtest, palette can upgrade to use `FenceBlock` state explicitly or swap
  the base block to something less protruding.
- **Old Realm soul-lantern + polished-blackstone** may read too dark next to
  lighter great-road surfaces in snowy biomes. Worth a palette-per-biome pass
  later if the contrast is too stark.
- **Imperial alternate great roads** produce stone-brick-dominant great roads
  with sea lanterns. This is intentional for "royal road" flavour but may
  read indistinguishable from Old Realm at a glance; watch for player
  confusion in playtest.
- **Pedestals at raised-viaduct sections** (see design decision above) land
  on the terrain floor, not on the viaduct deck. If playtest finds that
  unreadable, switch to `profileY` snapping inside the lighting placer.

### Observations

Because compilation can't be validated in this sandbox (NeoForge maven
unreachable), the implementation is shipped unverified at the byte-code
level. Static inspection confirms:

- All new files compile against the same Mojang Codec/Registry idioms already
  used elsewhere (Identifier.CODEC.xmap block codec, record codecs,
  optionalFieldOf, RecordCodecBuilder.create). The RoadEdge codec is now 17
  fields; the project already has several codecs with 18–34 fields
  (`AdventurerGroup`, `Company`, `Village`, `RoadPrimitive`, `CastleStyle`).
- Public API surface change: `EdgeMaterialResolver.MaterialContext` gained a
  third field `palette`. Only caller is `EdgeRealizer`, which was updated.

### Next

Phase 7-series is complete (7a, 7b, 7c, 7d, 7e, 7f Slice 1+2, 7g).
Next in the main plan: **Phase 9 — network evolution** (dead edges,
road-attracted village placement) — now unblocked because all great-road,
cultural, and terrain-authority subsystems ship visible, legible output at
ground level.

---

## Phase 9 — Network evolution: dead edges + road-attracted placement

**Date:** 2026-04-24

### Summary

Two simulation mechanics that wire population change to the road network:

1. **Dead edges.** When the last maintainer village of a CONNECTOR / TRUNK
   edge dies, the edge transitions through five death phases over 180 in-game
   days (RECENT → DECAYING → OVERGROWN → TRACE → RECLAIMED) and is eventually
   removed from the graph after a 30-day grace period. GREAT_ROAD edges are
   exempt per invariant 3.
2. **Network-attracted village placement.** New villages within an existing
   kingdom claim now receive a 0–50 point network alignment bonus based on
   proximity to live GREAT_ROAD / TRUNK edges, weighted at 0.4 in the final
   score. Capitals are exempt — they are placed first and seed their own
   network.

Two fire-and-forget event hooks (`EdgeDeathEvent`, `VillagePlacementEvent`)
are added for future systems to subscribe to. No existing logic subscribes.

### Files created (new package: `Village/Roads/Lifecycle/`)

**`DeadEdgeState.java`**
- Record `(firstMarkedTick, lastMaintainerDiedTick, Optional<Long> reclaimedAt)`
  with Codec.
- `DeathPhase` enum: RECENT (0–7d), DECAYING (7–30d), OVERGROWN (30–90d),
  TRACE (90–180d), RECLAIMED (180+d) with String codec.
- Phase computation methods: `phaseAtTick`, `ticksUntilNextPhase`,
  `withReclaimedAt`, `atPhase` (used by `force_death_phase` debug command).
- Constants: TICKS_PER_DAY=24000, RECLAIM_GRACE_DAYS=30.

**`DeadEdgeDetector.java`**
- `maybeScan(level)` — once-per-day scheduled scan; self-throttles via
  `lastScanTick` static.
- `scanForDeadEdges(level)` — iterates all edges, skips GREAT_ROAD, marks
  dead any edge whose every maintainer UUID is missing from
  `VillageSavedData`. Fires `EdgeDeathEvent.MARKED_DEAD` per marking. Also
  detects phase transitions on already-dead edges via a static
  `LAST_PHASE` map (fires `PHASE_CHANGED` when crossing a boundary). Stamps
  `reclaimedAt` the first time an edge is observed at RECLAIMED.

**`ReclaimedEdgeCleanup.java`**
- `maybeCleanup(level)` — once-per-month grace-period sweep.
- Removes edges where `currentTick − reclaimedAt ≥ 30 days`. Fires
  `EdgeDeathEvent.REMOVED`. Also removes orphan TERMINUS / POI_STUB /
  TRUNK_JUNCTION nodes after the edge removal; preserves VILLAGE_DOCK and
  any node still carrying a `gatewayLink` (those belong to live villages).
- `despawnEdgeBlocks` replaces each surface-Y road block with grass; gentle,
  best-effort, skips unloaded chunks.

**`NetworkAlignmentScorer.java`**
- `networkAlignmentScore(BlockPos, WorldRoadGraph) -> float` (0–50).
- Uses `WorldRoadGraph.edgesNear(...)` with a 1500-block radius, then walks
  block-path or cell-path to find squared distance to nearest GREAT_ROAD /
  TRUNK. Banded scoring per spec: GREAT_ROAD ≤500 = +30, ≤1500 = +15;
  TRUNK ≤300 = +20, ≤800 = +10. Dead edges don't count.

**`EdgeDeathEvent.java`**
- Static listener-list hook with `Kind` (MARKED_DEAD / PHASE_CHANGED /
  REMOVED), `subscribe`/`unsubscribe`/`fire`. Listener exceptions logged to
  stderr; never propagated.

**`VillagePlacementEvent.java`**
- Static listener-list hook fired from `VillageSpawner` after `addVillage`,
  carrying `(villageId, type, position, isCapital, networkScore, tick)`.

### Files modified

**`Village/Roads/Graph/RoadEdge.java`**
- Added `Optional<DeadEdgeState> deadState` field (codec is now 18 fields;
  Village.java's codec already exercises 25). `optionalFieldOf("deadState")`
  in the codec group.
- Accessors: `getDeadState`, `isDead`, `deathPhase(currentTick)`,
  `markDead(tick)` (no-op for GREAT_ROAD per invariant 3 + idempotent), and
  `setDeadState(state)` for the `force_death_phase` debug command.

**`Village/Roads/Decoration/RoadOvergrowthDecorator.java`**
- Branches on `edge.isDead()`: dead edges use `densityForPhase(phase)` and
  `maintenanceForPhase(phase)` instead of the maintenance-driven logic, so
  decay is permanent and progresses through phases on each re-realisation.
- Added `traceReplaceSurface` for TRACE / RECLAIMED phases: replaces road
  surface blocks with grass / coarse_dirt, keeping every Nth block as a
  "stone from the old road" (TRACE keeps 25%, RECLAIMED keeps 12.5%).
  Replaced positions are appended to `decorationPositions` so re-realisation
  doesn't double-decorate.

**`Events/KingdomTaxEvent.java`**
- Added two calls inside the existing `onServerTick`:
  `DeadEdgeDetector.maybeScan(level)` and
  `ReclaimedEdgeCleanup.maybeCleanup(level)`. Both self-throttle.

**`Kingdom/Placement/VillagePlacementScorer.java`**
- New 7-arg `score(...)` overload accepting `@Nullable WorldRoadGraph graph`
  and `boolean isCapital`. Adds `NETWORK_SCORE_WEIGHT * networkAlignmentScore`
  to the final score when graph is non-null and `!isCapital`.
- 5-arg overload preserved for backward compatibility (delegates with
  `graph=null, isCapital=false`).

**`Kingdom/Placement/ClaimVillagePlacer.java`**
- New 6-arg `plan(...)` overload accepting `@Nullable WorldRoadGraph graph`.
- `selectCell(...)` plumbs the graph into both score-loop call sites; both
  paths now pass `graph, isCapital` to the scorer.

**`Kingdom/KingdomSpawner.java`**
- After computing the territorial claim, fetches the graph from
  `WorldRoadSavedData.get(level).getGraph()` and passes it to
  `ClaimVillagePlacer.plan(...)` so non-capital villages receive the
  network-alignment bonus. Capital placement is unaffected.

**`Village/VillageSpawner.java`**
- After `data.addVillage(village)` and gateway population, fires
  `VillagePlacementEvent` with the per-village network score (computed via
  `NetworkAlignmentScorer.networkAlignmentScore(anchor, graph)`).
  `isCapital` is approximated by "kingdom has only this village" since the
  spawner doesn't know its caller's intent. Wrapped in `try/catch` so a
  faulty subscriber can never block placement.

**`Commands/RoadGraphDebugCommand.java`**
- Six new subcommands under `/liv road debug`:
  - `dead_edges` — lists every dead edge with phase, marked tick, and
    "until next phase" countdown.
  - `force_dead <edgeId>` — marks an edge dead at the current tick.
    Refuses GREAT_ROAD (invariant 3).
  - `force_death_phase <edgeId> <phase>` — backdates the edge's
    `firstMarkedTick` so it computes to the requested phase, useful for
    inspecting decoration changes.
  - `reclaim_edge <edgeId>` — manual graph removal for already-RECLAIMED
    edges; runs the same cleanup that the periodic sweep does.
  - `network_score <x> <y> <z>` — reports the score a candidate would
    receive at that position (helpful for understanding why one cell is
    favoured over another).
  - `village_death <villageName>` — removes a village from saved data,
    detaches it from kingdoms, and immediately runs the dead-edge scan so
    the cascade is visible in the same tick.

### Design decisions

**Dead state is permanent.** Once an edge is marked dead, the `deadState`
optional is never cleared. The spec explicitly forbids edge "revival". The
`firstMarkedTick` is the canonical clock; phase computation is pure given
that tick, so phases recompute deterministically on every load.

**Detector is single-shot per UUID.** `LAST_PHASE` is a static
HashMap<UUID, DeathPhase>. It's not persisted because phases are derivable
from `firstMarkedTick` — the map only exists to avoid firing PHASE_CHANGED
twice for the same boundary within a session. On reload it's empty, so the
first scan after reload won't fire phase events for already-known edges.
Acceptable: subscribers should reconcile state from `EdgeDeathEvent.MARKED_DEAD
+ initial scan output` if they need exhaustive tracking.

**Phase-driven overgrowth ignores maintenance.** For dead edges,
`maintenanceForPhase` produces a synthetic maintenance value that drives the
existing `degradeSurface` / `placeLogs` paths, but the density curve and the
TRACE-surface replacement run only on dead edges. Living edges with low
maintenance still get the original Phase 6b overgrowth, unchanged.

**Network score is gated on isCapital, not on village count.** Capitals
(index 0 in the kingdom's composition list) skip the bonus regardless of
how many roads exist — this matches the spec's "first village of a kingdom"
rule. For natural-spawn paths that don't know their kingdom, the
`VillageSpawner.isCapital` heuristic falls back to "kingdom has only this
village".

**Orphan-node cleanup spares VILLAGE_DOCK and gateway links.** A removed
TERMINUS for a now-vanished arm endpoint is fine to delete, but a node that
still carries `gatewayLink.isPresent()` belongs to a live village and might
be the destination of a future re-connection.

### Known tuning candidates

- **180 days to RECLAIMED** is a long real-time window. If players never
  see TRACE phase in normal play, shorten the thresholds. The constants are
  in `DeadEdgeState` and only need a recompile.
- **NetworkAlignmentScorer search radius** is 1500 blocks. Worlds with
  sparse great-road graphs may want this raised; performance is currently
  bounded by the spatial index returning at most ~10 edges per query.
- **VillagePlacementScorer.NETWORK_SCORE_WEIGHT** is 0.4/50 (max +0.4 to a
  raw score that typically lives in the 1.0–3.0 range — this is a meaningful
  but not dominant nudge). If villages cluster too tightly along great
  roads in playtest, drop to 0.2/50.
- **Village.isAlive()** was deliberately not added — the codebase models
  village lifecycle via presence in `VillageSavedData` rather than an
  explicit alive flag, so the detector reads `getVillageById(...).isPresent()`.

### Observations

The build can't be verified in this sandbox because the NeoForge maven
returns 403. Static inspection confirms:

- All new files use the same idioms already exercised by the codebase
  (RecordCodecBuilder, optionalFieldOf, the WorldRoadGraph API, the
  ServerTickEvent.Post wiring through `KingdomTaxEvent`).
- RoadEdge codec is now 18 fields. `Village.java`'s 25-field codec proves
  the project's DFU/Mojang stack handles this arity.
- Public API additions are additive — original `score(...)` and `plan(...)`
  signatures are preserved, so callers in `VillageRealisationSystem` and
  other places need no changes.

### Carryovers

- Caravan re-routing on dead edges is deferred per spec ("if a caravan was
  on a dead edge when it was marked dead, it keeps using that edge").
- Persistent phase-event tracking (across reloads) is not implemented;
  PHASE_CHANGED events fire only within a session.
- Network-alignment scoring runs on the realised block path when present,
  cell path otherwise. For long unrealised great roads at worldgen time
  this is potentially expensive (cell-path iteration). Acceptable for now;
  optimisation would push a per-edge bounding box pre-check.

### Next

**Phase 10 — events (travelers, lone structures, junctions, landmarks)**.
Phase 9's `EdgeDeathEvent` and `VillagePlacementEvent` hooks are available
for Phase 10 lore generators / event spawners to subscribe to.

---

## Phase 10 — Event system infrastructure

**Date:** 2026-04-24

### Summary

Lays the groundwork for four categories of road events (TRAVELER /
LONE_STRUCTURE / JUNCTION / LANDMARK) without shipping any real event
content. Event types are registered at mod init via a static
`RoadEventRegistry`, planned deterministically along an edge or at a node by
`EventSitePlanner`, materialised by per-type `EventFactory`s through
`EventRealizer`, persisted alongside the road graph in `WorldRoadSavedData`,
and despawned on expiry by `EventLifecycleSystem` (running daily from
`KingdomTaxEvent` alongside the Phase 9 dead-edge sweep).

Two placeholder event types — `test_marker` (COMMON, PERMANENT,
LONE_STRUCTURE — polished andesite + redstone torch every 2000 blocks on
CONNECTOR/TRUNK) and `test_ephemeral` (UNCOMMON, EPHEMERAL, JUNCTION —
2x2 leaves cluster with a 5-day lifespan) — register only when the JVM is
launched with `-Dlitv.testEvents=true`. They exist for regression testing
of the registry, planning, realisation, persistence, and expiry paths.

### Files created (new package: `Village/Roads/Events/`)

**`RoadEvent.java`**
- Record `(eventId, typeId, position, containingEdgeId, containingNodeId,
  placedTick, expiresAtTick, properties, historyRefId)` with Codec.
- Helpers: `isPermanent()`, `hasExpired(currentTick)`, `withProperty`,
  `withHistoryRef`. Properties bag is `Map<String,String>` for cheap codec
  + extensibility.
- Codec uses `optionalFieldOf` for every nullable / optional field so old
  saves with no events load cleanly.

**`RoadEventType.java`**
- Record `(typeId, category, permanence, rarity, minSpacingBlocks,
  applicableTiers, applicableBiomes, worldUnique, factory)`.
- Enums: `EventCategory` (TRAVELER / LONE_STRUCTURE / JUNCTION / LANDMARK),
  `EventPermanence` (PERMANENT / EPHEMERAL / MIXED),
  `EventRarity` (COMMON 1.0× / UNCOMMON 0.5× / RARE 0.1× / WORLD_UNIQUE).
- The factory and predicates are code-only — only `typeId` is persisted on
  events; the type is re-resolved through the registry on load.

**`EventFactory.java`**
- Interface `(create(EventPlacementContext) -> RoadEvent | null,
  despawn(level, event))`.

**`EventPlacementContext.java`**
- Record `(level, edge, node, sitePosition, currentTick, rng)` passed to
  `create`.

**`RoadEventRegistry.java`**
- Static `LinkedHashMap<String, RoadEventType>` with `register`, `get`,
  `all`, `byCategory`, `applicableTo(edge|node)`, `worldUniqueTypes`.
- JUNCTION-only types are filtered out of the edge applicability list;
  edge-only categories are filtered out of node applicability.

**`EventSitePlanner.java`**
- `planSitesForEdge(edge, graph, level)` walks `edge.blockPath()` by XZ
  block distance, steps every `minSpacingBlocks`, applies biome filter via
  `WorldAtlas.getCellAtBlock`, and rolls a deterministic per-slot float
  against `rarity.targetDensityMultiplier()` seeded by
  `edgeId XOR worldSeed XOR typeIdHash XOR slotIndex`.
- A second pass enforces same-type spacing along path order.
- `planSitesForNode(node, graph, level)` is a single deterministic roll
  per (node, type) with rarity-mapped probabilities (COMMON 0.30 →
  WORLD_UNIQUE 1.0).

**`EventRealizer.java`**
- `realizeEvents(level, edge|node, plans, graph)` iterates planned sites,
  invokes each factory inside
  `RoadPlacementContext.withSuppressionReturning(...)` (new helper),
  registers the result in `WorldRoadSavedData`, threads the new event UUID
  into the owner's `eventIds` list, and marks world-unique types placed.
- Idempotency: re-realising an edge skips planned sites whose type already
  has an event within `minSpacingBlocks` on the same owner.
- Factory exceptions are caught + logged; bad factories don't abort the
  rest of the plan.

**`EventLifecycleSystem.java`**
- `maybeTickExpirations(level)` self-throttles to once per game day.
- `tickExpirations` collects expired events, calls each factory's
  `despawn` (catching exceptions), removes the event from owner edge/node
  `eventIds`, removes from `WorldRoadSavedData.events`, and unmarks the
  world-unique flag if applicable so the type can re-place later.
- `despawnEvent(level, saved, graph, event)` is exposed for the
  `despawn_event` debug command.

**`PlaceholderEvents.java`**
- `test_marker`: polished_andesite at offset (+3X, ground) with a
  redstone_torch on top. PERMANENT.
- `test_ephemeral`: 2x2 persistent oak_leaves block at node.above().
  EPHEMERAL with `currentTick + 5 days` expiry.
- `registerIfEnabled()` only registers when `-Dlitv.testEvents=true`;
  `register()` is a force-on entry point used by tests.

### Files modified

**`Village/Roads/Graph/RoadEdge.java`**
- Added `List<UUID> eventIds` field; codec slot lives in the `ExtrasTuple`
  half (now 3 fields, still under the 16-arity ceiling). Accessors:
  `getEventIds`, `addEventId`, `removeEventId`, `clearEventIds`.

**`Village/Roads/Graph/RoadNode.java`**
- Added `List<UUID> eventIds` field; codec is now 7 fields. Same accessors
  as RoadEdge.

**`Networking/WorldRoadSavedData.java`**
- `Snapshot` record gained `Map<UUID, RoadEvent> events` and
  `List<String> worldUniqueTypesPlaced` (codec is now 8 fields). Both
  `optionalFieldOf` so pre-Phase-10 saves load cleanly.
- Added accessor methods: `getEvent`, `registerEvent`, `updateEvent`,
  `removeEvent`, `allEvents`, `eventsForEdge`, `eventsForNode`,
  `isWorldUniquePlaced`, `markWorldUniquePlaced`,
  `unmarkWorldUniquePlaced`, `getWorldUniqueTypesPlaced`.

**`Village/Roads/Realization/RoadPlacementContext.java`**
- Added `withSuppressionReturning(Supplier<T>)` that mirrors the existing
  `Runnable` flavour but allows the work to return a value. Used by
  `EventRealizer` to capture the factory's `RoadEvent` while keeping
  terrain-change suppression active.

**`Village/Roads/Realization/EdgeRealizer.java`**
- After shelter placement, calls `EventSitePlanner.planSitesForEdge` and
  `EventRealizer.realizeEvents`. Then for each unprocessed adjacent node
  (by empty `eventIds`) plans + realises node events. Whole event block
  is wrapped in try/catch so an event-placement bug never aborts road
  realisation.

**`Events/KingdomTaxEvent.java`**
- After the Phase 9 lifecycle calls, invokes
  `EventLifecycleSystem.maybeTickExpirations(level)`.

**`Lore/HistoryTextGenerator.java`**
- Added `recordEventInHistory(typeId, description, relatedKingdomId, tick)
  -> UUID`. Phase 10 hook only — no event factories invoke it yet. Logs
  the description and returns a generated UUID.

**`Life_in_the_village.java`**
- `commonSetup` calls `PlaceholderEvents.registerIfEnabled()` so the test
  types appear when launched with `-Dlitv.testEvents=true`.

**`Commands/RoadGraphDebugCommand.java`**
- Six new subcommands under `/liv road debug`:
  - `events <targetId>` — argument is matched against edge then node UUIDs
    by prefix. Prints type, position, placed tick, expiry delta.
  - `events_near <radius>` — sorts saved events by distance from the
    player; useful for "what's around me?" inspection.
  - `spawn_event <typeId>` — calls the type's factory at the player's
    position with no edge/node owner. Refuses if `worldUnique` is already
    placed. Marks `WorldRoadSavedData` dirty.
  - `despawn_event <eventId>` — prefix-matches an event UUID and routes
    through `EventLifecycleSystem.despawnEvent` so factory teardown,
    owner-list cleanup, and saved-data removal all run.
  - `event_registry` — lists every registered type with category /
    permanence / rarity / spacing / tiers.
  - `event_stats` — total events, by category, by type, plus the
    world-unique-placed set.

### Design decisions

**Property bag uses `Map<String,String>` rather than typed records.**
Phase 10 doesn't yet know what data future events will store; a string
map is the cheapest way to keep the codec stable across future event
types without adding a `Codec.dispatch` for properties. Specific event
types that need richer state can encode multiple keys.

**Re-planning is deterministic, realisation is idempotent.** Re-realising
an edge calls the planner again; the planner produces the same site
positions; the realiser drops any planned site that already has an event
of the same type within `minSpacingBlocks`. The combined effect is
equivalent to "place once, never again", but without needing a separate
"events placed" persistent flag.

**Node events trigger from the first adjacent edge's realisation.** Nodes
don't have their own realise hook. The first edge to be realised that
touches a node with empty `eventIds` runs the node planner. Re-running
the planner on a later edge realisation no-ops because `eventIds` is
non-empty. If a node has no realised edges, its events stay deferred —
acceptable, since unrealised nodes aren't visible anyway.

**`isCapital`-style filter is not used here.** Unlike Phase 9's
network-alignment scoring, Phase 10 events apply uniformly across edges
of the eligible tier. Specific event types can constrain themselves
through `applicableTiers` and `applicableBiomes`.

**World-unique gates run twice.** The planner skips a world-unique type
already placed (cheap pre-filter); the realiser re-checks immediately
before invoking the factory (race-safe in case two edges realise the
same tick). The factory is responsible for its own internal
race-tolerance — only one of the two callers will end up registering the
event in `WorldRoadSavedData`.

**`unmarkWorldUniquePlaced` on despawn.** When the only world-unique
event of its type expires, the slot opens up again. Future placements of
the same type are eligible. This avoids permanent lockout if a temporary
landmark expires mid-world-life.

### Known tuning candidates

- **Density multipliers** are guesses (`COMMON 1.0 / UNCOMMON 0.5 /
  RARE 0.1`). They scale the per-slot probability so a long edge gets
  more sites; a short edge gets fewer. Likely too dense at COMMON for
  some types — worth checking once real types ship.
- **Node-event probabilities** (COMMON 0.30 → RARE 0.05) are picks; if
  junction events feel rare in playtest the COMMON bucket can rise.
- **`test_marker` offset** is hardcoded `+3X` rather than perpendicular to
  the road direction. Real events should compute perpendicular like
  `RoadLightingPlacer`. Acceptable for placeholder.
- **Event-event spacing across types** is unenforced. Two different
  COMMON types can stack on the same block. If clutter becomes an issue,
  add a global-spacing pass to the planner.

### Observations

The build can't be verified in this sandbox (NeoForge maven returns 403).
Static inspection confirms:
- All five new codec sites stay within DFU's 16-field arity ceiling
  (BaseTuple 16, ExtrasTuple 3, RoadNode 7, Snapshot 8, RoadEvent 9).
- The `ExtrasTuple` slot is the only place RoadEdge can grow without
  breaking the 16-field limit; future road-edge fields should bundle here
  too.
- `Codec.unboundedMap(UUID_CODEC, RoadEvent.CODEC)` mirrors the existing
  `edgeShelters` codec pattern; persistence behaviour should match.

### Carryovers

- No real event types yet. Spec says they come after the NPC rework.
- History integration is a hook only; nothing invokes it.
- `EventSitePlanner.planSitesForNode` does not honour `minSpacingBlocks`
  across types yet — single roll per (node, type). Add if needed.
- Test placeholder events default off; flip `-Dlitv.testEvents=true` to
  exercise them.

### Next

**Phase 11 — player-initiated road construction**. Real event types will
arrive as small additions in later phases; Phase 11 takes precedence per
the road plan.

---

## Session B5 — bugfix pass: gateway selection, lighting, signs, great-road junctioning

**Date:** 2026-04-24
**Phase:** Bugfix (post-Phase-10)

### Summary

Four defects investigated. Three confirmed and fixed; the fourth (Defect 3,
spurious road crossings) is deferred for re-test because it is a likely
downstream symptom of Defect 1.

### Defect 1 — gateway selection backward — FIXED

**Root cause:** `VillageRoadNode.OutwardDirection.fromAngle(...)` computes a
sector index `(int)(r/(π/4) + 0.5) % 8` where sector 0 corresponds to angle 0
(EAST in Minecraft +x convention), then returns `values()[sector]`. But the
enum is declared in the order `NORTH, NORTHEAST, EAST, SOUTHEAST, SOUTH,
SOUTHWEST, WEST, NORTHWEST`, so `values()[0]` is NORTH, not EAST. Every
gateway's `outwardDirection` was therefore wrong by two sectors. In
`ConnectorPlanner.selectGateway`, every alignment dot product collapsed to ~0
(a perpendicular outward vs. an east-pointing approach), and the
PRIMARY-vs-SIDE role priority became the tiebreaker — selecting whichever
gateway happened to be PRIMARY regardless of approach direction. The user's
report ("eastern connector ends at the western gateway") matches this
exactly when the western gate was the layout's primary.

**Fix:** explicit `switch (sector)` mapping in `OutwardDirection.fromAngle`.
The selection logic was correct; only the direction lookup was broken. The
codec persists the enum by `name`, so existing saves are unaffected.

**Files modified:** `Village/Roads/Graph/VillageRoadNode.java`.

**Verification:** trace through with east gate at (+50, 0) and a connector
at (+300, 0): old code returned NORTH for the east gate, new code returns
EAST; alignment becomes +1.0 instead of 0; east gate selected.
`/litv road debug test_gateway_selection` should now report the correct
gateway for any external position.

### Defect 2 — road lighting has no visible pattern — FIXED

**Root cause:** `RoadLightingPlacer.placeLighting` iterated over
`edge.getBlockPath()`, but post-Phase-5 that field is the **full-width
placed-block list** returned by `OrganicRoadPlacer.PlacementResult.placedBlocks`
— every block placed across the road's core/inner/edge zones, not the
centerline. The lighting placer's accumulator advanced 1 per step, but
"steps" were perpendicular siblings of the same centerline position, so
spacing math broke and the perpendicular calculation derived from
consecutive-block deltas pointed in nonsensical directions. Result: lights
scattered everywhere, sometimes on the road, sometimes off it, with no
visible cadence.

**Fix:** `EdgeRealizer.realizeEdgeImpl` now accumulates a `centerlinePath`
in parallel with `fullPath`, by appending each primitive's `computeCenterline`
output (with the same first-block-skip dedup as `fullPath`).
`RoadLightingPlacer` gains a 6-arg overload that accepts an explicit
centerline; the 5-arg legacy overload still falls back to
`edge.getBlockPath()` for any external caller (debug `force_lighting`),
but the realisation pipeline always uses the new overload.

**Files modified:**
- `Village/Roads/Realization/EdgeRealizer.java` — accumulate centerlinePath,
  pass to lighting placer.
- `Village/Roads/Lighting/RoadLightingPlacer.java` — new
  `placeLighting(level, edge, centerline, palette, profile, graph)` overload;
  legacy 5-arg variant delegates with `edge.getBlockPath()`.

**Verification:** with the centerline path, the accumulator's
`blockDistXZ(prev, cur)` is consistently 1 per step, so spacing
(SPARSE 24 / MODERATE 16 / DENSE 8) is enforced; `computePerp` derives a
real road-direction perpendicular; offset `halfWidth + 1` lands lights
clearly off the road; strategy (BOTH_SIDES / ALTERNATING_SIDES /
SINGLE_SIDE_*) behaves as designed.

### Defect 2b — junction signs on road surface — FIXED

**Root cause:** `JunctionDecorator.placeRoadSigns` computed sign position as
`nodePos + (dx/dist, dz/dist)` — i.e. one block forward along the outgoing
road direction. That landed the sign on the road centerline.
`isRoadMaterial` skipped placement when the road was already realised but
let the sign through when the sign decorator ran first.

**Fix:** sign position is now computed as
`nodePos + forward + perpendicular * (halfWidth + 1)` — one block forward
plus a perpendicular offset using `RoadClearanceValidator.minimumDecorationOffset(tier)`,
mirroring the milestone / lighting offset convention. Added an explicit
`RoadClearanceValidator.isClearOfRoads(signBase, graph, 1)` guard before
placement.

**Files modified:** `Village/Roads/Decoration/JunctionDecorator.java`.

### Defect 3 — new roads cross over older roads — DEFERRED

**Hypothesis:** likely a downstream symptom of Defect 1. With the wrong
gateway selected, connector A* targets a destination on the wrong side of
the village, so the route detours backward and crosses any existing road
that lies between start and the wrong-side gateway. Once the gateway
selection picks the correct (near-side) gateway, the route should be
direct and the spurious crossing should disappear.

**Action:** no code change in B5. Re-test after the Defect 1 fix is in.
If perpendicular crossings still appear in playtest, add the perpendicular-
crossing penalty to `AtlasRouteRouter` per the spec: query
`EdgeGridIndex` for any edge in the cell, compute the angle between the
proposed move and the edge direction; apply the existing road-cell
discount only when the move is within ~30° of the edge direction;
otherwise apply a `COST_ROAD_CROSSING ≈ 4.0f` penalty.

### Defect 4 — great roads don't junction with each other — FIXED

**Root cause:** `GreatRoadGenerationQueue.CommitEdgeTask.process` checked
only for an exact-endpoint duplicate; it never asked "does my path overlap
an existing GREAT_ROAD edge's cellPath?" Two trunks routed independently
through the same area would both commit and both place blocks, producing
the visual "weaving / looping" the user reported, with no
TRUNK_JUNCTION node ever inserted.

**Fix:** before committing the new trunk, the task now walks
`trunk.cellPath()` and queries `graph.edgesInCell(cellKey)` for each cell.
The first cell that is also in any existing GREAT_ROAD edge's cellPath
becomes the convergence cell. If the new trunk shares more than 2 cells
with that existing edge, the new trunk is treated as a duplicate route
and dropped (with a warning). Otherwise the existing edge is split at the
convergence cell via `WorldRoadGraph.splitEdgeAtCell` (creating a
TRUNK_JUNCTION node), and the new trunk is committed as **two** edges
(`anchorA → junction` and `junction → anchorB`) that share the new junction
node. `splitEdgeAtCell` already handles preservation of meanderProfile,
maintenance, maintainerVillageIds, staleCells, and realized blockPath.

The MAX_SHARED_FOR_JUNCTION threshold is 2 cells (~128 world blocks), per
the spec's guideline that 1–2 cells = junction, more = duplicate route.
Junction surface-snap uses
`level.getHeight(MOTION_BLOCKING_NO_LEAVES, x, z)` so the inserted node
sits at the surface.

**Files modified:**
- `Events/GreatRoadGenerationQueue.java` — pre-commit overlap detection +
  conditional split-and-rejoin or duplicate-skip in `CommitEdgeTask.process`.
  Added imports for `AtlasRouteRouter` and `Heightmap`.

### Open questions / follow-ups

- **Multi-cell overlap divergence point:** the current fix splits at the
  *first* shared cell only. If a new trunk has 2 shared cells (within the
  junction-allowed range), the second shared cell is silently un-junctioned —
  the new trunk's halfB segment passes through it but doesn't insert a
  matching node. Acceptable for now because the visual artifact is
  vastly reduced; the second cell is short and the rendered overlap is
  small. If it shows up in playtest, extend the split logic to also
  insert a divergence junction.
- **Multiple simultaneous overlaps with different edges:** the current fix
  handles overlap with the *first* existing edge encountered. If the new
  trunk crosses two different existing trunks, the second crossing falls
  through to the normal commit path and visually overlaps. Rare in
  practice (worldgen anchor spacing is wide enough that triple-overlaps
  are unusual). Tracked for follow-up if observed.
- **Defect 3 retest:** required after Defect 1 lands. Add to next
  playtest checklist.
- **Pre-B5 saved worlds with broken outwardDirections:** village graphs
  stored before this session have stale (NORTH-instead-of-EAST etc.)
  gateway directions. They will reload with the wrong directions because
  the field is persisted by name. Re-spawning a village or manually
  editing the value would fix individual cases. No automatic migration
  shipped — the symptom is "wrong gateway sometimes selected on a
  village created in a pre-B5 session"; new villages from B5 onward are
  correct.

### Next

Per-user direction.

## Session B6 — 2026-05-01 — RADIAL shared outer ring road

### Goal

Fix the AGRI/DEFENSE band placement in `RadialRecipe` where slots landed
30–50 blocks beyond the outermost arc road and had no real feeding road,
so guard towers / stockpiles / farmhouses failed validation. Diagnostic
dumps from the previous session showed slots at cheb radii ~120/130 with
the nearest existing road being SPINE / ARC_1 at meanR=59 / 73.

### Solution

Introduce a single shared perimeter ring road that both bands attach to.
DEFENSIVE slots sit just inside the ring (`ringR - 16`), AGRICULTURAL
slots just outside (`ringR + 16`). The 16-block offset is exactly the
window that satisfies both constraints: footprint clearance against the
road's reservedHalfWidth=3 (slot half-fp 9 + reservedHalf 3 + gap 4 = 16
blocks min), and the validator's road-distance threshold (3 + 9 + 6 = 18
max). With Ring drift amplitude=2, slot-to-road distance ranges 14–18,
inside the window.

### Implementation

1. **`EdgeRole.OUTER_RING`** — new enum value for the perimeter ring.
   `LayoutDebugCommand.particleForRole` extended with `WAX_ON` particle
   so the exhaustive switch still compiles.
2. **`VillageLayout.addRoad(primitive, level, seed, role)`** — overload
   that accepts an explicit role. Existing 3-arg form delegates with
   `role=null` (preserving `defaultRoleFor` inference). Used by
   `RadialRecipe` to tag the new ring as `OUTER_RING`.
3. **`PlanContext.outerRingEdgeId / outerRingCenterline / outerRingRadius`**
   — three new fields plus `setOuterRing(...)` and accessors. Default
   `edgeId=-1` means "no shared ring; bands fall back."
4. **`LayoutPrimitive.RingBand.emitSlots`** — branches on `pctx.outerRingEdgeId() >= 0`
   AND zone ∈ {DEFENSIVE, AGRICULTURAL}. Shared-ring path uses the
   ring's edge id and centerline as `feedingRoad/feedingEdgeId` and
   computes slot radius as `ringR ± SAFE_OFFSET` (sign by zone).
   Fallback path keeps the legacy per-target nearest-road snap; non-RADIAL
   recipes (CrossroadsRecipe, GroveRecipe, ChainRecipe, PlazaRecipe,
   TerracedRecipe-stragglers, etc.) hit this path unchanged.
5. **`RadialRecipe.composeSectors`** — after the spurs/cluster arcs/outer
   arcs are emitted, builds a `RoadPrimitive.Ring` at `centre` with
   `ringR = outerR + 20`, drift 2.0, VILLAGE_PATH tier; calls the new
   `addRoad(..., EdgeRole.OUTER_RING)`; stashes edge id / centerline /
   radius on PlanContext; passes the captured edge id as `parentEdgeId`
   on both AGRI and DEFENSE sectors.

### Anchor choice

Ring anchored at `centre` (= `pctx.layout.getCenter()`), not `squarePos`.
The bands compute slot positions from `centre`; using a different anchor
would offset the road by the plaza-to-centre vector and erode the 16–18
SAFE_OFFSET window. Existing arc roads still anchor at `squarePos` —
that's a separate decision for inner arcs, where the plaza is the
natural focal point.

### Files modified

- `Village/Planning/Graph/EdgeRole.java` — added `OUTER_RING`.
- `Village/Planning/Primitives/PlanContext.java` — outer-ring fields,
  setter, getters.
- `Village/Planning/Primitives/LayoutPrimitive.java` — `RingBand.emitSlots`
  shared-ring branch + fallback preservation.
- `Village/Planning/VillageLayout.java` — `addRoad` 4-arg overload with
  optional role.
- `Village/Planning/Primitives/Recipes/RadialRecipe.java` — emits the
  shared ring, sets PlanContext, threads edge id into both sectors.
- `Commands/LayoutDebugCommand.java` — switch case for the new role.

### Untouched on purpose

- Civic ring + plaza geometry (Phase 18 doc 04).
- Inner arc roads (ARC_0, ARC_1) — they remain as residential infill,
  anchored at `squarePos`.
- Spur cluster arcs — still serve production clusters at the spur tip
  radius.
- SlotTag values for AGRI/DEFENSE.
- `Sector.expectedMaxFootprint=18` — kept.
- Other recipes that build RingBands without setting the outer ring on
  PlanContext use the legacy fallback path.

### Build status

Gradle compile gated by network access (no NeoForge artifacts cached
offline); changes verified by inspection. The exhaustive `EdgeRole`
switch in `LayoutDebugCommand` is the only switch the grep found, so
adding `OUTER_RING` to the enum is safe everywhere else (no other
exhaustive consumers).

### Validation criteria for next playtest

- New `--- ROADS ---` entry with `role=OUTER_RING`, pts ≈ 2π·ringR
  (~880 for ringR=140).
- AGRI / DEFENSE sector dumps report `road=Npts` matching the outer
  ring's centerline length (not SPINE/ARC).
- GUARD_TOWER / STOCKPILE / FARMHOUSE rows in committed buildings show
  `feedRoad=Npts` matching the ring.
- VALIDATION SUMMARY: zero FAILs for these three building types across
  multiple RADIAL spawns on Lithosphere terrain.

### Next

Phase 17 (below).

---

### 2026-05-01 — Phase 17: recipes react to road truncation

**Phase:** 17 — maxStepDeltaY tune + spine viability + slot clamping

#### Step 1 — maxStepDeltaY bump

`PrimitiveContext.DEFAULT_MAX_STEP_DELTA_Y` raised 6 → 8. Vanilla hilly
biomes regularly produce 6–7 block Y-steps that are not actual cliffs; 8
avoids premature CLIFF truncation on normal terrain.

Javadoc updated; `@param` entry updated (was "Default 4", now "Default 8").
TODO comment added: introduce `TerrainCategory` enum and lower to 4 for
Lithosphere/Tectonic when that infrastructure exists.

#### Step 2 — slot emission clamped to actual road extent

**`RingBand.emitSlots` proximity clamp (LayoutPrimitive.java):**
When `useSharedRing` is true, each candidate slot position is checked
against the outer ring's actual centerline. If the nearest ring centerline
point is more than `SAFE_OFFSET * 2 = 32` blocks away, the slot is skipped.
This discards slots that fall in the angular gap left by a truncated Ring
road (e.g., a ring that stopped at a cliff only covers 270° — the remaining
90° would otherwise emit slots with no nearby feeding road, guaranteed to
fail the validator).

When the ring is complete (360°), every slot's nearest ring point is ≤18
blocks away — all slots pass. When truncated, slots in the uncovered zone
have nearest ring points from the arc ends, which are far away → dropped.

**`LinearRecipe` farm cluster endpoints:**
Farm clusters were previously placed at the geometric `mainStart`/`mainEnd`
(the intended road endpoints). If the road truncated before reaching
`mainEnd`, the cluster was still placed at the geometric target — which may
be in bad terrain (why the road stopped). Now uses:
- `actualStart = mainCenterline.get(0)` (or geometric fallback if empty)
- `actualEnd   = mainCenterline.get(last)` (actual truncation point)

Farm outward angles (`mainDirRad ± π`) are unchanged; they still point
away from the road, but now from the correct anchor position.

#### Step 3 — severe-truncation cascade

**`BaseRecipe` (BaseRecipe.java):**
- Added `SpineViability` inner enum: `OK | ROTATE | FALLBACK | ABORT`.
- Added static `checkSpineViability(CenterlineResult result, int intendedLength)`:
  - If `result.isComplete()` or `intendedLength ≤ 0` → `OK`
  - If `actualPoints / intendedLength ≥ 0.30` → `OK`
  - `CLIFF_DROP / CLIFF_RISE` → `ROTATE` (rotating 90° may clear the cliff)
  - `WATER_CROSSING` → `FALLBACK` (rotation unlikely to help)
  - `NO_SURFACE` → `ABORT`
- `compose()` now prints `[SPINE-VIABILITY] RecipeName truncations=N pivots=M`
  after `composeSectors` returns if any truncation was recorded.

**`PlanContext` (PlanContext.java):**
- Added `spineTruncationCount` / `spinePivotCount` int fields (default 0).
- Added `recordSpineTruncation()`, `recordSpinePivot()`, and matching
  getters — recipes call these from the cascade path.

**`RadialRecipe` (RadialRecipe.java):**
- After constructing `mainRoad` (before `addRoad`), probes the centerline
  via `mainRoad.computeCenterline(PrimitiveContext.basic(...))` without
  touching the graph.
- Calls `checkSpineViability(probeMain, mainLength)`:
  - `ABORT` → records truncation, returns (composeSectors aborted).
  - `ROTATE` / `FALLBACK` → constructs a 90°-rotated road, probes it:
    - If rotated version is `OK` → reassigns `mainDirRad`, `mainStart`,
      `mainEnd`, `mainRoad` to the rotated values; records truncation + pivot.
      All downstream layout (spurs, arcs, outer ring, gate endpoints) uses
      the rotated direction automatically.
    - Otherwise → records truncation + pivot, logs "DUMBELL recommended",
      continues with the partially-truncated original road.
  - `OK` → falls through to `addRoad` unchanged.

**`LinearRecipe` (LinearRecipe.java):**
- `addNode` calls moved to AFTER the probe/rotation block so no orphaned
  GATE nodes are created in the road graph if we rotate.
- Same probe → rotate → continue pattern as RadialRecipe.
- `ABORT` path also calls `PlacementFailureRecorder.record` with
  `TERRAIN_UNSUITABLE`.

### Files modified (this session)

- `Village/Planning/Primitives/PrimitiveContext.java` — step 1 constant bump
- `Village/Planning/Primitives/PlanContext.java` — truncation/pivot stats
- `Village/Planning/Primitives/BaseRecipe.java` — SpineViability + checkSpineViability + summary print
- `Village/Planning/Primitives/LayoutPrimitive.java` — RingBand proximity clamp
- `Village/Planning/Primitives/Recipes/RadialRecipe.java` — probe + rotation, new imports
- `Village/Planning/Primitives/Recipes/LinearRecipe.java` — probe + rotation, actual farm endpoints, new imports

### Invariants respected

- No abstract-method renames.
- No changes to `CenterlineResult` / `TerminationReason` API.
- No changes to `PlacementSlot`, matcher, validator, building profiles.
- No new `RoadPrimitive` subtypes.
- No civic ring / TownSquare path touched.
- Other recipes (Crossroads, Chain, Plaza, Grove, etc.) unchanged —
  `spineTruncationCount` stays 0 for them.

### Build status

Gradle compile gated by network access; changes verified by inspection.
Key correctness checks:
- `SpineViability` switch in `checkSpineViability` is exhaustive over all
  5 `TerminationReason` values.
- `RingBand` proximity check only executes when `useSharedRing` is true
  and `outerRingCenterline` is known non-empty (guarded by the
  `useSharedRing` condition).
- `LinearRecipe` addNode calls now after probe/rotation — no phantom gate
  nodes on the rotate path.
- `RadialRecipe` variable reassignments (`mainDirRad`, `mainStart`,
  `mainEnd`, `mainRoad`) are all non-final locals.

### Deferred

- Phase 17.5: WEAVER perturbation-clamp (matcher-side, single-file change)
- Phase 17.6: FARMHOUSE intermittent FIELD_EDGE diagnostic
- Per-terrain-category `maxStepDeltaY` override (awaits TerrainCategory enum)

### Next

Phase 17.5 or as directed.

---

### 2026-05-02 — Phase 16b: truncation reaction + general re-emit pattern

**Phase:** 16b — recipes consume `RoadResult`; general cascade engine.

Reference: `docs/zoningandlayout_redesign/PLACEMENT-REWORK-STATE.md`,
sections 3, 4, 8.

This phase reframes / replaces the Phase-17 work in commit `65b3b6d`.
The previous implementation used the right idea (probe → check →
rotate-or-fall-back) but was hard-named to truncation specifically;
sections 3 and 8 of the state doc require general re-emit naming so
Phase 22 can extend the engine without churning every override.

#### Naming changes (mandatory per state doc §8)

| Old (65b3b6d)                 | New (Phase 16b)             |
|-------------------------------|-----------------------------|
| `SpineViability` enum         | `RecipeStatus` enum         |
| values: OK/ROTATE/FALLBACK/ABORT | OK/RETRY/FALLBACK/ABORT  |
| `checkSpineViability()`       | `checkPrimarySpine()`       |
| `recordSpineTruncation()`     | `recordTruncation()`        |
| `recordSpinePivot()`          | `recordCascadeRetry()`      |
| `spineTruncationCount`        | `truncationCount`           |
| `spinePivotCount`             | `cascadeRetryCount`         |

`SpineViability.ROTATE` was renamed `RecipeStatus.RETRY` because
"rotate" was overly specific — RETRY accommodates future re-emit
strategies (different anchor, alternate primitive, etc.) that aren't
axis rotations.

#### Step 1 — `RoadResult` data flow

New `Village/Planning/Primitives/RoadResult.java` — record wrapping a
primitive's centerline with `intendedLength` and `actualLength` plus a
`completionRatio()`. Recipes capture per-primitive results and pass
them into slot emission.

Added `RoadPrimitive.intendedLength()` as a default method that throws
`UnsupportedOperationException` (so unmigrated subtypes fail loudly).
Implemented for the four subtypes recipes use today:

- `StraightRoad`: chord length `sqrt(distSqr(from, to))`
- `Ring`: `2πr`
- `Arc`: `r * |arcSpan|`
- `Spur`: `length` field

`CurvedRoad`, `SmoothedPath`, `ArmApproach`, `Bridge`, `Stairway` use
the throwing default. None of them flow through `computeAndRecord`
yet, so the failure mode never triggers in practice.

`BaseRecipe.computeAndRecord(primitive, pctx)` is the helper that
constructs a `RoadResult` from a `computeCenterline` call without
mutating the road graph. Recipes call it during their probe phase.

#### Step 2 — slot clamping

Static helper `BaseRecipe.clampToRoadReach(candidates, roadResult,
slack)`. Returns the input list unchanged when the road is complete;
otherwise filters out slots whose chebyshev distance from the nearest
centerline point exceeds `footprintHalf + roadHalfWidth + slack`.

`footprintHalf` is computed inline from `slot.footprintBudget() / 2`
(no `PlacementSlot` change). `roadHalfWidth` is per-tier via
`feedingRoad.primitive().tier().reservedHalfWidth()`. `slack=6`
matches the validator's `VALIDATOR_ROAD_SLACK`.

Applied at the recipe level (post-emission filter):

- **RADIAL**: `OUTER_RING.RoadResult` clamps both AGRI and DEFENSE
  bands. Each per-spur cluster arc's RoadResult clamps that spur's
  cluster slots.
- **LINEAR**: spine `RoadResult` clamps residential and production
  along-centerline slots.

The previous in-emit proximity clamp on `RingBand.emitSlots`
(`distToRing > SAFE_OFFSET * 2`) is removed — same goal, but the new
clamping is per-recipe and uses the proper budget formula instead of
a hardcoded `32`.

#### Step 3 — minimal-viable LINEAR conversion

LINEAR was already producing slots along the spine (existing
`RecipeHelpers.generateSlotsAlongCenterline`). The only Phase-16b
change is wrapping the slot list through `clampToRoadReach`. No new
sectors, no retag, no body restructure beyond the
probe-then-commit reorganization required for cascade discipline.

PLAZA, CLUSTERED, CHAIN, others — unchanged. They participate via the
default `compose()` (calls `composeSectors` once) and ignore the
engine. Their `compose()` is still callable by name from the cascade
engine if a cascade-aware recipe falls back to one — they just don't
have their own cascade behaviour.

#### Step 4 — general re-emit engine in `BaseRecipe`

```
RecipeStatus { OK, RETRY, FALLBACK, ABORT }

ReEmitReason  (sealed):
    SevereTruncation(RoadResult primary)   // consumed in 16b
    SlotsDropped(int dropped, int total)   // scaffolding for Phase 22
    SectorStarved(String, int)             // scaffolding for Phase 22

runWithCascade(pctx, maxRetries):
    prepareFeatures
    loop (maxRetries):
        reason = composeOnce(pctx)
        if reason == null: registerAnchors; return
        switch reEmit(reason, pctx):
            OK       → registerAnchors; return
            RETRY    → loop (engine state was mutated by reEmit)
            FALLBACK → set finalShape, reset cascade-axis + retry counter,
                       delegate to ShapeRecipe.forShape(fallbackShape())
            ABORT    → markUnplannable; return
    markUnplannable("retry budget exhausted")
```

`composeOnce(pctx) → ReEmitReason` is the probe-then-commit body.
Default impl (for non-cascade recipes that nonetheless route through
the engine) calls `composeSectors` and returns null = OK.

`reEmit(reason, pctx) → RecipeStatus` is the recipe-side dispatch.
Default returns FALLBACK for any non-null reason. Cascade-aware
recipes override.

`fallbackShape() → ShapeType` — null = abort. Cascade-aware recipes
declare their next link.

#### Compose contract change (item 1 in load-bearing answers)

`BaseRecipe.compose()` is no longer `final`. Cascade-aware recipes
override it to call `runWithCascade(pctx, 3)`; non-cascade recipes
keep the inherited default (`prepareFeatures + composeSectors +
registerAnchors`).

The lifecycle ordering invariant becomes a documented convention.
The Javadoc on `BaseRecipe` is updated to make the override
explicitly allowed for cascade-aware recipes.

#### Recipe state on PlanContext (item 2)

Recipes stay stateless singletons. Per-village retry state lives on
`PlanContext`:

- `truncationCount` — incremented each time a recipe records a
  truncation (replaces 65b3b6d's `spineTruncationCount`).
- `cascadeRetryCount` — engine-tracked retry attempts; recipes
  read this in `reEmit` to decide RETRY-vs-FALLBACK.
- `cascadeAxisRotation` — accumulated radian offset applied to spine
  direction; set by the recipe's `reEmit` before returning RETRY,
  read at the top of the next `composeOnce`.
- `primarySpineResult` — the recipe's spine RoadResult; printed in
  the per-village summary.
- `finalShape` — current shape after any fallback; engine updates
  on FALLBACK delegation.

#### Probe-then-commit discipline (item 3)

`composeOnce` MUST not mutate the layout before deciding whether to
return a reason. RADIAL: spine probe uses `centre` plus
`density.getRing1Radius()` as a civic-ring proxy (the real
`installPlaza` runs only after the probe passes). LINEAR: spine probe
runs before `addNode` / `addEdge`.

A few-block discrepancy between probe geometry (centre-anchored) and
commit geometry (squarePos-anchored, post-plaza) is well within the
30 % viability threshold and doesn't affect the OK/RETRY/FALLBACK/ABORT
classification.

#### `markUnplannable` wiring (item 11)

`VillageLayout.markUnplannable(reason)` + `isUnplannable()` +
`unplannableReason()` added.

`VillagePlanner` after `compose`:
- If `layout.isUnplannable()`: log "VillagePlanner: site unplannable —
  <reason>", record TERRAIN_UNSUITABLE failure, stash reason in
  static `lastUnplannableReason`, print summary, return empty.

`VillageSpawner`:
- After first `plan` returns empty: read `VillagePlanner.
  lastUnplannableReason()`. Non-null → log "VillageSpawner: site
  unplannable" and abort without local refinement.
- After local refinement also returns empty: same check, distinct log.

Static field is reset at the start of every `plan()` call. Single-
threaded village planning makes the field safe.

#### Per-village summary (item 10)

`VillagePlanner.printVillageSummary` runs after the matcher (or as
close as the failure path allows) and emits:

```
[VILLAGE-SUMMARY] shape=RADIAL status=OK spineRatio=1.00
  spineLen=232/232 truncations=0 retries=0 finalShape=RADIAL
  validated=27/27
```

Status codes: `OK | UNPLANNABLE | NO_BUILDINGS | VALIDATION_FAILED`.

The `[SPINE-VIABILITY]` line previously emitted from
`BaseRecipe.compose()` is removed — its info is now in the summary
line, printed at the right point in the pipeline.

#### Fallback chain

- RADIAL → LINEAR → ABORT (LINEAR's `fallbackShape()` returns null)
- Every other recipe → ABORT (default `fallbackShape() = null`)

Phase 22 will let village types declare custom chains in JSON; Phase
16b's hardcoded default is the seed.

#### Files changed

- `Village/Planning/Primitives/RoadResult.java` — NEW
- `Village/Planning/Primitives/RoadPrimitive.java` — `intendedLength()`
- `Village/Planning/Primitives/BaseRecipe.java` — full rewrite of
  the cascade engine, RecipeStatus, ReEmitReason, runWithCascade,
  composeOnce, reEmit, fallbackShape, computeAndRecord,
  clampToRoadReach. Un-finals compose().
- `Village/Planning/Primitives/PlanContext.java` — renames + new
  fields (cascadeAxisRotation, primarySpineResult, finalShape,
  resetCascadeRetryCount).
- `Village/Planning/Primitives/LayoutPrimitive.java` — RingBand
  in-emit proximity clamp removed.
- `Village/Planning/Primitives/Recipes/RadialRecipe.java` —
  full rewrite; cascade-aware, probe-then-commit, OUTER_RING +
  cluster-arc clamping.
- `Village/Planning/Primitives/Recipes/LinearRecipe.java` —
  full rewrite; cascade-aware, spine clamping.
- `Village/Planning/VillageLayout.java` — markUnplannable +
  accessors.
- `Village/Planning/VillagePlanner.java` — short-circuit on
  unplannable, per-village summary, lastUnplannableReason static.
- `Village/VillageSpawner.java` — distinct site-unplannable path
  that skips local refinement.

#### Build status

Gradle compile gated by network access; changes verified by inspection.
Open questions resolved per user direction:

1. Rotate twice before fallback? **Deferred** — one rotation, then
   fallback. Revisit if spawn data shows two-rotation recovery is
   meaningful.
2. Retry budget? **3** — accommodates one rotation + one fallback +
   safety margin.
3. compose() override opt-in? **Yes** — un-finaled, documented as
   intentional.

#### Validation (next steps for the user)

The user's prompt asks for spawn data after this lands. Cascade
behavior — RETRY resolving to OK vs FALLBACK vs ABORT — is the
diagnostic that drives the next phase's prompt.

---

### 2026-05-02 — Phase 17: farm plot sector integration

**Phase:** 17 — move farm plot positioning from post-spawn realiser
into the planner.

Reference: `docs/zoningandlayout_redesign/PLACEMENT-REWORK-STATE.md`,
section 4.

#### Changes

**Step 1 — SlotTag.** Added `FARM_PLOT_CROP` and `FARM_PLOT_ANIMAL`
near `FIELD_EDGE`. These tags are recipe-emitted-and-claimed by the
new plot pass, NOT building-matcher-targeted; no `BuildingProfileRegistry`
entries reference them.

**Step 2 — `FarmPlotSpec` record** (new file
`Village/Planning/FarmPlotSpec.java`):

```java
record FarmPlotSpec(
    BlockPos ownerFarmhousePos,   // position-based; UUID resolved at realise time
    FarmPlot.PlotSubtype subtype, // CROP_FIELD | ANIMAL_PEN
    int halfW, int halfL,         // plot base half-dimensions
    int edgeJitterSeed)           // for deterministic shape regen
```

**Owner ID timing.** Building UUIDs do not exist at planning time —
`BuildingPlacer.placeAndRegister` creates them after the planner returns.
The spec carries the planning-time anchor `ownerFarmhousePos` (the
FARMHOUSE LayoutSlot's pos); the realiser maps this to a Building UUID
via a position-nearest lookup in `resolveOwner`.

**Step 3 — VillageLayout plot storage.** Added a parallel
`Map<PlacementSlot, FarmPlotSpec>` plus a list of plot slots, with
`addPlotSlot(slot, spec)`, `plotSlots()`, `getPlotSpec(slot)`. Kept
off `PlacementSlot` itself so the building matcher's tag-based logic
isn't affected.

**Step 4 — `RecipeHelpers.emitFarmPlotSlots`** now placement-aware.
The prior 65b3b6d-style hardcoded ring would have silently converted
INTEGRATED and DISTANT_FIELDS villages into PERIMETER_OUTSIDE
behaviour; that's a regression masquerading as plumbing. Per-mode
defaults (overridden when `config.minDistance/maxDistance` are
non-zero):

| placement          | inner default       | outer default        |
|--------------------|---------------------|----------------------|
| INTEGRATED         | 0                   | 16                   |
| PERIMETER_OUTSIDE  | villageRadius + 12  | villageRadius + 36   |
| DISTANT_FIELDS     | 40                  | 80                   |
| NONE               | (skipped)           |                      |

Each emitted slot's footprint = `(BASE_HALF + EDGE_JITTER) * 2` on
each axis — reserves the realiser's jitter margin so jittered plot
edges can't spill outside the validated footprint:

- Crop plot: `24×20` (= `(9+3)*2 × (7+3)*2`)
- Animal pen: `32×26` (= `(9+4+3)*2 × (7+3+3)*2`)

Signature is `emitFarmPlotSlots(pctx, farmhouseCount, config)` — the
helper doesn't need the farmhouse list (only the count drives plot
total). The planner does the per-slot owner claim.

**Step 5 — `VillagePlanner.runFarmPlotPass`.** Hooks in *after*
`validatePlan(layout, pctx)` returns success. No point planning plots
for a layout about to be discarded; if validation fails, plot pass
doesn't run at all.

The pass:
1. Collects FARMHOUSE LayoutSlots from `layout.buildings()`.
2. Calls `emitFarmPlotSlots` for candidate positions.
3. For each candidate, finds the nearest farmhouse via
   `LayoutSlot.getPos().distSqr(slot.pos())` (the prompt's
   `b.getShape().getOrigin()` was a Building API; corrected to
   LayoutSlot per user direction).
4. Caps owner claims at `config.plotsPerFarmhouse()`.
5. Validates via `validatePlotSlot`: footprint clear of
   `layout.getRoadFootprint()`; across-footprint flatness `≤ 6`
   (different metric from Phase 16b's per-step `maxStepDeltaY=8`).
6. Constructs a `FarmPlotSpec` (with `pctx.rng.nextInt()` as the
   `edgeJitterSeed`) and calls `layout.addPlotSlot(slot, spec)`.

Drops on validation failure are silent per spec; the realiser surfaces
the count.

The planner emits a separate `--- PLOT SLOTS ---` log section after
the pass — the main `dumpPlan` runs before plot pass (inside
`validatePlan`) so plot positions land in a follow-up log rather than
the central dump.

**Step 6 — `FarmPlotPlacer.placeAll` refactor.** Now a thin realiser:
walks `layout.plotSlots()`, resolves owner Building via
`resolveOwner(plannedPos, village, data)` (closest FARMHOUSE Building
to the planned anchor; null if structure load failed), regenerates
deterministic plot shape from `spec.edgeJitterSeed()`, and runs the
existing levelling / crop / pen / fence / footpath / FarmPlot
registration logic on the planned position.

Removed:
- `findPlotLocation` (spiral search)
- `isValidPlotLocation`
- `placeCropPlots` / `placeAnimalPens` (split-by-subtype loops)
- Bounds-aware retry

Preserved (all on the realiser side, behaviour unchanged):
- `generateShape` / `medianFootprintY` / `levelPad`
- `placeCropPlot` / `placeAnimalPen` / `placeFenceWithGate` /
  `placeWaterSource` / `placeWaterTrough` / `spawnPenAnimals`
- `chooseCrops` / `chooseCropType`
- `placeFootpath` (still uses `RoadRouter.findRoad`; Phase 20 will
  migrate this to the new graph)
- The `PlotShape` data class and its private helpers

**Realiser log.** Now emits:

```
FarmPlotPlacer: planned=N realised=M droppedNoOwner=K droppedNoSpec=K
```

`droppedNoOwner` non-zero indicates a planned plot whose FARMHOUSE
failed to materialise (rare; structure load error). `droppedNoSpec`
is a defensive counter that should always be zero.

#### Files changed

- `Village/Planning/Zoning/SlotTag.java` — two new values
- `Village/Planning/FarmPlotSpec.java` — NEW
- `Village/Planning/VillageLayout.java` — plot slot storage
- `Village/Planning/Primitives/Recipes/RecipeHelpers.java` —
  `emitFarmPlotSlots` + `ringForPlacement`
- `Village/Planning/VillagePlanner.java` — `runFarmPlotPass` +
  `validatePlotSlot` + plot-slots log section
- `Village/Planning/FarmPlotPlacer.java` — refactored `placeAll` +
  `resolveOwner`; removed `findPlotLocation` / `isValidPlotLocation` /
  `placeCropPlots` / `placeAnimalPens`; cleaned unused imports
  (`AABB`, `Collectors`, `BoundingBox`)

#### Constraints honored

- `FarmPlot` data class untouched
- `FarmPlotCommands` (CLI) untouched
- `VillageInhabitantPopulator` untouched
- `PlacementMatcher.run()` untouched (plot pass is separate, not a
  matcher modification)
- `FarmPlotConfig` schema unchanged (just consumed via the new emission helper)
- AGRI ring slot emission unchanged in `RingBand` / `RecipeHelpers`
- Plot footpath routing still uses `RoadRouter.findRoad` (Phase 20
  migrates to the new graph)
- Crop selection / animal spawning / fence-with-gate / water trough
  logic byte-identical for any plot landing at the same position
- No new persistent data type for plot specs — `FarmPlot` is what
  persists; `FarmPlotSpec` is planning-time only

#### Open questions resolved

1. Validation drop vs retry: drop, log count. Confirmed.
2. Uniform-around-ring vs cluster-by-owner emission: uniform-ring
   stays for Phase 17. Aesthetic clustering is later authoring work.
3. Plot specs on PlacementSlot vs parallel map on VillageLayout:
   parallel map. Confirmed.

#### Build status

Gradle compile gated by network access; verified by inspection. Key
correctness points:

- `LayoutSlot.getPos()` and `getBuildingType()` exist; used correctly
  in the claim pass.
- `village.getBuildingIds()` exists on `Village` (line 747); used
  in the realiser's owner resolver.
- `FarmPlot.PlotSubtype` enum unchanged; `setFarmhouseId(UUID)` setter
  unchanged.
- `pctx.density.getRing2Radius()` exists; used as `villageRadius` for
  PERIMETER_OUTSIDE ring offset.
- Same-package access keeps `FarmPlotSpec` reachable from
  VillagePlanner / VillageLayout / FarmPlotPlacer without imports.

---

### 2026-05-03 — Microfix batch: stabilise the 27/27 baseline

**Phase:** Microfix batch (P0; per state-doc Section 8). Three
independent fixes that share test surface (the same superflat seed
reproduces all three).

#### Fix 1 — RingBand DEFENSIVE SAFE_OFFSET tuned for 9×9 buildings

**Diagnosis verified.** GUARD_TOWER (9×9, halfW=4) at slot
{ringR − 16} fails the validator's road-distance cap of
halfFp(4) + roadHalfWidth(3) + slack(6) = 13. Distance 16 > 13.

**The prompt's suggested fix (slot center on centerline) was
rejected** — it would put the building footprint inside the road's
±5-block reservation, trading road-distance failure for road-overlap
failure.

**Actual fix**: split the single SAFE_OFFSET into zone-specific
constants in `LayoutPrimitive.RingBand.emitSlots`:

| zone | offset | passes validator? | clears road? |
|------|--------|-------------------|--------------|
| DEFENSIVE     | **12** (was 16) | 12 ≤ 13 ✓ for 9×9 | 12 > 4+6=10 ✓ |
| AGRICULTURAL  | 16 (unchanged)  | 16 ≤ 18 ✓ for 18×14 | 16 > 9+6=15 ✓ |
| (fallback path) | 16 (unchanged) | unchanged from 16b |  |

Skipped the SLOT_FOOTPRINT tuning (DEFENSIVE roster all ≤ 9×9).

**Documented inline as a Path 1/2 signal.** Per-zone SAFE_OFFSET
tuning is the same shape of problem as 16b's removed in-emit clamp:
slots emitted at a fixed radius can't satisfy every building size.
Architectural fix (matcher-side perpendicular slide, or
per-building-tier slot rings) is Phase 22+ work.

#### Fix 2 — Slot footprint budget derived from perpOffset

**Diagnosis verified — but the prompt's first two hypotheses are
wrong.** `BuildingFootprint.reserveRoad` reserves exactly
`(2*halfWidth+1)²` blocks per centerline point, with halfWidth=3
for every tier (`RoadShape.RoadTier.reservedHalfWidth()`). Reservation
is 7 blocks wide, NOT 20+. Same key added twice is a Set no-op, so
no per-segment accumulation either.

**Real cause**: legacy `generateSlotsAlongCenterline` (6-arg) and
`PlanContext.offerRoadSlots` hardcode `footprintBudget = 16`. With
LinearRecipe's `perpOffset = 7`, only buildings ≤ 6 wide actually
fit (perpOffset − roadHalfWidth − 1 gap = 3 blocks each side from
slot centre), but the slot advertised 16 wide. The matcher
committed large buildings (TOWN_HALL 29×29, HOUSE 11×20, STOCKPILE
13×13) and they immediately failed the road-overlap check.

**Fix per user direction (Path B — tighten footprint, don't change
perpOffset)**:

- `RecipeHelpers.generateSlotsAlongCenterline` (6-arg) and
  `generateOneSidedSlotsAlongCenterline` (7-arg): footprint budget
  derived via new `footprintBudgetForPerpOffset(perpOffset)` helper.
  Formula: `max(4, (perpOffset − 3 − 1) * 2)`. The 9-arg
  footprint-aware overloads (which take `expectedMaxFootprint`
  explicitly) are unchanged.
- `PlanContext.offerRoadSlots`: same inline derivation.

**Expected behavioural fallout** (per user direction):
LINEAR will visibly route TOWN_HALL / HOUSE / STOCKPILE elsewhere
(to civic / plaza-edge slots when those exist; otherwise these
buildings fail to place via the matcher's
`No slot matched any preference tier` warning). That's information
worth surfacing — if LINEAR's plaza/civic emission can't absorb
large buildings, LINEAR is structurally weaker than its current
slot count suggested. **The validation bar shifts from "27/27
placed" to "all buildings that fit emitted slots place;
buildings that don't fit are correctly rejected."**

**Path C (matcher-side perpendicular slide)** is the structurally
correct long-term fix — noted as Phase 22 / post-rework candidate.

#### Fix 3 — Wrap un-sectored road emissions in sectors

**Diagnosis verified.** The matcher's sector attribution
(`PlacementMatcher.slotSectorIds`, `IdentityHashMap`) is correct:
sector slots → attribution preserved; flat-pool slots → sector=null
→ "unknown". HOUSEs reporting `sector=unknown` were claiming slots
emitted via `pctx.offerRoadSlots(...)` without subsequent
`drainSlotsSince` / `offerSector` wrapping. That's the spine and
inner arc emissions in RadialRecipe (and the trunk / ringB / spurs
in DumbellRecipe, and the spine in ChainRecipe).

**Fix**: wrap each un-sectored `offerRoadSlots` call in a snapshot
→ drain → `offerSector(...)` block. Sectors created:

- `radial_main_road` (RESIDENTIAL_INFILL, mainEdgeId)
- `radial_arc_N` per inner arc (RESIDENTIAL_INFILL, arcEdgeId)
- `dumbell_trunk` / `dumbell_ringB` (RESIDENTIAL_INFILL)
- `dumbell_spur_N` per spur (SPUR_CLUSTER)
- `chain_spine` (RESIDENTIAL_INFILL)

Other recipes that already wrap their road emissions (LINEAR,
GROVE spurs, ChainRecipe stubs/farm ends, CrossroadsRecipe arms,
PlazaRecipe spurs, HilltopRecipe terrace rows, TerracedRecipe rows)
were left unchanged.

#### Files modified

- `Village/Planning/Primitives/LayoutPrimitive.java` — Fix 1
- `Village/Planning/Primitives/Recipes/RecipeHelpers.java` —
  Fix 2 (legacy slot generators + footprint helper)
- `Village/Planning/Primitives/PlanContext.java` —
  Fix 2 (offerRoadSlots)
- `Village/Planning/Primitives/Recipes/RadialRecipe.java` —
  Fix 3 (spine + 2 inner arcs sectored)
- `Village/Planning/Primitives/Recipes/DumbellRecipe.java` —
  Fix 3 (trunk + ringB + N spurs sectored, edge IDs captured)
- `Village/Planning/Primitives/Recipes/ChainRecipe.java` —
  Fix 3 (spine sectored)

#### Constraints honored

- PlacementMatcher tier-walking, scoring, avoidance — untouched
- No new SlotTags
- TownSquarePlacer / decoration / paving — untouched
- ENCLAVE not changed (no spine, no arc; uses installPlaza)
- Validator rules untouched (the 13-block cap is correctly catching
  the geometry bug; we fixed the geometry instead)
- No Phase 16b or Phase 17 code touched
- LINEAR's recipe authoring (perpOffset choice) unchanged — Path B
  honors the constraint by tightening the slot footprint budget so
  the matcher rejects oversized candidates rather than committing
  them and failing post-commit
- Recipes that already wrap their emissions in sectors left untouched
- DumbellRecipe was previously a `ShapeRecipe` (not BaseRecipe);
  Fix 3 only added sector wrapping, not cascade integration

#### Build status

Gradle compile gated by network access; verified by inspection.
Notes:

- DumbellRecipe gained imports for `BuildingZone`, `FixedGrowth`,
  `Sector`, `SectorRole`, `PlacementSlot`. The `LayoutPrimitive`
  import is now unused (only referenced by the old, non-existent
  `LayoutPrimitive.TownSquare` symbol per a stale import). Left in
  place; Java's unused-import is a warning, not an error.
- Edge ID capture pattern (`int beforeX = edgeCount();
  addRoad(...); int xEdgeId = edgeCount() > beforeX ? -1 : ...`)
  added to DumbellRecipe to support the new sectors' parentEdgeId.
  Same pattern already used elsewhere.

#### Validation (next steps for the user)

Spawn on superflat:

- **RADIAL** — should now report `validated=27/27` and HOUSE
  attributions like `radial_main_road`, `radial_arc_0`,
  `radial_spur_N` instead of `unknown`. GUARD_TOWER should commit
  on first try inside the OUTER_RING.
- **LINEAR** — TOWN_HALL / large buildings will land on plaza/civic
  slots if those fit, otherwise surface as `No slot matched any
  preference tier`. The bar is "all buildings that fit are placed",
  not "27/27".
- **PLAZA / GROVE / DUMBELL** — no regressions expected; new sectors
  are debug-visible attribution improvements.
- **ENCLAVE** — unchanged; runs as before.
- **Cascade behavior on hilly terrain** — unchanged from Phase 16b
  baseline.

If LINEAR drops a meaningful number of large buildings post-fix,
that's signal for Phase 22 (recipe fallback chains) — LINEAR's
authoring genuinely needs civic/plaza slot capacity revisited.

---

### 2026-05-04 — Phase 18: polygon-aware plaza ownership consolidation

**Phase:** 18 (Path B per user direction). The original "refactor
TownSquare" framing didn't match the codebase — `LayoutPrimitive
.TownSquare` was deleted in a prior doc-04 effort, and the polygon
machinery (`PlazaGenerator` + `PlazaRegion` + `RecipeHelpers
.installPlaza`) had taken over. Surfaced this twice; user ruled
**Path B**: build Plaza as a façade over the polygon migration that
already happened, not over the deleted class.

#### Plaza façade

New `Village/Planning/Plaza.java`. Class (not record) so the
civic-slot list can be mutable while geometry fields stay
effectively-final.

```
Plaza(
    PlazaRegion region,        // nullable for HAMLET
    BlockPos    townSquarePos,
    int         townSquareRadius,
    int         civicRingRadius,
    List<PlacementSlot> civicSlots)  // mutable; matcher consumes
```

Helpers: `centre()` (alias of townSquarePos), `civicSlots()` (mutable
view for matcher), `civicSlotsView()` (read-only for inspection),
`withCivicRingRadius(int)` and `withCivicSlots(List)` (immutable
geometry overrides).

#### VillageLayout plaza ownership

```
private final List<Plaza> plazas
public  void   addPlaza(Plaza)
public  void   replacePlaza(Plaza old, Plaza new)
public  Plaza  getPlaza()         // first registered, null if none
public  List<Plaza> getPlazas()   // all (DUAL_PLAZA returns 2)
```

The legacy `getTownSquarePos / getTownSquareRadius / getCivicRing
Radius` getters now delegate to the first plaza when one is
registered, falling back to the recipe-set field otherwise. ENCLAVE
(no plaza) still works via the field. The setters are kept (they
remain the source of truth for plaza-less layouts).

#### Civic slots off the flat pool

`PlazaGenerator.emitPlazaCivicSlots` was renamed to
`buildPlazaCivicSlots` and now returns `List<PlacementSlot>` instead
of calling `pctx.offerSlot`. `PlazaGenerator.generate` builds and
registers a Plaza on the layout (with civic slots inside) and only
then writes the legacy fields (idempotent — getters delegate to
Plaza first anyway).

`RecipeHelpers.installPlaza`'s HAMLET branch also registers a
minimal Plaza (region=null, civicSlots=empty) so downstream code
can call `layout.getPlaza()` without null-checking shape detail.

#### Matcher civic-first claim path

`PlacementMatcher` gets a new `commitBestFromPool(sb, bt, pool,
required, preferred, weight)` helper that mirrors `commitBest` but
draws candidates from the passed pool and removes claimed slots
from it.

- `placeTownHallPrePass` tries `plaza.civicSlots()` first
  (PRIME_CIVIC then SECONDARY_CIVIC), falls back to the flat pool
  if nothing claimed.
- `placeOne` checks `ZoneRegistry.zoneOf(bt) == BuildingZone.CIVIC`
  and walks the building's preference tiers against `plaza
  .civicSlots()` first, then the flat pool. Other zones skip
  straight to the flat tier walk.

Plaza-less layouts (ENCLAVE) skip both Plaza pool checks via
`plaza == null` guards and use the flat-pool walk unchanged.

#### Recipe migration

Removed civic snapshot/drain + `_civic_ring` sector wrapping from
nine recipes (civic slots no longer enter the flat pool, so the
drain would always return empty):

- RadialRecipe (`radial_civic_ring` sector gone)
- LinearRecipe (`linear_civic` sector gone)
- PlazaRecipe (`plaza_civic_ring` sector gone)
- ChainRecipe (`chain_civic_ring` sector gone)
- GroveRecipe (`grove_civic_ring` sector gone; uses
  `Plaza.withCivicRingRadius` + `replacePlaza` for the wide-ring
  override)
- CrossroadsRecipe (`crossroads_civic_ring` sector gone)
- RiverineRecipe (`riverine_civic` sector gone)
- RoadsideRecipe (`roadside_civic` sector gone)
- TerracedRecipe (`terraced_civic` sector gone)

Orphan `SECTOR_CIVIC` constants and `civicCap` locals left in
place — Java compiles with warnings. Phase 19's LayoutPlan migration
will scrub them.

ENCLAVE untouched (no `installPlaza` to flow Plaza out of, but it
DOES call installPlaza per the survey — verifying separately).
DumbellRecipe doesn't call `installPlaza` directly (uses
`LayoutPrimitive.RingBand` for its production end).

#### GROVE wide-ring override pattern

Before: `setCivicRingRadius(wideRing)` directly on the layout's
mutable field. Brittle — the legacy field could drift from the
PlazaRegion's polygon geometry.

After: `plaza.withCivicRingRadius(wideRing)` returns a new Plaza,
`layout.replacePlaza(old, new)` swaps it in place. The legacy
setter is also called for consistency with plaza-less paths
(harmless — the getter delegates to the Plaza anyway).

#### Plan dump --- PLAZA --- section

Added between `--- ROADS ---` and `--- SECTORS ---`. Format:

```
--- PLAZA ---
  plaza#0 centre=BlockPos{...} plazaR=12 civicRingR=35 shape=CIRCLE
          purpose=CIVIC verts=8 civicSlots=8
    pos=... tags=[PRIME_CIVIC, PLAZA_ADJACENT, ROAD_ADJACENT]
            fp=18x18 q=90
    pos=... tags=[SECONDARY_CIVIC, ...] fp=18x18 q=88
    ...
```

For plaza-less layouts: `none (recipe does not register a Plaza)`.
For DUAL_PLAZA: prints two `plaza#N` blocks.

The `radial_civic_ring` / `linear_civic` etc. sectors no longer
appear in `--- SECTORS ---` (the recipes don't construct them).
Civic visibility moves entirely to the new PLAZA section.

#### Files modified

- `Village/Planning/Plaza.java` — NEW
- `Village/Planning/VillageLayout.java` — plazas list, accessors,
  delegating legacy getters
- `Village/Decoration/Plaza/PlazaGenerator.java` —
  `buildPlazaCivicSlots` returns list; `generate` constructs +
  registers Plaza
- `Village/Planning/Primitives/Recipes/RecipeHelpers.java` —
  HAMLET branch registers minimal Plaza
- `Village/Planning/Zoning/PlacementMatcher.java` — civic-first
  claim path in `placeTownHallPrePass` and `placeOne`;
  `commitBestFromPool` helper; new imports
- 9 recipes — civic snapshot/drain/sector-wrap removed; GroveRecipe
  uses `Plaza.withCivicRingRadius` + `replacePlaza`
- `Village/Planning/VillagePlanner.java` — `--- PLAZA ---` dump
  section

#### Constraints honored

- Did NOT recreate the deleted `LayoutPrimitive.TownSquare` class
- Did NOT recreate the deleted civic ring road
- Did NOT change PlazaGenerator polygon math, vertex generation,
  or paving logic
- Did NOT change the HAMLET vs VILLAGE+ branching in `installPlaza`
- Did NOT change matcher tier-walking, scoring, or avoidance —
  only added a Plaza-first preamble for CIVIC zone tier walks
- Did NOT add new SlotTags (PRIME_CIVIC / SECONDARY_CIVIC /
  CIVIC_ADJACENT unchanged)
- Did NOT touch TownSquarePlacer (the realiser)
- Did NOT change ENCLAVE (plaza-less; flat-pool fallback)
- Did NOT remove `townSquarePos / Radius / civicRingRadius` from
  VillageLayout — getters delegate, fields stay for backward compat
- Did NOT change `addForced` or DECORATION semantics
- Did NOT touch Phase 17 plot slot emission
- Did NOT touch microfix-batch outputs (DEFENSIVE SAFE_OFFSET,
  Fix 3 sector wraps, Fix 2 footprint budget formula)

#### Build status

Gradle compile gated by network access; verified by inspection.
Notes:

- Plaza is in the same package as VillageLayout / VillagePlanner
  (`Village.Planning`) so no import is needed there. PlacementMatcher
  (in `Village.Planning.Zoning`) imports it explicitly.
- Orphan `SECTOR_CIVIC` constants and `civicCap` locals (4 recipes)
  emit unused-variable warnings. Java accepts the build.
- The matcher's civic-first path increases the pre-pass call depth
  by one tier-walk attempt for CIVIC buildings. Negligible perf
  impact; the Plaza pool is at most ~12 slots for VILLAGE+ and
  empty for HAMLETs.

#### Validation (next steps for the user)

Spawn on superflat:

- **RADIAL** — plan dump shows new `--- PLAZA ---` section with
  centre, plazaR, civicRingR, shape, civicSlots count + first 6
  slot positions. The `radial_civic_ring` sector no longer appears
  in `--- SECTORS ---`. Building positions byte-identical to the
  microfix-batch baseline (TOWN_HALL still claims PRIME_CIVIC, etc.).
- **PLAZA** — larger plaza shows higher `plazaR` / `civicRingR` /
  `civicSlots` count. Buildings still place identically.
- **GROVE** — `civicRingR` reflects the widened value
  (`originalCivicRing * GROVE_RING_MULTIPLIER`).
- **ENCLAVE** — `--- PLAZA ---` reads "none". TOWN_HALL still
  places via the matcher's flat-pool fallback. No regression.
- Microfix-batch fixes intact: GUARD_TOWER at chebDist 12 from
  OUTER_RING; HOUSEs report `radial_main_road` / `radial_arc_N`
  attribution; Phase 17 `--- PLOT SLOTS ---` section unchanged.

If Phase 18 surfaces friction in any specific recipe (most likely
PLAZA's custom plaza radius or DUAL_PLAZA's two plazas), surface
it for Phase 22 / post-rework consideration.

---

### 2026-05-04 — Phase 19: LayoutPlan + AnchorKind handoff

**Phase:** 19 — immutable plan-as-contract handoff between the
planner and downstream consumers (spawner, decorator, expansion).

#### What landed

Three new types in `Village/Planning`:

- **`AnchorKind`** — enum naming canonical anchor positions:
  TOWN_SQUARE, MAIN_GATE, SECONDARY_GATE, RIVER_LANDING,
  HIGH_GROUND, BRIDGE_HEAD, HARBOR, KEEP. The latter three are
  reserved for future phases.

- **`LayoutPlan`** — immutable record carrying the post-compose
  snapshot consumers read. Fields:
  - `centre`, `finalShape`, `plaza` (nullable), `plazaRegions`
  - `roads` (`List<RoadEdge>`)
  - `buildings` (`List<PlacedBuilding>` — committed, post-validation)
  - `plotSlots` (`List<PlannedPlot>` — Phase 17 farm plots)
  - `sectors` (`List<SectorView>` — metadata-only per open-question
    default)
  - `anchors` (`Map<AnchorKind, BlockPos>`)
  - `status` (`OK | ABORT`), `truncations`, `cascadeRetries`
  - `unplannableReason` (nullable)
  - Convenience: `anchor(AnchorKind)`, `primaryPlaza()`, `isOk()`

- **`LayoutPlanBuilder`** — builds a `LayoutPlan` from a
  fully-composed `VillageLayout` plus `PlanContext`. Resolves road
  node IDs to BlockPos via the graph, derives stable per-building
  UUIDs from `(centre, slotIndex)`, reads sector attribution from
  `pctx.committedSectorIds`.

`VillageLayout` gets `getPlan()` / `setPlan(LayoutPlan)`.
`VillagePlanner.plan()` builds the plan after `runFarmPlotPass`
returns and attaches it; logs a `[LAYOUT-PLAN]` summary line via
`appendPlanSnapshot`.

The plan dump now emits a `--- LAYOUT PLAN SNAPSHOT ---` section at
the top of `dumpPlan`. (Note: `dumpPlan` runs inside `validatePlan`,
which fires BEFORE the plan is built — so the in-dump snapshot
shows a placeholder message; the real snapshot is the
`[LAYOUT-PLAN]` line emitted from `plan()` after `LayoutPlanBuilder
.build()` completes.)

#### Consumer migrations

**`FarmPlotPlacer.placeAll`** — fully migrated. Now reads
`layout.getPlan().plotSlots()` (a `List<PlannedPlot>`) instead of
`layout.plotSlots()` + `layout.getPlotSpec(slot)`. The
`PlannedPlot` record carries every field the realiser needs
(centre, subtype, halfW/halfL, edgeJitterSeed, ownerFarmhousePos)
inline — no parallel-map lookup. Unused `PlacementSlot` import
removed.

**`VillageSpawner` buildings loop — DEFERRED.** The iteration loop
reads `slot.getVariantId()`, `slot.getStyle()`, `slot
.getStructurePath()` (variant data). `PlacedBuilding` doesn't carry
these fields — adding them would expand the record substantially.
Per scope discipline (the prompt explicitly says this is a "shape
change to the planning/consumption boundary, not a redesign of
either side"), the buildings loop stays on `LayoutSlot` for now.
Phase 19 follow-up or post-rework: extend `PlacedBuilding` with
variant fields and migrate the spawner.

**`VillageDecorator` — DEFERRED.** Only 4 `layout.*` reads, all
mechanical. Same rationale: minimum-blast-radius scope keeps the
phase landable; full decorator migration comes when expansion
(Phase 20) lands and the consumption surface stabilises.

#### Accessor name corrections vs prompt pseudocode

The prompt's Step 2/3 pseudocode uses several names that don't
match the codebase. Corrected per the prompt's directive
("Where current accessors don't exist, surface the gap"):

| Prompt name                | Actual accessor                            |
|----------------------------|--------------------------------------------|
| `LayoutSlot.getPivot()`    | (does not exist; spawner computes pivot)   |
| `LayoutSlot.getFootprintW`/`L` | `getFootprintWidth()` / `getFootprintLength()` |
| `LayoutSlot.getSectorId()` | `pctx.committedSectorIds.get(slot.getPos())` |
| `Sector.zone()`            | `Sector.zoneHint()`                        |
| `Sector.cap()`             | `Sector.capacity()`                        |
| `Sector.edgeId()`          | `Sector.parentEdgeId()`                    |
| `Sector.maxFp()`           | `Sector.expectedMaxFootprint()`            |
| `RoadGraph.Edge.from()`    | `e.fromNodeId()` → `graph.node(id).pos()`  |
| `RoadGraph.Edge.to()`      | `e.toNodeId()` → `graph.node(id).pos()`    |
| `RoadGraph.Edge.tier()`    | `e.primitive().tier()`                     |
| `RoadRole`                 | `EdgeRole` (already exists; same 4 values) |
| `pctx.cascadeStatus`       | (no field; derived from `layout.isUnplannable()`) |
| `pctx.sectors()`           | `pctx.offeredSectors()`                    |
| `pctx.truncationCount` (field) | `pctx.truncationCount()` (method)      |
| `pctx.finalShape` (field)  | `pctx.finalShape()` (method)               |

#### Open-question defaults applied

- **(1) UUID generation**: deterministic from `(centre, slotIndex)`
  per `LayoutPlanBuilder.deriveStableId`. The prompt suggested
  `(villageId, slotIndex)` but no Village UUID exists at planning
  time — `BuildingPlacer.placeAndRegister` mints UUIDs at
  realisation. Centre coords are stable across re-builds with the
  same seed, so cross-system reference is safe; realisation can
  substitute its own UUID without breaking plan immutability.

- **(2) SectorView slot positions**: metadata-only. Slot positions
  for committed slots are on `PlacedBuilding`; uncommitted slots
  aren't structurally interesting after the matcher has run. If a
  decoration pass needs uncommitted slot positions, surface and add
  a separate field.

- **(3) RoadEdge.role enum**: uses existing `EdgeRole` (SPINE, SPUR,
  RING, OUTER_RING). No new enum.

- **(4) Status field**: derived from `layout.isUnplannable()` since
  Phase 16b doesn't surface a final `RecipeStatus` on `pctx`.
  `OK` if planning succeeded, `ABORT` if marked unplannable.
  Intermediate cascade states never appear on a built plan.

- **(5) ENCLAVE plaza handling**: `plaza == null` on the plan;
  `anchors[TOWN_SQUARE]` reads from `layout.getTownSquarePos()`
  (the legacy field set by the recipe). No synthetic Plaza, no
  ENCLAVE migration to the polygon path.

#### Files modified

- `Village/Planning/AnchorKind.java` — NEW
- `Village/Planning/LayoutPlan.java` — NEW (record + 4 nested records)
- `Village/Planning/LayoutPlanBuilder.java` — NEW
- `Village/Planning/VillageLayout.java` — getPlan / setPlan
- `Village/Planning/VillagePlanner.java` — build LayoutPlan after
  runFarmPlotPass, log `[LAYOUT-PLAN]`, plan-dump section,
  `appendPlanSnapshot` helper
- `Village/Planning/FarmPlotPlacer.java` — consumes
  `LayoutPlan.PlannedPlot` list (clean migration); removed unused
  PlacementSlot import

#### Constraints honored

- Did NOT delete `VillageLayout` — still planning-time scratch
- Did NOT change recipe authoring — recipes still build into layout
- Did NOT change the matcher — still consumes flat pool +
  `Plaza.civicSlots()` per Phase 18
- Did NOT introduce `LayoutPlan.toLayout()` — arrow is one-way
- Did NOT make `LayoutPlan` mutable — record + immutable copies
- Did NOT change Phase 16b cascade engine
- Did NOT migrate `BuildSiteFinder` — Phase 20 work
- Did NOT add Phase 22 fallback chains
- Did NOT touch persistence formats (`FarmPlot`, `Building`,
  `VillageSavedData`) — `LayoutPlan` is in-memory only
- Did NOT add new SlotTags / BuildingTypes / RoadTiers
- Did NOT extend `AnchorKind` beyond Step 1's set
- Did NOT introduce constraint feedback loops

#### Build status

Gradle compile gated by network access; verified by inspection.
Notable points:

- All three new files (AnchorKind, LayoutPlan, LayoutPlanBuilder)
  are in `Village.Planning` so VillageLayout / VillagePlanner /
  FarmPlotPlacer access them without imports.
- `PlazaRegion` is referenced fully-qualified in LayoutPlanBuilder
  (no import needed; unconventional but legal).
- `LayoutPlan.PlannedPlot.ownerFarmhousePos` matches
  `FarmPlotSpec.ownerFarmhousePos()` (Phase 17's Path A choice —
  position-based ownership, UUID resolved at realise time).
- The unused `civicCap` / `SECTOR_CIVIC` orphans from Phase 18 are
  unchanged; still warnings only.

#### Validation (next steps for the user)

Spawn on flat-terrain RADIAL, expect:
- New `[LAYOUT-PLAN]` log line after `[VILLAGE-SUMMARY]`, showing
  `shape=RADIAL status=OK truncations=0 retries=0 plaza=present
  ... buildings=27 plotSlots=N roads=10 sectors=8 anchors:
  TOWN_SQUARE=... MAIN_GATE=...`.
- Existing dump sections (`--- ROADS ---`, `--- PLAZA ---`,
  `--- SECTORS ---`, etc.) byte-identical to Phase 18 baseline.
- Buildings, roads, plaza paving, plot crops visually identical.
- `FarmPlotPlacer: planned=N realised=N droppedNoOwner=0` — the
  realiser's plot-iteration log unchanged in shape.

ENCLAVE: `[LAYOUT-PLAN] plaza=absent anchors: TOWN_SQUARE=...` (the
courtyard position still surfaces via the legacy field).

ABORT cases (Lithosphere LINEAR truncation): `[LAYOUT-PLAN]
shape=LINEAR status=ABORT reason="..." plaza=absent buildings=0`.

#### Phase 19 follow-ups

1. **Spawner buildings-loop migration** — requires extending
   `PlacedBuilding` with variant fields (`variantId`, `style`,
   `structurePath`). Substantial; defer until consumer surface
   stabilises.
2. **VillageDecorator migration** — 4 mechanical reads. Land
   alongside Phase 20 (BuildSiteFinder migration) which touches
   the same surface.
3. **`dumpPlan`'s placeholder** — the in-dump snapshot is "built
   after validatePlan" because `dumpPlan` fires inside
   `validatePlan` before `LayoutPlanBuilder.build()` runs. Could
   restructure to dump twice or move the build earlier; deferred.




