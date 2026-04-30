# Life in the Village — Layout API Reference (post-Phase-10)

Reference for `litv-layout-recipe`. Authoritative on signatures and
patterns; defers to `docs/placement-rework/01-PLACEMENT-ABSTRACTIONS.md`
for the contract behind them.

## 1. PlanContext

### Fields

| Field | Type | Use |
|-------|------|-----|
| `pctx.layout.getCenter()` | `BlockPos` | Village origin |
| `pctx.layout.getTerrain()` | `TerrainProfile` | Legacy terrain snapshot — recipes prefer `pctx.features` |
| `pctx.features` | `FeatureMap` | Hull, water polygons, cliff polygons, plaza polygons, reservations. Authoritative for feature queries. |
| `pctx.density.getRing1Radius()` | `int` | Inner spur length guidance |
| `pctx.density.getRing2Radius()` | `int` | Outer ring radius guidance (density scale, not absolute) |
| `pctx.remaining` | `List<StarterBuilding>` | All unplaced buildings. **Do NOT read or claim** — the matcher consumes this after `composeSectors` returns. |
| `pctx.rng` | `Random` | Seeded RNG. Use only for visual jitter; sector growth derives its own seeds. |
| `pctx.worldSeed` | `long` | Stable world seed |
| `pctx.level` | `ServerLevel` | World reference |
| `pctx.allowRidgePlacement` | `boolean` | Set `true` early in compose if buildings may sit on ridges (HILLTOP, TERRACED). |

### Sector / slot API

```java
// Emit a sector. The matcher consumes these.
pctx.offerSector(new Sector(id, role, zone, slots, capacity,
                            canGrow, growth, parentEdgeId, exclusionShape));

// Read what's been offered so far (debug / introspection).
List<Sector> sectors = pctx.offeredSectors();
boolean any = pctx.hasSectors();

// Snapshot/drain pattern — used during the Phase 8-15 transition for
// helpers that still write into the flat slot pool (installPlaza,
// LayoutPrimitive.RingBand.emitSlots, etc.). Capture the slot-pool
// size before the helper runs, then drain into a sector after.
int snapshot = pctx.slotPoolSize();
helperThatWritesFlatSlots(pctx);
List<PlacementSlot> drained = pctx.drainSlotsSince(snapshot);
```

### Do not call

- `pctx.offerSlot(...)` / `pctx.offerRoadSlots(...)` — legacy flat-slot
  path. Only used by unconverted recipes; new recipes emit sectors only.
- `pctx.claimByZone(...)` / `pctx.claimType(...)` / `pctx.claimTownHall()`
  — recipes never claim from `remaining`. The matcher owns that.
- `pctx.runMatcher()` — VillagePlanner calls this after compose. Do not
  invoke it manually.

### Surface helpers (still used)

```java
BlockPos snapped = pctx.solidSurface(pos);  // snap Y to MOTION_BLOCKING_NO_LEAVES
```

---

## 2. Building the road graph

New recipes build the graph explicitly: register every node and
capture every edge id. The legacy 3-arg `pctx.layout.addRoad(primitive,
level, seed)` still works as a backward-compat shim (it auto-creates
TERMINUS endpoints), but new recipes prefer `addEdge` so sector
`parentEdgeId` wiring is unambiguous.

### Spine pattern

```java
RoadShape.RoadTier tier = RoadShape.RoadTier.VILLAGE_ROAD;

// 1. Endpoints — surface-snapped
BlockPos gateStart = pctx.solidSurface(...);
BlockPos gateEnd   = pctx.solidSurface(...);

// 2. Register nodes; capture node ids
int gateStartId = pctx.layout.addNode(gateStart, NodeKind.GATE,     tier);
int gateEndId   = pctx.layout.addNode(gateEnd,   NodeKind.TERMINUS, tier);

// 3. Build the primitive and add it as an edge with role + node ids
RoadPrimitive.StraightRoad spine = new RoadPrimitive.StraightRoad(
        gateStart, gateEnd, 4.0, tier);
int spineEdgeId = pctx.layout.addEdge(
        gateStartId, gateEndId, spine, pctx.level, pctx.worldSeed,
        EdgeRole.SPINE);

// 4. Centerline (if needed for slot positioning)
RoadGraph graph = pctx.layout.getRoadGraph();
List<BlockPos> spineCenterline = graph.edge(spineEdgeId).centerline();

// 5. Tell the layout this is the main gate
pctx.layout.setMainGateEndpoint(gateEnd);
pctx.layout.addGatePosition(gateStart);
pctx.layout.addGatePosition(gateEnd);
```

