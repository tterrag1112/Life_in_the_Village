# Life in the Village — Layout Primitive API Reference (post-Phase-10)

Reference for `litv-layout-primitive`. Covers the slot-generation
pattern (the canonical path), the rare `place()` pattern, rotation
helpers, footprint lookup, and the slot tag vocabulary.

For sector wiring (the recipe's job), see the
`litv-layout-recipe` skill's `references/api.md` § "Sector emission
patterns."

---

## 1. Slot generation (canonical path)

New primitives expose `generateSlots(PlanContext)` returning a
`List<PlacementSlot>`. The calling recipe wraps the list in a sector.
Slots use the 9-arg constructor:

```java
new PlacementSlot(
    BlockPos pos,
    List<BlockPos> feedingRoad,    // road centerline; null permitted
    int feedingEdgeId,             // RoadGraph edge id, -1 if none
    Set<SlotTag> tags,
    int footprintBudgetW,          // along-road budget
    int footprintBudgetL,          // across-road budget
    Rotation forcedRotation,       // null = matcher-derived
    int qualityScore,
    int terrainPenalty             // 0 = flat
)
```

### Quality score conventions

- 90–100: prime civic / landmark.
- 60–80: production cluster / key feature slot.
- 40–60: standard road-side infill.
- 20–40: outer / fallback positions.
- 5–20: last resort.

Decay quality across a sequence so the matcher fills better positions
first:

```java
int q = 60;
for (int i = 0; i < count; i++) {
    out.add(new PlacementSlot(pos, feedingRoad, edgeId, tags,
            footprintBudget, footprintBudget,
            /* forcedRotation */ null, q, /* terrainPenalty */ 0));
    q = Math.max(10, q - 2);
}
```

### Footprint budgets

`footprintBudgetW` is the along-road dimension; `footprintBudgetL`
is across-road. For a primitive that doesn't have a road heading,
set both equal. Pick a value that fits the largest building class
the slot is intended to host — overly generous budgets crowd
neighbours; overly tight budgets reject candidates the slot could
have hosted.

### Surface snapping

Always snap slot positions to the solid surface:

```java
BlockPos snapped = pctx.solidSurface(pos);
```

Skip surface-snapping only when the primitive intentionally produces
unsnapped intermediate positions (rare; document the reason).

---

## 2. Slot generation patterns

### Radial slots (around a focal point)

```java
public List<PlacementSlot> generateSlots(PlanContext pctx) {
    List<PlacementSlot> out = new ArrayList<>();
    int count = Math.max(slotCount, 4);
    double angleStep = 2 * Math.PI / count;
    int q = 60;

    for (int i = 0; i < count; i++) {
        double angle = startAngle + i * angleStep;
        BlockPos target = pctx.solidSurface(new BlockPos(
            focal.getX() + (int) Math.round(Math.cos(angle) * radius),
            focal.getY(),
            focal.getZ() + (int) Math.round(Math.sin(angle) * radius)));

        Rotation rot = PlanContext.chooseFacing(target, focal);

        out.add(new PlacementSlot(
            target, feedingRoad, feedingEdgeId, tags,
            footprintBudget, footprintBudget,
            rot, q, /* terrainPenalty */ 0));
        q = Math.max(10, q - 2);
    }
    return out;
}
```

### Linear slots (along a heading)

```java
public List<PlacementSlot> generateSlots(PlanContext pctx) {
    List<PlacementSlot> out = new ArrayList<>();
    int count = Math.max(slotCount, 3);
    double headX = Math.cos(headingRad), headZ = Math.sin(headingRad);
    double sideX = -headZ * facingSide,  sideZ =  headX * facingSide;
    int step = 14;

    // Synthetic 2-point facing road derived from heading; the matcher
    // uses this for rotation when forcedRotation is null.
    BlockPos fa = new BlockPos((int)(start.getX() + sideX * 6),
                               start.getY(),
                               (int)(start.getZ() + sideZ * 6));
    BlockPos fb = new BlockPos((int)(start.getX() - sideX * 6),
                               start.getY(),
                               (int)(start.getZ() - sideZ * 6));
    List<BlockPos> facingRoad = List.of(fa, fb);

    for (int i = 0; i < count; i++) {
        BlockPos target = pctx.solidSurface(new BlockPos(
            start.getX() + (int) Math.round(headX * (i + 1) * step),
            start.getY(),
            start.getZ() + (int) Math.round(headZ * (i + 1) * step)));

        out.add(new PlacementSlot(
            target, facingRoad, feedingEdgeId, tags,
            /* W */ 16, /* L */ 16,
            /* forcedRotation */ null,    // matcher derives from facingRoad
            50 - i * 2, 0));
    }
    return out;
}
```

### Centerline-walk slots (both sides of a road)

The pattern most commonly used by recipe-private helpers; see the
`litv-layout-recipe` skill's api.md § 4 for the full version. A
primitive that walks a stored centerline mirrors that pattern but
exposes the result via `generateSlots`.

---

## 3. Rotation helpers

```java
// Face a building toward a focal point (ring centre, plaza, road end):
Rotation rot = PlanContext.chooseFacing(slotPos, focalPoint);

// Face along a road heading derived from two adjacent centerline points:
//   prev → next direction; building presents its front face to the road.
//   Use the same convention recipes use:
private static Rotation rotationAlongRoad(BlockPos prev, BlockPos next) {
    int dx = next.getX() - prev.getX();
    int dz = next.getZ() - prev.getZ();
    if (Math.abs(dx) >= Math.abs(dz)) {
        return dx > 0 ? Rotation.COUNTERCLOCKWISE_90 : Rotation.CLOCKWISE_90;
    } else {
        return dz > 0 ? Rotation.NONE : Rotation.CLOCKWISE_180;
    }
}
```

Pass the chosen `Rotation` as `forcedRotation` on the slot. Pass
`null` if the matcher should derive rotation from the slot's
`feedingRoad` instead — that's the common case for road-side slots
where the matcher will face the building toward the nearest centerline
point.

---

## 4. Footprint lookup

When sizing slot budgets to a specific building's footprint:

```java
StructureSizeCache.FootprintInfo info =
    pctx.sizes.get(sb.structure(), Rotation.NONE);
int width  = info != null ? info.width()  : 12;  // 12 = safe default
int length = info != null ? info.length() : 12;
```

This is rare in slot-generation primitives because slots are typed by
class (e.g. "houses fit in 14×14") rather than per-building. Footprint
lookup is more common inside `place()`-implementing primitives that
commit specific buildings.

### Parsing the building type

When iterating buildings (in `place()` or rare slot generators that
read `pctx.remaining`):

```java
BuildingType bt = PlanContext.parseType(sb);
if (bt == null) continue;  // unknown type; skip
```

---

## 5. The rare `place()` pattern

Implement `place(PlanContext)` only when the primitive owns
world-state placement that's separate from the matcher's slot
consumption. The canonical example is `TownSquare`: it draws plaza
pavement, registers gathering points, and sets `civicRingRadius` /
`townSquarePos` on the layout. None of that is slot generation.

### When to implement `place()`

- The primitive sets transient layout state read by other primitives
  or by the realiser (`townSquarePos`, `civicRingRadius`).
- The primitive draws non-building world geometry (plaza pavement).
- The primitive commits one or more anchor buildings whose position
  must be exact rather than matcher-decided.

### When NOT to implement `place()`

- The primitive's only job is to produce slots for the matcher to
  fill. Use `generateSlots` and leave `place` empty.
- The primitive needs to "make sure this building lands here." That's
  what `forcedRotation`, high `qualityScore`, and a tight
  `footprintBudget` are for — let the matcher commit it.

### Direct-commit skeleton (when `place()` is justified)

```java
@Override
public void place(PlanContext pctx) {
    if (buildings.isEmpty()) return;

    // 1. Lay any non-building world state first (e.g. pavement,
    //    gathering points, layout state mutations).

    // 2. Iterate the buildings the primitive directly commits.
    for (VillageTypeData.StarterBuilding sb : buildings) {
        BuildingType bt = PlanContext.parseType(sb);
        if (bt == null) continue;

        BlockPos target = pctx.solidSurface(/* primitive-specific position */);

        LayoutSlot slot = pctx.tryCommitWithRetries(
            target, sb, bt, feedingRoad, /* maxShift */ 8);
        if (slot != null) {
            slot.setRotation(PlanContext.chooseFacing(slot.getPos(), focal));
        } else {
            System.out.println("<PrimitiveName>: dropped " + bt);
        }
    }
}
```

Notes on this pattern:

- `tryCommitWithRetries` is the right call for direct placement —
  it shifts along/perpendicular to the road on terrain failure.
  `tryCommitBuilding` is the no-retry variant; use only when you want
  strict positional control.
- Every dropped building must log: `System.out.println("<PrimitiveName>: dropped " + bt);`
- Direct placement bypasses the matcher's variant selection. If you
  need variant scoring on a directly-committed slot, pass the slot
  tags to the variant-aware overload of `tryCommitBuilding`.

---

## 6. Legacy `emitSlots()` (transitional, do not implement for new primitives)

`emitSlots(PlanContext)` writes into the flat slot pool via
`pctx.offerSlot(...)`. Unconverted recipes still consume the flat
pool. New primitives should leave `emitSlots` empty and expose
`generateSlots` instead.

If a new primitive must support an unconverted recipe during the
Phase 8-15 migration window, document the requirement explicitly:

```java
@Override
public void emitSlots(PlanContext pctx) {
    // TODO(Phase 11): <RecipeName> still consumes flat slots. Remove
    // when that recipe converts to BaseRecipe + sectors.
    for (PlacementSlot s : generateSlots(pctx)) {
        pctx.offerSlot(s);
    }
}
```

The shim above forwards to `generateSlots` so there's only one source
of truth. Once the consuming recipe converts, delete the body.

---

## 7. Existing primitives (the survivors)

The four primitives that survive into the post-Phase-10 architecture:

- **`TownSquare`** — owns plaza pavement, gathering points,
  `townSquarePos`, and `civicRingRadius`. Only primitive that
  legitimately implements both `place()` and `emitSlots()`. (Note:
  Phase 17 / 18 may consolidate plaza ownership into `installPlaza`
  and `PlazaGenerator`; until then, recipes still use TownSquare via
  `installPlaza`'s internals.)
- **`BuildingCircle`** — radial cluster around a focal point with
  TIGHT/LOOSE/SCATTER modes. Used by spur tip clusters in RADIAL.
- **`LinearRow`** — buildings along a heading direction. Used by
  road-side rows.
- **`RingBand`** — concentric distribution between an inner and
  outer radius. Used by agricultural / defensive fringes.

Before adding a new primitive, confirm none of these fit. Most
"unique" geometries decompose into one of these plus a recipe-private
slot generator.

---

## 8. SlotTag quick reference

| Tag | Use |
|-----|-----|
| `PRIME_CIVIC` | Best civic position |
| `SECONDARY_CIVIC` | Secondary civic |
| `CIVIC_ADJACENT` | Near civic area |
| `PLAZA_ADJACENT` | Adjacent to a plaza polygon edge |
| `PRODUCTION_CLUSTER` | Workshop cluster |
| `PRODUCTION_SPUR_END` | Spur tip |
| `PRODUCTION_INFILL` | Road-side production |
| `RESIDENTIAL_INFILL` | Standard housing |
| `RESIDENTIAL_OUTER` | Outer-edge housing |
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
