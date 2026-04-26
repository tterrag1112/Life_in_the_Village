# 02 — AdjunctPlot Framework

## Purpose

A unified pattern for "a small plot attached to a specific building."
Examples: an apothecary's herb garden, a blacksmith's forge yard, a
stable's paddock, a house's homestead chicken coop, a temple's
meditation garden.

Before this framework, each such feature would require its own placer,
its own registry, its own save-data integration. The AdjunctPlot
framework provides one of each and lets all content subsystems register
their own plot types into it.

## Design

### The plot concept

An AdjunctPlot is:

1. Attached to exactly one parent `Building` via `parentBuildingId`.
2. Sized small (typically 3×3 to 8×8 blocks, larger for paddocks).
3. Placed on a specific face of the parent building (back or side,
   never front).
4. Registered on `VillageSavedData` alongside `FarmPlot`.
5. Materialized either by NBT stamping or piece-kit assembly.
6. Consulted by other systems (economy, profession goals, decoration)
   via its type and position.

### Two placement strategies

**NBT strategy (default)**: the content is a single pre-authored NBT
stamped at the plot origin. Simpler to author, less adaptive.

**Piece-kit strategy**: the content is assembled from primitives
(FlowerBed, Hedgerow, Trellis, etc.) with variation per plot. Reserved
for content where variation matters more than polish (homesteads,
simple industry yards).

Each `AdjunctPlotType` declares which strategy it uses.

### Building → AdjunctPlot spec

A single table maps `BuildingType` (optionally + culture) to the list of
AdjunctPlot types that should be attached to it when the building is
placed:

```java
APOTHECARY   → [HERB_GARDEN]
BLACKSMITH   → [FORGE_YARD]
INN          → [KITCHEN_GARDEN]
TEMPLE       → [MEDITATION_GARDEN]
NOBLE_MANOR  → [FORMAL_GARDEN, STABLE_PADDOCK]
STABLE       → [PADDOCK]
WEAVER       → [DRYING_RACK_YARD]
FISHERY      → [DRYING_RACK_YARD]
CARPENTRY    → [LOG_YARD]
WOODCUTTER   → [LOG_YARD]
CANDLEMAKER  → [KILN_YARD]
STONEMASON   → [KILN_YARD]
BAKERY       → [OVEN_SHED]
HOUSE        → [ maybe HOMESTEAD_* (size-tier gated, subsystem 11) ]
```

The spec table lives in `AdjunctPlotRegistry` and is extensible via a
registration method called from subsystems 07, 08, and 11.

### Placement algorithm

Called once per building, during a realiser pass that runs AFTER
building placement and foundation but BEFORE the uniform decoration
emitter (so AdjunctPlot contents don't get decorated-over):

1. Look up the building's AdjunctPlot specs.
2. For each spec, probe the four cardinal directions from the building
   (back first, then sides), testing for:
   - Clear footprint of the required size
   - Flat enough terrain (slope tolerance per type)
   - No road or building overlap
   - Not blocking the building's front face
3. If any direction passes, place the plot.
4. If none pass, the plot silently drops (no retry). The building
   functions normally without it.

Plot orientation matches the building's rotation. Plot origin is the
center of the plot footprint.

## Data structures

```java
public record AdjunctPlot(
    UUID plotId,
    UUID parentBuildingId,
    AdjunctPlotType type,
    BlockPos origin,            // center, same Y as building floor
    int halfWidthX,             // facing-matched
    int halfLengthZ,
    Direction facingFromParent, // which side of parent the plot is on
    Rotation rotation           // matches parent
) {}

public enum AdjunctPlotType {
    // Industry (subsystem 07)
    FORGE_YARD, DRYING_RACK_YARD, KILN_YARD, LOG_YARD,
    PADDOCK, OVEN_SHED,

    // Gardens (subsystem 08)
    HERB_GARDEN, KITCHEN_GARDEN, MEDITATION_GARDEN, FORMAL_GARDEN,

    // Homesteads (subsystem 11)
    HOMESTEAD_COOP, HOMESTEAD_GARDEN, HOMESTEAD_PEN, HOMESTEAD_BEES;

    // Each value carries: default footprint, strategy (NBT/kit),
    // slope tolerance, preferred face, required resource contribution
}
```

Persisted on `VillageSavedData` as `Map<UUID, AdjunctPlot>` with accessor
methods `addAdjunctPlot`, `getAdjunctPlotsForBuilding`,
`getAdjunctPlotById`, `removeAdjunctPlot`.

## Integration points

- **Realiser pipeline**: new `AdjunctPlotRealiser` step between
  `BuildingRealiser` and `DecorationPass`.
- **NPC goals**: farmer-like goals (feeding chickens at coop, tending
  herb garden) receive a building's `AdjunctPlot` list as a target
  pool. Profession-specific routing covered in subsystems 07 and 11.
- **Economy channels (NPC Phase 3)**: homestead plots open
  DirectBusinessChannels at their location.
- **Resource categories (NPC Phase 4)**: plot types declare their
  contribution to `ResourceCategory` maps in the building's profile.
- **Decoration framework**: the uniform emitter skips slots that fall
  inside an AdjunctPlot footprint.

## Behavior contract

### Does

- Provide a single pattern for "something attached to a building."
- Persist per-plot records with stable UUIDs.
- Pick a valid face for the plot automatically.
- Scale footprint and content per building tier where relevant.

### Does not

- Replace FarmPlot for field-scale agriculture. FarmPlot remains
  village-territory-scale; AdjunctPlot is building-scale. (Homesteads
  blur the line but register as AdjunctPlots for the placement
  framework and as FarmPlot subtypes for production logic.)
- Resize over time. Once placed, a plot's bounds are fixed.
- Retry if placement fails. Silent drop.

## Edge cases

- **Building crammed between neighbors with no free face.** Plot drops.
  Acceptable loss.
- **Plot collides with a planned road.** Road wins; plot drops during
  the probe.
- **Multiple plots on the same building competing for the same face.**
  First-spec-wins ordering. Secondary plots probe remaining faces.
- **Building demolished or replaced.** Plot orphaned; cleanup hook in
  `VillageSavedData.removeBuilding` removes all child plots.
- **Parent building rotated after plot placement (should not happen
  but guard against it).** Plot is re-oriented at load time from
  parent's current rotation.

## Ordering dependencies

- Runs after `BuildingRealiser` and `BuildingFoundation`.
- Runs before `DecorationPass` (so uniform emission can skip plot
  footprints).
- No dependency on NPC or Trade Route redesigns for the framework
  itself. Specific content subsystems (homesteading) depend on NPC
  Phases 3 and 4.

## Open decisions

- **Probe order for faces.** Proposed: back → left → right → front.
  Never front unless explicitly allowed by the plot type. Confirm
  before implementing.
- **Multiple plots per building.** Proposed: max 3, hard cap in the
  registry. Noble manor is the only current 2-plot case (formal garden
  + stable paddock).
- **Codec stability.** Plot type is a string enum, so reordering the
  enum is safe. New values always append.

## Does-not-include

- Village-scale gardens (parks) — those are `GardenPlot`, subsystem 09.
- Field-scale farm plots — remain `FarmPlot`.
- Player-placed plots — AdjunctPlot is strictly procedural in this
  rework.
- Dynamic plot content updates — each plot is materialized once at
  placement.

## Revision notes

(Changes recorded here as the spec evolves.)
