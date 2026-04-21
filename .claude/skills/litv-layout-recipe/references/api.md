# Life in the Village — Layout API Reference

## PlanContext Fields

| Field | Type | Use |
|-------|------|-----|
| `pctx.layout.getCenter()` | `BlockPos` | Village origin (town square centre) |
| `pctx.layout.getTerrain()` | `TerrainProfile` | Full terrain snapshot |
| `pctx.density.getRing1Radius()` | `int` | Inner spur length guidance |
| `pctx.density.getRing2Radius()` | `int` | Outer ring radius guidance (acts as a density multiplier — not an absolute; use as a scale input to derived geometry) |
| `pctx.remaining` | `List<StarterBuilding>` | All unplaced buildings — do NOT claim directly; let the matcher consume |
| `pctx.rng` | `Random` | Seeded RNG |
| `pctx.worldSeed` | `long` | Stable world seed for road noise |
| `pctx.level` | `ServerLevel` | World reference |

## PlanContext Methods

```java
// Add a road to the layout and get its centerline back
List<BlockPos> centerline = pctx.layout.addRoad(primitive, pctx.level, pctx.worldSeed);

// Emit road-adjacent slots along a centerline
// spacing=6-8, perpOffset=6-8, quality=25-60 typical
pctx.offerRoadSlots(centerline, spacing, perpOffset, tagSet, quality);

// Emit a single named slot (for features: shore, peak, terrace, etc.)
pctx.offerSlot(new PlacementSlot(pos, feedingRoad, tags, footprintBudget, qualityScore));

// Snap a BlockPos to the solid surface Y
BlockPos snapped = pctx.solidSurface(pos);

// Allow buildings on ridge terrain (call early in compose() when needed)
pctx.allowRidgePlacement = true;

// Run the matcher — called by VillagePlanner after compose(); do NOT call manually
// pctx.runMatcher();  ← do NOT call from inside compose()
```

## Road Primitives

All road primitives are passed to `pctx.layout.addRoad(primitive, pctx.level, pctx.worldSeed)`
which returns the computed `List<BlockPos>` centerline.

### StraightRoad
```java
new RoadPrimitive.StraightRoad(from, to, driftAmplitude, tier)
// driftAmplitude: 3=subtle, 6=visible wander, 10+=don't
```

### CurvedRoad
```java
new RoadPrimitive.CurvedRoad(from, to, curvature, driftAmplitude, tier)
// curvature: 0=straight, 0.15=gentle, 0.3=strong, 0.5+=near-semicircle
```

### Ring (closed loop)
```java
new RoadPrimitive.Ring(centre, radius, driftAmplitude, tier)
```

### Arc (partial ring, has two endpoints)
```java
new RoadPrimitive.Arc(centre, radius, startAngle, arcSpan, driftAmplitude, tier)
// startAngle/arcSpan in radians; arcSpan=Math.PI for a half-ring
```

### Spur (branches off an existing road)
```java
new RoadPrimitive.Spur(parentCenterline, branchPointHint, directionRad, length, driftAmplitude, tier)
// branchPointHint snaps to nearest actual point on parent
```

### Road tiers (from best to worst-looking)
```
RoadShape.RoadTier.MAIN_ROAD       — trunk, widest
RoadShape.RoadTier.VILLAGE_PATH    — standard village road
RoadShape.RoadTier.FOOTPATH        — narrow connector / stub spur
```

### Angle helpers
```java
RecipeHelpers.directionRadOf(TerrainAnalyzer.FlatDirection dir)  // FlatDirection → radians
// FlatDirection values: EAST(0), SOUTH(PI/2), WEST(PI), NORTH(-PI/2)
```

---

## Layout Primitives

Layout primitives call `emitSlots(pctx)` to offer slots to the matcher.
They also have `place(pctx)` for direct building placement — do NOT use `place()`
in matcher-compatible recipes; use `emitSlots()` instead, except for `TownSquare`
which must call both.

