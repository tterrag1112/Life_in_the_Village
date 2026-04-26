# 01 — Decoration Framework

## Purpose

Provide a uniform slot/matcher pass that runs after building placement
to add non-building decorative content to a village. Parallel to the
existing `SlotTag` / `PlacementMatcher` system but in its own namespace
so the two matchers don't interfere.

Every piece of decoration content added in later subsystems flows
through this framework: it emits `DecorationSlot`s, registers
`DecorationProfile`s, and the matcher places pieces from content kits
into matching slots.

## Design

### Pipeline

```
VillagePlanner.plan(...) → recipe.compose → matcher → realisers
                                                         ↓
                                                 DecorationPass ←  NEW
                                                         ↓
                                             AdjunctPlotRealiser ← later
                                             SubBuildingResolver ← later
                                             WallRealizer         ← later
```

The decoration pass runs after all building placement and terrain
realisation is complete, because decoration slot emission needs to
see the final positions of buildings, roads, and padded terrain.

### DecorationTag enum

A single enum describing what kind of decoration fits in a given slot.
Tags are intentionally coarse — fine-grained variation comes from the
`DecorationProfile` tier system, not from tag proliferation.

```
// Road-side
ROAD_SIDE_SMALL         bench, planter, small signpost
ROAD_SIDE_LARGE         lamppost, notice board, well
WELCOME_MARKER          edge of village on inbound trade road

// Building-adjacent
BUILDING_GAP            narrow space between two buildings facing street
BUILDING_CORNER_ACCENT  outside-corner slot between angled neighbors
FACADE_ORNAMENT         directly-against-wall ornament (planter, lantern)

// Civic-adjacent
CIVIC_ACCENT            near the town square, quality bias
PARK_FEATURE            inside a GardenPlot or TownSquare interior

// Periphery
VILLAGE_BOUNDARY        outermost ring markers / cairns
ROAD_INBOUND_EDGE       where a trade road enters the built area

// Special-context
GUILD_EMBLEM            attached to the face of a guild hall
TRADE_SIGN              attached to the face of a production building
HEADSTONE               cemetery grave slot
```

If a new subsystem needs a truly new kind of slot, it may extend this
enum. Default behavior: re-use an existing tag.

### DecorationSlot record

```java
public record DecorationSlot(
    UUID slotId,
    BlockPos pos,
    Direction facing,            // toward nearest road/building/feature
    Set<DecorationTag> tags,
    int footprintBudget,         // max piece footprint allowed
    int qualityScore,            // 0-100, context-derived
    UUID parentId,               // building / plot / village; nullable
    List<BlockPos> contextRoad   // nearest road centerline (for orientation)
)
```

Not a building slot — does not interact with `PlacementSlot` /
`LayoutSlot`. Ephemeral: lives only during a DecorationPass invocation.

### DecorationProfile

Parallels `BuildingProfile`. Declares, per decoration piece:

- Which tags it requires / prefers
- Tier weights (primary/secondary/backfill)
- Footprint size
- Culture and biome constraints
- Anchor rules (against-wall, on-ground, etc.)

Registered in `DecorationProfileRegistry` keyed by piece ID. Piece ID
is `{culture}/{category}/{name}` matching the NBT path.

### DecorationMatcher

Single-pass matcher following the existing `PlacementMatcher` pattern:

1. Collect every DecorationProfile that has at least one matching slot
   available
2. Sort candidates by (tier weight + slot quality + preferred tag hits)
3. Commit best match → remove slot → continue
4. Avoidance rules by piece type to prevent clustering

Terrain retry is lighter than the building matcher because decoration
pieces are small and failure modes are limited. If an NBT piece can't
be placed at a slot, burn the slot and continue.

### Uniform slot emission

A single `DecorationSlotEmitter` walks every realised village and
produces a consistent density of slots regardless of layout shape.
Algorithm:

1. For each road centerline, emit `ROAD_SIDE_*` slots every N blocks,
   alternating left/right, quality derived from distance to town square.
