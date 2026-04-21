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
  and slot tagging conventions the mod requires.
---

# Life in the Village — Layout Recipe Skill

## Step 0 — Gather Required Inputs

Do NOT write any code until you have confirmed answers to every item below.
Ask the user for any that are missing or ambiguous.

| # | Input | Notes |
|---|-------|-------|
| 1 | **ShapeType enum value** | Must match an existing value in `VillageTypeData.ShapeType` or you must ask the user to add one |
| 2 | **Concept / theme** | e.g. "river-bend trading post", "fortified hilltop", "logging camp" |
| 3 | **Road structure** | Describe the skeleton: a single main road? ring + spurs? arc along a feature? |
| 4 | **Terrain trigger** | Which `TerrainProfile` fields gate/shape this layout? (slope, water, ridges, tree cover, flat ratio) |
| 5 | **Terrain fallback** | Which existing recipe to delegate to when terrain conditions aren't met |
| 6 | **Building distribution** | How are CIVIC / PRODUCTION / RESIDENTIAL / AGRICULTURAL / DEFENSIVE buildings distributed across the road network? |
| 7 | **Special slot tags** | Any terrain-specific tags to emit? (SHORE, TERRACE_EDGE, HILLTOP_PEAK, RIVER_BANK, FOREST_EDGE, HIGH_GROUND…) |

If the geometry you need cannot be expressed with existing layout primitives
(TownSquare, BuildingCircle, LinearRow, RingBand), use the `litv-layout-primitive`
skill first, then return here. Similarly, if you need a road centerline shape not
covered by existing road primitives (StraightRoad, CurvedRoad, Ring, Arc, Spur),
use the `litv-road-primitive` skill first.

Once all primitives are in place, proceed to write the recipe file. No design documents — output Java only.

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
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.LayoutPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.ShapeRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
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
public final class <Name>Recipe implements ShapeRecipe {

    @Override
    public void compose(PlanContext pctx) {
        TerrainProfile terrain = pctx.layout.getTerrain();
        BlockPos centre = pctx.layout.getCenter();

        // ── Terrain guard ──────────────────────────────────────────────────
        // Check terrain conditions and fall back if not met.
        // See "Terrain Adaptation Patterns" below.

        // ── Town square ────────────────────────────────────────────────────
        // Always first. Emits PRIME_CIVIC / SECONDARY_CIVIC slots for matcher.

        // ── Road network ───────────────────────────────────────────────────
        // Build roads, collect centerlines into allRoads.

        // ── Slot emission ──────────────────────────────────────────────────
        // Call pctx.offerRoadSlots() and/or primitive.emitSlots() on each road.
        // Emit terrain-feature slots (SHORE, HIGH_GROUND, etc.) if applicable.

        // ── Outer rings ────────────────────────────────────────────────────
        // RecipeHelpers.placeAgriculturalRing / placeDefensiveRing / placeStragglersRingBand
        // Use only if these building types need to live outside the road network.

        // ── Town hall rescue ───────────────────────────────────────────────
        // RecipeHelpers.rescueTownHallOnAnyRoad(pctx, allRoads);
    }
}
```

See `references/api.md` for the complete API reference (primitives, slot tags, terrain reads, RecipeHelpers).

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

- [ ] `compose()` ends without any `claimByZone` calls — matcher only
- [ ] No road pieces overlap — each road starts at the radius/endpoint of the previous connecting road (see `references/api.md` → Road Non-Overlap Rules)
- [ ] Every road's centerline is collected into `allRoads` (or equivalent) and passed to `offerRoadSlots`
- [ ] `TownSquare.emitSlots()` is called (not just `place()`)
- [ ] Terrain fallback is present and delegates to an existing recipe
- [ ] `RecipeHelpers.rescueTownHallOnAnyRoad` is called at the end
- [ ] All slot tags used are real values from the `SlotTag` enum (see `references/api.md`)
- [ ] `PlacementFailureRecorder.record(...)` is called alongside each fallback

Present the complete `.java` file inline in the chat, followed by the one-line `ShapeRecipe.forShape()` wiring change.
