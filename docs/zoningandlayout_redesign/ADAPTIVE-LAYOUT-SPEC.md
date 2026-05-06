# Adaptive Layout System — Specification

This document specifies the adaptive layout system that replaces
imperative slot emission inside village recipes. It is the design
reference for the five Claude Code prompts that implement the
system. Read this document alongside `PLACEMENT-REWORK-STATE.md`,
which describes the existing rework's surface that this design
builds on top of.

This is the architectural shift the rework's Section 5 deferred for
empirical comparison. The Phase 23 measurement run made the
comparison: every authored shape except RADIAL is producing slot
positions that fail validation. Patching shape-by-shape costs more
than building the system that makes the failure mode architecturally
impossible. This document is what we build.

## 1. Scope and motivation

### What this system replaces

Today, a recipe's `compose(pctx)` does two distinct jobs at once:

1. **Declare structure.** Roads, plazas, sectors — the bones of
   the village.
2. **Emit slots.** Compute concrete `BlockPos` positions for
   buildings to occupy, push them into a flat pool.

These jobs run in the same method, in the same imperative pass, and
are tangled. The recipe computes slot positions based on the
geometry it *intends* the village to have — not the geometry the
realised roads actually produce. When roads truncate from terrain
(CLIFF_RISE, CLIFF_DROP, WATER), or when the plaza polygon's
vertices land exactly where a spur exits, the slots emit at
positions that no road can feed.

The validator catches this and rejects the village. The Phase 16b
cascade engine retries with rotated parameters. Phase 22 fallback
chains pivot to a different shape. None of these mechanisms fix the
underlying authoring brittleness — they're escape hatches for it.

The 4072-line measurement run showed every authored non-RADIAL
shape failing this way: CROSSROADS 10/15 fail, DUAL_PLAZA 9/19,
HILLTOP 17/21, SPRAWL 3/8, OUTPOST 11/14, DOCKSIDE 2/15. The
authoring is not the problem; the imperative model that asks
authors to compute slot positions before geometry is realised is
the problem.

### What this system does instead

Recipes return a **declarative blueprint**. The blueprint describes
*intent*: "I want 8 civic slots on the plaza perimeter, kept clear
of spur exits." It does not compute positions.

The planner builds the geometry — runs road primitives, generates
the plaza polygon — and then a new component, the `SlotEmitter`,
resolves intentions to actual positions. Every position the emitter
produces is guaranteed to satisfy the validator's road-distance cap
by construction. If a position can't satisfy the cap, the emitter
does not emit it; the intention's count is reduced rather than the
slot being placed somewhere unreachable.

The validator's role shifts from primary gate to sanity check.
Recipe authors stop computing positions; they declare what they
want. Creative variation moves up a level: authors compose
intentions and anchor types creatively rather than computing
positions creatively.

### Relationship to the rework

This system reuses the rework's infrastructure almost entirely.
Specifically:

- `RoadPrimitive` + `RoadResult` + `TerminationReason` (Phase 16)
  for road realisation with truncation tracking.
- `FeatureMap` (Phase 20a Option B, rebuild-on-demand) for terrain
  gating.
- `Plaza` + `PlazaRegion` + `PlazaGenerator` (Phase 18) for polygon
  geometry.
- `PlacementMatcher` including the civic-first claim path
  (Phase 18). Unchanged.
- `LayoutPlan` + `LayoutPlanBuilder` + persistence (Phases 19, 20a).
  Unchanged.
- `BuildSiteFinder.findSiteOnGraph` (Phase 20). Unchanged. The
  `SlotEmitter`'s resolvers borrow this method's pattern (walk
  centerline, generate perpendicular candidates, validate each).
- `FarmPlotPlacer` + `runFarmPlotPass` (Phase 17). Unchanged.
- Cascade engine (Phase 16b) including `RecipeStatus`,
  `ReEmitReason`, `BaseRecipe.reEmit`. The mechanism stays;
  `SlotsDropped` (scaffolding since Phase 16b) gets its first real
  use site here.
- Phase 22 fallback chains. Unchanged.
- `MeasureCommand` (Phase 23.1). Becomes the regression test for
  this system.

What gets deleted: imperative slot emission inside every recipe,
and the `LayoutPrimitive` interface plus its `BuildingCircle`,
`LinearRow`, `RingBand` subclasses. Their patterns are absorbed into
the `Anchor` vocabulary.

## 2. The architectural shift

### Old flow

```
VillagePlanner.plan(pctx):
  status = recipe.compose(pctx)
    // recipe imperatively: builds roads, generates plaza, 
    // emits slots into pctx.offerSlot (flat pool)
  if (status != OK) handleCascade(status)
  matcher.commit(layout)
  if (matcher CORE_PLACEMENT_FAILED) handleCascade
  runFarmPlotPass(pctx)
  validator.validate(pctx)
  if (!validator.passed()) handleCascade or fail
  return LayoutPlanBuilder.build(pctx)
```

The cascade ends up firing because *slots emit before validation
catches them*, and recipes can't correct positionally because
they've already returned.

### New flow

```
VillagePlanner.plan(pctx):
  blueprint = recipe.compose(pctx)
    // recipe DECLARATIVELY: returns roads, plazas, sectors,
    // intentions, named anchors. NO position math, no slot 
    // emission.
  
  realiseRoads(blueprint.roads, pctx)
    // RoadPrimitive applied for each road declaration.
    // RoadResult per road attached to its EdgeRef in pctx.
  
  status = checkPrimarySpine(pctx)
    // Phase 16b cascade trigger, unchanged.
  if (status != OK) {
    blueprint = recipe.reEmit(SevereTruncation, pctx)
    realiseRoads(blueprint.roads, pctx)  // re-do with rotated axis
  }
  
  realisePlazas(blueprint.plazas, pctx)
    // PlazaGenerator produces polygon geometry per declaration.
    // No civic slot emission yet — that's the SlotEmitter's job.
  
  registerSectors(blueprint.sectors, pctx)
    // Sector declarations registered for attribution and caps.
  
  emitterReport = slotEmitter.emit(blueprint.intentions, layout, fmap, pctx)
    // For each intention, run the appropriate Anchor resolver.
    // Resolvers walk realised geometry, generate candidates,
    // validate each (FeatureMap + footprint + road-distance cap).
    // Slots tagged PRIME_CIVIC/SECONDARY_CIVIC route to plaza
    // .civicSlots(). Other slots go to the flat pool.
    // Returns a report: per-intention, how many slots were emitted
    // vs requested. Drop ratio drives possible cascade.
  
  if (emitterReport.dropRatio > THRESHOLD) {
    blueprint = recipe.reEmit(SlotsDropped, pctx)
    // re-run emission with new intentions
  }
  
  matcher.commit(layout)
    // Civic-first path (Phase 18) unchanged.
    // Cascade trigger if too many CORE_PLACEMENT_FAILED.
  
  runFarmPlotPass(pctx)
    // Phase 17 unchanged.
  
  validator.validate(pctx)
    // Sanity check. Should pass — slots were pre-validated.
    // Failure here surfaces a SlotEmitter bug, not a recipe bug.
  
  return LayoutPlanBuilder.build(pctx, villageId)
```

