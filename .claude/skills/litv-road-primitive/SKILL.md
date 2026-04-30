---
name: litv-road-primitive
description: >
  Writes complete, compilable Java RoadPrimitive implementations for the
  Life in the Village Minecraft mod. Use this skill whenever the user needs
  a new road centerline shape that existing primitives (StraightRoad,
  CurvedRoad, Ring, Arc, Spur) cannot express — for example, a switchback,
  a spiral approach, a figure-eight, or a road that hugs a terrain contour.
  Always use this skill before free-styling road primitive code. Output is
  the new record body to add to RoadPrimitive.java plus the one-line wiring
  change to the sealed interface permits clause.
---

# Life in the Village — Road Primitive Skill

## Step 0 — Gather Required Inputs

Do NOT write any code until all items below are confirmed.

| # | Input | Notes |
|---|-------|-------|
| 1 | **Primitive name** | Will become the record name (e.g. `Switchback`) |
| 2 | **Geometry concept** | What path shape does this produce? |
| 3 | **Record fields** | What parameters does the caller supply? (anchor points, radii, angles, drift amplitude, tier…) |
| 4 | **Centerline algorithm** | How are points computed? Step through the logic before writing code |
| 5 | **Drift strategy** | Does this primitive use `driftedLine`, per-point `DriftNoise.sample`, or no drift? |
| 6 | **Connection points** | What are the start and end positions of the returned centerline? Recipes depend on these for non-overlap. |
| 7 | **Caller contexts** | Will this primitive be used by recipes (always), grow strategies (sometimes), or both? Most primitives serve both. If the primitive requires explicit terrain-feature inputs that grow strategies can't easily synthesize, say so — that's a recipe-only primitive. Otherwise both contexts apply. |

---

## Step 1 — Write the Primitive

**File location:**
```
src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/RoadPrimitive.java
```
Add the new record at the end of `RoadPrimitive.java`, before the closing brace. All required imports and helpers (`surfaceAt`, `dedupe`, `driftedLine`, `DriftNoise`) are already present in the file.

**Record skeleton:**
```java
// =========================================================================
// <PrimitiveName>
// =========================================================================

/**
 * <One-line description of path geometry and intended use.>
 *
 * <Describe start point, end point, and any key geometry parameters.>
 */
record <PrimitiveName>(
        /* fields — always include RoadShape.RoadTier tier as last field */
) implements RoadPrimitive {

    @Override
    public List<BlockPos> computeCenterline(ServerLevel level, long worldSeed) {
        // 1. Derive a deterministic local seed from worldSeed + anchor points
        // 2. Walk the geometry, calling surfaceAt() at every point
        // 3. Apply drift via DriftNoise.sample() or driftedLine()
        // 4. Return dedupe(line)
        // See references/api.md for full patterns
    }

    @Override
    public RoadShape.RoadTier tier() { return tier; }
}
```

See `references/api.md` for the seeding contract, drift patterns, surface snapping, and shared helper signatures.

### Caller contexts

A road primitive's `computeCenterline` is called from two places:

1. **Recipe direct.** The recipe builds the primitive in `composeSectors` and passes it to `pctx.layout.addEdge(...)`. The recipe captures the returned edge id for sector wiring. This is the common case.
2. **Grow strategy.** When a sector overflows, an `ExtendAlongEdge` / `AddRing` / `AddSpur` strategy may construct a primitive of the appropriate type and add it to the graph. This happens during matching, after compose finishes.

Both contexts call `computeCenterline(level, worldSeed)` with the same arguments. The primitive does not know which context invoked it, and does not need to. The seeding contract — deterministic from `worldSeed` plus anchor positions — ensures the centerline is reproducible regardless of caller.

---

## Step 2 — Wire into the sealed interface

In `RoadPrimitive.java`, add the new type to the `permits` clause:

```java
public sealed interface RoadPrimitive
        permits RoadPrimitive.StraightRoad,
        RoadPrimitive.CurvedRoad,
        RoadPrimitive.Ring,
        RoadPrimitive.Arc,
        RoadPrimitive.Spur,
        RoadPrimitive.<PrimitiveName> {   // ← add this line
```

---

## Step 3 — Self-check before presenting

- [ ] `computeCenterline` calls `surfaceAt(level, x, z)` at every point — never raw `BlockPos`
- [ ] `dedupe(line)` is called before returning
- [ ] Seed is derived via `DriftNoise.localSeed(worldSeed, p1, p2)` XOR'd with geometry constants — never a raw `worldSeed`
- [ ] `tier()` returns the `tier` field
- [ ] Start and end points of the returned centerline are documented in the Javadoc
- [ ] `permits` clause updated in `RoadPrimitive.java`
- [ ] The primitive's centerline is fully determined by `worldSeed` plus the record's fields. No `new Random()`, no `level.getRandom()`, no calls to mutable global state. (Grow strategies invoke primitives from inside the matcher loop where call-order nondeterminism would silently break placement determinism.)

Present the complete record body inline in the chat, followed by the updated `permits` clause.
