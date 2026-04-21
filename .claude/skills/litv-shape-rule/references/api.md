# Shape Rule — API Reference

## RuleContext Write API

```java
// Anchor — named position used by recipes (e.g. "town_square", "gate")
ctx.setAnchor(String name, BlockPos pos);

// Axis — preferred orientation angle for the village road
ctx.setAxis(double radians, double toleranceRadians);

// Zone — assign a building type to a ring + optional sector
ctx.assignZone(BuildingType type, RuleContext.ZoneSpec spec);

// Adjacency — add a hard placement constraint for a building type
ctx.addAdjacencyReq(BuildingType type, RuleContext.AdjacencyReq req);

// Density
ctx.setDensityPer100m2(float value);

// Walled
ctx.setForceWalled(boolean walled);
```

## RuleContext Read API

```java
ctx.terrain()            // TerrainProfile — see below
ctx.origin()             // BlockPos village origin
ctx.seed()               // long world seed
ctx.getAnchor("name")    // BlockPos or null
ctx.axisRad()            // Double or null
ctx.getZone(BuildingType)// ZoneSpec or null
ctx.getAdjacencyReqs(BuildingType) // List<AdjacencyReq>
ctx.densityPer100m2()    // Float or null
ctx.forceWalled()        // boolean
```

## TerrainProfile (read-only inside rules)
```java
TerrainProfile t = ctx.terrain();
t.flatRatio()            // 0-1
t.waterRatio()           // 0-1
t.steepRatio()           // 0-1
t.hasSlope()             // boolean
t.slopeDir()             // FlatDirection or null
t.waterBody()            // WaterBodyInfo or null
t.waterFacingDir()       // FlatDirection or null
t.bestFlatDir()          // FlatDirection
t.flatCandidates()       // List<BlockPos>
t.clearingCandidates()   // List<BlockPos>
t.ridges()               // List<RidgeInfo>
t.baseY(), t.maxY()      // int
t.bestFlatNear(dx, dz)   // BlockPos
```

## Supporting Types

### RuleContext.ZoneSpec
```java
new RuleContext.ZoneSpec(RuleContext.Ring ring, RuleContext.AngularSector sector)
// sector may be null
```
Ring values: `INNER`, `MIDDLE`, `OUTER`

### RuleContext.AngularSector
```java
new RuleContext.AngularSector(int startDegrees, int endDegrees)
// e.g. new RuleContext.AngularSector(0, 90) = NE quadrant
```

### RuleContext.AdjacencyReq
```java
new RuleContext.AdjacencyReq(String feature, int maxDist, boolean required)
// feature: "water", "river", "coast", "forest"
// required=true → building not placed if constraint unsatisfied
// required=false → soft preference (tries but doesn't fail)
```

## JSON Parsing Patterns

```java
// String with default
String value = json.has("field") ? json.get("field").getAsString() : "default";

// Int with default
int n = json.has("field") ? json.get("field").getAsInt() : 16;

// Float with default
float f = json.has("field") ? json.get("field").getAsFloat() : 1.0f;

// Boolean with default
boolean b = json.has("field") && json.get("field").getAsBoolean();

// Enum with fallback
MyEnum e = MyEnum.DEFAULT;
if (json.has("field")) {
    try { e = MyEnum.valueOf(json.get("field").getAsString().toUpperCase(Locale.ROOT)); }
    catch (IllegalArgumentException ignored) {}
}

// BuildingType array
List<BuildingType> buildings = new ArrayList<>();
if (json.has("buildings")) {
    for (var el : json.getAsJsonArray("buildings")) {
        try { buildings.add(BuildingType.valueOf(el.getAsString().toUpperCase(Locale.ROOT))); }
        catch (IllegalArgumentException ignored) {}
    }
}
```

## All Six Built-in Rules — Summary

| Rule | JSON type | What it sets | Key JSON fields |
|------|-----------|-------------|-----------------|
| `AnchorRule` | `"anchor"` | `ctx.setAnchor()` | `target` (string), `strategy` (FLAT_GROUND / HIGHEST_POINT / RIVERBANK / COASTLINE / ORIGIN) |
| `AxisRule` | `"axis"` | `ctx.setAxis()` | `strategy` (FLATTEST / ALONG_RIVER / ALONG_COAST / FIXED / NONE), `angle` (degrees), `max_deviation` (degrees) |
| `ZoneRule` | `"zone"` | `ctx.assignZone()` | `buildings` (array), `ring` (INNER / MIDDLE / OUTER), `angular_sector` ([start, end] degrees) |
| `AdjacencyRule` | `"adjacency"` | `ctx.addAdjacencyReq()` | `buildings` (array), `requires` (feature string), `max_distance` (int), `optional` (bool) |
| `DensityRule` | `"density"` | `ctx.setDensityPer100m2()` | `buildings_per_100m2` (float, default 0.5) |
| `WalledRule` | `"walled"` | `ctx.setForceWalled()` | `value` (bool, default true) |

## Rule Ordering

Rules run in JSON array order. The expected dependency chain:
```
anchor rules → axis rules → zone rules → adjacency rules → density/walled rules
```
A rule that reads `ctx.getAnchor("town_square")` should only be placed after
the anchor rule that sets it. Rules don't fail if a dependency is missing —
they get null/defaults — so ordering is a correctness concern, not a crash concern.