The structural difference is that road realisation, plaza
generation, sector registration, and slot emission are now
sequenced **after** the recipe returns, by the planner, against the
realised geometry. The recipe can no longer compute slot positions
against geometry that doesn't exist yet.

The cascade engine still fires when a primary spine truncates — but
because it now operates on the blueprint, the recipe's `reEmit`
returns a *new blueprint* with the rotated axis or fallback
configuration. The mechanism is the same; the data flowing through
it is declarative.

## 3. The blueprint types

### `LayoutBlueprint`

The new return type of `recipe.compose(pctx)`.

```java
public record LayoutBlueprint(
    ShapeType shape,
    List<RoadDeclaration> roads,
    List<PlazaDeclaration> plazas,
    List<SectorDeclaration> sectors,
    List<SlotIntention> slotIntentions,
    Map<AnchorKind, BlockPos> namedAnchors,
    Map<String, Object> recipeMetadata
) {
    public LayoutBlueprint {
        roads = List.copyOf(roads);
        plazas = List.copyOf(plazas);
        sectors = List.copyOf(sectors);
        slotIntentions = List.copyOf(slotIntentions);
        namedAnchors = Map.copyOf(namedAnchors);
        recipeMetadata = Map.copyOf(recipeMetadata);
    }
}
```

Field semantics:

- **`shape`** — the ShapeType identity. Used by debug, dump, and
  the cascade engine to identify what recipe produced this.
- **`roads`** — declared roads in build order. Order matters for
  the cascade engine: the first road is the primary spine that
  `checkPrimarySpine` evaluates.
- **`plazas`** — zero, one, or many plaza declarations. ENCLAVE-
  style plaza-less recipes return an empty list. DUAL_PLAZA returns
  two.
- **`sectors`** — every sector this recipe wants to register, with
  caps and zone semantics.
- **`slotIntentions`** — declarative slot specifications. The
  emitter consumes these.
- **`namedAnchors`** — pre-computed named-anchor positions
  (TOWN_SQUARE, MAIN_GATE, etc.) for the LayoutPlan. Note:
  recipes know the plaza centre and can name it directly; named
  anchors that depend on road realisation get populated by the
  planner after road realisation, not by the recipe.
- **`recipeMetadata`** — opaque cascade-engine state. Used to track
  cascade axis rotation, retry counts, fallback positions across
  re-emissions of the same recipe.

### `RoadDeclaration`

```java
public record RoadDeclaration(
    EdgeRef ref,
    RoadRole role,
    RoadShape.RoadTier tier,
    RoadPrimitive primitive
) {}
```

The recipe instantiates the `RoadPrimitive` (existing classes:
`StraightRoad`, `Spur`, `Arc`, `Ring`) and binds it to an
`EdgeRef`. The planner runs the primitive at realisation time and
attaches the resulting `RoadResult` to the EdgeRef.

`EdgeRef` is a string-ID handle (see Section 10.4 for rationale):

```java
public record EdgeRef(String id) {
    public static EdgeRef of(String id) { return new EdgeRef(id); }
}
```

Recipes use stable IDs ("spine_n", "spur_e", "ring_outer") so that
intentions can reference roads by ID and so that re-emission can
preserve identity across blueprint regenerations.

### `PlazaDeclaration`

```java
public record PlazaDeclaration(
    BlockPos center,
    int targetRadius,
    PlazaShape shape,
    PlazaPurpose purpose,
    SectorRef civicSector
) {}
```

The planner calls `PlazaGenerator.generate(declaration)` to produce
the polygon `PlazaRegion`. The `civicSector` reference tells the
emitter which sector PRIME_CIVIC/SECONDARY_CIVIC slots should be
attributed to.

`PlazaGenerator` continues to own polygon geometry. What changes:
**`PlazaGenerator.emitPlazaCivicSlots` is deleted.** Civic slot
emission moves into the SlotEmitter's `PlazaPerimeter` resolver.
`PlazaGenerator.installPlaza` (the HAMLET path) still installs the
plaza geometry but no longer emits slots.

### `SectorDeclaration`

```java
public record SectorDeclaration(
    String id,
    SectorRole role,
    BuildingZone zone,
    int cap,
    int maxFp
) {}
```

Same fields as the existing `Sector` record; the declaration is the
recipe's request for the planner to register this sector. Existing
Sector creation paths inside recipes go away.

`SectorRef` is the lightweight handle:

```java
public record SectorRef(String id) {
    public static SectorRef of(String id) { return new SectorRef(id); }
}
```

The recipe declares sectors and references them from intentions by
ID. This decouples intention declaration from sector lookup.

## 4. The SlotIntention vocabulary

```java
public record SlotIntention(
    SectorRef sector,
    Set<SlotTag> tags,
    int desiredCount,
    int maxFootprint,
    Anchor anchor
) {
    public SlotIntention {
        tags = Set.copyOf(tags);
    }
}
```

Field semantics:

- **`sector`** — sector for attribution (Phase 16b Fix 3 — sector
  identity for HOUSE/etc.).
- **`tags`** — slot tags that buildings will match against. Same
  vocabulary as today (PRIME_CIVIC, SECONDARY_CIVIC, ROAD_ADJACENT,
  PLAZA_ADJACENT, FIELD_EDGE, WALL_ADJACENT, RESIDENTIAL_INFILL,
  PRODUCTION_CLUSTER, BACKFILL, etc.).
- **`desiredCount`** — the count the recipe asks for. The emitter
  may return fewer if not enough validator-passing positions exist;
  it never returns more.