### Spur pattern

```java
// Mid-edge branch: snap branch point to an existing node if one is
// close, otherwise insert a JUNCTION node.
int branchNodeId = graph.findNearestNode(branchPos, /* slack */ 4);
if (branchNodeId < 0) {
    branchNodeId = pctx.layout.addNode(branchPos, NodeKind.JUNCTION, tier);
}
int spurEndId = pctx.layout.addNode(spurEndPos, NodeKind.TERMINUS, tier);

RoadPrimitive.Spur spur = new RoadPrimitive.Spur(
        spineCenterline, branchPos, directionRad, length, 3.0, tier);
int spurEdgeId = pctx.layout.addEdge(
        branchNodeId, spurEndId, spur, pctx.level, pctx.worldSeed,
        EdgeRole.SPUR);
```

### NodeKind values recipes commonly use

| Value | Use |
|---|---|
| `JUNCTION` | 3+ edges meet (spur branch off a spine, ring tangent) |
| `GATE` | Village boundary — road exits the layout here |
| `TERMINUS` | Dead end (spur tip, isolated road end) |
| `FOCAL` | Plaza centre, town-hall pad |

`CASTLE_ANCHOR`, `MANOR_ANCHOR`, `BRIDGE_HEAD`, `SHORE_HEAD` are reserved
for kingdom-rework / bridge-rework recipes. Don't use them in general
recipes.

### EdgeRole values

| Value | Use |
|---|---|
| `SPINE` | Main road / trunk |
| `SPUR` | Branches off a spine |
| `RING` | Closed loop or arc |

The matcher and the visualizer both read `EdgeRole`; pick by intent.

---

## 3. Sector emission patterns

A `Sector` is a region of placement intent: ordered slots, a capacity
hint, a growth strategy, and an optional parent road edge. The
constructor:

```java
new Sector(
    String id,                  // recipe-prefixed, stable, unique
    SectorRole role,            // matcher peer-overflow lookup
    BuildingZone zoneHint,      // hint, not a filter
    List<PlacementSlot> slots,  // ordered candidate positions
    int capacity,               // matcher prefers not to exceed; 0 = reservation
    boolean canGrow,
    GrowthPolicy growth,        // FixedGrowth, AddRing, AddSpur, ExtendAlongEdge
    int parentEdgeId,           // RoadGraph edge id, -1 if floating
    PolygonXZ exclusionShape    // null unless excluding a region from growth
)
```

### Civic ring (fixed, around the plaza)

`installPlaza` (in `RecipeHelpers`) writes civic slots into the flat
pool. Drain them into a `CIVIC_RING` sector with `FixedGrowth`:

```java
int snapshot = pctx.slotPoolSize();
RecipeHelpers.installPlaza(pctx, centre, PlazaShape.CIRCLE);
List<PlacementSlot> civicSlots = pctx.drainSlotsSince(snapshot);
if (!civicSlots.isEmpty()) {
    pctx.offerSector(new Sector(
        "<recipe>_civic_ring", SectorRole.CIVIC_RING, BuildingZone.CIVIC,
        civicSlots, /* capacity tuned to plaza size */ 8,
        false, FixedGrowth.INSTANCE, -1, null));
}
```

The `drainSlotsSince` snapshot/drain pattern is a Phase 8 transitional
helper. After Phase 18 (plaza polygon ownership consolidation), plaza
slot emission moves out of the flat pool entirely and this dance goes
away. For now, every recipe that uses `installPlaza` does this.

### Spur cluster (growable via AddSpur)

