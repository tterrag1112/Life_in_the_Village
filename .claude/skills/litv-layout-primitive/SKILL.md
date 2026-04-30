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

Post-Phase-10 architecture: recipes emit `Sector` records inside
`composeSectors`, and the matcher consumes slots from those sectors.
Most "I need a new primitive" intents from the pre-rework era are
better expressed as a recipe-private slot-generator helper. Layout
primitives are now reserved for genuinely reusable, multi-recipe
geometry. See `docs/placement-rework/01-PLACEMENT-ABSTRACTIONS.md`
for the full contract.

## Step −1 — Do you actually need a new layout primitive?

Before writing a new layout primitive, confirm that none of the
following alternatives fit:

1. **A private helper method inside one recipe.** If the geometry is
   specific to one recipe, it lives in that recipe. Most "I need a
   wedge cluster of buildings" cases live as a
   `private List<PlacementSlot> generateWedgeSlots(...)` helper inside
   the single recipe that needs it.

2. **An existing primitive used differently.** `BuildingCircle`
   (TIGHT/LOOSE/SCATTER modes) covers many cluster geometries.
   `LinearRow` covers straight rows. `RingBand` covers concentric
   distributions. Confirm none of these fit before adding a new one.

3. **A sector with appropriate slots emitted directly.** Most
   primitive intent in the new architecture is just "build a list
   of `PlacementSlot`s and pass them to `new Sector(...)`." If you
   can express the geometry as a slot generator, you don't need a
   primitive.

A layout primitive is justified when the geometry is:

- **Used by 2+ recipes** (or genuinely planned to be).
- **Stateful in a way that warrants encapsulation** — `TownSquare`
  setting `townSquarePos` and `civicRingRadius` on the layout is the
  archetype.
- **Complex enough that a recipe-private helper would obscure the
  recipe's structure.**

If none of these apply, **stop and write a recipe-private helper
instead.** Use the `litv-layout-recipe` skill if it's a new recipe,
or just add a `private static List<PlacementSlot> ...` method to the
existing recipe.

If a primitive is justified, proceed to Step 0.

## Step 0 — Gather Required Inputs

Do NOT write any code until all items below are confirmed.

| # | Input | Notes |
|---|-------|-------|
| 1 | **Primitive name** | Will become the record name (e.g. `WedgeCluster`) |
| 2 | **Geometry concept** | What spatial shape does this primitive produce? |
| 3 | **Record fields** | What parameters does the caller supply? (positions, radii, angles, feeding road, edge id, tags…) |
| 4 | **Output mode** | Does this primitive emit slots for a sector (typical), perform direct placement (rare — `TownSquare` does this for its pavement), or both? |
| 5 | **Slot emission behaviour** | What slots does the primitive produce? With which tags, footprint budgets (W and L), and quality scores? |
| 6 | **Rotation strategy** | How should buildings face? (toward a focal point, perpendicular to a road, fixed angle, `chooseFacing`) Returned via the slot's `forcedRotation`, or left null for matcher-derived rotation. |
| 7 | **Sector wrapping** | How does the calling recipe wrap this primitive's output in a sector? Document the expected `SectorRole`, capacity, and `GrowthPolicy` the caller will use. The primitive doesn't construct the sector itself; the caller does, so the primitive author must understand caller intent. |

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
`PlacementSlot`, `SlotTag`, `VillageTypeData`, `PlanContext`. If your primitive
needs `Rotation`, add `import net.minecraft.world.level.block.Rotation;` at
the top of the file (already imported).