- **`maxFootprint`** — the largest footprint the slot should
  accommodate. The emitter uses this for the
  `roadHalfWidth + max(halfW, halfL) + 1` perpendicular offset
  formula, ensuring slots have enough clearance from the road for
  the largest expected building.
- **`anchor`** — the spatial relationship that defines where slots
  should go. See Section 5.

The intention is a description of *requirements*, not positions.
"Eight road-adjacent slots in the production sector, with footprint
budget 16, distributed across all spurs" rather than "slots at
[BlockPos1, BlockPos2, ..., BlockPos8]".

## 5. The Anchor types and their resolvers

`Anchor` is a sealed interface; each implementation specifies a
spatial relationship and the SlotEmitter contains one resolver per
implementation. Six anchor types cover every pattern observed in
the existing recipes.

### `Anchor.PlazaPerimeter`

```java
record PlazaPerimeter(
    SectorRef plazaSector,
    int avoidSpurExitChebDist
) implements Anchor {}
```

Slots at plaza polygon vertices, skipping vertices that are within
`avoidSpurExitChebDist` (chebyshev) of any road exiting the plaza.

**Resolver.** Walk `plaza.region().vertices()`. For each vertex,
compute chebyshev distance to every road's first non-plaza-interior
centerline point. If the minimum is less than
`avoidSpurExitChebDist`, skip that vertex. Otherwise emit a
candidate slot at the vertex and validate against FeatureMap +
footprint clearance + matcher's existing road-footprint reservation
check. Return slots in quality order (highest q first), routed to
`plaza.civicSlots()` instead of the flat pool because the tags
include PRIME_CIVIC or SECONDARY_CIVIC.

This anchor solves the failure pattern in CROSSROADS, PLAZA,
DUAL_PLAZA where civic slots at all polygon vertices overlap spur
road reservations.

### `Anchor.RoadAlong`

```java
record RoadAlong(
    EdgeRef edge,
    int spacing,
    boolean bothSides
) implements Anchor {}
```

Slots along one specific road's centerline, perpendicular at
spacing intervals.

**Resolver.** Look up the edge's centerline from the realised road
graph. Walk the centerline at `spacing` intervals. For each
centerline point, compute candidate positions perpendicular to the
road tangent at offset
`roadHalfWidth + max(intention.maxFp/2, intention.maxFp/2) + 1`. If
`bothSides`, alternate sides; otherwise pick the side away from any
nearby plaza or other road. Validate each candidate. Return slots
to the flat pool with the intention's tags.

This anchor solves the failure pattern in LINEAR (civic spine
slots), SPRAWL (FARMHOUSEs along a single SPINE), and most
ROAD_ADJACENT building placements. It's the workhorse anchor.

### `Anchor.AllSpurs`

```java
record AllSpurs(int spacingPerSpur, boolean bothSides) implements Anchor {}
```

Slots distributed across all SPUR-role roads.

**Resolver.** Find all RoadDeclarations with role=SPUR in the
blueprint. For each, run the `RoadAlong` resolver internally with
`spacing = spacingPerSpur`. Cap the total count at the intention's
`desiredCount`. If a spur was truncated, fewer candidates are
generated for that spur — the count drops, but slots aren't placed
in invalid positions.

This anchor solves PLAZA's PRODUCTION_CLUSTER and CROSSROADS's
spur-cluster patterns. Truncation-tolerance is automatic.

### `Anchor.RingValidated`

```java
record RingValidated(int radius, double radialJitter) implements Anchor {}
```

Slots on a ring at the specified radius, only at points that are
within validator distance of *some* feeding road.

**Resolver.** Generate candidate angular positions evenly spaced
around the ring. For each angle, compute the position at
`radius * (cos(angle), sin(angle))` (with optional jitter). For
each candidate position, check distance to the nearest road edge in
the realised graph. If the distance is greater than the validator's
adjacency cap for the slot's largest expected footprint
(`max=19` for fp=18 ag slots, `max=13` for fp=9 guard towers, etc.),
**skip the position entirely** — do not emit a slot. The candidate
counts that pass become the emitted slots. The intention's count
gets reduced.

This is the critical resolver for solving the failure pattern in
DUAL_PLAZA, OUTPOST, HILLTOP, and the outer agri/defense rings of
PLAZA and CROSSROADS — slots emit only at angular positions
adjacent to a road, never between spurs.

The radial jitter parameter lets recipes break perfect circular
symmetry without breaking the validator-cap invariant.

### `Anchor.NamedAnchor`

```java
record NamedAnchor(AnchorKind kind, int radialOffset) implements Anchor {}
```

Slots at a position near a named anchor, offset radially along that
anchor's feeding road.

**Resolver.** Look up the named anchor's position from
`blueprint.namedAnchors()`. Find the road that feeds it (the road
whose centerline passes nearest the anchor). Walk the centerline
outward `radialOffset` blocks from the anchor. Generate candidates
perpendicular at that point. Validate.

This anchor solves HILLTOP's TOWN_HALL placement (which currently
lands 76 blocks from the peak's road). The TOWN_HALL gets a
NamedAnchor(HIGH_GROUND, 0) intention; the resolver places it at
the peak adjacent to the peak's road.

### `Anchor.RegionalGather`

```java
record RegionalGather(
    BlockPos center,
    int radius,
    int minSpacing
) implements Anchor {}
```

Free placement within a circular region, not road-anchored. Used by
ENCLAVE-style recipes that hand-roll their own courtyards without a
formal road graph.

**Resolver.** Generate candidates within the region using
poisson-disc-style sampling at `minSpacing`. For each, validate
against FeatureMap + footprint clearance only — no road distance
check, because regional gather doesn't require road adjacency.
Validate that each candidate doesn't overlap committed buildings
or roads.

This anchor preserves ENCLAVE-style recipes without forcing them
through the road-graph model.

### Resolver invariant

Every resolver enforces this contract:

> Every `PlacementSlot` returned satisfies the validator's
> road-adjacency cap by construction (or in the case of
> `RegionalGather`, satisfies the alternative validation rules
> for non-road-anchored slots). If a candidate position can't
> satisfy the cap, the resolver does not emit it; the intention's
> count is reduced rather than the slot being placed somewhere
> unreachable.

This is the load-bearing invariant. Recipe authors no longer have
to think about validator distance caps because the resolvers
enforce the cap structurally. The validator's role becomes a
sanity check that should never fire — and when it does fire, that's
diagnostic of a SlotEmitter resolver bug, not an authoring bug.

## 6. The SlotEmitter

