---
name: litv-terrain-step
description: >
  Writes a new TerrainStep implementation for the Life in the Village Minecraft
  mod, and optionally wires it into a new or existing TerrainStrategy. Use this
  skill whenever the user needs a new world-modification pass that runs after
  village planning (e.g. a flood-fill water removal step, a bridge-building step,
  a custom foundation style, a contour-grading pass). Output is a new class in
  the Steps package plus, if needed, a new TerrainStrategy enum value. Always
  use this skill before free-styling terrain step code — the contract (no layout
  mutations, slot.snapY() as the only exception) is easy to violate silently.
---

# Life in the Village — Terrain Step Skill

## Step 0 — Gather Required Inputs

| # | Input | Notes |
|---|-------|-------|
| 1 | **Class name** | e.g. `BridgeStep`, `DrainWaterStep` |
| 2 | **What world modifications it makes** | Blocks placed/removed, surface Y changes, etc. |
| 3 | **What layout/profile data it reads** | Building slots, pad Y, terrain profile fields, road footprint? |
| 4 | **Strategy wiring** | Goes into an existing `TerrainStrategy`, or needs a new one? |
| 5 | **Constructor parameters** | Any configuration? (most steps are zero-arg) |

---

## Step 1 — Write the Step Class

**File location:**
```
src/main/java/tterrag1112/life_in_the_village/Village/Planning/Terrain/Steps/<n>Step.java
```

**Package:**
```java
package tterrag1112.life_in_the_village.Village.Planning.Terrain.Steps;
```

**Skeleton:**
```java
/**
 * <One-line description of what this step does and why.>
 *
 * <h3>Scope</h3>
 * <What area of the world does it touch? What does it leave alone?>
 *
 * <h3>Contract</h3>
 * Does NOT modify layout slot positions — those are final by the time
 * terrain steps run. Only calls slot.snapY() to commit a new pad Y
 * after physical levelling.
 */
public class <n>Step implements TerrainStep {

    @Override
    public String name() { return "<snake_case_name>"; }

    @Override
    public void execute(ServerLevel level, VillageLayout layout,
                        TerrainProfile profile, VillageBiomeStyle style) {
        // implementation
        // see references/api.md for available APIs
        System.out.println("<n>Step: <summary of what was done>");
    }
}
```

See `references/api.md` for block placement patterns, slot iteration,
`VillageBiomeStyle` palette, and the full `TerrainProfile` field list.

---

## Step 2 — Wire into TerrainStrategy

**File:** `src/main/java/tterrag1112/life_in_the_village/Village/Planning/Terrain/TerrainStrategy.java`

### Adding to an existing strategy
```java
WATERFRONT(List.of(
        new ClearTreesStep(),
        new FillHolesStep(),
        new DetectShorelineStep(),
        new <n>Step(),           // ← add here
        new LevelBuildingPadsStep(true),
        new RetainingWallStep(),
        new FoundationStep()
)),
```

### Adding a new strategy (if needed)
```java
<NEW_NAME>(List.of(
        new ClearTreesStep(),
        new <n>Step(),
        // ... compose from existing + new steps
)),
```

Then add the import for the new step class at the top of `TerrainStrategy.java`.

---

## Step 3 — Self-check before presenting

- [ ] `execute()` does NOT call any `layout.tryAdd()` or modify slot positions
- [ ] `slot.snapY(y)` is the only layout mutation allowed (only after physically levelling)
- [ ] Block placement uses `level.setBlock(pos, state, 18)` (flag 18 = update + notify)
- [ ] Surface Y is read via `level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)`
- [ ] `name()` returns a short snake_case string for logging
- [ ] `System.out.println()` at the end summarises what was done (count of blocks, etc.)
- [ ] Step is added to the correct position in the strategy pipeline (see ordering in `references/api.md`)

Present the complete class file inline, followed by the strategy wiring change.