### TownSquare
```java
int capacity = Math.max(6, Math.min(10, pctx.remaining.size() / 4 + 3));
LayoutPrimitive.TownSquare square = new LayoutPrimitive.TownSquare(centre, capacity, mainCenterline);
square.emitSlots(pctx);  // emits PRIME_CIVIC + SECONDARY_CIVIC slots
square.place(pctx);      // sets townSquarePos, townSquareRadius, civicRingRadius; draws ring road
// Both must be called. emitSlots() first.

// After place(), civicRingRadius is set. Use the ring road outer edge to start the next road:
// (civicRing is the building placement radius; ring road sits at civicRing-6, halfwidth=3)
int civicRing = pctx.layout.getCivicRingRadius();
int ringRoadOuter = Math.max(9, civicRing - 3);
```

### BuildingCircle
```java
// SCATTER = randomised spread around focal point (most common)
// TIGHT   = buildings tangent to each other (dense cluster)
// LOOSE   = buildings with gaps (outer clusters)
new LayoutPrimitive.BuildingCircle(focal, Mode.SCATTER, buildings, feedingRoad)
    .emitSlotsWithTags(pctx, tagSet, baseQuality);  // preferred over .emitSlots()
```

### LinearRow
```java
// Buildings lined up along a heading direction
new LayoutPrimitive.LinearRow(start, headingRad, facingSide, spacing, buildings, facingRoad)
    .emitSlots(pctx);
// facingSide: +1=left of heading, -1=right
```

### RingBand
```java
// Outer ring distribution (agri/defensive/stragglers)
// Use RecipeHelpers helpers below instead of constructing directly
new LayoutPrimitive.RingBand(centre, innerRadius, outerRadius, zone, buildings, snapRoads)
    .place(pctx);
// RingBand.place() is fine — it handles its own terrain resolution internally
```

---

## RecipeHelpers (static utility methods)

```java
// Distribute buildings round-robin into N buckets (production first)
List<List<StarterBuilding>> buckets = RecipeHelpers.distributeToBuckets(pctx, bucketCount);

// Plant stub spurs along a road and scatter buildings at each tip
List<List<BlockPos>> spurs = RecipeHelpers.stubSpursAlongRoad(
    pctx, parentCenterline, bucket,
    RecipeHelpers.SidePolicy.ALTERNATING,  // or LEFT_ONLY / RIGHT_ONLY
    spurLength, perpOffset,
    RoadShape.RoadTier.FOOTPATH);

// Scatter a bucket of buildings around a focal point
RecipeHelpers.scatterBucketAt(pctx, focal, bucket, feedingRoad);

// Outer rings — claim remaining buildings of a zone and place in RingBand
RecipeHelpers.placeAgriculturalRing(pctx, centre, innerOffset, outerOffset, allRoads);
RecipeHelpers.placeDefensiveRing(pctx, centre, innerOffset, outerOffset, allRoads);
RecipeHelpers.placeStragglersRingBand(pctx, centre, allRoads);

// Ensure TOWN_HALL gets placed even if no PRIME_CIVIC slot was taken
RecipeHelpers.rescueTownHallOnAnyRoad(pctx, allRoads);

// Place farm cluster at a road end
RecipeHelpers.placeFarmCluster(pctx, roadEnd, outwardAngle, farms, snapRoads);
```

---

## SlotTag Reference

Use `EnumSet.of(SlotTag.X, SlotTag.Y)` to build tag sets.

