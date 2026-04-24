# Roads System — Canonical Plan

**Status:** Planning phase complete. Phase 1 ready to begin.
**Owner:** Human-managed. Claude Code reads; does not edit without explicit permission.
**Last updated:** 2026-04-21

---

## How to use this document

This is the canonical source of truth for the road system rework. Everything Claude Code produces for this system must be consistent with the decisions, fiction, and invariants recorded here.

- **Fiction section** (below) defines the world's in-universe origin for great roads. All flavor text, material names, and decorative choices must be consistent with it.
- **Invariants section** defines architectural rules that cannot be violated without human approval.
- **Phase sections** define the implementation order and per-phase contracts.
- **Rejected alternatives** records approaches we considered and chose not to take. Do not re-propose these without new information.

When a decision changes during implementation, this file is updated. When a session discovers something this file didn't anticipate, this file is updated.

---

## The fiction: the Old Realm

**All great roads in this mod were built by a fallen precursor empire — the Old Realm.**

The Old Realm was an ancient civilization that spanned the world before the present age. It collapsed long before the current kingdoms arose. Its roads remain — built to outlast civilizations, and they have.

Current kingdoms are successor cultures, each with a different relationship to the Old Realm:

- **Imperial** kingdoms style themselves as the "true heirs" of the Old Realm. Their roads most closely imitate Old Realm engineering: straight, paved, formal, with stone-slab drainage gutters.
- **Highland** cultures rejected imperial engineering in favor of terrain-adapted pragmatism. Their roads prefer going *around* hills rather than over them, with stacked-stone retaining walls.
- **Nordic** cultures built for their environment: wooden planks over bogs, spruce-log corduroy in wetlands, stone only where it matters.
- **Default** cultures have no formal tradition. Dirt and gravel with the occasional cobble, worn in by use rather than built.

**Why this matters mechanically:**
- Great roads do not decay. They were built to outlast civilizations. (Diegetic, not arbitrary.)
- Great roads have no maintainer. No kingdom owns them. They are shared neutral infrastructure.
- Cultural material variants are architecturally distinct, not cosmetic recolors.
- Ruined Old Realm structures (collapsed way-stations, cracked statues) appear at great-road junctions and as lone roadside landmarks.
- `HistoryTextGenerator` can reference "the old roads" as a pre-existing motif when generating kingdom history.

**Naming convention for great roads:** Latin-esque or archaic-English names suggesting antiquity. `the Via Antiqua`, `the Hammerway`, `Old Caelin Road`, `the Sunset March`. One curated table, crossed with kingdom-culture flavor. **Only the most significant great roads are named — roughly one per 8000×8000 block region.** Scarcity makes names meaningful.

**Everything below in this document assumes this fiction. If the fiction is revised, every downstream decision must be reviewed.**

---

## Core architectural decision

**The road network is a first-class, persistent, world-level graph. Villages plug into it. Trade routes are paths queried across it, with no block ownership. Great roads exist independently of villages and kingdoms.**

This inverts the current system, which creates pairwise trade routes and attempts to build network topology after the fact via overlap-sharing. That approach produces parallel roads, redundant construction, and weak network structure. The inversion is non-negotiable — it is the root cause fix for the observed problems.

---

## Invariants

These are load-bearing. Do not violate without explicit human approval.

1. **Graph is canonical.** `WorldRoadGraph` is the source of truth. `TradeRoute` is a lightweight reference into it. Block placement is derived from graph state, not the reverse.
2. **Routes own no blocks.** Routes hold `List<UUID> edgeIds`. Edges own their block paths (lazily realized).
3. **Great roads never decay.** Maintenance does not apply. Period.
4. **Great roads have no maintainer kingdom.** They are neutral.
5. **Village upkeep is village-local, not kingdom-central.** Each edge's `maintainerVillageIds` list drives decay. Kingdom treasuries do not pay road upkeep directly.
6. **Connector tier derives from village size tier, not caravan traffic count.** Traffic counter remains for internal event-density decisions but is not the promotion driver.
7. **Planning-layer correctness over realiser heroics.** When failures cluster, the fix belongs in planning, not in realisation retry loops.
8. **No abstract method renames on existing interfaces.** New behavior through hooks and helpers.
9. **Tags, primitives, and enums are added only when a concrete consumer requires the distinction.**
10. **Great-road logical graph exists at worldgen, not lazily.** Only block realization is lazy. Village placement depends on the graph being queryable from the first tick.
11. **Road signs respect the sign text constraint.** Village name + direction glyph, no distances.
12. **All material and flavor choices must be consistent with the Old Realm fiction.**

