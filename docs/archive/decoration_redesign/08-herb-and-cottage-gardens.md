# 08 — Herb and Cottage Gardens

## Purpose

Small ornamental and functional gardens attached to civic and service
buildings. Complements the industry adjuncts (subsystem 07) for the
non-production side of the village: apothecary, inn, temple, library,
noble manor.

Also covers what I originally called "kitchen gardens" — small food-
producing patches attached to houses in low-tier villages. These
overlap with homesteading (subsystem 11); the distinction is that
subsystem 08 covers *ornamental-primary* gardens, subsystem 11 covers
*production-primary* homesteads.

## Design

### Garden catalog

| Building     | AdjunctPlotType      | Footprint | Character                                                              |
|--------------|----------------------|-----------|------------------------------------------------------------------------|
| APOTHECARY   | HERB_GARDEN          | 5×5       | raised beds with diverse low flowers, composter, shelves, basin        |
| INN          | KITCHEN_GARDEN       | 5×5       | mixed vegetables + herbs + small fruit tree                            |
| TEMPLE       | MEDITATION_GARDEN    | 6×6       | gravel paths, central feature (fountain/statue), sparse flowers        |
| NOBLE_MANOR  | FORMAL_GARDEN        | 10×10     | geometric hedge parterres, statues, gravel paths, central focal point  |
| LIBRARY      | MEDITATION_GARDEN    | 5×5       | contemplative — benches, low hedges, single fruit tree                 |
| GUILD_HALL   | (none by default)    | —         | guild halls get emblems, not gardens                                   |
| TOWN_HALL    | (none by default)    | —         | civic authority — no attached garden                                   |
| HOUSE        | KITCHEN_GARDEN (tiny)| 3×3       | optional, hamlet/village tier only, <30% chance per house              |

### Culture variations

Every AdjunctPlotType has per-culture NBT variants so the same type
reads differently in a nordic village vs. an imperial city. Examples:

```
HERB_GARDEN
  default    raised wooden beds, assorted flowers
  nordic     low stone walls, wild-look mixed herbs, rune stone
  highland   terraced beds, stone borders
  imperial   symmetric stone planters, clipped hedges, small fountain

MEDITATION_GARDEN
  default    simple gravel path, bench, central stone
  nordic     moss garden, standing stones, weathered wood
  highland   cairn-style stones, thistle flowers, cross-stone marker
  imperial   geometric gravel, urn planters, topiary statues

FORMAL_GARDEN
  default    rectangular hedge parterres, statue center
  nordic     simpler form, stone urns instead of statues
  highland   topiary + thistle + formal cross paths
  imperial   full parterre with fountain, urns, topiary, statuary
```

### Piece primitives (shared with public parks, subsystem 09)

Several primitives are reused across gardens:

```
FlowerBed      row or patch of flowers, culture-biome weighted
Hedgerow       line of low bushes/leaves
GravelPath     narrow path between bed sections
StatuePedestal plinth + statue NBT
Pond           small water feature with lily pads
Trellis        fence + vine
Topiary        tall bush in pot
```

These primitives live in `Village/Decoration/Pieces/` and are used by
both this subsystem and subsystem 09. Authoring them once multiplies
value.

### Strategy choice

- HERB_GARDEN, KITCHEN_GARDEN, MEDITATION_GARDEN — **NBT strategy** per
  culture. Small enough that authoring variants is cheap.
- FORMAL_GARDEN — **piece-kit strategy**. Larger, more variation
  desirable, composability matters.

### Low-tier house kitchen garden

HAMLET and VILLAGE tier houses may receive a tiny (3×3) KITCHEN_GARDEN
with base probability 0.3. Picks a random mix of:

- A single crop row (wheat, carrots, or beetroot)
- A composter
- A few flowers
- A small raised bed frame

This is meant to read as "lived-in" rather than produce meaningful
resources. Homesteads (subsystem 11) are the production-focused
equivalent.

## Data structures

No new records beyond `AdjunctPlot`. Registration:

```java
public final class GardenAdjunctRegistry {
    static {
        register(BuildingType.APOTHECARY,  HERB_GARDEN,          5, 5, NBT);
        register(BuildingType.INN,         KITCHEN_GARDEN,       5, 5, NBT);
        register(BuildingType.TEMPLE,      MEDITATION_GARDEN,    6, 6, NBT);
        register(BuildingType.LIBRARY,     MEDITATION_GARDEN,    5, 5, NBT);
        register(BuildingType.NOBLE_MANOR, FORMAL_GARDEN,        10, 10, KIT);
        // HOUSE: random 30% chance in hamlet/village tier only
        registerConditional(BuildingType.HOUSE, KITCHEN_GARDEN,  3, 3, NBT,
            ctx -> ctx.villageTier().isBelow(TOWN)
                && ctx.random().nextFloat() < 0.3f);
    }
}
```

## Integration points

- **AdjunctPlotFramework**: all placement through shared framework.
- **CultureResolver**: per-culture NBT variants.
- **VillageBiomeStyle**: flower selection + material substitution.
- **Pieces package**: shared primitives with subsystem 09 (parks).

## Behavior contract

### Does

- Attach culture-appropriate gardens to specific building types.
- Use shared piece primitives for formal and park gardens.
- Provide random variety among tier-appropriate houses via conditional
  registration.
- Silent-drop on steep terrain.

### Does not

- Grow food that gets harvested. HERB_GARDEN next to apothecary doesn't
  produce apothecary inputs — that's the building's responsibility.
  Gardens are ornamental; homesteads (subsystem 11) are productive.
- Resize. Fixed footprint per type.
- Change content by season.

## Edge cases

- **Building with no face for a 10×10 FORMAL_GARDEN.** NOBLE_MANOR in
  tight layouts drops the garden. Acceptable for urban city cores;
  most manors spawn with room.
- **Culture without any garden variant.** Default fallback via
  CultureResolver. If default missing, plot drops.
- **House with KITCHEN_GARDEN but neighbors too close.** AdjunctPlot
  framework probes faces; if none clear, drops silently. No retry.

## Ordering dependencies

- Requires subsystem 02 (AdjunctPlotFramework).
- Piece primitives in `Pieces/` package must be implemented before the
  FORMAL_GARDEN kit strategy. Others use NBT only and have no such
  dependency.

## Open decisions

- **Fruit tree species per culture.** Proposed: oak sapling for
  default/imperial, spruce for nordic, birch for highland. Purely
  aesthetic.
- **Flower palette per culture × biome.** Proposed: reuse
  `VillageBiomeStyle.flowerState()` weighted selections. Culture
  refines the weights. Single source of truth.
- **House kitchen garden probability.** Proposed: 0.3 in hamlet, 0.2
  in village, 0.0 in town+. Adjust after in-world feel test.

## Does-not-include

- Functional herb harvesting (no apothecary gets mats from HERB_GARDEN).
- Player-plantable garden beds.
- Seasonal variations.
- Scholar-attached gardens — libraries get meditation gardens but
  scholars are the NPC plan's concern.

## Revision notes

(Changes recorded here as the spec evolves.)