2. For each pair of buildings whose edges face the street at ≤ 12 blocks
   apart, emit a `BUILDING_GAP` slot in the middle.
3. For each exterior corner between two buildings at an angle > 30°,
   emit a `BUILDING_CORNER_ACCENT` slot.
4. For each building wall facing a road, emit up to 2 `FACADE_ORNAMENT`
   slots.
5. For the outermost ring of buildings, emit `VILLAGE_BOUNDARY` slots
   at consistent angular spacing.
6. For each trade road endpoint on the village perimeter, emit one
   `WELCOME_MARKER` and one `ROAD_INBOUND_EDGE` slot.

Special-context slots (`GUILD_EMBLEM`, `TRADE_SIGN`, `HEADSTONE`) are
emitted by their owning subsystem, not by the uniform emitter.

## Data structures

```java
enum DecorationTag { ... }         // 15 tags listed above
record DecorationSlot(...)         // as above
record DecorationProfile(...)      // piece ID, tags, tier, footprint, rules
class DecorationProfileRegistry    // static registry, per-culture lookup
class DecorationMatcher            // single-pass matcher
class DecorationSlotEmitter        // uniform emission algorithm
class DecorationPass               // orchestrates emit → match → place
```

## Integration points

- Called from `VillagePlanner` after all realiser passes complete, once
  all buildings and roads are in the world.
- Reads `VillageSavedData` for building list, road centerlines, culture.
- Reads `CultureResolver` for culture-specific piece selection.
- Reads `VillageBiomeStyle` for material fallbacks when piece doesn't
  exist at the culture-specific path.
- Writes nothing to `VillageSavedData` by default. Individual subsystems
  (adjunct plots, cemeteries) may persist their own records.

## Behavior contract

### Does

- Emit a consistent density of slots across every layout shape.
- Match pieces from kits per culture, with fallback to default.
- Run as a single deterministic pass; same seed → same decorations.
- Honor existing protected surfaces (paved plazas, road blocks).

### Does not

- Place anything on building roofs. Facade-ornament slots are ground-
  adjacent only.
- Modify terrain beyond a 1-block pad under placed pieces.
- Retry exhaustively on failure. Burn slots that can't resolve.
- Interact with building placement. Runs strictly after.

## Edge cases

- **Village with zero buildings.** DecorationPass exits cleanly with no
  slots emitted.
- **Village with only a town square.** Town square emits its own
  sub-slots (subsystem 04); the uniform emitter may find no valid
  road-side or building-adjacent positions and emit nothing.
- **Building placed directly on another's footprint (placement bug).**
  The emitter treats the overlap as a single building and skips the
  gap slot that would otherwise appear between them.
- **Culture without any decoration NBTs.** Falls through to default
  culture via CultureResolver. If default also has nothing, slot
  burns silently.
- **Decoration slot on protected surface (plaza paving, road block).**
  Slot is suppressed during emission.

## Ordering dependencies

- Zoning rework must be complete — layouts 2–16 converted — so that
  road centerlines and building positions are stable before the
  emitter walks them.
- Runs after `TownSquarePlacer` (subsystem 04) so that the plaza's
  protected surfaces are in place when slot-emission skips them.

## Open decisions

- **Slot density constant.** Proposed: one road-side slot every 6
  blocks. Revisit after first in-world test.
- **Facade ornament limit per wall.** Proposed: 2. May need reducing
  for small buildings.
- **Culture fallback chain for decoration pieces.** Proposed:
  `{culture}/decoration/{cat}/{name}` → `default/decoration/{cat}/{name}`
  → burn slot. No additional legacy fallback.

## Does-not-include

- Ambient effects (particles, lights, animals) — scope excluded.
- Seasonal or time-of-day variants — later polish.
- Player-placed decoration — decoration is procedural only in this rework.
- Runtime replacement of decorations as village changes — decoration
  pass runs once at realisation and is not re-run.

## Revision notes

(Changes recorded here as the spec evolves.)
