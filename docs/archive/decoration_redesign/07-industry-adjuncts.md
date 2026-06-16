# 07 — Industry Adjuncts

## Purpose

Give every production building a visible working yard attached to it.
The yard is the most visible "this is a working place" signal in the
entire village — more than the building itself, because buildings tend
to look similar at a glance while yards say "this one makes tools" or
"this one weaves cloth."

All content here uses the AdjunctPlot framework (subsystem 02).

## Design

### Yard catalog

Each production building gets exactly one AdjunctPlotType attached:

| Building     | AdjunctPlotType      | Footprint | Contents                                                                 |
|--------------|----------------------|-----------|--------------------------------------------------------------------------|
| BLACKSMITH   | FORGE_YARD           | 5×5       | anvil, coal pile, water barrel, tool rack, weapon display                |
| ARMORER      | FORGE_YARD (variant) | 5×5       | anvil, grindstone, armor stand, weapon rack                              |
| TOOLSMITH    | FORGE_YARD (variant) | 5×5       | anvil, workbench, tool storage, iron stockpile                           |
| STONEMASON   | KILN_YARD            | 5×5       | smelting pit, stone stacks, chisel tools, raw-stone heap                 |
| CANDLEMAKER  | KILN_YARD (variant)  | 4×4       | small smelting pit, honeycomb pile, wax stockpile, drying rack           |
| WEAVER       | DRYING_RACK_YARD     | 5×5       | wooden drying frames, wool bundles, dye vat, spinning wheel prop         |
| FISHERY      | DRYING_RACK_YARD     | 5×5       | net-drying frames, fish barrels, weighing scale, coiled rope             |
| CARPENTRY    | LOG_YARD             | 6×6       | log piles, plank stacks, sawhorse, sawdust heap                          |
| WOODCUTTER   | LOG_YARD             | 6×6       | log piles, axe rack, bundle of kindling, sawhorse                        |
| STABLE       | PADDOCK              | 8×8       | fenced paddock, hay bales, water trough, hitching rails                  |
| BAKERY       | OVEN_SHED            | 4×4       | wood-fired oven NBT, flour sacks, wheat pile                             |
| MILLER       | LOG_YARD (sack var.) | 5×5       | grain sacks, millstone prop, barrels, flour-dust                         |
| VINEYARD     | (uses FarmPlot)      | —         | vineyard IS the field; no adjunct                                        |
| MINE         | KILN_YARD (raw)      | 5×5       | ore stockpile, cart, minecart rail segments, coal heap                   |

Each yard ships as a single NBT (NBT strategy) per culture, with biome-
style material substitution applied post-stamp.

### Content variation

Multiple variants per culture, authored over time:

```
structures/default/decoration/industry/forge_yard_1.nbt
structures/default/decoration/industry/forge_yard_2.nbt
structures/nordic/decoration/industry/forge_yard_1.nbt
structures/imperial/decoration/industry/forge_yard_1.nbt
```

AdjunctPlotPlacer picks one at random per plot. Fallback via
CultureResolver.

### Slope and terrain

Yards tolerate mild slope (within 2 blocks over footprint) but not
steep terrain. On steep ground the plot silently drops — the parent
building still functions.

Yards do NOT levelPad by default. A foundation pass similar to
BuildingFoundation runs on each yard, placing retaining walls on
uphill sides and stilts on downhill, matching the parent building's
foundation style.

### NPC goal integration

Profession-specific goals (BlacksmithGoal, WeaverGoal, etc.) gain a
"work in yard" phase that sends the NPC to the yard during part of
their work window:

```
Work phase 1 (building interior)   — production at the workstation
Work phase 2 (yard)                — tending output / props
Work phase 3 (building interior)   — resume production
```

Phase 2 is purely visual — the NPC stands at the yard, occasionally
picks up a prop item, swings a tool animation. No item production
happens in the yard. This is an NPC-layer change, out of scope for
this decoration plan but worth noting for downstream work.

### Economy integration

Yards don't independently produce anything. They're props. Production
remains inside the building. No ResourceCategory contribution.

(Exception: PADDOCK — see below.)

### Paddock as a real livestock location