---

## Rejected alternatives

Do not re-propose these. They were considered and rejected for specific reasons.

- **Kingdom treasury funds road upkeep.** Rejected: not historically plausible (medieval kingdoms used corvée labor and local lords, not central treasuries), and the gameplay signal is invisible (player can't tell which kingdom is struggling vs which road is decaying). Replaced by village-local upkeep.
- **Tier promotion based on cumulative caravan traffic count.** Rejected: invisible to players, slow to trigger, disconnected from other visible village state. Replaced by village size tier.
- **Lazy great road generation triggered by player proximity.** Rejected: makes great roads feel like they are appearing for the player rather than pre-existing, and breaks the road-attracted village placement feedback loop. Replaced by deterministic logical graph at worldgen with lazy block realization.
- **Cultural variants as cosmetic recolors only.** Rejected: doesn't differentiate cultures meaningfully. Replaced by architecturally distinct cultures with routing cost implications.
- **Milestones every 300 blocks.** Rejected: too frequent, becomes wallpaper. Replaced with 500–1000 blocks, some deliberately broken.
- **Procedurally naming most roads.** Rejected: dilutes meaning. Replaced with scarcity-based naming (one per 8000×8000 region).
- **Road signs listing distances.** Rejected: Minecraft has no diegetic distance unit, and sign text is too constrained.
- **Central `TradeRoad` entity persisting after graph migration.** Rejected: the graph replaces it. Migration converts existing `TradeRoad`s to edges, then `TradeRoad` is removed.

---

## `WorldRoadGraph` data model

Stored in a new `WorldRoadSavedData`, persisted alongside the atlas.

### `RoadNode`
- World position
- Type: `GREAT_ROAD_ANCHOR`, `TRUNK_JUNCTION`, `VILLAGE_DOCK`, `POI_STUB`, `TERMINUS`, `TOLL_GATE`, `WAYSTATION`
- Optional kingdom-affinity tag
- Optional decoration hook

### `RoadEdge`
- Ordered cell path (canonical)
- Lazily-populated block path (realization)
- Tier: `GREAT_ROAD` / `TRUNK` / `CONNECTOR` / `LOCAL`
- Seeded meander profile (amplitude + frequency, stable across re-realizations)
- Maintenance score (0–100)
- Traffic counter (cumulative caravan uses; internal metric)
- Realize-radius (tier-derived)
- Material profile axes: biome × culture × maintenance × tier × season
- `maintainerVillageIds` (list; null/empty for great roads)
- Endpoint node IDs
- Staleness bitmap (per-cell, for terrain-change invalidation)
- Overgrowth level (derived from maintenance, drives visible-at-range vegetation)

### `EdgeGridIndex`
Coarse 256-block spatial hash. Answers "edges near point P" in O(1).

### `TradeRoute` (revised)
`routeId`, endpoints, `List<UUID> edgeIds`. No backing `TradeRoad`.

---

## Phase breakdown

Each phase has a contract. Phases are implemented in order except where the "vertical slice" at Phase 3a validates end-to-end integration before breadth expansion.

### Phase 1 — Graph skeleton, migration, invariants

**Deliverables:**
- `RoadNode`, `RoadEdge`, `EdgeGridIndex` record types with codecs
- `WorldRoadSavedData` persistence
- One-time migration from existing `TradeRoad`s (chop on cell-path overlap, create junctions)
- Load-time invariant validator (warns, doesn't crash)
- Per-edge meander profile seeded at planning time

**Exit criteria:** World loads, migration runs, invariant validator passes on migrated graph, no behavior changes yet.

### Phase 1.5 — Debug visualization commands

**Deliverables:** `/litv road debug` family:
- `show_graph` — particle trails on edges, beacon beams on nodes
- `highlight_edge <id>`
- `show_parallel_pairs`
- `show_junctions`
- `show_staleness`
- `show_traffic`
- `show_maintenance` — edge color by score
- `show_overgrowth`

**Exit criteria:** Can visually inspect the full graph state in a test world. Must exist before Phase 3b.

### Phase 2 — Village gate docking + roadside allées

**Deliverables:**
- Last 30 blocks of any connector = village's arm (aligned, straight, uses existing `getCapitalGatePositions`)
- Connector routing targets arm endpoint, not gate
- Last 30–60 blocks of approach use internal village `RoadPrimitive` pipeline — seamless material transition
- Culturally-themed allées: saplings alongside final stretch
    - default: oak
    - nordic: spruce
    - imperial: dark oak
    - highland: birch
- Gate threshold decorations always see a clean approach

**Exit criteria:** Test village has a clean, straight, culturally-themed approach road that transitions seamlessly into the village's internal road network.

### Phase 3a — Minimal end-to-end vertical slice

**Critical validation point.** Before full connector routing, cut one vertical slice through the entire pipeline:
- One hand-seeded great road (not procedural)
- One village
- One connector routed from village to the great road
- One junction inserted where they meet
- One caravan dispatched that successfully traverses village → junction → great road terminus

**Purpose:** Expose integration issues in graph/primitive/realization/caravan interaction before they compound through later phases.

**Exit criteria:** Caravan walks the entire slice without stalling. Junction rendering is visually acceptable. All data persists across save/reload.

### Phase 3b — Full connector routing

**Deliverables:**
- Replaces `TradeRouteManager.establishRoutes` for new villages
- Spatial index query: edges + `VILLAGE_DOCK` nodes within 2000 blocks
- **Corridor-aware attractor injection**: before `AtlasRouteRouter` runs, collect cells from every nearby edge within 3 cells of the straight-line corridor; inject as strong attractors. This is the *primary* anti-parallelism mechanism
- Mid-edge connections split the edge, insert `TRUNK_JUNCTION`
- Store dock node ID on `Village`
- Works identically for natural, command-spawned, and future kingdom-expansion villages

**Exit criteria:** New villages in a populated test world route connectors into the existing network without producing parallel duplicates. Command-spawned villages behave identically to natural ones.

### Phase 4 — Parallelism cleanup pass (safety net)

Reduced scope because Phase 3b handles the common case. Periodic scan merges sustained-parallel edges (<120 blocks separation over >400 blocks).

### Phase 5 — Unified `RoadPrimitive` + realization

**Deliverables:**
- `RoadEdge` holds `List<RoadPrimitive>`
- `RouteRealiser` becomes `edge.realise(level)`, walks primitives
- Multi-edge caravan pathing: `CaravanMerchantGoal` iterates concatenated block sequence with junction overlap to prevent stalls
- Terrain-change invalidation: cell-level staleness bitmap on edges; player-triggered significant block changes mark affected cells; next re-realization handles stale cells only
- Tiered realize-radius: `GREAT_ROAD` 768 blocks, `TRUNK` 384, `CONNECTOR`/`LOCAL` current

**Exit criteria:** Trade roads and internal village roads share one primitive system. Distant great roads visible across landscape before player reaches them. Caravans don't stall at junctions.

### Phase 6a — Materials and architecturally distinct cultures

**Deliverables:**
- `PathMaterial.resolve(biome, culture, maintenance, tier, season)`
- **Architectural culture behaviors (not just material):**
    - **Imperial**: straightest possible routes; lowest meander amplitude; stone-slab drainage gutters at edges; cobble-and-stone-brick core
    - **Highland**: routing cost function adds steep-cell penalty (prefers going around hills); stacked-stone retaining walls on downhill sides; narrower cobble-and-gravel
    - **Nordic**: routing cost function reduces swamp penalty; wooden plank over boggy ground; spruce-log corduroy in wetlands
    - **Default**: organic dirt-and-gravel with occasional cobble
- **Great-road "Old Realm" style**: mossy cobblestone + cracked stone brick core, stone-slab drainage, slightly oversized; no cultural override
- Meander amplitude scales: great roads most, imperial least, highland most terrain-adapted
- **Seasonal overlay**: `SeasonTracker` hook. Winter = snow on dirt/gravel; autumn = leaves. Stone tiers resist seasonal change. Dirt decay rates seasonally modulated.

**Exit criteria:** Roads in different kingdoms are visually and behaviorally distinct, not just recolored. Great roads feel ancient regardless of kingdom. Seasons visibly affect road surfaces.

### Phase 6b — Decorative pass

**Deliverables:**
- Milestones on `GREAT_ROAD` every 500–1000 blocks; some deliberately broken/toppled; styled as Old Realm waymarkers
- Junction decorations:
    - `TRUNK_JUNCTION`: culture-biased (well, shrine, notice board, covered rest spot)
    - `GREAT_ROAD_ANCHOR` / great-road junctions: ruined Old Realm structures (collapsed way-station, cracked statue)
- Road signs at `TRUNK_JUNCTION`: village name + direction glyph, one per outbound direction, respects 4-line / ~15-char constraint, no distances
- **Overgrowth visual system**: maintenance drives corridor vegetation
    - High maintenance: 4-block clear sightline either side
    - Low maintenance: encroaching saplings, debris, fallen logs across path (caravans pathfind around)
    - Visible at range — primary maintenance tell for players

**Exit criteria:** Graph features are visually identifiable at a glance. Maintenance state is readable from a distance without UI.

### Phase 6c — Village-maintained upkeep economy

**Deliverables:**
- Each `CONNECTOR`/`TRUNK` edge has `maintainerVillageIds` (usually one, sometimes two)
- Per-village upkeep contribution proportional to `VillageSizeTier`
- Insufficient treasury → that edge's maintenance decays (localized effect)
- Great roads: no maintainer, no decay
- Player intervention hooks (donate to village treasury; manual repair)

**Exit criteria:** Failing villages visibly cause nearby road decay. Thriving villages have pristine connectors. Effect is spatially local and traceable to specific villages.

### Phase 6d — Village-size-driven tier promotion

**Deliverables:**
- Connector tier derived from connected village's current size tier
- Village growth HAMLET → TOWN → CITY widens and upgrades connector materials visibly
- Traffic counter retained as internal metric for event density but not as promotion driver

**Exit criteria:** Village growing a size tier visibly upgrades its connector roads within gameplay-reasonable time.

### Phase 7a — Deterministic anchor graph at worldgen

**Critical reframing from earlier drafts.** Great-road *logical graph* exists immediately at worldgen (microseconds of work). Only *block realization* is lazy.

**Deliverables:**
- Poisson-seeded anchors every 4000–6000 blocks, deterministic from `level.getSeed()`
- Anchors biased toward flat, non-ocean, feature-adjacent (river/mountain-pass) cells
- 2–4 anchors biased into broad directional alignment (world-seeded global axis — each world has a dominant old-road direction)
- Trunk edges between nearby anchor pairs (straight-line up to ~8000 blocks) committed immediately as unrealized cell paths
- Great-road cost profile: lighter existing-road discount, higher steep penalty, higher meander, ~12k node budget
- Cell-path routing cross-ticked via `RoadPlanningTask` queue (atlas fill → routing → commit, one step per tick — never starves server)
- Block realization remains player-proximity-driven

**Exit criteria:** At world creation, the full logical great-road graph exists. Village placement in Phase 9 can query it immediately. Blocks appear when players approach.

### Phase 7b — Named roads (scarcity-based)

**Deliverables:**
- One named great road per ~8000×8000 block region
- Names drawn from curated Old Realm table, crossed with kingdom-culture flavor
- `HistoryTextGenerator` references named roads as pre-existing features in kingdom history

**Exit criteria:** Named roads feel like landmarks, not filler. History text mentions them naturally.

### Phase 7c — Great road character

**Deliverables:**
- Per-great-road seeded parameters: meander amplitude, anchor-direction bias, character tag (`mountain-hugging`, `plains-straight`, `river-following`, etc.)
- Re-realization uses stored params — never changes how a given great road looks

**Exit criteria:** Each great road has distinct, stable visual character.

### Phase 7e — Great road terrain authority ✓ COMPLETE

**Deliverables:**
1. `GreatRoadProfile` — Gaussian-smoothed elevation profile + NORMAL/RAISED/LOWERED/SLOPED_LEFT/SLOPED_RIGHT classification. ✓
2. `RetainingWallBuilder` — Old Realm retaining walls on lateral slopes (height 2–12, capstone every 8). ✓
3. `RoadSupportBuilder` — Solid fill (depth 2–12, gap < 20) + pillared viaduct (gap ≥ 20). ✓
4. `RoadSmoother` — Conservative ±1 nudge for NORMAL; excavation capped at 8 blocks for LOWERED. ✓
5. `UnifiedRoadPlacer` integration — terrain authority pipeline runs before `OrganicRoadPlacer` so heightmap returns profileY at paint time. GREAT_ROAD only. ✓
6. Debug commands: `profile`, `show_supports`, `force_rebuild_profile`, `supports_report`. ✓

**Exit criteria:** ✓ Great roads follow a smoothed grade; raised sections have stone-brick fill or viaducts; lateral slopes have Old Realm retaining walls; all four debug commands functional.

### Phase 7f — Village internal road graph

Village road networks are separate from the world trade graph. Each village owns one
`VillageRoadGraph` (empty until Slice 2 generates it from the layout).

#### Phase 7f Slice 1 — Data model and persistence ✓ COMPLETE

**Deliverables:**
1. `VillageRoadNode` — record with NodeType (INTERIOR, GATEWAY, LANDMARK), `GatewayInfo` (outward direction, arm endpoint, role), `OutwardDirection` enum (8 compass points), `GatewayRole` enum (PRIMARY, SIDE, REAR). ✓
2. `VillageRoadEdge` — record with `EdgeCharacter` (MAIN_STREET, SIDE_PATH, THROUGH_VILLAGE), `cellPath`, `isTraversable`. ✓
3. `VillageRoadGraph` — graph class per village: node/edge CRUD, incidence index, O(n) spatial queries, BFS `findPath`, 6-invariant `validateInvariants`. ✓
4. `VillageRoadsSavedData` — `litv_village_roads` saved data; `getOrCreate(UUID)`, `removeGraph(UUID)`, `validateAll()`, `bootstrapFromVillageSavedData()`. ✓
5. `RoadNode.GatewayLink` — bidirectional link placeholder; always empty until Slice 3. ✓
6. Lifecycle hooks: `VillageSpawner.spawnVillage` creates empty graph; `VillageRealisationSystem` removes graph on village abandonment; `KingdomTaxEvent` bootstraps existing-world graphs on first tick. ✓
7. Debug commands: `village_graph <name>`, `validate_village_graphs`, `show_village_graph <name>`. ✓

**Exit criteria:** ✓ New and existing worlds load without error. Every village has an empty graph. Codec round-trips. `validateAll()` returns empty on a fresh world.

#### Phase 7f Slice 2 — Gateway generation (PLANNED)
Generate gateways from village layouts. LINEAR/ROADSIDE/CHAIN layouts produce 1–2 gateways. Connector planning picks best gateway.

#### Phase 7f Slice 3 — Gateway integration with world graph (PLANNED)
Wire `RoadNode.GatewayLink` bidirectionally. Connectors route from world-graph TERMINUS to VillageRoadGraph gateway.

### Phase 7d — Worldgen ordering and atlas generation speed

**Inserted after Phase 8d** to address testing pain (~25-minute worldgen). No changes to road graph output; all changes are performance and ordering.

**Deliverables:**
1. **Lazy atlas fill during routing** — A* in `AtlasRouteRouter.findGreatRoadRoute` fills atlas cells on-demand (cap 5000/call) instead of pre-filling full corridors. `FillAtlasCorridorTask` removed.
2. **Serialize worldgen** — `WorldgenKingdomSeeder` waits for `greatRoadGenerationComplete` before planning kingdoms. Logs waiting/unblocked transitions.
3. **Raise worldgen-time tick budget** — 500ms atlas fill budget when no players online OR roads not complete; 50ms otherwise.
4. **Eliminate unnecessary iteration** — `WorldAtlas.ensureRegionFilled` collects unfilled cells first; returns `true` immediately if none found.
5. **Timing instrumentation** — `GreatRoadGenerationQueue` logs wall-time, anchor seed time, per-trunk routing time, total lazy fills. `WorldgenKingdomSeeder` logs seeder wall time.
6. **Debug commands** — `/liv road debug worldgen_status` and `/liv road debug worldgen_timing`.

**Expected improvement:** ~25-minute worldgen → under 5 minutes. Atlas fill ~600k cells → ~20k (lazy), budget 50ms → 500ms during worldgen, kingdom seeder no longer competing for atlas budget during road generation.

**Exit criteria:** World creation completes in under 5 minutes on a standard seed. `worldgen_timing` reports lazy fill count < 30,000 for a full generation.

### Phase 8 — Player-facing road gameplay

**The missing "roads that matter to players" layer.**

#### Phase 8a — Travel incentives
- Movement speed bonus on maintained road surfaces, scaled by tier (great roads fastest, dirt paths slowest, unmaintained gives no bonus)
- Extends vanilla `DIRT_PATH` behavior to all road materials

#### Phase 8b — Safety gradient
- Mob spawn suppression within ~6 blocks of maintained roads during day
- Night remains dangerous (hooks bandit events)
- Creates "stick to the road vs cut through wilderness" tension

#### Phase 8c — Shelter expectation
- Along long `GREAT_ROAD` and `TRUNK` stretches, guarantee shelter (inn, shrine, ruined-but-roofed tower, caravanserai, waystation) every ~500 blocks
- Depends on Phase 10a

#### Phase 8d — Tolls and checkpoints
- Kingdoms place `TOLL_GATE` nodes at kingdom borders on `TRUNK`/`GREAT_ROAD` edges
- Structure: gatehouse + boom arm + guard `TownspersonMob`
- Caravans pay fee; drains `CROSS_KINGDOM` routes meaningfully
- Player pays, negotiates free passage (reputation-gated), or detours through wilderness
- Natural event hookpoint: corrupt toll-keeper, unpaid toll confrontation, toll-raid by bandits

**Exit criteria:** Roads are useful to walk on, safer than wilderness, usable for day-planning. Border crossings feel different.

### Phase 9 — Network evolution

**Deliverables:**
- Dead edge handling: village loss → `VILLAGE_DOCK` becomes `TERMINUS`; connector weathers out via existing weathering system; exhausted connectors eventually reclaim their junction back to mid-edge
- Road-attracted village placement: natural-spawn scoring gets strong bonus adjacent to `TRUNK`/`GREAT_ROAD` cells (only works because Phase 7a committed logical graph at worldgen)

**Exit criteria:** Villages placed after great roads exist preferentially spawn along them. Abandoned villages leave visibly decaying roads.

### Phase 10 — Event system expansion

#### Phase 10a — Structures along roads
- Lone buildings at edge creation: inns, shrines, ruined watchtowers (Old Realm), toll houses, caravanserais
- Real `Building` entries, no village parent, can host `TownspersonMob`s
- Roadside village tags: `ROADSIDE_INN`, `ROADSIDE_WAYSTATION`; placement prefers trunk-adjacent cells at biome boundaries or major river crossings

#### Phase 10b — Travelling groups
- Additional `TravellingGroup` types: pilgrims, migrants, kingdom messengers, refugees
- New `RoadEvent` types: `MERCHANT_CONVOY`, `ROYAL_PROCESSION`, `DESERTER_BAND` (cross-kingdom only), `PILGRIMAGE`
- Bandit event density modulated by maintenance (low = more) and cross-kingdom flag

#### Phase 10c — Landmarks and junction activity
- Named bridge landmarks: wide river crossings (4+ block span) promoted to named POIs
- Events bound to `TRUNK_JUNCTION` nodes (scuffle, camp, market day)
- Caravan rerouting on blocked segments

### Phase 11 — Player-initiated road construction

**The agency layer.**

- High-profession Road Engineer can propose connector between two existing villages or extension of dead-end
- Proposal routes through same pipeline as any other edge
- Construction cross-ticked, consumes resources + labor + currency
- Village takes on maintenance after construction
- Player profession XP + reputation boost

### Phase 12 — POI subroads (deferred)

`POI_STUB` nodes for dungeons, ruins, shrines. Same infrastructure as village connectors.

### Phase 13 — Sea route unification (optional)

`SeaRoute` becomes another edge tier with `is-water` flag. Unifies the split currently at the `TradeConnection` interface level.

---

## Implementation order

1. Phase 1 — graph foundation + invariants
2. Phase 1.5 — debug viz
3. Phase 2 — village gate docking + allées
4. **Phase 3a — vertical slice validation** (critical checkpoint)
5. Phase 3b — full connector routing
6. Phase 4 — parallelism safety net
7. Phase 5 — unified primitives + caravan pathing + invalidation
8. Phase 6a — materials + distinct cultures + seasonal overlay
9. Phase 6b — decorative pass
10. Phase 6c — village-local upkeep
11. Phase 6d — size-driven tier promotion
12. Phase 7a — deterministic anchor graph
13. Phase 7b — named roads
14. Phase 7c — great road character
15. Phase 7e — great road terrain authority
16. Phase 8a–d — player-facing gameplay
16. Phase 9 — network evolution
17. Phase 10a–c — events, travelers, landmarks
18. Phase 11 — player construction
19. Phase 12 — POI subroads (deferred)
20. Phase 13 — sea unification (optional)

---

## Session handoff protocol

At the end of every Claude Code session touching roads:
1. Append an entry to `ROADS_PROGRESS.md` with date, current phase, what was done, what broke, what's next.
2. If any decision in this file was revised during the session, update the relevant section here and note the revision in the progress log.
3. Never delete entries from `ROADS_PROGRESS.md`. Append-only.

At the start of every Claude Code session touching roads:
1. Read `ROADS_PROGRESS.md` most-recent entries.
2. Read the current phase's section of this document.
3. Re-read the `Invariants` and `Fiction` sections of this document whenever starting a new phase.