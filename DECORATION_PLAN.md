# Decoration Rework — Master Plan

## Purpose

This rework closes the remaining structural gaps between "functional
village" and "settled, beautiful, lived-in village." It does not add
ambient polish (particles, scripted lantern lighting, wild animals,
seasonal shifts). Polish passes come later, after the world generates
with functional and beautiful kingdoms and villages populated by NPCs
that the player can meaningfully interact with.

The rework now also **absorbs the zoning rework** (layouts 2–16
conversion to slot/matcher pattern + `ZoneRegistry` deletion), because
the building variants system needs `BuildingProfile` to be variant-
keyed and converting layouts twice would be wasted work.

## Scope

**In scope:**
- Building variants and per-building colors (with zoning rework absorbed)
- Decoration framework (DecorationTag + DecorationMatcher pass)
- AdjunctPlot framework (attached plots next to specific buildings)
- Subbuildings (anchor-block scanned regions within buildings)
- Town square rework (composable, populated, NPC-hooked)
- Street furniture (benches, planters, lampposts as static pieces)
- Signage and markers (guild emblems, trade signs, welcome markers)
- Industry adjuncts (forge yards, drying racks, etc.)
- Herb and cottage gardens (building-scale AdjunctPlots)
- Parks and public gardens (village-scale)
- Farm plot rework (terrain-following fields, hedgerows, territories)
- Homesteading (attached homestead plots + family roles)
- Village walls (polyline-planned, NBT-kit realized)
- Festival grounds (reserved plot + event-specific kits)
- Cemeteries (small attached plots tied to village history)

**Out of scope (may be revisited later):**
- Bridges → handled in Trade Route 7b
- Ambient particles (forge smoke, oven steam, chimney smoke)
- Decorative live animals (cats, dogs, pigeons, moths)
- Scripted lantern lighting / open-business indicators
- Seasonal garden shifts, weather-reactive decorations
- Laundry, firewood piles, tool props ("signs of habitation" clutter)

## Phases

### Phase 0 — Foundation

The framework + variant layer. Everything in later phases depends on
these. This phase is the largest because it absorbs the zoning rework.

- `15-building-variants-and-colors.md` — variant system + color +
  absorbs zoning rework
- `01-decoration-framework.md` — DecorationTag, DecorationMatcher
- `02-adjunct-plot-framework.md` — attached plot pattern
- `03-subbuildings.md` — anchor-block scanning, subbuilding registry

Document 15 numerically follows 14 because it arrived later in design,
but it is the **first to implement**. Read it first.

### Phase 1 — Public spaces

The most player-visible pieces of a village.

- `04-town-square-rework.md` — composable square + NPC gathering hooks
- `05-street-furniture.md` — kit of small static pieces along roads
- `06-signs-and-markers.md` — guild emblems, trade signs, welcome markers

### Phase 2 — Working yards

AdjunctPlot content per production building.

- `07-industry-adjuncts.md` — forge yard, drying rack, kiln, paddock, etc.
- `08-herb-and-cottage-gardens.md` — apothecary, inn, temple, manor gardens

### Phase 3 — Landscape

Large terrain-interacting features.

- `09-parks-and-gardens.md` — village-scale public parks
- `10-farm-plot-rework.md` — terrain-following fields, hedgerows, terracing
- `11-homesteading.md` — household-scale production AdjunctPlots + roles

### Phase 4 — Defenses and history

The last set — each depends on specific upstream work.

- `12-village-walls.md` — polyline + NBT wall kit
- `13-festival-grounds.md` — requires NPC Phase 5 events
- `14-cemeteries.md` — requires NPC Phase 2 death arcs + Phase 4 history

## Subsystem → phase mapping

