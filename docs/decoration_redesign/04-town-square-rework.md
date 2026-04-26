# 04 — Town Square Rework

## Purpose

Replace the current `TownSquarePlacer` (reduced to a 1-block-radius well
during the zoning rework) with a `TownSquareComposer` that produces a
real civic hub. The square should feel like a destination: paved, sized
to village tier, populated by NPCs during social time, and visually
coordinated with the surrounding civic ring.

## Design

### Composition model

The square is no longer a single placed structure. It is a
**composition** of sub-features drawn from a culture-specific kit:

```
Core kit
  paving            (biome-style material, specific pattern per culture)
  central feature   (well / fountain / statue / monument)
  perimeter edging  (optional raised border)

Furniture kit
  benches           (2-4 per square, oriented inward)
  planters          (corner positions, scaled with tier)
  notice board      (one per square)
  gazebo / pavilion (town+ tier only)
  monument / obelisk (city tier only)
  vendor zone       (reserved flat space; activated by MARKET_DAY event)

Lighting kit
  lampposts         (perimeter, tier-dependent density)
  corner lanterns   (city tier)
```

### Tier scaling

Each `VillageSizeTier` picks a different subset:

| Tier    | Diameter | Central Feature  | Benches | Gazebo | Monument |
|---------|----------|------------------|---------|--------|----------|
| HAMLET  | 7×7      | well             | 0       | no     | no       |
| VILLAGE | 11×11    | well             | 2       | no     | no       |
| TOWN    | 17×17    | fountain         | 4       | yes    | no       |
| CITY    | 25×25    | fountain + monument | 4    | yes    | yes      |

Diameter drives the outer ring road geometry already in place from the
zoning rework — `placementRing = civicInnerEdge + maxCivicFrontFace/2`.
The composer sets `plazaRadius` accordingly and the existing ring road
primitive handles the rest.

### Sub-slot emission

The composer emits its own decoration slots inside the plaza interior:

```
FOUNTAIN_CENTER     one slot, center of plaza, quality 100
MONUMENT_ACCENT     city tier only, slightly offset from center
GAZEBO_SPOT         town+ tier, off-axis to avoid blocking view
BENCH_PERIMETER     N slots (2-4) at fixed angles, facing inward
NOTICE_BOARD        one slot, along the road-facing edge
LAMP_CORNER         4 slots, corners of the plaza
FLOWERBED_EDGE      perimeter slots between benches (tier-gated)
VENDOR_ZONE         reserved flat slot, stays empty until MARKET_DAY
```

These slots are `DecorationTag.PARK_FEATURE` slots with additional
piece-specific sub-tags. `DecorationMatcher` consumes them the same way
it consumes road-side or building-adjacent slots.

### NPC gathering points

Each placed sub-feature registers a **gathering point** on the `Village`
record. Gathering points are named positions that NPC social goals
(NPC Phase 2 hobby activities + weekly schedule) can target:

```java
village.addGatheringPoint(
    new GatheringPoint(
        id,
        locationPos,
        GatheringPointKind.BENCH,       // or FOUNTAIN, GAZEBO, NOTICE_BOARD, VENDOR_ZONE
        capacity))                      // how many NPCs can occupy at once
```

Kinds the NPC layer is expected to consume:

- `BENCH` — sit-and-chat, 1 NPC per bench (animation pending NPC rework)
- `FOUNTAIN` — edge-stand, 2-3 NPCs
- `GAZEBO` — small-group gather, 3-5 NPCs
- `NOTICE_BOARD` — read-posts, 1 NPC at a time
- `VENDOR_ZONE` — stall-run, used by merchants during market day

This plan registers the points; the NPC plan's hobby system consumes
them. The decoration rework does not own the NPC behavior.

### Existing plaza geometry — migration

The zoning rework already uses:
- `plazaRadius = TownSquarePlacer.RADIUS + 2`
- `ringRoadRadius = plazaRadius + 3`
- `civicInnerEdge = ringRoadRadius + 3 + 2`
- `placementRing = civicInnerEdge + maxCivicFrontFace/2`

The composer replaces `TownSquarePlacer.RADIUS` with a tier-derived
value (3, 5, 8, 12 for hamlet/village/town/city). Everything downstream
of `plazaRadius` updates automatically.

## Data structures

