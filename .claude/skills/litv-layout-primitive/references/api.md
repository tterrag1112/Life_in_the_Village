# Life in the Village — Layout Primitive API Reference

## Core Placement Methods

```java
// Preferred — retries with small shifts along/perpendicular to road on terrain failure.
// Returns the committed LayoutSlot, or null if all retries exhausted.
LayoutSlot slot = pctx.tryCommitWithRetries(target, sb, bt, feedingRoad, maxShift);
// maxShift: how far to shift on retries, in blocks. Typical: 6–12.

// No-retry variant. Use only when you want strict positional control.
LayoutSlot slot = pctx.tryCommitBuilding(target, sb, bt, feedingRoad);
```

## Getting BuildingType from StarterBuilding

Always null-check — unknown types return null:
```java
BuildingType bt = PlanContext.parseType(sb);
if (bt == null) continue;
```

## Footprint Lookup

```java
StructureSizeCache.FootprintInfo info =
    pctx.sizes.get(sb.structure(), net.minecraft.world.level.block.Rotation.NONE);
int width  = info != null ? info.width()  : 12;  // 12 = safe default
int length = info != null ? info.length() : 12;
int frontFace = info != null ? info.width() : 12; // width = front face
```

## Rotation

```java
// Face a building toward a focal point (e.g. ring centre, plaza, road end):
Rotation rot = PlanContext.chooseFacing(slot.getPos(), focalPoint);
slot.setRotation(rot);

// Face perpendicular to a road (buildings line up along a heading):
// Construct a 2-point synthetic road along the desired facing direction:
BlockPos facingA = target.offset((int)(perpX * 6), 0, (int)(perpZ * 6));
BlockPos facingB = target.offset((int)(-perpX * 6), 0, (int)(-perpZ * 6));
List<BlockPos> facing = List.of(facingA, facingB);
// Pass `facing` as feedingRoad to tryCommitWithRetries — rotation is derived automatically.
```

## Surface Snapping

```java
BlockPos snapped = pctx.solidSurface(pos); // snaps Y to MOTION_BLOCKING_NO_LEAVES
```

## Nearest Point on a Road

```java
BlockPos nearest = PlanContext.nearestOn(centerline, target);
```

## Offering Slots (emitSlots)

```java
pctx.offerSlot(new PlacementSlot(
    pos,            // ideal target position
    feedingRoad,    // road centerline for rotation; may be null
    tags,           // EnumSet<SlotTag>
    footprintBudget,// max footprint this slot can fit; use maxFrontFace + 4 or a fixed value
    qualityScore    // 0–100; higher = matcher prefers this slot
));
```

Quality score conventions:
- 90–100: prime civic / landmark
- 60–80: production cluster / key feature slot
- 40–60: standard road-side infill
- 20–40: outer / fallback positions
- 5–20: last resort

Decay quality across a sequence of slots so the matcher fills better positions first:
```java
int q = 60;
for (int i = 0; i < count; i++) {
    pctx.offerSlot(new PlacementSlot(pos, road, tags, budget, q));
    q = Math.max(10, q - 2);
}
```

## Dropped Building Logging

Every building that fails to place must be logged:
```java
LayoutSlot slot = pctx.tryCommitWithRetries(target, sb, bt, feedingRoad, 10);
if (slot == null) {
    System.out.println("<PrimitiveName>: dropped " + bt);
}
```

## place() Patterns

### Radial (buildings around a focal point)
```java
double angleStep = 2 * Math.PI / buildings.size();
for (int i = 0; i < buildings.size(); i++) {
    VillageTypeData.StarterBuilding sb = buildings.get(i);
    BuildingType bt = PlanContext.parseType(sb);
    if (bt == null) continue;

    double angle = startAngle + i * angleStep;
    BlockPos target = new BlockPos(
        focal.getX() + (int) Math.round(Math.cos(angle) * radius),
        focal.getY(),
        focal.getZ() + (int) Math.round(Math.sin(angle) * radius));

    LayoutSlot slot = pctx.tryCommitWithRetries(
        pctx.solidSurface(target), sb, bt, feedingRoad, 8);
    if (slot != null) {
        slot.setRotation(PlanContext.chooseFacing(slot.getPos(), focal));
    } else {
        System.out.println("<PrimitiveName>: dropped " + bt);
    }
}
```

