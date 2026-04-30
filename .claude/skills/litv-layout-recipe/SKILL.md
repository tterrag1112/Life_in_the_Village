---
name: litv-layout-recipe
description: >
  Writes complete, compilable Java ShapeRecipe implementations for the
  Life in the Village Minecraft mod. Use this skill whenever the user asks
  to create a new village layout, add a new ShapeType recipe, or write code
  for a new village shape — even if they describe it informally (e.g.
  "a village that wraps around a river bend", "a fortified hilltop town",
  "a trade-road village"). The skill gathers required inputs first, then
  produces a single ready-to-drop-in .java file plus the one-line wiring
  change in ShapeRecipe.forShape(). Always use this skill before free-styling
  layout code — it contains the full API surface, terrain adaptation patterns,
  sector emission patterns, and slot tagging conventions the mod requires.
---

# Life in the Village — Layout Recipe Skill

Post-Phase-10 architecture: recipes extend `BaseRecipe`, build the
road graph via `addNode` / `addEdge`, and emit `Sector` records via
`pctx.offerSector`. The matcher consumes sectors and grows them on
overflow. See `docs/placement-rework/01-PLACEMENT-ABSTRACTIONS.md`
for the full contract; this skill operationalises it.

## Step 0 — Gather Required Inputs

Do NOT write any code until you have confirmed answers to every item below.
Ask the user for any that are missing or ambiguous.

| # | Input | Notes |
|---|-------|-------|
| 1 | **ShapeType enum value** | Must match an existing value in `VillageTypeData.ShapeType` or you must ask the user to add one |
| 2 | **Concept / theme** | e.g. "river-bend trading post", "fortified hilltop", "logging camp" |
| 3 | **Road graph** | What spine edges? What spur edges? Where are the gates? Sketch the graph as nodes + edges, not as "a road that does X". This determines `addNode` / `addEdge` calls. |
| 4 | **Terrain trigger** | Which `pctx.features` queries gate or shape this layout? (e.g. `nearestShoreEdge`, `cliffFeatures()`, `isOnWater`, `isInsideHull`). Recipes consume `pctx.features`, not `pctx.layout.getTerrain()` directly. |
| 5 | **Terrain fallback** | When the trigger isn't met. Two options: (a) in-recipe fallback that delegates to another recipe's `compose` (current convention); (b) declarative fallback chain (Phase 22 — not shipped yet, prefer (a) for now). |
| 6 | **Sector composition** | Which sectors does this recipe emit? Typical: a civic ring, one or more spur clusters, residential infill along the spine, agri/defensive fringes, named anchors, reservations. Each sector gets a stable id, a `SectorRole`, a capacity, and a growth strategy. |
| 7 | **Growth strategies** | For each non-fixed sector, which `GrowthPolicy`? `FixedGrowth.INSTANCE` (no growth — civic rings, anchors, reservations), `AddSpur` (spur clusters), `AddRing` (concentric rings — agri/defense), `ExtendAlongEdge` (spine residential infill). |
| 8 | **Special slot tags** | Any terrain-specific tags to emit? (`SHORE`, `TERRACE_EDGE`, `HILLTOP_PEAK`, `RIVER_BANK`, `FOREST_EDGE`, `HIGH_GROUND`…) |

If the geometry you need cannot be expressed within `composeSectors`,
consider whether new layout primitives are warranted (`litv-layout-primitive`).
**Most new recipe geometry now lives directly in `composeSectors`** —
new layout primitives are reserved for genuinely reusable geometry
(like the soon-to-be-rebuilt TownSquare). Don't reach for
`litv-layout-primitive` reflexively.

If you need a road centerline shape not covered by existing road
primitives (StraightRoad, CurvedRoad, Ring, Arc, Spur), use
`litv-road-primitive` first.

Once inputs are confirmed, proceed to write the recipe file. No design documents — output Java only.

---

## Step 1 — Write the Recipe File

**File location:**
```
src/main/java/tterrag1112/life_in_the_village/Village/Planning/Primitives/Recipes/<Name>Recipe.java
```

**Package declaration:**
```java
package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;
```

**Required imports block** (copy and trim as needed):
```java
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.Features.FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole;
import tterrag1112.life_in_the_village.Village.Planning.Graph.NodeKind;
import tterrag1112.life_in_the_village.Village.Planning.Graph.RoadGraph;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.BaseRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.AddRing;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.AddSpur;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.ExtendAlongEdge;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.FixedGrowth;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.Sector;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.SectorRole;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;
import tterrag1112.life_in_the_village.Village.VillageTypeData;
import tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
```

