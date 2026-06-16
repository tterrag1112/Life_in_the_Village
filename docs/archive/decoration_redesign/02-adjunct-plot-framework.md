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

### P0c-01 / P0c-02 / P0c-03 / P0c-04 — framework data model + placer landed

- **Front-direction convention.** The placer derives the parent
  building's "front" from `Building.getRotation()` using the
  same convention the building placer uses (NONE → SOUTH-facing).
  Doc 02 §"Placement algorithm" doesn't specify how to read the
  front; this is the existing in-codebase convention from the
  decoration emitter and the building placer.
- **Probe-order resolution.** `FaceProbeOrder.BACK_FIRST` returns
  the face sequence `(back, left, right)` per doc 02
  §"Open decisions". `SIDES_FIRST` returns `(left, right, back)`
  for the rare case (currently only `HOMESTEAD_BEES`) where a
  side face is preferred. Front is never returned — the placer
  treats front-of-parent as off-limits per doc 02 §B.2.
- **Plot half-extents are facing-matched.** The plot's
  `defaultHalfWidthX` / `defaultHalfLengthZ` are declared in the
  type's local frame. The placer swaps them when the chosen face
  is on the parent's local X axis so the outward dimension is
  always the plot's longer dimension. `AdjunctPlot.halfWidthX` /
  `halfLengthZ` on the persisted record are in the world frame
  after that swap, matching the `BlockPos` origin.
- **Slope sampling uses the world heightmap.** The placer reads
  `level.getHeight(MOTION_BLOCKING_NO_LEAVES, x, z)` over the
  proposed footprint. Origin Y is the median sample, matching
  the building placer's pad-Y convention. Slope tolerance is
  per-type (1 for tight herb gardens, 4 for loose paddocks).
- **No NBT stamping or piece-kit assembly happens at this
  phase.** The placer materialises plot bounds only; subsystems
  07 (industry), 08 (gardens), and 11 (homesteading) read the
  persisted `AdjunctPlot` record and stamp content from their
  own realisers when they ship. Each `AdjunctPlotType` carries
  a `PlacementStrategy` enum (`NBT` or `PIECE_KIT`) so those
  subsystems can route to the right materializer without
  re-checking type IDs.
- **HOUSE → HOMESTEAD_* not registered.** Doc 02 §"Building →
  AdjunctPlot spec" calls those out as conditional (probability
  + tier gate). The static registry doesn't model conditional
  registrations, so subsystem 11 will register at realisation
  time via a separate code path. The four `HOMESTEAD_*` enum
  values are still authored on `AdjunctPlotType` so subsystem
  11's plumbing only needs to add the conditional logic.
- **FARMER intentionally has no spec.** FarmPlot (subsystem 10)
  handles farm production at village-territory scale; AdjunctPlot
  is building-scale. Documented in the registry's javadoc.
- **Realiser pipeline insertion.** `VillageSpawner.java` line
  ~315 (post-edit), between `VillageDecorator.decorateVillage(...)`
  and the Phase 0b `DecorationPass.run(...)`. Doc 02
  §"Ordering dependencies" specified "after BuildingRealiser /
  BuildingFoundation, before DecorationPass" — this is the
  closest fit in the current pipeline shape.
- **Cleanup hook.** `VillageSavedData.removeBuilding(Building)`
  now calls `removeAdjunctPlotsForBuilding(building.getId())`
  before marking dirty, so orphaned plots can't outlive their
  parent.
- **`/liv adjunct list` debug command deferred.** Doc 00
  §"Debug commands" spec'd it; the prompt deferred it to Phase
  1 alongside content registration so the command has something
  meaningful to show. The persistence layer is in place to
  support it whenever it lands.