### Linear (buildings along a heading)
```java
double headX = Math.cos(headingRad), headZ = Math.sin(headingRad);
double sideX = -headZ * facingSide,  sideZ =  headX * facingSide;
double cursor = 0;
for (VillageTypeData.StarterBuilding sb : buildings) {
    BuildingType bt = PlanContext.parseType(sb);
    if (bt == null) continue;

    StructureSizeCache.FootprintInfo info =
        pctx.sizes.get(sb.structure(), net.minecraft.world.level.block.Rotation.NONE);
    int w = info != null ? info.width() : 12;

    cursor += w / 2.0;
    BlockPos target = pctx.solidSurface(new BlockPos(
        start.getX() + (int) Math.round(headX * cursor + sideX * 8),
        start.getY(),
        start.getZ() + (int) Math.round(headZ * cursor + sideZ * 8)));

    // Synthetic facing road so buildings face outward from the row
    BlockPos fa = target.offset((int)(sideX * 6), 0, (int)(sideZ * 6));
    BlockPos fb = target.offset((int)(-sideX * 6), 0, (int)(-sideZ * 6));
    LayoutSlot slot = pctx.tryCommitWithRetries(
        target, sb, bt, List.of(fa, fb), 8);
    if (slot == null) System.out.println("<PrimitiveName>: dropped " + bt);
    cursor += w / 2.0 + spacing;
}
```

## emitSlots() Patterns

### Radial slot emission
```java
int count = Math.max(buildings.size(), 4);
double angleStep = 2 * Math.PI / count;
int q = 60;
for (int i = 0; i < count; i++) {
    double angle = startAngle + i * angleStep;
    BlockPos target = new BlockPos(
        focal.getX() + (int) Math.round(Math.cos(angle) * radius),
        focal.getY(),
        focal.getZ() + (int) Math.round(Math.sin(angle) * radius));
    pctx.offerSlot(new PlacementSlot(target, feedingRoad, tags, footprintBudget, q));
    q = Math.max(10, q - 2);
}
```

### Linear slot emission
```java
int count = Math.max(buildings.size(), 3);
int step = 14; // approximate building spacing
for (int i = 0; i < count; i++) {
    BlockPos target = new BlockPos(
        start.getX() + (int) Math.round(headX * (i + 1) * step),
        start.getY(),
        start.getZ() + (int) Math.round(headZ * (i + 1) * step));
    pctx.offerSlot(new PlacementSlot(target, feedingRoad, tags, 16, 50 - i * 2));
}
```

## SlotTag Quick Reference

| Tag | Use |
|-----|-----|
| `PRIME_CIVIC` | Best civic position |
| `SECONDARY_CIVIC` | Secondary civic |
| `CIVIC_ADJACENT` | Near civic area |
| `PRODUCTION_CLUSTER` | Workshop cluster |
| `PRODUCTION_SPUR_END` | Spur tip |
| `PRODUCTION_INFILL` | Road-side production |
| `RESIDENTIAL_INFILL` | Standard housing |
| `RESIDENTIAL_OUTER` | Outer edge housing |
| `FIELD_EDGE` | Farm-adjacent |
| `PASTURE` | Open grazing ground |
| `WALL_ADJACENT` | Near perimeter |
| `GATE_ADJACENT` | Near entrance |
| `HIGH_GROUND` | Elevated position |
| `SHORE` | Water-adjacent |
| `PIER_ADJACENT` | Dock-adjacent |
| `RIVER_BANK` | Along river |
| `TERRACE_EDGE` | Slope terrace |
| `FOREST_EDGE` | Treeline |
| `HILLTOP_PEAK` | Highest point |
| `ROAD_ADJACENT` | Generic road-side |
| `BACKFILL` | Last resort |