**Class skeleton:**
```java
public final class <Name>Recipe extends BaseRecipe {

    // ── Sector identifiers (recipe-prefixed to avoid collisions) ─────
    private static final String SECTOR_CIVIC      = "<name>_civic_ring";
    private static final String SECTOR_SPINE_INFL = "<name>_spine_residential";
    // ... etc

    // ── Tag sets (named constants per slot class) ────────────────────
    private static final Set<SlotTag> TAGS_SPINE = EnumSet.of(
        SlotTag.ROAD_ADJACENT, SlotTag.RESIDENTIAL_INFILL, SlotTag.BACKFILL);
    // ... etc

    @Override
    protected void prepareFeatures(PlanContext pctx) {
        // OPTIONAL pre-pass. Override only if the recipe needs to:
        //   - Compute a feature-aware spine direction (e.g. RIVERINE
        //     orienting parallel to a shore polygon edge)
        //   - Locate a terrain anchor (e.g. HILLTOP locating the peak
        //     via cliffFeatures())
        //   - Pin plaza polygons before sector emission
        // If you don't need any of this, omit the override entirely —
        // BaseRecipe's default is a no-op.
    }

    @Override
    protected void composeSectors(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        FeatureMap features = pctx.features;

        // 1. TERRAIN GUARD / FALLBACK
        //    Check pctx.features queries; PlacementFailureRecorder.record(...)
        //    + delegate to another recipe's compose() if conditions aren't met.
        //    See references/api.md → "Feature-aware patterns".

        // 2. ROAD GRAPH
        //    Build endpoints with pctx.solidSurface, register them as nodes
        //    via pctx.layout.addNode(pos, NodeKind.X, tier), then add each
        //    primitive as an edge via pctx.layout.addEdge(...) and capture
        //    the returned edge id.
        //    See references/api.md → "Building the road graph".

        // 3. SECTORS
        //    For each region of placement intent, build a Sector and call
        //    pctx.offerSector(...). Pick a SectorRole, BuildingZone hint,
        //    capacity, growth strategy, and parentEdgeId.
        //    See references/api.md → "Sector emission patterns".

        // 4. GATES (still required for road network)
        //    pctx.layout.addGatePosition(gatePos);
        //    pctx.layout.setMainGateEndpoint(mainGatePos);
    }

    @Override
    protected void registerAnchors(PlanContext pctx) {
        super.registerAnchors(pctx);  // registers MAIN_GATE if set
        // Override only if this recipe has additional named anchors
        // (TREASURY_ANCHOR for capital recipes, AUDIENCE_CHAMBER, etc.)
    }
}
```

### When NOT to extend BaseRecipe

`BaseRecipe` is convenience, not contract. Recipes whose structure
doesn't fit the three-step lifecycle (CLUSTERED, GROVE, OUTPOST,
possibly others) implement `ShapeRecipe` directly and override
`compose(PlanContext)` themselves. They still emit sectors via
`pctx.offerSector(...)`; they just skip the lifecycle scaffolding.
This is fine and not a workaround.

A recipe should implement `ShapeRecipe` directly if and only if its
compose flow genuinely cannot decompose into prepareFeatures →
composeSectors → registerAnchors. If you're tempted to override
`compose` on a `BaseRecipe`-extending class, use `ShapeRecipe`
directly instead — `BaseRecipe.compose` is intentionally `final`.

See `references/api.md` for the complete API reference (graph, sectors, features, RecipeHelpers).

---

## Step 2 — Wire into ShapeRecipe.forShape()

Add one line to the switch in
`src/main/java/.../Village/Planning/Primitives/ShapeRecipe.java`:

```java
case <SHAPE_TYPE> -> new <Name>Recipe();
```

If the ShapeType value doesn't exist yet, add it to `VillageTypeData.ShapeType` first.

---

## Step 3 — Self-check before presenting the file

Before presenting the completed file, verify:

- [ ] Class extends `BaseRecipe` (or implements `ShapeRecipe` directly with documented justification).
- [ ] `composeSectors` (or `compose`) ends without any `claimByZone` calls. The matcher consumes from `pctx.remaining`; recipes never claim.
- [ ] No `pctx.offerSlot(...)` or `pctx.offerRoadSlots(...)` calls — those are the legacy flat-slot path. New recipes emit sectors only.
- [ ] Every sector has a stable, prefix-namespaced `id` (e.g. `"radial_civic_ring"`, not just `"civic_ring"`). Sector ids must not collide across recipes.
- [ ] Every sector has a `SectorRole` matching its function.
- [ ] Sectors with growable overflow set `canGrow=true` and a non-FixedGrowth strategy.
- [ ] Sectors that should NOT grow (civic rings, named anchors, reservations) use `FixedGrowth.INSTANCE` and `canGrow=false`.
- [ ] Reservations have `capacity=0` and an empty slot list.
- [ ] Every sector with road-adjacent slots has a valid `parentEdgeId` (not `-1`) so growth knows what to extend. Free-floating sectors (rings, reservations) may use `-1`.
- [ ] Road primitives are added via `pctx.layout.addEdge(fromNode, toNode, primitive, level, seed, role)` with explicit node IDs and an `EdgeRole`. The legacy 3-arg `addRoad` is acceptable for transitional recipes but new code prefers `addEdge`.
- [ ] `pctx.features` is consulted before placing slots in feature-sensitive locations (RIVERINE near water, HILLTOP near cliffs).
- [ ] Terrain fallback (if used) calls `PlacementFailureRecorder.record(...)` before delegating.
- [ ] All slot tags used are real `SlotTag` enum values (see `references/api.md`).
- [ ] Anchor registration covers `MAIN_GATE` at minimum — the default `super.registerAnchors(pctx)` handles that if `setMainGateEndpoint` was called.
- [ ] Sectors do not overlap. (See `docs/placement-rework/01-PLACEMENT-ABSTRACTIONS.md` § "Sector" for the overlap policy.)

Present the complete `.java` file inline in the chat, followed by the one-line `ShapeRecipe.forShape()` wiring change.