| Tag | When to emit |
|-----|-------------|
| `PRIME_CIVIC` | TownSquare tangent positions, best quality |
| `SECONDARY_CIVIC` | TownSquare outer ring, lower quality |
| `CIVIC_ADJACENT` | Near civic area but not prime |
| `PRODUCTION_CLUSTER` | Spur clusters, workshop districts |
| `PRODUCTION_SPUR_END` | Spur tip positions |
| `PRODUCTION_INFILL` | Road-side production infill |
| `RESIDENTIAL_INFILL` | Standard road-side houses |
| `RESIDENTIAL_OUTER` | Outer edge residential |
| `FIELD_EDGE` | Adjacent to farm plots / open land |
| `PASTURE` | Open ground suitable for animals |
| `WALL_ADJACENT` | Near a defensive perimeter |
| `GATE_ADJACENT` | Near a village entrance |
| `HIGH_GROUND` | Elevated terrain (watchtower, mine) |
| `SHORE` | Adjacent to water body |
| `PIER_ADJACENT` | Dock/pier slot near water |
| `RIVER_BANK` | Along a river channel |
| `TERRACE_EDGE` | On a slope terrace step |
| `FOREST_EDGE` | At treeline boundary |
| `HILLTOP_PEAK` | Highest point of a hill |
| `ROAD_ADJACENT` | Generic fallback for any road-side slot |
| `BACKFILL` | Last-resort filler |

---

## TerrainProfile Fields (for terrain guards and geometry decisions)

```java
TerrainProfile terrain = pctx.layout.getTerrain();

terrain.flatRatio()           // 0-1, proportion of flat ground
terrain.waterRatio()          // 0-1, proportion of water
terrain.steepRatio()          // 0-1, proportion of steep/ridge terrain
terrain.treeRatio()           // 0-1, proportion with tree cover
terrain.hasSlope()            // true if slopeDir != null && slopeMagnitude >= 6
terrain.slopeDir()            // FlatDirection of downhill, or null
terrain.slopeMagnitude()      // Y-drop in blocks across ~24 block radius
terrain.waterBody()           // WaterBodyInfo or null
terrain.waterFacingDir()      // FlatDirection toward nearest water, or null
terrain.ridges()              // List<RidgeInfo> — terrain ridges to route around
terrain.bestFlatDir()         // cardinal direction with most flat terrain
terrain.heightVariance()      // maxY - minY
terrain.flatCandidates()      // List<BlockPos> — good flat building positions
terrain.clearingCandidates()  // List<BlockPos> — flat + no trees
```

### WaterBodyInfo fields
```java
TerrainAnalyzer.WaterBodyInfo wb = terrain.waterBody();
wb.centre()        // BlockPos centre of water mass
wb.nearestShore()  // BlockPos closest shore to village origin
wb.radius()        // approximate radius in blocks
wb.blockCount()    // number of water blocks detected
```

---

## Road Non-Overlap Rules

**Every road piece must begin where the previous connecting piece ends.**
Overlap causes visual artifacts and footprint conflicts. Follow these patterns:

### After TownSquare (civic ring road)
`civicRingRadius` is the civic **building** placement radius, not the ring road edge.
The ring road sits at `civicRing - 6` with a half-width of 3, so its outer edge is `civicRing - 3`.
```java
int civicRing = pctx.layout.getCivicRingRadius();
int ringRoadOuter = Math.max(9, civicRing - 3); // ring road outer edge
BlockPos roadStart = pctx.solidSurface(new BlockPos(
    centre.getX() + (int) Math.round(Math.cos(dirRad) * ringRoadOuter),
    centre.getY(),
    centre.getZ() + (int) Math.round(Math.sin(dirRad) * ringRoadOuter)));
```

### After a Ring (explicit Ring primitive)
A road following a ring must **start at the ring's perimeter** in the outward direction:
```java
BlockPos roadStart = pctx.solidSurface(new BlockPos(
    ringCentre.getX() + (int) Math.round(Math.cos(dirRad) * ringRadius),
    ringCentre.getY(),
    ringCentre.getZ() + (int) Math.round(Math.sin(dirRad) * ringRadius)));
```

