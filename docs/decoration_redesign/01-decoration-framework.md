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

### P0b-01 / P0b-02 / P0b-05 — data model landed

- `DecorationTag` ships with 13 values per the §"DecorationTag enum"
  list (the data-structures sketch said "15" but only 13 names
  appear in the spec; matched the named list).
- `DecorationSlot.qualityScore` is clamped to 0..100 in the
  canonical constructor rather than rejecting out-of-range values.
  Emitters that compute scores probabilistically may overshoot
  slightly; clamping is more forgiving than throwing.
- `DecorationProfile.biomeConstraint` uses
  `Optional<net.minecraft.resources.Identifier>` instead of the
  spec's `Optional<Biome>`. Vanilla `Biome` doesn't have a
  stand-alone codec (it requires registry context), and storing the
  resource location works for the matcher's "is this slot's biome
  the constrained one?" check without needing a registry-bound
  `Holder<Biome>` lookup at registration time. The matcher resolves
  via `level.getBiome(pos).unwrapKey()` and compares.
- `DecorationProfileRegistry.eligibleFor` returns the empty list
  when the slot carries zero tags. Doc 01 §"DecorationSlot" frames
  tags as the "what kind of decoration fits here" signal; an empty
  set carries no signal, so nothing matches and the matcher's
  burn-the-slot policy kicks in.
- The registry's culture fallback mirrors `CultureResolver` (and
  `VariantSelector`): a same-culture hit returns only same-culture
  profiles; if none, the matcher falls through to `"default"`
  culture; otherwise empty. There is no further legacy fallback —
  doc 01 §"Open decisions" already settled on this two-step chain.

### P0b-03 / P0b-04 — emitter + matcher + pass landed

- **Slot UUIDs are deterministic.** Doc 01 mandates "same seed →
  same decorations". `UUID.randomUUID()` would break that, so the
  emitter derives slot ids via `UUID.nameUUIDFromBytes(villageId
  + algorithm + key + pos)`. Re-running the emitter on the same
  village produces the same slot list with the same ids.
- **Trade-road endpoint detection.** Doc 01 §A.6 says "for each
  trade road that connects to this village" but post-realisation
  the codebase doesn't yet expose a first-class `TradeRoad → Village`
  endpoint accessor. The emitter approximates via `Village
  .getMainGateEndpoint()` + `getCapitalGatePositions()` — every
  gate is the intersection of an inbound road with the built
  area, which is what the doc cares about. When a proper
  trade-road endpoint accessor lands the emitter swaps over with
  no algorithm change.
- **No concave-hull computation.** Doc 01 §A.5 mentioned a hull
  but the v1 emitter sidesteps the algorithm: for each angular
  step (15°), it finds the outermost building dot-producting in
  that direction and emits a marker just beyond it. Cheap,
  deterministic, doesn't need a hull library; produces a result
  visually equivalent to "ring of markers around the village
  perimeter" for any reasonable building distribution.
- **Building-gap / corner / facade rely on rotation convention.**
  The emitter assumes the placer's `Rotation.NONE → SOUTH-facing`
  convention. If a recipe ever places buildings whose recorded
  rotation doesn't reflect the structure's authored front face,
  facade slots will project from the wrong wall. Worth flagging
  in the next prompt's validation.
- **`Building` geometry is rotation-only.** Doc 01 talks about
  "facade angle" being arbitrary, but the codebase only carries
  cardinal rotations (NONE / 90 / 180 / 270). Corner-accent
  detection therefore uses a 90-degree facing-difference
  threshold derived from rotation rather than a true angular
  measure. Adequate for cardinally-rotated buildings; imprecise
  for any future free-rotation work.
- **Slot occupancy not checked at emit time.** Doc 01 §"Behavior
  contract" says "Honor existing protected surfaces (paved
  plazas, road blocks)" but the v1 emitter doesn't query the
  world during emission — it stays a pure geometric pass. Slots
  that overlap protected surfaces fall through the matcher's
  burn-the-slot policy when the NBT stamp can't actually place
  there. Worth revisiting once we have profiles registered and
  can observe the failure rate.
- **`DecorationPass` insertion point.** `VillageSpawner.java` line
  ~317, after `VillageDecorator.decorateVillage(...)` and before
  `TradeRouteManager.establishRoutes(...)`. Phase 0c (AdjunctPlot)
  and 0d (Subbuilding) realisers will land between the legacy
  decorator and the new pass when they ship.
- **Placements ARE persisted; slots are NOT.** The pass clears
  prior placements for the village id at start-of-run so re-runs
  from the eventual debug command produce a fresh placement list
  rather than accumulating duplicates. Slot UUIDs being
  deterministic means a re-run produces identical slotId values
  — even though slots themselves are ephemeral, the placements'
  `slotId` field round-trips meaningfully.
- **Per-village culture lookup is a TODO.** `DecorationPass`
  currently passes `DEFAULT_CULTURE` because `VillageSavedData`
  doesn't expose a per-village culture accessor. When such an
  accessor lands (via `VillageTypeData` lookup or a per-village
  cached field), `cultureFor(village)` becomes a one-line edit.
