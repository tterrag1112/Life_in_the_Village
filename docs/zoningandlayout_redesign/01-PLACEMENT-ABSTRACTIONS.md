# Village Placement Rework — Abstractions

This document is the single source of truth for the data types, APIs, and cross-cutting contracts of the rework. Phase docs reference it by anchor.

---

## RoadGraph

Replaces `VillageLayout`'s `List<RoadPrimitive>` plus `Map<RoadPrimitive, List<BlockPos>>`.

### Data

```java
final class RoadGraph {
    record Node(int id, BlockPos pos, NodeKind kind, RoadShape.RoadTier tier) {}
    record Edge(int id, int fromNodeId, int toNodeId,
                RoadPrimitive primitive,
                List<BlockPos> centerline,
                EdgeRole role) {}
}

enum NodeKind {
    JUNCTION,        // 3+ edges meet
    GATE,            // village boundary, road exits here
    TERMINUS,        // dead end (spur tip)
    FOCAL,           // plaza centre, town hall pad, etc.
    CASTLE_ANCHOR,   // kingdom rework
    MANOR_ANCHOR,    // kingdom rework
    BRIDGE_HEAD,     // node where a road meets a Bridge primitive
    SHORE_HEAD       // node where a road terminates at water without a bridge
}

enum EdgeRole { SPINE, SPUR, RING }
```

`EdgeRole` is intentionally minimal in v1. Bridge is a primitive type, not an edge role. Arc is a `RING` with curvature in the primitive. Ramp is a `SPUR` tagged with elevation delta in the primitive.

### API

```java
class RoadGraph {
    int addNode(BlockPos pos, NodeKind kind, RoadShape.RoadTier tier);
    int addEdge(int fromNodeId, int toNodeId,
                RoadPrimitive primitive,
                List<BlockPos> centerline,
                EdgeRole role);
    
    Node node(int id);
    Edge edge(int id);
    List<Edge> edgesAt(int nodeId);
    Edge edgeNearest(BlockPos pos);
    
    /** O(edges*centerline.length) — only for sparse queries. */
    boolean isOnAnyEdge(BlockPos pos, int slack);
    
    /** Returns gate nodes in deterministic insertion order. */
    List<Node> gates();
    /** First gate created, conventionally the "main" gate. */
    Node mainGate();
    
    /** Used by the road realiser to walk every edge once. */
    Iterable<Edge> allEdges();
    Iterable<Node> allNodes();
}
```

### Invariants