```java
public final class SlotEmitter {
    
    public EmitterReport emit(
        List<SlotIntention> intentions,
        VillageLayout layout,
        FeatureMap fmap,
        PlanContext pctx
    );
    
    public record EmitterReport(
        int totalRequested,
        int totalEmitted,
        Map<SlotIntention, Integer> perIntentionEmitted,
        List<DroppedIntention> drops
    ) {
        public double dropRatio() {
            return totalRequested == 0 ? 0.0 
                : 1.0 - ((double) totalEmitted / totalRequested);
        }
    }
    
    public record DroppedIntention(
        SlotIntention intention,
        int emittedCount,
        String dropReason
    ) {}
}
```

The emitter runs each intention through the resolver matching its
`Anchor` subtype. Slots produced are placed into:
- `plaza.civicSlots()` if the intention's tags include
  PRIME_CIVIC or SECONDARY_CIVIC and the intention's anchor is
  `PlazaPerimeter`. The civic-first matcher path (Phase 18) then
  consumes them.
- `pctx.offerSlot(...)` (the flat pool) for everything else. The
  matcher's existing flat-pool consumption path handles them.

The `EmitterReport` lets the planner decide whether under-
satisfaction crosses the cascade threshold (Section 8).

### Implementation pattern per resolver

Each resolver implementation follows this template:

```java
private List<PlacementSlot> resolveRoadAlong(
        SlotIntention intention, Anchor.RoadAlong anchor,
        VillageLayout layout, FeatureMap fmap, PlanContext pctx) {
    
    var edge = pctx.findEdge(anchor.edge());
    if (edge == null) return List.of();  // edge wasn't realised
    
    var centerline = edge.centerline();
    int halfW = intention.maxFootprint() / 2;
    int halfL = intention.maxFootprint() / 2;
    int perpOffset = edge.tier().reservedHalfWidth() 
                     + Math.max(halfW, halfL) + 1;
    
    List<PlacementSlot> result = new ArrayList<>();
    int needed = intention.desiredCount();
    
    for (int i = 0; i < centerline.size() - 1 
                    && result.size() < needed; 
            i += anchor.spacing()) {
        BlockPos pt = centerline.get(i);
        Vec3 tangent = computeTangent(centerline, i);
        Vec3 perp = perpendicular(tangent);
        
        for (int side = 0; side < (anchor.bothSides() ? 2 : 1); side++) {
            BlockPos candidate = pt.offset(
                (int)(perp.x * perpOffset * (side == 0 ? 1 : -1)),
                0,
                (int)(perp.z * perpOffset * (side == 0 ? 1 : -1)));
            
            if (!fmap.isClearForFootprint(candidate, halfW, halfL)) continue;
            if (BuildingFootprint.overlapsExistingBuilding(
                    candidate, halfW, halfL, layout)) continue;
            if (BuildingFootprint.overlapsReservedRoad(
                    candidate, halfW, halfL, pctx)) continue;
            // Validator-cap invariant: by construction, the slot
            // is within roadHalfWidth + max(halfW, halfL) + 1 of
            // the centerline, which is exactly the validator's
            // cap for this footprint. PASSES BY CONSTRUCTION.
            
            result.add(new PlacementSlot(
                candidate, intention.tags(),
                halfW, halfL, intention.sector().id(),
                /* feedRoad: */ centerline,
                /* drift: */ perpOffset
            ));
        }
    }
    return result;
}
```

The pattern is the same as `BuildSiteFinder.findSiteOnGraph`
(Phase 20). The resolvers borrow that method's filter chain — same
FeatureMap call, same footprint check, same road-reservation check.
The `Phase 20 polish item — scoreSlot lift` becomes relevant here:
if the SlotEmitter wants scored selection rather than first-viable
ordering, the lift becomes useful. For initial implementation,
first-viable ordering (positions taken in centerline-walk order) is
acceptable; a polish pass can add scoring.

## 7. Recipe authoring pattern

Recipes shrink dramatically. The authoring pattern:

1. **Build road declarations.** Instantiate primitives and bind to
   stable EdgeRefs.
2. **Declare plazas (if any).** Centre + radius + shape + sector.
3. **Declare sectors.** Roles, zones, caps.
4. **Declare slot intentions.** Tags + count + footprint + anchor
   per intention.
5. **Declare named anchors that the recipe knows.** Plaza centre is
   common. Other named anchors (HIGH_GROUND, RIVER_LANDING) get
   filled in by the planner after geometry realises.
6. **Return blueprint.**

No position math. No `pctx.offerSlot` calls. No `LayoutPrimitive`
subclass instantiation.

### Worked example: CROSSROADS

```java
public class CrossroadsRecipe extends BaseRecipe {
    
    @Override
    public LayoutBlueprint compose(PlanContext pctx) {
        BlockPos c = pctx.center;
        
        var spineN = new RoadDeclaration(EdgeRef.of("spine_n"),
            SPINE, VILLAGE_ROAD, new StraightRoad(c, NORTH, 100, ...));
        var spineS = new RoadDeclaration(EdgeRef.of("spine_s"),
            SPINE, VILLAGE_ROAD, new StraightRoad(c, SOUTH, 100, ...));
        var spurE = new RoadDeclaration(EdgeRef.of("spur_e"),
            SPUR, VILLAGE_PATH, new StraightRoad(c, EAST, 50, ...));
        var spurW = new RoadDeclaration(EdgeRef.of("spur_w"),
            SPUR, VILLAGE_PATH, new StraightRoad(c, WEST, 50, ...));
        
        var civicSec = SectorRef.of("plaza_civic");
        var spurSec = SectorRef.of("spur_production");
        var agriSec = SectorRef.of("outer_agri");
        var defenseSec = SectorRef.of("outer_defense");
        
        var plaza = new PlazaDeclaration(c, 12, SQUARE, CIVIC, civicSec);
        
        return new LayoutBlueprint(
            ShapeType.CROSSROADS,
            List.of(spineN, spineS, spurE, spurW),
            List.of(plaza),
            List.of(
                new SectorDeclaration("plaza_civic",       
                    CIVIC_RING, CIVIC, 9, 46),
                new SectorDeclaration("spur_production",   
                    SPUR_CLUSTER, PRODUCTION, 8, 16),
                new SectorDeclaration("outer_agri",        
                    AGRICULTURAL_FRINGE, AGRICULTURAL, 1024, 18),
                new SectorDeclaration("outer_defense",     
                    DEFENSIVE_FRINGE, DEFENSIVE, 1024, 18)
            ),
            List.of(
                new SlotIntention(civicSec,
                    Set.of(PRIME_CIVIC, ROAD_ADJACENT, PLAZA_ADJACENT),
                    1, 46, new PlazaPerimeter(civicSec, 8)),
                new SlotIntention(civicSec,
                    Set.of(SECONDARY_CIVIC, ROAD_ADJACENT, PLAZA_ADJACENT),
                    8, 46, new PlazaPerimeter(civicSec, 8)),
                new SlotIntention(spurSec,
                    Set.of(PRODUCTION_CLUSTER, ROAD_ADJACENT),
                    8, 16, new AllSpurs(6, true)),
                new SlotIntention(agriSec,
                    Set.of(FIELD_EDGE, ROAD_ADJACENT),
                    8, 18, new RingValidated(60, 0.0)),
                new SlotIntention(defenseSec,
                    Set.of(WALL_ADJACENT, ROAD_ADJACENT),
                    8, 18, new RingValidated(60, 0.0))
            ),
            Map.of(AnchorKind.TOWN_SQUARE, c),
            Map.of()
        );
    }
    
    @Override
    public LayoutBlueprint reEmit(ReEmitReason reason, PlanContext pctx) {
        return switch (reason) {
            case ReEmitReason.SevereTruncation t -> 
                composeWithRotatedAxis(pctx, t);
            case ReEmitReason.SlotsDropped s ->
                // not enough slots emitted — fall back to RADIAL via 
                // chain (Phase 22)
                null;  // signals chain advance
            case ReEmitReason.SectorStarved s -> null;
            case ReEmitReason.ValidationFailed v -> null;
        };
    }
}
```

