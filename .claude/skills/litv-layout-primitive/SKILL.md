---
name: litv-layout-primitive
description: >
  Writes complete, compilable Java LayoutPrimitive implementations for the
  Life in the Village Minecraft mod. Use this skill whenever the user needs
  a new building-placement shape that existing primitives (TownSquare,
  BuildingCircle, LinearRow, RingBand) cannot express — for example, a
  spiral placement, a grid, buildings along a contour, or a wedge cluster.
  Always use this skill before free-styling primitive code. If you are unsure
  whether a new primitive is needed or a recipe-level workaround would suffice,
  ask the user before proceeding. Output is a single ready-to-drop-in .java
  file plus the one-line wiring change in LayoutPrimitive.java.
---

# Life in the Village — Layout Primitive Skill

## Step 0 — Gather Required Inputs

Do NOT write any code until all items below are confirmed.

| # | Input | Notes |
|---|-------|-------|
| 1 | **Primitive name** | Will become the record name (e.g. `WedgeCluster`) |
| 2 | **Geometry concept** | What spatial shape does this primitive produce? |
| 3 | **Record fields** | What parameters does the caller supply? (positions, radii, angles, building lists, feeding roads…) |
| 4 | **`place()` behaviour** | How does it iterate buildings and call `tryCommitWithRetries`? |
| 5 | **`emitSlots()` behaviour** | What positions does it offer, with which tags and quality scores? Leave empty if placement-only. |
| 6 | **Rotation strategy** | How should buildings face? (toward a focal point, perpendicular to a road, fixed angle, `chooseFacing`) |

---

## Step 1 — Write the Primitive File

**File location:**
```
src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/LayoutPrimitive.java
```
The primitive is a new `record` nested inside `LayoutPrimitive`. Add it at the end of the file, before the closing brace.

**Package / imports** are already present in the file — no new file to create.
The record uses types already imported at the top of `LayoutPrimitive.java`:
`BlockPos`, `BuildingType`, `BuildingZone`, `LayoutSlot`, `StructureSizeCache`,
`PlacementSlot`, `SlotTag`, `VillageTypeData`, `PlanContext`.

**Record skeleton:**
```java
// =========================================================================
// <PrimitiveName>
// =========================================================================

/**
 * <One-line description of geometry and use case.>
 */
record <PrimitiveName>(
        /* fields */
) implements LayoutPrimitive {

    @Override
    public void place(PlanContext pctx) {
        if (buildings.isEmpty()) return;
        // iterate buildings, call tryCommitWithRetries, log drops
        // see "place() Patterns" in references/api.md
    }

    @Override
    public void emitSlots(PlanContext pctx) {
        // call pctx.offerSlot(...) for each candidate position
        // may be empty body if this primitive is placement-only
        // see "emitSlots() Patterns" in references/api.md
    }
}
```

See `references/api.md` for the full API (placement methods, rotation, slot construction, footprint lookup).

---

## Step 2 — Wire into the sealed interface

In `LayoutPrimitive.java`, add the new type to the `permits` clause:

```java
public sealed interface LayoutPrimitive
        permits LayoutPrimitive.TownSquare,
        LayoutPrimitive.BuildingCircle,
        LayoutPrimitive.LinearRow,
        LayoutPrimitive.RingBand,
        LayoutPrimitive.<PrimitiveName> {   // ← add this line
```

---

## Step 3 — Self-check before presenting

- [ ] Both `place()` and `emitSlots()` are present (one may have an empty body)
- [ ] `place()` calls `tryCommitWithRetries` (not `tryCommitBuilding`) for each building
- [ ] Every failed placement logs: `System.out.println("<PrimitiveName>: dropped " + bt)`
- [ ] Rotation is set on the committed slot via `slot.setRotation(...)`
- [ ] `emitSlots()` uses only valid `SlotTag` values (see `references/api.md`)
- [ ] `PlanContext.parseType(sb)` null-check is present before using `bt`
- [ ] `permits` clause updated in `LayoutPrimitive.java`

Present the complete record body inline in the chat, followed by the updated `permits` clause.