A road leading **into** a ring must end at the ring's perimeter, with the ring centre offset
a further `ringRadius` beyond the road endpoint:
```java
// Road endpoint lands on the ring's perimeter
BlockPos roadEnd = ...; // last point of the incoming road
// Ring centre is ringRadius further in the same direction
BlockPos ringCentre = pctx.solidSurface(new BlockPos(
    roadEnd.getX() + (int) Math.round(Math.cos(dirRad) * ringRadius),
    roadEnd.getY(),
    roadEnd.getZ() + (int) Math.round(Math.sin(dirRad) * ringRadius)));
// Ring road: Ring(ringCentre, ringRadius, ...)  — perimeter meets roadEnd exactly
```

### After a StraightRoad or Spur
```java
List<BlockPos> centerline = pctx.layout.addRoad(road, pctx.level, pctx.worldSeed);
BlockPos roadEnd = centerline.get(centerline.size() - 1);
// next road starts from roadEnd
```

### Full dumbbell pattern (TownSquare → trunk → far ring)
```java
int civicRing     = pctx.layout.getCivicRingRadius();
int ringRoadOuter = Math.max(9, civicRing - 3);
int ringBRadius   = Math.max(8, pctx.density.getRing1Radius() / 2 + 4);
int armLength     = pctx.density.getRing1Radius() + pctx.density.getRing2Radius();

// Trunk: starts at ring A outer edge, ends at ring B perimeter
BlockPos trunkStart = pctx.solidSurface(offset(centre, dirRad, ringRoadOuter));
BlockPos trunkEnd   = pctx.solidSurface(offset(trunkStart, dirRad, armLength));
BlockPos ringBCentre = pctx.solidSurface(offset(trunkEnd, dirRad, ringBRadius));

// Helper: offset(pos, angleRad, dist) = pos + (cos*dist, 0, sin*dist)
```

---

## Terrain Adaptation Patterns

### Fallback pattern (always required)
```java
if (!terrain.hasSlope()) {  // or whatever your guard condition is
    PlacementFailureRecorder.record(
        PlacementFailureRecorder.Reason.TERRAIN_UNSUITABLE,
        "no <feature> detected",
        centre, VillageTypeData.ShapeType.<SHAPE_TYPE>.name());
    new <FallbackRecipe>().compose(pctx);
    return;
}
```

### Orient main road toward best flat ground
```java
double mainDirRad = RecipeHelpers.directionRadOf(terrain.bestFlatDir());
```

### Orient toward water
```java
double waterAngle = terrain.waterFacingDir() != null
    ? RecipeHelpers.directionRadOf(terrain.waterFacingDir())
    : RecipeHelpers.directionRadOf(terrain.bestFlatDir());
```

### Route road perpendicular to slope (terrace-style)
```java
// If slope is EAST/WEST, terraces run NORTH/SOUTH and vice versa
double slopeRad = RecipeHelpers.directionRadOf(terrain.slopeDir());
double terraceHeading = slopeRad + Math.PI / 2;
```

### Avoid ridges in road placement
```java
// Default: pctx rejects ridge-blocked building positions automatically.
// To ALLOW ridge placement (e.g. HILLTOP, TERRACED):
pctx.allowRidgePlacement = true;
```

### Emit water-feature slots
```java
if (terrain.waterBody() != null) {
    BlockPos shore = pctx.solidSurface(terrain.waterBody().nearestShore());
    pctx.offerSlot(new PlacementSlot(
        shore, mainCenterline,
        EnumSet.of(SlotTag.SHORE, SlotTag.ROAD_ADJACENT),
        16, 80));
}
```

### Emit high-ground slots (hilltop / watchtower)
```java
// Sample elevation above baseY to find highest flat candidate
terrain.flatCandidates().stream()
    .filter(p -> p.getY() > terrain.baseY() + 4)
    .forEach(p -> pctx.offerSlot(new PlacementSlot(
        pctx.solidSurface(p), mainCenterline,
        EnumSet.of(SlotTag.HIGH_GROUND, SlotTag.ROAD_ADJACENT),
        16, 75)));
```

---

## Common Slot Tag Sets (as named constants in recipe files)

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