`STABLE → PADDOCK` is the one exception where the adjunct plot has
gameplay meaning. The paddock spawns and maintains a small number of
horses (or donkeys/mules, per culture). The stable's inhabitants
include these animals; they graze during the day, return to the stable
at night (existing horse pathing).

This integrates with the economy via the STABLE profession — which
already exists as FARMER role for now; a STABLEHAND role is left as
future work.

## Data structures

No new records beyond `AdjunctPlot`. Registration table:

```java
public final class IndustryAdjunctRegistry {
    static {
        register(BuildingType.BLACKSMITH,  AdjunctPlotType.FORGE_YARD,       5, 5);
        register(BuildingType.ARMORER,     AdjunctPlotType.FORGE_YARD,       5, 5);
        register(BuildingType.TOOLSMITH,   AdjunctPlotType.FORGE_YARD,       5, 5);
        register(BuildingType.STONEMASON,  AdjunctPlotType.KILN_YARD,        5, 5);
        register(BuildingType.CANDLEMAKER, AdjunctPlotType.KILN_YARD,        4, 4);
        register(BuildingType.WEAVER,      AdjunctPlotType.DRYING_RACK_YARD, 5, 5);
        register(BuildingType.FISHERY,     AdjunctPlotType.DRYING_RACK_YARD, 5, 5);
        register(BuildingType.CARPENTRY,   AdjunctPlotType.LOG_YARD,         6, 6);
        register(BuildingType.WOODCUTTER,  AdjunctPlotType.LOG_YARD,         6, 6);
        register(BuildingType.STABLE,      AdjunctPlotType.PADDOCK,          8, 8);
        register(BuildingType.BAKERY,      AdjunctPlotType.OVEN_SHED,        4, 4);
        register(BuildingType.MILLER,      AdjunctPlotType.LOG_YARD,         5, 5);  // sack variant
        register(BuildingType.MINE,        AdjunctPlotType.KILN_YARD,        5, 5);  // raw variant
    }
}
```

## Integration points

- **AdjunctPlotFramework**: registers all industry yards through the
  shared framework. No new placement code.
- **CultureResolver**: per-culture NBT variants.
- **VillageBiomeStyle**: material substitution post-stamp.
- **NPC profession goals**: consume yard AdjunctPlots as optional work
  phase targets. Purely visual; no item logic.
- **Paddock + livestock**: spawn/persist horses per stable. Tied into
  existing animal spawning.

## Behavior contract

### Does

- Attach one yard to each production building of the listed types.
- Variety through multiple NBT variants per type.
- Silent-drop on steep terrain.

### Does not

- Add production logic inside yards (except paddock livestock).
- Tie yards to building condition — they don't decay independently.
- Resize over time.

## Edge cases

- **Building against a wall or cliff with no clear face.** Yard drops.
- **Building surrounded by close neighbors.** Yard drops. Common in
  dense urban layouts; acceptable.
- **Stable without surrounding pasture.** Paddock fills with hay bales
  and 1-2 horses only. Larger herds need adjacent pasture which is out
  of scope for the paddock.
- **Blacksmith in a culture without forge yard NBT.** Default culture
  fallback. If default lacks it, no yard — log warning.

## Ordering dependencies

- Requires subsystem 02 (AdjunctPlotFramework) complete.
- Requires CultureResolver (exists).
- NBT content authoring can be phased — each yard type is independent.

## Open decisions

- **Variant count per culture.** Proposed minimum: 2 variants per yard
  type per culture. Start with 1 and expand; authoring is the bottleneck.
- **Paddock livestock count.** Proposed: 2 horses per stable as a
  baseline, scales with stable level.
- **LOG_YARD sack variant vs base variant.** Proposed: separate NBT
  files under `{culture}/decoration/industry/log_yard_sacks/`. Simpler
  than a runtime flag.

## Does-not-include

- Yard-based production (smelting in the forge yard) — all production
  stays inside the parent building.
- Upgrade paths (bigger yard as building levels up) — single size per
  yard type.
- Weather integration (yards don't get covered in snow vs. summer
  variants).

## Revision notes

(Changes recorded here as the spec evolves.)