**Record skeleton:**
```java
// =========================================================================
// <PrimitiveName>
// =========================================================================

/**
 * <One-line description of geometry and use case.>
 *
 * <p>Output mode: <slot-emission | direct-placement | both>.
 * <p>Used by: <recipe names that consume this primitive>.
 *
 * <p>Caller pattern: the recipe wraps this primitive's slot output
 * in a Sector. Typical:
 * <pre>
 *   List&lt;PlacementSlot&gt; slots = new <PrimitiveName>(...).generateSlots(pctx);
 *   pctx.offerSector(new Sector(
 *       "&lt;recipe&gt;_&lt;sector_id&gt;", SectorRole.X, BuildingZone.Y,
 *       slots, capacity, canGrow, growth, parentEdgeId, exclusionShape));
 * </pre>
 */
record <PrimitiveName>(
        /* fields */
) implements LayoutPrimitive {

    /**
     * Slot-emission entry point. Most new primitives implement this
     * and return a list of slots; the calling recipe wraps them in a
     * Sector. The 9-arg PlacementSlot constructor is canonical.
     */
    public List<PlacementSlot> generateSlots(PlanContext pctx) {
        // Build slots; each carries:
        //   - pos             (surface-snapped via pctx.solidSurface)
        //   - feedingRoad     (from a field, or null)
        //   - feedingEdgeId   (from a field, or -1 for free-floating)
        //   - tags            (from a field — the caller picks)
        //   - footprintBudgetW / footprintBudgetL
        //   - forcedRotation  (or null for matcher-derived)
        //   - qualityScore
        //   - terrainPenalty  (0 unless the primitive computes one)
        //
        // See references/api.md → "Slot generation patterns".
    }

    @Override
    public void place(PlanContext pctx) {
        // Direct placement is uncommon. Implement only if the primitive
        // owns building commitment AND world-state setup that's separate
        // from the matcher's slot consumption. TownSquare is the
        // canonical example: it draws plaza pavement and sets
        // civicRingRadius — that's not slot-generation, that's layout
        // state. Otherwise leave empty; the matcher places buildings
        // via slot consumption.
    }

    @Override
    public void emitSlots(PlanContext pctx) {
        // Legacy flat-slot emission. New primitives leave this empty
        // unless they must support unconverted recipes during the
        // Phase 8-15 migration window. Flag any non-empty body
        // explicitly with a TODO referencing the recipe(s) that need it.
    }
}
```

### Method roles after Phase 10

- **`generateSlots(PlanContext)` → `List<PlacementSlot>`** is the new
  canonical entry point. The recipe consumes the return value and
  wraps it in a sector.
- **`place(PlanContext)`** is preserved on the interface but rarely
  implemented. Use only when the primitive does world-state placement
  that's separate from building emission. `TownSquare` qualifies
  because it draws plaza pavement and sets `civicRingRadius`.
- **`emitSlots(PlanContext)`** is the legacy flat-slot path. Leave
  empty for new primitives unless backward compatibility with
  unconverted recipes is genuinely needed.

If the `LayoutPrimitive` interface doesn't yet declare
`generateSlots`, the primitive can still expose it as a non-interface
public method — recipes call it on the concrete record type. Phase
11+ recipe conversions will decide whether to add a `default
generateSlots` to the interface; for now the convention stands either
way.

See `references/api.md` for slot generation patterns, rotation
helpers, footprint lookup, and the rare `place()` patterns.

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

- [ ] The primitive justifies its existence per Step −1 (used by 2+ recipes, stateful in a way that warrants encapsulation, or complex enough that a recipe-private helper would obscure the recipe).
- [ ] `generateSlots` returns `List<PlacementSlot>` using the 9-arg constructor: `pos`, `feedingRoad`, `feedingEdgeId`, `tags`, `footprintBudgetW`, `footprintBudgetL`, `forcedRotation`, `qualityScore`, `terrainPenalty`.
- [ ] Rotation, when forced, uses a sensible heading derivation (typically `rotationAlongRoad(prev, next)` or facing toward a focal point via `PlanContext.chooseFacing`). Otherwise `forcedRotation` is `null` (matcher-derived).
- [ ] `place(PlanContext)` is empty unless the primitive has a documented direct-placement responsibility (write that responsibility into the Javadoc).
- [ ] `emitSlots(PlanContext)` is empty for new primitives unless backward compatibility with unconverted recipes is required (flag with a TODO if non-empty).
- [ ] `PlanContext.parseType(sb)` null-check is present anywhere the primitive iterates buildings — usually only `place()` does this, since `generateSlots` typically doesn't iterate `pctx.remaining`.
- [ ] Every slot's footprint budget (`W` and `L`) is consistent with the primitive's intended building size class.
- [ ] Slot tags used are real `SlotTag` enum values (see `references/api.md`).
- [ ] `permits` clause updated in `LayoutPrimitive.java`.

Present the complete record body inline in the chat, followed by the
updated `permits` clause and a one-paragraph note on how the calling
recipe is expected to wrap the output in a sector.