- Node IDs and edge IDs are monotonically assigned from 0. Insertion order is deterministic given the same recipe execution.
- Centerline cached on the edge at insertion time; never recomputed. If a primitive's centerline depends on the world state, it's the recipe's job to call `computeCenterline` once and pass it.
- Every spur edge has its `from` end at an existing node (the spur's branch point on the parent). Spurs cannot float.

---

## Sector

The middle layer between recipe and slot.

### Data

```java
record Sector(
    String id,                       // "civic_ring", "spur_NE", "shore_strip_W"
    SectorRole role,
    BuildingZone zoneHint,           // ideal occupant zone, NOT a hard filter
    List<PlacementSlot> slots,       // ordered candidate positions
    int capacity,                    // matcher prefers not to exceed
    boolean canGrow,                 // false → drop overflow silently
    GrowthPolicy growth,             // strategy object, see below
    int parentEdgeId,                // RoadGraph edge this sector hangs off, -1 if floating
    PolygonXZ exclusionShape         // optional: exclude growth into this region
) {}

enum SectorRole {
    CIVIC_RING, CIVIC_TIGHT,
    SPUR_CLUSTER, RESIDENTIAL_CLUSTER, RESIDENTIAL_INFILL,
    SHORE_STRIP, RAMP_TERRACE,
    AGRICULTURAL_FRINGE, DEFENSIVE_FRINGE,
    GARDEN_RESERVATION, FESTIVAL_RESERVATION, CEMETERY_RESERVATION,
    NAMED_ANCHOR    // sectors that exist solely to host one specific named building
}
```

### Sector author rules

These are **author-side rules**. They are not enforced by the system; they are the recipe author's contract.

- **Sectors must not overlap.** Two sectors emitting candidate slots in the same XZ region produce undefined matching. A debug assertion catches this if `LIV_DEBUG_SECTOR_OVERLAP=true` is set.
- **Outsized buildings need dedicated sectors.** A CASTLE/PALACE/TEMPLE bigger than the typical CIVIC_RING budget must have its own sector with appropriate `footprintBudget` slots. Generic civic rings will not place a 30×30 building.
- **Reservations have `capacity = 0` and `canGrow = false`.** `GARDEN_RESERVATION`/`FESTIVAL_RESERVATION`/`CEMETERY_RESERVATION` exist only to mark territory for later phases. The matcher places nothing in them; the decoration rework consumes them later.
- **`NAMED_ANCHOR` sectors hold exactly one slot.** Used for `TREASURY`, `CASTLE`, `AUDIENCE_CHAMBER` placement when the recipe needs a specific positional commitment that overflow logic must not redirect.

### Slot rotation in sectors

Sectors emit slots that already know their rotation, because the recipe knows the parent edge direction at that point on the centerline. `PlacementSlot` extends to carry both axes:

```java
record PlacementSlot(BlockPos pos,
                     List<BlockPos> feedingRoad,
                     int feedingEdgeId,        // NEW: graph node ref
                     Set<SlotTag> tags,
                     int footprintBudgetW,     // along-road
                     int footprintBudgetL,     // across-road
                     Rotation forcedRotation,  // NEW: nullable
                     int qualityScore,
                     int terrainPenalty) {     // NEW: 0=flat, scales with variance
    ...
}
```

The matcher subtracts `terrainPenalty` from score (avoids burning rough slots after a failed commit). `forcedRotation` is non-null when the sector needs the building to face a specific way (e.g. plaza-tangent civic), null when the slot is rotation-flexible.

---

## GrowthPolicy

Strategy interface, not enum. Each implementation reads from `PlanContext` plus the sector's stored geometry and returns new slots and possibly new edges.

```java
sealed interface GrowthPolicy permits
    FixedGrowth,           // never grows
    ExtendAlongEdge,       // lengthen parent edge (LINEAR, RIVERINE, ROADSIDE main spine)
    AddRing,               // concentric ring beyond current outer ring (RADIAL, PLAZA, HILLTOP)
    AddSpur                // spawn spur off parent at deterministic angle (CROSSROADS, DUMBELL, SPRAWL)
{
    /**
     * Called when the matcher overflows this sector. Mutates the
     * RoadGraph if needed (new edges/nodes), returns new slots to
     * append to the sector pool. Return empty list to refuse growth.
     *
     * Determinism: implementations seed any randomness from
     * (worldSeed, villageAnchor, sectorId, growRound).
     */
    List<PlacementSlot> grow(PlanContext pctx, Sector sector, int growRound);
}
```

### Concrete strategies

**`FixedGrowth`** — never grows. Used by reservations, fixed-capacity civic rings, named anchors.

**`ExtendAlongEdge`** — appends a segment to the parent edge along its existing direction, surface-snaps via `RoadPrimitive` helpers, emits N new slots along the extension. Parameters: extension length per round, slots per round.

**`AddRing`** — adds a concentric ring road outside the current outer ring with deterministic angular distribution. Updates the graph with one new ring edge. Parameters: ring spacing, slot count.

**`AddSpur`** — picks the next free deterministic angle from a precomputed list (e.g. 8 cardinal+ordinal directions, used in order), spawns a spur edge outward, emits slots along it. Parameters: spur length, slots per spur.

### Growth round semantics

Matcher overflow does up to `MAX_GROW_ROUNDS = 3` rounds. Each round:
1. Identify the originating sector (the one whose `capacity` was exceeded).
2. If `originator.canGrow`, call `originator.growth.grow(pctx, originator, round)` and append returned slots.
3. If originator did not grow OR returned empty, look for a peer sector with the same `SectorRole`. Try to grow that.
4. If no peer, look for a sector with the same `zoneHint`. Try.
5. If no sector accepted growth, drop the building and emit a structured failure log.

The originator → role-peer → zone-peer → drop ordering is **explicit** and tested. The user's stated demand was "houses overflow into another residential cluster, not into a farm field"; this ordering enforces that.

---

## FeatureMap

### Data

```java
final class FeatureMap {
    PolygonXZ hull;                          // computed from sector union
    List<PolygonXZ> plazaPolygons;           // emitted by recipe
    List<WaterFeature> waterFeatures;
    List<CliffFeature> cliffFeatures;
    List<ReservedRegion> reservations;
}

record WaterFeature(PolygonXZ outline, int waterY, WaterKind kind, BlockPos centroid) {}
enum WaterKind { RIVER, LAKE, OCEAN, MARSH }

record CliffFeature(PolygonXZ ridgeFootprint, int topY, int baseY, List<BlockPos> edgeWalk) {}

record ReservedRegion(PolygonXZ shape, ReservationKind kind, String ownerSectorId) {}
enum ReservationKind { GARDEN, FESTIVAL, CEMETERY }
```

### API

```java
class FeatureMap {
    boolean isInsideHull(BlockPos pos);
    boolean isOnWater(BlockPos pos);
    boolean isOnCliff(BlockPos pos);
    boolean isClearForFootprint(BlockPos centre, int w, int l);
    
    PolygonXZ.Edge nearestShoreEdge(BlockPos pos);     // null if no water
    PolygonXZ.Edge nearestCliffEdge(BlockPos pos);     // null if no cliffs
    
    /** True if any reservation overlaps this footprint. */
    boolean isReserved(BlockPos centre, int w, int l);
}
```

### Determinism: two-pass build

This is **the** determinism risk in the rework. Get it right.

```java
// Phase 3 (planning, no chunks loaded):
FeatureMap.buildPlanning(BlockPos origin, long seed, AtlasSampler atlas);
// Uses DeepTerrainInspector (noise-only). Polygons are stable across reloads.

// Phase 4 (realization, chunks loaded):
FeatureMap refined = featureMap.refine(ServerLevel level);
// Snaps Y values to actual heightmap, no polygon reshape.
// If a polygon has shifted >tolerance from noise prediction,
// log a warning but DO NOT change the polygon — only Y values.
```

Recipes consume the planning-time map. Realisers (road placer, building placer) consume the refined map for Y values but trust planning-time XZ polygons. This is the contract that survives chunk-load nondeterminism.

A test harness in Phase 4 spawns the same village three times across reloads and asserts the resulting `LayoutPlan.committedSlots` are identical (sorted by id).

---

## LayoutPlan

Immutable result of planning. Replaces `VillageLayout` as the spawner/decorator/kingdom-prep contract.

```java
record LayoutPlan(
    BlockPos centre,
    RoadGraph graph,
    List<Sector> sectors,             // post-grow, post-matcher
    List<LayoutSlot> committedSlots,  // 1:1 with eventual buildings
    FeatureMap features,
    Map<AnchorKind, Integer> anchors  // values are RoadGraph node IDs
) {}
```

### AnchorKind

```java
enum AnchorKind {
    MAIN_GATE,
    SECONDARY_GATE,           // multiple allowed: matcher uses Map<AnchorKind, List<Integer>> for these via separate accessor
    PLAZA_CENTRE,
    TOWN_HALL_PAD,
    
    // Kingdom rework — schema reserved, no behavior in placement rework
    CASTLE_ANCHOR,
    PALACE_ANCHOR,
    TREASURY_ANCHOR,
    AUDIENCE_CHAMBER,
    MANOR_ANCHOR,             // multiple allowed
    
    // Decoration rework
    CEMETERY_ANCHOR,
    FESTIVAL_GROUND,
    
    // Walls (decoration rework Phase 4)
    WALL_GATE,                // multiple allowed
    WALL_TOWER                // multiple allowed
}
```

Anchors store **node IDs**, never `BlockPos` directly. Position is fetched via `graph.node(id).pos()`. This keeps anchors and graph nodes from drifting, and lets anchors be redirected if a sector's growth reroutes an edge.

`LayoutPlan` is **persistable**. The kingdom rework needs planned-but-unrealized villages to be queryable for capital→vassal planning before any village exists physically. Persistence requires `RoadGraph` to have a Codec; `Sector` likewise. Add codecs in Phase 19 alongside the LayoutPlan introduction.

---

## Cross-cutting contracts

### Recipe contract (`BaseRecipe`)

```java
abstract class BaseRecipe implements ShapeRecipe {
    @Override public final void compose(PlanContext pctx) {
        prepareFeatures(pctx);
        composeSectors(pctx);
        registerAnchors(pctx);
    }
    
    /** Default: no-op. Recipes that need feature awareness override. */
    protected void prepareFeatures(PlanContext pctx) {}
    
    /** REQUIRED. Build the road graph and emit sectors. */
    protected abstract void composeSectors(PlanContext pctx);
    
    /** Default: registers MAIN_GATE if pctx.layout.getMainGateEndpoint() set. */
    protected void registerAnchors(PlanContext pctx) { /* default impl */ }
    
    /** Optional: declares which plaza shape this recipe wants. */
    public PlazaShape preferredPlazaShape() { return PlazaShape.CIRCLE; }
}
```

Recipes that don't fit the three-step model (CLUSTERED, GROVE, OUTPOST may not) override `compose()` directly and skip the base class. The base class is a convenience, not a straitjacket.

### Determinism contract

Every system that introduces randomness during planning seeds its `Random` from a function of `(worldSeed, villageOriginX, villageOriginZ)` plus a stable string identifier for the subsystem. No `Math.random()`. No `level.getRandom()` for planning-time work.

Sector growth seeds from `(worldSeed, villageAnchor, sectorId, growRound)`. This is non-negotiable — without it, growth produces different shapes on different chunk-load orders.

`pctx.rng` is fine for compose-time randomness because compose runs in a single thread with deterministic seed input. It is **not** fine for grow, which runs lazily during matching.

### Slot reservation semantics

Three orthogonal mechanisms serve different needs:

1. **Sectors with `capacity = 0` and `canGrow = false`** — block matcher placement in a region. Used for plaza interiors (already covered by exclusion polygon), garden plots, festival grounds.
2. **`FeatureMap.reservations`** — read by every system that does spatial queries (matcher, sector growth, decoration). The list is the single source of truth.
3. **Forced slots on `VillageLayout`** (current mechanism) — survives for `DECORATION` slot-type uses.

Reservations are emitted *during* sector emission (not afterwards), so the matcher sees them from round 1.

### Sector overlap policy

**Author-enforced, not system-enforced.** Two sectors with overlapping slot regions produce undefined matcher behavior. A debug assertion (`LIV_DEBUG_SECTOR_OVERLAP=true`) catches this during testing. The matcher does not deduplicate.

The exception is `NAMED_ANCHOR` sectors, which are allowed to sit inside a larger sector (e.g. `TREASURY` anchor inside the `CIVIC_RING`). The named-anchor slot is committed first; the larger sector then matches around it.