Compare to the current CROSSROADS implementation: the new version
has zero positional math, zero validator-distance considerations,
zero polygon-vertex iteration. The author specifies what they want
and the system figures out where it goes.

### Worked example: HILLTOP (the most position-fragile current recipe)

```java
public class HilltopRecipe extends BaseRecipe {
    
    @Override
    public LayoutBlueprint compose(PlanContext pctx) {
        BlockPos peak = findPeakPosition(pctx);  // FeatureMap-based
        
        var ringMid = new RoadDeclaration(EdgeRef.of("ring_mid"),
            RING, VILLAGE_PATH, new Ring(peak, 25, ...));
        var ringLower = new RoadDeclaration(EdgeRef.of("ring_lower"),
            RING, VILLAGE_PATH, new Ring(peak, 50, ...));
        var spineToPeak = new RoadDeclaration(EdgeRef.of("spine_peak"),
            SPINE, VILLAGE_ROAD, new StraightRoad(peak, downhill, 80, ...));
        
        var civicSec = SectorRef.of("hilltop_civic");
        var midSec = SectorRef.of("hilltop_mid_arc");
        var lowerSec = SectorRef.of("hilltop_lower_arc");
        var agriSec = SectorRef.of("hilltop_base_agri");
        
        return new LayoutBlueprint(
            ShapeType.HILLTOP,
            List.of(ringMid, ringLower, spineToPeak),
            List.of(),  // no plaza polygon — peak is the civic anchor
            List.of(
                new SectorDeclaration("hilltop_civic",       
                    NAMED_ANCHOR, CIVIC, 1, 32),
                new SectorDeclaration("hilltop_mid_arc",     
                    RESIDENTIAL_CLUSTER, RESIDENTIAL, 6, 16),
                new SectorDeclaration("hilltop_lower_arc",   
                    RESIDENTIAL_INFILL, RESIDENTIAL, 5, 16),
                new SectorDeclaration("hilltop_base_agri",   
                    AGRICULTURAL_FRINGE, AGRICULTURAL, 8, 18)
            ),
            List.of(
                // TOWN_HALL at the peak, on the road feeding the peak.
                // Old recipe: places at peak, 76 blocks from the road. FAIL.
                // New recipe: NamedAnchor resolves to a position 
                // adjacent to the spine_peak's nearest centerline point.
                new SlotIntention(civicSec,
                    Set.of(PRIME_CIVIC, HIGH_GROUND, HILLTOP_PEAK),
                    1, 32, new NamedAnchor(AnchorKind.HIGH_GROUND, 0)),
                
                new SlotIntention(midSec,
                    Set.of(RESIDENTIAL_CORE, HIGH_GROUND, ROAD_ADJACENT),
                    8, 16, new RoadAlong(EdgeRef.of("ring_mid"), 6, true)),
                
                new SlotIntention(lowerSec,
                    Set.of(RESIDENTIAL_OUTER, ROAD_ADJACENT),
                    4, 16, new RoadAlong(EdgeRef.of("ring_lower"), 8, true)),
                
                new SlotIntention(agriSec,
                    Set.of(FIELD_EDGE, PASTURE),
                    8, 18, new RingValidated(80, 0.1))
            ),
            Map.of(
                AnchorKind.HIGH_GROUND, peak,
                AnchorKind.MAIN_GATE, /* spine endpoint, computed by planner */
                    null  // planner fills this in
            ),
            Map.of()
        );
    }
}
```

The TOWN_HALL placement that currently fails (chebDist=76 from
nearest road) becomes structurally correct: the `NamedAnchor`
resolver places the slot adjacent to the spine's nearest centerline
point, which is by definition within the validator's distance cap.

## 8. Cascade engine integration

### What changes

The cascade engine's mechanism — `RecipeStatus`, `ReEmitReason`,
`BaseRecipe.reEmit`, fallback chains, the per-village summary line —
is unchanged. What changes:

1. **`reEmit(reason, pctx)` returns a new `LayoutBlueprint`**
   instead of mutating pctx and returning RecipeStatus. The
   blueprint is a fresh declaration; the planner re-realises roads,
   re-runs emission against the new blueprint.

2. **`SlotsDropped` ReEmitReason gets its first real use site.**
   Phase 16b created this as scaffolding. It's now consumed when
   the SlotEmitter under-satisfies intentions beyond the threshold.

3. **`ValidationFailed` ReEmitReason becomes mostly inert.** With
   slots pre-validated by the emitter, validator failure should be
   rare. When it does fire, it's a SlotEmitter bug, not a normal
   cascade trigger. The Phase 22 ValidationFailed-triggers-cascade
   logic still exists as a safety net, but it should rarely fire.

### `SlotsDropped` semantics