| Doc | Subsystem | Phase |
|---|---|---|
| 15-building-variants-and-colors | Variants, color, zoning absorption | 0 |
| 01-decoration-framework | DecorationTag + DecorationMatcher pass | 0 |
| 02-adjunct-plot-framework | AdjunctPlot attached plot system | 0 |
| 03-subbuildings | SubBuilding anchor scan + registry | 0 |
| 04-town-square-rework | TownSquareComposer + sub-slots | 1 |
| 05-street-furniture | Road-adjacent static piece kit | 1 |
| 06-signs-and-markers | Guild emblems, trade signs, welcome markers | 1 |
| 07-industry-adjuncts | Per-profession AdjunctPlot content | 2 |
| 08-herb-and-cottage-gardens | Per-civic-building garden AdjunctPlots | 2 |
| 09-parks-and-gardens | Public park GardenPlot type | 3 |
| 10-farm-plot-rework | FieldPatch, Hedgerow, FarmTerritory, Terracing | 3 |
| 11-homesteading | Homestead AdjunctPlots + FamilyRole additions | 3 |
| 12-village-walls | WallPlan + WallRealizer | 4 |
| 13-festival-grounds | FESTIVAL_GROUND plot + festival kits | 4 |
| 14-cemeteries | Cemetery plot + headstone NBTs | 4 |

## Cross-system dependencies

Items in the decoration rework that depend on features outside this
rework:

- **Homesteading** requires NPC Phase 3 (`23-economic-channels`) for
  the DirectBusinessChannel that routes homestead sales, and Phase 4
  (`25-resource-categories`) for the production contribution.
- **Festival grounds** requires NPC Phase 5 (`32-events-expanded`) for
  the event location resolver and attendance logic.
- **Cemeteries** requires NPC Phase 2 (`15-child-elderly-arcs`) for
  death events and Phase 4 (`30-village-history`) for named deceased
  records.
- **Building variants age preference** uses NPC Phase 4
  (`30-village-history`) for the village age category. Decoration
  rework can ship variants with `agePref: ANY` until that's available;
  weighted age selection turns on once history exists.

## Overlaps with existing redesigns — not in this plan

These were candidates but belong to adjacent work:

- **Bridges** — moved to Trade Route Phase 7b.
- **Route events (bandits, travelers, adventurers)** — Trade Route 7b.
- **NPC-NPC social gathering at town square** — NPC Phase 2 hobby
  activities. This plan provides the spatial slots; the NPC plan
  provides the behavior.
- **Business greeting at shops** — NPC Phase 3 doc 24.
- **Scholar / Herald / Scribe professions** — NPC Phase 2 doc 17.
- **Inn-room visitor assignment** — NPC Phase 4 doc 29. Subbuildings
  provide the target regions; visitor flux does the assignment.
- **NPC-triggered building auto-recolor** — implementation in NPC
  Phase 3 economic channels; this rework reserves the integration
  hook.

## Open decisions

Captured in per-subsystem docs. Top-level questions:

- **Where in the project do these docs live?** Assumed project root in
  a `decoration-docs/` directory parallel to `npc-docs/`. Change if
  the convention differs.
- **DecorationTag as a separate enum from SlotTag.** Confirmed in prior
  conversation. Keeps the building matcher and decoration matcher
  cleanly separated.
- **Subbuilding pathfinding — door-only targets.** Confirmed.
- **AdjunctPlot as the framework name.** Confirmed.
- **Variant style scope at launch.** Default culture, RURAL + URBAN.
  Confirmed.
- **Color slots.** Three: PRIMARY, ACCENT, ROOF. Confirmed.

## Sequencing across all reworks in flight

The full ordering is now:

1. **This rework, Phase 0** (variants + decoration framework + adjunct
   framework + subbuildings, with zoning absorption built in)
2. Kingdom planning Slice C — regional viability + deep-inspection
3. Trade Route rework — Phase 7a, 7b, 7c
4. NPC rework — Phase 0 through Phase 5
5. **This rework, Phases 1–4** (the rest of decoration)

Phase 0 of the decoration rework moves to the **front of the queue**
because the variant + zoning work changes BuildingProfile in ways the
other reworks shouldn't have to track separately. Everything else here
waits its turn after Trade Route + NPC ship.

## Revision notes

- Initial plan written without variant/color system; doc 15 added
  later and inserted as Phase 0.
- Zoning rework formerly listed as separate pre-rework dependency;
  absorbed into doc 15's Phase 0 because the variant system requires
  variant-keyed BuildingProfile and converting layouts twice would
  be wasted.
- Sequencing reordered: Phase 0 of this rework precedes Kingdom Slice
  C, Trade Route, and NPC reworks.