```java
List<PlacementSlot> slots = generateSlotsAlongCenterline(
    spurCenterline, spurEdgeId, TAGS_SPUR_CLUSTER,
    /* stride */ 6, /* perpOffset */ 7, /* quality */ 50);

pctx.offerSector(new Sector(
    "<recipe>_spur_NE", SectorRole.SPUR_CLUSTER, BuildingZone.PRODUCTION,
    slots, /* capacity */ 5, true,
    new AddSpur(/* spurLength */ 48, /* slotsPerSpur */ 4),
    spurEdgeId, null));
```

### Residential infill along a spine (growable via ExtendAlongEdge)

```java
pctx.offerSector(new Sector(
    "<recipe>_main_residential", SectorRole.RESIDENTIAL_INFILL,
    BuildingZone.RESIDENTIAL,
    slots, /* capacity */ 8, true,
    new ExtendAlongEdge(/* extension */ 32, /* slotsPerRound */ 4),
    spineEdgeId, null));
```

### Outer agri ring (growable via AddRing)

```java
pctx.offerSector(new Sector(
    "<recipe>_outer_agri", SectorRole.AGRICULTURAL_FRINGE,
    BuildingZone.AGRICULTURAL,
    slots, /* capacity */ 6, true,
    new AddRing(/* ringSpacing */ 20, /* slotCount */ 8),
    -1, null));
```

`-1` for `parentEdgeId` is correct here — concentric rings don't hang
off a single edge.

### Reservation (no slots, no growth)

```java
pctx.offerSector(new Sector(
    "<recipe>_market_reservation", SectorRole.GARDEN_RESERVATION,
    BuildingZone.CIVIC,
    List.of(), /* capacity */ 0, false,
    FixedGrowth.INSTANCE, -1, exclusionPolygon));
```

`exclusionPolygon` is a `PolygonXZ` that other sectors' growth must
avoid. A reservation with `capacity=0` and an empty slot list is the
canonical "block this region from being used" pattern.

### Author rules

- **Id prefix.** Always start the id with the recipe name (`radial_…`,
  `riverine_…`). Sector ids share a flat namespace across recipes.
- **`parentEdgeId`.** `-1` only for floating sectors (rings, reservations).
  Road-adjacent sectors must point at the right edge so growth knows
  which road to extend.
- **`zoneHint`.** Pick the `BuildingZone` whose buildings the sector
  primarily wants. The matcher uses this for peer-overflow ordering;
  it is **not** a filter.
- **Capacity tuning.** Tune to match similar sectors in existing
  recipes (RadialRecipe is the reference). When in doubt, err small —
  growth picks up the slack.
- **No overlap.** Sectors must not overlap. See
  `docs/placement-rework/01-PLACEMENT-ABSTRACTIONS.md` § "Sector".

### SectorRole values

```
CIVIC_RING, CIVIC_TIGHT,
SPUR_CLUSTER, RESIDENTIAL_CLUSTER, RESIDENTIAL_INFILL,
SHORE_STRIP, RAMP_TERRACE,
AGRICULTURAL_FRINGE, DEFENSIVE_FRINGE,
GARDEN_RESERVATION, FESTIVAL_RESERVATION, CEMETERY_RESERVATION,
NAMED_ANCHOR
```

---

## 4. Slot generation helper

A pattern recipes use repeatedly to walk a centerline and emit slots
on both sides:

```java
private static List<PlacementSlot> generateSlotsAlongCenterline(
        List<BlockPos> centerline, int edgeId, Set<SlotTag> tags,
        int stride, int perpOffset, int quality) {
    List<PlacementSlot> out = new ArrayList<>();
    if (centerline.size() < 2) return out;

    for (int i = stride; i < centerline.size(); i += stride) {
        BlockPos pos  = centerline.get(i);
        BlockPos prev = centerline.get(Math.max(0, i - 1));
        BlockPos next = centerline.get(Math.min(centerline.size() - 1, i + 1));

        int hx = next.getX() - prev.getX();
        int hz = next.getZ() - prev.getZ();
        double hl = Math.sqrt((double) hx * hx + (double) hz * hz);
        if (hl < 1) continue;

        Rotation rot = rotationAlongRoad(prev, next);

        for (int side : new int[] { -1, +1 }) {
            int px = (int) Math.round(-hz / hl * perpOffset * side);
            int pz = (int) Math.round( hx / hl * perpOffset * side);
            BlockPos slotPos = pos.offset(px, 0, pz);

            out.add(new PlacementSlot(
                slotPos, centerline, edgeId, tags,
                /* footprintBudgetW */ 14, /* footprintBudgetL */ 14,
                rot, quality, /* terrainPenalty */ 0));
        }
    }
    return out;
}
```