```java
record SlotsDropped(
    double dropRatio,             // emitter.report.dropRatio
    Map<SlotIntention, Integer> perIntentionDrop,
    List<String> dropReasons      // e.g. "outer_agri ring radius 60 had only 3 viable angles"
) implements ReEmitReason {}
```

The cascade engine fires `SlotsDropped` when the EmitterReport's
`dropRatio` exceeds a configurable threshold (default: 0.30 — more
than 30% of intended slot capacity dropped). The recipe's `reEmit`
can:

- Return a new blueprint with rotated axis or different anchor
  parameters (e.g., `RingValidated(50)` instead of
  `RingValidated(60)` if the larger ring had too many gaps).
- Return `null` to signal "I can't fix this," advancing the
  Phase 22 fallback chain to the next recipe.

The threshold is a configurable static field on the cascade engine,
not per-recipe. Recipes that want different sensitivity can wrap
their reEmit logic to ignore certain SlotsDropped patterns.

### `SectorStarved` — also gets a use site

```java
record SectorStarved(
    String sectorId,
    int requestedCap, 
    int actuallyEmitted
) implements ReEmitReason {}
```

This was also Phase 16b scaffolding. It now fires when a specific
sector's intentions emit far below their cap (e.g., a CIVIC sector
that was supposed to support 9 buildings emits only 2). Recipes can
respond by reducing that sector's expected demand or restructuring
the layout to avoid relying on the starved sector.

For initial implementation, only `SlotsDropped` is consumed; 
`SectorStarved` remains scaffolding until a use case justifies its
consumption logic.

### Cascade flow with new types

```
recipe.compose(pctx) returns blueprint v1
realiseRoads(v1.roads)
checkPrimarySpine — if FAIL, recipe.reEmit(SevereTruncation) 
  returns blueprint v2 with rotated axis
  realiseRoads(v2.roads)  
realisePlazas, registerSectors
slotEmitter.emit(v2.intentions) returns report
  if report.dropRatio > 0.30:
    recipe.reEmit(SlotsDropped) returns blueprint v3 or null
    if null: chain advance (Phase 22) — try fallback recipe
    if v3: re-realise from v3
matcher.commit(layout)
  if too many CORE_PLACEMENT_FAILED: 
    recipe.reEmit(MatcherStarved) — new ReEmitReason if needed
runFarmPlotPass
validator.validate — should pass; if not, surface as a bug
```

The pivot points are unchanged; only the data flowing through them
becomes blueprints.

## 9. Migration of existing components

### Reused unchanged

These components stay exactly as they are. Only the caller changes.

- **`RoadPrimitive`** + subclasses (`StraightRoad`, `Spur`, `Arc`,
  `Ring`) + `RoadResult` + `TerminationReason`. Phase 16. Recipes
  instantiate primitives in `RoadDeclaration`s; the planner
  realises them post-compose.
- **`FeatureMap`**. Phase 20a. SlotEmitter resolvers consume the
  same way BuildSiteFinder does.
- **`Plaza`** + `PlazaRegion` for polygon geometry. Phase 18.
- **`PlazaPaver`**. Decoration pass, unchanged.
- **`PlacementMatcher`** including the civic-first claim path.
  Phase 18.
- **`LayoutPlan`** + `LayoutPlanBuilder` + `Phase 20a` persistence.
  Phase 19, 20a.
- **`BuildSiteFinder`**. Phase 20. Expansion path unchanged.
- **`FarmPlotPlacer`** + `runFarmPlotPass`. Phase 17.
- **`PlanContext`** carries cascade state (cascadeRetryCount,
  cascadeAxisRotation, truncationCount, cascadeChain,
  cascadeChainPosition). Phase 16b, 22.
- **`Validator`** + its rules. The rules don't change; the role
  shifts from primary gate to sanity check.
- **`BuildingProfile`**, `BuildingProfileRegistry`, `SlotTag`
  vocabulary, `BuildingType`, `BuildingZone`.
- **`VillageTypeData`**, Phase 21 schema, Phase 22 fallback chains.
- **`MeasureCommand`**. Phase 23.1.

### Modified

These components keep their role but change signature or
implementation.

- **`BaseRecipe.compose(pctx)`** — return type changes from
  `RecipeStatus` to `LayoutBlueprint`. Body becomes declarative.
  All subclasses updated.

- **`BaseRecipe.reEmit(reason, pctx)`** — return type changes from
  `RecipeStatus` to `LayoutBlueprint` (or `null` to signal fallback
  chain advance). The mechanism is the same; the data is a
  blueprint instead of a status.

- **`VillagePlanner.plan(pctx)`** — orchestration changes per
  Section 2's flow. The planner now realises roads, plazas,
  sectors, and runs the SlotEmitter, in that order, against the
  blueprint.

- **`VillageLayout`** — still mutable planning scratch. Slot
  population now happens via the SlotEmitter writing into it; no
  longer via recipe-side `pctx.offerSlot` calls (except for the
  farm plot pass, which still uses a specialized add-after-the-fact
  path).

- **`PlanContext.offerSlot`** — kept for backward compatibility and
  for `runFarmPlotPass` to use. Recipe code stops calling it. The
  method signature doesn't change; the call sites narrow
  dramatically. (Section 10.2 records the alternative of renaming;
  rejected for migration cost.)

- **`PlazaGenerator`** — `emitPlazaCivicSlots` is deleted. Civic
  slot emission moves to the SlotEmitter's `PlazaPerimeter`
  resolver. `generate()` and `installPlaza()` continue to produce
  polygon geometry, just without slot emission.

- **`PlacementMatcher.scoreSlot`** — Phase 20 deferred polish item.
  Becomes load-bearing if the SlotEmitter wants scored slot
  selection. For initial implementation, first-viable ordering is
  acceptable; scoring can be added in a polish pass.

### Deleted

These components go away in Phase D of the migration.

- **`LayoutPrimitive`** interface and all subclasses
  (`BuildingCircle`, `LinearRow`, `RingBand`, plus any others).
  Their patterns are absorbed into the `Anchor` vocabulary:
  - `BuildingCircle` (slot emission around a center) →
    `RingValidated`
  - `LinearRow` (slot emission along a road) → `RoadAlong`
  - `RingBand` (slot emission in a band between two radii) → can be
    expressed as two `RingValidated` intentions, or as a new
    `Anchor.RingBandValidated` if recipes need it. Defer this; if
    no recipe needs it after the port, the new anchor isn't
    written.
