# 05 — Street Furniture

## Purpose

Fill road margins and gaps between buildings with small, culture-
appropriate pieces so streets don't read as blank corridors between
structures. This is the first real content pass that uses the decoration
framework.

## Design

### Kit contents (per culture)

```
Small pieces (1×1 to 2×2 footprint)
  bench_variants        wooden plank bench, stone bench, log bench
  planter_variants      flower barrel, flower pot, wooden tub with sapling
  hitching_post         fence + lead marker
  mounting_block        stair block + optional signpost
  water_trough          wooden or stone, for horses
  signpost              blank wooden sign; directional signs by hand-placed

Medium pieces (2×3 to 3×3)
  lamppost              post + lantern top (tall)
  handcart              decorative wooden cart with optional barrel
  stacked_crates        inventory-implication prop
  woodpile              seasonal accent; use spruce or oak logs
  notice_board          lectern-variant (for roads away from the square)

Large pieces (3×3 to 5×5)
  well_variant          secondary well on the edge of town; hamlets
  small_shrine          wayside religious marker
  communal_oven         shared bake oven prop
```

Every piece is a single NBT stamped at the slot position. No piece-kit
assembly at this scale; keep authoring light.

### Culture palettes

Initial four cultures (matching NPC Phase 5 doc 31):

| Culture   | Bench    | Planter       | Lamppost         | Shrine         |
|-----------|----------|---------------|------------------|----------------|
| default   | oak      | barrel+flower | fence+lantern    | stone cairn    |
| nordic    | spruce   | log tub       | birch+lantern    | rune stone     |
| highland  | birch    | stone pot     | stone+torch      | cross-stone    |
| imperial  | polished | urn+flower    | stone+glowberry  | niche+statue   |

Fallback chain: `{culture}/decoration/street_furniture/{name}` →
`default/decoration/street_furniture/{name}` → skip.

### Placement

Consumes slots emitted by the uniform decoration emitter:

- `ROAD_SIDE_SMALL` — benches, planters, hitching posts, mounting
  blocks, water troughs, signposts
- `ROAD_SIDE_LARGE` — lampposts, notice boards, handcarts, woodpiles
- `BUILDING_GAP` — stacked crates, shrines, small wells
- `FACADE_ORNAMENT` — planters, lanterns (ground-adjacent)

Density from the emitter's default (one slot every 6 blocks of road
centerline, alternating sides) gives enough furniture without
overwhelming. Quality scoring by distance to town square biases higher-
quality pieces near the center.

### Tier gating

Hamlets get a minimal subset (bench + planter + signpost only).
Villages add lampposts and notice boards. Towns add handcarts, woodpiles,
wells. Cities add shrines, communal ovens, mounting blocks.

Gating is enforced by DecorationProfile `minTier` field.

## Data structures

Reuses `DecorationProfile` from subsystem 01. No new persisted records.

Registration table lives in `StreetFurnitureKitRegistry`:

```java
public final class StreetFurnitureKitRegistry {
    static { registerDefaults(); }

    private static void registerDefaults() {
        for (String culture : CULTURES) {
            registerPiece(culture, "bench_1",
                DecorationTag.ROAD_SIDE_SMALL, tier(HAMLET));
            registerPiece(culture, "planter_1",
                DecorationTag.ROAD_SIDE_SMALL, tier(HAMLET));
            registerPiece(culture, "lamppost_1",
                DecorationTag.ROAD_SIDE_LARGE, tier(VILLAGE));
            // ...
        }
    }
}
```

## Integration points

- **DecorationFramework (subsystem 01)**: all street furniture placement
  goes through DecorationMatcher. No direct placement.
- **CultureResolver**: piece path resolution via existing fallback chain.
- **VillageBiomeStyle**: material-substitution pass runs after NBT
  stamp, same as building placement.
- **VillageSizeTier**: minTier gating per piece.

## Behavior contract

### Does

- Populate road margins with consistent, culture-appropriate content.
- Scale density and variety by village tier.
- Avoid overlap with AdjunctPlots, existing buildings, and paved plazas.
- Use the uniform emitter's density so all layouts get similar coverage.

### Does not

- Place pieces in building interiors or on rooftops.
- Interact with NPC schedules (hitching posts don't pathfind horses).
- Animate or change state (lampposts don't light at dusk in this rework).

## Edge cases

- **Road ends abruptly at a building face.** The last road-side slot
  before the wall is suppressed by the emitter (no facing direction).
- **Road borders a river or cliff.** Slot emission on the water/void
  side is suppressed; single-sided density is the result.
- **Building gap too narrow (< 3 blocks).** BUILDING_GAP slot emission
  skips narrow gaps.
- **Culture without kit content.** Default culture fills in. If default
  is also missing, the slot burns silently. A warning-once-per-piece
  log entry helps authors notice gaps.

## Ordering dependencies

- Requires subsystem 01 complete (DecorationFramework).
- Requires all building placement complete so gap detection works.
- Runs as part of the standard DecorationPass sweep.

## Open decisions

- **Initial kit size.** Proposed: 6 pieces per culture at v1 (bench,
  planter, lamppost, signpost, notice board, handcart). Expand after
  first in-world tests show density feels right.
- **Seasonal woodpile presence.** Out of scope for this rework —
  woodpiles appear year-round in v1.
- **Multiple variants per piece.** Proposed: at least 2 variants for
  bench and planter (the highest-visibility pieces). One variant is
  acceptable for lower-visibility pieces.

## Does-not-include

- Seasonal variants — polish pass.
- Interactive furniture (sittable benches) — depends on NPC animation
  work.
- Painted/signposted pieces (directional signposts with real village
  names) — subsystem 06 handles welcome markers; directional signage
  is deferred.

## Revision notes

(Changes recorded here as the spec evolves.)