### PlacementSlot constructor (9-arg, post-Phase-6)

```java
new PlacementSlot(
    BlockPos pos,
    List<BlockPos> feedingRoad,    // road centerline; null permitted
    int feedingEdgeId,             // RoadGraph edge id, -1 if none
    Set<SlotTag> tags,
    int footprintBudgetW,          // along-road budget
    int footprintBudgetL,          // across-road budget
    Rotation forcedRotation,       // null = rotation-flexible
    int qualityScore,
    int terrainPenalty             // 0 = flat; matcher subtracts from score
)
```

The legacy 5-arg constructor (`pos, feedingRoad, tags, footprintBudget,
qualityScore`) still exists for backward compatibility with unconverted
recipes. New recipes always use the 9-arg form so `feedingEdgeId` and
`forcedRotation` are explicit.

---

## 5. Feature-aware patterns

Recipes consult `pctx.features` (a `FeatureMap`) for terrain queries.
The `FeatureMap` is built by `FeatureMap.buildPlanning(...)` before
compose runs and refined by `refine(level)` after. XZ polygons are
authoritative; Y values are advisory (terrain step refines them).

### Feature queries

```java
FeatureMap features = pctx.features;

PolygonXZ hull             = features.hull();              // village footprint
List<WaterFeature> water   = features.waterFeatures();
List<CliffFeature> cliffs  = features.cliffFeatures();
List<PolygonXZ> plazas     = features.plazaPolygons();     // already-pinned plazas
List<ReservedRegion> resvs = features.reservations();

boolean inHull   = features.isInsideHull(pos);
boolean onWater  = features.isOnWater(pos);
boolean onCliff  = features.isOnCliff(pos);
boolean clear    = features.isClearForFootprint(centre, w, l);

PolygonXZ.Edge shore = features.nearestShoreEdge(pos);     // null if no water
PolygonXZ.Edge cliff = features.nearestCliffEdge(pos);     // null if no cliffs
```

### Riverine: orient spine parallel to shore

```java
@Override
protected void prepareFeatures(PlanContext pctx) {
    PolygonXZ.Edge shore = pctx.features.nearestShoreEdge(pctx.layout.getCenter());
    if (shore == null) return;  // composeSectors falls back below
    // Stash the shore edge for composeSectors via a private field, or
    // recompute it there. The PlanContext is the only state shared
    // across the three lifecycle methods.
}

@Override
protected void composeSectors(PlanContext pctx) {
    PolygonXZ.Edge shore = pctx.features.nearestShoreEdge(pctx.layout.getCenter());
    if (shore == null) {
        PlacementFailureRecorder.record(
            PlacementFailureRecorder.Reason.TERRAIN_UNSUITABLE,
            "no water detected for RIVERINE",
            pctx.layout.getCenter(),
            VillageTypeData.ShapeType.RIVERINE.name());
        new RadialRecipe().compose(pctx);
        return;
    }
    // Use shore.a() / shore.b() to derive a parallel heading...
}
```

### Hilltop: locate the peak via cliff features

```java
List<CliffFeature> cliffs = pctx.features.cliffFeatures();
if (cliffs.isEmpty()) {
    /* fallback */ return;
}
CliffFeature peak = cliffs.stream()
    .max(Comparator.comparingInt(CliffFeature::topY))
    .orElseThrow();
// peak.ridgeFootprint() is a PolygonXZ; centre slots inside it.
pctx.allowRidgePlacement = true;  // peak buildings sit on the ridge
```

### Per-slot guards