- All imperative slot emission code inside recipes. Per-recipe; no
  surviving call sites of `pctx.offerSlot` from recipe bodies.

### New

- **`LayoutBlueprint`** record (Section 3).
- **`RoadDeclaration`** record.
- **`PlazaDeclaration`** record.
- **`SectorDeclaration`** record.
- **`SlotIntention`** record (Section 4).
- **`Anchor`** sealed interface and six implementations
  (Section 5).
- **`SlotEmitter`** class (Section 6).
- **`EmitterReport`** record.
- **`EdgeRef`** + **`SectorRef`** records (lightweight handles).

## 10. Open questions resolved

These were flagged as open at the end of the planning conversation.
Settled with my recommended answers; flag any to flip before
implementation begins.

### 10.1 — `LayoutPrimitive` interface deletion

**Resolution: yes, delete in Phase D.** Claude Code should grep
first to confirm no consumer outside recipes (the rework's
codebase grep should show only recipe call sites + the interface
itself + its subclasses). If a non-recipe caller exists, surface
before deletion.

### 10.2 — `PlanContext.offerSlot` keep or rename

**Resolution: keep the name.** After the recipe port, only
`runFarmPlotPass` (Phase 17) and similar after-the-fact emitters
call it. The narrow caller list is acceptable. Renaming would
require touching FarmPlotPlacer for no real benefit.

### 10.3 — Civic slot routing (emitter vs planner)

**Resolution: emitter does it.** When the SlotEmitter produces
slots from a `PlazaPerimeter` intention whose tags include
PRIME_CIVIC or SECONDARY_CIVIC, it routes them directly to
`plaza.civicSlots()` rather than to the flat pool. The routing
logic stays with the emission logic.

### 10.4 — `EdgeRef` semantics (string ID vs object)

**Resolution: string ID.** Recipes construct EdgeRefs at compose
time with stable IDs ("spine_n", "spur_e"). The planner resolves
the string IDs against realised edges in `pctx` post-realisation.
This keeps the cascade engine simple — re-emission returns a new
blueprint with the same string IDs, the planner re-resolves
against newly-realised edges.

### 10.5 — `SlotsDropped` as cascade trigger

**Resolution: yes, with threshold 0.30.** The SlotEmitter's report
includes `dropRatio()`. If it exceeds 0.30 (i.e., more than 30% of
intended slot capacity was dropped), the cascade fires
`SlotsDropped`. The threshold is configurable on the cascade
engine, not per-recipe. The 0.30 default matches Phase 16b's
truncation cascade thresholds in spirit (0.6 hard, 0.5 soft) but
is more permissive because slot drops are normal under truncation —
a recipe authoring 8 outer ring slots that lose 2 to truncation
shouldn't trigger cascade; losing 5 should.

The threshold tuning is something Phase 23.2 measurement might
inform — initial value 0.30 is a guess. Re-measure after Phase E
and adjust if the rate is wrong.

## 11. Migration plan

Five Claude Code prompts. Each landable in one cycle.

### Phase A — Foundation types and orchestration changeover

**Scope:** Define `LayoutBlueprint`, `SlotIntention`, `Anchor`
sealed interface (no resolvers yet), `SectorDeclaration`,
`RoadDeclaration`, `PlazaDeclaration`, `EdgeRef`, `SectorRef`.
Modify `BaseRecipe.compose()` signature to return
`LayoutBlueprint`. Modify `BaseRecipe.reEmit()` signature to
return `LayoutBlueprint` or null. Modify
`VillagePlanner.plan(pctx)` orchestration per Section 2's flow.

**Constraint:** No recipe is ported yet. All existing recipe
subclasses are temporarily stubbed to return a placeholder
blueprint that fails fast — Phase A is a compile-clean
architectural cutover, not a working spawn.

**Validation:** `./gradlew compileJava` succeeds. Spawning fails
gracefully (recipe stubs return placeholder blueprints that the
planner refuses).

**Out of scope for Phase A:** Implementing the SlotEmitter
(Phase B). Porting any recipe (Phases C, D).

### Phase B — SlotEmitter and all six resolvers

**Scope:** Implement `SlotEmitter.emit()`. Implement six
resolvers — `PlazaPerimeter`, `RoadAlong`, `AllSpurs`,
`RingValidated`, `NamedAnchor`, `RegionalGather`. Each resolver
enforces the validator-cap invariant per Section 5.

**Constraint:** No recipe ported yet. Resolvers are unit-testable
in isolation by feeding synthetic geometry.

**Validation:** Unit tests pass. Synthetic test cases:
- `RoadAlong` on a straight 50-block centerline produces 8 slots
  at spacing 6 perpendicular at offset 6.
- `PlazaPerimeter` on a 12-vertex polygon with 4 spur exits
  produces 8 vertex slots (skipping the 4 nearest each spur exit).
- `RingValidated` at radius 60 around a 4-spur graph produces 4
  slots (one near each spur exit), not 8.
- `NamedAnchor(HIGH_GROUND, 0)` near a peak with a feeding road
  produces a single slot at the peak adjacent to the road.

### Phase C — Port RADIAL

**Scope:** Port `RadialRecipe` to the new declarative pattern.
Verify: spawn a RADIAL village on superflat. Verify byte-identical
building positions to the post-Phase-20 baseline (centre =
(-2496, -60, 292), 27/27 placed, 27/27 validated).

**Constraint:** Other recipes are still stubbed. RADIAL is the
canonical baseline; if its port works, the system works.

**Validation:** `/litv measure 1 [seed=known-radial-seed]` produces
the post-Phase-20 baseline plan dump byte-identically. PLAZA
SECTION, ROADS, COMMITTED BUILDINGS, VALIDATION SUMMARY all match.

### Phase D — Port the remaining recipes

**Scope:** Port every other recipe (CROSSROADS, PLAZA, LINEAR,
DUAL_PLAZA, OUTPOST, HILLTOP, SPRAWL, DOCKSIDE, TERRACED, ENCLAVE,
GROVE, BEND_HAMLET, MOUNTAIN_KEEP, MINING_CAMP, SACRED_GROVE,
SPARSE_HOLDING, VINEYARD_TERRACE — every recipe class that
currently exists). Delete `LayoutPrimitive` and its subclasses.
Delete `PlazaGenerator.emitPlazaCivicSlots`.

**Constraint:** Each recipe's port follows the worked examples in
Section 7. Mechanical work; no architectural decisions.

