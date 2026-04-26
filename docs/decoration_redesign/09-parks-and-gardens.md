# 09 — Parks and Public Gardens

## Purpose

Village-scale public open spaces. A park is to a town square what a
town square is to a lamppost: the scale up. Large areas (20×20 to 40×40)
that sit within or at the edge of a village, planted with flowers and
trees, laced with paths, and furnished with benches and features.

Parks are distinct from AdjunctPlots (attached to buildings) and from
TownSquare (single civic plaza). They occupy dedicated plots in the
village layout, similar to FarmPlot but inside the built area.

## Design

### When a village gets a park

Not every village has a park. Generation rules:

- HAMLET: never
- VILLAGE: rarely (5% chance, only if layout has open space)
- TOWN: sometimes (30% chance)
- CITY: always (100%)

A village may have multiple parks at CITY tier if space allows. Parks
are placed by the shape recipe during compose, using a `GARDEN_PLOT`
slot emitted at appropriate positions (see placement below).

### ParkStyle catalog

Similar to Village gardens but public-scale. Per culture:

| ParkStyle         | Character                                                      |
|-------------------|----------------------------------------------------------------|
| COTTAGE_GREEN     | default — open lawn, wildflower patches, scattered trees       |
| FORMAL_PARK       | imperial — gravel promenades, hedge borders, statue plaza      |
| ZEN_GARDEN        | exotic/japanese — sand, boulders, moss, pond with bridge       |
| SACRED_GROVE      | druidic/nordic — standing stones, preserved trees, shrine      |
| MEMORIAL_PARK     | any — commemorative; obelisks, cypress trees, plaque stones    |

Memorial parks overlap with cemeteries (subsystem 14) — a memorial
park may contain or adjoin a cemetery. Treat them as separate plots
that can be placed adjacent.

### Piece primitives (shared with subsystem 08)

All park content assembles from shared primitives:

```
FlowerBed      — patches of flowers with edge treatment
Hedgerow       — lines of bushes
GravelPath     — walkways through the park
StatuePedestal — 2-3 blocks tall, center or path intersections
Pond           — 3×3 to 5×5 water feature with lily pads
Trellis        — arched trellis with vines
Topiary        — stylized trees in pots
StandingStone  — single monolith, sacred grove variant
WoodenArch     — entry arch for cottage green
Bench          — same as street furniture subsystem
Lamppost       — perimeter lighting
```

Assembly per style is described in a `ParkAssembler` class that
composes primitives according to a style-specific rule set. No single
NBT — parks are large enough that full-NBT authoring is unrealistic.

### Placement in the layout

Shape recipes emit `GARDEN_PLOT` slots during compose for parks:

- RADIAL / PLAZA / CROSSROADS: park slot at the outer edge of the civic
  ring, in a cardinal direction not taken by a spur road
- DUAL_PLAZA: one park between the two plazas
- RIVERINE: park along the river bank inside the village bounds
- TERRACED: park on a wider terrace level
- HILLTOP: small park on a flat saddle
- Others: park slot if the layout has a viable open area

GardenPlots are registered on `VillageSavedData` alongside FarmPlots
and are realized by a `ParkRealiser` running after buildings but before
the decoration pass.

### Terrain

Parks minimally edit terrain — the ParkAssembler follows the ground
rather than flattening it. Paths terrace where slope requires (same
primitive as farm plot terracing, subsystem 10). Trees and flowers
tolerate slope naturally.

Water features (ponds) require locally-flat terrain; if no such spot
exists, the pond is dropped from the composition.

## Data structures

```java
public record GardenPlot(
    UUID plotId,
    UUID villageId,
    BlockPos origin,
    int halfWidthX,
    int halfLengthZ,
    ParkStyle style,
    String culture
) {}

public enum ParkStyle {
    COTTAGE_GREEN, FORMAL_PARK, ZEN_GARDEN,
    SACRED_GROVE, MEMORIAL_PARK
}

public interface ParkPiecePrimitive {
    void emit(ParkContext ctx, Random rng);
}
```

GardenPlot persists on VillageSavedData. ParkPiecePrimitive
implementations are code-only, not persisted.

## Integration points

- **Shape recipes**: emit GARDEN_PLOT slots in compose for tier-gated
  villages. The matcher treats them as reserved space, not placeable.
- **ParkRealiser**: reads GardenPlot records, runs ParkAssembler for
  each, places pieces.
- **Pieces package**: shared with subsystem 08.
- **NPC hobby activities (NPC Phase 2)**: park locations register as
  social hangout targets, same GatheringPoint mechanism as town square.
- **VillageBiomeStyle**: flowers and materials biome-appropriate.

## Behavior contract

### Does

- Generate one park per village at TOWN or above with appropriate
  probability.
- Use piece-kit assembly to adapt each park to its plot size and
  terrain.
- Register the park as a social hangout location.
- Honor the culture's style preference.

### Does not

- Flatten the terrain where the park sits.
- Produce resources (parks are purely ornamental/social).
- Include buildings within the park bounds.
- Re-generate parks at runtime.

## Edge cases

- **No valid open space for a park in a CITY layout.** Park probability
  drops to 0 for that village. City without a park is uncommon but
  acceptable.
- **Park plot ends up on steep terrain.** ParkAssembler terraces the
  paths and drops the pond feature. Works in most cases.
- **Park adjacent to a cemetery plot (subsystem 14).** Both are placed;
  they visually blend via shared Hedgerow boundary. Allow this.
- **Park where a trade road would go.** Trade Route 7a/b routes around
  the park's reserved slot. If route planning was done before park
  placement (rare), park drops.

## Ordering dependencies

- Requires subsystem 02 (Pieces primitives shared with gardens).
- Must run after shape recipe compose and before decoration pass.
- NPC hobby activity integration depends on NPC Phase 2 complete.

## Open decisions

- **Park size per village.** Proposed: 20×20 for TOWN, 30×30 for CITY.
  Confirm after test.
- **Multiple parks per CITY.** Proposed: up to 2 parks if the layout
  has two suitable slots. Simpler to ship one-per-village and iterate.
- **Park vs. cemetery adjacency.** Proposed: adjacency allowed,
  sharing boundary hedgerow. Disallow overlap.
- **Which cultures use which styles as default.** Proposed mapping:
  default → COTTAGE_GREEN, nordic → SACRED_GROVE, imperial →
  FORMAL_PARK, highland → SACRED_GROVE.

## Does-not-include

- Player-placed parks.
- Seasonal park changes.
- Park wildlife (deer, rabbits) — scope excluded.
- Park events (concerts, picnics) — could hook into NPC Phase 5 events
  later.

## Revision notes

(Changes recorded here as the spec evolves.)