```java
// Skip slots that fall on water or cliffs
if (pctx.features.isOnWater(slotPos)) continue;
if (pctx.features.isOnCliff(slotPos)) continue;

// Stay inside the village hull (especially relevant for AddRing growth)
if (!pctx.features.isInsideHull(slotPos)) continue;

// Footprint clearance — the matcher will retry adjacent positions
// if this fails, but pre-filtering saves cycles
if (!pctx.features.isClearForFootprint(slotPos, w, l)) continue;
```

### Terrain fallback recorder

Always record the reason before delegating:

```java
PlacementFailureRecorder.record(
    PlacementFailureRecorder.Reason.TERRAIN_UNSUITABLE,
    "no <feature> detected",
    centre, VillageTypeData.ShapeType.<SHAPE_TYPE>.name());
new <FallbackRecipe>().compose(pctx);
return;
```

Phase 22 introduces a declarative fallback chain. Until then, the
in-recipe fallback shown here is the convention.

---

## 6. SlotTag reference

Use `EnumSet.of(SlotTag.X, SlotTag.Y)` to build tag sets.

| Tag | When to emit |
|-----|-------------|
| `PRIME_CIVIC` | Plaza-adjacent tangent positions, best quality |
| `SECONDARY_CIVIC` | Outer civic ring, lower quality |
| `CIVIC_ADJACENT` | Near civic area but not prime |
| `PLAZA_ADJACENT` | Adjacent to a plaza polygon edge |
| `PRODUCTION_CLUSTER` | Spur clusters, workshop districts |
| `PRODUCTION_SPUR_END` | Spur tip positions |
| `PRODUCTION_INFILL` | Road-side production infill |
| `RESIDENTIAL_INFILL` | Standard road-side houses |
| `RESIDENTIAL_OUTER` | Outer-edge residential |
| `FIELD_EDGE` | Adjacent to farm plots / open land |
| `PASTURE` | Open ground suitable for animals |
| `WALL_ADJACENT` | Near a defensive perimeter |
| `GATE_ADJACENT` | Near a village entrance |
| `HIGH_GROUND` | Elevated terrain (watchtower, mine) |
| `SHORE` | Adjacent to a water body |
| `PIER_ADJACENT` | Dock/pier slot near water |
| `RIVER_BANK` | Along a river channel |
| `TERRACE_EDGE` | On a slope terrace step |
| `FOREST_EDGE` | At treeline boundary |
| `HILLTOP_PEAK` | Highest point of a hill |
| `ROAD_ADJACENT` | Generic fallback for any road-side slot |
| `BACKFILL` | Last-resort filler |

---

## 7. RecipeHelpers (slimmed)

The following helpers are still safe to call from sector-aware recipes:

```java
// Angle conversion: FlatDirection → radians
double rad = RecipeHelpers.directionRadOf(TerrainAnalyzer.FlatDirection.EAST);

// Modulo-aware angle delta in (-π, π]
double d = RecipeHelpers.angleDelta(a, b);

// Branch-index helpers for placing spurs along a centerline
List<Integer> idxs = RecipeHelpers.branchIndicesAlong(centerline, fractions);

// Local tangent direction (radians) at centerline[idx]
double tangentRad = RecipeHelpers.localTangentRad(centerline, idx);

// Distribute pctx.remaining round-robin into N buckets
List<List<VillageTypeData.StarterBuilding>> buckets =
    RecipeHelpers.claimAndBucketProdResidential(pctx, bucketCount);

// Plaza installer — emits civic slots into the flat pool.
// Use the snapshot/drain pattern (section 3) to wrap into a sector.
RecipeHelpers.installPlaza(pctx, centre, PlazaShape.CIRCLE);
```

**Removed from this doc** because they wrote into the flat slot pool
or relied on the deleted rescue passes:

- `placeAgriculturalRing`, `placeDefensiveRing`, `placeStragglersRingBand`
  — replace with direct `AGRICULTURAL_FRINGE` / `DEFENSIVE_FRINGE`
  sector emission using `AddRing` growth.
- `rescueTownHallOnAnyRoad`, `rescueTownHallOnRoad` — the matcher's
  town-hall pre-pass and the new growth path replace these.

**May still appear in unconverted recipes** but should not be called
from sector-aware recipes:

- `stubSpursAlongRoad`, `scatterBucketAt`, `scatterBucketAtRoadEnd`
  — these write into the flat pool. If a sector-aware recipe needs
  similar geometry, replicate the slot-generation logic inside
  `composeSectors` and emit a sector instead.
- `placeFarmCluster` — Phase 17 reworks farm placement; for now,
  prefer direct sector emission over this helper.

---

## 8. Road non-overlap rules

**Every road piece must begin where the previous connecting piece ends.**
Overlap causes visual artifacts and footprint conflicts.

### After `installPlaza` (civic ring road)

`civicRingRadius` is the civic **building** placement radius. The ring
road sits at `civicRing - 6` with a half-width of 3, so its outer edge
is `civicRing - 3`:

```java
int civicRing = pctx.layout.getCivicRingRadius();
int ringRoadOuter = Math.max(9, civicRing - 3);
BlockPos roadStart = pctx.solidSurface(new BlockPos(
    centre.getX() + (int) Math.round(Math.cos(dirRad) * ringRoadOuter),
    centre.getY(),
    centre.getZ() + (int) Math.round(Math.sin(dirRad) * ringRoadOuter)));
```

### After a Ring (explicit Ring primitive)

A road following a ring must **start at the ring's perimeter**:

```java
BlockPos roadStart = pctx.solidSurface(new BlockPos(
    ringCentre.getX() + (int) Math.round(Math.cos(dirRad) * ringRadius),
    ringCentre.getY(),
    ringCentre.getZ() + (int) Math.round(Math.sin(dirRad) * ringRadius)));
```

A road leading **into** a ring must end at the ring's perimeter, with
the ring centre offset a further `ringRadius` beyond the road endpoint.

### Adding the road as an edge (canonical form)

```java
List<BlockPos> centerline = pctx.layout.getRoadGraph()
        .edge(edgeId).centerline();
BlockPos roadEnd = centerline.get(centerline.size() - 1);
// next road's "from" node sits at roadEnd
```

Use `pctx.layout.addEdge(fromNodeId, toNodeId, primitive, level, seed,
role)` rather than the legacy 3-arg `addRoad(primitive, level, seed)`.
The legacy form auto-creates two TERMINUS nodes with no `EdgeRole` —
fine for transitional recipes, but new recipes give the caller control
over node kinds, edge role, and the captured edge id (needed for
`Sector.parentEdgeId`).

### Full dumbbell pattern (plaza → trunk → far ring)

```java
int civicRing     = pctx.layout.getCivicRingRadius();
int ringRoadOuter = Math.max(9, civicRing - 3);
int ringBRadius   = Math.max(8, pctx.density.getRing1Radius() / 2 + 4);
int armLength     = pctx.density.getRing1Radius() + pctx.density.getRing2Radius();

BlockPos trunkStart  = pctx.solidSurface(offset(centre, dirRad, ringRoadOuter));
BlockPos trunkEnd    = pctx.solidSurface(offset(trunkStart, dirRad, armLength));
BlockPos ringBCentre = pctx.solidSurface(offset(trunkEnd, dirRad, ringBRadius));

// Helper: offset(pos, angleRad, dist) = pos + (cos*dist, 0, sin*dist)
```

---

## 9. Common slot tag sets (named constants)

```java
private static final Set<SlotTag> TAGS_CIVIC =
    EnumSet.of(SlotTag.SECONDARY_CIVIC, SlotTag.ROAD_ADJACENT);

private static final Set<SlotTag> TAGS_PRODUCTION =
    EnumSet.of(SlotTag.PRODUCTION_CLUSTER, SlotTag.ROAD_ADJACENT);

private static final Set<SlotTag> TAGS_RESIDENTIAL =
    EnumSet.of(SlotTag.RESIDENTIAL_INFILL, SlotTag.ROAD_ADJACENT);

private static final Set<SlotTag> TAGS_SPUR_END =
    EnumSet.of(SlotTag.PRODUCTION_SPUR_END, SlotTag.ROAD_ADJACENT);

private static final Set<SlotTag> TAGS_FIELD =
    EnumSet.of(SlotTag.FIELD_EDGE, SlotTag.ROAD_ADJACENT);
```