**Validation:** Compile clean. Spawn one of each shape on flat
terrain. Confirm at least one of each spawns successfully without
crashing or aborting. The success rate per shape doesn't have to
match Phase E's threshold yet — that's the closeout phase.

### Phase E — Re-measure, close out

**Scope:** Run `/litv measure 300` on default Minecraft. Capture
the JSONL and console summary. Compare against the pre-adaptive
measurement (the 4072-line log).

**Validation:** Per-shape primary-success rate ≥ 90% on default
Minecraft for shapes that previously failed. Overall success rate
≥ 80% (terrain rejections still apply; some sites are genuinely
unbuildable). Cascade engine activity remains observable
(`SlotsDropped` trigger count > 0; truncation cascades fire on
appropriate sites). No CRASHes.

**Closeout:** Update `PLACEMENT-REWORK-STATE.md`:
- Phase 23 row → marked landed with measured numbers.
- Section 4 backlog: "LINEAR + PLAZA recipe authoring rework"
  retires (the new system makes broken authoring architecturally
  impossible). "Architectural-shift experiment" retires (just did
  it).
- New section: "Adaptive layout system — landed" describing the
  shift, measured success rates, retirements.
- Section 5's Path 1 vs Path 2 question retires; this system is
  effectively a hybrid (Path 1's recipes + Path 2's terrain-aware
  resolution).

After Phase E, the rework is complete. The state doc transitions
from active rework state to historical reference + backlog.

## 12. Validation strategy

### Per-phase smoke tests

Each phase has a specific smoke test. Phase A: compile clean.
Phase B: resolver unit tests. Phase C: RADIAL byte-equality. Phase
D: one of each shape spawns. Phase E: measurement run thresholds.

### Regression detection (long-term)

After Phase E, `MeasureCommand` (Phase 23.1) is the regression
detector. Any future placement-related change should re-run
measurement and confirm success rates haven't dropped.

### Observability

The plan dump's `--- LAYOUT PLAN SNAPSHOT ---` section (Phase 19)
gains an emitter report subsection:

```
--- LAYOUT PLAN SNAPSHOT centre=(...) ===
  shape=CROSSROADS status=OK truncations=0 retries=0
  ...
  emitter: requested=33 emitted=31 dropRatio=0.06
    plaza_civic intent#0  PRIME_CIVIC          requested=1  emitted=1
    plaza_civic intent#1  SECONDARY_CIVIC      requested=8  emitted=8
    spur_production       PRODUCTION_CLUSTER   requested=8  emitted=7  
      (1 dropped: spur_w truncated, only 1 viable position)
    outer_agri            FIELD_EDGE           requested=8  emitted=8
    outer_defense         WALL_ADJACENT        requested=8  emitted=7  
      (1 dropped: cliff at angle 270°, position not validator-passable)
```

This gives Phase E and future polish passes a clear view of which
intentions are dropping slots and why.

### Validator role

The validator continues to run after slot commit. It should pass
in nearly all cases — the SlotEmitter pre-validated the positions.
When validator fails, treat it as a SlotEmitter bug (probably a
resolver under-filtering) rather than a recipe authoring error.
The plan dump's VALIDATION SUMMARY section becomes a diagnostic
tool for SlotEmitter resolver quality, not for recipe authoring
quality.

## 13. Out of scope

- **Atlas / kingdom selection.** Independent rework.
- **NPC profession assignment, building inhabitants, economy.**
  Independent reworks.
- **Decoration content.** Decoration rework.
- **Trade roads, road decay, pathfinding.** Trade road rework.
- **Bridge / Stairway / Causeway road primitives.** Listed as
  post-rework backlog in the state doc; orthogonal to this system
  (they're new RoadPrimitive subclasses, not new Anchors).
- **Recipe creative/aesthetic redesign.** This system fixes the
  position-emission brittleness; aesthetic redesigns remain a
  separate effort and don't block on this work.
- **Adding new ShapeTypes, BuildingTypes, SlotTags, AnchorKinds.**
  Schema-stable. New shapes can be added later as new recipes
  emitting blueprints.

## 14. Conventions and naming

- **`LayoutBlueprint`** — declarative return of `compose()`. The
  unit of recipe output.
- **`SlotIntention`** — declarative slot specification. The unit
  of intent within a blueprint.
- **`Anchor`** — spatial relationship type. Sealed interface.
- **`SlotEmitter`** — resolves intentions to positioned slots.
- **`EmitterReport`** — summary of emission, including per-
  intention emit counts and drop reasons.
- **`EdgeRef`** — string-ID handle for a road declaration.
- **`SectorRef`** — string-ID handle for a sector declaration.
- **Resolver** — a method on SlotEmitter that handles one Anchor
  subtype. Six resolvers in the initial implementation.

Phrases used informally in this document with specific meanings:

- **"Recipe authors"** — code authors writing `compose()` methods
  for new shapes. Distinct from "village type authors" who write
  JSON declaring fallback chains and tier preferences.
- **"Validator-cap invariant"** — the SlotEmitter contract that
  every emitted slot satisfies the validator's road-distance cap
  by construction. Section 5.
- **"Pre-validation"** — running validator-equivalent checks during
  emission, so that the post-commit validator pass becomes a
  sanity check rather than a primary gate.

## 15. What this document is not

This document is the design reference for a five-phase
implementation, not the implementation itself. It does not:

- Specify exact method signatures beyond record fields. Phase A's
  prompt should let Claude Code pick natural Java idioms within
  the records' shapes.
- Specify exact resolver algorithms beyond the patterns in
  Section 5. Phase B's prompt should let Claude Code use the
  `BuildSiteFinder.findSiteOnGraph` template as guidance.
- Specify which existing recipe gets ported in which order beyond
  RADIAL first. Phase D's prompt can let Claude Code batch the
  remaining recipes by similarity.
- Specify a tuning value for the SlotsDropped threshold beyond the
  initial 0.30 guess. Re-measure after Phase E and tune.
- Specify how `MeasureCommand` reports adaptive-system metrics
  beyond the emitter-report snapshot in Section 12.

This document also does not lock in the failure-of-this-system
recovery: if Phase E reveals the adaptive system underperforms in
ways that aren't fixable by tuning, the rework's Section 5
architectural question reopens. That outcome is unlikely — every
failure mode in the 4072-line log maps to an Anchor pattern that
prevents it — but it's not impossible.