```java
public enum TownSquareTier { HAMLET, VILLAGE, TOWN, CITY }

public record TownSquareKit(
    String culture,
    TownSquareTier tier,
    Identifier pavingNbtOrNull,
    Identifier centralFeatureNbt,
    List<Identifier> benchVariants,
    Identifier gazeboNbtOrNull,
    Identifier monumentNbtOrNull,
    Identifier noticeBoardNbt,
    Identifier lampNbt,
    Identifier flowerbedNbtOrNull
) {}

public record GatheringPoint(
    UUID id,
    BlockPos pos,
    GatheringPointKind kind,
    int capacity
) {}

public enum GatheringPointKind {
    BENCH, FOUNTAIN, GAZEBO, NOTICE_BOARD, VENDOR_ZONE
}
```

Gathering points live on the `Village` record as
`List<GatheringPoint>`, persisted via codec.

## Integration points

- **Replaces `TownSquarePlacer`**. All existing callers updated to
  `TownSquareComposer.place(...)` with the same signature.
- **Reuses zoning geometry** — `plazaRadius`, `ringRoadRadius`,
  `placementRing`, `civicRingRadius` fields remain, driven by tier.
- **Emits DecorationSlots** via the decoration framework (subsystem 01).
  Sub-feature placement happens through DecorationMatcher.
- **Registers GatheringPoints on Village** — consumed by NPC Phase 2
  hobby activities.
- **Interacts with MARKET_DAY event** — vendor zone reserves space that
  the event effects system activates with stalls.
- **Kits per culture** resolve via CultureResolver fallback chain:
  `{culture}/town_square/{tier}/*` → `default/town_square/{tier}/*`.

## Behavior contract

### Does

- Scale size and feature set by village tier.
- Use biome-and-culture-specific paving and furniture.
- Register named gathering points for the NPC social layer.
- Emit sub-slots for internal decoration through the standard matcher.
- Preserve the existing zoning ring geometry.

### Does not

- Place buildings. Civic buildings still placed by the building matcher
  at `placementRing` distance.
- Own NPC behavior. Goals belong to the NPC plan.
- Decorate for events directly. Events consume the VENDOR_ZONE and
  FESTIVAL_GROUND (subsystem 13) reserved space.
- Retry composition. If a sub-feature can't place (terrain, overlap),
  it drops silently.

## Edge cases

- **Plaza overlaps a ridge or terrain fault.** The composer uses the
  existing `medianGroundY` to pick a single pad Y. Steep terrain is
  rejected at the recipe level, not handled here.
- **Plaza on water.** Recipes already reject this. If it slips through,
  the composer places the central feature on a small pad and the
  decoration fails at the sub-feature level.
- **City-tier square in a walled enclave layout.** The enclave layout
  explicitly overrides town square radius; the composer honors that
  override and uses the smallest tier-matching kit.
- **Very small hamlet (1–3 buildings).** Composer uses HAMLET kit;
  gazebo and monument slots are skipped; only the well + notice board.

## Ordering dependencies

- Runs during `ShapeRecipe.compose` at the town square placement step,
  same position as `TownSquarePlacer` today.
- Must run before `DecorationSlotEmitter` sweep so that PARK_FEATURE
  sub-slots are already in place when the uniform emitter runs.
- Gathering point registration requires the `Village` record to exist,
  which it always does by this point.

## Open decisions

- **Tier override hook.** Should individual layouts (ENCLAVE, GROVE)
  be allowed to force a smaller-than-default tier? Proposed: yes,
  via `TownSquareComposer.placeForced(tier, ...)`. Enclave already
  needs this.
- **GatheringPoint capacity tuning.** Proposed starting values:
  BENCH=1, FOUNTAIN=3, GAZEBO=5, NOTICE_BOARD=1, VENDOR_ZONE=4. Adjust
  after NPC hobby activities are tested.
- **Culture kit completeness.** If a culture has no town_square kit at
  any tier, fall to `default`. If default is missing a TIER entry,
  downgrade to the next tier up with content. Confirm this fallback
  logic is acceptable.

## Does-not-include

- NPC animations for sitting on benches — NPC plan work.
- Dynamic plaza expansion as a village grows — the plaza is tier-
  locked at realisation.
- Player-customizable squares — procedural only.
- Festival-specific decorations — subsystem 13. The VENDOR_ZONE is
  the only reservation for events.

## Revision notes

(Changes recorded here as the spec evolves.)
